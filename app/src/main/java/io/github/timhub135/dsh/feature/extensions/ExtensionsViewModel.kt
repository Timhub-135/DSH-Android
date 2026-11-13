package io.github.timhub135.dsh.feature.extensions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.data.Settings
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.core.engine.EngineManager
import io.github.timhub135.dsh.core.engine.EngineState
import io.github.timhub135.dsh.core.extensions.ChangeView
import io.github.timhub135.dsh.core.extensions.DetailResult
import io.github.timhub135.dsh.core.extensions.ExtException
import io.github.timhub135.dsh.core.extensions.ExtensionsRepository
import io.github.timhub135.dsh.core.extensions.InstallFlow
import io.github.timhub135.dsh.core.extensions.LogResult
import io.github.timhub135.dsh.core.extensions.McpCandidate
import io.github.timhub135.dsh.core.extensions.McpServer
import io.github.timhub135.dsh.core.extensions.McpStatusResult
import io.github.timhub135.dsh.core.extensions.McpTestResult
import io.github.timhub135.dsh.core.extensions.PluginRegistries
import io.github.timhub135.dsh.core.extensions.RegistryMode
import io.github.timhub135.dsh.core.extensions.UserError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置 → 扩展 (插件 · 技能 · MCP 服务). Thin glue: the logic lives in ExtensionsStore / InstallFlow / the *State classes. */
@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val repo: ExtensionsRepository,
    private val settingsRepo: SettingsRepository,
    private val engine: EngineManager,
) : ViewModel() {
    val store = repo.store
    private val client = repo.client
    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val install = InstallFlow(store, viewModelScope)
    val market = MarketState(client, viewModelScope)
    val skills = SkillsState(client, viewModelScope)
    private val language: String get() = context.resources.configuration.locales[0].toLanguageTag()

    /** 插件 · 技能 · MCP 服务 (0, 1, 2); here so other flows can switch it (an MCP config pasted as a plugin). */
    val tab = MutableStateFlow(0)

    /** In-place failure of the last plugin action (switch, update, uninstall …), keyed by plugin name. */
    val actionErrors = MutableStateFlow<Map<String, UserError>>(emptyMap())
    /** Plugins with an action in flight (switch disabled meanwhile). */
    val busy = MutableStateFlow<Set<String>>(emptySet())

    private var opened = false
    /** [open] once per ViewModel (the settings graph): every extensions page calls it, whichever is shown first. */
    fun openOnce() { if (!opened) { opened = true; open() } }

    fun open() = viewModelScope.launch {
        if (store.refresh() != null) {
            market.load()
            store.checkUpdates()
        }
    }

    fun retry() = open()

    fun startSafeMode() = viewModelScope.launch { repo.startInSafeMode(); market.load() }

    // ---- plugin actions ----

    private fun act(name: String, call: suspend io.github.timhub135.dsh.core.extensions.ExtensionsClient.() -> ChangeView) = viewModelScope.launch {
        busy.update { it + name }
        actionErrors.update { it - name }
        val view = store.change(name, call)
        if (view.failed) actionErrors.update { it + (name to (view.error ?: UserError("error"))) }
        busy.update { it - name }
    }

    fun setEnabled(name: String, on: Boolean) = act(name) { setEnabled(name, on) }
    fun setRowEnabled(name: String, entryId: String, on: Boolean) = act(name) { setRowEnabled(entryId, on) }
    fun update(name: String, version: String? = null) = act(name) { update(name, version) }
    fun updateAll() = viewModelScope.launch { store.list.value?.updates?.forEach { update(it.name, it.update?.version).join() } }
    fun remove(name: String) = act(name) { remove(name) }
    fun resume(name: String) = act(name) { resume(name) }
    fun restartNow() = store.restartNow()
    fun enableEngineKeepAlive() = viewModelScope.launch { engine.setKeepAlive(true) }

    fun setAskBeforeCall(name: String, on: Boolean) = viewModelScope.launch {
        try { client.askBeforeCall(name, on); store.refresh() }
        catch (e: ExtException) { actionErrors.update { it + (name to e.error) } }
    }

    // ---- detail page ----

    val details = MutableStateFlow<Map<String, DetailResult>>(emptyMap())
    fun loadDetail(name: String) = viewModelScope.launch {
        if (name in details.value) return@launch
        val d = runCatching { client.detail(name, language) }.getOrElse { DetailResult() }
        details.update { it + (name to d) }
    }

    fun configEditor(entryId: String) = ConfigEditor(client, viewModelScope, entryId, language).also { it.load() }

    val log = MutableStateFlow<LogResult?>(null)
    fun loadLog(name: String?, requestId: String? = null) = viewModelScope.launch {
        log.value = runCatching { client.log(name, requestId) }.getOrElse { LogResult(it.message.orEmpty()) }
    }
    fun closeLog() { log.value = null }

    // ---- install sources ----

    /** A file picked in the + menu: staged, then the same flow as a typed link. */
    fun installFile(uri: Uri) = viewModelScope.launch {
        runCatching { repo.stage(uri) }.onSuccess { install.start(it.path) }
            .onFailure { install.start("") }
    }

    /** 换个下载源重试: swap the primary registry (npm 官方 ⇄ npmmirror), apply it, then try again. */
    fun retryOtherRegistry(input: String) = viewModelScope.launch {
        val primary = store.list.value?.registries?.firstOrNull() ?: PluginRegistries.plan(settings.value.pluginRegistry, settings.value.pluginRegistryCustom,
            java.util.Locale.getDefault(), java.util.TimeZone.getDefault().id).first()
        val next = if (primary.contains("npmmirror")) RegistryMode.Npmjs else RegistryMode.Npmmirror
        settingsRepo.setPluginRegistry(next, settings.value.pluginRegistryCustom)
        store.restartNow()
        // restartNow runs the restart in the store's scope; wait until it settled before retrying.
        kotlinx.coroutines.delay(300)
        store.restart.first { it == io.github.timhub135.dsh.core.extensions.RestartState.Idle }
        install.start(input)
    }

    fun setRegistry(mode: RegistryMode, custom: String = settings.value.pluginRegistryCustom) = viewModelScope.launch { repo.setRegistry(mode, custom) }

    // ---- skills ----

    fun importSkillFile(uri: Uri) = viewModelScope.launch {
        runCatching { repo.stage(uri) }.onSuccess { skills.preview(it.path) }
    }

    // ---- MCP ----

    val mcpStatus = MutableStateFlow<McpStatusResult?>(null)
    fun loadMcpStatus() = viewModelScope.launch { mcpStatus.value = repo.mcpStatus() }

    suspend fun testMcp(server: McpServer, headers: Map<String, String>): McpTestResult = repo.testMcp(server, headers)

    /** Saves a tested remote server. */
    fun saveRemoteMcp(server: McpServer, headers: Map<String, String>) = viewModelScope.launch { repo.saveMcp(server, headers) }

    suspend fun addNpmMcp(server: McpServer): Result<McpServer> = repo.installNpmMcp(server)

    /** Adds the supported entries of a pasted `mcpServers` JSON; returns how many were added. */
    suspend fun addCandidates(list: List<McpCandidate>): Int {
        var added = 0
        val taken = settings.value.mcpServers.map { it.id }.toMutableList()
        for (c in list) {
            val id = McpServer.newId(c.name, taken).also { taken += it }
            when (c) {
                is McpCandidate.Remote -> { repo.saveMcp(McpServer(id, c.name, McpServer.KIND_REMOTE, url = c.url, headerNames = c.headers.keys.toList()), c.headers); added++ }
                is McpCandidate.Npm -> if (repo.installNpmMcp(McpServer(id, c.name, McpServer.KIND_NPM, `package` = c.pkg, args = c.args, env = c.env)).isSuccess) added++
                is McpCandidate.Unsupported -> {}
            }
        }
        return added
    }

    /** false: the local-server limit is reached. */
    suspend fun setMcpEnabled(id: String, on: Boolean): Boolean = repo.setMcpEnabled(id, on)
    fun setMcpAsk(id: String, on: Boolean) = viewModelScope.launch { repo.setMcpAskBeforeCall(id, on) }
    fun removeMcp(id: String) = viewModelScope.launch { repo.removeMcp(id) }
}
