# 工具结果原生卡片 设计

状态：方案稿（2026-10-30），待确认后实现。

## 0. 第一性原理

一次工具调用的结果有两个读者，需求完全不同：

| | 模型 | 用户 |
|---|---|---|
| 要什么 | 完整、精确、可推理的文字 | 一眼看懂的结论，能直接接着用 |
| 怕什么 | 多余 token、提示词前缀被改动（缓存失效） | 一屏日志、找不到结果、结果用不了 |

现在两者共用一份文字。用户看到的是“工具名 + 参数摘要”，展开才是给模型的原文。查到的天气、设好的闹钟、生成的文件，都埋在折叠行里。

所以要做的是**两条通道**：给模型的文字照旧，原样进引擎；另外给用户一份结构化卡片数据，只存在 App 本地，模型永远看不到，因此不影响 token 和缓存。

三条约束：
1. **卡片是类型化的数据，不是任意界面描述**。工具只能从一组固定的卡片类型里选一种填字段，界面由 App 统一绘制。这样风格一致、能做无障碍、不会被工具输出注入奇怪的界面，后续加工具也不用写界面代码。
2. **卡片上的操作只做“不经过模型的本地操作”**：打开、复制、分享、播放、导航、撤销。会改变手机状态的操作要么是撤销刚做的事，要么回到对话里让模型做。
3. **没有卡片时一切照旧**，现有的工具行就是降级方案。

## 1. 数据流

```
设备工具 ToolResult(content=给模型的文字, card=Card?)
   │ content ──► 能力桥 ──► 引擎 ──► 模型      （不变）
   │ card ─────► SessionStore.appendLocal("app/card", {callId, card})   （只在本机）
引擎内置工具（bash、web_search、文件读写…）
   └─ App 侧 CardDerivers 按工具名从参数和结果文字推导 card（同样只在本机）
界面：Projection 按 callId 把 card 挂到对应工具调用上
```

- `Card` 是 `@Serializable` 的密封类，存在会话事件表里（已有的本地事件机制，和 `app/notice` 一样），会话导出、恢复都跟着走。
- 图片、缩略图不进事件表：存成会话目录下的文件，卡片里只放路径；会话删除时一起删。
- 单张卡片序列化后不超过 16KB；列表最多 20 项，超出写“还有 N 项”。

## 2. 卡片类型

| 类型 | 字段 | 典型来源 |
|---|---|---|
| **Facts** 键值 | 标题、图标、若干“标签：值”行、强调值（可选） | 设备状态、电量、存储、设置读取 |
| **List** 列表 | 标题、项目（标题、副标题、缩略图、点击动作）、总数 | 联系人、短信、通知、日历、文件列表、搜索结果、App 列表 |
| **Media** 媒体 | 图片/视频/音频路径、尺寸、时长 | 截图、拍照、录音、生成的图片 |
| **File** 文件 | 文件名、大小、类型、路径、是否新建 | 写文件、下载、导出、压缩包 |
| **Place** 地点 | 名称、地址、经纬度、精度 | 定位、地址查询 |
| **Event** 日程/提醒 | 标题、时间、重复规则、地点 | 日历新增、闹钟、计时器、自动化规则 |
| **Change** 状态变更 | 项目名、旧值 → 新值、是否可撤销 | 音量、亮度、勿扰、Wi-Fi、蓝牙、系统设置写入 |
| **Message** 消息 | 收件人、内容、渠道、状态（已发/草稿） | 发短信、回复通知、分享到 App |
| **Link** 网页 | 标题、域名、摘要、图标 | 网页读取、浏览器打开 |
| **Screen** 屏幕 | 截图缩略图、App、做了什么 | 接管、关键步骤 |

卡片类型只增不改：新增类型时旧版本 App 读到不认识的类型就降级成工具行。

## 3. 操作

每张卡片最多 3 个操作，从固定的动作类型里选：

| 动作 | 说明 |
|---|---|
| `open` | 打开文件 / 网址 / App / 系统设置页（Intent，FileProvider 授权） |
| `copy` | 复制一段文字（值、地址、号码） |
| `share` | 系统分享面板 |
| `play` | 内嵌播放音频、视频，看大图 |
| `navigate` | 用地图 App 打开地点 |
| `undo` | 撤销刚做的状态变更：工具执行时记下旧值，点一下直接恢复，不经过模型；恢复后卡片标“已撤销”，并往对话里记一条本地备注，让模型下一轮知道 |
| `ask` | 带着这张卡片的上下文往输入框填一句话，比如“把这个文件发给…”，由用户确认发送 |

`undo` 是唯一会改手机状态的卡片操作，限制为：只撤销同一次调用改的那一项；只在本轮结束后 10 分钟内可用；值已经被别人改过就提示不能撤销。

## 4. 界面

- **对话流**：带卡片的工具调用，折叠行下面直接显示卡片，不用展开。连续多次同类调用（比如读了 5 次屏）只显示最后一张卡。读屏、点击这类过程步骤默认不出卡片，只有截图、接管这种关键时刻出 Screen 卡。
- **最终回答下方**：本轮产生的“成果类”卡片（File、Media、Event、Message、Change、Place）汇总在回答下面，即使工具行被折叠进步骤组也看得到。这是用户最常要找的东西。
- **轨迹视图**：检查器里同时显示卡片和给模型的原文，方便对照。
- **悬浮球 / 快捷面板**：任务结束时状态胶囊显示最后一张成果卡片的一句话摘要（例如“已设 7:30 闹钟”），点开回到对话。
- 视觉沿用现有设计令牌（dsh 风格），浅色、深色都适配；卡片有完整的无障碍描述。

## 5. 每个工具出什么卡

| 工具 | 卡片 | 操作 |
|---|---|---|
| `alarm_set` | Event | open（时钟 App） |
| `calendar_add` / `calendar_read` | Event / List | open、ask |
| `contacts_search` | List（姓名、号码） | copy、open（拨号/联系人） |
| `rule_create` / `rule_list` | Event / List | open（自动化页） |
| `clipboard_get` | Facts | copy |
| `sms_send`、`notification_reply`、`share_to_app` | Message | open |
| `sms_read`、`call_log_read`、`notification_list` | List | open |
| `volume_set`、`brightness_set`、`dnd_set`、`sound_mode`、`wifi_toggle`、`bluetooth_toggle`、`mobile_data_toggle`、`flashlight` | Change | undo |
| `device_status`、`setting_get`、`app_info` | Facts | — |
| `location_get` | Place | navigate、copy |
| `file_write`、`file_move`、`archive_create`、`archive_extract` | File | open、share |
| `file_list`、`file_search` | List | open |
| `screen_capture`、`browser_screenshot` | Media | play、share |
| `hand_to_user` 及自动接管 | Screen | — |
| `app_launch` | 不出卡（过程步骤） | — |
| 引擎 `web_search` | List（标题、域名） | open |
| 引擎 `web_fetch`、`browser_read` | Link | open、copy |
| 引擎 `write`、`edit` | File（编辑时显示改动行数） | open、share |
| 引擎 `bash` | 不出卡（保留终端视图） | — |

## 6. 实现要点

- `Card` 模型与 `CardAction`：`core/capability/Card.kt`；`ToolResult` 加 `card` 字段，和 `outcome`、`screen` 一样只在本机。
- `CapabilityHub` 在调用完成时把 card 写成 `app/card` 本地事件（带 callId、工具名）。
- `CardDerivers`：引擎内置工具的推导规则，纯函数，单元测试覆盖。
- `Projection` 把 card 挂到工具块上；`feature/conversation/cards/` 下每种类型一个 Composable。
- `UndoLedger`：Change 卡片的旧值，按 callId 存，过期清理。
- 测试：每个工具的卡片快照测试（JVM），Compose 预览覆盖每种类型的浅色和深色。
- 校验：卡片数据从不进入 `content`。加一个单元测试，断言能力桥发给引擎的 JSON 里没有 card 字段。

## 7. 不做的

- 不让工具或模型自由描述界面（不做任意 UI DSL）。
- 不在卡片上直接执行新的写操作；除了撤销，要做的新事都回到对话里。
- 不把卡片内容回传给模型；用户在卡片上做了撤销，只记一条本地备注。
