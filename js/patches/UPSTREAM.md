# Upstreaming the provider patches

Status: **nothing submitted**. This file checks whether each patch that touches model requests could
go upstream, names the upstream, and drafts the issue / PR text. Before filing, re-check against the
current upstream release; the line numbers in our patches are against the versions pinned here.

| Patch | Package (pinned) | Upstream | Android-specific? | Upstreamable |
|---|---|---|---|---|
| 14 `llm-lite-transport-cause` | `@snnh/dsh-llm-service-lite` 0.2.1 (vendored) | [snnh/dsh-llm-service-lite](https://github.com/snnh/dsh-llm-service-lite) | no | yes, as is |
| 15 `http-proxy-exclusion-globs` | `@deepseek-ai/dsh-http-proxy` (dsh 0.2.1-rc.2) | DeepSeek Harness (`@deepseek-ai/dsh-*`) | motivated by Android's exclusion list, behaviour is general | yes |
| 16 `llm-lite-usage-null-parse-errors` | `@snnh/dsh-llm-service-lite` 0.2.1 | snnh/dsh-llm-service-lite | no | yes, as is |
| 17 `llm-lite-reasoning-echo-gateway-compat` | `@snnh/dsh-llm-service-lite` 0.2.1 | snnh/dsh-llm-service-lite | no | yes; best split into 3 PRs (below) |
| 18 `llm-lite-route-headers-model-access` | `@snnh/dsh-llm-service-lite` 0.2.1 | snnh/dsh-llm-service-lite | `userAgent` default is ours; the rest no | yes, as 2 PRs (below) |
| 22 `llm-lite-declared-reasoning` | `@snnh/dsh-llm-service-lite` 0.2.1 | snnh/dsh-llm-service-lite | no | yes, as a semantics question first (below) |

Only patch 15 targets dsh itself. 14, 16, 17 and 18 target the community adapter; dsh's own
`dsh-llm-deepseek` adapter is not patched. One dsh-side note belongs with 17 (see "dsh core" below).

Common ground for every PR: the changes carry `DSH-Android (patch N)` comments that should be dropped
or reworded upstream, the adapter is shipped as a built `lib/index.js` (the PR has to go against the
TypeScript sources in `lib/types/*.ts`), and our tests run against the built file
(`js/test/provider-network.test.mjs`, `provider-protocol.test.mjs`, `gateway-replay.test.mjs` +
`test/fixtures/gateways/`). The fixture suite is the most useful single contribution: it is
independent of our code and pins behaviour per provider.

---

## 14: name the cause of a transport failure

**What it changes.** `classifyTransport` turned every fetch `TypeError` into
`llm-service-lite: provider transport failed`, dropping undici's `cause` chain, so the user saw no
reason. The message now carries the root cause and the target (`ECONNREFUSED 192.168.1.5:8080
(connection refused)`, TLS codes, `AggregateError` attempts), URLs reduced to their origin (never the
key). Codes are unchanged (`TRANSPORT`); a few more bare network codes count as transport.
Exports `classifyTransport` / `describeTransportCause` for tests.

**Draft issue / PR** (snnh/dsh-llm-service-lite):

> **Transport failures lose their cause: "provider transport failed" with no reason**
>
> When `fetch` fails (refused port, DNS, TLS, proxy), the adapter throws
> `LlmError("llm-service-lite: provider transport failed", "TRANSPORT")` and the undici `cause`
> chain is not in the message. Front ends only show the message, so a user with a wrong LAN IP, a
> self-signed certificate or a missing proxy gets the same text for all of them.
>
> This PR walks `error.cause` (and `AggregateError.errors`) to the root, and appends
> `<CODE> <host:port> (<plain-language reason>)` to the message, e.g.
> `provider transport failed: ECONNREFUSED 192.168.1.5:8080 (connection refused)` or
> `…: CERT_HAS_EXPIRED api.example.com:443 (TLS certificate rejected: certificate has expired)`.
> URLs inside messages are reduced to their origin so a key in a query string never leaks. The
> error code and retry behaviour do not change. Tests: unit tests over synthetic undici error
> shapes + an end-to-end refused-port test.

## 15: Android / Java-style `NO_PROXY` globs

**What it changes.** `dsh-http-proxy` matches `NO_PROXY` entries as exact hosts or `*.suffix` /
`.suffix`. Exclusion lists from Android (`ProxyInfo.getExclusionList()`) and Java
(`http.nonProxyHosts`) use globs anywhere: `192.168.*`, `10.*`, `*.local`. Any entry with a `*`
other than a leading `*.` now matches as a whole-host glob.

**Draft issue / PR** (DeepSeek Harness, `@deepseek-ai/dsh-http-proxy`):

> **NO_PROXY: support host globs such as `192.168.*`**
>
> `NO_PROXY=192.168.*` (the form Android's and Java's proxy exclusion lists use) does not bypass
> the proxy for `192.168.1.5`, because only exact hosts and leading-dot / `*.` suffixes are
> recognised. A LAN model server behind a system proxy is therefore unreachable. Proposal: an entry
> containing `*` anywhere else is a glob over the whole host name (`*` = any run of characters,
> other characters literal). Existing forms keep their meaning. curl does not support this form,
> so this is an extension; it only adds matches for entries that previously never matched.

## 16: `"usage": null` frames; parse errors are not network errors

**What it changes.** OpenAI-compatible gateways (and OpenAI itself with `stream_options.include_usage`)
send `"usage": null` on every content frame; `usageOf` read `.prompt_tokens` of `null` and threw a
`TypeError`, which `classifyTransport` called a network failure (`TRANSPORT`, retried for ~25 s,
"can't reach the model service"). Now null / non-object `usage`, `prompt_tokens_details`, `error`,
`choices[i]`, `delta`, `tool_calls[i]` are absent; only undici's `fetch failed` or a `TypeError` over a
coded network cause is `TRANSPORT`, and any other uncoded `TypeError` / `RangeError` / `SyntaxError`
is `MALFORMED_RESPONSE` (not retried). A 404 names itself and suggests `/v1` for a bare-origin base
URL (NVIDIA NIM).

**Draft issue / PR** (snnh/dsh-llm-service-lite):

> **`usage: null` stream frames break every chat-completions turn (reported as a network error)**
>
> With `includeUsage` (or any gateway that always sends the field), each content frame carries
> `"usage": null`. `usageOf` does `chunk.usage.prompt_tokens` after checking only `!== undefined`,
> throws `TypeError: Cannot read properties of null`, and `classifyTransport` maps every `TypeError`
> to `TRANSPORT`, so the turn is retried and finally reported as an unreachable provider.
>
> Fix: treat `null` / non-object values as absent wherever the stream reads an optional object, and
> classify by origin: `TypeError('fetch failed')` or a `TypeError` whose cause carries a network
> `code` is `TRANSPORT`; other language errors raised while translating a received response are
> `MALFORMED_RESPONSE` with the error named. Also: a 404 says `HTTP 404 Not Found from <origin>` and,
> when the base URL has no path, suggests `<origin>/v1`. Tests against a local SSE server.

## 17: thinking echo-back + gateway compatibility

Best upstreamed as three PRs, in this order:

### 17a: gateway compatibility of the chat-completions translator

**What it changes** (each found by the replay suite, each with a fixture):

- a JSON (`stream: false`-style) completion body, from a proxy that ignores `stream: true`, is
  translated as one frame instead of failing as a cut-off stream;
- any non-empty `finish_reason` ends the turn (`tool_use`, `max_tokens`, `sensitive`, `eos`, …);
  `stop` with tool calls is `tool-calls`; `error` / `network_error` (OpenRouter, Zhipu) is `SERVER`;
- tool calls are matched by id, then index: no `index` (Gemini), parallel calls reusing index 0,
  names repeated on every fragment, an id that never arrives (one is synthesized), `""` arguments
  (`{}`);
- usage inside the final choice (Moonshot); cache hits as `prompt_cache_hit_tokens` (DeepSeek) or
  `usage.cached_tokens` (Moonshot);
- error bodies `{"error": "text"}` (LM Studio, Ollama), `{"detail", "title"}` (FastAPI, NVIDIA NIM),
  bare JSON strings (SiliconFlow 401); LM Studio's HTTP 200 `Unexpected endpoint or method` is a 404
  with the `/v1` hint;
- exhausted-balance wordings the shared English patterns miss (欠费, 余额不足, `AccountOverdueError`,
  `Arrearage`, `exceeded_current_quota_error`, "balance is insufficient") are `QUOTA`, also on 403.

**Draft PR** (snnh/dsh-llm-service-lite):

> **chat-completions: tolerate the stream shapes real gateways send (+ a replay test suite)**
>
> This adds `test/fixtures/gateways/`: raw response bodies (SSE, JSON, error bodies) for DeepSeek
> (live captures), OpenAI, vLLM, SGLang, NVIDIA NIM, OpenRouter, Ollama, LM Studio, SiliconFlow,
> Volcano Ark, Moonshot, Zhipu, DashScope, Mistral, MiniMax, Gemini's OpenAI endpoint and Azure,
> each with an expectation file, replayed through the real adapter from a local HTTP server. The
> README marks each body "live capture" or "from docs" (built from the documented format).
>
> Against 0.2.1 the suite fails on: Mistral text in array `content` (dropped entirely), thinking in
> `reasoning` / `reasoning_details` (dropped), Moonshot usage (dropped), JSON bodies, `eos` /
> `network_error` finish reasons, parallel calls sharing index 0 (arguments concatenated into
> invalid JSON), calls without id (dropped → empty response), repeated names (`get_weatherget_weather`),
> `""` arguments (invalid JSON), and several error bodies (no message / wrong code). The changes
> above fix each; nothing changes for well-formed streams.

### 17b: thinking echo-back in the field it arrived in

**What it changes.** Thinking is read from `reasoning_content`, `reasoning`, `reasoning_details`
(merged by index, signatures / encrypted items kept) and thinking parts of an array `content`, plus
per-call `extra_content`. The finish chunk records on `replayState.response` (kind
`llm-service-lite-chat`, version 1) the fields / shape and the endpoint (base URL origin + path). The
request path writes the thinking back in exactly that shape, only to the same provider route and
base URL: always on assistant turns with tool calls; on other turns when the family/capabilities
declare replay; never with `replayReasoning: false`; `reasoning_details` / `extra_content` only to
the same model. History without a record keeps the current rule.

**Draft issue / PR** (snnh/dsh-llm-service-lite):

> **Echo thinking back in the provider's own field, for gateways too**
>
> Today the chat wire reads only `delta.reasoning_content` and replays only as `reasoning_content`,
> and only when the model id matches a catalog family that declares replay. Reasoning models behind
> gateways break in both directions:
>
> - their thinking arrives as `reasoning` (OpenRouter, vLLM ≥ the rename, Ollama), as
>   `reasoning_details` (OpenRouter, MiniMax) or as `content` parts (Mistral) and is lost;
> - endpoints that require it back on tool-call turns (DeepSeek thinking mode, Kimi thinking, MiMo,
>   signed OpenRouter reasoning, Gemini thought signatures in `extra_content`) get nothing when the
>   model id is spelled the gateway's way, and endpoints that reject unknown fields could be sent
>   `reasoning_content` they never emitted.
>
> Proposal: record per assistant message, in the adapter-private replay state, which field / shape
> the thinking arrived in and from which base URL, and write it back in that shape only to the same
> route and base URL (and model, for signed data). Tool-call turns always echo (the endpoint emitted
> that field, so it reads it); other turns follow the declared family rule. If an endpoint answers
> an echoed field with a 400 naming it, retry once without echo and remember the base URL.
>
> The record has no per-block entries, so dsh's `BlockAssembler` passes it through unchanged even
> when blocks are pruned.

### 17c: dsh core (optional, issue only)

> **`replayState` envelopes with `blocks` are dropped whenever block counts differ**
>
> `BlockAssembler.assembled()` discards the whole envelope when `envelope.blocks.length` differs from
> the assembled block count. Adapters that record message-level facts (as 17b does) avoid `blocks`
> for that reason. Worth documenting on the `finish.replayState` type that a `response`-only envelope
> is preserved as is, so adapters can rely on it.

---

## 18: route session header / secret headers; MODEL_NOT_AVAILABLE

**What it changes.** (a) Route fields `sessionHeader` (an opaque per-session id, sha256 of route +
`options.sessionId`, fresh random without a session), `headerEnv` (secret header values by credential
reference) and `userAgent` (a product token in front of the attribution UA); final headers are
lower-cased so a declared header replaces a default case-insensitively; listings send them too.
(b) A 4xx that says the model is not activated / access is denied is `MODEL_NOT_AVAILABLE` instead of
AUTH / INVALID_REQUEST.

**Draft issue / PR A** (snnh/dsh-llm-service-lite):

> **Per-session routing header and secret headers on a route**
>
> OpenCode Go (`https://opencode.ai/zen/go/v1`) refuses chat requests that lack `x-opencode-session`
> ("Request is missing x-opencode-session … INVALID_REQUEST"); its docs ask clients for a stable
> per-conversation id there. The adapter receives `options.sessionId` but a route can only declare
> static headers. Proposal: `sessionHeader: "<name>"` sends a hash of route + session id (stable per
> session, not the raw id), and `headerEnv: { name: credentialRef }` resolves secret header values
> like `apiKeyEnv`. Tests: a fake server refusing requests without the header; id stable across two
> turns and different across sessions.

**Draft issue / PR B**:

> **"Model not enabled for this account" is reported as an authentication failure**
>
> DashScope 403 `Model.AccessDenied`, Zhipu `1220 您无权访问…`, SiliconFlow `30011`, Ark 404
> `ModelNotOpen` ("has not activated the model") and OpenAI's 403 "Project … does not have access to
> model …" all mean the key is fine but the account may not use this model. They classify as AUTH
> (or INVALID_REQUEST), so front ends tell the user to fix the key. Proposal: a `MODEL_NOT_AVAILABLE`
> code (not retryable) keeping the server's message. Fixtures for each provider included.

## 22: a profile's reasoning declaration replaces the family's

**What it changes.** `resolveModel` falls back to the family table per list: an empty `effort`
(which the config schema materializes for an absent list) takes the family's effort levels and default
level, and declared `thinking` modes lose to the family's effort levels. A profile therefore cannot
say "this id is a thinking switch" or "this id takes no reasoning parameter" when its name matches a
family with effort levels (a renamed gateway model, a fine-tune behind a known prefix). With the patch,
declared effort levels, thinking modes or a `thinkingStyle` state the whole control (no family default
level); a style with no levels means none. Undeclared profiles behave as before.

**Draft issue** (snnh/dsh-llm-service-lite):

> **A profile cannot override the family table's reasoning levels with fewer, other or none**
>
> For `my-gw/kimi-k3-proxy` (family `kimi-k3`: effort low/high/max, default max) a profile with
> `capabilities: { thinking: ["enabled","disabled"], thinkingStyle: "thinking" }` still offers the
> family's effort levels and sends `reasoning_effort: "max"` by default, and `effort: []` cannot turn
> the control off because the schema turns an absent list into `[]` too. Proposal: treat any declared
> level list or `thinkingStyle` as the model's whole reasoning declaration. Tests:
> `js/test/provider-reasoning.test.mjs`.

## Not upstream material

Patches 01–13 adapt dsh to Android (bionic, `/system/bin/sh`, `link(2)`, sharp WASM, SDK extensions
for the app). Some could be generalised (05's copy fallback, 10's SDK extension hook, 11's prompt
time zone), but they are out of scope for this file.
