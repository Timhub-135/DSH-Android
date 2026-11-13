package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * An MCP server the user added in 设置 → 扩展 → MCP 服务 (PLUGINS-DESIGN §11). The app owns these:
 * stored in DataStore (`mcp_servers_json`), header values in SecretStore (`mcp:<id>:<header>`),
 * and rendered into the app's settings layer as one `@deepseek-ai/dsh-mcp-client` row each.
 */
@Serializable
data class McpServer(
    /** Lowercase `[a-z0-9_]` id; tools appear as `mcp__<id>__<tool>`. */
    val id: String,
    val name: String = id,
    val kind: String = KIND_REMOTE,
    /** [KIND_REMOTE]: the Streamable HTTP endpoint. */
    val url: String? = null,
    /** Header names; their values live in SecretStore only. */
    val headerNames: List<String> = emptyList(),
    /** [KIND_NPM]: the npm package (installed into `dsh-home/mcp-servers/`). */
    val `package`: String? = null,
    /** [KIND_NPM]: the bin script relative to `dsh-home/mcp-servers/node_modules` (`<package>/<path>`, from `mcp/install`). */
    val bin: String? = null,
    val args: List<String> = emptyList(),
    /** Plain (non-secret) environment for a stdio server. */
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val askBeforeCall: Boolean = false,
) {
    val local: Boolean get() = kind == KIND_NPM
    fun secretName(header: String) = "mcp:$id:$header"
    /** `DSH_MCP_<ID>_<HEADER>`: the engine environment variable carrying a header value. */
    fun headerEnv(header: String) = "DSH_MCP_" + envPart(id) + "_" + envPart(header)

    companion object {
        const val KIND_REMOTE = "remote"
        const val KIND_NPM = "npm"
        /** Each local server is a resident node process; more would hit Android 12+'s phantom-process limit and memory. */
        const val MAX_LOCAL_ENABLED = 4
        private fun envPart(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "_")

        /** A free id derived from [name]. */
        fun newId(name: String, taken: Collection<String>): String {
            val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifEmpty { "server" }
            var id = base
            var n = 2
            while (id in taken) id = "${base}_${n++}"
            return id
        }
    }
}

/** One entry of a pasted `mcpServers` JSON, classified (§11.1). */
sealed interface McpCandidate {
    val name: String

    /** A remote Streamable HTTP server: [headers] carry the pasted values (to be stored as secrets). */
    data class Remote(override val name: String, val url: String, val headers: Map<String, String>) : McpCandidate

    /** `npx -y <package> [args]`: a JS stdio server from npm. */
    data class Npm(override val name: String, val pkg: String, val args: List<String>, val env: Map<String, String>) : McpCandidate

    /** Cannot run on a phone; [code] is `mcp-sse-only`, `mcp-runtime` or `mcp-local-path`, [runtime] names what it needs. */
    data class Unsupported(override val name: String, val code: String, val runtime: String? = null) : McpCandidate
}

object McpImport {
    private val RUNTIMES = mapOf(
        "uvx" to "Python", "uv" to "Python", "python" to "Python", "python3" to "Python", "pip" to "Python", "pipx" to "Python",
        "docker" to "Docker", "podman" to "Docker", "deno" to "Deno", "bun" to "Bun", "bunx" to "Bun",
        "java" to "Java", "go" to "Go", "cargo" to "Rust", "dotnet" to ".NET", "ruby" to "Ruby", "php" to "PHP",
    )

    /**
     * Parses Claude Desktop / Cursor style `{"mcpServers": {name: {...}}}`, a bare `{name: {...}}`
     * map, or a single server object. Throws [IllegalArgumentException] when it is not JSON.
     */
    fun parse(text: String): List<McpCandidate> {
        val root = runCatching { RpcJson.parseToJsonElement(text.trim()) }.getOrElse { throw IllegalArgumentException("not JSON") } as? JsonObject
            ?: throw IllegalArgumentException("not a JSON object")
        val servers = (root["mcpServers"] ?: root["servers"]) as? JsonObject
        return when {
            servers != null -> servers.mapNotNull { (k, v) -> (v as? JsonObject)?.let { classify(k, it) } }
            looksLikeServer(root) -> listOf(classify((root["name"] as? JsonPrimitive)?.contentOrNull ?: "server", root))
            else -> root.mapNotNull { (k, v) -> (v as? JsonObject)?.takeIf(::looksLikeServer)?.let { classify(k, it) } }
        }
    }

    private fun looksLikeServer(o: JsonObject) = "url" in o || "command" in o || "serverUrl" in o

    fun classify(name: String, o: JsonObject): McpCandidate {
        val type = o.str("type") ?: o.str("transport")
        val url = o.str("url") ?: o.str("serverUrl")
        if (url != null) {
            val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            if (type == "sse" || path.trimEnd('/').endsWith("/sse")) return McpCandidate.Unsupported(name, "mcp-sse-only")
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            return McpCandidate.Remote(name, url, headers)
        }
        val command = o.str("command") ?: return McpCandidate.Unsupported(name, "mcp-runtime")
        val args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val env = (o["env"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        val exe = command.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe").removeSuffix(".cmd")
        return when (exe) {
            "npx", "pnpx" -> {
                val rest = args.dropWhile { it.startsWith("-") }
                val pkg = rest.firstOrNull()
                if (pkg == null || InstallSource.npmSpec(pkg) == null) McpCandidate.Unsupported(name, "mcp-runtime")
                else McpCandidate.Npm(name, pkg, rest.drop(1), env)
            }
            // pnpm dlx / npm exec / yarn dlx <pkg>
            "pnpm", "npm", "yarn" -> {
                val i = args.indexOfFirst { it == "dlx" || it == "exec" || it == "x" }
                val rest = if (i >= 0) args.drop(i + 1).dropWhile { it.startsWith("-") } else emptyList()
                val pkg = rest.firstOrNull()
                if (pkg == null || InstallSource.npmSpec(pkg) == null) McpCandidate.Unsupported(name, "mcp-runtime", "npm")
                else McpCandidate.Npm(name, pkg, rest.drop(1), env)
            }
            "node", "nodejs" -> McpCandidate.Unsupported(name, "mcp-local-path")
            else -> McpCandidate.Unsupported(name, "mcp-runtime", RUNTIMES[exe] ?: command)
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

object McpRows {
    const val MODULE = "@deepseek-ai/dsh-mcp-client"
    /** Set by the engine (bundle-android main.mjs) to its own preload; stdio children need it like pnpm does (§6.1). */
    const val ENV_PRELOAD = "DSH_ANDROID_ENV_CJS"
    /**
     * The node launcher: `DSH_NODE_BIN`, else `libdshnode.so` in the app's native dir (both in the
     * engine environment). A `!!js` expression, evaluated by the Loader when the row starts.
     */
    const val NODE_EXPR = "process.env.DSH_NODE_BIN || (process.env.DSH_NATIVE_DIR + '/libdshnode.so')"
    /** bundle-android's preload (restores TMPDIR / CA / TZ under AT_SECURE), next to the engine entry. */
    const val PRELOAD_EXPR = "process.env.$ENV_PRELOAD || process.argv[1].replace(/[^/]*\$/, 'android-env.cjs')"

    /**
     * The row config `android/ext/mcp/test` connects with and the overlay renders: real header
     * values for a test ([headerValue] non-null), env references for the overlay.
     */
    fun testConfig(s: McpServer, headerValue: (String) -> String?): JsonObject = buildJsonObject {
        put("serverName", s.id)
        put("kind", s.kind)
        if (s.local) {
            put("transport", "stdio")
            s.bin?.let { put("bin", it) }
            s.`package`?.let { put("package", it) }
            putJsonArray("args") { s.args.forEach { add(JsonPrimitive(it)) } }
            if (s.env.isNotEmpty()) putJsonObject("env") { s.env.forEach { (k, v) -> put(k, v) } }
        } else {
            put("transport", "streamable-http")
            put("url", s.url.orEmpty())
            putJsonObject("headers") { s.headerNames.forEach { h -> headerValue(h)?.let { put(h, it) } } }
        }
    }

    /**
     * `insert` rows for the enabled servers, appended to the app's settings layer (§11.2). Values
     * are JSON-quoted (valid YAML); secrets appear only as `!!js process.env.<NAME>`. Local servers
     * beyond [McpServer.MAX_LOCAL_ENABLED] are left out.
     */
    fun render(servers: List<McpServer>, mcpRoot: String): String {
        val local = servers.filter { it.enabled && it.local && it.bin != null }.take(McpServer.MAX_LOCAL_ENABLED)
        val remote = servers.filter { it.enabled && !it.local && !it.url.isNullOrBlank() }
        val rows = (remote + local).sortedBy { servers.indexOf(it) }
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("- insert:\n")
        rows.forEach { s ->
            sb.append("    - id: ").append(q("mcp-${s.id}")).append('\n')
            sb.append("      name: ").append(q(MODULE)).append('\n')
            sb.append("      config:\n")
            sb.append("        serverName: ").append(q(s.id)).append('\n')
            if (s.local) {
                sb.append("        transport: stdio\n")
                sb.append("        command: !!js ").append(q(NODE_EXPR)).append('\n')
                sb.append("        args:\n")
                sb.append("          - \"--require\"\n")
                sb.append("          - !!js ").append(q(PRELOAD_EXPR)).append('\n')
                (listOf(binPath(mcpRoot, s.bin!!)) + s.args).forEach { sb.append("          - ").append(q(it)).append('\n') }
                if (s.env.isNotEmpty()) {
                    sb.append("        env:\n")
                    s.env.forEach { (k, v) -> sb.append("          ").append(q(k)).append(": ").append(q(v)).append('\n') }
                }
            } else {
                sb.append("        transport: streamable-http\n")
                sb.append("        url: ").append(q(s.url!!.trim())).append('\n')
                if (s.headerNames.isNotEmpty()) {
                    sb.append("        headers:\n")
                    s.headerNames.forEach { h -> sb.append("          ").append(q(h)).append(": !!js process.env.").append(s.headerEnv(h)).append('\n') }
                }
            }
            sb.append("        failOnStartupError: false\n")
        }
        return sb.toString()
    }

    /** Header values for the engine environment (name → value), read from SecretStore. */
    fun secretEnv(servers: List<McpServer>, read: (String) -> String?): Map<String, String> =
        servers.filter { it.enabled && !it.local }.flatMap { s -> s.headerNames.mapNotNull { h -> read(s.secretName(h))?.let { s.headerEnv(h) to it } } }.toMap()

    /** Absolute path of an installed server's bin ([bin] as `mcp/install` reports it, relative to node_modules). */
    fun binPath(mcpRoot: String, bin: String): String = if (bin.startsWith("/")) bin else mcpRoot.trimEnd('/') + "/node_modules/" + bin.trimStart('/')

    private fun q(s: String) = JsonPrimitive(s).toString()
}

/**
 * Appends [rows] (YAML list items) to a rendered patch layer: the generated layer is `[]` when it
 * has nothing of its own, which must become a list the rows can join.
 */
fun appendPatchRows(base: String, rows: String): String {
    if (rows.isBlank()) return base
    val lines = base.trimEnd('\n').lines()
    val body = lines.dropWhile { it.startsWith("#") }
    val header = lines.takeWhile { it.startsWith("#") }
    val keep = if (body.all { it.isBlank() || it.trim() == "[]" }) emptyList() else body
    return (header + keep).joinToString("\n").let { if (it.isEmpty()) it else "$it\n" } + rows.trimEnd('\n') + "\n"
}
