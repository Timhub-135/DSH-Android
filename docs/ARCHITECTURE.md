# 架构

DSH-Android 把 DeepSeek Harness（dsh）跑在手机上：Node 24 编译成 bionic 共享库 `libnode.so`，通过 JNI 在独立的 `:engine` 进程里启动 dsh；设备能力全部由 Kotlin 实现，经一条私有 socket 暴露给 dsh。协议细节见 [ENGINE-CONTRACT.md](ENGINE-CONTRACT.md)，引擎包见 [js/README.md](../js/README.md)，原生构建见 [native/README.md](../native/README.md)。

代码路径相对 `app/src/main/java/io/github/jackwener/dsh/`。

## 进程模型

```
:main（默认进程）
  Compose UI · QuickActivity · 无障碍 / 通知监听 / 输入法 / 系统助手 / 磁贴 / 悬浮球
  EngineManager ── SessionStore ── CapabilityHub ── TriggerHub
        │  stdin/stdout 管道：dsh SDK JSON-RPC
        │  socketpair（DSH_BRIDGE_FD）：能力桥 JSON-RPC
:engine（前台服务 EngineService，specialUse）
  libdshengine.so → node::Start → dsh --profile android
     + @dsh-android/plugin-device / plugin-guard / plugin-mode / plugin-account
     + llm-service-lite（第三方模型，OpenAI 兼容）
  子进程：/system/bin/sh、toybox、rg（librg.so）
Shizuku 用户服务（shell uid，可选）：ShellUserService
```

- Node 每个进程只能初始化一次，所以引擎独占一个进程，退出即杀进程，下次按需重开。引擎崩溃不影响 UI。
- 无障碍、通知监听、输入法服务由系统绑定在默认进程，因此所有设备工具在 `:main` 执行。
- `:main` 与 `:engine` 之间只有一个 AIDL 调用 `IEngineService.start(env)`，用来把三个 fd（SDK 管道两端、能力桥 socket）交给 `:main`；之后的通信都走这些 fd。不开任何网络端口（账号登录时的 127.0.0.1 回调除外，见下文）。
- minSdk 29，targetSdk 36，仅 arm64-v8a。可执行文件都以 `lib*.so` 形式放在 nativeLibraryDir（`extractNativeLibs=true`），这是 targetSdk ≥ 29 下唯一允许 exec 的位置。

## 引擎生命周期（core/engine/）

**安装**：`EngineInstaller` 读取 assets 里的 `engine.sha256`，取前 16 个十六进制字符作为版本目录，把 `engine.zip` 解压到 `files/engine/<sha16>/`，写 `.installed` 标记，并删除其他版本目录。zip 是扁平的 npm 树，不含符号链接。另外在 `files/bin/` 建 `rg → <nativeLibraryDir>/librg.so` 的符号链接。

**启动**：`EngineManager` 调 `startForegroundService` 并 `bindService(BIND_IMPORTANT)`（不带 `BIND_AUTO_CREATE`，崩溃后系统不会自行重启服务，重启由 `RestartPolicy` 统一处理）。如果残留的 `:engine` 已经跑过 Node（上一个 `:main` 死了而 `:engine` 还活着），先杀掉再重新绑定。`EngineService.start` 依次：解压、创建管道和 socketpair、轮转 `files/logs/engine.log`、设置环境变量、在 16 MB 栈的线程里调 `NodeRuntime.nativeStart`。Node 退出后服务 `stopSelf` 并杀掉本进程。

启动参数：`--expose-internals`、`NODE_OPTIONS` 中的各项、`--require android-env.cjs`、dsh 的 `bin.js --profile android`。zygote 派生的进程带 `AT_SECURE`，Node 会忽略 `NODE_OPTIONS`、`TMPDIR`、`NODE_COMPILE_CACHE`、`NODE_EXTRA_CA_CERTS`，所以选项另外放进 argv，其余由预加载脚本 `android-env.cjs` 从 `process.env` 恢复。

主要环境变量（完整表见契约 §1）：

| 变量 | 值 |
|---|---|
| `HOME` | `files/home`，也是 dsh 的工作目录 |
| `DSH_HOME` | `files/dsh-home`（profile、会话、账号凭据、预算账本） |
| `TMPDIR` | `cacheDir/tmp` |
| `NODE_EXTRA_CA_CERTS` | 由 `AndroidCAStore`（系统 CA 去掉用户停用的，加上用户安装的）导出的 PEM，摘要不变就不重写 |
| `NODE_COMPILE_CACHE` | `cacheDir/node-compile-cache` |
| `NODE_OPTIONS` | `--max-old-space-size=768`，只在 `EngineRuntimeEnv` 合并 |
| `TZ` / `DSH_ANDROID_TZ` | 设备时区；时区变化后在空闲时重启引擎 |
| `DEEPSEEK_API_KEY` | 已配置 API key 时才设置 |
| `DSH_ANDROID_KEY_<ID>` | 每个已启用且存了 key 的第三方服务商一个 |
| `HTTP(S)_PROXY` / `NO_PROXY` | 系统为当前网络设置了代理（Wi-Fi 代理、全局代理、VPN 的代理模式）时才设置，和 `HttpURLConnection`（获取模型列表）走同一条路；排除列表照搬 Android（含 `192.168.*` 这类通配，js patch 15），回环地址总是直连。PAC 不支持。引擎启动时读取，代理变化在下次启动生效 |

启动后先请求 `account/state`，再 `initialize`（provider、model、reasoningEffort 都是进程级的）。模型、思考强度或凭据路由变化时：本代引擎还没开过会话就重新 `initialize`，开过会话就在空闲时重启。第三方服务商的配置（用户 patch 层 + key 环境变量，见下文“模型服务”）只在进程启动时读取，变化后在空闲时重启。

**停止**：没有运行中的会话且账号登录不在进行时，空闲 5 分钟后发 `shutdown`，等进程退出（超时则直接杀）。SDK 没有按会话取消的方法，所以用户点“停止”就是停掉整个引擎进程。

**引擎常驻**（设置 → 关于 → 后台保活，默认关，#52）：插件自己的后台工作（心跳定时器、机器人连接）只活在 `:engine` 里，按需模式下会随空闲停止一起停掉。打开后不做空闲停止；`:main` 每次启动（含开机、App 更新）都拉起引擎，停止或异常退出后按 `RestartPolicy` 的退避重新拉起（熔断时不拉）；`EngineService` 以 `START_STICKY` 运行，进程被系统杀掉后由系统重建服务，服务再发 `EngineKeepAlive.ACTION_RESTORE` 广播唤醒 `:main` 重新启动 Node。有插件跑过后台会话（没有会话行的会话）或启用了名字像心跳 / 机器人的插件时，扩展页提示打开它（`EngineKeepAlive`）。

**崩溃恢复**：SDK 管道 EOF、binder 死亡、`onServiceDisconnected` 三个信号只算一次。`RestartPolicy` 按 1/2/4 秒退避；10 分钟内 3 次失败就熔断，熔断期间不自动重启，自动化直接失败；稳定运行 60 秒后清零。dsh 的 `session/resume` 只会关闭悬空的工具调用，不会续跑，所以 `SessionStore.onEngineLost` 在退避后对每个中断的根会话 resume，再发一条续跑提示（告诉模型已有结果的工具调用不要重做）。每个任务最多自动续跑一次，再次中断或熔断时显示带“继续”按钮的错误。两个进程都被杀时，下次启动把残留的“运行中”会话标为中断。dsh 在事件持久化之前就会发通知，重启后的引擎可能复用序号，`LogSeqs` 按引擎代次给序号加偏移。

**断网续跑**：一轮以网络类错误（`TRANSPORT`、`TIMEOUT`，dsh 自己已重试约 25 秒）结束、且系统判定当前没有可用网络（`NET_CAPABILITY_VALIDATED`）时，会话不算失败，而是进入“等待网络”状态（不持有唤醒锁）。网络恢复后自动发一条续跑提示。这和崩溃续跑共用“每个任务一次”的额度。

**唤醒锁**：只要有会话在运行，`:main` 就持有 `PARTIAL_WAKE_LOCK`（10 分钟超时，随会话事件续期，最多每 30 秒续一次）。等待审批、回答、解锁或网络时不持有。

**诊断**：Node 的 stderr 写到 `files/logs/engine.log`，保留 `.1..3` 三份历史。`:main` 启动时以及每次异常退出后，读取 `:engine` 的 `ApplicationExitInfo`，写 `exit-*.txt`；原生崩溃另存 tombstone `tombstone-*.pb`，各保留 3 份，在设置的自检和关于页显示。debug 包开启 GWP-ASan。

## 两条通道

**SDK 通道**（stdin/stdout）：dsh 原样的 SDK JSON-RPC 2.0，App 是客户端。用到的方法有 `initialize`、`session/prompt`、`session/resume`、`shutdown`、`android/session/permission`、`android/session/mode`，以及 `account/*`。dsh 发来的服务端请求有 `approval/request`、`user-question/ask`。每个 `session.event` 都原样写进 Room 的 `events` 表；对话视图和轨迹视图都是这份日志的投影（`core/session/Projection.kt`）。

**能力桥**（`DSH_BRIDGE_FD`，AF_UNIX socketpair，换行分隔的 JSON-RPC）：`plugin-device` 调用 `capability/list` 把每个 `ToolDescriptor` 注册成 dsh 工具，再用 `capability/invoke` 执行。工具描述是静态的，Shizuku 是否可用、权限是否授予都放在调用结果里返回，避免改动描述导致整段缓存的 prompt 重新计费。超时和取消规则见契约 §3.1：每个工具有一个 `timeoutMs`，App 在截止前 5 秒作答；插件超时或本轮中止时发 `capability/cancel`；所有等待在动手前都会再检查一次调用是否仍然有效。

## 能力层（core/capability/）

`CapabilityHub` 是设备能力的唯一入口。它按顺序检查：工具组是否开启 → 无人值守会话的白名单 → 锁屏 → 执行。工具缺权限时抛 `PermissionMissingException`：有人值守的会话在输入框位置显示授权卡片，用户授权后自动重试；无人值守的会话直接返回 `permission_missing`。截图类结果由 `ShotStore` 按会话保存，供界面显示。

| 组 | 工具 |
|---|---|
| files | `file_search` `file_read` `file_list` `file_stat`（读）；`file_write` `file_move` `file_mkdir` `archive_extract` `archive_create`（写）；`file_delete`（危险）。`file_read` 能抽取 PDF、docx、xlsx、pptx 的文本。Android 11+ 的 `Android/data`、`Android/obb` 经 Shizuku 的 shell 服务（`ShellFiles`，java.io，不拼命令）访问，没有 Shizuku 时返回 `shizuku_required`；压缩工具不支持这两个目录 |
| apps | `app_list` `app_info`；`app_launch` `app_open_link` `app_send_intent` `share_to_app`（需解锁） |
| screen | `screen_read` `screen_capture` `screen_wait` `screen_tap` `screen_swipe` `screen_scroll` `screen_type` `screen_key` `screen_set_wheel`（全部需解锁）；`hand_to_user`（交给用户处理：登录、验证码、付款等）。参数 `screen: agent / main`，有后台屏时默认后台屏（见 `docs/AGENT-SCREEN-V2.md`） |
| system | `device_status` `setting_get`；`sound_mode` `volume_set` `dnd_set` `brightness_set` `flashlight` `wifi_toggle` `bluetooth_toggle` `mobile_data_toggle` `alarm_set` `alarm_show`；`location_get`（敏感）；`setting_put`（危险，仅 Shizuku）；`adb_shell`（写，仅 Shizuku：以 shell 用户运行命令，等同 `adb shell`；engine 的 hardline 同样检查它的命令，不能作为自动化的直接步骤） |
| notifications | `notification_list`（敏感）；`notification_dismiss` `notification_open`；`notification_reply`（危险） |
| comms | `contacts_search` `sms_read` `calendar_read` `call_log_read`（敏感）；`calendar_add` `calendar_new_event`（打开日历自带的新建页，无需日历权限）；`sms_send`（危险） |
| media / clipboard | `media_control`；`clipboard_get`（敏感）`clipboard_set` |
| browser | `browser_open` `browser_read` `browser_screenshot` `browser_scroll` `browser_back` `browser_click` `browser_type`：App 内一个不显示在屏幕上的 WebView |
| rules | `rule_create` `rule_list` `rule_update` `rule_delete` |

风险等级 `read / write / sensitive / dangerous` 定义在 `core/model/Models.kt`。

**审批**分两层：
- 引擎侧 `plugin-guard`：`read` 和 `sensitive` 在对话里直接执行（无人值守的自动化仍按规则授权）；只读 shell 命令和 `HOME`/`TMPDIR` 内的 `write`/`edit` 不询问（权限模式为“仅可查看”或未知时除外）；hardline 禁止清单（删除受保护目录、刷分区、重启、恢复出厂设置、卸载系统包、`curl|sh` 等）在任何授权下都拒绝。
- App 侧 `SessionStore.approve`：按会话的权限模式处理。三种模式对应 dsh 的预设：仅可查看（`read-only`，审批类操作一律拒绝）、询问（`workspace-write`，读取类直接执行，其余弹审批卡片，可选“允许一次 / 本会话允许 / 拒绝”；`dangerous` 每次都要确认）、完全访问（`danger-full-access`，**默认**，只有给别人发消息的 `sms_send` / `notification_reply` 还要确认，其余都自动允许）。没有生物识别确认。每次 `session/prompt` 之前把模式通过 `android/session/permission` 发给引擎。
- 预算：会话和每日 token 上限在设置里配置，通过 `budget/update` 下发，由 `plugin-guard` 在每一步之前检查；超限时发 `budget/exceeded`，会话里显示提示。

**执行路径**：工具优先用系统 API 或 Intent，其次是 Shizuku，最后是无障碍。这条规则也写进了 `plugin-guard` 的 system prompt。

## 会话模式（issue #3）

模式决定一个对话能用哪些工具、附加什么系统提示词，以及选中时套用的权限预设（契约 §2.5）。

- **定义在 App**（`core/model/SessionMode.kt`）：`id`、名称、图标（固定的一组）、附加提示词、手机能力组（`ToolGroup`，null = 全部）、内置工具类别（`EngineTool`：命令行、读文件、写文件、联网、子智能体，null = 全部）、默认权限（null = 跟随通用设置）、技能开关。内置四个：默认（不做任何限制，即原来的行为）、手机操作（全部手机能力 + 读文件/联网/子智能体，操作需确认）、编程（命令行、文件、技能、联网，手机能力只有文件和剪贴板，操作需确认）、只聊天（只联网，仅可查看，关技能）。内置模式不能修改，可以复制；用户模式存在 DataStore（`session_modes_json`），在设置 → 会话模式里新建、编辑、删除。
- **选择**：输入框工具栏的模式标签（权限标签右边）。只能在对话的第一条消息之前选（`SessionLive.modeChangeable`：没有会话行、没有引擎事件、没在运行）；之后标签只显示当前模式，点开说明为什么不能改。选模式时同时套用它的默认权限，之后仍可照常改权限。会话行的 `modeId` 在首次发送时写入；旧会话和从未选过的会话是 null，按“默认”处理，行为不变。被删除的用户模式也回退到“默认”。用户模式的修改从该模式对话的下一条消息起生效。
- **下发与执行**：`SessionStore.prompt` 在每次 `session/prompt` 之前（包括 resume 之后）发 `android/session/mode`，载荷是解析后的完整模式，引擎不保存定义。`plugin-mode` 按根会话记住模式，对根会话和它的子智能体：`agent.ctx.tools.restrict({ deny })` 隐藏被排除的工具（只列当前存在的名字，工具集变化时重新计算），全局 `tools.guard` 兜底拒绝；关技能就隐藏 `skill` 工具（dsh 不再发技能目录）；附加提示词作为每个 agent 的 prompt section；被隐藏工具的 `tool:*` 指引段落从组装后的 prompt 里去掉；`plugin-guard` 的手机说明段按 agent 可见的工具生成。选择记在 dsh 会话日志里（插件记录 `plugin:android-mode/selected`，不进模型上下文）。`CapabilityHub` 再按会话模式拒绝被排除组的设备调用，作为 App 侧兜底（`capability/list` 是全局的，所以不按会话裁剪清单）。
- 无障碍（`DshAccessibilityService`）：读界面元素树、手势、截图（Android 14+ 遇到 `FLAG_SECURE` 页面时显示“请你来操作”的接管卡片）。没开无障碍但 Shizuku 可用时，点按和滑动改用 `input` 命令。
- `screen_type` 逐级回退，每一步都回读字段内容确认：`ACTION_SET_TEXT` → 无障碍输入连接（Android 13+）→ DSH 输入法（当前就是它时）→ Shizuku `input text`（ASCII），或用 `ime set` 临时切到 DSH 输入法再切回 → 剪贴板粘贴。
- Shizuku（`core/platform/ShellAccess`、`ShizukuShell`、`ShellUserService`）：只和官方 Shizuku App 通信，不自带 ADB 客户端。工具分为三类（`ShellUse`）：不需要、可选（Wi‑Fi、蓝牙、移动数据、剪贴板、`setting_get`、`location_get`；没有 Shizuku 时打开系统面板让用户点，或走普通 API）、必需（`setting_put`、`adb_shell`）。shell 服务调用系统服务时统一用 `ShellContext`（包名 `com.android.shell`、uid 2000 的 AttributionSource），不用系统 context 的 `android` 包名（#34）；建后台屏失败时把异常原因带回 App（`lastDisplayError`），模型、诊断页和诊断报告都能看到。后台屏（`core/capability/AgentScreen`）在 Shizuku 就绪时由 `app_launch` / `app_open_link` 自动创建，没有 Shizuku 时退回主屏。必需 Shizuku 的调用会在输入框位置显示 Shizuku 卡片，Shizuku 就绪后自动继续。APK 内置官方 Shizuku 安装包（构建时由 `tools/fetch-shizuku.sh` 下载并校验哈希和签名），通过 `PackageInstaller` 在 App 内安装或升级。
- 锁屏：`needsUnlock` 的工具在锁屏或灭屏时挂起，发通知“解锁后继续”，收到 `USER_PRESENT`/`SCREEN_ON` 后执行；超过预算时间返回 `waiting_for_user`，不执行任何操作。

## 自动化（core/trigger/）

规则 `RuleSpec`：一个触发器、若干条件、一个动作，外加一份 `allowedTools` 白名单。规则只能通过和 agent 对话创建（`rule_*` 工具会校验并规范化规则）；自动化页面负责列表、开关、详情和执行记录。

| 触发器 | 实现 |
|---|---|
| 定时（每天/每周的某个时刻、每隔 N 分钟（至少 15 分钟）、单次） | AlarmManager `setExactAndAllowWhileIdle`；没有精确闹钟权限时退回 `setAndAllowWhileIdle` |
| Wi‑Fi（可按 SSID）、蓝牙（可按设备名）、充电、低电量、耳机、亮屏/灭屏/解锁 | `RuleMonitorService`：只在有这类规则时运行的轻量前台服务，不加载引擎 |
| 收到通知（可按包名和关键词） | `DshNotificationListener` |
| 某个 App 被打开 | 无障碍服务的窗口变化事件 |
| 进入或离开某个区域 | 有 Play 服务时用 `GeofencingClient`（属于后台启动前台服务的豁免）；没有时用 `LocationManager.addProximityAlert` |

条件：时间段、星期几、电量范围、是否在充电。同一条规则 60 秒内只触发一次，24 小时内最多 50 次。开机、App 更新、精确闹钟权限变化时由 `BootReceiver` 重新注册。

动作有两种：`steps` 直接调用 `CapabilityHub`，不启动引擎、不调用模型；`agent` 新建一个 `auto_<ruleId>_<uuid>` 会话。无人值守会话不会弹审批：白名单内的工具自动允许，其他的拒绝；`ask_user_question` 由 `plugin-guard` 直接回答“没人在场”。Android 12+ 不允许从后台启动前台服务时，降级为一条“点此执行”的高优先级通知（`DeferredRunActivity`）。

## 入口

都会打开 `QuickActivity`（singleInstance 的半屏面板，和主界面共用输入框和卡片）：
- 悬浮球 `FloatingBallService`：可在设置中开启，贴边停靠，主界面在前台时隐藏；通过无障碍读取当前屏幕内容作为上下文。
- 系统助手：`DshVoiceInteractionService` 从 `AssistStructure` 取得当前屏幕内容（长按电源键或手势唤起）。DSH 没有语音识别，`DshRecognitionService` 只是为了能被选为助手。
- 快捷设置磁贴 `QuickTileService`。
- 分享：`SEND`/`SEND_MULTIPLE`，文字、图片和文件放进输入框，由用户确认后发送。
- `io.github.jackwener.dsh.RUN_TASK`（extra `task`）：受“允许其他 App 调用”开关控制，默认关闭，关闭时提示并拒绝；不附带屏幕内容。

面板顶部显示“当前页面”标签，可以取消附带。

## 界面（feature/）

- `MainActivity` 用 Navigation Compose，没有底部导航。没有凭据时先进入引导页（API key 或账号登录），然后是可选的 Shizuku 增强步骤，最后到首页。
- 首页 `HomeScreen`：单会话页，左侧是模态抽屉 `SessionDrawer`（新会话、自动化、会话列表、账号）。顶部在“对话 / 轨迹”两个视图之间切换。对话视图（`Stream`、`ToolRows`）把工具调用折叠成步骤行；轨迹视图（`feature/trajectory/`）移植自 dsh ui-trajectory：时间轴总览，加按轮次分组的记录，点开是检查器。
- 输入框位置一次只显示一样东西，优先级是：授权卡片 → 审批卡片 → 问题卡片 → 输入框。运行中发送的消息默认进入队列（可在设置里改）。附件支持最多 4 张图片，文档会复制到 `home/attachments`。
- 输入框工具栏：`+`、权限标签、会话模式标签、上下文环、模型标签、发送。
- 设置 `SettingsScreen`：分为账号、通用、模型服务、能力（工具组开关）、会话模式（列表 + 编辑页）、用量、手机权限（`PermissionCatalog`，各项权限用到时才申请）、关于（自检 `ReliabilityDoctor`、引擎重启、崩溃记录）。
- 视觉对齐 dsh Web：颜色、字号、圆角取自 dsh 的 ui-theme，映射到 Material 3，不启用动态取色。见 [design-tokens.md](design-tokens.md) 和 `core/designsystem/`。

## 账号登录

dsh-base 自带 DeepSeek 账号 provider（OAuth + PKCE，凭据存在 `DSH_HOME`）。`plugin-account` 补上回环回调服务器，并把账号操作暴露为 SDK 方法 `account/*` 和通知 `account.*`（契约 §2.4）。授权页在 App 内的 WebView 里打开（`PlatformWebScreen`；整个 OAuth 流程的 http(s) 跳转和弹窗都留在这个 WebView 会话里。Google 拒绝内嵌浏览器时（`disallowed_useragent`），或用户点“用浏览器登录”，改为在 Custom Tab 里从头走一遍，见契约 §2.4），回调到 `http://127.0.0.1:<port>/oauth/callback`（`network_security_config` 只对 127.0.0.1 和 localhost 允许明文）。完成页通过 `intent://…;scheme=dsh-android` 回到 App。路由优先级和 dsh 一致：配置了 API key 就用 `deepseek-official`，否则在已登录时用 `deepseek-account`。API key 用 Android Keystore 的 AES-GCM 加密，存在 `noBackupFilesDir`；`allowBackup=false`。

## 模型服务（第三方模型，core/provider/）

除 DeepSeek（API key / 账号登录，路由 `deepseek-official` / `deepseek-account`，不变）外，可以添加 OpenAI 兼容的服务商。设计取舍：

- **引擎**：不重开 `llm-pi-ai`（它会带回数十 MB 厂商 SDK 和约 500 个启动模块），而是挂载 vendored 的 [`@snnh/dsh-llm-service-lite`](https://github.com/snnh/dsh-llm-service-lite) 0.2.1（MIT，为 dsh 0.2.1-rc.2 编写，在 rc.5 上运行；`js/packages/llm-service-lite/` 里原样保存其构建产物 `lib/index.js`，去掉 Web 配置界面）。没有服务商时这一行休眠，不注册任何路由。
- **协议**：只用 OpenAI Chat Completions（`openai-chat-completions`）。国内外主流厂商都提供这个兼容接口，界面里不让用户选协议。
- **预设**（`PRESETS`，一行一个）：OpenAI、Anthropic、Gemini、xAI、OpenRouter、Kimi、智谱、通义千问、火山方舟、MiniMax、硅基流动、阶跃星辰，以及“自定义（OpenAI 兼容）”。地址参考 [magpie](https://github.com/yetone/magpie) 的 `internal/provider/presets.go`（MIT），只取各家的 OpenAI 兼容地址；默认模型 ID 只是起点。仅支持 HTTPS（`network_security_config` 不变）。
- **配置下发**：App 在每次启动引擎前生成设置层 `$DSH_HOME/android/app-layer.patch.yml`（`renderProfilePatch`，契约 §1.1；引擎入口把它作为 `--patch` 覆盖层，profile 自己的 `cordis.patch.yml` 归 dsh 的插件管理和配置编辑，见 PLUGINS-CONTRACT.md），里面只有 `apiKeyEnv` 名字，没有密钥。每个服务商是一条路由（路由 ID = 预设 ID，重复时加 `-2`…）。
- **密钥**：`SecretStore` 的命名槽位（`provider-<路由 ID>`，与 DeepSeek key 同一个 Keystore AES-GCM 密钥，`noBackupFilesDir`），启动引擎时作为 `DSH_ANDROID_KEY_<ID>` 环境变量传入。设置里只存 `hasKey`。
- **模型元数据**：不做逐模型的能力矩阵。第三方模型的上下文窗口 / 输出上限按「单模型手动 › 服务商手动 › `/models` 返回值 › 默认 128K / 16K」取值（`max_tokens`，避免厂商默认值过小截断工具调用）、压缩 headroom 16K（借鉴 magpie 的经验：dsh 的压缩阈值 = min(80% 窗口, 窗口 − maxTokens − headroom)，默认 64K headroom 会在 48K 就压缩）。不发推理等级，由适配器按模型家族的默认值处理；模型选择器对第三方模型隐藏推理等级，上下文环按同一有效窗口计，并标明来源（接口返回 / 手动设置 / 默认）。
- **界面**：设置 → 模型服务：DeepSeek 卡片、第三方服务商列表（开关、编辑、删除）、“添加服务商”（选预设 → 填 key → “获取模型列表”即 `GET {baseURL}/models`，兼作连接测试，也可手动输入模型 ID）、默认模型。输入框的模型菜单按服务商分组。引导页也可以直接进入模型服务。
- **选择回退**：所选服务商被删除、停用或模型不在列表里时回到 DeepSeek（`resolveChoice`）。第三方路由 `initialize` 失败时引擎改用 DeepSeek 路由启动，对话报出该路由的错误。
- **联网搜索**：`web_search` 由 DeepSeek 提供，需要 DeepSeek API key（或账号路由下的账号登录）。选用第三方模型且没有 API key 时，patch 层关掉 `tool-web.search`，设置页显示“需要 DeepSeek API Key”；`web_fetch` 不受影响。

## 应用内更新（core/update/）

- 不在 Google Play 上分发，更新走 GitHub Releases：每个版本附带 `update.json`（`tools/make-update-manifest.py` 从 APK 生成，同一份提交为 `update/latest.json`），发布步骤见 [发布流程](RELEASE.md)。
- `AppUpdater`（单例，`DshApp` 启动时 `start()`）：从 GitHub Release、raw.githubusercontent.com 和 jsDelivr 依次错开请求清单（`firstSuccess`，先到的有效清单生效），自动检查每天最多一次；`ApkDownloader` 断点续传到 `cacheDir/update/`，SHA-256 不符换下一个地址；`ApkCheck` 用 `getPackageArchiveInfo` 核对包名、versionCode 和签名证书（与已安装的 App 比，不只看清单）；最后像 `ShizukuInstaller` 一样用 `PackageInstaller` 会话安装。
- 界面：`feature/update/UpdateUi.kt` 的提示弹窗挂在 `MainActivity` 上（对话框自带返回处理，不加 BackHandler），设置 → 关于有“检查更新”和“自动检查更新”。

## 存储

第三方服务商列表存在 DataStore（JSON），密钥存在 `SecretStore`。用户的会话模式也存在 DataStore（JSON）。Room（`core/data/Database.kt`，版本 3）：`sessions`（含 `preset`、`modeId`）、`events`（原样的 dsh 事件，加上 App 本地记录 `app/*`，后者用负序号）、`rules`、`rule_runs`、`usage`（按天的 token 数）。设置存在 DataStore。dsh 自己在 `DSH_HOME` 里持久化会话，以便 resume。

Room 升级必须写 `Migration`（`Migrations.ALL`）：从 2 起的每个已发布版本都原地迁移（v2 → v3 只是 `ALTER TABLE sessions ADD COLUMN modeId TEXT`），只有预发布的 v1 还会被重建（`fallbackToDestructiveMigrationFrom(1)`）；降级安装才会清空。缺少迁移时 Room 直接报错，而不是悄悄删掉所有对话。`MigrationTest` 在 JVM 上用 sqlite-jdbc 按冻结的 v2 schema（`src/test/resources/room-schemas/2.json`）建库、灌数据、跑迁移 SQL，再逐列对照 Room 为当前版本导出的 schema（`app/schemas/`，构建时生成）。

## 构建

```
native/prebuilt.sh fetch          # 预编译原生库 → $WORK/out/jniLibs/arm64-v8a
js/build-engine.sh                # engine.zip + engine.sha256 → $OUT_DIR
./gradlew :app:assembleDebug -Pdsh.engineOut=<含 engine.zip/engine.sha256 的目录>
```

- 原生库：`native/build.sh` 交叉编译 Node 24.22.1（`--shared --dest-os=android`，Termux 补丁加少量自有补丁）、JNI 包装 `libdshengine.so`、flock 和 node-pty 两个插件（打包为 `libdsh_flock.so`、`libdsh_pty.so`）。产物以 GitHub Release 发布，tag 是 `native-node<版本>-<输入哈希>`。`librg.so` 是上游的 ripgrep 二进制。
- 引擎包：用 npm 按 lockfile 安装锁定版本的 dsh，复制 `js/packages/*`（含 vendored 的 `@snnh/dsh-llm-service-lite`，其依赖必须已在 lockfile 树里且版本满足），打 `js/patches/*`，裁剪掉用不到的包，校验没有符号链接，生成确定性的 zip。
- Gradle 属性：`dsh.nativeOut`（默认 `toolchains/out/jniLibs`）、`dsh.engineOut`（默认 `toolchains/out/engine`，即 build-engine.sh 的输出目录）、`dsh.thirdPartyOut`（Shizuku APK，默认 `toolchains/out/third-party`）、`dsh.fetchShizuku=false`（不内置 Shizuku）。在 arm64 Linux 主机上构建时需要传 `-Pandroid.aapt2FromMavenOverride=<arm64 aapt2>`。`toolchains/` 已被 gitignore。
- 技术栈：Kotlin、Compose + Material 3、Hilt、Room、DataStore、kotlinx.serialization、Coil 3、multiplatform-markdown-renderer、Shizuku API、Play services location（只用于地理围栏）、pdfbox-android。
