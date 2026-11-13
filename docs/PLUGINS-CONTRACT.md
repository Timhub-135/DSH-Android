# Plugins contract (engine ↔ app)

Plugins, skills and MCP servers on the phone (issue #17; design: `PLUGINS-DESIGN.md`). This file is the single source
of truth for what the app writes, what the engine reads, and every `android/ext/*` method. It extends
[`ENGINE-CONTRACT.md`](ENGINE-CONTRACT.md) (§1 launch, §1.1 settings layer, §2.5 modes, §2.6 pointer here).

Principle: the engine is the only source of truth for plugin state; dsh's own plugin manager (pnpm in the profile
directory) does every install, enable and removal, exactly as on desktop. The app renders, picks files, stores MCP
settings and secrets, and restarts the engine when a result says `restart-required`.

## 1. Launch, environment, files

Entry: `<ENGINE_DIR>/node_modules/@dsh-android/bundle-android/main.mjs` (replaces `@deepseek-ai/dsh/lib/bin.js
--profile android`; same node flags, same `--require …/android-env.cjs` preload). `files/bin/node` →
`<nativeLibraryDir>/libdshnode.so` (`EngineInstaller.linkTools`, like `rg`).

| Env (app → engine) | Meaning |
|---|---|
| `DSH_NODE_BIN` | node launcher, `<nativeLibraryDir>/libdshnode.so`. Optional: fallback `$DSH_NATIVE_DIR/libdshnode.so` |
| `DSH_PLUGIN_REGISTRY` | first registry (`https://…/`); unset = by region: npmmirror when `DSH_ANDROID_LOCALE` is `zh`, `zh-CN`, `zh-Hans*` or `DSH_ANDROID_TZ`/`TZ` is `Asia/{Shanghai,Chongqing,Chungking,Urumqi,Harbin}`, else npmjs |
| `DSH_PLUGIN_FALLBACK_REGISTRIES` | comma-separated fallbacks; empty string = none; unset = the other official registry |
| `DSH_ANDROID_SAFE_MODE=1` | start with every third-party plugin suspended (§1.3) |
| `DSH_MCP_<ID>_<HEADER>` | secret MCP request header values, referenced from the overlay (§1.1); naming is the app's, the engine only evaluates the `!!js` the app wrote |

Exported by the engine into its own `process.env` before boot (usable from overlay `!!js` and by plugins):
`DSH_NODE_BIN` (the value used), `DSH_ANDROID_ENV_CJS` (the current `android-env.cjs`), `DSH_ANDROID_MCP_DIR`
(`$DSH_HOME/mcp-servers`). The app's fallbacks (launcher in `nativeLibraryDir`, `android-env.cjs` next to the entry)
are the same paths.

Files (`$DSH_HOME` = `files/dsh-home`; nothing lives in `files/engine/<sha>/`, so an engine update keeps every plugin):

| Path | Writer | |
|---|---|---|
| `android/app-layer.patch.yml` | **app only** | settings layer, a `--patch` overlay above all layers (§1.1) |
| `profiles/android/package.json`, `cordis.patch.yml`, `pnpm-lock.yaml`, `compatibility.json`, `node_modules/` | dsh (plugin manager, config editor) | the profile; plugins hoisted, no symlinks |
| `profiles/android/pnpm-workspace.yaml`, `.pnpmfile.cjs` | engine, every boot | `nodeLinker: hoisted`, `autoInstallPeers: false`, `packageImportMethod: copy`, `storeDir`, `ignoreScripts: true`, bounded fetch retries; the gate of the current engine |
| `android/extensions-state.json` | engine | suspensions, safe mode, install sources, ask-before-call, update cache, market indexes, notices |
| `android/boot-journal.json`, `android/last-crash.json` | engine | §1.3 |
| `skills/<name>/`, `skills-disabled/<name>/` | engine (skills methods) | `skills/` is scanned by dsh (watched, no restart) |
| `mcp-servers/` | engine | pnpm project of npm stdio MCP servers |
| `<cacheDir>/tmp/dsh-pnpm/` (or `DSH_ANDROID_CACHE_DIR`) | engine | pnpm cache/store (store emptied after each operation), `ext-staging/` (repacked archives) |

Paths the app passes as `input` (picked files) must be absolute files the engine can read, e.g. copied into
`<cacheDir>/ext-staging/` first.

### 1.1 App settings layer (what the app writes)

`$DSH_HOME/android/app-layer.patch.yml`, fully regenerated before each start, atomic, only when the text changed; the
restart fingerprint covers its text and the env secrets. Content = ENGINE-CONTRACT §1.1 (llm-service-lite,
compaction-basic, tool-web rows) plus one row per enabled MCP server:

```yaml
- insert:
    - id: mcp-<id>                       # <id> = [A-Za-z0-9_-]{1,32}, also serverName → tools mcp__<id>__<tool>
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: <id>
        transport: streamable-http
        url: https://…/mcp
        headers:
          Authorization: !!js "process.env.DSH_MCP_<ID>_AUTHORIZATION"
        failOnStartupError: false
    - id: mcp-<id2>                      # npm stdio server installed with android/ext/mcp/install
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: <id2>
        transport: stdio
        command: !!js "process.env.DSH_NODE_BIN"
        args: !!js "['--require', process.env.DSH_ANDROID_ENV_CJS, '--max-old-space-size=256', process.env.DSH_ANDROID_MCP_DIR + '/node_modules/<bin>', ...<user args as a JSON array>]"
        env: {}                          # plain values only; secrets via !!js process.env.…
        failOnStartupError: false
```

`<bin>` is `bin` from `android/ext/mcp/install` (e.g. `@modelcontextprotocol/server-memory/dist/index.js`). Absolute
paths instead of the `!!js` forms also work (the app rewrites the file every start). An mcp-client row waits for its
first connection during boot: keep `failOnStartupError: false`, and at most 4 stdio servers enabled (phantom-process
limit, ~40–80 MB each).

### 1.2 Migration from the old profile patch

At boot, a `profiles/android/cordis.patch.yml` whose first line starts with `# Generated by DSH-Android` (the old app
writer) is backed up to `cordis.patch.yml.bak-<ms>` and replaced by an empty list; if no overlay exists yet, its text
becomes `android/app-layer.patch.yml`. Rows the overlay sets cannot be changed by plugins or by `config/set`
(dsh refuses: "overridden by … overlay"; `config/get` reports `managedByApp`).

### 1.3 Crash attribution and safe mode

- Every boot appends `{phase: 'booting', pid, bundles}` to `boot-journal.json`; when dsh's tree is up it becomes
  `ready`; SIGTERM/SIGINT before that → `stopped` (not a failure).
- A fatal error (uncaught exception, fail-loud rejection → exit ≠ 0) is written to `last-crash.json` with its stack.
  Next boot: if the stack's files under `profiles/android/node_modules/` belong to exactly one selected third-party
  bundle (itself or its dependency closure), that bundle is deselected and `suspended {reason: 'crash'}`.
- Otherwise, when the last two boots of this engine build never became ready (SIGKILL on the app's initialize timeout
  counts; journal entries carry `engineVersion`, an older build's boots do not count) or the app sets
  `DSH_ANDROID_SAFE_MODE` (`1` = the user's 安全模式启动, `crash-loop` = the app's crash breaker is open), and
  third-party bundles are selected: **safe mode** — bundles reset to the three built-ins, every third-party one
  `suspended {reason: 'safe-mode'}`, `safeMode = {active, savedBundles, reason: boot-failures|requested|crash-loop,
  engineVersion}`.
- Only an engine that did not come up or exited unexpectedly counts (app: EngineManager start failures and channel
  losses feed RestartPolicy); an `android/ext/…` result the app cannot decode is `bad-result` on the 扩展 page, never
  an engine failure, a restart or a safe-mode request.
- Both produce a notice (§4). `android/ext/resume` / `setEnabled` re-selects one. Safe mode is a state of the installed
  plugins: it ends as soon as no installed plugin is paused by it (resumed, removed — the plugin set empty), checked at
  every boot, on `list` and after each change; suspensions of plugins no longer installed are dropped. The app shows the
  banner only while `safeMode` and at least one plugin is `suspended {reason: 'safe-mode'}`.
- Upgrade: safe mode recorded by another engine build (no `engineVersion`: v0.3.2 and older) is cleared once at the
  first boot of the new build, its plugins selected again (notice `safe-mode-cleared`). Decision: the old build's cause
  may be gone — v0.3.1's 扩展 page offered 安全模式启动 for a list it merely failed to decode, and the banner then claimed
  two failed boots; a plugin that really breaks boot brings safe mode back after two failed boots of the new build.
  Crash suspensions (stack-attributed) are kept.

## 2. Data model

```ts
interface UserError {            // §5
  code: string; message: string  // one sentence, zh for DSH_ANDROID_LOCALE zh*, else en
  params: object                 // the facts in the message (name, pkg, file, range, dsh, registry, host, services, labels, …)
  detail?: string; logPath?: string
  actions?: ('retry-mirror'|'retry'|'force-install'|'uninstall'|'disable'|'open-log'|'resume')[]
}
interface ChangeView {
  application: 'applied'|'restart-required'|'failed'|'cancelled'
  restartRequired: boolean        // call the existing restartEngine() when idle
  name?: string; version?: string; warnings?: string[]; error?: UserError
  skippedOptional?: string[]      // computer-only optional dependencies dropped
  sharedWithEngine?: string[]     // dependencies turned into peers of the engine's copy
  deferred?: true                 // remove: the package goes at the next start
}
// Display text from manifests / bundle metadata / indexes. The engine normalizes (errors.js localizedText):
// a non-blank string, or {tag: text} with lower-case '-' tags (`zh_CN` → `zh-cn`) and non-blank strings.
// The app decodes both (LocalizedText) and picks exact tag → shorter tag → same language → en → first.
// The app reads lists element by element: one entry it cannot read is shown as its name with a note
// (code 'bad-entry'), never as a failed list; an unreadable result is 'bad-result', not an engine failure.
type LocalizedText = string | { [languageTag: string]: string }
interface ExtPlugin {
  name: string; title: LocalizedText; description?: LocalizedText; version?: string
  source: { kind: 'npm'|'tarball'|'file'|'github'; spec: string; input?: string; registry?: string; repo?: string }
  license?: string; homepage?: string; publisher?: string
  enabled: boolean                // in dsh.profile.bundles
  status: 'active'|'disabled'|'restart-pending'|'incompatible'|'failed'|'waiting'|'suspended'
  statusDetail?: UserError        // incompatible-version | activation-failed | waiting-service | crash-suspended | safe-mode
  rows: { id: string; module: string; entryId?: string; phase: string; error?: string; waitingFor?: string[]; configurable: boolean }[]
                                  // phase: active|failed|waiting|loading|disabled|inactive|missing
  tools: string[]; skills: string[]; commands: string[]   // what it provides (running plugins)
  overrides: { rowId: string; label: string }[]           // built-in rows its patch changes ("会修改：联网搜索")
  skippedOptional: string[]
  updatable: boolean; update?: { version: string; compatible: boolean }
  askBeforeCall: boolean
  suspended?: { reason: 'crash'|'safe-mode'; at: number; message?: string }
}
interface ExtList { plugins: ExtPlugin[]; safeMode: boolean; safeModeReason?: 'boot-failures'|'requested'|'crash-loop'; restartPending: boolean; dshVersion: string; registries: string[]; notices: Notice[] }
interface Notice { kind: 'suspended'|'safe-mode'|'safe-mode-cleared'|'activated'|'activation-failed'; name?: string; code?: string; message: string; tools?: string[]; count?: number; at: number }
```

Only third-party bundles are listed (not dsh-base, dsh-sdk-app, bundle-android).

## 3. SDK methods (App → engine, JSON-RPC on stdin/stdout; work before `initialize`)

Operations that touch packages run one at a time (queued). Expected refusals are results with `error`, never JSON-RPC
errors; a JSON-RPC error means a bad request (missing parameter) or an engine bug.

| Method | Params → result |
|---|---|
| `android/ext/list` | `{}` → `ExtList` (first call loads dsh's plugin manager, ~0.1 s) |
| `android/ext/resolve` | `{input}` → `{spec, kind, display, updatable, repo?, note?}` or `{error}` (network only for GitHub/zip URLs) |
| `android/ext/inspect` | `{input}` → `{spec, kind, display, name, version, description, bundle, registry, compatible?, incompatible?, publisher?, maintainers?, license?, sizeBytes?, provenance?, downloads?, homepage?, repository?, overrides?, error?}` |
| `android/ext/install` | `{input, requestId?, force?}` → `{result: ChangeView}`; `force` grants dsh's exact-version exemption after an `incompatible-version` refusal and retries |
| `android/ext/cancel` | `{requestId}` → `{cancelled, status}` |
| `android/ext/setEnabled` | `{name, enabled}` → `ChangeView` (enabling also lifts a suspension) |
| `android/ext/setRowEnabled` | `{entryId, enabled}` → `ChangeView` (one row of a bundle; `entryId` from `rows[]`) |
| `android/ext/remove` | `{name}` → `ChangeView`; a running bundle is deselected now and removed at the next start (`deferred`); the profile patch's rows for its row ids are dropped |
| `android/ext/updates` | `{refresh?}` → `{updates: {name, current, latest, compatible}[], checkedAt}` (cached 6 h; one abbreviated packument request per npm-installed plugin) |
| `android/ext/update` | `{name, version?, requestId?, force?}` → `ChangeView` (exact version, same gates; a refused update reinstalls the previous version) |
| `android/ext/detail` | `{name, language?, version?}` → `{markdown?, origin?}` (installed README.<lang>.md / README.md, else the first of jsDelivr (cdn / gcore / testingcf) → unpkg → npmmirror that answers) |
| `android/ext/config/get` | `{entryId}` → `{entryId, rowId, module, schema, limitations, value, overridden, managedByApp}`: `schema` = JSON Schema 2020-12 for a form (no `!!js` branches, non-null types, constant unions as `enum`, `x-cordis.role: 'secret'` for secrets, `$defs` when recursive); `null` without a Config |
| `android/ext/config/set` | `{entryId, value}` → `{ok: true}` or `{ok: false, errors: {path, message}[]}`; applied live (no restart) through dsh's config editor, persisted in the profile patch |
| `android/ext/askBeforeCall` | `{key, enabled}` → `{}`; `key` = bundle name or `mcp:<serverName>` |
| `android/ext/resume` | `{name}` → `ChangeView` |
| `android/ext/log` | `{name?}` → `{text, path}` (last 64 KiB of the newest pnpm log, mentioning `name` if given) |
| `android/ext/market/indexes` | `{}` → `{indexes: {url, title?, description?, error?}[]}` |
| `android/ext/market/setIndexes` | `{urls}` → `{indexes}` (stored in extensions-state.json; default `https://cdn.jsdelivr.net/gh/Timhub-135/DSH-Android@main/plugins/index.json`; a stored former address `…/Timhub-135/DSH-Android@main/…` is rewritten to it at boot and on set) |
| `android/ext/market/search` | `{url, query?, offset?, limit?, refresh?, category?, sort?}` → dsh plugin-market `search` result (index format 1, entries annotated with install state and per-version compatibility), or `{error}` (`index-unreachable`, `index-invalid`, `unknown`). A GitHub-hosted index (jsDelivr `/gh/…`, raw.githubusercontent.com, github.com `raw`/`blob`) is read from the first of its equivalent sources that answers — cdn / fastly / gcore / testingcf.jsdelivr.net, raw.githubusercontent.com — started 2 s apart (at once after 6 s without headers; earlier ones keep running, the first answer wins; 25 s overall), the last working one first next time (`sourcePreferences` in extensions-state.json; `fetch-sources.js`, js patch 20) |
| `android/ext/market/entry` | `{url, name}` → `{entry \| null}` or `{error}` as for search |
| `android/ext/skills/list` | `{}` → `{skills: {name, description, source: 'imported'\|'plugin'\|'agents'\|'builtin'\|…, enabled, removable, scripts: string[], path?}[]}` |
| `android/ext/skills/preview` | `{input}` (absolute .md / .zip / .skill / directory, any URL, GitHub repo / tree / blob / raw link, §5.1) → `{previewId, skills: {name, description, path, files, scripts, valid, problem?, exists}[]}` or `{error}` |
| `android/ext/skills/import` | `{previewId, names, overwrite?}` → `{imported, skipped: {name, reason: 'invalid'\|'exists'}[]}` |
| `android/ext/skills/setEnabled` | `{name, enabled}` → `{}` (moves between `skills/` and `skills-disabled/`) |
| `android/ext/skills/remove` | `{name}` → `{}` |
| `android/ext/mcp/parse` | `{text}` (`{"mcpServers":{…}}`, a map, one server, or a URL) → `{servers: {id, name, kind: 'remote'\|'npm'\|'local'\|'binary', url?, headers?, package?, args?, env?, supported, error?}[]}` |
| `android/ext/mcp/test` | `{config: {kind: 'remote', url, headers?} \| {kind: 'npm', package, args?, env?, bin?}, timeoutMs?}` → `{ok: true, tools: {name, description}[], server}` or `{ok: false, error}` |
| `android/ext/mcp/install` | `{package}` (npm spec) → `{ok: true, package, version, bin}` or `{ok: false, error}` (same gate and binary scan as plugins) |
| `android/ext/mcp/remove` | `{package}` → `{ok}` |
| `android/ext/mcp/status` | `{}` → `{servers: {id, serverName, phase, error?, waitingFor?, tools, askBeforeCall}[], installed: {package, version, bin}[], maxLocalServers: 4}` |

Install pipeline: resolve source → `pnpm add` through dsh's plugin manager (gate 1: the pnpmfile refuses native modules
during resolution, nothing is downloaded) → gate 2: binary scan of added packages (`.node`, ELF, `prebuilds/`,
`binding.gyp`) → gate 3: the bundle's row modules are imported in a worker and their required services checked against
the web-only list (DS-Bot: `needs-web-services`) → enable. A refusal after pnpm removes the package again.

Sources: npm names/specs; `npmjs.com/package/…`, `npmmirror.com/package/…` links; GitHub `https://github.com/o/r[/tree/ref/dir]`
or `o/r` → the registry package of the same repository if published, else the latest release's `.tgz`, else the commit's
source tree repacked (refused with `needs-build` when its entry points are missing); `.tgz`/`.tar.gz` URLs; `.zip` URLs
and local `.zip`/directories (repacked); local `.tgz`; `git+ssh:`, `git@…` → `needs-git`.

## 4. Notifications (engine → App)

| Method | Params |
|---|---|
| `android.ext.progress` | `{requestId, phase: 'resolving'\|'installing'\|'checking'\|'applying', registry?, index?, total?}` |
| `android.ext.changed` | `{}`: refresh the list |
| `android.ext.notice` | `Notice` (§2), sent once dsh's tree is up; the same notices are in `ExtList.notices` for this boot |

## 5. Error codes

`native-addon` (`params.pkg`), `prebuilt-binary` (`pkg`, `file`), `needs-desktop-browser`, `needs-web-services`
(`services`, `labels`), `not-bundle`, `incompatible-version` (`range`, `dsh`, `incompatible[]`), `not-found`
(`registry`), `network` (`host`, `reason`), `integrity`, `no-space`, `needs-git`, `needs-build`, `github-unreachable`,
`bad-archive`, `bad-input`, `already-installed`, `installer-broken` (no launcher / pnpm), `activation-failed`,
`waiting-service`, `crash-suspended`, `safe-mode`, `pnpm-failed`, `not-installed`, `not-removable`,
`management-required`, `skill-invalid`, `skill-exists`, `mcp-sse-only`, `mcp-runtime` (`runtime`), `mcp-local-path`,
`mcp-auth` (`status`), `mcp-failed`, `mcp-too-many`, `index-unreachable` (`url`, `tried` = localized `host（原因）、…`,
`attempts: {host, url, reason: timeout|dns|refused|reset|tls|unreachable|http-<n>|network, detail?}[]`; action `retry`),
`index-invalid` (`url`, `reason`; action `retry`), `link-unreachable` (a user-supplied link: `url`, `host`, `tried` as
for `index-unreachable` with the root cause's code, e.g. `raw.githubusercontent.com（连接超时 UND_ERR_CONNECT_TIMEOUT）`,
`attempts: {…, code?}[]`; action `retry`), `link-is-page` (`url`, `host`: a web page where a file was expected),
`link-too-large` (`host`, `maxBytes`), `unknown`. Unknown codes: show `message`.

### 5.1 User-supplied links

Every link the user gives (skill import, plugin `.zip` / GitHub links, index URLs) goes through `fetch-sources.js`:
`parseGithubLink` normalizes github.com `blob` / `raw` / `tree` / repository links, raw.githubusercontent.com (also
`refs/heads/…`, `refs/tags/…`) and jsDelivr `/gh/` URLs to `{owner, repo, ref, path, kind: file|dir|repo}`; a GitHub
file is read from raw.githubusercontent.com and the jsDelivr CDNs (a github.com page itself never: it is HTML), a gist
page from its raw URL, anything else as is. `fetchFirst` starts candidates staggered; a candidate quiet for the header
timeout lets the next one start but keeps running — the first 2xx answer wins — so a slow-but-working source (the only
one, or the last mirror left) is ended only by the operation's deadline (links: 45 s to headers, 120 s body). Skills: a
SKILL.md link imports its directory; directories come from jsDelivr's listing (`data.jsdelivr.com`) + file mirrors, or
the codeload archive (archive first for a whole repository), the single file as the last resort. The app makes no
extension network requests of its own.

## 6. Tools, modes, approvals

- js patch 19 records which Loader entry registers each tool and command (`ctx.tools.ownerOf`, `ctx.commands.ownerOf`);
  the `androidExtensions` service maps entries to third-party bundles.
- Mode category `extensions` (ENGINE-CONTRACT §2.5) = third-party plugin tools + `mcp__*`. The app's `EngineTool`
  for it ("插件和 MCP 工具") must be included by every mode that should keep them.
- `askBeforeCall` keys make plugin-guard return `ask` for that plugin's / server's tools; the app's existing approval
  card shows them (risk `write`, as for any tool without a device descriptor).

## 7. DS-Bot

Refused at install with `needs-web-services` (`connection`, `sessionController`, `workspaceRegistry`). Checked:
mounting dsh's `workspace`, `session-controller`, `file-upload` rows and a headless `connection` stub makes its row
activate, but its `connection` collides with dsh-api-gateway's binding of any `connection` + `webServer` pair (our
account loopback server), and every DS-Bot tool answers "Only Bots on the team can use …" outside Bot sessions,
which only its web client opens. Not worth a web stack on the phone.

## 8. Needs device verification

exec of `libdshnode.so` from `:engine` (SELinux), `AT_SECURE` in the child, pnpm peak RSS (< 250 MB target),
`.pnpmfile.cjs` + `packageImportMethod: copy` on app storage, stdio MCP children under the phantom-process limit, real
npmmirror installs from mainland networks.
