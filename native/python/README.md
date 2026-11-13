# native/python — Termux 预编译 Python 3.14 运行时

把 Termux 的 bionic/aarch64 预编译包变成 DSH-Android 可直接打包的产物；**不编译 CPython**。

## 流水线

```bash
# 1) 下载固定版本 Termux 包 + 纯 Python wheel，解包到 staging
native/python/fetch-termux.sh --out toolchains/python-stage

# 2) 规范化：重命名 soname、改写 NEEDED/RPATH/Verneed、扩展模块改名、ext-map、裁剪依赖闭包
python3 native/python/normalize.py toolchains/python-stage --out-dir toolchains/out

# 3) 静态检查（APK 规则、16K 对齐、依赖完整、ext-map、RPATH）
native/python/check.sh toolchains/out
```

产物：

```
toolchains/out/python-jniLibs/arm64-v8a/   # 进 APK 的 lib*.so（launcher/libpython/依赖/扩展模块）
toolchains/out/python-runtime/             # 进 engine.zip 的 python/（stdlib+site-packages+ext-map.txt+licenses）
```

`js/build-engine.sh` 用 `PYTHON_RUNTIME=<python-runtime>` 把它嵌入 `engine.zip` 的 `python/`；APK 侧
`EngineInstaller` 按 `ext-map.txt` 在运行时目录建符号链接指向 `nativeLibraryDir`，并把 Android CA 写入
pip 的 vendored certifi。

## 固定版本

- Python **3.14.10**（Termux `python` deb）
- 51 个 Termux 包（`manifest.json`，含 sha256）+ 7 个纯 Python wheel（pandas/matplotlib 依赖）
- 来源：`https://packages.termux.dev/apt/termux-main`（stable/aarch64）+ PyPI
- 升级 = 改 `manifest.json` 里的版本/哈希并重跑；`fetch-termux.sh` 对任何不一致直接失败

## 实测（2026-11，Android 12/aarch64 本机）

| 项 | 结果 |
|---|---|
| 冷启动 `python -c pass` | ~0.3 s |
| numpy 2.5.4 / pandas 3.1.1 / matplotlib 3.11.2 / Pillow 13.0.0 | 全部可 import；matplotlib Agg 出图 |
| 导入耗时（无 pyc、冷） | numpy 1.2 s、pandas 4.3 s（首次后 pyc 缓存会快） |
| pandas+matplotlib RSS | ~120 MB |
| jniLibs | 206 个 `.so`，73 MiB raw / ~31 MiB deflate |
| python-runtime | 118 MiB raw / ~34 MiB deflate |

APK 增量估算 ≈ 31 + 34 ≈ **65 MiB**（APK ≈ 175 MB），在 165–190 MB 预算内。

## 注意

- 裁剪按“launcher + 扩展模块”的 NEEDED 闭包进行；新增扩展依赖时会在 `normalize.py` 报 missing，按提示往 `manifest.json` 加包。
- 扩展模块在 APK 里叫 `libpy_<hash>.so`，导入名由 `ext-map.txt` 恢复；EngineInstaller 负责建链接。
- RPATH 统一改成 `$ORIGIN`；`DT_SONAME`/`DT_NEEDED`/GNU Verneed 一起改，否则 zlib/openssl 这类带符号版本库会 `verneed` 解析失败。
- 不要在本机跑 gradle/Android Studio；真实编译在 GitHub Actions（`.github/workflows/android.yml`）。
