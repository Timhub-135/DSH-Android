<div align="center">

<img src="docs/screenshots/hero.webp" width="100%" alt="DSH-Android：让 DSH 替你用手机">

<br />

不要 root，不用电脑，直接跑在你的安卓手机上。真正的手机 Agent，替你把手机上的事干完。

在安卓手机上运行 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）：让它操作 App、整理文件、修改系统设置，或者自动执行任务。

[![Release](https://img.shields.io/github/v/release/jackwener/DSH-Android?style=flat-square&color=4D6BFE&label=release)](../../releases/latest)
![Android](https://img.shields.io/badge/Android-10%2B-34A853?style=flat-square&logo=android&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-555?style=flat-square)
![Engine](https://img.shields.io/badge/engine-dsh%200.2.1%20%C2%B7%20Node%2024-4D6BFE?style=flat-square)
![Root](https://img.shields.io/badge/root-不需要-34A853?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square)

**[下载 APK](../../releases/latest)** • **[快速开始](#快速开始)** • **[架构](#架构)** • **[从源码构建](#开发者)**

</div>

---

## 亮点

- **手机上的 App 都能操作**：用微信发消息、总结群聊，抓取小红书上的内容，在 BOSS 直聘上直接投递、打招呼，去淘宝帮你下单，等等。
- **它操作，你照常用手机**：授权后 Agent 能读屏、点按、输入，过程中你可以随时暂停、停止或插话。Android 14 及以上支持“不打扰模式”：配好 Shizuku 或自连 ADB 后，Agent 在虚拟屏幕上操作，不占用你的屏幕。
- **在任何 App 里随时呼起**：用悬浮球直接对话、派活，也可以把当前这一屏发给它，问“这条怎么回”“帮我记下来”。
- **手机里自带 Linux 环境**：Agent 可以跑 Python、处理你发来的文件、运行它自己写的脚本。
- **出门也能用电脑上的 Agent**：通过 SSH 或 DSH Remote 连接电脑或服务器上的 DSH。只用远程模式的话，手机不用安装本地运行环境。

## 界面一览

<img src="docs/screenshots/showcase-dark.webp" width="100%" alt="界面一览：首页、操作手机、轨迹、随处唤起、手机权限、会话列表">

## 快速开始

1. 从 [Releases](../../releases/latest) 下载 APK 并安装（arm64，Android 10 及以上）。
2. 登录 DeepSeek 账号，或者填入 [API key](https://platform.deepseek.com/api_keys)；也可以在 设置 → 模型服务 里添加其他服务商。
3. 直接说要做什么，比如“列出下载目录里最大的 5 个文件”。用到某项权限时，App 会再引导你开启。
4. 可选：在 设置 → 手机权限 里一键安装 Shizuku，并在 Shizuku 里打开“开机启动（无线调试）”。

## 能做什么

- **文件**：查找、整理、移动、重命名、压缩解压；能读 PDF / Word / Excel / PPT 里的文字。
- **屏幕和 App**：读屏、点击、滑动、输入（基于无障碍服务）；打开 App 或直接跳到指定页面。
- **系统设置**：亮度、音量、勿扰、闹钟、Wi‑Fi、蓝牙、定位；读取和回复通知，控制媒体播放。
- **自动化**：定时执行，或在连上 Wi‑Fi/蓝牙、开始充电、到达某地、收到通知等条件下触发。
- **其他模型服务**：除 DeepSeek 外，可添加 OpenAI、Claude、Gemini、Grok、OpenRouter、Kimi、智谱 GLM、通义千问、火山方舟、MiniMax、硅基流动、阶跃星辰，或任意 OpenAI 兼容接口；填 key、拉取模型列表即可在模型选择器里切换，推理强度按模型记住，只列出该模型支持的档位。
- **完整的 dsh**：dsh 原样运行（没有 fork），技能、MCP、子 agent、网页搜索都能用。
- **不需要 root 或 Termux**：装一个 APK 就能用；Shizuku 可选，能解锁更多系统开关。

## 架构

```mermaid
flowchart LR
  subgraph main[":main 进程（Kotlin · Compose）"]
    UI["对话 · 轨迹 · 审批<br/>唤起层 · 设置"]
    CAP["CapabilityHub<br/>设备工具"]
    TRG["TriggerHub<br/>自动化"]
    SVC["无障碍 · 通知监听<br/>系统助手 · 输入法"]
  end
  subgraph engine[":engine 进程（前台服务）"]
    NODE["libnode.so<br/>Node 24 · bionic"]
    DSH["dsh --profile android<br/>+ device / guard / account 插件"]
  end
  SHZ["Shizuku（可选）"]
  API["DeepSeek API"]

  UI <-- "dsh SDK JSON-RPC（stdio）" --> DSH
  DSH <-- "能力桥 JSON-RPC（socketpair）" --> CAP
  TRG --> DSH
  CAP --- SVC
  CAP -. shell 权限 .-> SHZ
  NODE --- DSH
  DSH -- HTTPS --> API
```

- **引擎嵌在 App 进程里**：Node 24 编译成安卓原生的共享库，通过 JNI 在 `:engine` 前台服务中启动。不用 proot、不用 Termux，也不会碰到安卓 12 以后的“幽灵进程”限制。
- **dsh 不做 fork**：App 只通过 dsh 的 SDK 协议和它对话；安卓相关的适配都放在插件和少量补丁里。
- **手机能力在 App 侧**：所有设备工具都由 Kotlin 实现，通过一条私有的 socket 通道暴露给 dsh（见 [引擎契约](docs/ENGINE-CONTRACT.md)）。

## 系统要求

Android 10 及以上，arm64-v8a；安装包约 110 MB，建议预留 500 MB 可用空间；需要能访问 DeepSeek API。

## 安装失败怎么办

- **提示“安装包没有签名文件”或“未签名”**：v0.3.5 起安装包同时带 v1、v2、v3 三种签名，请换用 v0.3.5 及以后的版本。之前的版本只有 v2 签名，部分国内手机的安装器、文件管理器和应用商店不认。
- **“解析失败”“解析包出现问题”**：多半是安装包没下完整，或者经过微信、QQ 转发后被改了名（比如变成 `.apk.1`）。从 Releases 重新下载，核对 SHA-256 与发布说明一致后再装。
- **“与已安装应用签名不一致”**：手机上装的是自己编译的或别处来的版本，签名和正式版不同。先卸载再装（会清除 App 数据）。
- **“不兼容”“与设备不兼容”**：本 App 只支持 Android 10 及以上的 arm64-v8a 手机。
- **提示“已屏蔽有害应用”**：见下一节。

## 安装时提示“已屏蔽有害应用”

DSH-Android 不在 Google Play 上分发，又用到了无障碍、通知读取、短信等能力，Google Play 保护机制可能把它误判为有害应用。核对过下载的 APK 和发布说明里的 SHA-256 一致后，可以这样安装：

- **在手机上安装**：弹窗里点“更多详情”，再点“仍要安装”。不要点“知道了”，那会取消安装。
- **用 `adb install`**：命令会一直没有输出，因为手机上在等你处理上面这个弹窗，看一眼手机屏幕就行。也可以先执行 `adb shell settings put global verifier_verify_adb_installs 0` 跳过对 adb 安装的检查，装完再改回 `1`。

## 当前限制

- **锁屏后不能操作其他 App**：在有安全锁屏的情况下，系统不允许操作其他 App 的界面。这类任务会暂停，提示你解锁后继续。系统 API 类的任务（文件、设置、通知等）锁屏时照常执行。
- **Shizuku 重启后要重新启动**：建议打开 Shizuku 的“开机启动（无线调试）”（需要 Shizuku 13.6 及以上、Android 11 及以上）。
- **国产 ROM**：部分机型需要手动允许“后台弹出界面”和“自启动”，悬浮球和后台自动化才能稳定工作。App 的体检页会提示你。
- **第三方模型**：只支持 OpenAI 兼容的 Chat Completions 接口（HTTPS）；上下文统一按 128K 计；推理强度只对引擎认得的模型家族（Kimi、GLM、Qwen、GPT、Claude、豆包等）列出它们支持的档位，自定义或改名的模型要在 设置 → 模型服务 → 模型设置 里手动声明「推理控制」，否则不显示推理强度、按接口自己的默认来；联网搜索由 DeepSeek 提供，使用第三方模型时需要另外配置 DeepSeek API key。工具调用的效果取决于所选模型。
- **冷启动**：引擎冷启动目前大约 5 秒，还在优化。

## 开发者

<details>
<summary><b>从源码构建</b></summary>

需要 JDK 21、Android SDK（compileSdk 37.2）、NDK r29。在 arm64 Linux 主机上构建时，需要额外传入 `-Pandroid.aapt2FromMavenOverride=<arm64 版 aapt2 的路径>`。

```bash
# 1. 原生库（libnode 等）：下载预编译产物并校验哈希，不用自己编 Node
native/prebuilt.sh fetch            # → toolchains/out/jniLibs/arm64-v8a/

# 2. 引擎包：dsh + 插件 + 补丁 → engine.zip
OUT_DIR=<engineOut> js/build-engine.sh

# 3. APK
./gradlew :app:assembleDebug -Pdsh.engineOut=<engineOut>
```

- 预编译产物的标签由 Node 版本加上全部原生补丁的哈希生成（比如 `native-node24.22.1-caacecde44`）。只有升级 Node 或改动原生补丁时才需要重编：先运行 `native/build.sh`，再用 `native/prebuilt.sh publish` 发布。
- 主机端到端测试（真实调用 DeepSeek API）：`node js/test/host-e2e.mjs`，API key 放在一个文件里，用环境变量 `DSH_E2E_KEY_FILE` 指定路径（默认是 `~/.config/dsh/deepseek_api_key`）。

</details>

<details>
<summary><b>目录结构</b></summary>

```text
app/        Android App（Kotlin · Compose · Hilt · Room）
  core/engine/       引擎进程、JNI 启动、崩溃恢复
  core/capability/   设备工具（文件、屏幕、系统、通知、媒体……）
  core/trigger/      自动化规则和事件源
  feature/           界面：对话、轨迹、快捷面板、自动化、设置、登录
js/         dsh 的 android profile、插件、补丁，build-engine.sh → engine.zip
native/     Node 24 安卓构建、JNI 包装、原生插件、预编译产物脚本
tools/      字符串、图标、README 首图（tools/ui/hero.py）、App 内更新清单（make-update-manifest.py）等生成脚本
update/     latest.json：App 内更新读取的最新版本清单（docs/RELEASE.md）
docs/       架构说明、引擎契约、设计 token、截图
```

</details>

<details>
<summary><b>翻译 / 添加界面语言</b></summary>

`app/src/main/res/values/` 是中文（默认），`values-en/` 是英文。添加一种语言只需两步：

1. 新建 `app/src/main/res/values-<语言代码>/`，复制 `values-en/` 下的全部 `strings*.xml` 并翻译（lint 的 MissingTranslation 会报漏翻的条目）；
2. 在 `app/src/main/res/xml/locales_config.xml` 里加一行 `<locale android:name="<语言代码>" />`。

设置 → 通用 → 语言 的选项和 Android 13+ 系统设置里的“应用语言”都读这份列表，不用改代码；单元测试 `LocalesConfigTest` 会检查两者一致。引擎拿到的语言（`DSH_ANDROID_LOCALE`）跟随 App 语言。

</details>

<details>
<summary><b>设计文档</b></summary>

- [架构](docs/ARCHITECTURE.md)
- [引擎契约（App ↔ dsh）](docs/ENGINE-CONTRACT.md)
- [设计 token](docs/design-tokens.md)
- [发布流程（含 App 内更新的 update.json）](docs/RELEASE.md)

</details>

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)：agent 引擎
- [Node.js](https://nodejs.org)；安卓构建参考了 [Termux](https://github.com/termux/termux-packages) 和 [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) 的做法
- [Shizuku](https://github.com/RikkaApps/Shizuku)（Apache-2.0）：App 内置了官方安装包
- [ripgrep](https://github.com/BurntSushi/ripgrep)
- [dsh-llm-service-lite](https://github.com/snnh/dsh-llm-service-lite)（MIT，snnh）：第三方模型适配器，原样内置于 `js/packages/llm-service-lite/`
- [magpie](https://github.com/yetone/magpie)（MIT）：服务商预设地址和接入 dsh 的经验（按协议分组、maxTokens 与压缩阈值）

## 声明

本项目是社区项目，与 DeepSeek 官方无关联，也未获其背书。“DeepSeek”及相关商标归其各自所有者所有。

## 许可证

[Apache License 2.0](LICENSE)。内置的第三方组件（Node.js、dsh、dsh-llm-service-lite、Shizuku、ripgrep 等）遵循各自的许可证。
