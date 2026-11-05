// #57: a conversation the engine opened in an earlier process, and the app never resumed, is refused by
// `session/prompt` with dsh's `session "<id>" already exists` (the app sends a session it has no logged events
// for straight to `session/prompt`). The app (SessionStore.prompt, SessionOpen.alreadyExists) answers that
// error with `session/resume` and the prompt again; this pins the engine side of that on a built stage tree:
// the exact message, and that resume + prompt then continues the stored conversation.
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/session-open-engine.test.mjs
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

// Fake OpenAI-compatible endpoint (a custom provider, as in the report); records each chat's user messages.
const chats = []
const server = createServer((req, res) => {
  let body = ''
  req.on('data', d => { body += d })
  req.on('end', () => {
    if (req.method === 'GET') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"object":"list","data":[{"id":"flash"}]}'); return }
    const json = JSON.parse(body)
    if (Array.isArray(json.tools) && json.tools.length > 0) chats.push(JSON.stringify(json.messages ?? []))
    res.writeHead(200, { 'content-type': 'text/event-stream' })
    const chunk = o => res.write(`data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: json.model, ...o })}\n\n`)
    chunk({ choices: [{ index: 0, delta: { role: 'assistant', content: 'ok' }, finish_reason: null }] })
    chunk({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })
    chunk({ choices: [], usage: { prompt_tokens: 10, completion_tokens: 1, total_tokens: 11 } })
    res.end('data: [DONE]\n\n')
  })
})

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
      if (!line.trim()) continue
      let m
      try { m = JSON.parse(line) } catch { continue }
      if (m.method !== undefined && m.id !== undefined) {
        Promise.resolve(onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method === undefined) {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return {
    request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }),
  }
}

let root
const engines = []

/** One engine process on the shared DSH_HOME (an app engine generation). */
async function boot() {
  const dshHome = join(root, 'dsh-home'); const home = join(root, 'home'); const tmp = join(root, 'tmp')
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_ANDROID_KEY_CUSTOM: 'sk-fake', DSH_BRIDGE_FD: '3',
  }
  const preload = join(NM, '@dsh-android/bundle-android/android-env.cjs')
  const child = spawn(process.execPath, ['--expose-internals', '--require', preload, join(NM, '@dsh-android/bundle-android/main.mjs')], { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  const engine = { child, stderr: '' }
  engines.push(engine)
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { engine.stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], async method => {
    if (method === 'capability/list') return { tools: [] }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  engine.sdk = rpcPeer(child.stdout, child.stdin, () => ({ outcome: 'allowed-once' }))
  await engine.sdk.request('initialize', { cwd: home, provider: 'custom', model: 'flash', approvalRequests: true, capabilities: { userQuestions: true } })
  engine.stop = async () => {
    await Promise.race([engine.sdk.request('shutdown', {}).catch(() => {}), sleep(5000, undefined, { ref: false })])
    child.kill('SIGKILL')
  }
  return engine
}

before(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  root = mkdtempSync(join(TMP, 'session-open-'))
  const dshHome = join(root, 'dsh-home')
  for (const d of [join(dshHome, 'android'), join(root, 'home'), join(root, 'tmp')]) mkdirSync(d, { recursive: true })
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), [
    `- id: llm-service-lite\n  config: ${JSON.stringify({ providers: { custom: { displayName: 'custom', interfaceType: 'openai-chat-completions',
      baseURL: `http://127.0.0.1:${server.address().port}/v1`, apiKeyEnv: 'DSH_ANDROID_KEY_CUSTOM', includeUsage: true,
      models: [{ id: 'flash', contextWindow: 131072, defaults: { maxTokens: 8192 } }] } } })}`,
    '- id: tool-web\n  config: {"search":false,"fetch":false,"searchTimeoutMs":60000}',
    '',
  ].join('\n'))
})

after(async () => {
  for (const e of engines) if (e.child.exitCode === null) await e.stop()
  server.closeAllConnections()
  server.close()
})

// What SessionStore.prompt sends for a conversation before its prompt (the route the app sends per conversation, #53).
const describe = (engine, sessionId) => engine.sdk.request('android/session/model', { sessionId, provider: 'custom', model: 'flash' })
const prompt = (engine, sessionId, text) => engine.sdk.request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text }] })
const idle = (engine, sessionId) => Promise.race([engine.sdk.request('session/wait', { sessionId }), sleep(60_000, undefined, { ref: false }).then(() => { throw new Error(`timeout\n${engine.stderr.slice(-2000)}`) })])

test('a session opened by an earlier engine: prompt is refused as already existing, resume + prompt continues it', async () => {
  const sessionId = crypto.randomUUID()
  const first = await boot()
  await describe(first, sessionId)
  await prompt(first, sessionId, '第一条 [one]')
  await idle(first, sessionId)
  await first.stop()

  // The next engine generation; the app did not resume the session (it logged none of its events).
  const second = await boot()
  await describe(second, sessionId)
  const refused = await prompt(second, sessionId, '第二条 [two]').then(() => undefined, e => e)
  assert.ok(refused, 'session/prompt must refuse a stored session that is not open')
  // SessionOpen.alreadyExists matches exactly this.
  assert.match(refused.message, /^session "[^"]+" already exists$/)
  assert.equal(refused.message, `session "${sessionId}" already exists`)

  // The app's answer: resume, describe again, prompt again. The conversation continues with its history.
  await second.sdk.request('session/resume', { sessionId })
  await describe(second, sessionId)
  await prompt(second, sessionId, '第二条 [two]')
  await idle(second, sessionId)
  const last = chats.at(-1)
  assert.ok(last.includes('[two]') && last.includes('[one]'), 'the resumed conversation keeps its first message')
})
