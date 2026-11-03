// Per-conversation model (#53, ENGINE-CONTRACT §2): the app sends each conversation's model route with
// `android/session/model { sessionId, provider, model, reasoningEffort? }` before each `session/prompt`
// (patch 10 SDK extension, like `android/session/permission`) and again when the user switches the
// model of a running conversation. The route is validated by dsh's llm runtime (`resolveCallConfig`:
// a registered provider, a level the model accepts) and installed on the conversation's root Agent
// with dsh's own Agent-scoped selection (`installModelSelection`, the mechanism behind the Web host's
// `session/selectModel`): it routes the Agent's next model request (the next step, also inside a
// running turn), names the model in the system prompt, and tells the model about a provider/model
// switch. Subagents inherit the route from their parent's latest request (dsh-subagent).
// `{ sessionId, model: null }` forgets it (the Agent goes back to its creation route, `initialize`'s).
// Sessions the app never describes (plugins' background sessions) keep the route they were created with.

export const METHOD = 'android/session/model'

/** Bounded Map: drops the oldest entries past `max`. */
function remember(map, key, value, max = 500) {
  map.delete(key)
  map.set(key, value)
  while (map.size > max) map.delete(map.keys().next().value)
}

/**
 * Validate the app's payload. Returns `{ sessionId, selection }` (selection undefined = forget).
 * Throws TypeError on bad input.
 */
export function normalizeSelection(params) {
  const sessionId = params?.sessionId
  if (typeof sessionId !== 'string' || sessionId === '') throw new TypeError(`${METHOD} requires a sessionId string`)
  if (params.model === null) return { sessionId, selection: undefined }
  const { provider, model, reasoningEffort } = params
  if (typeof provider !== 'string' || provider === '') throw new TypeError(`${METHOD}: provider must be a non-empty string`)
  if (typeof model !== 'string' || model === '') throw new TypeError(`${METHOD}: model must be a non-empty string or null`)
  if (reasoningEffort !== undefined && reasoningEffort !== null && (typeof reasoningEffort !== 'string' || reasoningEffort === ''))
    throw new TypeError(`${METHOD}: reasoningEffort must be a non-empty string when given`)
  return {
    sessionId,
    selection: Object.freeze({ provider, model, ...typeof reasoningEffort === 'string' ? { reasoningEffort } : {} }),
  }
}

/** Whether [agent] is a conversation's root (subagents follow their parent's requests). */
const isRoot = agent => agent?.session?.header?.parentSession === undefined

/**
 * Serve `android/session/model` on [ctx].
 * @param ctx - plugin context (`llm`, `agents` services; `agent/created`, `sdk-extensions/resolve` events).
 * @param options.install - dsh's `installModelSelection` (tests inject a fake); loaded from
 *   `@deepseek-ai/dsh-agent` on first use otherwise.
 */
export function setupSessionModel(ctx, { install } = {}) {
  /** root session id → selection the app sent. */
  const selections = new Map()
  /** live root Agent → its installed mutable selection (`{ current, assembled }`). */
  const installed = new WeakMap()
  let installer = install
  const loadInstaller = async () => {
    installer ??= (await import('@deepseek-ai/dsh-agent')).installModelSelection
    return installer
  }

  const rootAgent = sessionId => {
    try { return ctx.get('agents')?.get?.(sessionId) ?? ctx.get('agents')?.list?.().find(a => String(a.session?.id) === sessionId) } catch { return undefined }
  }

  const applyTo = async (agent, selection) => {
    const ref = installed.get(agent)
    if (ref !== undefined) { ref.current = selection; return }
    if (selection === undefined) return
    const next = { current: selection, assembled: undefined }
    ;(await loadInstaller())(agent.ctx, next)
    installed.set(agent, next)
  }

  const methods = new Map([
    [METHOD, async params => {
      const { sessionId, selection } = normalizeSelection(params)
      if (selection === undefined) {
        selections.delete(sessionId)
      } else {
        const llm = ctx.get('llm')
        if (llm === undefined) throw new Error(`${METHOD}: the llm service is not available`)
        // Same check as `initialize` and the Web host's selectModel: a registered route, a level the model accepts.
        await llm.resolveCallConfig({
          provider: selection.provider,
          model: selection.model,
          ...selection.reasoningEffort === undefined ? {} : { reasoningEffort: selection.reasoningEffort },
        })
        remember(selections, sessionId, selection)
      }
      const agent = rootAgent(sessionId)
      if (agent !== undefined && isRoot(agent)) await applyTo(agent, selection)
      return selection === undefined ? {} : { selected: { ...selection } }
    }],
  ])
  ctx.on('sdk-extensions/resolve', method => methods.get(method))

  // A conversation the app described before its Agent existed (new, resumed, cleared): install on creation,
  // before the Agent's first request.
  ctx.on('agent/created', async ({ agent }) => {
    if (!isRoot(agent)) return
    const selection = selections.get(String(agent.session?.id))
    if (selection !== undefined) await applyTo(agent, selection)
  })

  return { selectionOf: sessionId => selections.get(sessionId) }
}
