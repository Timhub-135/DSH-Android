# js/ — the engine package

DeepSeek Harness (dsh) **0.2.1-rc.5**, installed unmodified from npm and customized only by a dsh bundle (profile
`android`), three plugins, one vendored third-party LLM adapter and a few patches applied at build time. The app side of the interface is
[`../docs/ENGINE-CONTRACT.md`](../docs/ENGINE-CONTRACT.md).

## Layout

| Path | What |
|---|---|
| `engine/package.json`, `engine/package-lock.json` | npm project whose production install is the engine tree. dsh is pinned exactly; the lockfile pins every transitive package. |
| `packages/bundle-android/` | the dsh bundle behind profile `android` (`cordis.patch.yml`), the `android-env.cjs` preload and the engine entry `main.mjs` |
| `packages/plugin-extensions/` | plugins, skills, MCP servers: the phone gate around dsh's plugin manager and the `android/ext/*` SDK methods ([`../docs/PLUGINS-CONTRACT.md`](../docs/PLUGINS-CONTRACT.md)); `boot.js` is main.mjs's pre-boot bookkeeping |
| `packages/plugin-device/` | capability bridge client (`DSH_BRIDGE_FD`) → dynamic dsh tools |
| `packages/plugin-guard/` | hardline shell floor, approval policy, budgets, phone system prompt, long-task continuation and turn-end reasons (`continuation.js`, ENGINE-CONTRACT §2.7) |
| `packages/plugin-mode/` | session modes: per-conversation tool restriction, skills switch, mode prompt section (`android/session/mode`, ENGINE-CONTRACT §2.5); deferred tools: catalog section + `load_tools` (`deferred.js`, §2.5.1) |
| `packages/plugin-account/` | DeepSeek account login: loopback OAuth callback server + `account/*` SDK methods |
| `packages/llm-service-lite/` | vendored `@snnh/dsh-llm-service-lite` 0.2.1 (MIT, snnh): third-party OpenAI-compatible model routes; built `lib/index.js` copied verbatim from the npm tarball (provenance in its `package.json` `//vendored`), Web client dropped. Upgrade = replace `lib/index.js` + note; changes go in `patches/` (14, 16, 17, 18, 22); upstreaming notes in [`patches/UPSTREAM.md`](patches/UPSTREAM.md) |
| `patches/*.patch` | unified diffs against `node_modules/`, applied in order with `patch -p1` (dry-run first) |
| `scripts/fetch-locked.mjs` | installs lockfile-pinned packages `npm ci` skips on the host (sharp's WebAssembly build), sha512-verified |
| `scripts/unreachable.mjs` | lists packages reachable only through given deps (used to prune pi-ai's provider SDKs) |
| `scripts/make-zip.py` | deterministic zip (sorted, fixed mtimes, rejects symlinks) |
| `scripts/bench-cold-start.mjs` | host cold-start benchmark (spawn → `initialize`) |
| `scripts/measure-prompt.mjs` | first-request size per session mode ("Hi"): system sections and tools by group, through a capture server on `DEEPSEEK_BASE_URL`; `--live` forwards to DeepSeek and adds provider token counts. Input: the app's `capability/list` (`DSH_CAPABILITY_DUMP=<file> ./gradlew testDebugUnitTest --tests '*CapabilityListTest*'`) |
| `test/hardline.test.mjs` | hardline unit tests: `node --test js/test/hardline.test.mjs` |
| `test/prompt.test.mjs` | system prompt section tests: `node --test js/test/prompt.test.mjs` |
| `test/mode.test.mjs` | plugin-mode unit tests (categories, payload validation, restriction / guard / record on a fake context; deferred tools: catalog, `load_tools`, loaded set across subagents and resume, stable prompt): `node --test js/test/mode.test.mjs` |
| `test/provider-network.test.mjs` | patches 14/15 against a built stage tree: `DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-network.test.mjs` |
| `test/provider-protocol.test.mjs` | patch 16 end to end: the real adapter against synthetic `node:http` SSE servers (`usage: null` frames, malformed JSON, bare-origin 404, refused port); an `openai-responses` route as the app writes it (#59: `/v1/responses`, flat tools, `reasoning.effort` per level, text + tool call + usage, `includeUsage` refused there); same `DSH_STAGE_NM` |
| `test/provider-headers.test.mjs` | patch 18 route headers against a fake OpenCode-Go-like server that refuses a chat without `x-opencode-session`: session id stable across two turns of a session, different across sessions / routes, fresh without a session and for listings; static / secret (`headerEnv`) headers; User-Agent token; same `DSH_STAGE_NM` |
| `test/provider-reasoning.test.mjs` | reasoning levels for third-party models (#46, patch 22, plugin-guard `android/models/reasoning`): the real adapter against a fake chat endpoint records the request fields per chosen level for a `reasoning_effort` family (Kimi K3), an `enable_thinking` family (Qwen 3.5+) and profile-declared models (effort levels, `enable_thinking` / `thinking.type` switch, no control), and that no level adds nothing beyond the family default; then the real engine with the app's patch layer: levels before `initialize`, a level through `initialize` onto the wire, an unsupported level refused; same `DSH_STAGE_NM` |
| `test/gateway-replay.test.mjs` | recorded gateway compatibility suite (patches 14/16/17/18): every `test/fixtures/gateways/<case>.expect.json` replays one raw response body (SSE, JSON completion or error) of a provider (DeepSeek live captures; OpenAI, vLLM, SGLang, NVIDIA NIM, OpenRouter, Ollama, LM Studio, SiliconFlow, Volcano Ark, Moonshot, Zhipu, DashScope, Mistral, MiniMax, Gemini, Azure, magpie-style gateway from their documented formats) through the real adapter and checks text, reasoning, tool calls, usage, finish reason or error code, then the thinking echo on the follow-up request. Add a case = one body + one expectation file ([format](test/fixtures/gateways/README.md)); same `DSH_STAGE_NM` |
| `test/extensions-unit.test.mjs` | plugin-extensions pure parts: pnpmfile gate, crash attribution / safe mode / overlay migration, sources against a fake GitHub, archives, MCP classification, error mapping, mirror fetch (`fetch-sources.js`: a source that accepts and never answers, then one that does; memory; all-failed `index-unreachable`), index URL migration, manifest LocalizedText: `node --test js/test/extensions-unit.test.mjs` |
| `test/links.test.mjs` | user-supplied links (§5.1 of PLUGINS-CONTRACT): GitHub link normalization (blob/tree/raw/refs-heads/jsDelivr/gist), fake slow servers (answers after 3 s: a lone slow source and the last mirror left are not cut off; earlier attempts keep running), deadline, root-cause codes in failures, plugin `.zip` via mirrors; skill import from a SKILL.md blob link (jsDelivr listing → codeload → single file) when `DSH_STAGE_NM` is set: `node --test js/test/links.test.mjs` |
| `test/extensions.test.mjs` | plugins end to end on a built stage with two fake registries, fake MCP servers and real restarts (native refusal before download, scan rollback, web-service precheck, peers, config forms, mirror fallback, integrity, updates, deferred removal, crash suspension, safe mode, engine replacement, MCP, skills); `DSH_STAGE_NM` as below, ~4 min |
| `test/guard-ask.test.mjs` | plugin-guard's per-plugin ask-before-call |
| `test/session-model.test.mjs` | plugin-guard `session-model.js` (#53): `android/session/model` payload validation, installed on the root agent at creation or in place while live (never on subagents or other sessions), `null` forgets, a refused route / level changes nothing: `node --test js/test/session-model.test.mjs` |
| `test/host-e2e.mjs` | host end-to-end run of the built `engine.zip` with a fake bridge; phase H checks deferred tool loading on the wire (capture proxy in front of DeepSeek); phase X installs real plugins from npm |
| `test/host-ac8-resume.mjs` | host repro of engine kill + `session/resume` + continuation |
| `test/session-open-engine.test.mjs` | #57 on a built stage: a session an earlier engine process opened is refused by `session/prompt` with `session "<id>" already exists` (the exact text the app's `SessionOpen` matches); `session/resume` + prompt continues it with its history; `DSH_STAGE_NM` as below |
| `test/continuation.test.mjs` | plugin-guard `continuation.js` (#37): when a text-only stop is continued (open todos, "will continue" claims), questions, no-progress and cap stops, the outcome record: `node --test js/test/continuation.test.mjs` |
| `test/long-task.test.mjs` | long tasks end to end on a built stage (real engine, scripted fake OpenAI-compatible model, fake bridge): "next round" text with open todos continues in the same turn (no idle gap), `pending_work` / `needs_user` / `continue_limit` / `budget` outcomes, no goal tools; same `DSH_STAGE_NM` (`DSH_NATIVE_DIR` defaults to its `../../host-native`), ~1 min |
| `test/steer.test.mjs` | steering end to end on a built stage (patch 21; real engine, scripted fake model, fake bridge with a tool that blocks until released): a `mode: "steer"` prompt sent during a tool call reaches the very next model request of the same turn (one turn, no idle gap); without `mode` it still waits for a turn of its own; a steer sent during a model request that then fails runs as a turn of its own; steering an idle session starts a turn; an unknown mode is refused; after an engine kill a steer the dead process never ran is not claimed by the next prompt; same `DSH_STAGE_NM` |

Our packages are plain ESM with no dependencies outside `node:` built-ins; there is no compile step (the vendored adapter is shipped as built upstream).

## Build

```sh
js/build-engine.sh
```

Paths are relative to the repo root; `toolchains/` is gitignored.

| Env | Default | |
|---|---|---|
| `OUT_DIR` | `toolchains/out/engine` | `engine.zip`, `engine.sha256` (`sha256sum` format), `engine-version.txt`, `size-report.txt` |
| `WORK_DIR` | `toolchains/out/engine-build` | staging tree; `host-native/` holds host bindings for tests |
| `TMPDIR_OVERRIDE` | `toolchains/tmp` | exported as `TMPDIR`; `/tmp` is not used |

Gradle packages `engine.zip` and `engine.sha256` from `-Pdsh.engineOut=<dir>` (default `toolchains/out/engine`, the same as `OUT_DIR`).
Requires node/npm, `patch`, `python3` and access to the npm registry.

Steps:

1. `npm ci --omit=dev --ignore-scripts` in a copy of `engine/` (flat hoisted layout; includes pnpm 11, pinned). `overrides: {zod: 4.4.3}`: dsh
   pins zod exactly in ~60 packages; without the override npm nests ~50 copies (~250 MiB). Install scripts are not
   needed (node-pty/koffi only pick prebuilds, which are pruned).
2. Copy the four `@dsh-android/*` packages and the vendored `@snnh/dsh-llm-service-lite` into `node_modules/` (copies,
   not links) and add them to the root `package.json`. The adapter's dependencies (`eventsource-parser`, cosmokit,
   schemastery, dsh-util-values) must already be installed from the lockfile at versions satisfying its ranges.
3. Add `@img/sharp-freebsd-wasm32`, `@img/sharp-wasm32`, `@emnapi/runtime`, `tslib` at their lockfile versions (patch 09).
4. Save the host's native bindings to `$WORK_DIR/host-native/libdsh_*.so` (tests only, never zipped).
5. Apply `patches/*.patch`; fail on rejects or fuzz.
6. Prune: packages reachable only through `llm-pi-ai` (openai, anthropic, google, aws SDKs; the row is disabled),
   `libreoffice-kit-wasm`, `sherpa-onnx*`, the web client UI and inspector, native `@img/sharp-*`, other-platform native
   packages, node-pty prebuilds/sources, every `*.node/.dll/.dylib/.exe`, `.bin`, `*.map`, `*.d.ts`, READMEs, and
   package-root `docs|test|tests|examples|benchmark`; in pnpm: node-gyp, the reflink binaries, Windows helpers.
   Then `plugin-extensions/installation-packages.json` lists the shipped `@deepseek-ai/*` packages (the gate peers them).
7. Verify: no symlinks, entry points present, patch markers present, the pruned pnpm runs, engines.dsh of our packages = the bundled dsh.
8. `engine-version.txt` = `<dsh version>-a<10 hex of the sha256 of all inputs>`; deterministic zip; sha256; size report.

Zip layout (= `ENGINE_DIR` on the device):

```
package.json  package-lock.json  engine-version.txt
node_modules/@dsh-android/bundle-android/main.mjs  ← entry point (dsh's lib/bin.js + plugin bookkeeping)
node_modules/pnpm/bin/pnpm.mjs                     ← run by dsh's plugin manager on the node launcher
node_modules/@dsh-android/{bundle-android,plugin-device,plugin-guard,plugin-mode,plugin-account,plugin-extensions}/
node_modules/…                                  ← flat npm tree
```

## Profile `android`

Patch 07 adds `android` to dsh's shipped profile templates, so `--profile android` creates
`$DSH_HOME/profiles/android/` on first launch; the app has no init step. The profile stacks three bundles:

1. `@deepseek-ai/dsh-base`: core rows (tools, persistence, approval, DeepSeek web search, account provider).
2. `@deepseek-ai/dsh-sdk-app`: stdio JSON-RPC SDK server.
3. `@dsh-android/bundle-android` ([`cordis.patch.yml`](packages/bundle-android/cordis.patch.yml)):
   - persona "DSH, an AI agent running on the user's own Android phone";
   - `sandbox-policy` → `danger-full-access` with `workspaceRoot = $HOME`, `approval` → `ask`. Android has no
     bwrap/landlock/seatbelt; safety comes from approvals and the hardline floor;
   - `android-extensions` mounts dsh's `plugin-manager` and `plugin-market` on first use (the dsh-base row stays off:
     ~110 ms of boot); the DeepSeek routes wait 1 ms, not 5 s, for `/models` on first connect (rc.5's listing);
   - disabled rows: `plugin-manager`, `session-telemetry-otel`, `otel`, `llm-pi-ai`, `ptc-runtime`, `workflow-ptc`,
     `tool-workflow`, `skill-office`, `workspace-dependencies`, `tmux-context` (otel + pi-ai halve boot-time modules);
   - inserted rows: `dsh-time-context`, `dsh-tool-ask-user`, `android-extensions`, `android-device`, `android-guard`, `android-account`.
   - Model default stays dsh-base's `deepseek-official` / `deepseek-flash`.

Only the bundle list is persisted in `DSH_HOME`, so later engine versions can change `bundle-android` freely. The
profile's `cordis.patch.yml` belongs to dsh (plugin manager, config editor); the app's settings layer is
`$DSH_HOME/android/app-layer.patch.yml`, passed by main.mjs as a `--patch` overlay (PLUGINS-CONTRACT §1). Inspect the composed tree with
`node …/bin.js --profile android --dump-config`.

`android-env.cjs` (loaded with `--require`) restores what `AT_SECURE` makes Node ignore: `os.tmpdir()` from `TMPDIR`,
the compile cache, extra CA certificates, and `TZ` from `DSH_ANDROID_TZ`.

## Plugins

### plugin-extensions

Third-party plugins are dsh bundles installed by dsh's own plugin manager into `$DSH_HOME/profiles/android`
(hoisted, copied, no symlinks; outside the engine directory, so engine updates keep them). pnpm is the bundled
pnpm 11 run on the node launcher `libdshnode.so` (`profileContext.packageManager`, set by main.mjs). Gates:
`pnpmfile.cjs` (readPackage: native modules refused before any download, native optional / platform packages
skipped, `@deepseek-ai/*` and `sharp` made peers so plugins share the engine's instances), `scan.js` (binaries no
manifest declared), `precheck.js` (row modules imported in a worker; web-only services → refused). `boot.js` runs
before dsh: crash attribution (`last-crash.json` stack → one bundle → suspended), safe mode after two unready boots,
profile pnpm settings, overlay migration. Methods, files and error codes: `docs/PLUGINS-CONTRACT.md`.

### plugin-device

- Opens `new net.Socket({ fd: DSH_BRIDGE_FD })` (unref'd; SDK stdin owns process lifetime).
- `capability/list` at activation (with retries) and on every `capability/changed`; each `ToolDescriptor` becomes a dsh
  tool with its JSON Schema. Changed or removed tools are unregistered first; names that collide with dsh built-ins are
  skipped. The description gets a one-line footer with risk and unlock (`[risk: write (changes the phone); needs the phone unlocked]`); plugin-guard's phone section explains it once.
- Approval: a `tools/pre-execute` listener returns `ask` for `write | sensitive | dangerous`; dsh then sends
  `approval/request` to the app. `read` runs directly.
- Timeouts (contract §3.1): dsh tool timeout = `timeoutMs + 15 s`, bridge request timeout = `timeoutMs + 10 s`;
  `capability/cancel` on timeout or abort.
- `capability/invoke.sessionId` is always the root SDK session, so automation whitelists apply to subagents too.
- `ok:false` → tool error `"[<code>] <message>"`.
- Images go to `ctx.attachments` and are returned as image blocks if the model route accepts images, otherwise as a
  short text note.
- Without `DSH_BRIDGE_FD` it logs once and registers nothing.

### plugin-guard

- **Hardline** ([`hardline.js`](packages/plugin-guard/hardline.js)) for `bash`, `pwsh`, `terminal_*`. Shell-aware parse:
  quotes, `$(…)`, backticks, `sh -c`, `su -c`, `eval`, `xargs`, `env`/`sudo`/`busybox`/`toybox` prefixes,
  `$EXTERNAL_STORAGE`-style variables, globs, `cd` earlier in the command, small scripts run via `sh x.sh`. Blocks
  recursive rm of `/`, `/sdcard`, `/storage…`, `/data…` and their contents; any rm under `/system`, `/vendor`, `/apex`,
  `/proc`, `/dev`, …; `find -delete` there; writes to block devices, `mkfs`, partition tools; reboot/shutdown/power
  intents; fork bombs; decoded payloads or `curl` piped into a shell; uninstalling system packages; `settings delete`;
  wipe and factory reset. Enforced by a prepended `tools/pre-execute` listener plus a `ctx.tools.guard` backstop. The
  model gets `Error: HARDLINE_BLOCKED (rule: …): … Do not retry`. The parser is heuristic: commands assembled at runtime
  are not resolved.
- **Approvals**: `askTools` (`bash`, `write`, `edit`) ask, except read-only shell commands (`ls`, `cat`, `grep`,
  `getprop`, `dumpsys`, `settings get`, `pm list`, … without output redirects) and `write`/`edit` inside
  `HOME`/`TMPDIR` unless the mode is read-only or unknown. A `sensitive` device tool asks once per conversation. The
  conversation's mode arrives via `android/session/permission` (patch 10 listener). Unattended (`auto_…`) sessions get a
  runtime note and automatic "nobody is available" answers to `user-question/ask`.
- **Budgets**: limits from `budget/update`. Usage is metered from `assistant/message` / `assistant/attempt` usage,
  attributed to the root session, stored in `$DSH_HOME/android/budget.json` (local-day rollover). Token limits count
  cache reads at their price ratio (0.1). When a limit is reached the next step is rejected at `agent/pre-step` (turn
  ends `blocked`) and `budget/exceeded` is sent on the bridge. Cost is estimated from CNY per 1M tokens
  `{input: 2, cacheRead: 0.2, output: 3}`, overridable via the row's `prices`.
- **System prompt** section `android:phone-context`: tool priority (system API > intent/deep link >
  accessibility), approvals, destructive-operation scope, `/system/bin/sh` + toybox, reply language from
  `DSH_ANDROID_LOCALE`.
- dsh `ctx.logger` warnings/errors are mirrored to stderr (`engine.log`).

### plugin-account

dsh-base already provides the account provider (`deepseek-account`: OAuth + PKCE S256 against
`https://platform.deepseek.com`, token in `$DSH_HOME`, profile/balance, sign-out) and the `deepseek-account` model
route. The plugin adds:

- a loopback `webServer` on `127.0.0.1:<OS-assigned port>`, opened on the first sign-in, for `/oauth/callback`. Its
  success/failure/cancel answers are replaced by a page that opens
  `intent://account/login?result=ok|failed|cancelled#Intent;scheme=dsh-android;package=$DSH_ANDROID_PACKAGE;end`;
- SDK methods `account/state`, `account/details`, `account/sign-in/start`, `account/sign-in/cancel`,
  `account/sign-out`, `account/tasks/running` and notifications `account.*` (patch 10's `sdkExtensions` service;
  contract §2.4).

The app uses `deepseek-account` only when no API key is set; the engine starts without `DEEPSEEK_API_KEY` so login can run.

## Patches

Targets must be stable file names. `build-engine.sh` refuses a patch whose target is missing and maps a content-hashed
chunk name (`name-XXXXXXXX.js`) to the single current chunk of that name, so a dsh upgrade fails loudly instead of
silently skipping. All patches are inert on desktop Linux unless the corresponding env var is set (the host e2e sets
`DSH_NATIVE_DIR` to exercise the Android code paths).

| Patch | Package(s) | What / why |
|---|---|---|
| `01-flock-android` | `node-addon-system` | `flock` threw `ERR_FLOCK_UNSUPPORTED_PLATFORM` on android (the session write lease needs it). Loads `$DSH_NATIVE_DIR/libdsh_flock.so` via `process.dlopen`. |
| `02-require-builtin-expose-internals` | `node-addon-require-builtin` | dsh uses this addon only to reach Node *internal* modules (`internal/modules/esm/loader`, …), which `process.getBuiltinModule` does not serve. The engine runs with `--expose-internals` (argv only; Node refuses it in `NODE_OPTIONS`) and the patched entry uses plain `require()`, so no native binding ships. Without the flag the upstream path runs unchanged (on android it throws a clear error). |
| `04-shell-system-bin-sh` | `dsh-bash-local` | `bash -c` → `/system/bin/sh -c` on android (override: `DSH_BASH_SHELL`). |
| `05-hardlink-copy-fallback` | `dsh-attachment-local`, `dsh-session-persistence-jsonl` | `link(2)` is denied in app storage; fall back to exclusive copy to a sibling temp + rename, keeping `EEXIST` semantics. |
| `06-ripgrep-from-path` | `dsh-tool-fs-search` | `@vscode/ripgrep` has no android build: use `DSH_RG_PATH`, else `rg` from `PATH` (`files/bin/rg → librg.so`). |
| `07-android-profile-template` | `dsh-app-boot` | adds `android` to the shipped profile templates. |
| `08-sdk-approval-user-questions` | `dsh-sdk-jsonrpc-server` | the 0.2.1 release line's SDK server (rc.2 … rc.5) lacks `approval/request` / `user-question/ask`; backport from the upstream development line. **Drop once a bundled dsh ships them** (it would double-register; check `grep approval/request` in the unpatched server on every bump). Without it every approval fails closed. |
| `09-sharp-wasm-android` | `sharp` | no bionic libvips: on `android-*` sharp loads its WebAssembly build. Attachments (tool images, prompt images) need it. |
| `10-sdk-extension-methods` | `dsh-sdk-jsonrpc-server` | unknown SDK methods resolve through an optional `sdkExtensions` service or a `sdk-extensions/resolve` listener (works before `initialize`); plugins emit SDK notifications with `ctx.emit('sdk-extensions/notify', method, params)`. Used by plugin-account, plugin-guard and plugin-mode. Re-check on every dsh bump. |
| `11-sdk-prompt-time-zone` | `dsh-sdk-jsonrpc-server` | SDK prompts carried no time zone, so `dsh-time-context` reported "time zone unavailable". Prompts now carry `clientTimeZone` from `session/prompt`, else the process `TZ`. |
| `14-llm-lite-transport-cause` | `@snnh/dsh-llm-service-lite` (vendored) | a fetch failure surfaced as a bare `provider transport failed`: the cause chain does not reach the app. The message now carries the root cause and the target, e.g. `…: ECONNREFUSED 192.168.1.5:8080 (connection refused)` (undici's `cause` / `AggregateError` attempts, TLS codes; URLs reduced to their origin, never the key). Code stays `TRANSPORT` for any fetch `TypeError`; bare-code throws also count `EHOSTUNREACH`/`ENETUNREACH`/`UND_ERR_HEADERS_TIMEOUT`/…, not `ENOTFOUND`. Exports `classifyTransport` / `describeTransportCause` for the unit test. The app maps the code to a hint (`NetDiagnose`). |
| `15-http-proxy-exclusion-globs` | `dsh-http-proxy` | `NO_PROXY` entries with a `*` other than a leading `*.` match as a whole-host glob, so Android exclusion lists (`192.168.*`, `10.*`) bypass the proxy as they do for `HttpURLConnection`. |
| `16-llm-lite-usage-null-parse-errors` | `@snnh/dsh-llm-service-lite` (vendored) | (#20, #28) OpenAI-compatible gateways (and OpenAI itself with `include_usage`) send `"usage": null` on every content frame; `usageOf` only skipped `undefined`, threw a `TypeError` on the first frame, and `classifyTransport` called every `TypeError` a network failure: `TRANSPORT`, retried ~25 s, shown as "can't reach the model service" with only `host:port`. Now: chat-completions treats null / non-object `usage`, `prompt_tokens_details`, `error`, `choices[i]`, `delta`, `tool_calls[i]` as absent (anthropic / responses already went through `asRecord`). `TRANSPORT` = undici `TypeError('fetch failed')`, a `TypeError` over a coded cause (`terminated` ← `UND_ERR_SOCKET`), or a bare network code; any other uncoded `TypeError`/`RangeError`/`SyntaxError` is `MALFORMED_RESPONSE` (not retried): `llm-service-lite: could not parse the provider's response: <Name>: <message>` (the app shows 模型服务返回的数据格式不兼容). A cause-less transport failure names the error itself (`(TypeError: fetch failed)`). A 404 says `HTTP 404 Not Found from <origin>` and, for a base URL without a path, suggests `<origin>/v1` (NVIDIA NIM). Exports `notFoundMessage`. |
| `17-llm-lite-reasoning-echo-gateway-compat` | `@snnh/dsh-llm-service-lite` (vendored) | **Thinking echo-back.** The chat wire read thinking only from `reasoning_content` and sent it back only as `reasoning_content`, only for model ids whose catalog family declares replay. Thinking is now read from `reasoning_content`, `reasoning` (OpenRouter, vLLM, Ollama), `reasoning_details` (OpenRouter, MiniMax; merged by index, signatures kept) and thinking parts of an array `content` (Mistral); Gemini's per-call `extra_content` is kept. The finish chunk records, as adapter-private replay state on the assistant message (`source.replayState.response`, kind `llm-service-lite-chat`), which fields / shape it arrived in and the endpoint (base URL origin + path). The next request writes it back in that field / shape only to the same provider route *and* base URL: always on tool-call turns, on other turns only when the family/capabilities declare replay, never with `replayReasoning: false`; `reasoning_details` / `extra_content` only to the same model. A 400 naming an echoed field is retried once without echo and the base URL remembered. History without a record keeps the upstream rule. **Gateway compatibility** (found by `gateway-replay`): JSON (non-streaming) bodies; any `finish_reason` ends the turn (`stop` + calls = tool-calls, `error`/`network_error` = `SERVER`); tool calls matched by id then index (missing index, reused index 0, repeated names, missing ids synthesized, `""` arguments → `{}`); usage inside the choice (Moonshot), `prompt_cache_hit_tokens` / `cached_tokens`; error bodies `{"error":"…"}`, `{"detail":…}`, bare JSON strings; LM Studio's 200 `Unexpected endpoint` → 404 hint; CJK / gateway balance wording and 403 + balance → `QUOTA`. |
| `18-llm-lite-route-headers-model-access` | `@snnh/dsh-llm-service-lite` (vendored) | **Route headers** (#32: OpenCode Go refuses chats without `x-opencode-session`). New route fields: `sessionHeader` (every model request of a harness session carries sha256(tag, route, sessionId)[0..32]: stable for the session and across restarts, unlinkable to the id, different per session / route; fresh random id without a session and on model listings), `headerEnv` (header → credential reference, resolved per request like `apiKeyEnv`; never replaces the key's authorization), `userAgent` (product token in front of the harness UA: `DSH-Android/0.2.6 deepseek-harness/…`; a declared User-Agent header wins). Final headers are lower-cased so declared headers replace defaults case-insensitively. **`MODEL_NOT_AVAILABLE`**: a 4xx (not 401/402/429) saying the model is not activated (未开通, Ark `ModelNotOpen`), or that access is denied (on a 403, or without "does not exist" wording: DashScope `Model.AccessDenied`, Zhipu 1220, SiliconFlow 30011, OpenAI project access), was AUTH / INVALID_REQUEST; now its own code (not retried), message `llm-service-lite: model "<id>" is not available to this account: <server text>`. The app maps it (`NetDiagnose.modelUnavailableOf`). |
| `19-tool-command-owners` | `dsh-tools`, `dsh-commands` | `register()` records the Loader entry (fiber chain → `fiber.entry`) that registers each tool / command; `ownerOf(name)` returns `{ entryId, rowId, moduleName }`. plugin-extensions maps rows to third-party bundles: mode category `extensions`, ask-before-call, "provides" lists. Relies on cordis-loader's `fiber.entry`: re-check on every dsh bump. |
| `20-plugin-market-fetch-hook` | `dsh-plugin-market` | `fetchIndex` calls `PluginMarket.fetchResponse` when it is set (else the global `fetch`). plugin-extensions sets it to its mirror fetch (`fetch-sources.js`): a GitHub-hosted index (jsDelivr `/gh/…` or raw.githubusercontent.com) is read from the first of its equivalent sources that answers (cdn / fastly / gcore / testingcf.jsdelivr.net, raw.githubusercontent.com), started in order a few seconds apart, the last working one first next time. The index URL stays the identity (cache key, icon base, error). |
| `21-sdk-prompt-steer` | `dsh-sdk-jsonrpc-server` | SDK `session/prompt` always queued a new turn (`agent.followup`), so a message sent while a turn ran (插话发送 / 立即发送) reached the model only after the turn ended (#41). `mode: "steer"` now uses dsh's `Agent.steer` (the Web host's steer mode) while the session runs: the message is claimed at the next step boundary, before the next model request of the same turn; an idle session starts a turn as before. The result adds `delivery: "step" \| "turn"`. A turn that fails with a steer still unclaimed (sent during its last model request) leaves dsh idle with the steer pending, to be claimed by the next prompt; it now runs at once as a turn of its own. `session/resume` drops input still pending in the log (a dead process's unclaimed steer or queued prompt; the app hands it back to the user). |

Not patched: `terminal-bash` (`/bin/bash` default) and `dsh-api-workspace-controller` are not mounted by
dsh-base/sdk-app; `subprocess-local`'s `/bin/sh` fallback is Linux-only (Android uses plain `child_process.spawn`; its
one-time warning about process-tree containment is expected); `sandbox-local` is handled by profile config. node-pty is
not patched; `libdsh_pty.so` ships but is unused while no terminal row is mounted.

## Host tests

```sh
node js/test/host-e2e.mjs [engine.zip] [host-native-dir]
E2E_PHASES=C,D node js/test/host-e2e.mjs          # account phases only, no API key
```

Defaults: `toolchains/out/engine/engine.zip` and `toolchains/out/engine-build/host-native`; scratch under
`toolchains/tmp/`. The API key is read from the file named by `DSH_E2E_KEY_FILE` (default
`~/.config/dsh/deepseek_api_key`) and never printed. Other knobs: `E2E_ENGINE_DIR` (reuse an extracted tree),
`E2E_MODEL`, `E2E_KEEP=1` (keep scratch).

Host bindings are installed under their Android file names with `DSH_NATIVE_DIR` set, so the patched loaders run.

- **A**: boot without a bridge; profile self-initializes; cwd is `HOME`; the missing bridge is logged once.
- **B**: a fake bridge on fd 3 with `file_list` (read), `file_write` (write) and `screen_capture` (read, needsUnlock);
  real `deepseek-flash` prompts check read-without-approval, `approval/request` for writes, results flowing back, a
  hardline block of `rm -rf /sdcard` (via a test-only `--patch` persona overlay, since the shipped prompt makes the model
  refuse to try), and a budget-refused turn. Also checks that sharp loads its WebAssembly build as `android-arm64`.
- **C**: account login against a local fake Platform (PKCE, bad `state`, failed exchange, success page, details,
  re-`initialize` onto `deepseek-account`, 401 → session expired, sign-out, cancel).
- **D**: real `https://platform.deepseek.com` up to the authorize URL; forged callbacks rejected; cancel. Completing a
  real login needs a person.
- **E**: B with `process.platform` faked to `android` (approval policy, time zone, unattended runs).

Not covered on the host: the bionic addons, `/system/bin/sh`, the `link(2)` fallback, image attachments reaching an
image-capable model, a real login through the in-app WebView.

## Size

`size-report.txt` (next to the zip) records `node_modules` size before and after pruning, the zip size and the largest
packages.
