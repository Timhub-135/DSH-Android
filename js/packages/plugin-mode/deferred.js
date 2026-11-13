// Deferred tools (issue #36): load tool definitions on demand.
//
// Every tool definition is input on every request, and the phone offers ~70 device tools: a bare
// "Hi" in 默认 cost ~19k input tokens, ~90 % of them tool schemas. Like Claude Code's ToolSearch or
// OpenAI's tool search, only a small core is loaded up front; the rest (device tools, third-party
// plugin / MCP tools, rarely used built-ins) is listed in a compact catalog section (one line per
// group) and the `load_tools` tool activates definitions for the rest of the conversation.
//
// Prompt caching: the catalog lists every deferred tool whether loaded or not and the guidance
// sections of deferrable tools are always dropped, so loading changes neither the system prompt
// nor the tools that were already offered. Loading only ADDS tools; dsh logs the addition and, on
// routes with mid-conversation tool changes (official DeepSeek, `toolUpdate: addition-only`),
// sends the new definitions after the cached prefix as a `tool_addition`. Other routes resend the
// tool list once per load. Tools are never unloaded within a conversation.
//
// What a conversation has loaded is per root session (subagents share it) and survives a resume:
// it is the deferrable tools the logged request header already offered, plus the
// `plugin:android-mode/tools-loaded` records the loader writes (ignorable, never model-visible).

export const LOADER = 'load_tools'
export const LOADED_RECORD = 'plugin:android-mode/tools-loaded'
/** After the mode section (150). */
export const CATALOG_ORDER = 160
export const CATALOG_SECTION = 'android:deferred-tools'

/** Built-in groups that are deferred; everything else built in stays loaded (unknown future tools too). */
const BUILTIN_GROUPS = Object.freeze({
  jobs: {
    summary: 'background jobs of bash run_in_background; you are notified when one finishes, do not poll',
    test: n => n.startsWith('job_'),
  },
  subagents: {
    summary: 'delegate work to subagents; start independent ones together in one message',
    test: n => ['subagent', 'subagent_fork', 'list_subagent_models', 'send_message', 'interrupt_agent', 'list_agents'].includes(n),
  },
  session: {
    summary: 'working directory, plan mode',
    test: n => n === 'working_directory' || n === 'exit_plan_mode',
  },
})

/** Device tool groups (app ToolGroup ids) → catalog summary. */
const DEVICE_SUMMARY = Object.freeze({
  files: 'shared storage /sdcard',
  apps: 'list, launch, links, intents, sharing, per-app guides and notes',
  screen: 'operate apps on screen; needs the phone unlocked',
  system: 'settings, toggles, volume, alarms, location',
  notifications: 'read, open, reply, dismiss',
  comms: 'contacts, SMS, call log, calendar',
  media: 'playback control',
  clipboard: 'read, write',
  browser: 'in-app web browser',
  rules: 'scheduled and triggered automations',
})
const DEVICE_ORDER = Object.keys(DEVICE_SUMMARY)

/** Fewer deferrable tools than this are simply loaded: the catalog and loader would cost more. */
export const MIN_DEFERRED = 3

const EXTENSIONS_GROUP = 'extensions'
const MCP_PREFIX = 'mcp__'
const EXTENSION_HINT_MAX = 80

/**
 * The catalog group of a deferrable tool, or undefined for a tool that is always loaded.
 * [descriptorOf] returns a device tool's descriptor; [isExtension] says whether a tool belongs to
 * a third-party plugin.
 */
export function deferredGroup(toolName, descriptorOf = () => undefined, isExtension = () => false) {
  if (toolName === LOADER) return undefined
  const device = descriptorOf(toolName)
  if (device !== undefined) return typeof device.group === 'string' && device.group !== '' ? device.group : 'system'
  for (const [id, g] of Object.entries(BUILTIN_GROUPS)) if (g.test(toolName)) return id
  if (toolName.startsWith(MCP_PREFIX) || isExtension(toolName)) return EXTENSIONS_GROUP
  return undefined
}

/** Whether [toolName] is a deferred built-in (its dsh guidance section is dropped: see the header). */
export const isDeferredBuiltin = toolName => Object.values(BUILTIN_GROUPS).some(g => g.test(toolName))

/** The first sentence of a tool description, bounded: the catalog hint for third-party tools. */
export function shortHint(description) {
  const text = String(description ?? '').replace(/\s+/g, ' ').trim()
  const first = /^(.+?[.!?。])(\s|$)/.exec(text)?.[1] ?? text
  return first.length <= EXTENSION_HINT_MAX ? first : `${first.slice(0, EXTENSION_HINT_MAX - 1)}…`
}

const groupRank = id => {
  const device = DEVICE_ORDER.indexOf(id)
  if (device >= 0) return device
  if (id === EXTENSIONS_GROUP) return 200
  if (Object.hasOwn(BUILTIN_GROUPS, id)) return 100 + Object.keys(BUILTIN_GROUPS).indexOf(id)
  return 150
}

/**
 * Groups the deferrable tools: `[{ id, tools: [name…] }]` in a stable order (device groups first in
 * app order, then built-in groups, then extensions; tools by name). Empty below [MIN_DEFERRED].
 */
export function catalogGroups(names, descriptorOf = () => undefined, isExtension = () => false) {
  const groups = new Map()
  for (const n of names) {
    const id = deferredGroup(n, descriptorOf, isExtension)
    if (id === undefined) continue
    if (!groups.has(id)) groups.set(id, [])
    groups.get(id).push(n)
  }
  if ([...groups.values()].reduce((n, tools) => n + tools.length, 0) < MIN_DEFERRED) return []
  return [...groups].map(([id, tools]) => ({ id, tools: tools.sort() }))
    .sort((a, b) => groupRank(a.id) - groupRank(b.id) || a.id.localeCompare(b.id))
}

/**
 * The catalog section: one line per group. [describe] returns a tool's description (extension
 * tools get a short hint each, since their names say little). Empty when nothing is deferred.
 */
export function catalogSection(groups, describe = () => undefined) {
  if (groups.length === 0) return ''
  const lines = groups.map(({ id, tools }) => {
    const summary = DEVICE_SUMMARY[id] ?? BUILTIN_GROUPS[id]?.summary
    if (id === EXTENSIONS_GROUP) return `- ${id} (third-party plugins and MCP servers): ${tools.map(n => { const h = shortHint(describe(n)); return h ? `${n} (${h})` : n }).join('; ')}`
    return `- ${id}${summary ? ` (${summary})` : ''}: ${tools.join(', ')}`
  })
  return [
    '# Tools loaded on demand',
    `These tools exist but their definitions are not loaded yet. Before calling one, call ${LOADER} with the tool names or group names you need ` +
      '(several at once is fine); they then stay available for the rest of the conversation. Tools named elsewhere in these instructions may be in this list.',
    ...lines,
  ].join('\n')
}

/** The loader's model-facing definition (stable text: it is part of every request). */
export function loaderDefinition(execute) {
  return {
    name: LOADER,
    description: 'Load the definitions of tools listed under "Tools loaded on demand" so you can call them from your next step on. ' +
      'Pass tool names and/or group names; already loaded or unknown names are reported back.',
    parameters: {
      type: 'object',
      properties: {
        names: { type: 'array', items: { type: 'string' }, description: 'Tool names (e.g. "screen_tap") or group names (e.g. "screen").' },
      },
      required: ['names'],
      additionalProperties: false,
    },
    isConcurrencySafe: () => true,
    output: {
      schema: {},
      render: (_args, value) => Array.isArray(value?.content) ? value.content : [{ type: 'text', text: String(value ?? '') }],
    },
    execute,
  }
}

/**
 * Resolves a load request against the catalog [groups] and what is [loaded]. Returns the names to
 * add plus the model-facing reply.
 */
export function resolveLoad(requested, groups, loaded, isOffered = () => false) {
  const byGroup = new Map(groups.map(g => [g.id, g.tools]))
  const deferred = new Set(groups.flatMap(g => g.tools))
  const add = new Set()
  const already = new Set()
  const unknown = []
  for (const raw of Array.isArray(requested) ? requested : []) {
    if (typeof raw !== 'string') continue
    const name = raw.trim()
    if (name === '') continue
    const targets = deferred.has(name) ? [name] : byGroup.get(name) ?? byGroup.get(name.replace(/^group:/, ''))
    if (targets === undefined) {
      if (isOffered(name)) already.add(name)
      else unknown.push(name)
      continue
    }
    for (const t of targets) (loaded.has(t) ? already : add).add(t)
  }
  const parts = []
  if (add.size > 0) parts.push(`Loaded: ${[...add].sort().join(', ')}. You can call them from your next step on.`)
  if (already.size > 0) parts.push(`Already available: ${[...already].sort().join(', ')}.`)
  if (unknown.length > 0) parts.push(`Not available in this conversation: ${unknown.join(', ')}.`)
  if (parts.length === 0) parts.push('No tool names given.')
  return { add: [...add].sort(), text: parts.join(' ') }
}

/** Deferrable tool names a session's log shows as loaded: offered by a request header, or recorded by the loader. */
export function loadedInLog(session) {
  const names = new Set()
  try {
    for (const t of session.requestHeader?.()?.tools ?? []) if (typeof t?.name === 'string') names.add(t.name)
  } catch {}
  try {
    for (let seq = Number(session.seq) - 1; seq >= 0; seq -= 1) {
      const event = session.eventAt(seq)
      if (event?.type === LOADED_RECORD && event.ignorable === true && Array.isArray(event.data?.names)) {
        for (const n of event.data.names) if (typeof n === 'string') names.add(n)
      }
    }
  } catch {}
  return names
}
