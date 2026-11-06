# 后台屏操作 App 2.0 设计

状态：设计稿（2026-10-30）。参考业界做法，按我们的架构（dsh 引擎 + App 能力桥 + Shizuku）重做。

## 0. 第一性原理

用户要的是“一句话交代，事情办成，不打扰我”。拆开是四件事：

1. **不占屏**：智能体在自己的屏上干活，用户照常用手机。
2. **看得准、做得对**：每一步都知道屏上有什么、上一步有没有生效。
3. **卡住时交给人，而不是硬闯或乱试**：登录、验证码、付款、禁止截屏的页面交给用户；同一件事反复失败就停下说明。
4. **看得见进度**：用户随时看到它在做什么、要不要自己出手。

从用户角度还有两条约束：
- **零配置**：模型不该管“屏幕编号”这类事，用户也不该先去开什么“实验功能”。
- **少弹窗**：延续 v0.1.3 默认完全权限的方向，这一层不新增确认。

## 1. 后台屏（转正，默认开启）

**现在**：`vscreen_start` 是实验工具，要模型自己建屏、自己传 `displayId`。
**改成**：后台屏由 App 管，模型不再看到屏幕编号。

- **自动建屏**：Shizuku 可用且设置“在后台屏操作 App”开着（默认开）时，会话里第一次 `app_launch` / `app_open_link` 就在后台屏打开。之后这个会话的 `screen_*` 默认作用于后台屏。会话空闲 10 分钟或 App 退出时释放。
- **模型只看到两个屏**：工具参数 `screen: "agent" | "main"`，默认 agent（有后台屏时）。需要用户当前屏的内容（“这个怎么回”）用 `main`。
- **没有 Shizuku**：退回主屏操作，`app_launch` 结果里明确写“在你的屏幕上操作”。设置页给一键引导装 Shizuku。
- **建屏参数**（Shizuku 侧，shell 身份）：
  - 基础：`PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL`
  - Android 13+：`TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED`
  - Android 14+：`OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED`（后台屏有独立焦点，不抢用户键盘）
  - 输入法策略设为“只在后台屏本地显示”（`IWindowManager.setDisplayImePolicy`）。
  - 尺寸和密度同主屏。
  - **兼容模式（不受信任的后台屏，#60）**：`TRUSTED` 和 `OWN_DISPLAY_GROUP` 都要 `ADD_TRUSTED_DISPLAY`（signature 权限，AOSP 给 shell，但有的 ROM 没给，如华为 EMUI 14.2 / Android 12）。所有受信任的 flag 组都失败后，用 `PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL`，再不行用 `PUBLIC | OWN_CONTENT_ONLY`。不加 `PRESENTATION`（会让别的 App 把后台屏当副屏投放内容）。
    - 一次 `SecurityException: Requires ADD_TRUSTED_DISPLAY …` 之后，Shizuku 服务把它记在内存和 `/data/local/tmp/dsh_vd_untrusted`（按 SDK + 系统指纹 + 本次开机），之后直接从不受信任的 flag 组开始，不再刷同样的异常；重启或系统升级后重新试一次。
    - 诊断信息：`agent screen: degraded (untrusted display: no ADD_TRUSTED_DISPLAY, SDK 31)`；全部失败时 `unsupported (…)`，原因在 `agent screen last failure`。“诊断”和设置项说明里写明限制。
    - 仍然可用：`am start --display`（shell 有 `INTERNAL_SYSTEM_WINDOW`）、`input -d` 注入、截帧、`ACTION_SET_TEXT` 输入。
    - 限制：
      - 输入法策略固定为“回退到主屏”（`setDisplayImePolicy` 对不受信任的屏会被拒，不再调用）：后台屏上的输入框获得焦点时，键盘会弹在用户屏幕上。输入文字本身不依赖键盘（`ACTION_SET_TEXT` / `input -d text`）。
      - 不受信任的屏只允许 `allowEmbedded` 的 Activity 由别的 App 启动上去：App 内跳到另一个 App（付款、分享）时，新 App 会落在主屏，由“跨 App 跳转回收”挪回（`moveRootTaskToDisplay` 在个别 ROM 上可能被拒，此时留在主屏）。
      - 没有独立的显示组 / 电源组：后台屏跟随主屏亮灭，不加专用唤醒锁；主屏熄灭时后台屏的调用返回 `agent_display_off`（与 Android 12 及以下相同），亮屏后恢复；“熄屏后重建”只在主屏亮着时做。
      - 没有独立焦点（`OWN_FOCUS` 需要受信任的屏）；没有系统栏；禁止截屏（`FLAG_SECURE`）的页面照旧是黑的，走接管。
- **静音**：后台屏上打开的 App 用 `appops set <pkg> PLAY_AUDIO ignore` 静音，原值记下，释放屏或 App 被挪到主屏时恢复；Shizuku 服务重启时先恢复残留。
- **用户正在主屏用同一个 App**：启动前检查。是的话不抢，结果里告诉模型“用户正在用 X”，由模型决定是等还是问用户。
- **实时小画面**：智能体在后台屏工作时，对话页顶部显示一块可点开的小画面（约 2 帧/秒，宽 360），点开全屏看，可以“我来接管”。

## 2. 接管与交还

- **新工具 `hand_to_user(reason, kind)`**：kind = login / captcha / payment / secure_page / choose / other。
- **流程**：
  1. App 发“需要你处理”通知 + 对话里一张卡片：原因、[我来处理] [跳过]。
  2. 点“我来处理”：把后台屏最上面的任务挪到主屏（`IActivityTaskManager.moveRootTaskToDisplay`，挪“最上面的任务”是因为登录/付款页常开在另一个任务里），恢复声音。
  3. 卡片变成 [处理好了] [我自己做完了] [不做了]。
  4. “处理好了”：把任务挪回后台屏，智能体重新读屏再继续。“我自己做完了”：只读屏核实、不再操作。“不做了”：结束这一步。
- **自动触发**：
  - 后台屏上连续两帧全黑且有 App 窗口 → 判为禁止截屏页，直接走接管（不让模型盲点）。
  - 现有主屏的 FLAG_SECURE 检测保留。
- **跨 App 跳转回收**：后台屏上点了某个按钮后 3 秒内，主屏前台出现了一个用户没主动打开的 App（比如跳去支付宝付款），就把它挪回后台屏。只挪不重开，保住“谁打开的它”。系统选择框（“用哪个 App 打开”）留在主屏，提示用户选。

## 3. 读屏与动作

### 3.1 稳定编号
- 编号从纯数字改成 `e1, e2…`，**相邻两次读屏里没变的元素沿用同一个编号**（按 类名+文字+描述+资源 id 匹配，位置漂移在 48px 内）。模型手里的编号不会因为一次刷新全部作废。
- 用了已消失的编号：按原特征在当前屏重新找，找到就用并提示，找不到才报错。

### 3.2 只发差异
- 动作后的读屏：变化不大时只发“新增 / 消失 / 改变”的元素和前台 App，标明“其余不变”。页面切换（前台 App 或大半元素变了）才发全屏。
- `screen_read` 永远发全屏。

### 3.3 文字识别兜底
- 随 APK 带 Google ML Kit 离线文字识别（中文 + 拉丁，bundled 模型，约 +8MB）。
- 触发：控件树里可读文字太少（自绘界面、Flutter、游戏、WebView 没暴露），或模型传 `ocr: true`。
- 输出：每行文字 + 点击中心坐标，编号 `t1, t2…`，可直接给 `screen_tap`。
- 禁止截屏的页面不识别。

### 3.4 动作效果
每个动作的结果都报实际效果，而不只是“已点击”：
- **没反应**：点击前后界面指纹（元素文字哈希）相同 → `效果：界面没有变化`。
- **输入核对**：已有，保留。
- **频率限制**：动作后新出现“操作过于频繁 / 已达上限 / 稍后再试 / too many / limit reached”一类文字 → `效果：App 提示操作太频繁`，并让模型停下告诉用户。
- **跳到别的 App**：报“已切到 X”。

### 3.5 防打转
App 侧按会话统计，提醒文字附在工具结果后面，到上限时拒绝执行并要求模型停下汇报：

| 信号 | 提醒 | 叫停 |
|---|---|---|
| 同一动作 + 同一目标（最近 16 次内） | 3、6 次 | 12 次 |
| 动作连续没效果 | 3、6、9 次 | 12 次 |
| 界面没变时重复读屏 | 5、8 次 | 12 次 |
| 连续失败 | 2、4 次 | 8 次 |

用户插话、新一轮对话时清零。

## 4. App 使用知识包

- 自己编写 18 个常用 App 的使用知识（`APP.md`，纯事实：界面结构、入口、易混点）。
- 放在 App 资源 `assets/knowledge/apps/<id>/APP.md`，构建时生成“包名 → 知识”索引并校验格式。
- **自动给**：会话里第一次进入某个有知识的 App（`app_launch` 或读屏发现前台换了）时，把知识正文附在那次工具结果后面。每份每个会话只给一次，每轮最多 3 份。
- **工具 `app_guide(app)`**：按名字或包名查。
- 网站知识（`SITE.md`）这次不做，先做 App。
- **App 经验库**（本机学到的，比内置知识更准）：`app_note` 记一句话的界面事实（入口 / 易错 / 事实），成功的回合自动记下点击路线（只用元素文字，≤6 步，不含输入和坐标），回合顺利结束后可再用一次简短的模型调用总结 0–3 条。首次进入 App 时本机经验排在内置知识前面（最多 12 条，超出时按和本轮请求的相关度挑）；App 更新后标为旧版本。设置 → 能力 → App 经验 可查看和清空。

## 5. 防风控节奏

- **对外动作识别**：点击目标文字是“发送 / 提交 / 发布 / 评论 / 打招呼 / 投递 / 关注 / 回复 / send / submit / post / reply”一类。
- **节奏**：同一个 App 里，第一次不等；之后两次之间随机隔 8–20 秒，越密越长，最多 30 秒。等待在执行前进行，结果里注明“为避免被 App 判为机器操作，等了 N 秒”。
- 配合 3.4 的频率限制检测：App 提示太频繁就停。
- 不是安全确认，不弹窗。

## 6. 体验

- **悬浮球状态**：智能体在后台屏干活时，悬浮球显示“干活中”动画；需要用户时变成“需要你”（橙色），点开直接到接管卡片。
- **双击悬浮球**：把当前这一屏（截图 + 文字）发进最近的对话，打开快捷面板等用户补一句话。
- **每轮小结**：轨迹视图顶部一行：用时、工具调用次数、Token、首次反馈时间（引擎已有的数据）。

## 7. 改动清单

| 层 | 文件 | 改动 |
|---|---|---|
| Shizuku 侧 | `IShellService.aidl`、`ShellUserService.kt` | 建屏 flag 按版本；输入法策略；`moveTaskToDisplay`；任务查询（某屏最上面的任务、主屏前台）；帧变化查询 |
| 后台屏管理 | 新 `core/capability/AgentScreen.kt` | 每会话一块屏：懒创建、空闲释放、静音账本、小画面帧流 |
| 读屏 | `DshAccessibilityService.kt`、新 `ScreenModel.kt` | 结构化节点、稳定编号、差异、指纹 |
| 识别 | 新 `ScreenOcr.kt` | ML Kit 中文+拉丁 |
| 工具 | `ScreenTools.kt`、`AppTools.kt`、`VirtualScreenTools.kt` | `screen` 参数替代 `displayId`；效果报告；`hand_to_user`；`app_guide`；删掉 `vscreen_start/stop` |
| 守卫 | 新 `ActionGuard.kt` | 防打转 + 防风控节奏 |
| 接管 | `SessionStore.kt`、`Slots.kt` | 接管卡片三态、挪屏 |
| 知识 | `assets/knowledge/`、新 `AppKnowledge.kt` | 索引 + 自动注入 |
| 界面 | `HomeScreen`、`FloatingBallService`、`TrajectoryView` | 小画面、悬浮球状态与双击、每轮小结 |
| 提示词 | `plugin-guard` | 屏幕操作一节：后台屏、接管、效果、知识 |

## 8. 真机验收（@kabi）

1. 装 Shizuku 授权后，“打开设置，看一下电池电量”：在后台屏完成，主屏不动，对话页有小画面。
2. 后台屏操作时在主屏打字：键盘不被抢（Android 16 机型）。
3. 后台屏打开有声音的 App：没有声音；挪到主屏后声音恢复。
4. 让它打开一个需要登录的 App：出现接管卡片和通知 → 我来处理 → App 到主屏 → 处理好了 → 回到后台屏继续。
5. 同一个按钮故意点不动（禁用按钮）：结果报“界面没有变化”，3 次提醒，12 次前停下。
6. 一个自绘界面的 App（如某游戏或 Flutter App）：读屏走文字识别，能按 t 编号点击。
7. 打开微信（或任一有知识的 App）：第一次进入时结果里附了知识。
8. 连续“发送”三次：第二、三次前有 8–20 秒间隔。
9. 不装 Shizuku：退回主屏操作，结果写明。
10. 悬浮球：干活中 / 需要你两种状态；双击把当前屏发进对话。
