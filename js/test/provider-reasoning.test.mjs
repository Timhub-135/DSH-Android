// Reasoning levels for third-party models (#46) end to end: the real OwcProfilesAdapter (with patch 22)
// against a fake chat-completions endpoint that records every request body. Checks that the level the
// app picks reaches the wire in the family's spelling (`reasoning_effort` for a Kimi K3 route,
// `enable_thinking` for a Qwen 3.5+ route), that a manual declaration in the profile (what the app's
// 推理控制 writes) replaces the family table's (levels, switch style, or no control at all), that
// sending no level adds nothing beyond the family's documented default, and what plugin-guard's
// `android/models/reasoning` reports for each route. The last test boots the real engine (profile
// android, main.mjs) with the app's patch layer and goes through the SDK: the levels before
// `initialize`, a level on `initialize { reasoningEffort }` reaching the model request, an unsupported
// level refused. Runs against a built stage tree:
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-reasoning.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const NATIVE = resolve(process.env.DSH_NATIVE_DIR ?? resolve(NM, '../../host-native'))
const TMP = process.env.TMPDIR ?? join(AGENT, 'toolchains/tmp')
const { OwcProfilesAdapter, ConcurrencyLimiter, resolveProfiles } = await import(pathToFileURL(join(NM, '@snnh/dsh-llm-service-lite/lib/index.js')).href)
const { reasoningLevels } = await import(pathToFileURL(join(NM, '@dsh-android/plugin-guard/index.js')).href)

const USER = { role: 'user', content: [{ type: 'text', text: 'hi' }] }
/** Request fields that carry a reasoning choice on the chat-completions wire. */
const REASONING_FIELDS = ['reasoning_effort', 'enable_thinking', 'thinking', 'reasoning']

/** A chat endpoint that answers one word and records each request body. */
async function endpoint() {
  const bodies = []
  const server = createServer((req, res) => {
    let raw = ''
    req.on('data', c => { raw += c })
    req.on('end', () => {
      bodies.push(JSON.parse(raw))
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      res.end('data: {"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
    })
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  return { bodies, base: `http://127.0.0.1:${server.address().port}/v1`, close: () => { server.closeAllConnections(); return new Promise(r => server.close(r)) } }
}

/** One route `p` with [models] (profile model entries, as the app's patch layer writes them). */
function adapterFor(base, models) {
  const profiles = resolveProfiles({ p: { interfaceType: 'openai-chat-completions', baseURL: base, apiKey: 'sk-test', models } })
  const limiter = new ConcurrencyLimiter(4)
  return new OwcProfilesAdapter({ profiles: () => profiles, limiter: () => limiter, resolveApiKey: async p => p.apiKey })
}

/** dsh's llm service as far as `android/models/reasoning` uses it. */
const llmOf = adapter => ({ resolveModelInfo: (provider, model) => adapter.resolveModel(provider, model) })

/** One turn on [model] with [reasoningEffort] (undefined = the app sends none); the request's reasoning fields. */
async function sent(srv, adapter, model, reasoningEffort) {
  for await (const _ of adapter.stream({ provider: 'p', model, messages: [USER], ...(reasoningEffort === undefined ? {} : { reasoningEffort }) })) { /* drain */ }
  const body = srv.bodies.at(-1)
  return Object.fromEntries(REASONING_FIELDS.filter(f => f in body).map(f => [f, body[f]]))
}

const MODELS = [
  { id: 'kimi-k3' },                                   // reasoning_effort family (low/high/max, default max)
  { id: 'qwen3.6-plus' },                              // enable_thinking family (enabled/disabled, no default)
  { id: 'my-org/effort-model', capabilities: { effort: ['low', 'high'], thinking: [], thinkingStyle: 'effort_only' } },
  { id: 'my-org/switch-model', capabilities: { effort: [], thinking: ['enabled', 'disabled'], thinkingStyle: 'enable_thinking' } },
  { id: 'my-org/type-model', capabilities: { effort: [], thinking: ['enabled', 'disabled'], thinkingStyle: 'thinking' } },
  { id: 'kimi-k3-none', capabilities: { effort: [], thinking: [], thinkingStyle: 'effort_only' } }, // a known family declared as "no control"
  { id: 'kimi-k3-listed', capabilities: { modalities: ['text'], effort: [], thinking: [] } }, // undeclared, as the config schema materializes it
  { id: 'my-org/plain' },                              // unknown id, nothing declared
]

test('reported levels: family tables, manual declarations, none', async () => {
  const { models } = await reasoningLevels(llmOf(adapterFor('http://127.0.0.1:9/v1', MODELS)), MODELS.map(m => ({ provider: 'p', model: m.id })))
  const by = Object.fromEntries(models.map(m => [m.model, m]))
  assert.deepEqual(by['kimi-k3'], { provider: 'p', model: 'kimi-k3', efforts: [{ id: 'low', name: 'Low' }, { id: 'high', name: 'High' }, { id: 'max', name: 'Max' }], defaultEffort: 'max' })
  assert.deepEqual(by['qwen3.6-plus'].efforts.map(e => e.id), ['enabled', 'disabled'])
  assert.equal(by['qwen3.6-plus'].defaultEffort, undefined)
  assert.deepEqual(by['my-org/effort-model'].efforts.map(e => e.id), ['low', 'high'])
  assert.equal(by['my-org/effort-model'].defaultEffort, undefined, 'a declaration brings no default level')
  assert.deepEqual(by['my-org/switch-model'].efforts, [{ id: 'enabled', name: 'Enabled' }, { id: 'disabled', name: 'Disabled' }])
  assert.deepEqual(by['kimi-k3-none'].efforts, [], 'a style with no levels: no control, the family is not consulted')
  assert.equal(by['kimi-k3-none'].defaultEffort, undefined)
  assert.deepEqual(by['my-org/plain'].efforts, [])
  assert.deepEqual(by['kimi-k3-listed'], { ...by['kimi-k3'], model: 'kimi-k3-listed' }, 'empty lists alone inherit the family')
})

test('reasoning_effort family: the chosen level is sent; no level sends the family default only', async () => {
  const srv = await endpoint()
  try {
    const a = adapterFor(srv.base, MODELS)
    assert.deepEqual(await sent(srv, a, 'kimi-k3', 'low'), { reasoning_effort: 'low' })
    assert.deepEqual(await sent(srv, a, 'kimi-k3', 'high'), { reasoning_effort: 'high' })
    assert.deepEqual(await sent(srv, a, 'kimi-k3'), { reasoning_effort: 'max' }, 'unchanged from before #46: the documented default')
  } finally { await srv.close() }
})

test('enable_thinking family: on / off as enable_thinking; no level sends nothing', async () => {
  const srv = await endpoint()
  try {
    const a = adapterFor(srv.base, MODELS)
    assert.deepEqual(await sent(srv, a, 'qwen3.6-plus', 'disabled'), { enable_thinking: false })
    assert.deepEqual(await sent(srv, a, 'qwen3.6-plus', 'enabled'), { enable_thinking: true })
    assert.deepEqual(await sent(srv, a, 'qwen3.6-plus'), {})
  } finally { await srv.close() }
})

test('manual declarations: the declared spelling; no level sends nothing', async () => {
  const srv = await endpoint()
  try {
    const a = adapterFor(srv.base, MODELS)
    assert.deepEqual(await sent(srv, a, 'my-org/effort-model', 'high'), { reasoning_effort: 'high' })
    assert.deepEqual(await sent(srv, a, 'my-org/effort-model'), {})
    assert.deepEqual(await sent(srv, a, 'my-org/switch-model', 'disabled'), { enable_thinking: false })
    assert.deepEqual(await sent(srv, a, 'my-org/switch-model'), {})
    assert.deepEqual(await sent(srv, a, 'my-org/type-model', 'enabled'), { thinking: { type: 'enabled' } })
    assert.deepEqual(await sent(srv, a, 'my-org/type-model', 'disabled'), { thinking: { type: 'disabled' } })
    // Declared "no control" on a family with a default level: the default is not sent either.
    assert.deepEqual(await sent(srv, a, 'kimi-k3-none'), {})
    assert.deepEqual(await sent(srv, a, 'my-org/plain'), {})
  } finally { await srv.close() }
})

test('android/models/reasoning: a route that cannot resolve answers its error; bad params are refused', async () => {
  const llm = { resolveModelInfo: async (provider, model) => { if (provider === 'gone') throw new Error(`no adapter registered for provider "${provider}"`); return { provider, id: model, name: model } } }
  const { models } = await reasoningLevels(llm, [{ provider: 'gone', model: 'x' }, { provider: 'q', model: 'y' }])
  assert.match(models[0].error, /no adapter registered/)
  assert.deepEqual(models[1], { provider: 'q', model: 'y', efforts: [] })
  await assert.rejects(reasoningLevels(llm, [{ provider: '', model: 'x' }]), TypeError)
  await assert.rejects(reasoningLevels(llm, undefined), TypeError)
})

// ── the real engine, through the SDK ─────────────────────────────────────────
/** Newline-delimited JSON-RPC over a stream pair (client side; server requests get [onRequest]'s answer). */
function rpcPeer(readable, writable, onRequest) {
  let buf = ''
  let id = 0
  const pending = new Map()
  readable.setEncoding('utf8')
  readable.on('data', d => {
    buf += d
    let n
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n); buf = buf.slice(n + 1)
      let m
      try { m = JSON.parse(line) } catch { continue }
      if (m.method !== undefined && m.id !== undefined) {
        Promise.resolve().then(() => onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method === undefined) {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return { request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }) }
}

test('engine: android/models/reasoning before initialize, the chosen level on the wire, an unsupported level refused', { timeout: 180_000 }, async () => {
  const srv = await endpoint()
  const root = mkdtempSync(join(TMP, 'reasoning-'))
  const dshHome = join(root, 'dsh-home'), home = join(root, 'home'), tmp = join(root, 'tmp')
  for (const d of [join(dshHome, 'android'), home, tmp]) mkdirSync(d, { recursive: true })
  // What Providers.kt renderProfilePatch writes: modalities always, a reasoning declaration only when the user made one.
  const models = [
    { id: 'kimi-k3', capabilities: { modalities: ['text'] } },
    { id: 'qwen3.6-plus', capabilities: { modalities: ['text'] } },
    { id: 'my-org/switch-model', capabilities: { modalities: ['text'], effort: [], thinking: ['enabled', 'disabled'], thinkingStyle: 'thinking' } },
    { id: 'kimi-k3-gateway', capabilities: { modalities: ['text'], effort: [], thinking: [], thinkingStyle: 'effort_only' } },
  ].map(m => ({ ...m, contextWindow: 131072, maxTokens: 8192, defaults: { maxTokens: 8192 } }))
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), [
    `- id: llm-service-lite\n  config: ${JSON.stringify({ providers: { fake: { displayName: 'Fake', interfaceType: 'openai-chat-completions', baseURL: srv.base, apiKeyEnv: 'DSH_ANDROID_KEY_FAKE', includeUsage: true, models } } })}`,
    '- id: tool-web\n  config: {"search":false,"fetch":false,"searchTimeoutMs":60000}',
    '',
  ].join('\n'))
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_ANDROID_KEY_FAKE: 'sk-fake', DSH_BRIDGE_FD: '3',
  }
  const child = spawn(process.execPath, ['--expose-internals', '--require', join(NM, '@dsh-android/bundle-android/android-env.cjs'), join(NM, '@dsh-android/bundle-android/main.mjs')],
    { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  let stderr = ''
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], method => {
    if (method === 'capability/list') return { tools: [] }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  const sdk = rpcPeer(child.stdout, child.stdin, () => ({ outcome: 'allowed-once' }))
  const until = (what, ms = 60_000) => Promise.race([what, sleep(ms, undefined, { ref: false }).then(() => { throw new Error(`timeout\n${stderr.slice(-2000)}`) })])
  const turn = async (model, reasoningEffort) => {
    await until(sdk.request('initialize', { cwd: home, provider: 'fake', model, ...(reasoningEffort === undefined ? {} : { reasoningEffort }), approvalRequests: true }))
    const sessionId = `r-${model.replace(/\W/g, '')}-${reasoningEffort}-${Date.now()}`
    const before = srv.bodies.length
    await until(sdk.request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: 'hi' }] }))
    await until(sdk.request('session/wait', { sessionId }))
    const body = srv.bodies.slice(before).find(b => Array.isArray(b.tools) && b.tools.length > 0) ?? srv.bodies.at(-1)
    return Object.fromEntries(REASONING_FIELDS.filter(f => f in body).map(f => [f, body[f]]))
  }
  try {
    // The app asks right after boot, before `initialize` (EngineManager.refreshReasoning).
    const { models: levels } = await until(sdk.request('android/models/reasoning', { models: models.map(m => ({ provider: 'fake', model: m.id })).concat({ provider: 'gone', model: 'x' }) }))
    assert.deepEqual(levels.map(l => [l.model, (l.efforts ?? []).map(e => e.id).join(','), l.defaultEffort ?? null]),
      [['kimi-k3', 'low,high,max', 'max'], ['qwen3.6-plus', 'enabled,disabled', null], ['my-org/switch-model', 'enabled,disabled', null], ['kimi-k3-gateway', '', null], ['x', '', null]])
    assert.ok(levels[4].error, 'an unregistered route answers an error')
    assert.deepEqual(await turn('qwen3.6-plus', 'disabled'), { enable_thinking: false })
    assert.deepEqual(await turn('qwen3.6-plus'), {}, 'default: nothing extra')
    assert.deepEqual(await turn('kimi-k3', 'low'), { reasoning_effort: 'low' })
    assert.deepEqual(await turn('kimi-k3'), { reasoning_effort: 'max' }, 'default: the family default, as before #46')
    assert.deepEqual(await turn('my-org/switch-model', 'disabled'), { thinking: { type: 'disabled' } })
    assert.deepEqual(await turn('kimi-k3-gateway'), {}, 'declared no control: not even the family default')
    // dsh refuses a level the model does not offer (why the app never sends one).
    await assert.rejects(sdk.request('initialize', { cwd: home, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'disabled' }), /does not support reasoning effort/)
  } finally {
    await Promise.race([sdk.request('shutdown', {}).catch(() => {}), sleep(5000)])
    child.kill('SIGKILL')
    for (const s of child.stdio) s?.destroy()
    await srv.close()
  }
})
