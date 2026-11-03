// Per-conversation model (#53, plugin-guard `android/session/model`) end to end on a built stage tree: the real
// engine (profile android, main.mjs) against a scripted fake OpenAI-compatible endpoint with two models
// (`chat-a`, the `initialize` default, and `kimi-k3`, a reasoning_effort family), and a fake bridge whose
// `device_wait` tool blocks until the test releases it.
//   - a conversation sent its own model runs on it, with its level on the wire; another conversation without one
//     (a plugin's background session, in the app's terms) stays on the default;
//   - switching a running conversation reaches the turn's next model request, with dsh's model-switch notice;
//   - an unknown route or an unsupported level is refused; `model: null` goes back to the default.
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/session-model-engine.test.mjs
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const AGENT = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const NATIVE = resolve(process.env.DSH_NATIVE_DIR ?? resolve(NM, '../../host-native'))
const TMP = process.env.TMPDIR ?? join(AGENT, 'toolchains/tmp')

// ── scripted fake endpoint ───────────────────────────────────────────────────
const scripts = new Map()
const requests = []
let callSeq = 0
function sse(res, model, reply) {
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  const chunk = o => res.write(`data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model, ...o })}\n\n`)
  if (reply.text) chunk({ choices: [{ index: 0, delta: { role: 'assistant', content: reply.text }, finish_reason: null }] })
  const tools = reply.tools ?? []
  tools.forEach(([name, args], index) => chunk({ choices: [{ index: 0, delta: { tool_calls: [{ index, id: `call_${++callSeq}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }, finish_reason: null }] }))
  chunk({ choices: [{ index: 0, delta: {}, finish_reason: tools.length > 0 ? 'tool_calls' : 'stop' }] })
  chunk({ choices: [], usage: { prompt_tokens: 1000, completion_tokens: 10, total_tokens: 1010 } })
  res.end('data: [DONE]\n\n')
}
const server = createServer((req, res) => {
  let body = ''
  req.on('data', d => { body += d })
  req.on('end', () => {
    if (req.method === 'GET' && req.url === '/v1/models') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"object":"list","data":[{"id":"chat-a"},{"id":"kimi-k3"}]}'); return }
    const json = JSON.parse(body)
    const id = /\[case:([a-z0-9-]+)\]/.exec(JSON.stringify(json.messages ?? []))?.[1]
    if (id === undefined || !Array.isArray(json.tools) || json.tools.length === 0) { sse(res, json.model, { text: 'ok' }); return }
    requests.push({ id, body: json })
    sse(res, json.model, (scripts.get(id) ?? []).shift() ?? { text: 'Done.' })
  })
})

// ── engine + fake bridge ─────────────────────────────────────────────────────
function rpcPeer(readable, writable, onRequest, onNotify) {
  let buf = ''
  let id = 0
  const pending = new Map()
  readable.setEncoding('utf8')
  readable.on('data', d => {
    buf += d
    let n
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n); buf = buf.slice(n + 1)
      if (!line.trim()) continue
      let m
      try { m = JSON.parse(line) } catch { continue }
      if (m.method !== undefined && m.id !== undefined) {
        Promise.resolve(onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method !== undefined) onNotify?.(m.method, m.params)
      else {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return {
    request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }),
  }
}

const TOOLS = [
  { name: 'device_wait', group: 'apps', risk: 'read', needsUnlock: false, description: 'Wait for the device (test stand-in for a long tool call).',
    inputSchema: { type: 'object', properties: { tag: { type: 'string' } }, required: ['tag'] } },
]
const events = []
const gates = new Map()
function gate(tag) {
  let g = gates.get(tag)
  if (!g) {
    const started = Promise.withResolvers(), released = Promise.withResolvers()
    g = { started, released, release: () => released.resolve() }
    gates.set(tag, g)
  }
  return g
}
let engine
let root

before(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  root = mkdtempSync(join(TMP, 'session-model-'))
  const dshHome = join(root, 'dsh-home'); const home = join(root, 'home'); const tmp = join(root, 'tmp')
  for (const d of [join(dshHome, 'android'), home, tmp]) mkdirSync(d, { recursive: true })
  const base = `http://127.0.0.1:${server.address().port}/v1`
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), [
    `- id: llm-service-lite\n  config: ${JSON.stringify({ providers: { fake: { displayName: 'Fake', interfaceType: 'openai-chat-completions', baseURL: base, apiKeyEnv: 'DSH_ANDROID_KEY_FAKE', includeUsage: true,
      models: [{ id: 'chat-a', contextWindow: 131072, defaults: { maxTokens: 8192 } }, { id: 'kimi-k3', contextWindow: 131072, defaults: { maxTokens: 8192 } }] } } })}`,
    '- id: tool-web\n  config: {"search":false,"fetch":false,"searchTimeoutMs":60000}',
    '- id: agent-default-model\n  config: {"provider":"fake","model":"chat-a"}',
    '',
  ].join('\n'))
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_ANDROID_KEY_FAKE: 'sk-fake', DSH_BRIDGE_FD: '3',
  }
  const preload = join(NM, '@dsh-android/bundle-android/android-env.cjs')
  const child = spawn(process.execPath, ['--expose-internals', '--require', preload, join(NM, '@dsh-android/bundle-android/main.mjs')], { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  engine = { child, stderr: '' }
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { engine.stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], async (method, params) => {
    if (method === 'capability/list') return { tools: TOOLS }
    if (method === 'capability/invoke') {
      const g = gate(params.args?.tag)
      g.started.resolve()
      await g.released.promise
      return { ok: true, content: [{ type: 'text', text: `Waited ${params.args?.tag}.` }] }
    }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  engine.sdk = rpcPeer(child.stdout, child.stdin, () => ({ outcome: 'allowed-once' }), (method, params) => { if (method === 'session.event') events.push(params) })
  await engine.sdk.request('initialize', { cwd: home, provider: 'fake', model: 'chat-a', approvalRequests: true, capabilities: { userQuestions: true } })
})

after(async () => {
  for (const g of gates.values()) g.release()
  if (engine?.child.exitCode === null) await Promise.race([engine.sdk.request('shutdown', {}).catch(() => {}), sleep(5000)])
  engine?.child.kill('SIGKILL')
  server.closeAllConnections()
  server.close()
})

const until = (what, ms = 30_000) => Promise.race([what, sleep(ms).then(() => { throw new Error(`timeout\n${engine.stderr.slice(-2000)}`) })])
const prompt = (sessionId, t) => engine.sdk.request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text: t }] })
const idle = sessionId => until(engine.sdk.request('session/wait', { sessionId }), 60_000)
const sent = id => requests.filter(r => r.id === id).map(r => ({ model: r.body.model, effort: r.body.reasoning_effort }))

test('a conversation runs on its own model and level; another one stays on the default', async () => {
  const own = `sm-own-${Date.now()}`
  const other = `sm-other-${Date.now()}`
  assert.deepEqual(await engine.sdk.request('android/session/model', { sessionId: own, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'low' }),
    { selected: { provider: 'fake', model: 'kimi-k3', reasoningEffort: 'low' } })
  await prompt(own, '[case:own] 你好')
  await idle(own)
  await prompt(other, '[case:other] 你好')
  await idle(other)
  assert.deepEqual(sent('own'), [{ model: 'kimi-k3', effort: 'low' }])
  assert.deepEqual(sent('other'), [{ model: 'chat-a', effort: undefined }])
  // Its next turn too, and a level change alone: no switch notice.
  await engine.sdk.request('android/session/model', { sessionId: own, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'high' })
  await prompt(own, '[case:own] 再来')
  await idle(own)
  assert.deepEqual(sent('own').at(-1), { model: 'kimi-k3', effort: 'high' })
  assert.equal(events.filter(e => e.sessionId === own && e.event.type === 'user/message' && e.event.data.source?.kind === 'model-selection').length, 0)
})

test('switching a running conversation reaches the next model request of the same turn', async () => {
  const id = 'switch'
  const sessionId = `sm-switch-${Date.now()}`
  scripts.set(id, [{ tools: [['load_tools', { names: ['device_wait'] }]] }, { tools: [['device_wait', { tag: 'w1' }]] }, { text: '好了。' }])
  await engine.sdk.request('android/session/permission', { sessionId, preset: 'danger-full-access' })
  await engine.sdk.request('android/session/model', { sessionId, provider: 'fake', model: 'chat-a' })
  await prompt(sessionId, `[case:${id}] 等一下设备。`)
  await until(gate('w1').started.promise)
  await engine.sdk.request('android/session/model', { sessionId, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'max' })
  gate('w1').release()
  await idle(sessionId)
  assert.deepEqual(sent(id), [{ model: 'chat-a', effort: undefined }, { model: 'chat-a', effort: undefined }, { model: 'kimi-k3', effort: 'max' }])
  const evs = events.filter(e => e.sessionId === sessionId).map(e => e.event)
  assert.equal(evs.filter(e => e.type === 'turn/start').length, 1, 'one turn')
  const notice = evs.find(e => e.type === 'user/message' && e.data.source?.kind === 'model-selection')
  assert.ok(notice, 'dsh logs its model-switch notice')
  assert.match(notice.data.source.summary, /chat-a → kimi-k3/)
})

test('an unknown route or an unsupported level is refused; null goes back to the default', async () => {
  const sessionId = `sm-refused-${Date.now()}`
  await assert.rejects(engine.sdk.request('android/session/model', { sessionId, provider: 'gone', model: 'x' }))
  await assert.rejects(engine.sdk.request('android/session/model', { sessionId, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'xhigh' }), /reasoning effort/)
  await engine.sdk.request('android/session/model', { sessionId, provider: 'fake', model: 'kimi-k3', reasoningEffort: 'low' })
  await engine.sdk.request('android/session/model', { sessionId, model: null })
  await prompt(sessionId, '[case:refused] 你好')
  await idle(sessionId)
  assert.deepEqual(sent('refused'), [{ model: 'chat-a', effort: undefined }])
})
