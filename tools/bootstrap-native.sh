#!/usr/bin/env bash
# 一次性把上游 Node 原生库镜像到 fork（只跑一次，人工执行，不要放进 CI）。
#
#   UPSTREAM_URL="<上游 tarball 的下载地址>" \
#   UPSTREAM_SHA256="<该 tarball 的 sha256>" \
#   tools/bootstrap-native.sh
#
# 流程（见“包名重命名与一次性 fork 方案”§2.2）：
#   下载上游 tarball → 校验 sha256 → 解包 → 用 tools/patch-jni-symbol.py
#   把 .dynstr 里的包名片段等长替换 → NEW_TAG=$(native/prebuilt.sh key)
#   → 重算 SHA256SUMS → 打包 → 以 fork 名义发布 prerelease（--latest=false）。
#
# 前置：① 已完成包名改名（dshengine.cc / build.sh / prebuilt.sh key() 自洽）；
#       ② gh 已登录且有 Timhub-135/DSH-Android 的写权限；
#       ③ UPSTREAM_URL / UPSTREAM_SHA256 必填（上游地址只作为一次性输入，不写进仓库）。
# 之后所有构建只从 fork fetch：
#   GH_REPO=Timhub-135/DSH-Android native/prebuilt.sh fetch
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"

: "${UPSTREAM_URL:?UPSTREAM_URL is required (upstream native tarball URL)}"
: "${UPSTREAM_SHA256:?UPSTREAM_SHA256 is required (sha256 of UPSTREAM_URL)}"
GH_REPO="${GH_REPO:-Timhub-135/DSH-Android}"
FROM_SEG="${FROM_SEG:-jackwener}"
TO_SEG="${TO_SEG:-timhub135}"
ABI=arm64-v8a

log() { printf '\033[1m[bootstrap-native]\033[0m %s\n' "$*" >&2; }
die() { printf '[bootstrap-native] ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { if [ -n "${WORK:-}" ]; then rm -rf "$WORK"; fi; }
trap cleanup EXIT

command -v gh >/dev/null 2>&1 || die "gh not found (GitHub CLI is needed to publish the fork release)"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
[ -x tools/patch-jni-symbol.py ] || [ -f tools/patch-jni-symbol.py ] || die "tools/patch-jni-symbol.py missing"

WORK="$(mkdir -p "$REPO/toolchains/tmp" && mktemp -d "$REPO/toolchains/tmp/bootstrap.XXXXXX" 2>/dev/null || mktemp -d)"
STAGE="$WORK/stage"
mkdir -p "$STAGE"

# ── 1. 下载 + 校验上游 tarball ────────────────────────────────────────────────
log "downloading upstream tarball"
curl -fL --retry 3 --retry-delay 2 -o "$WORK/upstream.tar.gz" "$UPSTREAM_URL"
actual="$(sha256sum "$WORK/upstream.tar.gz" | awk '{print $1}')"
want="$(printf '%s' "$UPSTREAM_SHA256" | tr 'A-F' 'a-f')"
[ "$actual" = "$want" ] || die "sha256 mismatch: got $actual, want $want"
log "sha256 verified"

# ── 2. 解包 + 校验上游自带的 SHA256SUMS ───────────────────────────────────────
tar -C "$STAGE" -xzf "$WORK/upstream.tar.gz"
[ -f "$STAGE/SHA256SUMS" ] || die "upstream tarball has no SHA256SUMS"
[ -d "$STAGE/$ABI" ] || die "upstream tarball has no $ABI/ directory"
(cd "$STAGE" && sha256sum -c --quiet SHA256SUMS) || die "upstream SHA256SUMS check failed"
[ -f "$STAGE/$ABI/libdshengine.so" ] || die "upstream tarball has no $ABI/libdshengine.so"

# ── 3. 等长替换 JNI 符号 ─────────────────────────────────────────────────────
log "patching JNI package segment in libdshengine.so ($FROM_SEG -> $TO_SEG)"
python3 tools/patch-jni-symbol.py "$STAGE/$ABI/libdshengine.so" --from "$FROM_SEG" --to "$TO_SEG"

# ── 4. fork 侧的新 tag（改名后 native/prebuilt.sh key() 的结果）──────────────
NEW_TAG="$(native/prebuilt.sh key)"
[ -n "$NEW_TAG" ] || die "native/prebuilt.sh key returned nothing"
log "fork tag: $NEW_TAG"

# ── 5. 重算校验和 + 确定性打包 ───────────────────────────────────────────────
(cd "$STAGE" && sha256sum "$ABI"/* > SHA256SUMS)
(cd "$STAGE" && sha256sum -c --quiet SHA256SUMS)
tarball="$WORK/$NEW_TAG.tar.gz"
tar --sort=name --mtime='2020-01-01 00:00:00 UTC' --owner=0 --group=0 --numeric-owner \
  -czf "$tarball" -C "$STAGE" SHA256SUMS "$ABI"
log "packed $tarball ($(stat -c%s "$tarball") bytes)"

# ── 6. 以 fork 名义发布 prerelease（不参与 App 更新）────────────────────────
if gh release view "$NEW_TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
  die "release $NEW_TAG already exists in $GH_REPO"
fi
gh release create "$NEW_TAG" --repo "$GH_REPO" --prerelease --latest=false \
  "$tarball" \
  --title "$NEW_TAG" \
  --notes "fork native mirror: upstream libnode (one-time), JNI symbol rewritten to io.github.$TO_SEG"

log "published $NEW_TAG to $GH_REPO"
log "verify: GH_REPO=$GH_REPO native/prebuilt.sh fetch"
