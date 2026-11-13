// plugin-guard: per-plugin / MCP-server "ask before call" (androidExtensions.askBeforeCall)
// goes through the same approval path as built-in mutating tools.
//   node --test js/test/guard-ask.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { apply } from '../packages/plugin-guard/index.js'

function fakeContext(services) {
  const listeners = new Map()
  const ctx = {
    on: (event, fn) => { listeners.set(event, [...listeners.get(event) ?? [], fn]); return () => {} },
    get: name => services[name],
    inject: () => {},
    effect: () => {},
    emit: () => {},
    tools: { guard: () => () => {} },
    logger: undefined,
  }
  return { ctx, listeners }
}

test('ask-before-call turns an allowed plugin tool call into an approval request', async () => {
  process.env.DSH_HOME = mkdtempSync(join(tmpdir(), 'guard-ask-'))
  const requests = []
  const agent = { id: 'a1', session: { id: 's1', header: {} } }
  const services = {
    androidExtensions: { askBeforeCall: name => name === 'hello_world' || name.startsWith('mcp__gh__') },
    approval: { request: async r => { requests.push(r.toolName); return 'rejected' } },
    agents: { list: () => [agent] },
  }
  const { ctx, listeners } = fakeContext(services)
  apply(ctx, { ledgerPath: join(process.env.DSH_HOME, 'budget.json') })
  const pre = listeners.get('tools/pre-execute')[0]
  const allow = async () => ({ kind: 'allow' })
  const run = name => pre({ name, arguments: {}, agent, callId: 'c' }, allow)
  assert.equal((await run('hello_world')).kind, 'deny')
  assert.equal((await run('mcp__gh__create_issue')).kind, 'deny')
  assert.deepEqual((await run('other_plugin_tool')), { kind: 'allow' })
  assert.deepEqual(requests, ['hello_world', 'mcp__gh__create_issue'])
})

test('bash gets the same hardline floor: blocked before approval and in the guard backstop', async () => {
  process.env.DSH_HOME = mkdtempSync(join(tmpdir(), 'guard-bash-'))
  const agent = { id: 'a1', session: { id: 's1', header: {} } }
  const guards = []
  const { ctx, listeners } = fakeContext({ agents: { list: () => [agent] } })
  ctx.tools.guard = fn => { guards.push(fn); return () => {} }
  apply(ctx, { ledgerPath: join(process.env.DSH_HOME, 'budget.json') })
  const pre = listeners.get('tools/pre-execute')[0]
  const ask = async () => ({ kind: 'ask', reason: 'mutating tool' })
  const run = command => pre({ name: 'bash', arguments: { command }, agent, callId: 'c' }, ask)
  for (const blocked of ['reboot', 'pm uninstall com.android.settings', 'rm -rf /sdcard', 'cd /sdcard && rm -rf *', 'settings delete global adb_enabled']) {
    const d = await run(blocked)
    assert.equal(d.kind, 'deny', blocked)
    assert.equal(d.info?.code, 'HARDLINE_BLOCKED', blocked)
    assert.match(guards[0]({ name: 'bash', arguments: { command: blocked } }) ?? '', /HARDLINE_BLOCKED/, blocked)
  }
  // Ordinary commands are not blocked: they reach the tool's approval (no approval service here → its ask comes back).
  for (const ok of ['dumpsys battery', 'pm list packages -3', 'ls /sdcard']) {
    assert.notEqual((await run(ok)).kind, 'deny', ok)
    assert.equal(guards[0]({ name: 'bash', arguments: { command: ok } }), undefined, ok)
  }
})
