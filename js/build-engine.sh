#!/usr/bin/env bash
# Build the DSH-Android engine tree and package it as engine.zip.
#
#   js/build-engine.sh            # -> $OUT_DIR/engine.zip, engine.sha256, engine-version.txt, size-report.txt
#
# Steps: npm ci (exact lockfile, production, no install scripts) -> add our
# packages -> add the sharp WebAssembly build -> apply patches/ -> prune ->
# verify (no symlinks, patches present) -> deterministic zip.
#
# Env overrides: OUT_DIR (default <agent>/toolchains/out/engine),
#                WORK_DIR (default <agent>/toolchains/out/engine-build),
#                TMPDIR (default <agent>/toolchains/tmp; /tmp is not used).
set -euo pipefail

JS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_DIR="${AGENT_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"  # repo root; toolchains/ is gitignored
OUT_DIR="${OUT_DIR:-$AGENT_DIR/toolchains/out/engine}"
WORK_DIR="${WORK_DIR:-$AGENT_DIR/toolchains/out/engine-build}"
export TMPDIR="${TMPDIR_OVERRIDE:-$AGENT_DIR/toolchains/tmp}"
mkdir -p "$TMPDIR" "$OUT_DIR" "$WORK_DIR"

STAGE="$WORK_DIR/stage"
NM="$STAGE/node_modules"
HOST_NATIVE="$WORK_DIR/host-native"

log() { printf '\033[1m[build-engine]\033[0m %s\n' "$*" >&2; }
die() { printf '[build-engine] ERROR: %s\n' "$*" >&2; exit 1; }

DSH_VERSION="$(node -p 'require(process.argv[1]).dependencies["@deepseek-ai/dsh"]' "$JS_DIR/engine/package.json")"
[[ "$DSH_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "dsh must be pinned to an exact version (got $DSH_VERSION)"

# ── 1. production install from the committed lockfile ────────────────────────
log "npm ci @deepseek-ai/dsh@$DSH_VERSION (production, --ignore-scripts)"
rm -rf "${STAGE:?}"
mkdir -p "$STAGE"
cp "$JS_DIR/engine/package.json" "$JS_DIR/engine/package-lock.json" "$STAGE/"
# Install scripts are not needed: node-pty/koffi pick prebuilds (pruned anyway),
# dsh-subprocess-local's only fixes the macOS spawn-helper mode, protobufjs/genai are no-ops.
(cd "$STAGE" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error)

# ── 2. our packages, copied (never linked) ───────────────────────────────────
# js/packages/<dir> → node_modules/<package name>: our @dsh-android packages plus the vendored
# third-party LLM adapter @snnh/dsh-llm-service-lite (MIT; see its package.json "//vendored").
log "adding @dsh-android packages + vendored @snnh/dsh-llm-service-lite"
OUR_PKGS=(bundle-android plugin-device plugin-guard plugin-mode plugin-account plugin-extensions llm-service-lite)
for pkg in "${OUR_PKGS[@]}"; do
  node -e '
    const [src, nm, stagePkg] = process.argv.slice(1)
    const fs = require("node:fs"), path = require("node:path")
    const manifest = require(path.join(src, "package.json"))
    const dst = path.join(nm, manifest.name)
    for (const f of manifest.files) {
      fs.mkdirSync(path.dirname(path.join(dst, f)), { recursive: true })
      fs.copyFileSync(path.join(src, f), path.join(dst, f))
    }
    // Record it as a dependency of the engine root so npm/dsh resolution sees a consistent tree.
    const j = JSON.parse(fs.readFileSync(stagePkg, "utf8"))
    j.dependencies[manifest.name] = manifest.version
    fs.writeFileSync(stagePkg, JSON.stringify(j, null, 2) + "\n")
  ' "$JS_DIR/packages/$pkg" "$NM" "$STAGE/package.json"
done
# The vendored adapter's runtime dependencies must come from the lockfile-pinned tree (no new
# downloads): each must already be installed at the locked version and satisfy the declared range.
node -e '
  const [nm, lockPath] = process.argv.slice(1)
  const path = require("node:path")
  const semver = require(path.join(nm, "semver"))
  const lock = require(lockPath).packages
  const deps = require(path.join(nm, "@snnh/dsh-llm-service-lite/package.json")).dependencies
  for (const [name, range] of Object.entries(deps)) {
    const locked = lock["node_modules/" + name]
    let have; try { have = require(path.join(nm, name, "package.json")).version } catch {}
    if (!locked || have !== locked.version || !semver.satisfies(have, range, { includePrerelease: true }))
      { console.error(`llm-service-lite dependency ${name}@${range}: locked ${locked?.version}, installed ${have}`); process.exit(1) }
  }
' "$NM" "$STAGE/package-lock.json" || die "llm-service-lite dependencies not satisfied by the locked tree"

# ── 3. sharp WebAssembly build (attachment images on android; see patch 09) ──
log "adding @img/sharp-freebsd-wasm32 closure (lockfile-pinned, integrity-checked)"
node "$JS_DIR/scripts/fetch-locked.mjs" "$STAGE/package-lock.json" "$NM" \
  @img/sharp-freebsd-wasm32 @img/sharp-wasm32 @emnapi/runtime tslib

# ── 4. host-native test bindings (only used by test/host-e2e.mjs, never zipped) ─
rm -rf "${HOST_NATIVE:?}"
if [[ "$(uname -s)-$(uname -m)" == "Linux-aarch64" ]]; then
  mkdir -p "$HOST_NATIVE"
  cp "$NM/@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node" "$HOST_NATIVE/libdsh_flock.so"
  log "host test bindings -> $HOST_NATIVE"
fi

# ── 5. patches ───────────────────────────────────────────────────────────────
# Every target must exist. A content-hashed chunk name (`name-XXXXXXXX.js`, renamed by any dsh
# rebuild) is resolved to the single current chunk with the same stem; anything else fails loudly.
PATCH_TMP="$WORK_DIR/patches"
rm -rf "${PATCH_TMP:?}"; mkdir -p "$PATCH_TMP"
for p in "$JS_DIR"/patches/*.patch; do
  name="$(basename "$p")"
  log "patch $name"
  cp "$p" "$PATCH_TMP/$name"
  while IFS= read -r target; do
    [[ -f "$NM/$target" ]] && continue
    stem="${target%-*.js}"
    if [[ "$stem" != "$target" && "$target" =~ -[A-Za-z0-9_]{8}\.js$ ]]; then
      mapfile -t found < <(cd "$NM" && compgen -G "$stem-*.js" || true)
      if [[ ${#found[@]} -eq 1 ]]; then
        log "  $target -> ${found[0]} (content-hashed chunk renamed)"
        sed -i "s#^\(---\|+++\) \([ab]\)/$target#\1 \2/${found[0]}#" "$PATCH_TMP/$name"
        continue
      fi
    fi
    die "patch $name targets $target, which this dsh $DSH_VERSION tree does not have (upgrade? rebase or drop the patch)"
  done < <(sed -n 's#^+++ b/\([^[:space:]]*\).*#\1#p' "$p")
  patch -p1 -d "$NM" --forward --batch --no-backup-if-mismatch --dry-run --silent < "$PATCH_TMP/$name" >/dev/null \
    || die "patch $name does not apply cleanly to dsh $DSH_VERSION"
  patch -p1 -d "$NM" --forward --batch --no-backup-if-mismatch --silent < "$PATCH_TMP/$name"
done
find "$NM" \( -name '*.rej' -o -name '*.orig' \) -print -quit | grep -q . && die "patch left .rej/.orig files"

# ── 6. prune ─────────────────────────────────────────────────────────────────
log "pruning"
before_kb=$(du -sk "$NM" | cut -f1)
rm_pkg() { for d in "$@"; do [[ -e "$NM/$d" ]] && rm -rf "${NM:?}/$d"; done; return 0; }
# Big optional payloads the android profile never loads.
rm_pkg @deepseek-ai/libreoffice-kit-wasm
# The profile disables `llm-pi-ai` (DeepSeek-only product): pi-ai and the provider SDKs only it
# reaches (openai, @anthropic-ai, @google/genai, @aws-sdk, @smithy, …; computed from the lockfile).
mapfile -t pi_only < <(node "$JS_DIR/scripts/unreachable.mjs" "$STAGE/package-lock.json" @earendil-works/pi-ai)
for key in "${pi_only[@]}"; do [[ -e "$STAGE/$key" ]] && rm -rf "${STAGE:?}/$key"; done
log "pruned ${#pi_only[@]} packages only reachable through @earendil-works/pi-ai"
# Browser client UI and the experimental inspector (Web/desktop surfaces only; boot verified without them).
for d in "$NM"/@deepseek-ai/dsh-client-ui-*; do rm -rf "$d"; done
rm_pkg @deepseek-ai/dsh-web-frontend @deepseek-ai/dsh-experimental-inspector
for d in "$NM"/sherpa-onnx*; do [[ -e "$d" ]] && rm -rf "$d"; done
# Host/other-platform native packages (the app ships android builds in nativeLibraryDir).
for d in "$NM"/@img/sharp-*; do
  case "$(basename "$d")" in sharp-freebsd-wasm32|sharp-wasm32) ;; *) rm -rf "$d" ;; esac
done
for d in "$NM"/@deepseek-ai/node-addon-system-* "$NM"/node-addon-require-builtin-* "$NM"/@koromix/koffi-* \
         "$NM"/@vscode/ripgrep-* "$NM"/@esbuild "$NM"/esbuild; do
  [[ -e "$d" ]] && rm -rf "$d"
done
rm -rf "$NM/@vscode/ripgrep/bin" "$NM/node-pty/prebuilds" "$NM/node-pty/build" "$NM/node-pty/third_party" \
       "$NM/node-pty/src" "$NM/node-pty/deps" "$NM/koffi/build" "$NM/koffi/src"
find "$NM" -type d -name prebuilds -prune -exec rm -rf {} +
find "$NM" -path "$NM/@img/sharp-wasm32" -prune -o -type f \( -name '*.node' -o -name '*.dylib' -o -name '*.dll' -o -name '*.exe' -o -name '*.pdb' \) -exec rm -f {} +
# Tooling links, source maps, type declarations, docs, tests.
find "$NM" -type d -name .bin -prune -exec rm -rf {} +
find "$NM" -type f \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' -o -name '*.tsbuildinfo' \) -delete
find "$NM" -type f \( -iname 'README*' -o -iname 'CHANGELOG*' -o -iname 'HISTORY.md' -o -iname 'CONTRIBUTING*' -o -name 'README.i18n.yaml' \) -delete
for pkgdir in "$NM"/[!@.]* "$NM"/@*/*; do
  for sub in docs doc test tests __tests__ example examples benchmark benchmarks .github; do
    [[ -d "$pkgdir/$sub" ]] && rm -rf "${pkgdir:?}/$sub"
  done
done
# pnpm (plugin installs on the phone): no native builds (ignoreScripts), no reflink (packageImportMethod copy),
# no Windows process helpers.
rm -rf "$NM/pnpm/dist/node_modules/node-gyp" "$NM/pnpm/dist/node-gyp-bin" "$NM/pnpm/dist/vendor"
for d in "$NM"/pnpm/dist/node_modules/@reflink/reflink-*; do [[ -e "$d" ]] && rm -rf "$d"; done
find "$NM" -type d -empty -delete
after_kb=$(du -sk "$NM" | cut -f1)
# After pruning, so it lists what the zip really ships. The phone gate (plugin-extensions/pnpmfile.cjs) turns plugin dependencies on packages the engine
# ships into peers, so a plugin shares the engine's single instance (cordis, dsh-tools, …).
node -e '
  const [nm, out] = process.argv.slice(1)
  const fs = require("node:fs"), path = require("node:path")
  const names = fs.readdirSync(path.join(nm, "@deepseek-ai")).map(n => `@deepseek-ai/${n}`)
    .filter(n => fs.existsSync(path.join(nm, n, "package.json"))).sort()
  fs.writeFileSync(out, JSON.stringify(names, null, 1) + "\n")
' "$NM" "$NM/@dsh-android/plugin-extensions/installation-packages.json"


# ── 7. verify ────────────────────────────────────────────────────────────────
links=$(find "$STAGE" -type l | wc -l)
[[ "$links" -eq 0 ]] || { find "$STAGE" -type l | head >&2; die "$links symlinks remain in the engine tree"; }
[[ -f "$NM/@deepseek-ai/dsh/lib/bin.js" ]] || die "missing dsh entry point"
grep -q 'libdsh_flock.so' "$NM/@deepseek-ai/node-addon-system/lib/flock.js" || die "flock patch missing"
grep -q '"@dsh-android/bundle-android"' "$NM/@deepseek-ai/dsh-app-boot/lib/index.js" || die "profile template patch missing"
grep -q 'sdkExtensions' "$NM/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js" || die "SDK extension patch missing"
grep -q 'expose-internals' "$NM/node-addon-require-builtin/lib/index.js" || die "require-builtin patch missing"
grep -q 'promptTimeZone' "$NM/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js" || die "SDK prompt time-zone patch missing"
[[ -f "$NM/@deepseek-ai/dsh-time-context/package.json" && -f "$NM/@deepseek-ai/dsh-tool-ask-user/package.json" ]] || die "time-context / ask-user packages missing"
[[ ! -e "$NM/@earendil-works/pi-ai" && ! -e "$NM/openai" ]] || die "provider SDK prune failed"
[[ -f "$NM/@img/sharp-wasm32/package.json" ]] || die "sharp wasm build missing"
[[ -f "$NM/@snnh/dsh-llm-service-lite/lib/index.js" && -f "$NM/eventsource-parser/package.json" ]] || die "llm-service-lite or eventsource-parser missing"
grep -q 'describeTransportCause' "$NM/@snnh/dsh-llm-service-lite/lib/index.js" || die "llm-service-lite transport-cause patch missing"
grep -q 'patch 16' "$NM/@snnh/dsh-llm-service-lite/lib/index.js" || die "llm-service-lite usage-null / parse-error patch missing"
grep -q 'patch 17' "$NM/@snnh/dsh-llm-service-lite/lib/index.js" || die "llm-service-lite reasoning echo / gateway compatibility patch missing"
grep -q 'patch 18' "$NM/@snnh/dsh-llm-service-lite/lib/index.js" || die "llm-service-lite route headers / model access patch missing"
grep -q 'patch 22' "$NM/@snnh/dsh-llm-service-lite/lib/index.js" || die "llm-service-lite declared reasoning patch (22) missing"
grep -q 'patch 15' "$NM/@deepseek-ai/dsh-http-proxy/lib/index.js" || die "http-proxy exclusion-glob patch missing"
grep -q 'appendPluginRecord' "$NM/@deepseek-ai/dsh-session/lib/index.js" || die "dsh-session lacks appendPluginRecord (plugin-mode records its selection with it)"
[[ -f "$NM/@dsh-android/plugin-mode/index.js" ]] || die "plugin-mode missing"
[[ -f "$NM/@dsh-android/bundle-android/main.mjs" ]] || die "engine entry main.mjs missing"
[[ -f "$NM/@dsh-android/plugin-extensions/pnpmfile.cjs" && -s "$NM/@dsh-android/plugin-extensions/installation-packages.json" ]] || die "plugin-extensions gate missing"
grep -q 'js patch 19' "$NM/@deepseek-ai/dsh-tools/lib/index.js" && grep -q 'js patch 19' "$NM/@deepseek-ai/dsh-commands/lib/index.js" || die "tool/command owner patch (19) missing"
grep -q 'js patch 20' "$NM/@deepseek-ai/dsh-plugin-market/lib/index.js" || die "plugin-market fetch hook patch (20) missing"
grep -q 'js patch 21' "$NM/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js" || die "SDK prompt steer patch (21) missing"
PNPM_VERSION="$(node -p 'require(process.argv[1]).dependencies.pnpm' "$JS_DIR/engine/package.json")"
have_pnpm="$(node "$NM/pnpm/bin/pnpm.mjs" --version 2>/dev/null || true)"
[[ "$have_pnpm" == "$PNPM_VERSION" ]] || die "bundled pnpm does not run after pruning (got '$have_pnpm', want $PNPM_VERSION)"
# Our packages pin engines.dsh to the exact bundled dsh: a mismatch makes dsh skip the android bundle at boot.
for m in "$NM"/@dsh-android/*/package.json; do
  want="$(node -p 'require(process.argv[1]).engines?.dsh ?? ""' "$m")"
  [[ -z "$want" || "$want" == "$DSH_VERSION" ]] || die "$m declares engines.dsh $want, but the engine bundles dsh $DSH_VERSION"
done
log "no symlinks; entry point and patches verified"

# ── 8. version, zip, digest, report ──────────────────────────────────────────
INPUT_HASH=$( (cd "$JS_DIR" && cat engine/package-lock.json patches/*.patch \
  packages/bundle-android/cordis.patch.yml packages/bundle-android/android-env.cjs packages/bundle-android/main.mjs packages/*/package.json packages/plugin-*/*.js packages/plugin-extensions/*.cjs packages/plugin-extensions/*.mjs packages/plugin-extensions/*.json packages/llm-service-lite/lib/index.js scripts/*.mjs build-engine.sh) | sha256sum | cut -c1-10)
ENGINE_VERSION="${DSH_VERSION}-a${INPUT_HASH}"
printf '%s\n' "$ENGINE_VERSION" > "$STAGE/engine-version.txt"
printf '%s\n' "$ENGINE_VERSION" > "$OUT_DIR/engine-version.txt"
log "engine version $ENGINE_VERSION"

rm -f "$OUT_DIR/engine.zip"
python3 -I "$JS_DIR/scripts/make-zip.py" "$STAGE" "$OUT_DIR/engine.zip"
(cd "$OUT_DIR" && sha256sum engine.zip > engine.sha256)

{
  echo "DSH-Android engine $ENGINE_VERSION (dsh $DSH_VERSION)"
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -sm), node $(node -v)"
  echo
  echo "node_modules before prune: $((before_kb / 1024)) MiB"
  echo "node_modules after prune:  $((after_kb / 1024)) MiB"
  echo "files: $(find "$STAGE" -type f | wc -l), symlinks: $links"
  echo "bundled pnpm $PNPM_VERSION: $(du -sk "$NM/pnpm" | cut -f1) KiB unpacked"
  echo "engine.zip: $(du -k "$OUT_DIR/engine.zip" | cut -f1) KiB ($(stat -c %s "$OUT_DIR/engine.zip") bytes)"
  echo "sha256: $(cut -d' ' -f1 "$OUT_DIR/engine.sha256")"
  echo
  echo "largest packages (KiB):"
  (cd "$NM" && du -sk -- [!@]* @*/* 2>/dev/null | sort -rn | awk "NR<=25")
} > "$OUT_DIR/size-report.txt"
cat "$OUT_DIR/size-report.txt" >&2
log "done: $OUT_DIR/engine.zip"
