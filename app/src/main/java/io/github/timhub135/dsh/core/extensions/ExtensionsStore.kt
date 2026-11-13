package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** What the store needs from the rest of the app (SessionStore / EngineManager in production). */
interface ExtHost {
    /** A conversation is running in the engine: restarting now would cut it off. */
    fun busy(): Boolean
    /** Restart the engine through SessionStore (runs it kills end as stopped). */
    suspend fun restartEngine()
    /** Updates the 扩展 entry's "N 个可更新" badge. */
    suspend fun setUpdateBadge(count: Int) {}
}

/** Last [ExtList], so the page can draw before the engine is up (§13.2). */
interface ExtListCache {
    fun read(): ExtList?
    fun write(list: ExtList)
}

/** Restart orchestration for changes that only apply on a fresh engine (§7.4). */
sealed interface RestartState {
    data object Idle : RestartState
    /** A task is running; the restart happens when it ends (or on 立即重启). */
    data object WaitingForIdle : RestartState
    data object Restarting : RestartState
}

/** One plugin's outcome after the restart that applied it: "已启用 X：新增工具 a、b" or "X 启动失败：…". */
data class Activation(
    val name: String,
    val title: String,
    val ok: Boolean,
    val tools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val error: UserError? = null,
    /** Switched off / removed by the change (no message needed beyond "已停用"). */
    val disabled: Boolean = false,
)

sealed interface ExtEvent {
    data class Activated(val results: List<Activation>) : ExtEvent
    data class Notice(val notice: ExtNotice) : ExtEvent
    data class Failed(val error: UserError) : ExtEvent
}

/**
 * The app side of plugin management: the cached [ExtList], restart-when-idle, engine
 * notifications and the activation report. Engine state is authoritative; nothing about a plugin
 * is persisted here except the display cache.
 */
class ExtensionsStore(
    val client: ExtensionsClient,
    private val host: ExtHost,
    private val scope: CoroutineScope,
    private val cache: ExtListCache? = null,
    private val pollMs: Long = 1_500,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _list = MutableStateFlow(cache?.read())
    val list: StateFlow<ExtList?> = _list.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadError = MutableStateFlow<UserError?>(null)
    val loadError: StateFlow<UserError?> = _loadError.asStateFlow()
    private val _restart = MutableStateFlow<RestartState>(RestartState.Idle)
    val restart: StateFlow<RestartState> = _restart.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, ExtProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ExtProgress>> = _progress.asStateFlow()
    private val _events = MutableSharedFlow<ExtEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ExtEvent> = _events.asSharedFlow()
    private val _updates = MutableStateFlow<List<UpdateEntry>>(emptyList())
    val updates: StateFlow<List<UpdateEntry>> = _updates.asStateFlow()

    /** Plugins changed since the last restart: the activation report covers these. */
    private val touched = LinkedHashSet<String>()
    private var restartJob: Job? = null
    /** When this store last restarted the engine: its own activation report covers that boot. */
    @Volatile private var ownRestartAt = 0L

    suspend fun refresh(): ExtList? {
        _loading.value = true
        return try {
            client.list().also { l ->
                _list.value = l
                _loadError.value = null
                cache?.write(l)
                // The engine remembers a change whose restart never happened (app killed meanwhile).
                if (l.restartPending && _restart.value == RestartState.Idle) scheduleRestart()
            }
        } catch (e: ExtException) {
            _loadError.value = e.error
            null
        } finally {
            _loading.value = false
        }
    }

    suspend fun checkUpdates(refresh: Boolean = false): List<UpdateEntry> {
        val u = runCatching { client.updates(refresh).updates }.getOrElse { return _updates.value }
        _updates.value = u
        host.setUpdateBadge(u.count { it.compatible })
        return u
    }

    /**
     * Runs a state-changing call for [name] and applies its result: a restart-required change
     * restarts the engine when idle, a failure is returned for the caller to show in place.
     */
    suspend fun change(name: String?, call: suspend ExtensionsClient.() -> ChangeView): ChangeView {
        val view = try {
            client.call()
        } catch (e: ExtException) {
            ChangeView(application = "failed", error = e.error)
        }
        if (!view.failed && !view.cancelled) {
            name?.let { synchronized(touched) { touched += it } }
            if (view.restartRequired || view.application == "restart-required") scheduleRestart()
            refresh()
        }
        return view
    }

    /** Restart when no task runs (now, if none does). */
    fun scheduleRestart() {
        if (restartJob?.isActive == true) return
        restartJob = scope.launch {
            while (host.busy()) {
                _restart.value = RestartState.WaitingForIdle
                delay(pollMs)
            }
            restartLocked()
        }
    }

    /** 立即重启: stops what is running (it ends as stopped) and applies the pending change. */
    fun restartNow() {
        restartJob?.cancel()
        restartJob = scope.launch { restartLocked() }
    }

    private suspend fun restartLocked() {
        _restart.value = RestartState.Restarting
        ownRestartAt = now()
        val before = _list.value
        try {
            host.restartEngine()
            val after = refresh()
            val names = synchronized(touched) { touched.toSet().also { touched.clear() } }
            if (after != null) {
                val results = activationReport(before, after, names)
                if (results.isNotEmpty()) _events.tryEmit(ExtEvent.Activated(results))
            }
        } finally {
            _restart.value = RestartState.Idle
        }
    }

    fun onNotification(method: String, params: JsonObject) {
        when (method) {
            ExtMethods.N_PROGRESS -> runCatching { RpcJson.decodeFromJsonElement(ExtProgress.serializer(), params) }.getOrNull()?.let { p ->
                _progress.update { it + (p.requestId to p) }
            }
            ExtMethods.N_CHANGED -> scope.launch { refresh() }
            ExtMethods.N_NOTICE -> runCatching { RpcJson.decodeFromJsonElement(ExtNotice.serializer(), params) }.getOrNull()?.let { n ->
                // The engine reports activation after every boot; after our own restart activationReport already did.
                if (!(n.isActivation && now() - ownRestartAt < OWN_RESTART_WINDOW_MS)) _events.tryEmit(ExtEvent.Notice(n))
                scope.launch { refresh() }
            }
        }
    }

    fun clearProgress(requestId: String) = _progress.update { it - requestId }

    companion object {
        const val OWN_RESTART_WINDOW_MS = 120_000L
        fun newRequestId(): String = UUID.randomUUID().toString()

        /**
         * What a restart did to the plugins in [names] (all changed ones when empty): newly active
         * plugins with what they add, and the ones that failed to come up with why.
         */
        fun activationReport(before: ExtList?, after: ExtList, names: Set<String>): List<Activation> {
            val prev = before?.plugins.orEmpty().associateBy { it.name }
            return after.plugins.filter { names.isEmpty() || it.name in names }.mapNotNull { p ->
                val old = prev[p.name]
                val changed = old == null || old.status != p.status || old.version != p.version || old.enabled != p.enabled
                if (!changed && names.isEmpty()) return@mapNotNull null
                when (p.status) {
                    ExtStatus.ACTIVE -> Activation(p.name, p.displayTitle, ok = true, tools = p.tools, skills = p.skills, commands = p.commands)
                    ExtStatus.DISABLED -> if (old?.enabled == true) Activation(p.name, p.displayTitle, ok = true, disabled = true) else null
                    ExtStatus.FAILED, ExtStatus.WAITING, ExtStatus.INCOMPATIBLE, ExtStatus.SUSPENDED ->
                        Activation(p.name, p.displayTitle, ok = false, error = p.statusDetail ?: UserError(p.status, p.rows.firstNotNullOfOrNull { it.error }.orEmpty()))
                    else -> null
                }
            }
        }
    }
}

/**
 * 安装 (§9.3): inspect → one confirmation card → install with progress → result. One instance per
 * install sheet; the state drives the sheet.
 */
class InstallFlow(private val store: ExtensionsStore, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Inspecting(val input: InstallInput) : State
        /** The confirmation card. */
        data class Confirm(val input: InstallInput, val info: InspectResult) : State
        data class Installing(val input: InstallInput, val info: InspectResult?, val requestId: String) : State
        /** Installed; [view] says whether a restart applies it. */
        data class Done(val info: InspectResult?, val view: ChangeView) : State
        data class Failed(val input: InstallInput, val error: UserError, val info: InspectResult? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    /** Starts with whatever the user gave: a name, a link, a staged file path. */
    fun start(raw: String) {
        val input = InstallSource.classify(raw)
        job?.cancel()
        when (input.kind) {
            SourceKind.Rejected -> { _state.value = State.Failed(input, UserError(input.code ?: "needs-git")); return }
            SourceKind.McpJson -> { _state.value = State.Failed(input, UserError("mcp-json")); return }
            SourceKind.Unknown -> { _state.value = State.Failed(input, UserError("bad-input")); return }
            else -> {}
        }
        _state.value = State.Inspecting(input)
        job = scope.launch {
            _state.value = try {
                val info = store.client.inspect(input.spec)
                when {
                    info.error != null -> State.Failed(input, info.error, info)
                    !info.bundle -> State.Failed(input, UserError("not-bundle"), info)
                    else -> State.Confirm(input, info)
                }
            } catch (e: ExtException) {
                State.Failed(input, e.error)
            }
        }
    }

    /** 安装 on the card; [force] = 仍要安装 for an incompatible version (writes dsh's exact-version exemption). */
    fun confirm(force: Boolean = false) {
        val s = _state.value
        val (input, info) = when (s) {
            is State.Confirm -> s.input to s.info
            is State.Failed -> s.input to s.info
            else -> return
        }
        val requestId = ExtensionsStore.newRequestId()
        _state.value = State.Installing(input, info, requestId)
        job = scope.launch {
            val view = store.change(info?.name) { install(info?.spec ?: input.spec, requestId, force) }
            store.clearProgress(requestId)
            _state.value = when {
                view.cancelled -> State.Idle
                view.failed -> State.Failed(input, view.error ?: UserError("pnpm-failed"), info)
                else -> State.Done(info, view)
            }
        }
    }

    /** 取消 while installing (the engine rolls back) or closing the card. */
    fun cancel() {
        val s = _state.value
        if (s is State.Installing) scope.launch { runCatching { store.client.cancel(s.requestId) } }
        else { job?.cancel(); _state.value = State.Idle }
    }

    fun dismiss() { job?.cancel(); _state.value = State.Idle }
}
