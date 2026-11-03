# Engine ↔ App contract

The Android app (Kotlin) and the embedded dsh engine (Node 24 in process `:engine`) talk over exactly two channels. This file is the single source of truth for both sides.

## 1. Launch

`NodeRuntime.nativeStart(argv, stdinFd, stdoutFd, stderrFd)` (JNI, `libdshengine.so`) runs:

```
node --expose-internals <NODE_OPTIONS flags> --require <ENGINE_DIR>/node_modules/@dsh-android/bundle-android/android-env.cjs \
     <ENGINE_DIR>/node_modules/@dsh-android/bundle-android/main.mjs
```

`main.mjs` is dsh's own CLI (`@deepseek-ai/dsh/lib/bin.js --profile android`) plus the plugin bookkeeping of
[`PLUGINS-CONTRACT.md`](PLUGINS-CONTRACT.md) §1 (crash attribution, safe mode, the app layer as a `--patch` overlay,
the bundled pnpm as dsh's package manager). Arguments after it reach the booted app like bin.js's inner arguments.

`--expose-internals` replaces the `node-addon-require-builtin` native addon (js patch 02); Node rejects it in
`NODE_OPTIONS`, so it is argv-only (`EngineRuntimeEnv.nodeArgv`). stderr goes to `<filesDir>/logs/engine.log`
(previous runs rotated to `engine.log.1..3`).

**AT_SECURE.** Zygote-forked app processes run with `AT_SECURE=1`, so Node's `SafeGetenv` ignores
`NODE_OPTIONS`, `NODE_COMPILE_CACHE`, `NODE_EXTRA_CA_CERTS` and the `TMPDIR` lookup behind `os.tmpdir()` (which
then falls back to the nonexistent `/tmp`), although `process.env` still shows them. The app therefore also
passes the `NODE_OPTIONS` flags in argv, and the `android-env.cjs` preload restores `os.tmpdir()`, the compile
cache and the extra CA certificates from `process.env` (it logs `android-env: … restored …` when it acts).

Environment (set with `Os.setenv` before start):

| Var | Value |
|---|---|
| `DSH_HOME` | `<filesDir>/dsh-home` |
| `HOME` | `<filesDir>/home` (also the default workspace / cwd) |
| `TMPDIR` | `<cacheDir>/tmp` |
| `SHELL` | `/system/bin/sh` |
| `PATH` | `<filesDir>/bin:/system/bin:/system/xbin` (`files/bin/rg` → `<nativeLibraryDir>/librg.so`, `files/bin/node` → `<nativeLibraryDir>/libdshnode.so`) |
| `DSH_NODE_BIN` | `<nativeLibraryDir>/libdshnode.so`, the node launcher for engine children (pnpm, npm stdio MCP servers). Optional: the engine falls back to `$DSH_NATIVE_DIR/libdshnode.so` and exports the value it uses |
| `DSH_PLUGIN_REGISTRY`, `DSH_PLUGIN_FALLBACK_REGISTRIES`, `DSH_ANDROID_SAFE_MODE`, `DSH_MCP_*` | plugin download source, safe-mode start, MCP header secrets: [`PLUGINS-CONTRACT.md`](PLUGINS-CONTRACT.md) §1 |
| `DSH_NATIVE_DIR` | `<nativeLibraryDir>` (native addons ship there as `libdsh_pty.so`, `libdsh_flock.so`) |
| `DSH_BRIDGE_FD` | fd number of the capability socket (see §3) |
| `DEEPSEEK_API_KEY` | decrypted key (never logged); **absent** when the user has no API key (account login, or nothing yet) |
| `DSH_ANDROID_KEY_<ID>` | one per enabled third-party provider with a stored key (`<ID>` = route id upper-cased, non-alphanumerics → `_`); decrypted from Keystore-encrypted storage, never logged, never written to disk in plaintext. Named by the provider's `apiKeyEnv` in the patch layer (§1.1) |
| `DSH_ANDROID_HDR_<ID>_<HEADER>` | one per secret extra request header of an enabled provider (same mangling over `<route id>_<header name>`); stored and passed like the key. Named by the provider's `headerEnv` (§1.1) |
| `DSH_ANDROID_PACKAGE` | the app's package name (the sign-in completion page's `intent:` link) |
| `DSH_ANDROID_LOCALE` | e.g. `zh-CN` (system prompt language hint) |
| `TZ`, `DSH_ANDROID_TZ` | the device's IANA zone (`TimeZone.getDefault().id`, e.g. `Asia/Shanghai`). Node's ICU finds no zone on Android otherwise (UTC). `android-env.cjs` assigns `process.env.TZ` from `DSH_ANDROID_TZ` when the resolved zone differs, which resets ICU; drives the time context (patch 11), budget day rollover and log times. A zone change needs an engine restart. |
| `NODE_EXTRA_CA_CERTS` | `<filesDir>/certs/android-ca.pem`: `AndroidCAStore` (system minus user-disabled, plus user-installed CAs), re-exported when its digest changes |
| `NODE_COMPILE_CACHE` | `<cacheDir>/node-compile-cache` (V8 code cache) |
| `NODE_OPTIONS` | `--max-old-space-size=768`; composed only in `EngineRuntimeEnv` (app options + runtime options, de-duplicated) |
| `DSH_TELEMETRY_DISABLED` | `1` |
| `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (+ lowercase), `NODE_USE_ENV_PROXY=1` | only when Android has a proxy for the default network (`ConnectivityManager.getDefaultProxy()`, else the `http(s).proxyHost` properties): `http://host:port`; `NO_PROXY` = Android's exclusion list verbatim + loopback. dsh's launcher (`dsh-http-proxy`) installs fetch's global dispatcher from these; Android globs such as `192.168.*` need patch 15. argv also gets `--use-env-proxy` (`NODE_USE_ENV_PROXY` itself is ignored under AT_SECURE). PAC proxies are not applied (logged). Read at engine start; a proxy change applies on the next start. Mirrors what `HttpURLConnection` (获取模型列表) uses, so the connection test and chat take the same route. |

`ENGINE_DIR` = `<filesDir>/engine/<first 16 hex chars of engine.sha256>/`, unpacked from the APK asset `engine.zip` (flat tree, **no symlinks**) when its `.installed` marker is missing; other version directories are deleted. The hash only keys the directory; it is not re-verified on the device (the APK signature covers the asset).

### 1.1 App settings layer (third-party providers)

Before every engine start the app writes `$DSH_HOME/android/app-layer.patch.yml` (atomically, only when the
text changed; `core/provider/Providers.kt` `renderProfilePatch`). The engine entry passes it to dsh as a `--patch`
overlay, the highest layer (after every bundle, the profile's `cordis.patch.yml` and `$DSH_HOME/cordis.patch.yml`).
The profile's own `cordis.patch.yml` belongs to dsh (plugin manager, config editor; PLUGINS-CONTRACT §1.2), so each
entry replaces that row's whole `config`. The app is the file's only writer (no Web settings surface runs on the
phone) and fully regenerates it; it holds no secret. Values are JSON (valid YAML). Contents:

```yaml
# Generated by DSH-Android …
- id: llm-service-lite          # mounted dormant by bundle-android (@snnh/dsh-llm-service-lite, vendored)
  config: {"providers":{"<route id>":{"displayName":"Kimi","interfaceType":"openai-chat-completions",
           "baseURL":"https://api.moonshot.cn/v1","apiKeyEnv":"DSH_ANDROID_KEY_KIMI","includeUsage":true,
           "models":[{"id":"kimi-k3","contextWindow":131072,"maxTokens":16384,"defaults":{"maxTokens":16384}}]}}}
- id: compaction-basic          # per third-party model: headroomTokens 16384 (compacts at ~75 % of 128K, not 37 %)
  config: {"modelPolicies":[{"provider":"<route id>","model":"kimi-k3","headroomTokens":16384}]}
- id: tool-web                  # only when web search is unavailable (below)
  config: {"search":false,"fetch":true,"searchTimeoutMs":60000}
- id: agent-default-model       # dsh-base's default model: the 默认模型 route (#53), for plugins' own sessions
  config: {"provider":"deepseek-account","model":"deepseek-flash","reasoningEffort":"low"}
```

`agent-default-model` is the default model's route, the same as `initialize`'s (below; the level only when the stored
catalog lists it). Plugins that create sessions themselves (heartbeat, chat bots) read it through dsh's
`agentDefaultModel` service, so they run on the default, never on a model the user picked for one conversation.
(Without any row the file is just `[]`, when no provider is enabled and web search is available.) Only enabled providers with ≥ 1 model are
written; `apiKeyEnv` only when a key is stored (an open endpoint gets no credential). Each model gets its effective
`contextWindow` and output cap `maxTokens` (also `defaults.maxTokens`, sent as `max_tokens`; some vendors default to
1–4K and truncate tool calls): per-model override › provider-level override (设置 → 模型服务 「上下文窗口」「最大输出」) ›
the value `/models` reported (`context_length`, `context_window`, `max_model_len`, `max_context_length`,
`top_provider.*`, `max_completion_tokens`, …) › 131072 / 16384. Window clamped to 4096–10M; the output cap to half the
window when set by hand, a quarter otherwise; `headroomTokens` is min(16384, window/8). The composer's context ring
uses the same window and names its source.

Reasoning control (#46): without a user declaration a model's `capabilities` carry only `modalities`, and the adapter's
family table (matched on the model id) decides its levels and default level. 设置 → 模型服务 → 模型设置 「推理控制」
declares it for ids the table does not know or misreads, written into `capabilities` (js patch 22 makes a declaration
replace the family's levels and default; a `thinkingStyle` is what marks it declared, since the schema turns an absent
list into `[]`):

| 推理控制 | `capabilities` | Levels (wire) |
|---|---|---|
| 自动 | (nothing) | the family's |
| 无 | `effort: [], thinking: [], thinkingStyle: "effort_only"` | none; nothing is sent, not even a family default |
| 推理档位 | `effort: [<ticked>], thinking: [], thinkingStyle: "effort_only"` | the ticked levels (`reasoning_effort`) |
| 思考开关 | `effort: [], thinking: ["enabled","disabled"], thinkingStyle: "enable_thinking" \| "thinking"` | enabled / disabled (`enable_thinking: bool` or `thinking: {type}`) |

The composer offers exactly the levels the engine reports (`android/models/reasoning`, §2) plus 默认.
Route ids are the preset id (`kimi`, then `kimi-2`, …), never `deepseek-*`.

Provider headers (js patch 18; 设置 → 模型服务 → 高级设置, the OpenCode Go preset): `"headers":{name:value}` for
plain extra headers, `"headerEnv":{name:"DSH_ANDROID_HDR_…"}` for secret ones (a name containing key / token /
secret / auth…, or a value starting `sk-` / `Bearer`; the value lives in SecretStore and reaches the engine only as
env), `"sessionHeader":"x-opencode-session"` when the provider wants a per-conversation id (the engine sends the
first 32 hex of sha256(tag, route, session id): stable for the conversation, new per conversation, a fresh random id
for requests outside a session), and `"userAgent":"DSH-Android/<version>"` on every route (engine User-Agent
`DSH-Android/<version> deepseek-harness/<v> (+url)`). Keys are omitted when empty. 获取模型列表 and 测试并保存 send the same
extra headers, the same User-Agent and a fresh session id.

Errors: a third-party turn that fails because the account may not use the model (403 `Model.AccessDenied`, 您无权访问,
未开通 / Ark `ModelNotOpen`, SiliconFlow 30011, an OpenAI project without access) ends with code `MODEL_NOT_AVAILABLE`
and message `llm-service-lite: model "<id>" is not available to this account: <server text>` (not retried; the app
says the model is not enabled for the account instead of blaming the key).

Web search (`web_search`, DeepSeek search) needs a DeepSeek credential: `DEEPSEEK_API_KEY`, or the account login while
the session runs on `deepseek-account`. The app therefore turns the tool off when a third-party model is selected and
no API key is stored; `web_fetch` stays.

The patch layer and the `DSH_ANDROID_KEY_*` environment are read once per process: when either changes (the app
compares a fingerprint of the rendered patch + keys) the engine restarts the next time it is idle, like a route change.

## 2. SDK channel (stdin/stdout)

Unmodified dsh SDK JSON-RPC 2.0, newline-delimited (`packages/sdk/protocol`). The app is the client and handles
notifications strictly in wire order. dsh notifies `session.event`s before they are durable: after an `:engine` crash
the resumed session re-issues seqs the dead process had already sent, so the app logs each engine generation's events at
an offset past its log (`LogSeqs`), never treating a seq as a cross-generation identity.

- `initialize { cwd: HOME, provider, model, reasoningEffort, approvalRequests: true, capabilities: { userQuestions: true } }` —
  `provider` is `deepseek-official` when an API key is set, else `deepseek-account` when a DeepSeek account is signed in
  (dsh's `initializeDefaultModel` precedence: the account route only without a key), else `deepseek-official` (the engine
  still starts so the user can sign in; the app refuses prompts with "当前模型暂不可用，请登录后再试"). With a third-party model selected
  (设置 → 模型服务), `provider` is that provider's route id (§1.1), `model` its model id, and `reasoningEffort` the level
  the user picked for that model in the composer (saved per route + model), sent only while the engine's last
  `android/models/reasoning` answer lists it for the model; 默认, or a saved level the model no longer offers, omits it
  (the adapter then applies the family's default level, or nothing). dsh refuses any other level
  (`UNSUPPORTED_REASONING_EFFORT`), which would fail the route. If that route fails `initialize`, the engine is initialized on the DeepSeek route instead (so sign-in still
  works) and prompts fail with the route's error until the configuration changes. The app reads
  `account/state`, then `android/models/reasoning` (below), before `initialize`. A route change (provider, model or effort) re-sends `initialize` while no SDK
  session is open in the process (patch-free: the server just replaces its route), otherwise restarts the idle engine.
  This route is the **default** (设置 → 模型服务 → 新会话的默认模型): each conversation then runs on its own model,
  sent with `android/session/model` (below).
- `android/models/reasoning { models: [{ provider, model }] }` → `{ models: [{ provider, model, efforts: [{ id, name }],
  defaultEffort? } | { provider, model, error }] }` (plugin-guard, through patch 10; #46): each third-party model's
  reasoning selector as dsh's llm runtime resolves it (`resolveModelInfo` → the adapter's family table or the profile's
  declaration, §1.1): the levels `initialize` accepts, the adapter's labels, the level applied when none is sent.
  `efforts: []` = no reasoning control. Works before `initialize`. The app sends it after every engine start for all
  enabled third-party models and stores the answer; the composer's 推理强度 section lists exactly these levels plus
  默认 (none when `efforts` is empty or the engine has not answered yet).
- `session/prompt`, `session/resume`, `shutdown`. There is no per-session cancel: stopping a run stops the engine.
  `session/prompt` also accepts an optional `clientTimeZone` (IANA); the app does not send it, so the engine uses its
  process `TZ` (patch 11). Each request carries a
  `dsh-time-context` reading (date, time, zone, elapsed time) so relative requests ("tomorrow 7:00") resolve.
  `session/prompt` takes an optional `mode: 'followup' | 'steer'` (patch 21; default `followup`, dsh's own: a new turn
  after the running one). **插话发送 / 立即发送 send `mode: 'steer'` while the session runs**: dsh's `Agent.steer` puts
  the message into the running turn, claimed at its next step boundary (after the current tool calls finish, before
  the next model request), where it is logged as the turn's next `user/message` (`data.id` = the returned
  `messageId`). The result is `{ messageId, delivery: 'step' | 'turn' }`: `turn` when the session was idle (a new
  turn, as `followup`). A steer that arrives as the turn is ending runs as the next turn, with no idle gap; one left
  unclaimed by a failed turn runs right after it as a turn of its own. The app
  shows the message at once as pending and replaces it with the logged `user/message`; if the request fails it
  moves to the app-side queue (sent after the turn). `session/resume` drops input the dead process never claimed;
  the app hands undelivered steers back to the composer (stop, failure) or queues them (engine-loss continuation).
- `android/session/permission { sessionId, preset: 'read-only' | 'workspace-write' | 'danger-full-access' | null }` → `{}`
  (plugin-guard, through patch 10). **The app sends it before each `session/prompt` and whenever the user changes the
  conversation's permission mode** (`PermissionPreset.id`; `null` forgets it). With it the engine refuses actions that
  need approval up front in 仅可查看 and tells the model the mode is why (instead of "the user rejected tool …"), and
  auto-allows `write`/`edit` inside `HOME`/`TMPDIR` in the other modes. Until the app sends it the engine treats the
  mode as unknown: those writes still ask, and a refusal says "declined, or the mode is 仅可查看".
- `android/session/model { sessionId, provider, model, reasoningEffort? } | { sessionId, model: null }` →
  `{ selected: { provider, model, reasoningEffort? } }` (plugin-guard `session-model.js`, through patch 10; #53). Each
  conversation's own model (the app's `sessions.modelProvider/modelId/modelEffort`: a copy of the default taken at its
  first message, changed by the composer's model chip; rows without one follow the default). **The app sends it before
  each `session/prompt`, after `android/session/mode`, and right away when the user switches the model of a running
  conversation.** `provider` / `model` / `reasoningEffort` follow `initialize`'s rules (DeepSeek: the key or account
  route and its Effort id; third-party: the level only while `android/models/reasoning` lists it). The engine validates
  it like `initialize` (dsh `llm.resolveCallConfig`: a registered route, an accepted level; else a JSON-RPC error, which
  fails the prompt) and installs it on the conversation's root Agent with dsh's Agent-scoped selection
  (`installModelSelection` from `@deepseek-ai/dsh-agent`, the mechanism behind the Web host's `session/selectModel`):
  it routes the Agent's next model request (between steps, also inside a running turn), names the model in the system
  prompt, and adds dsh's switch notice (`user/message` `source { kind: 'model-selection', form: 'notice', summary:
  'from → to' }`, the app shows a quiet line) to the first request after a provider/model change. Subagents take the
  route of their parent's latest request (dsh-subagent). Sent before the Agent exists, it is installed at
  `agent/created` (new or resumed). `model: null` forgets it. Not persisted in the engine: the app re-sends it.
  A conversation whose own model is no longer available (provider removed or disabled, model dropped) runs on the
  default, which then becomes its own; the composer says so before the next message.
- `android/session/mode { sessionId, mode: null | SessionMode }` → `{}` (plugin-mode, through patch 10; §2.5). **The app
  sends it right after `android/session/permission`, before each `session/prompt`** (also after a resume).
- handles server requests `approval/request` (answer `allowed-once | rejected | cancelled`; requests from subagents carry `rootSessionId`, which owns the approval) and `user-question/ask` (`ask_user_question` tool, plan mode); consumes `session.event` / `session.status` notifications.
- Approval friction (plugin-guard, before anything reaches the app): read-only shell commands (`ls`, `cat`, `grep`,
  `getprop`, `dumpsys`, `settings get`, `pm list`, … with no output redirect; hardline-parsed) run without asking in
  every mode; `write`/`edit` inside `HOME`/`TMPDIR` run without asking unless the mode is 仅可查看 or unknown; a
  `sensitive` device tool asks once per conversation (after one `allowed-once`, later calls of that tool in the same
  root session run directly; the `reason` says so). `dangerous` tools always ask.
- What reaches the app is answered by the conversation's mode (`SessionStore.approve`): `read-only` rejects,
  `danger-full-access` allows everything but `dangerous`, `workspace-write` shows a card (允许一次 / 本会话允许 / 拒绝).
  本会话允许 is app-side: later requests for that tool in the same root session are answered `allowed-once` without a
  card. `dangerous` tools always show a card.
- Unattended (`auto_…`) sessions: the model gets a runtime-context note (nobody is watching, only the rule's tools run,
  unlock-waiting tools listed from metadata, finish with a notification-sized result); `user-question/ask` is never sent
  for them — plugin-guard answers "nobody is available" itself. No engine-side auto-allow of writes there.
- dsh 0.2.1-rc.5's SDK server (the 0.2.1 release line) lacks SDK approval/question requests; `js/patches/08` backports them from the upstream development line (drop once a bundled dsh ships them).

### 2.4 Account (DeepSeek login) — `@dsh-android/plugin-account`, patch 10

dsh-base already mounts the account provider (`deepseek-account` → `@deepseek-ai/dsh-deepseek-account-platform`: system
browser OAuth + PKCE S256 against `https://platform.deepseek.com`, grant in `$DSH_HOME`'s credential store, `resolveToken`
for model / web-search requests to `api.deepseek.com`, profile / balance, 401 → session expired, sign-out with background
revocation) and the `deepseek-account` model route. dsh's account RPC is a Web (Typert) controller, so the plugin
re-exposes the same operations as SDK methods (patch 10 lets profile plugins add SDK methods through an
`sdkExtensions` service and emit SDK notifications through the `sdk-extensions/notify` event). Methods work before
`initialize`. `client` (optional) = dsh `AccountClientMetadata` `{ locale, timezoneOffsetSeconds, version? }`
(version defaults to the engine's dsh version).

| App → engine | Result |
|---|---|
| `account/state {}` | `AccountView { status: 'signed-out' \| 'credential-stored', links: { usageUrl, topUpUrl }, attempt: null \| { id, phase, authorizeUrl?, expiresAt?, errorCode? } }` |
| `account/details { client? }` | `{ profile: null \| {status:'ready', value:{id,name,contact,avatarUrl}} \| {status:'failed'}, balance: null \| {status:'ready', value: Wallet[], bonusWallets: Wallet[]} \| {status:'failed'} }`, `Wallet = { currency: 'CNY'\|'USD', balance: decimal string }` |
| `account/sign-in/start { client? }` | `AccountView` (new or joined attempt; `phase` initializing → waiting-browser with `authorizeUrl` → exchanging → committing → succeeded \| failed \| expired \| cancelled) |
| `account/sign-in/cancel { attemptId }` | `AccountView` |
| `account/sign-out { client? }` | `AccountView` (local grant removed; Platform logout retried in the background; API keys untouched) |
| `account/tasks/running {}` | `{ running: boolean }` (running work on the account route; sign-out would interrupt it) |

Engine → app notifications: `account.changed { view }` (every state change, initial snapshot first),
`account.session-expired {}`, `account.signed-out {}`, `account.model-sign-in-required {}` (an account-model request was
refused for lack of a usable login), `account.callback { result: 'ok' | 'failed' | 'cancelled' }` (the browser reached the
callback).

Browser round trip on Android: `account/sign-in/start` opens a loopback HTTP server on `127.0.0.1:<random port>` (the
provider's `webServer`), sends `redirect_uri = http://127.0.0.1:<port>/oauth/callback` to Platform's `auth_init` and
returns; the app shows `authorizeUrl` (+ `theme=light|dark`) in its own sign-in screen, a WebView inside MainActivity
(feature/account/PlatformWebScreen.kt; no Custom Tab, so OEM browsers cannot turn it into a separate browser task).
Platform redirects the page to the loopback callback (cleartext allowed for 127.0.0.1 / localhost only, via
`network_security_config`); the provider validates `state`, exchanges the code with the PKCE verifier and commits the
grant. Instead of Platform's completion page (or dsh's "close this tab" page), the callback answers with a local page that
opens `intent://account/login?result=…#Intent;scheme=dsh-android;package=<pkg>;end` (automatically, and from a 返回 DSH
button); the WebView intercepts that navigation and closes the screen, which also closes when the attempt settles.
Closing the screen (✕, or Back with no page history) cancels the attempt (`account/sign-in/cancel`).

Inside that WebView every http(s) navigation stays put — tapped links, scripted navigations, redirects and popups
(`window.open` / `target=_blank` get a child WebView over the page, same session, so `window.opener` works): the
provider steps (Google's `signin/oauth/v3/consent?as=…&xsrf=…`, AWS WAF) are bound to the WebView's cookies, and handing
one to another browser broke the flow (#13, #49: Google 400 malformed). Only non-web schemes leave (`intent:` for other
apps, `mailto:`, …; `feature/account/SignInRouting.kt`).

用浏览器登录 (#49): Google may refuse OAuth in an embedded WebView (RFC 8252; `Error 403: disallowed_useragent` on
`accounts.google.com/signin/oauth/error?authError=…`, or "This browser or app may not be secure" on `…/signin/rejected`).
The screen detects those (URL, Google page text, an HTTP error page from Google Accounts) and offers 用浏览器登录; a link
under the page offers it at any time. It cancels the attempt shown in the WebView, starts a new one and opens its
`authorizeUrl` in a Custom Tab (default browser's provider; plain `ACTION_VIEW` when there is none) launched from
MainActivity, so it sits on top of the app's task; later attempts in the same process go there directly. Nothing changes
on the engine side: the browser follows Platform's redirect to `http://127.0.0.1:<port>/oauth/callback` (loopback is
shared by every app on the device; the engine runs in a foreground service while the attempt is active), and the
completion page's `intent://account/login?…;scheme=dsh-android;package=<pkg>;end` (automatic, or the 返回 DSH button)
reaches MainActivity's VIEW filter `dsh-android://account` (singleTask, which clears the Custom Tab); that filter also
serves a sign-in link copied into another browser. The PKCE verifier and `state` stay in the engine's memory: if
Android kills the app process while the user is in the browser, the loopback is gone with them and the user signs in
again (the browser shows a connection error, the dialog no attempt).

**Session id convention:** attended sessions use a random UUID. Sessions started by automations use `auto_<ruleId>_<uuid>`; the app auto-answers `approval/request` for these (allowed only if the tool is in the rule's whitelist, else `rejected`).

### 2.5 Session modes — `@dsh-android/plugin-mode`

A conversation's mode (issue #3) limits its tools and adds prompt text. The app owns the definitions
(`core/model/SessionMode.kt`, Settings → 会话模式) and sends the resolved mode; the engine stores nothing.

```ts
interface SessionMode {          // `mode` of android/session/mode; null = unrestricted (forget)
  id: string                     // "default" | "phone" | "code" | "chat" | "u_…" (user modes)
  name: string                   // display name (model-facing heading)
  prompt?: string                // extra system-prompt text (≤ 8000 chars)
  deviceGroups?: string[]        // device ToolGroup ids offered; absent = every group
  tools?: string[]               // built-in categories offered; absent = all
                                 //   shell: bash, pwsh, terminal_*, job_*   files_read: read, read_image, glob, grep
                                 //   files_write: write, edit   web: web_search, web_fetch
                                 //   subagent: subagent, subagent_fork, list_subagent_models, send_message, interrupt_agent, list_agents
                                 //   extensions: tools of third-party plugins (js patch 19 ownership) and of MCP servers (mcp__*)
  skills?: boolean               // false hides the `skill` tool (and with it dsh's skill catalog); default true
}
```

Tools in no category (ask_user_question, todo_write, goals, working_directory, exit_plan_mode, …) are never hidden.
`extensions` must be listed for a restricted mode to keep plugin and MCP tools: the read-only built-in modes leave it
out (nothing says a plugin tool only reads); a user mode that lists every built-in category should get it on migration.
Unknown category ids are ignored; the default mode (`{ id: "default" }`, nothing restricted) changes nothing.

Per root session (subagents resolve to their root, like approvals), for every agent of the conversation —
on `agent/created` (new, resumed, cleared, compacted) and when the app sends the mode:
- `agent.ctx.tools.restrict({ deny })` with the excluded names that exist in the agent's view (dsh rejects unknown
  names), re-derived on `tools/change` (device tools re-register after a bridge reconnect);
- a global `ctx.tools.guard` refuses an excluded call outright ("not available in this conversation's mode");
- the prompt text, a `# Conversation mode: <name>` heading and (when tools are limited) a note that missing tools are
  unavailable here go into a per-agent section `android:mode` (order 150, not interpolated); the `system-prompt/assemble`
  waterfall drops `tool:<name>` guidance sections of excluded tools (bash and jobs render theirs unconditionally);
  plugin-guard's phone-context section describes only the device tools / shell the agent sees;
- the root session's log gets the plugin record `plugin:android-mode/selected { id, name }` when the mode differs from
  the last record (a log without one is the default mode). It is `ignorable` and never model-visible; the app does not
  store it (`UNLOGGED`), the session row already has `modeId`.

The app lets the user pick the mode only before the conversation's first message, so a mode never changes mid-history
(an edited user mode applies from the next prompt). CapabilityHub additionally refuses device calls of a group the
session's mode excludes (`not_authorized`). `capability/list` stays global.

#### 2.5.1 Deferred tools (issue #36)

Tool definitions are input on every request; with all device groups a bare "Hi" cost ~19k input tokens, ~90 % of
them schemas. plugin-mode (`deferred.js`) therefore loads definitions on demand, in every mode:

- **Always loaded** (when the mode offers them): bash, read, read_image, write, edit, glob, grep, web_search, web_fetch,
  skill, ask_user_question, todo_write, the goal tools, `load_tools`, and any built-in not listed below.
- **Deferred**: every device tool, every third-party plugin / MCP tool, and the built-in groups `jobs` (job_*),
  `subagents` (subagent, subagent_fork, list_subagent_models, send_message, interrupt_agent, list_agents) and `session`
  (working_directory, exit_plan_mode). They are hidden with the same per-agent `tools.restrict({ deny })` as excluded
  tools and listed in a per-agent section `android:deferred-tools` (order 160; `# Tools loaded on demand`, one line per
  group: id, short summary, tool names; extension tools with a one-sentence hint). Fewer than 3 deferrable tools are
  simply loaded (no catalog, no loader).
- `load_tools { names: string[] }` takes tool and/or group names, adds them to the conversation's loaded set (per root
  session, shared by subagents) and re-applies the restriction; the next step's request offers them. Excluded or unknown
  names are reported back. No approval (it only changes what the model may call).
- **Caching**: loading only adds tools. The catalog lists every deferred tool whether loaded or not, the `tool:<name>`
  guidance sections of deferred built-ins are always dropped (the catalog line carries their gist), and plugin-guard's
  phone section counts deferred tools as available (`androidToolScope.deferred(name, agent)`), so the system prompt does
  not change on a load. dsh logs the addition (`developer/message` tool-addition); routes with mid-conversation tool
  changes (official DeepSeek, `toolUpdate: addition-only`) send the new definitions after the cached prefix
  (`defer_loading` + a `tool_addition` system block), other routes resend the tool list once per load.
- **Persistence**: the loaded set is the deferrable tools the session's logged request header already offers plus the
  plugin records `plugin:android-mode/tools-loaded { names }` (ignorable, never model-visible) the loader writes, so a
  resumed conversation (and one started before this change, whose header lists every tool) never loses a tool.
- Row config `deferTools: false` (android-mode) loads everything up front.

### 2.6 Extensions — `@dsh-android/plugin-extensions`

Plugins, skills and MCP servers: `android/ext/*` methods and `android.ext.*` notifications, specified in
[`PLUGINS-CONTRACT.md`](PLUGINS-CONTRACT.md) §3–§5.

### 2.7 Turn ends and continuation — `@dsh-android/plugin-guard` (`continuation.js`, #37)

dsh's loop has no step limit. A turn ends (`turn/end.reason.kind`) when the model answers without a tool call
(`completed`), on the output cap (`max-tokens`), when a pre-step hook refuses (`blocked`: a budget, an archived
session), on an error (`error`) or when the run is stopped (`aborted`; the app stops the engine). The phone profile
disables dsh's same-session goals (`tool-goal`, `goal-round-driver`, `command-goal`): a goal round is a new turn
started while the session is idle, which the app showed as the run ending; long tasks continue inside the turn.

- **Continuation.** At dsh's `agent/turn-stopping` of a root session, when the model's last answer has no tool call
  and the turn's own todo list (`todo/write` since its `turn/start`) has `pending`/`in_progress` items — or the answer
  claims the work goes on by itself ("下一轮会自动继续") — and it does not ask the user anything, plugin-guard injects
  a next-step `user/message` with `source { kind: "android-continue", form: "notice", summary }` (model-facing:
  continue now, do not repeat what succeeded, or stop and say why). The turn and the session status stay running.
  No second nudge when the model answered the previous one without calling a tool; at most 8 per turn.
- **Outcome record.** Before `turn/end`, an ignorable `plugin:android-guard/turn-outcome { turn, reason, todos:
  { pending, inProgress, completed } | null, nudges }` with `reason` `done` | `needs_user` | `pending_work` (stopped
  with work left despite a nudge) | `continue_limit`; a budget refusal records `{ turn, reason: "budget", kind, used,
  limit }` (then `turn/end` `blocked`).
- **App.** `Turn.endReason` (Projection.kt) maps both: `done`/`needs_user` read as a normal reply; `pending_work`,
  `continue_limit`, `budget` and `max-tokens` get an end card (reason, todo progress, next item, 继续). Without a
  record (older engine) a `completed` turn whose todo list has open items counts as `pending_work`. 继续 logs an
  `app/notice { kind: "user_continue" }` and sends an app prompt (`[DSH] …`, do not repeat what succeeded); the
  continuation turn folds into the ended one. The nudge shows as a notice inside the turn.

## 3. Capability channel (`DSH_BRIDGE_FD`)

A `socketpair(AF_UNIX, SOCK_STREAM)`; Node opens it with `new net.Socket({ fd })`. Newline-delimited JSON-RPC 2.0, both directions.

### Node → App requests
- `capability/list {}` → `{ tools: ToolDescriptor[] }`
- `capability/invoke { sessionId, callId, tool, args, timeoutMs? }` → `ToolResult` (`timeoutMs`: the budget the plugin uses for this call, see §3.1)
- `experience/turn { sessionId, ok }` → `{ reflect: boolean, package?, label? }` — a root turn that used app/screen
  tools ended (`ok`: `turn/end` reason `completed`). The app records the turn's tap route (App 经验库) and says whether
  the plugin should reflect on it (successful, in an app, setting 任务结束后自动总结经验 on).

```ts
interface ToolDescriptor {
  name: string            // e.g. "file_search"
  group: string           // files | apps | screen | system | notifications | comms | media | clipboard | browser | rules
  description: string     // model-facing, English
  inputSchema: object     // JSON Schema
  risk: "read" | "write" | "sensitive" | "dangerous"
  needsUnlock: boolean
  timeoutMs: number       // the tool's time budget (§3.1); default 300000, 900000 for needsUnlock tools
}
interface ToolResult {
  ok: boolean
  content: Array<{ type: "text", text: string } | { type: "image", mimeType: string, data: string /* base64 */ }>
  error?: { code: "not_authorized" | "permission_missing" | "device_locked_timeout" | "waiting_for_user" | "timeout" | "cancelled"
                 | "invalid_args" | "shizuku_unavailable" | "needs_user" | "failed" | string, message: string }
}
```

Descriptions are static: they never carry live state (Shizuku up/down, permissions), because every description
change re-lists the tools and re-bills the cached prompt. Availability is reported in the call's result (`path=…`,
"via Shizuku") or error (`shizuku_unavailable`, `permission_missing`). `capability/changed` is only sent when the
user changes which tool groups are enabled.

The app is authoritative for: which groups are enabled, runtime permissions, lock-state waiting (a `needsUnlock` call while locked is held by the app, which posts a "continue after unlock" notification; the request resolves after unlock, or with `waiting_for_user` when the budget runs out), and unattended whitelists.

### 3.1 Timeouts and cancellation

A call that the engine has given up on must never act later (e.g. tap stale coordinates after an unlock two hours
later, or send an SMS twice after a retry). The rules:

1. **One budget per tool.** `ToolDescriptor.timeoutMs` (set in the app's `Tool` definition) is the single source of
   truth. The plugin registers the dsh tool with `timeoutMs + 15 s`, sends `timeoutMs` in `capability/invoke`, and
   times the bridge request out at `timeoutMs + 10 s`.
2. **The app answers first.** CapabilityHub runs the call with a deadline of `timeoutMs − 5 s`. Every wait for the
   user inside it — unlock, the permission card, the Shizuku card, the takeover card — is a cancellable suspension
   bounded by that deadline. When the deadline passes the call is cancelled and answered with
   `waiting_for_user` ("still waiting for the user to …; nothing was done; call again") or `timeout` (the tool's own
   work overran). The model can simply call the tool again when the user is ready.
3. **Acting re-checks.** After any wait, the hub checks that the call is still live right before invoking the tool
   (`EngineCalls.ensureCallActive()`), so a call cancelled while it waited never starts its side effect.
4. **Cancel from the engine.** When the plugin's bridge request times out, or the turn is aborted (`exec.signal`,
   which dsh also aborts at the tool deadline), the plugin sends the notification
   `capability/cancel { sessionId, callId, reason: "timeout" | "aborted" }`. The app cancels that call's job (each
   bridge call runs in its own job, keyed by `sessionId` + `callId`); every wait inside unwinds, cards and the
   "unlock to continue" notification are removed in `finally`. The app may still answer the original request with
   `cancelled`; the plugin has already moved on and ignores it. Cancelling an unknown or finished call is a no-op.
   (`capability/cancel` is also accepted as a request, answered with `{ ok: true }`.)
5. **Engine loss.** On stop, crash, restart or bridge EOF, EngineManager tears the generation down: every in-flight
   engine request is cancelled (`EngineCallCancelled`, see `core/engine/EngineCalls.kt`) and
   `CapabilityHub.detach()` cancels all bridge calls. `CapabilityHub.cancelSession(id)` cancels one session's calls.
6. **Rule steps** (fixed tool calls run by an automation, no engine) use the descriptor budget too, except that a
   `needsUnlock` step may wait up to 2 h for the user to unlock; they are cancellable the same way.

### Node → App notifications
- `capability/cancel { sessionId, callId, reason? }` — the plugin gave up on this call (§3.1); the app cancels it.
- `budget/exceeded { sessionId, kind, used, limit }` — a budget refused a step; the app shows it in the conversation.
- `experience/notes { sessionId, package, notes: [{ kind: "entry"|"trap"|"fact", text }] }` — the post-turn
  reflection's findings (≤3); the app stores them like `app_note` add (dedupe, personal-data filter).

### App → Node notifications
- `capability/changed {}` — tool set changed (tool groups toggled); the plugin re-lists.
- `budget/update { sessionTokenLimit?: number, dailyTokenLimit?: number, dailyCostLimitCny?: number }`, sent after each
  engine start (the app currently sets only the two token limits; a limit of 0 is omitted). Token limits
  are metered **input-equivalent**: uncached input + cache writes + output, plus cache reads weighted by their price
  ratio (cacheRead / input = 0.1 by default). The cached prompt prefix (~13k tokens with all device groups) is re-read on
  every step; counting it at full weight made a 10-step task look like ~130k tokens (before deferred tools, §2.5.1; ~5k now plus what a conversation loads). The cost limit uses real prices.

## 4. dsh-side responsibilities (js/)
- `@dsh-android/plugin-device`: registers every `ToolDescriptor` as a dsh tool; maps `risk` → dsh approval requirement (`read` auto; others ask); per-tool timeouts from `ToolDescriptor.timeoutMs` and `capability/cancel` on timeout/abort (§3.1).
- `@dsh-android/plugin-account`: loopback OAuth callback server + account SDK methods/notifications (§2.4).
- `@dsh-android/plugin-mode`: session modes (§2.5): per-conversation tool restriction + guard, skills switch, mode prompt
  section, selection record; deferred tools and `load_tools` (§2.5.1).
- `@dsh-android/plugin-guard`: hardline command floor for the shell tool (relative paths resolve against the session
  cwd and any `cd` earlier in the command); approval policy (§2); token/cost budget enforcement from `budget/update`;
  system-prompt section describing the phone context and the tool-priority rule (system API > intent/deeplink >
  Shizuku > accessibility clicks), whose "works while locked" examples are derived from `needsUnlock:false`
  descriptors; unattended-run context; post-turn reflection for the App 经验库 (`experience.js`: one tool-free call
  with the session's model over a ≤3k-char step trace, metered into the budget ledger); dsh `ctx.logger` warnings/errors mirrored to stderr (`[dsh:<name>] warn: …` in
  engine.log); third-party models' reasoning levels for the composer (`android/models/reasoning`, §2).
- Profile also mounts `dsh-time-context` and `dsh-tool-ask-user`, and disables `otel` and `llm-pi-ai` (cold start:
  ~1000 → ~500 modules at boot; build-engine prunes the provider SDKs only pi-ai reaches). Third-party models go
  through `llm-service-lite` instead (`@snnh/dsh-llm-service-lite` 0.2.1, MIT, vendored as built in
  `js/packages/llm-service-lite/`: one ~220 KB module + `eventsource-parser`, no vendor SDK), mounted dormant: no
  route until the user patch layer (§1.1) supplies providers.
- Profile `android`: dsh-base minus web-app/HMR/office/voice/inspector/browser-use/ssh/experimental bundles; shell → `/system/bin/sh`; DeepSeek web search on.
- `patches/`: platform `android` support (flock loader → `DSH_NATIVE_DIR`, require-builtin → `--expose-internals`), `bash -c` → `/system/bin/sh -c`, attachment hard-link → copy fallback, ripgrep path → `PATH`, android profile template, SDK approval/user-question backport, sharp wasm, SDK extension methods, SDK prompt time zone; sandbox chain: none on android (approval-only). node-pty is not patched (no terminal row is mounted; `libdsh_pty.so` is unused).
