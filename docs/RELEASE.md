# 发布流程

DSH-Android 只通过 GitHub Releases 分发。App 内更新（`core/update/`）读取每个版本附带的 `update.json`，所以每次发布除了 APK，还要生成并上传这个文件，再把同一份内容提交到仓库的 `update/latest.json`。

## 步骤

1. **改版本号**：`app/build.gradle.kts` 里 `versionCode` 加 1（不能复用），`versionName` 改成新版本。
2. **测试并构建正式签名的 APK**：

   ```bash
   ./gradlew testDebugUnitTest -Pdsh.engineOut=<engineOut>
   ./gradlew assembleRelease -Pdsh.engineOut=<engineOut> \
     -Pdsh.signing.storeFile=<dsh-release.jks> -Pdsh.signing.storePassword=… \
     -Pdsh.signing.keyAlias=dsh-android -Pdsh.signing.keyPassword=…
   cp app/build/outputs/apk/release/app-release.apk DSH-Android-vX.Y.Z-arm64.apk
   ```

3. **写发布说明**：`notes-zh.md`（GitHub Release 的正文）和 `notes-en.md`（英文界面显示）。末尾的 `APK SHA-256: …` 行可以留着，生成脚本会去掉它。
4. **生成 update.json**（包名、版本号、minSdk、签名证书 SHA-256、大小和 SHA-256 都从 APK 读出，`--expect-signer` 防止误用调试签名）：

   ```bash
   tools/make-update-manifest.py DSH-Android-vX.Y.Z-arm64.apk \
     --notes-zh notes-zh.md --notes-en notes-en.md \
     --expect-signer 31c7e843953e3718ae633a6c7396068ddce8ca822f628cd3db4969f6ca3fc6ab \
     -o update/latest.json
   ```

   需要 `apksigner` 和 `aapt2`：默认取 Android SDK 里最新的 build-tools，也可以用 `--apksigner` / `--aapt2`（或环境变量 `APKSIGNER` / `AAPT2`）指定；arm64 主机上用 arm64 版的 aapt2。下载地址默认是本次 Release 的 APK 资产（`releases/download/vX.Y.Z/<APK 文件名>`），`--url` 可以在它后面追加镜像地址。
5. **发布 GitHub Release**（tag `vX.Y.Z`，不要勾选预发布），资产是 APK 和名字必须为 `update.json` 的清单：

   ```bash
   cp update/latest.json update.json
   gh release create vX.Y.Z DSH-Android-vX.Y.Z-arm64.apk update.json -t "vX.Y.Z" -F notes-zh.md
   ```

6. **提交 `update/latest.json`** 到 main 并推送。必须在 Release 公开之后：清单里的下载地址要能访问。
7. **刷新 jsDelivr 缓存**（`@main` 的文件会被缓存几个小时）：

   ```bash
   curl -s https://purge.jsdelivr.net/gh/Timhub-135/DSH-Android@main/update/latest.json
   ```

8. **验证**：`curl -sL https://github.com/Timhub-135/DSH-Android/releases/latest/download/update.json` 能拿到新清单；在装着上一个版本的手机上，设置 → 关于 → 检查更新，应当提示新版本，下载、校验并安装成功。

## 约定

- **v1+v2+v3 签名**：`app/build.gradle.kts` 的正式签名同时开了三种方案。minSdk 29 时构建工具默认不签 v1，而部分国内手机的安装器和应用商店只认 v1，会报“安装包没有签名文件”。`make-update-manifest.py` 会检查三种签名都在，缺一个就不生成清单。
- **签名不能换**：App 只接受签名证书与已安装版本相同的安装包（同一证书，或 APK v3 轮换链里包含旧证书），否则拒绝并删除下载。调试版或自行构建的版本签名不同，只会提示到 GitHub 手动下载。
- **预发布**不附 `update.json`，也不提交 `update/latest.json`：`releases/latest` 本来就跳过预发布，仓库里的副本也不能先指向它。
- **撤回版本**：把 `update/latest.json` 和最新 Release 的 `update.json` 换回上一版即可停止推送。已经装上的版本不会被降级（App 只接受更大的 versionCode）。
- **清单格式**：`format` 是 1。可以加新字段（旧版本 App 会忽略）；不兼容的改动要换新的 `format`，并在原来的位置继续发布 format 1，否则旧版本就再也收不到更新。

## App 端的行为

- **获取**：依次请求 `github.com/…/releases/latest/download/update.json`、`raw.githubusercontent.com/…/main/update/latest.json` 和几个 jsDelivr CDN（cdn / fastly / gcore / testingcf），每 3 秒启动下一个（前一个失败时立即启动），先到的有效清单生效，最多等 60 秒。和 App 的其他请求一样走系统代理。
- **检查**：设置 → 关于的“自动检查更新”默认开启，启动时在后台检查，每天最多一次；“检查更新”随时手动检查。versionCode 不大于已安装版本、minSdk 高于本机或用户跳过的版本不提示（手动检查仍会显示跳过的版本）。
- **提示**：弹窗显示版本、大小和更新说明，可以选更新 / 稍后（一天内不再弹出）/ 跳过此版本。后台检查发现新版本且 App 不在前台时发一条通知。
- **下载**：在 App 内下载到缓存目录，断点续传（HTTP Range），每个地址失败后重试，再换下一个地址；可取消，下次继续。
- **校验**：SHA-256 必须与清单一致；再用 `PackageManager.getPackageArchiveInfo` 读出包名、versionCode 和签名证书，与已安装的 App 对比。任何一项不符都拒绝安装并删除文件。
- **安装**：`PackageInstaller` 会话；没有“安装未知应用”权限时先跳到系统设置，返回后自动继续。有任务在执行时先确认，安装会结束它。
