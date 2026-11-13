package io.github.timhub135.dsh.core.extensions

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.data.SecretStore
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.core.engine.EngineManager
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.session.SessionStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置 → 扩展's single entry point: [store] for plugins (engine-backed), plus what the app itself
 * owns — MCP servers (settings + SecretStore → the engine's settings layer), the 插件下载源 setting,
 * and files the user picks or shares (copied into `cache/ext-staging/`, where the engine reads them).
 */
@Singleton
class ExtensionsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineManager,
    private val sessions: Lazy<SessionStore>,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "extensions task failed", e) })

    /** Every call goes to the running engine, starting it when needed (the 扩展 page asks the engine, not a copy). */
    val client = ExtensionsClient { method, params -> engine.control().request(method, params) }

    val store = ExtensionsStore(client, object : ExtHost {
        override fun busy() = sessions.get().hasEngineRuns()
        override suspend fun restartEngine() = sessions.get().restartEngine()
        override suspend fun setUpdateBadge(count: Int) { settings.setExtUpdateBadge(count) }
    }, scope, FileListCache(File(context.filesDir, "ext-list-cache.json")))

    init {
        // The restart that applies a change may happen long after the user left the page (when a task ends):
        // report its outcome wherever the user is.
        scope.launch {
            store.events.collect { e ->
                val text = when (e) {
                    is ExtEvent.Activated -> e.results.joinToString("\n") { ExtText.activation(context, it) }
                    is ExtEvent.Notice -> e.notice.let { n ->
                        val title = n.name?.let { nm -> store.list.value?.plugins?.firstOrNull { it.name == nm }?.displayTitle } ?: n.name.orEmpty()
                        when (n.kind) {
                            "activated" -> ExtText.activation(context, Activation(n.name.orEmpty(), title, ok = true, tools = n.tools))
                            "activation-failed" -> ExtText.activation(context, Activation(n.name.orEmpty(), title, ok = false, error = UserError("activation-failed", n.message)))
                            else -> n.message
                        }.takeIf { it.isNotBlank() }
                    }
                    is ExtEvent.Failed -> ExtText.error(context, e.error)
                } ?: return@collect
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * Something shared to DSH (分享 → 安装为 DSH 扩展): a staged file path or the shared text. 设置 → 扩展
     * picks it up and starts the right flow ([ShareTarget.of]).
     */
    val pendingInput = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** `android.ext.*` notifications from the SDK channel (routed by SessionStore). */
    fun onNotification(method: String, params: JsonObject) = store.onNotification(method, params)

    /** 安全模式启动: the next start pauses every third-party plugin (§7.6). */
    suspend fun startInSafeMode() {
        SafeModeRequest.request()
        sessions.get().restartEngine()
        store.refresh()
    }

    // ---- 插件下载源 ----
    suspend fun setRegistry(mode: RegistryMode, custom: String = "") {
        settings.setPluginRegistry(mode, custom)
        // Read by the plugin-manager row at start: apply when idle.
        store.scheduleRestart()
    }

    // ---- files picked through SAF or shared to DSH ----

    /**
     * Copies [uri] into `cache/ext-staging/` (readable by the `:engine` process, same uid) and
     * returns the absolute path to pass as `input`. Keeps the original file name so the engine can
     * tell `.tgz` from `.zip` / `.md`.
     */
    suspend fun stage(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "ext-staging").apply { mkdirs() }
        val name = displayName(uri)?.let(::safeFileName) ?: "upload-${System.currentTimeMillis()}"
        val out = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw ExtException(UserError("bad-archive", ""))
        out
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---- MCP servers (§11) ----

    suspend fun servers(): List<McpServer> = settings.current().mcpServers

    /** Test before saving: connects once in the engine with the real header values. */
    suspend fun testMcp(server: McpServer, headerValues: Map<String, String>): McpTestResult = try {
        client.mcpTest(McpRows.testConfig(server) { h -> headerValues[h] ?: secrets.read(server.secretName(h)) })
    } catch (e: ExtException) {
        McpTestResult(ok = false, error = e.error)
    }

    /** Saves [server] with [headerValues] (stored in SecretStore only) and applies it when idle. */
    suspend fun saveMcp(server: McpServer, headerValues: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val old = servers().firstOrNull { it.id == server.id }
            old?.headerNames?.filter { it !in server.headerNames }?.forEach { secrets.clear(old.secretName(it)) }
            headerValues.forEach { (h, v) -> if (v.isNotEmpty()) secrets.write(server.secretName(h), v) }
        }
        settings.saveMcpServer(server)
        store.scheduleRestart()
    }

    /** `npx -y <pkg>` servers: installs the package into `dsh-home/mcp-servers/` first (native deps are refused there too). */
    suspend fun installNpmMcp(server: McpServer): Result<McpServer> {
        val pkg = server.`package` ?: return Result.failure(ExtException(UserError("bad-input")))
        val r = try { client.mcpInstall(pkg) } catch (e: ExtException) { return Result.failure(e) }
        if (!r.ok || r.bin == null) return Result.failure(ExtException(r.error ?: UserError("pnpm-failed")))
        val saved = server.copy(bin = r.bin)
        // Over the local-server limit: saved, but switched off until another is.
        val localOn = servers().count { it.local && it.enabled && it.id != server.id }
        val final = if (localOn >= McpServer.MAX_LOCAL_ENABLED) saved.copy(enabled = false) else saved
        saveMcp(final, emptyMap())
        return Result.success(final)
    }

    /** false when switching on would exceed [McpServer.MAX_LOCAL_ENABLED] local servers. */
    suspend fun setMcpEnabled(id: String, on: Boolean): Boolean {
        val all = servers()
        val s = all.firstOrNull { it.id == id } ?: return false
        if (on && s.local && all.count { it.local && it.enabled && it.id != id } >= McpServer.MAX_LOCAL_ENABLED) return false
        settings.updateMcpServer(id) { it.copy(enabled = on) }
        store.scheduleRestart()
        return true
    }

    suspend fun setMcpAskBeforeCall(id: String, on: Boolean) {
        runCatching { client.askBeforeCall("mcp:$id", on) }.onFailure { Log.w(TAG, "askBeforeCall mcp:$id", it) }
        settings.updateMcpServer(id) { it.copy(askBeforeCall = on) }
    }

    suspend fun removeMcp(id: String) {
        val s = servers().firstOrNull { it.id == id } ?: return
        withContext(Dispatchers.IO) { s.headerNames.forEach { secrets.clear(s.secretName(it)) } }
        settings.deleteMcpServer(id)
        if (s.local) s.`package`?.let { pkg -> runCatching { client.mcpRemove(pkg) } }
        store.scheduleRestart()
    }

    suspend fun mcpStatus(): McpStatusResult? = runCatching { client.mcpStatus() }.getOrNull()

    private class FileListCache(private val file: File) : ExtListCache {
        override fun read(): ExtList? = runCatching { RpcJson.decodeFromString(ExtList.serializer(), file.readText()) }.getOrNull()
        override fun write(list: ExtList) {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(RpcJson.encodeToString(ExtList.serializer(), list))
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val TAG = "Extensions"

        /** A staging file name: the picked name without path separators or control characters. */
        fun safeFileName(name: String): String =
            name.substringAfterLast('/').replace(Regex("[\\u0000-\\u001f\\\\:*?\"<>|]"), "_").trim().take(120).ifEmpty { "upload" }
    }
}

/** Where a shared item goes: a plugin install, a skill import, or the MCP paste box. */
enum class ShareTarget {
    Plugin, Skill, Mcp;
    companion object {
        fun of(input: String): ShareTarget {
            val t = input.trim()
            val lower = t.lowercase()
            return when {
                t.startsWith("{") -> Mcp
                lower.endsWith(".md") || lower.endsWith(".skill") || lower.endsWith("/skill.md") -> Skill
                else -> Plugin
            }
        }
    }
}
