package io.github.timhub135.dsh.core.extensions

import android.content.Context
import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/** 插件下载源 (§6.6). Stored as [id] in settings (`plugin_registry_mode`). */
enum class RegistryMode(val id: String) {
    Auto("auto"), Npmjs("npmjs"), Npmmirror("npmmirror"), Custom("custom");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Auto }
}

object PluginRegistries {
    const val NPMJS = "https://registry.npmjs.org/"
    const val NPMMIRROR = "https://registry.npmmirror.com/"
    private val CHINA_TZ = setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Urumqi", "Asia/Harbin", "PRC")

    /** "自动" picks npmmirror first for zh-CN / mainland time zones (§6.6). */
    fun inChina(locale: Locale, tz: String): Boolean =
        (locale.language == "zh" && (locale.country.equals("CN", true) || locale.script.equals("Hans", true))) || tz in CHINA_TZ

    /** Registries in the order they are tried: the first is primary, the rest are fallbacks. */
    fun plan(mode: RegistryMode, custom: String, locale: Locale, tz: String): List<String> = when (mode) {
        RegistryMode.Npmjs -> listOf(NPMJS, NPMMIRROR)
        RegistryMode.Npmmirror -> listOf(NPMMIRROR, NPMJS)
        // A private registry is asked alone: never fall through to a public one that may publish a different package under the name.
        RegistryMode.Custom -> normalize(custom)?.let { listOf(it) } ?: plan(RegistryMode.Auto, "", locale, tz)
        RegistryMode.Auto -> if (inChina(locale, tz)) listOf(NPMMIRROR, NPMJS) else listOf(NPMJS, NPMMIRROR)
    }

    /** An http(s) registry URL with a trailing slash, or null when [s] is not one. */
    fun normalize(s: String): String? {
        val t = s.trim()
        val uri = runCatching { java.net.URI(t) }.getOrNull() ?: return null
        if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        return if (t.endsWith("/")) t else "$t/"
    }

    /** `DSH_PLUGIN_REGISTRY` / `DSH_PLUGIN_FALLBACK_REGISTRIES` for bundle-android's plugin-manager row. */
    fun env(plan: List<String>): Map<String, String> = if (plan.isEmpty()) emptyMap() else mapOf(
        "DSH_PLUGIN_REGISTRY" to plan.first(),
        "DSH_PLUGIN_FALLBACK_REGISTRIES" to plan.drop(1).joinToString(","),
    )
}

/**
 * What the extensions settings contribute to an engine start, for EngineManager: the MCP rows of the
 * app's settings layer, and environment (registry plan, MCP header secrets). Both feed the profile
 * fingerprint, so a change restarts the engine when it is idle, like a provider change.
 */
object ExtensionsEngineInputs {
    /** [dshHome]: `files/dsh-home` (stdio servers live under its `mcp-servers/`). */
    fun patchRows(servers: List<McpServer>, dshHome: String): String = McpRows.render(servers, "$dshHome/mcp-servers")

    fun env(mode: RegistryMode, custom: String, servers: List<McpServer>, readSecret: (String) -> String?,
            locale: Locale = Locale.getDefault(), tz: String = java.util.TimeZone.getDefault().id): Map<String, String> =
        PluginRegistries.env(PluginRegistries.plan(mode, custom, locale, tz)) + McpRows.secretEnv(servers, readSecret)
}

/**
 * 安全模式启动 (§7.6 item 4): the next engine start carries `DSH_ANDROID_SAFE_MODE`, which makes
 * bundle-android pause every third-party bundle before booting: `1` when the user asked (engine
 * failure card, 设置 → 扩展), `crash-loop` automatically while the crash breaker is open and
 * third-party bundles are installed. The engine records which, and the banner says it.
 *
 * Only an engine that did not come up or died counts toward the breaker (EngineManager's start
 * failures and unexpected exits); an `android/ext/…` answer the app cannot read is `bad-result`
 * on the 扩展 page and never reaches RestartPolicy or this request.
 */
object SafeModeRequest {
    const val ENV = "DSH_ANDROID_SAFE_MODE"
    const val USER = "1"
    const val CRASH_LOOP = "crash-loop"
    @Volatile private var requested = false

    fun request() { requested = true }

    /** Consumes a pending request ([USER]); else [CRASH_LOOP] when [breakerOpen] and the profile has third-party bundles; else null. */
    fun take(context: Context, breakerOpen: Boolean): String? = take(breakerOpen) { hasThirdPartyBundles(File(context.filesDir, "dsh-home/profiles/android/package.json")) }

    internal fun take(breakerOpen: Boolean, hasPlugins: () -> Boolean): String? {
        if (requested) { requested = false; return USER }
        return if (breakerOpen && hasPlugins()) CRASH_LOOP else null
    }

    /** The profile's package.json lists installed plugins as `dependencies` (dsh plugin-manager's layout, §2.2). */
    fun hasThirdPartyBundles(packageJson: File): Boolean = runCatching {
        if (!packageJson.isFile) return false
        val root = RpcJson.parseToJsonElement(packageJson.readText()) as? JsonObject ?: return false
        (root["dependencies"] as? JsonObject)?.isNotEmpty() == true
    }.getOrDefault(false)
}
