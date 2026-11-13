# native/ — Node for Android arm64

> Python 3.14（Termux 预编译）不在这里构建：见 [`native/python/README.md`](python/README.md)。

Builds what the `:engine` process loads:

| Output | jniLibs name | What |
|---|---|---|
| `libnode.so` | `libnode.so` | Node.js v24.22.1 as a shared library, bionic arm64, min API 29 |
| `libdshengine.so` | `libdshengine.so` | JNI wrapper: `NodeRuntime.nativeStart(String[] args, int stdin, int stdout, int stderr)` → `node::Start()` |
| `libdshnode.so` | `libdshnode.so` | `node` executable for child processes (bundled pnpm, JS MCP servers, plugins spawning `node`): Node's own `main()` → `node::Start()`, PIE, `DT_NEEDED libnode.so`, `RUNPATH $ORIGIN` |
| `libc++_shared.so` | `libc++_shared.so` | from the NDK r29 sysroot |
| `node_modules/@deepseek-ai/node-addon-system-android-arm64/bin/musl/system.node` | `libdsh_flock.so` | flock binding of `@deepseek-ai/node-addon-system`, loaded by js patch 01 via `$DSH_NATIVE_DIR` |
| `node_modules/node-pty/build/Release/pty.node` | `libdsh_pty.so` | node-pty; shipped but unused while no terminal row is mounted |

`librg.so` (upstream ripgrep, aarch64) is not built here; `prebuilt.sh` carries it in the release tarball.
Node's `require-builtin` addon is not built: the engine runs with `--expose-internals` instead (js patch 02).

## Prebuilt (normal workflow)

Native libs change only with a Node upgrade or a native patch. They are published as a GitHub Release whose tag hashes
every native input (`native-node<version>-<hash>` over `build.sh`, `patches/`, `engine-jni/`, `dshnode/`):

```sh
native/prebuilt.sh key        # print the tag for the current inputs
native/prebuilt.sh fetch      # download + sha256-verify into $JNI_DIR/arm64-v8a
native/prebuilt.sh publish    # package $OUT (+ librg.so from $JNI_DIR) and create the release (gh, push access)
```

If `fetch` finds no release for the current key, the inputs changed: run `native/build.sh`, then `publish`. The
release also carries the unstripped `libnode.so` for symbolizing tombstones.

Defaults: `WORK=<repo>/../toolchains` (the directory next to the checkout), `OUT=$WORK/out/arm64-v8a`,
`JNI_DIR=$WORK/out/jniLibs`, `GH_REPO=Timhub-135/DSH-Android`. Gradle reads jniLibs from `-Pdsh.nativeOut`
(default `<repo>/toolchains/out/jniLibs`), so either make `<repo>/toolchains` a symlink to `$WORK` (it is gitignored) or
pass `-Pdsh.nativeOut=$WORK/out/jniLibs`.

## Building from source

```sh
NDK=/path/to/android-ndk-r29 native/build.sh                 # all stages
native/build.sh toolchain node headers engine launcher addons check   # same, explicit
native/build.sh engine launcher addons check                          # only the small parts
```

| Env | Default | |
|---|---|---|
| `NDK` | `$WORK/android-ndk-r29` | NDK r29 root (sysroot and runtime libs only) |
| `WORK` | `<repo>/../toolchains` | sources, build trees, outputs |
| `OUT` | `$WORK/out/arm64-v8a` | outputs |
| `API` | `29` | |
| `JOBS` | `nproc` | |
| `HOST_CC` / `HOST_CXX` | `clang` / `clang++` | |
| `USE_NDK_CLANG=1` | | use the NDK's own (x86_64) clang, e.g. on an x86_64 host |
| `RECONFIGURE=1` | | re-run Node's `./configure` |
| `TMPDIR` | `$WORK/tmp` | |

Host requirements: clang/clang++ ≥ 19, ld.lld, llvm-ar/nm/strip/readelf, python3, ninja, curl, git, patch, xz; network
access to nodejs.org, registry.npmjs.org and github.com. With `ccache` only changed files recompile. A full libnode
build is several hours on a 12-core arm64 host (about half of it the host-side V8 for mksnapshot/torque).

Avoid global configure changes: a global define rebuilds all of V8. Prefer target-scoped gyp patches, as
`dsh-deps-openssl-no-asm-android.patch` does.

Stages:

1. **toolchain**: writes `$WORK/android-tc/bin/target-{cc,cxx}` wrappers.
2. **node**: downloads and sha256-checks `node-v24.22.1.tar.xz`, applies `patches/node/*.patch`, builds the `libnode`
   ninja target, strips it into `$OUT/libnode.so` (unstripped copy in `$WORK/out/symbols/`), copies `libc++_shared.so`.
3. **headers**: `tools/install.py --headers-only` into `$WORK/out/node-headers/include/node` (this build's headers and
   `config.gypi`, used by the JNI wrapper and addons).
4. **engine**: `engine-jni/dshengine.cc` → `$OUT/libdshengine.so`.
5. **launcher**: `dshnode/main.cc` → `$OUT/libdshnode.so` (below).
6. **addons**: fetches pinned addon sources from npm and compiles them with plain clang (no node-gyp).
7. **check**: ELF sanity (below).

## Toolchain

- Host: Linux aarch64 (glibc) with system clang 19 + ld.lld. Target:
  `--target=aarch64-linux-android29 --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot`, NDK r29
  (29.0.14206865) headers, libs and `libc++_shared.so`.
- The NDK's prebuilt clang is x86_64-only and is not used by default. Host clang has no Android compiler-rt, so the
  wrapper builds a `-resource-dir` from host clang's `include/` plus the NDK clang's `lib/linux/`
  (`libclang_rt.builtins-aarch64-android.a`, `libunwind.a`, `libatomic.a`), with
  `-rtlib=compiler-rt -unwindlib=libunwind -fuse-ld=lld`. Result: `NT_ANDROID_TYPE_IDENT` = API 29,
  `-z max-page-size=16384`.
- gyp's SOLINK step calls `$NM -gD -f p`, which llvm-nm rejects, so `NM` is GNU `nm` when available.
- Host tools (torque, mksnapshot, ICU tools, js2c, …) build with host clang (`CC_host`/`CXX_host`). Host and target are
  both arm64, so no V8 simulator is involved.

## Node configure

```
GYP_DEFINES="host_os=linux" ./configure --shared --dest-os=android --dest-cpu=arm64 --cross-compiling \
  --without-npm --without-corepack --with-intl=small-icu --without-node-snapshot --ninja
ninja -C out/Release libnode
```

Everything is bundled (V8, libuv, OpenSSL, small ICU, zlib, c-ares, nghttp2/3, ngtcp2, sqlite, brotli, zstd, simdjson,
ada, llhttp, uvwasi). OpenSSL is built without asm on Android (patch below). Root CAs are Node's bundled set; the app
adds the device trust store through `NODE_EXTRA_CA_CERTS`. There is no startup snapshot (`--cross-compiling` would need
to run a target binary on the build host).

## Patches (`patches/node/`)

From Termux `packages/nodejs-lts` (Node 24.22.1), applied with `patch -p1`:

| Patch | Why |
|---|---|
| `termux-avoid-ficlone-ioctl` | libuv: skip the FICLONE ioctl (SELinux/seccomp denials) |
| `termux-common.gypi` | drop the android branch that references undefined `android_ndk_path` |
| `termux-deps-uv-src-unix-process.c` | libuv: no setgroups/setgid/setuid in children (seccomp kills app processes) |
| `termux-deps-uv-uv.gyp` | include ordering + `__USE_GNU` for bionic |
| `termux-deps-uvwasi-src-uvwasi.c` | enable WASI `fd_readdir` |
| `termux-deps-v8-src-trap-handler-trap-handler.h` | V8 wasm trap handler off (nodejs/node#36287) |
| `termux-fix_multiple_definitions` | libuv: duplicate `uv_get_free_memory` |
| `termux-src-node_internals.h` | POSIX credentials (`process.getuid()` etc.) |
| `termux-src-node_report.cc` | `process.report` must not crash when `uv_cpu_info` fails (no `/proc/stat`) |
| `termux-tools-gyp-pylib-gyp-generator-ninja.py` | separate host/target `gen/` dirs for cross ninja builds |
| `termux-tools-v8_gypfiles-toolchain.gypi` | V8 logs to stdout; no `-mbranch-protection` |
| `termux-tools-v8_gypfiles-v8.gyp` | link `-latomic` on android+clang; add `platform-linux.h` |

Own patches:

| Patch | Why |
|---|---|
| `dsh-deps-zlib-zlib.gyp` | zlib: Linux `getauxval` CPU detection instead of NDK `cpu-features.h` (not on the include path; Termux uses a shared zlib) |
| `dsh-deps-openssl-no-asm-android` | OpenSSL without asm, scoped to the OpenSSL targets so no global define rebuilds V8. The aarch64 perlasm crashed in the app process (x18 is the platform register on Android). |
| `dsh-node.gyp-bsymbolic-android` | link libnode with `-Bsymbolic` so its OpenSSL/zlib/uv symbols cannot be interposed by system libraries already loaded in the app process (BoringSSL `OPENSSL_sk_push` crash) |
| `dsh-src-crypto-no-offthread-certs-android` | don't load root certificates on a helper thread; concurrent first use of OpenSSL decoders crashed in the app process |
| `dsh-src-debug_utils.cc` | adapted from Termux: Node's own stderr diagnostics go to fd 2 (the app redirects it to `engine.log`) instead of logcat, unless `NODE_ANDROID_LOG_STDERR_TO_LOGCAT` is defined |

Skipped Termux patches:

| Patch | Why |
|---|---|
| `deps-uv-src-unix-core.c` | only changes the `uv_os_tmpdir` fallback; the app always sets `TMPDIR` |
| `deps-v8-src-flags-flag-definitions.h` | only touches `/tmp` paths in the non-Android branch |
| `lib-child_process.js` | Termux redirects `/system/bin/sh` to its prefix; upstream already uses `/system/bin/sh` on Android |
| `lib-os.js` | `os.tmpdir()` fallback; irrelevant with `TMPDIR` set |
| `node.gyp` + `src-getaddrinfo` | getaddrinfo shim for the `node` executable only, `#ifdef __TERMUX__` |
| `test-*` | tests are not built |

## JNI wrapper (`engine-jni/dshengine.cc`)

```
JNIEXPORT jint JNICALL Java_io_github_timhub135_dsh_engine_NodeRuntime_nativeStart(
    JNIEnv*, jclass, jobjectArray args, jint stdinFd, jint stdoutFd, jint stderrFd);
JNIEXPORT jint JNI_OnLoad(JavaVM*, void*);   // returns JNI_VERSION_1_6
```

`dup2()`s each fd ≥ 0 onto 0/1/2, packs `argv = ["node", ...args]` into one contiguous block (Node may rewrite it in
place) and returns `node::Start(argc, argv)`. A second call in the same process returns -1, because Node cannot be
re-initialized; the app runs it in a dedicated process and restarts that process.

The Kotlin side loads `c++_shared`, `node`, then `dshengine`.

## Launcher (`dshnode/main.cc`)

The engine runs Node inside the app process, so there is no `node` to spawn. `libdshnode.so` is Node's own POSIX
`main()` (`return node::Start(argc, argv)`) linked against `libnode.so`: a PIE executable (`-pie`, interpreter
`/system/bin/linker64`, `RUNPATH $ORIGIN`), named `lib*.so` so the APK installer extracts it into `nativeLibraryDir`,
the only app location Android lets an app exec (W^X forbids its data directory). The app links
`files/bin/node → <nativeLibraryDir>/libdshnode.so` and passes `DSH_NODE_BIN`; the engine uses it as the command of
dsh's `profileContext.packageManager` (bundled pnpm) and of stdio MCP servers. Children are fresh processes: no
`--expose-internals`, and the engine passes `--require …/android-env.cjs` in argv because `AT_SECURE` may make Node
ignore `NODE_OPTIONS`. `process.execPath` in a child is the launcher, so `spawn(process.execPath)` works there.

To verify on a device: exec from `:engine` is allowed by SELinux (`librg.so` is the precedent), `AT_SECURE` of the
child, and peak RSS of a pnpm run.

## Addons

Linked with `DT_NEEDED libnode.so` (`-Wl,--no-undefined -lnode`): Android linker namespaces do not let a dlopen()ed
library resolve symbols from libraries loaded by someone else.

| Addon | Source | Build |
|---|---|---|
| node-pty | npm `node-pty@1.2.0-beta.15` `src/unix/pty.cc` + `node-addon-api@7.1.1` | C++17, `NAPI_CPP_EXCEPTIONS`, no `-lutil` (bionic has `forkpty`/`openpty` in libc) |
| flock | npm `@deepseek-ai/node-addon-system@0.1.2` `src/flock.c` | C11, `NAPI_VERSION=8`; landlock-run is not built |

Upstream `node-addon-system` rejects `android` (`ERR_FLOCK_UNSUPPORTED_PLATFORM`); js patch 01 loads
`$DSH_NATIVE_DIR/libdsh_flock.so` with `process.dlopen` instead of going through its platform-package lookup.

## Sanity checks (`build.sh check`)

For every `.so`/`.node`: ELF machine AArch64; no `PT_INTERP` (except the launcher, which must have
`/system/bin/linker64`, `RUNPATH $ORIGIN`, be PIE and call `node::Start`); NEEDED limited to libnode, libc++_shared, libc, libm,
libdl, liblog, libandroid, libz; `NT_ANDROID_TYPE_IDENT` note present; all LOAD segments 16 KB aligned. Exports:
`node::Start(int, char**)` and N-API from libnode.so, the JNI symbol and `JNI_OnLoad` from libdshengine.so,
`napi_register_module_v1` from every addon.
