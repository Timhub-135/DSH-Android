#!/usr/bin/env bash
# DSH-Android native layer: libnode.so + libdshengine.so + libdshnode.so + Node addons for android arm64.
#
# Usage:  native/build.sh [stage...]
#   stages: toolchain node headers engine launcher addons check   (default: all, in that order)
#
# Environment (all optional except NDK when not at the default location):
#   NDK        Android NDK r29 root               (default: $WORK/android-ndk-r29)
#   WORK       scratch dir for sources/builds      (default: <repo>/../toolchains)
#   OUT        output dir                          (default: $WORK/out/arm64-v8a)
#   API        Android min API level               (default: 29)
#   JOBS       parallel jobs                       (default: nproc)
#   HOST_CC / HOST_CXX   host compilers            (default: clang / clang++)
#   USE_NDK_CLANG=1      use the NDK's (x86_64) clang instead of host clang
#
# Host requirements: clang/clang++ (tested with 19), ld.lld, llvm-ar/strip/readelf/nm,
# python3, ninja, curl, patch, git, tar/xz.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$(cd "$REPO/.." && pwd)/toolchains}"
NDK="${NDK:-$WORK/android-ndk-r29}"
OUT="${OUT:-$WORK/out/arm64-v8a}"
API="${API:-29}"
JOBS="${JOBS:-$(nproc)}"
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"
TRIPLE="aarch64-linux-android"

NODE_VERSION=24.22.1
NODE_SHA256=4f5379796280cdaeb0c2f358fd04b6a16cc4fb7dfa83b5a8c91a9d963b435231
# Addon versions match the dsh install (node_modules of @deepseek-ai/dsh).
NODE_PTY_VERSION=1.2.0-beta.15
NAA_PTY_VERSION=7.1.1                 # node-addon-api used by node-pty
NODE_ADDON_SYSTEM_VERSION=0.1.2
# node-addon-require-builtin is no longer built: the engine runs with --expose-internals
# (js/patches/02-require-builtin-expose-internals.patch), so no runtime-probing addon ships.

SRC="$WORK/src"
NODE_SRC="$SRC/node-v$NODE_VERSION"
TC="$WORK/android-tc"                 # generated compiler wrappers + resource dir
HEADERS="$OUT/../node-headers"         # include/node from our libnode build (not shipped)
ADDON_BUILD="$WORK/addon-build"

export TMPDIR="${TMPDIR:-$WORK/tmp}"
mkdir -p "$TMPDIR" "$SRC" "$OUT"

NDK_LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$NDK_LLVM/sysroot"

log() { printf '\n==> %s\n' "$*"; }

# ---------------------------------------------------------------------------
# toolchain: wrappers around host clang targeting Android with the NDK sysroot.
# Host clang has no Android compiler-rt, so we build a resource dir that takes
# clang's own headers and the NDK's lib/linux (libclang_rt.builtins, libunwind.a,
# libatomic.a).
# ---------------------------------------------------------------------------
stage_toolchain() {
  log "toolchain"
  [ -d "$SYSROOT/usr/lib/$TRIPLE/$API" ] || { echo "NDK sysroot not found under $NDK" >&2; exit 1; }
  mkdir -p "$TC/bin"
  if [ "${USE_NDK_CLANG:-0}" = 1 ]; then
    ln -sfn "$NDK_LLVM/bin/$TRIPLE$API-clang" "$TC/bin/target-cc"
    ln -sfn "$NDK_LLVM/bin/$TRIPLE$API-clang++" "$TC/bin/target-cxx"
  else
    local rd="$TC/resource-dir" hrd ndkrd
    hrd="$("$HOST_CC" -print-resource-dir)"
    ndkrd="$(ls -d "$NDK_LLVM"/lib/clang/* | sort -V | tail -1)"
    mkdir -p "$rd/lib"
    ln -sfn "$hrd/include" "$rd/include"
    [ -d "$hrd/share" ] && ln -sfn "$hrd/share" "$rd/share"
    ln -sfn "$ndkrd/lib/linux" "$rd/lib/linux"
    for t in cc cxx; do
      local drv="$HOST_CC"; [ $t = cxx ] && drv="$HOST_CXX"
      cat > "$TC/bin/target-$t" <<EOF
#!/bin/sh
exec $drv --target=$TRIPLE$API --sysroot=$SYSROOT -resource-dir=$rd \\
  -rtlib=compiler-rt -unwindlib=libunwind -fuse-ld=lld \\
  -Wno-unused-command-line-argument "\$@"
EOF
      chmod +x "$TC/bin/target-$t"
    done
  fi
  # sanity: C++ shared object links and needs only libc++_shared + bionic
  printf '#include <string>\nextern "C" int f(int n){return std::string(n,0).size();}\n' > "$TMPDIR/tc.cc"
  "$TC/bin/target-cxx" -shared -fPIC "$TMPDIR/tc.cc" -o "$TMPDIR/tc.so"
  llvm-readelf -d "$TMPDIR/tc.so" | grep NEEDED
}

target_env() {
  # ccache (if installed) makes a rebuild after a patch/flag change recompile only what changed.
  local cc=""; command -v ccache >/dev/null && cc="ccache "
  export CC="${cc}$TC/bin/target-cc" CXX="${cc}$TC/bin/target-cxx" LINK="$TC/bin/target-cxx"
  export AR=llvm-ar NM="$(command -v nm || echo llvm-nm)" RANLIB=llvm-ranlib READELF=llvm-readelf STRIP=llvm-strip
  export CC_host="${cc}$HOST_CC" CXX_host="${cc}$HOST_CXX" LINK_host="$HOST_CXX"
  export AR_host=llvm-ar NM_host=llvm-nm RANLIB_host=llvm-ranlib
}

# ---------------------------------------------------------------------------
# node: fetch, patch, configure (--shared), build libnode.so with ninja.
# ---------------------------------------------------------------------------
stage_node() {
  log "node v$NODE_VERSION"
  local tarball="$SRC/node-v$NODE_VERSION.tar.xz"
  if [ ! -f "$tarball" ]; then
    curl -fL -o "$tarball.part" "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION.tar.xz"
    mv "$tarball.part" "$tarball"
  fi
  echo "$NODE_SHA256  $tarball" | sha256sum -c -
  if [ ! -f "$NODE_SRC/.dsh-patched" ]; then
    rm -rf "$NODE_SRC"
    tar -C "$SRC" -xf "$tarball"
    for p in "$HERE"/patches/node/*.patch; do
      echo "applying $(basename "$p")"
      patch -d "$NODE_SRC" -p1 -s < "$p"
    done
    touch "$NODE_SRC/.dsh-patched"
  fi
  target_env
  cd "$NODE_SRC"
  if [ ! -f out/Release/build.ninja ] || [ "${RECONFIGURE:-0}" = 1 ]; then
    GYP_DEFINES="host_os=linux" ./configure \
      --shared \
      --dest-os=android --dest-cpu=arm64 --cross-compiling \
      --without-npm --without-corepack \
      --with-intl=small-icu \
      --without-node-snapshot \
      --ninja
  fi
  ninja -C out/Release -j "$JOBS" libnode
  local so; so="$(find out/Release -maxdepth 2 -name 'libnode.so*' -type f | head -1)"
  [ -n "$so" ] || { echo "libnode.so not produced" >&2; exit 1; }
  mkdir -p "$OUT/../symbols" && cp "$so" "$OUT/../symbols/libnode.so"
  llvm-strip --strip-unneeded -o "$OUT/libnode.so" "$so"
  cp "$SYSROOT/usr/lib/$TRIPLE/libc++_shared.so" "$OUT/libc++_shared.so"
  ls -l "$OUT/libnode.so"
}

# headers: include/node from this exact source tree + config (used by JNI + addons).
stage_headers() {
  log "node headers"
  cd "$NODE_SRC"
  rm -rf "$HEADERS"
  python3 tools/install.py install --headers-only --dest-dir "$HEADERS" --prefix / --silent
  ls "$HEADERS/include/node/node.h" "$HEADERS/include/node/node_api.h" >/dev/null
}

LDFLAGS_SO=(-Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -L"$OUT" -lnode)

# ---------------------------------------------------------------------------
# engine: libdshengine.so (JNI -> node::Start)
# ---------------------------------------------------------------------------
stage_engine() {
  log "libdshengine.so"
  "$TC/bin/target-cxx" -shared -fPIC -O2 -std=c++20 -fvisibility=hidden \
    -I"$HEADERS/include/node" \
    "$HERE/engine-jni/dshengine.cc" \
    "${LDFLAGS_SO[@]}" -llog \
    -o "$OUT/libdshengine.so"
  llvm-strip --strip-unneeded "$OUT/libdshengine.so"
}

# ---------------------------------------------------------------------------
# launcher: libdshnode.so, a PIE `node` executable (Node's own main()) for child
# processes: bundled pnpm, JS MCP servers, plugins spawning node. Named lib*.so so
# it lands in nativeLibraryDir (exec-allowed); RUNPATH $ORIGIN finds libnode.so.
# ---------------------------------------------------------------------------
stage_launcher() {
  log "libdshnode.so"
  "$TC/bin/target-cxx" -fPIE -pie -O2 -std=c++20 \
    -I"$HEADERS/include/node" \
    "$HERE/dshnode/main.cc" \
    -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,-rpath,'$ORIGIN' \
    -L"$OUT" -lnode \
    -o "$OUT/libdshnode.so"
  llvm-strip --strip-unneeded "$OUT/libdshnode.so"
}

# ---------------------------------------------------------------------------
# addons: node-pty, @deepseek-ai/node-addon-system (flock).
# Each is linked with DT_NEEDED libnode.so: on Android, dlopen()ed objects in an
# app namespace do not see symbols of libraries loaded by the JNI library unless
# they depend on them directly.
# Output layout under $OUT/node_modules mirrors what each JS loader probes.
# ---------------------------------------------------------------------------
npm_fetch() {  # npm_fetch <name> <version> <dest-dir>
  local name="$1" ver="$2" dest="$3" base tgz
  [ -f "$dest/package.json" ] && return 0
  base="${name##*/}"
  tgz="$ADDON_BUILD/dl/$base-$ver.tgz"
  mkdir -p "$ADDON_BUILD/dl" "$dest"
  [ -f "$tgz" ] || curl -fsSL -o "$tgz" "https://registry.npmjs.org/$name/-/$base-$ver.tgz"
  tar -C "$dest" --strip-components=1 -xzf "$tgz"
}

stage_addons() {
  log "addons"
  local NM="$OUT/node_modules" modv
  modv="$(sed -n 's/^#define NODE_MODULE_VERSION \([0-9][0-9]*\).*/\1/p' "$HEADERS/include/node/node_version.h")"
  [ "$modv" = 137 ] || echo "warning: NODE_MODULE_VERSION=$modv (expected 137)"
  mkdir -p "$ADDON_BUILD" "$NM"
  local warn=(-Wall -Wno-unused-parameter)

  # --- node-pty: src/unix/pty.cc (node-addon-api, C++ exceptions) ---
  local pty="$ADDON_BUILD/node-pty-$NODE_PTY_VERSION" naa7="$ADDON_BUILD/node-addon-api-$NAA_PTY_VERSION"
  npm_fetch node-pty "$NODE_PTY_VERSION" "$pty"
  npm_fetch node-addon-api "$NAA_PTY_VERSION" "$naa7"
  mkdir -p "$NM/node-pty/build/Release"
  # bionic has forkpty/openpty in libc (API 23+): no -lutil.
  "$TC/bin/target-cxx" -shared -fPIC -O2 -std=c++17 -fvisibility=hidden "${warn[@]}" \
    -fexceptions -fstack-protector-strong \
    -DNAPI_CPP_EXCEPTIONS -DNODE_GYP_MODULE_NAME=pty -DBUILDING_NODE_EXTENSION \
    -I"$HEADERS/include/node" -I"$naa7" \
    "$pty/src/unix/pty.cc" "${LDFLAGS_SO[@]}" \
    -o "$NM/node-pty/build/Release/pty.node"

  # --- @deepseek-ai/node-addon-system: flock binding only (src/flock.c, N-API 8) ---
  local sys="$ADDON_BUILD/node-addon-system-$NODE_ADDON_SYSTEM_VERSION"
  local sysdir="$NM/@deepseek-ai/node-addon-system-android-arm64"
  npm_fetch @deepseek-ai/node-addon-system "$NODE_ADDON_SYSTEM_VERSION" "$sys"
  mkdir -p "$sysdir/bin/musl"
  "$TC/bin/target-cc" -shared -fPIC -O2 -std=c11 -fvisibility=hidden "${warn[@]}" \
    -DNAPI_VERSION=8 -DNODE_GYP_MODULE_NAME=system -DBUILDING_NODE_EXTENSION \
    -I"$HEADERS/include/node" \
    "$sys/src/flock.c" "${LDFLAGS_SO[@]}" \
    -o "$sysdir/bin/musl/system.node"
  cat > "$sysdir/package.json" <<JSON
{
  "name": "@deepseek-ai/node-addon-system-android-arm64",
  "version": "$NODE_ADDON_SYSTEM_VERSION",
  "description": "DSH-Android build of the node-addon-system flock binding (bionic, android-arm64)",
  "os": ["android"],
  "cpu": ["arm64"],
  "license": "BSD-3-Clause"
}
JSON

  find "$NM" -name '*.node' -exec llvm-strip --strip-unneeded {} \;
}

# ---------------------------------------------------------------------------
# check: ELF / NEEDED / exported symbol sanity (no device needed).
# ---------------------------------------------------------------------------
stage_check() {
  log "check"
  local bad=0 f n
  local allowed='^(libnode\.so|libc\+\+_shared\.so|libc\.so|libm\.so|libdl\.so|liblog\.so|libandroid\.so|libz\.so)$'
  while IFS= read -r f; do
    echo "--- ${f#$OUT/}  ($(stat -c %s "$f") bytes)"
    llvm-readelf -h "$f" | grep -q 'Machine:.*AArch64' || { echo "  NOT AArch64"; bad=1; }
    if [ "$(basename "$f")" = libdshnode.so ]; then
      # The launcher is an executable: PIE, bionic's linker, RUNPATH $ORIGIN, main().
      llvm-readelf -l "$f" | grep -q '/system/bin/linker64' || { echo "  launcher has no /system/bin/linker64 interpreter"; bad=1; }
      llvm-readelf -d "$f" | grep -q 'RUNPATH.*\[\$ORIGIN\]' || { echo "  launcher lacks RUNPATH \$ORIGIN"; bad=1; }
      llvm-readelf -h "$f" | grep -q 'Type:.*DYN' || { echo "  launcher is not PIE"; bad=1; }
      llvm-nm -D --undefined-only "$f" | grep -q ' _ZN4node5StartEiPPc$' || { echo "  launcher does not call node::Start"; bad=1; }
    elif llvm-readelf -l "$f" | grep -q 'INTERP'; then echo "  has PT_INTERP"; bad=1; fi
    for n in $(llvm-readelf -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
      echo "  NEEDED $n"
      [[ "$n" =~ $allowed ]] || { echo "  ^^ unexpected"; bad=1; }
    done
    llvm-readelf -n "$f" | grep -q 'NT_ANDROID_TYPE_IDENT' || { echo "  missing Android ident note"; bad=1; }
    llvm-readelf -l "$f" | awk '$1=="LOAD"{print $NF}' | sort -u | grep -qv 0x4000 && { echo "  LOAD align != 16K"; bad=1; }
  done < <(find "$OUT" \( -name '*.so' -o -name '*.node' \) -not -name 'libc++_shared.so' | sort)
  local sym syms
  syms="$(llvm-nm -D --defined-only "$OUT/libnode.so")"   # not piped into grep -q (SIGPIPE + pipefail)
  for sym in _ZN4node5StartEiPPc _ZNK4node14PrincipalRealm22builtin_module_requireEv napi_create_function; do
    grep -q " $sym\$" <<<"$syms" && echo "libnode.so exports $sym" || { echo "libnode.so MISSING $sym"; bad=1; }
  done
  for sym in Java_io_github_timhub135_dsh_engine_NodeRuntime_nativeStart JNI_OnLoad; do
    llvm-nm -D --defined-only "$OUT/libdshengine.so" | grep -q " $sym\$" && echo "libdshengine.so exports $sym" || { echo "libdshengine.so MISSING $sym"; bad=1; }
  done
  while IFS= read -r f; do
    llvm-nm -D --defined-only "$f" | grep -q ' napi_register_module_v1$' && echo "${f#$OUT/}: napi_register_module_v1 ok" || { echo "${f#$OUT/}: MISSING napi_register_module_v1"; bad=1; }
  done < <(find "$OUT/node_modules" -name '*.node' | sort)
  [ $bad = 0 ] && echo "ALL CHECKS PASSED" || echo "CHECKS FAILED"
  return $bad
}

stages=("$@"); [ ${#stages[@]} -gt 0 ] || stages=(toolchain node headers engine launcher addons check)
for s in "${stages[@]}"; do "stage_$s"; done
