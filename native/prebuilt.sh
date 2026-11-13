#!/usr/bin/env bash
# Prebuilt native libraries for the app (libnode + JNI + addons), published as a GitHub Release so
# app/engine builds never rebuild Node. The release tag is derived from every native input
# (Node version, build.sh, patches, JNI and launcher sources): change any of them and the tag changes.
#
#   native/prebuilt.sh key       # print the tag for the current inputs
#   native/prebuilt.sh fetch     # download + verify into $JNI_DIR (default toolchains/out/jniLibs)
#   native/prebuilt.sh package   # assemble $JNI_DIR from build.sh outputs ($OUT) into a tarball
#   native/prebuilt.sh publish   # package + create the release (gh, needs push access)
#
# Typical flow: `fetch`; only if no release exists for the key, run native/build.sh then `publish`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$(cd "$REPO/.." && pwd)/toolchains}"
OUT="${OUT:-$WORK/out/arm64-v8a}"
JNI_DIR="${JNI_DIR:-$WORK/out/jniLibs}"
ABI=arm64-v8a
GH_REPO="${GH_REPO:-Timhub-135/DSH-Android}"

key() {
  local v; v="$(sed -n 's/^NODE_VERSION=//p' "$HERE/build.sh")"
  local h; h="$(cd "$HERE" && find build.sh patches engine-jni dshnode -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -c1-10)"
  echo "native-node$v-$h"
}

# jniLibs name ← build.sh output path (librg.so is a pinned upstream ripgrep binary, carried over).
MAP=(
  "libnode.so:libnode.so"
  "libdshengine.so:libdshengine.so"
  "libdshnode.so:libdshnode.so"
  "libc++_shared.so:libc++_shared.so"
  "libdsh_pty.so:node_modules/node-pty/build/Release/pty.node"
  "libdsh_flock.so:node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/musl/system.node"
)

package() {
  local tag; tag="$(key)"
  local stage="$WORK/tmp/$tag/$ABI"
  rm -rf "$WORK/tmp/$tag"; mkdir -p "$stage"
  for m in "${MAP[@]}"; do cp "$OUT/${m#*:}" "$stage/${m%%:*}"; done
  cp "$JNI_DIR/$ABI/librg.so" "$stage/librg.so"
  (cd "$WORK/tmp/$tag" && sha256sum "$ABI"/* > SHA256SUMS && tar -czf "$WORK/tmp/$tag.tar.gz" SHA256SUMS "$ABI")
  echo "$WORK/tmp/$tag.tar.gz"
}

publish() {
  local tag tarball; tag="$(key)"; tarball="$(package)"
  gh release create "$tag" --prerelease --latest=false "$tarball" "$WORK/out/symbols/libnode.so#libnode.so (unstripped symbols)" \
    --repo "$GH_REPO" --title "$tag" \
    --notes "Prebuilt android $ABI native libs for inputs $tag (native/build.sh + patches). Fetch with native/prebuilt.sh fetch."
}

fetch() {
  local tag; tag="$(key)"
  local dl="$WORK/tmp/fetch-$tag"; rm -rf "$dl"; mkdir -p "$dl"
  gh release download "$tag" --repo "$GH_REPO" --pattern "$tag.tar.gz" --dir "$dl" \
    || { echo "no prebuilt release $tag — run native/build.sh, then native/prebuilt.sh publish" >&2; exit 2; }
  tar -C "$dl" -xzf "$dl/$tag.tar.gz"
  (cd "$dl" && sha256sum -c --quiet SHA256SUMS)
  mkdir -p "$JNI_DIR/$ABI"
  rm -f "$JNI_DIR/$ABI"/*.so
  cp "$dl/$ABI"/*.so "$JNI_DIR/$ABI/"
  echo "$tag → $JNI_DIR/$ABI"
}

case "${1:-}" in
  key|fetch|package|publish) "$1" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
