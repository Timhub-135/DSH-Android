package io.github.jackwener.dsh.core.capability

import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.tools.AppTools
import io.github.jackwener.dsh.core.capability.tools.BrowserTools
import io.github.jackwener.dsh.core.capability.tools.ClipboardTools
import io.github.jackwener.dsh.core.capability.tools.CommsTools
import io.github.jackwener.dsh.core.capability.tools.FileTools
import io.github.jackwener.dsh.core.capability.tools.KnowledgeTools
import io.github.jackwener.dsh.core.capability.tools.MediaTools
import io.github.jackwener.dsh.core.capability.tools.NotificationTools
import io.github.jackwener.dsh.core.capability.tools.RuleTools
import io.github.jackwener.dsh.core.capability.tools.ScreenTools
import io.github.jackwener.dsh.core.capability.tools.SystemTools
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.JsonRpcPeer
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.model.ruleIdOfSession
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.session.SessionStore
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single gate for device capabilities (docs/ENGINE-CONTRACT.md §3): lists the enabled tools
 * and executes calls after group, session-mode, unattended-whitelist and lock-state checks.
 */
@Singleton
class CapabilityHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rules: RuleDao,
    private val lock: LockState,
    private val sessions: Lazy<SessionStore>,
    files: FileTools, apps: AppTools, screen: ScreenTools, system: SystemTools, notifications: NotificationTools,
    comms: CommsTools, media: MediaTools, clipboard: ClipboardTools, browser: BrowserTools, ruleTools: RuleTools,
    knowledge: KnowledgeTools,
    private val shellGate: ShellGate,
    private val guard: ActionGuard,
    private val agentScreen: AgentScreen,
    private val appKnowledge: AppKnowledge,
    private val experience: AppExperience,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val allTools: Map<String, Tool> = listOf(files, apps, screen, system, notifications, comms, media, clipboard, browser, ruleTools, knowledge)
        .flatMap { it.tools }.associateBy { it.name }
    @Volatile private var bridge: JsonRpcPeer? = null
    private val liveSeq = java.util.concurrent.atomic.AtomicLong()
    private val _live = MutableStateFlow<Map<Long, LiveCall>>(emptyMap())
    /** Calls executing now, keyed per call: read-only, for status surfaces outside the app (floating ball). */
    val liveCalls: StateFlow<Map<Long, LiveCall>> = _live.asStateFlow()

    init {
        scope.launch {
            settings.settings.map { it.enabledGroups }.distinctUntilChanged().drop(1).collect {
                runCatching { bridge?.notify("capability/changed") }
            }
        }
    }

    fun descriptor(name: String): ToolDescriptor? = allTools[name]?.descriptor

    /**
     * The model-facing descriptor. Static on purpose: live state (Shizuku up/down, permissions) changes
     * often, and every description change re-bills the whole cached prompt. Availability is reported in
     * the call's result or error instead (path=…, shizuku_unavailable, permission_missing).
     */
    private val listedCache = java.util.concurrent.ConcurrentHashMap<String, ToolDescriptor>()
    private fun listed(t: Tool): ToolDescriptor = listedCache.getOrPut(t.name) { Companion.listed(t) }

    fun attach(peer: JsonRpcPeer) { bridge = peer }

    /** The bridge is gone (engine stopped, crashed or torn down): nothing can receive these results. */
    fun detach() { bridge = null; cancelAll("engine stopped") }

    /** In-flight bridge calls, keyed by [callKey]. Cancelling the job cancels every wait inside the call. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun callKey(sessionId: String, callId: String) = "$sessionId\u0000$callId"

    /** `capability/cancel { callId, sessionId? }`: the plugin timed out or the turn was aborted. */
    fun cancel(callId: String, sessionId: String?, reason: String = "cancelled by the engine") {
        val keys = if (sessionId != null) listOf(callKey(sessionId, callId)) else inFlight.keys.filter { it.endsWith("\u0000$callId") }
        keys.forEach { k -> inFlight.remove(k)?.let { Log.i(TAG, "cancel $k: $reason"); it.cancel(CancellationException(reason)) } }
    }

    /** Cancels every in-flight call of a session tree (the user pressed stop, the session was closed). */
    fun cancelSession(sessionId: String, reason: String = "session stopped") {
        inFlight.keys.filter { it.substringBefore('\u0000').let { s -> s == sessionId || s.startsWith("$sessionId/") } }
            .forEach { k -> inFlight.remove(k)?.cancel(CancellationException(reason)) }
    }

    /** Cancels every in-flight bridge call (engine loss / teardown). */
    fun cancelAll(reason: String) {
        inFlight.keys.toList().forEach { k -> inFlight.remove(k)?.cancel(CancellationException(reason)) }
    }

    /** Bridge notifications (EngineManager routes the capability channel's notifications here). */
    fun onBridgeNotification(method: String, params: JsonObject) {
        if (method == "capability/cancel") {
            val callId = params["callId"]?.jsonPrimitive?.contentOrNull ?: return
            cancel(callId, params["sessionId"]?.jsonPrimitive?.contentOrNull, params["reason"]?.jsonPrimitive?.contentOrNull ?: "cancelled by the engine")
        }
        if (method == "experience/notes") scope.launch(Dispatchers.IO) { runCatching { experience.reflected(params) }.onFailure { Log.w(TAG, "experience/notes", it) } }
    }

    suspend fun onBridgeRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "capability/list" -> {
            val enabled = settings.current().enabledGroups
            buildJsonObject {
                putJsonArray("tools") {
                    allTools.values.filter { ToolGroup.of(it.descriptor.group) in enabled }.forEach { t ->
                        add(RpcJson.encodeToJsonElement(ToolDescriptor.serializer(), listed(t)))
                    }
                }
            }
        }
        "capability/invoke" -> encode(invokeFromBridge(params))
        "experience/turn" -> withContext(Dispatchers.IO) { experience.turnEnded(params) }
        // Also accepted as a request (fire-and-forget clients may prefer an ack).
        "capability/cancel" -> { onBridgeNotification(method, params); buildJsonObject { put("ok", true) } }
        else -> throw JsonRpcException(-32601, "unknown method $method")
    }

    /** A bridge call runs in its own job so `capability/cancel`, stop and engine loss can cancel it. */
    private suspend fun invokeFromBridge(params: JsonObject): ToolResult {
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
        val callId = params["callId"]?.jsonPrimitive?.contentOrNull ?: java.util.UUID.randomUUID().toString()
        val key = callKey(sessionId, callId)
        // Own job (so capability/cancel can target this call), linked both ways to the engine request:
        // the request's cancellation (EngineCalls: teardown / EOF) cancels it below, and it keeps the Call element.
        val job = scope.async(currentCoroutineContext()[io.github.jackwener.dsh.core.engine.EngineCalls.Call] ?: kotlin.coroutines.EmptyCoroutineContext) { invoke(params, viaEngine = true) }
        inFlight.put(key, job)?.cancel(CancellationException("superseded by a new call with the same id"))
        return try {
            job.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) { job.cancel(); throw e }
            ToolResult.error("cancelled", "The call was cancelled (${e.message ?: "cancelled"}) before it acted; nothing was done.")
        } finally {
            inFlight.remove(key, job)
        }
    }

    /**
     * Executes one call. Also used directly by rule "steps" actions (no engine involved). Every wait
     * for the user (unlock, permission card, Shizuku card, takeover) is bounded by the call's deadline
     * (`timeoutMs` from the request, else the tool's descriptor) and is cancelled with the call; the
     * tool itself only starts once those waits are over and the call is still live. [viaEngine]: the
     * call came over the bridge, so the engine's policy (plugin-guard hardline, approvals) saw it first.
     */
    suspend fun invoke(params: JsonObject, viaEngine: Boolean = false): ToolResult {
        val name = params["tool"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.error("invalid_args", "missing tool")
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
        val callId = params["callId"]?.jsonPrimitive?.contentOrNull ?: ""
        val args = (params["args"] as? JsonObject) ?: JsonObject(emptyMap())
        val tool = allTools[name] ?: return ToolResult.error("invalid_args", "unknown tool $name")
        val group = ToolGroup.of(tool.descriptor.group)
        if (group !in settings.current().enabledGroups) return ToolResult.error("not_authorized",
            "The '${tool.descriptor.group}' capability is turned off. Ask the user to enable it in DSH → Settings → Agent.")
        // Backstop for session modes (plugin-mode already hides these tools from the conversation).
        if (group != null && !sessions.get().modeAllowsGroup(sessionId, group)) return ToolResult.error("not_authorized",
            "The '${tool.descriptor.group}' tools are not available in this conversation's mode; nothing was done. Tell the user it needs a conversation in another mode.")
        val ruleId = ruleIdOfSession(sessionId)
        if (ruleId != null) {
            val spec = rules.get(ruleId)?.let { e -> runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), e.specJson) }.getOrNull() }
            if (spec == null || name !in spec.allowedTools) return ToolResult.error("not_authorized",
                "tool_not_authorized: '$name' is not in this automation's approved tool list.")
        }
        val unattended = ruleId != null
        val requested = params["timeoutMs"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
        // Bridge calls carry the engine's timeout; rule steps (no engine) may wait longer for an unlock.
        val budget = requested ?: if (sessionId.isEmpty() || unattended && tool.needsUnlock) UNATTENDED_UNLOCK_WAIT_MS else tool.descriptor.timeoutMs
        // Answer a little before the engine gives up, so the model gets a clear result instead of a bare timeout.
        val budgetMs = (budget - ANSWER_MARGIN_MS).coerceAtLeast(budget / 2)
        val call = CallContext(sessionId, callId, unattended, SystemClock.elapsedRealtime() + budgetMs, viaEngine)
        val liveKey = liveSeq.incrementAndGet()
        // A screen call without an agent screen to act on works on the user's own screen (AgentScreen.target).
        val mainScreen = name.startsWith("screen_") && (args.str("screen") == "main" || agentScreen.current(sessionId) == null)
        _live.update { it + (liveKey to LiveCall(sessionId, name, mainScreen, acts = tool.descriptor.risk != Risk.read)) }
        val result = try {
            withTimeoutOrNull(budgetMs) { run(tool, name, args, call) }?.also { experience.observe(sessionId, name, args, it) }
        } finally {
            _live.update { it - liveKey }
        }
        return result ?: call.waitingFor.let { waiting ->
            if (waiting != null) ToolResult.error("waiting_for_user",
                "Still waiting for the user to $waiting after ${budgetMs / 1000}s, so the call was cancelled and nothing was done. " +
                    "Call the tool again when the user is ready, or tell them what is needed.")
            else ToolResult.error("timeout", "The step did not finish within ${budgetMs / 1000}s and was cancelled. " +
                "Check the current state (e.g. screen_read / device_status) before retrying.")
        }
    }

    /**
     * The user spoke (a new turn or steering): the loop guard, the app-guide cap and route tracking
     * start over ([text] ranks app notes), and agent-screen actions resume after a 我来接管.
     */
    fun onUserPrompt(sessionId: String, text: String = "") {
        guard.reset(sessionId)
        appKnowledge.newTurn(sessionId, text)
        experience.newTurn(sessionId, text)
        agentScreen.clearUserTakeover(sessionId)
    }

    /** Screen and app calls pass the loop guard (docs/AGENT-SCREEN-V2.md §3.5): refused once stopped, reminders appended. */
    private suspend fun run(tool: Tool, name: String, args: JsonObject, call: CallContext): ToolResult {
        guard.before(call.sessionId, tool, args)?.let { return it }
        return guard.after(call.sessionId, tool, args, execute(tool, name, args, call))
    }

    private suspend fun execute(tool: Tool, name: String, args: JsonObject, call: CallContext): ToolResult {
        val sessionId = call.sessionId
        if (tool.needsUnlock && lock.isLocked) {
            // The conversation's own unlock slot (only for the user's sessions); a step without a session gets a plain one.
            val notifId = Notifications.ID_SESSION_UNLOCK
            if (sessionId.isEmpty()) Notifications.post(context, Notifications.CHANNEL_ATTENTION, notifId,
                context.getString(R.string.notif_unlock_title), context.getString(R.string.notif_unlock_text), null)
            else sessions.get().announceWaiting(sessionId, context.getString(R.string.notif_unlock_title), context.getString(R.string.notif_unlock_text), notifId)
            val unlocked = try {
                if (sessionId.isNotEmpty()) sessions.get().setWaitingForUnlock(sessionId, true)
                call.waitFor("unlock the phone") { lock.awaitUnlock(call.deadlineMs - SystemClock.elapsedRealtime()) }
            } finally {
                withContext(NonCancellable) {
                    runCatching { if (sessionId.isEmpty()) androidx.core.app.NotificationManagerCompat.from(context).cancel(notifId) else sessions.get().clearWaiting(sessionId, notifId) }
                    if (sessionId.isNotEmpty()) runCatching { sessions.get().setWaitingForUnlock(sessionId, false) }
                }
            }
            if (!unlocked) return ToolResult.error("device_locked_timeout", "The phone stayed locked; this step needs an unlocked screen. Nothing was done.")
        }
        return try {
            var attempt = 0
            var result: ToolResult? = null
            while (result == null) {
                // Re-check right before acting: a call cancelled while it waited must never run late.
                io.github.jackwener.dsh.core.engine.EngineCalls.ensureCallActive()
                try {
                    result = tool.invoke(args, call)
                } catch (e: io.github.jackwener.dsh.core.platform.ShellUnavailableException) {
                    // Shizuku-only step: the Shizuku card waits in the composer slot and the call resumes
                    // as soon as Shizuku is ready; 不用了 (or an automation) gets a clear model-facing result.
                    if (attempt++ >= 2 || !shellGate.acquire(call, name, optional = false)) return shellGate.unavailable(call)
                } catch (e: PermissionMissingException) {
                    // Attended sessions get a just-in-time permission card in the composer slot; the call
                    // waits there and is retried once the user comes back from the system settings page.
                    if (call.unattended || sessionId.isEmpty()) return ToolResult.error("permission_missing",
                        "Missing permission: ${e.what}. Automations cannot ask the user for it; skip this step and report that DSH needs '${e.what}' (DSH → Settings → Permissions).")
                    if (attempt++ >= 3 || !call.waitFor("grant '${e.what}'") { sessions.get().requestPermission(sessionId, name, e.what) })
                        return ToolResult.error("permission_missing", "Missing permission: ${e.what}. The user declined to grant it; continue without this capability or explain what is needed.")
                }
            }
            result.also { r -> ShotStore.save(context, sessionId, call.callId, r.content.filterIsInstance<Content.Image>()) }
        } catch (e: CancellationException) {
            // Our own cancellation (cancel / stop / deadline) propagates; a timeout inside the tool is a failure.
            currentCoroutineContext().ensureActive()
            ToolResult.error("failed", "The step timed out internally (${e.message}); check the current state before retrying.")
        } catch (e: InvalidArgsException) {
            ToolResult.error("invalid_args", e.message ?: "invalid arguments")
        } catch (e: Exception) {
            Log.w(TAG, "tool $name failed", e)
            ToolResult.error("failed", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun encode(r: ToolResult) = buildJsonObject {
        put("ok", r.ok)
        putJsonArray("content") {
            r.content.forEach { c -> addJsonObject {
                when (c) {
                    is Content.Text -> { put("type", "text"); put("text", c.text) }
                    is Content.Image -> { put("type", "image"); put("mimeType", c.mimeType); put("data", c.base64) }
                }
            } }
        }
        if (!r.ok) putJsonObject("error") { put("code", r.errorCode ?: "failed"); put("message", r.errorMessage ?: "") }
    }

    companion object {
        private const val TAG = "CapabilityHub"

        /** [t]'s descriptor as `capability/list` sends it. */
        fun listed(t: Tool): ToolDescriptor =
            if (t.shell == ShellUse.None) t.descriptor
            else t.descriptor.copy(description = t.descriptor.description + ShellGate.staticNote(t.shell, t.shellFallback))
        /** Fixed-step automations (no engine): how long a needsUnlock step waits for the user to unlock. */
        const val UNATTENDED_UNLOCK_WAIT_MS = 2 * 60 * 60_000L
        const val ANSWER_MARGIN_MS = 5_000L
    }
}

/** A call executing now. [mainScreen]: a screen_* call on the user's screen; [acts]: it changes something (not a read). */
data class LiveCall(val sessionId: String, val tool: String, val mainScreen: Boolean, val acts: Boolean)

/** Every tool group class exposes its tools. */
interface ToolSet { val tools: List<Tool> }
