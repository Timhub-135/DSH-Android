// plugin-guard session-model.js (#53): the app's per-conversation model route on a fake context.
//   node --test js/test/session-model.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { METHOD, normalizeSelection, setupSessionModel } from '../packages/plugin-guard/session-model.js'

function harness({ efforts = { 'deepseek-official/deepseek-flash': ['off', 'low', 'high', 'max'], 'kimi/kimi-k3': ['low', 'high', 'max'] } } = {}) {
  const handlers = new Map()
  const live = new Map()
  const installs = []
  const resolved = []
  const ctx = {
    on: (event, fn) => { handlers.set(event, [...handlers.get(event) ?? [], fn]); return () => {} },
    get(name) {
      if (name === 'agents') return { get: id => live.get(id), list: () => [...live.values()] }
      if (name === 'llm') return {
        async resolveCallConfig(config) {
          resolved.push(config)
          const levels = efforts[`${config.provider}/${config.model}`]
          if (levels === undefined) throw new Error(`no adapter registered for provider "${config.provider}"`)
          if (config.reasoningEffort !== undefined && !levels.includes(config.reasoningEffort))
            throw Object.assign(new Error(`does not support reasoning effort "${config.reasoningEffort}"`), { code: 'UNSUPPORTED_REASONING_EFFORT' })
          return config
        },
      }
      return undefined
    },
  }
  const install = (agentCtx, selection) => { installs.push({ agentCtx, selection }) }
  setupSessionModel(ctx, { install })
  const method = name => handlers.get('sdk-extensions/resolve').map(h => h(name)).find(Boolean)
  const created = async agent => { live.set(String(agent.session.id), agent); for (const h of handlers.get('agent/created') ?? []) await h({ agent }) }
  const agent = (id, parent) => ({ id, ctx: { id: `ctx-${id}` }, session: { id, header: parent === undefined ? {} : { parentSession: parent } } })
  return { method, created, agent, installs, resolved, live }
}

test('payloads are validated', () => {
  assert.deepEqual(normalizeSelection({ sessionId: 's', provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'max' }).selection,
    { provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'max' })
  assert.deepEqual(normalizeSelection({ sessionId: 's', provider: 'kimi', model: 'kimi-k3' }).selection, { provider: 'kimi', model: 'kimi-k3' })
  assert.deepEqual(normalizeSelection({ sessionId: 's', provider: 'kimi', model: 'kimi-k3', reasoningEffort: null }).selection, { provider: 'kimi', model: 'kimi-k3' })
  assert.equal(normalizeSelection({ sessionId: 's', model: null }).selection, undefined)
  assert.throws(() => normalizeSelection({ provider: 'kimi', model: 'kimi-k3' }), TypeError)
  assert.throws(() => normalizeSelection({ sessionId: 's', provider: '', model: 'kimi-k3' }), TypeError)
  assert.throws(() => normalizeSelection({ sessionId: 's', provider: 'kimi' }), TypeError)
  assert.throws(() => normalizeSelection({ sessionId: 's', provider: 'kimi', model: 'kimi-k3', reasoningEffort: '' }), TypeError)
})

test('a route sent before the conversation exists is installed when its root agent is created', async () => {
  const h = harness()
  const result = await h.method(METHOD)({ sessionId: 's1', provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'low' })
  assert.deepEqual(result, { selected: { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'low' } })
  assert.equal(h.installs.length, 0)
  const root = h.agent('s1')
  await h.created(root)
  assert.equal(h.installs.length, 1)
  assert.equal(h.installs[0].agentCtx, root.ctx)
  assert.deepEqual(h.installs[0].selection.current, { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'low' })
  // Subagents follow their parent's requests: nothing is installed on them.
  await h.created(h.agent('s1/child', 's1'))
  assert.equal(h.installs.length, 1)
  // Another conversation (a plugin's background session) is left alone.
  await h.created(h.agent('bg'))
  assert.equal(h.installs.length, 1)
})

test('switching a live conversation updates its installed selection in place; null forgets it', async () => {
  const h = harness()
  const root = h.agent('s1')
  await h.created(root)
  await h.method(METHOD)({ sessionId: 's1', provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'max' })
  assert.equal(h.installs.length, 1)
  const ref = h.installs[0].selection
  await h.method(METHOD)({ sessionId: 's1', provider: 'kimi', model: 'kimi-k3' })
  assert.equal(h.installs.length, 1, 'installed once per agent')
  assert.deepEqual(ref.current, { provider: 'kimi', model: 'kimi-k3' })
  await h.method(METHOD)({ sessionId: 's1', model: null })
  assert.equal(ref.current, undefined)
  // A resumed agent (new process generation) gets nothing once forgotten.
  await h.created(h.agent('s1'))
  assert.equal(h.installs.length, 1)
})

test('an unknown route or an unsupported level is refused and changes nothing', async () => {
  const h = harness()
  const root = h.agent('s1')
  await h.created(root)
  await h.method(METHOD)({ sessionId: 's1', provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'high' })
  const ref = h.installs[0].selection
  await assert.rejects(h.method(METHOD)({ sessionId: 's1', provider: 'gone', model: 'm' }), /no adapter/)
  await assert.rejects(h.method(METHOD)({ sessionId: 's1', provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'xhigh' }), /does not support/)
  assert.deepEqual(ref.current, { provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'high' })
  assert.deepEqual(h.resolved.at(-1), { provider: 'kimi', model: 'kimi-k3', reasoningEffort: 'xhigh' })
})
