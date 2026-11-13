package io.github.timhub135.dsh.core.engine

/**
 * Proxy parity between the app and the engine. HttpURLConnection (获取模型列表) follows Android's
 * proxy for the default network (Wi-Fi proxy, global proxy, a VPN app's proxy mode); Node's fetch
 * ignores it. So without this a connection test passes while every chat request goes direct and fails.
 *
 * The engine gets the proxy as HTTP_PROXY / HTTPS_PROXY / NO_PROXY: dsh's launcher
 * (`@deepseek-ai/dsh-http-proxy`) installs undici's global dispatcher from them at boot, and
 * `--use-env-proxy` covers `node:http(s)`; spawned shells (curl, git) read the same variables.
 * Android's semantics are mirrored: everything goes through the proxy except the exclusion list.
 * Its globs (`192.168.*`) are matched by js patch 15; `*.example.com` and plain hosts natively.
 * Loopback is always excluded (dsh adds it too; listed here for child processes).
 * A PAC proxy is not applied (logged). The proxy is read at engine start; a change applies on the
 * next start.
 */
object EngineProxy {
    data class Proxy(val host: String, val port: Int, val exclusions: List<String>)

    val ENV_NAMES = listOf("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy", "NODE_USE_ENV_PROXY")
    private val LOOPBACK = listOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** The engine environment for [proxy]; empty when there is none. */
    fun env(proxy: Proxy?): Map<String, String> {
        if (proxy == null || proxy.host.isBlank() || proxy.port !in 1..65535) return emptyMap()
        val host = proxy.host.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        val url = "http://$host:${proxy.port}"
        val exclusions = proxy.exclusions.flatMap { it.split(',', '|', ' ') }.map { it.trim() }.filter { it.isNotEmpty() }
        val noProxy = (exclusions + LOOPBACK.filter { l -> exclusions.none { it.equals(l, ignoreCase = true) } }).joinToString(",")
        // Both casings: undici reads the lowercase name first, so a stale lowercase value must not win.
        return linkedMapOf(
            "HTTP_PROXY" to url, "HTTPS_PROXY" to url, "NO_PROXY" to noProxy,
            "http_proxy" to url, "https_proxy" to url, "no_proxy" to noProxy,
            // Ignored by the engine itself under AT_SECURE (hence --use-env-proxy in argv); for Node children.
            "NODE_USE_ENV_PROXY" to "1",
        )
    }

    /** `http.nonProxyHosts` as Android sets it (`|`-separated), for the system-property fallback. */
    fun parseNonProxyHosts(value: String?): List<String> = value.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
}
