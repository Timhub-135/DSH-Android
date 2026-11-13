package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.JsonRpcException
import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One request on the SDK channel; the app's EngineManager peer in production, a fake engine in tests. */
fun interface ExtRpc {
    suspend fun call(method: String, params: JsonObject): JsonElement
}

/**
 * A failed `android/ext/…` call, already in user terms: the engine's JSON-RPC error `data` when it
 * carries a [UserError] (§15), else a generic one built from the message.
 */
class ExtException(val error: UserError, cause: Throwable? = null) : Exception(error.message, cause)

/** Method names of PLUGINS-DESIGN §14 (registered by `@dsh-android/plugin-extensions`, row `android-extensions`). */
object ExtMethods {
    const val LIST = "android/ext/list"
    const val RESOLVE = "android/ext/resolve"
    const val INSPECT = "android/ext/inspect"
    const val INSTALL = "android/ext/install"
    const val CANCEL = "android/ext/cancel"
    const val SET_ENABLED = "android/ext/setEnabled"
    const val SET_ROW_ENABLED = "android/ext/setRowEnabled"
    const val REMOVE = "android/ext/remove"
    const val UPDATES = "android/ext/updates"
    const val UPDATE = "android/ext/update"
    const val DETAIL = "android/ext/detail"
    const val CONFIG_GET = "android/ext/config/get"
    const val CONFIG_SET = "android/ext/config/set"
    const val ASK_BEFORE_CALL = "android/ext/askBeforeCall"
    const val RESUME = "android/ext/resume"
    const val LOG = "android/ext/log"
    const val MARKET_INDEXES = "android/ext/market/indexes"
    const val MARKET_SET_INDEXES = "android/ext/market/setIndexes"
    const val MARKET_SEARCH = "android/ext/market/search"
    const val MARKET_ENTRY = "android/ext/market/entry"
    const val SKILLS_LIST = "android/ext/skills/list"
    const val SKILLS_PREVIEW = "android/ext/skills/preview"
    const val SKILLS_IMPORT = "android/ext/skills/import"
    const val SKILLS_SET_ENABLED = "android/ext/skills/setEnabled"
    const val SKILLS_REMOVE = "android/ext/skills/remove"
    const val MCP_TEST = "android/ext/mcp/test"
    const val MCP_STATUS = "android/ext/mcp/status"
    const val MCP_INSTALL = "android/ext/mcp/install"
    const val MCP_REMOVE = "android/ext/mcp/remove"

    /** Notifications (engine → app). */
    const val N_PROGRESS = "android.ext.progress"
    const val N_CHANGED = "android.ext.changed"
    const val N_NOTICE = "android.ext.notice"
}

/** Error codes the app itself produces (the rest come from the engine, §15). */
object ExtCodes {
    /** The running engine has no `android-extensions` row (an engine.zip from before plugin support). */
    const val UNSUPPORTED_ENGINE = "unsupported-engine"
    const val ENGINE_UNAVAILABLE = "engine-unavailable"
    const val UNKNOWN = "error"
    /** The engine answered, but the app could not read the result (a shape it does not know). Not an engine failure. */
    const val BAD_RESULT = "bad-result"
    /** One plugin's entry in an otherwise readable list could not be read. */
    const val BAD_ENTRY = "bad-entry"
}

/** Typed `android/ext/…` calls. Stateless; [ExtensionsStore] owns caching and restarts. */
class ExtensionsClient(private val rpc: ExtRpc) {
    private suspend fun <T> call(method: String, de: DeserializationStrategy<T>, params: JsonObject = JsonObject(emptyMap())): T {
        val result = try {
            rpc.call(method, params)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ExtException) {
            throw e
        } catch (e: JsonRpcException) {
            throw ExtException(userErrorOf(e), e)
        } catch (e: Exception) {
            throw ExtException(UserError(ExtCodes.ENGINE_UNAVAILABLE, e.message ?: e.javaClass.simpleName), e)
        }
        return try {
            RpcJson.decodeFromJsonElement(de, result)
        } catch (e: Exception) {
            throw ExtException(UserError(ExtCodes.BAD_RESULT, detail = "unexpected $method result: ${e.message}"), e)
        }
    }

    suspend fun list(): ExtList = call(ExtMethods.LIST, ExtList.serializer())
    suspend fun resolve(input: String): ResolveResult = call(ExtMethods.RESOLVE, ResolveResult.serializer(), obj { put("input", input) })
    suspend fun inspect(input: String): InspectResult = call(ExtMethods.INSPECT, InspectResult.serializer(), obj { put("input", input) })
    suspend fun install(input: String, requestId: String, force: Boolean = false): ChangeView =
        call(ExtMethods.INSTALL, InstallResult.serializer(), obj { put("input", input); put("requestId", requestId); if (force) put("force", true) }).result
    suspend fun cancel(requestId: String): Boolean =
        call(ExtMethods.CANCEL, JsonObject.serializer(), obj { put("requestId", requestId) })["cancelled"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
    suspend fun setEnabled(name: String, enabled: Boolean): ChangeView = call(ExtMethods.SET_ENABLED, ChangeView.serializer(), obj { put("name", name); put("enabled", enabled) })
    suspend fun setRowEnabled(entryId: String, enabled: Boolean): ChangeView = call(ExtMethods.SET_ROW_ENABLED, ChangeView.serializer(), obj { put("entryId", entryId); put("enabled", enabled) })
    suspend fun remove(name: String): ChangeView = call(ExtMethods.REMOVE, ChangeView.serializer(), obj { put("name", name) })
    suspend fun updates(refresh: Boolean = false): UpdatesResult = call(ExtMethods.UPDATES, UpdatesResult.serializer(), obj { if (refresh) put("refresh", true) })
    suspend fun update(name: String, version: String? = null): ChangeView = call(ExtMethods.UPDATE, ChangeView.serializer(), obj { put("name", name); version?.let { put("version", it) } })
    suspend fun detail(name: String, language: String): DetailResult = call(ExtMethods.DETAIL, DetailResult.serializer(), obj { put("name", name); put("language", language) })
    suspend fun configGet(entryId: String): ConfigGetResult = call(ExtMethods.CONFIG_GET, ConfigGetResult.serializer(), obj { put("entryId", entryId) })
    suspend fun configSet(entryId: String, value: JsonElement): ConfigSetResult = call(ExtMethods.CONFIG_SET, ConfigSetResult.serializer(), obj { put("entryId", entryId); put("value", value) })
    /** [key]: a bundle name, or `mcp:<id>` for an MCP server. */
    suspend fun askBeforeCall(key: String, enabled: Boolean) { call(ExtMethods.ASK_BEFORE_CALL, JsonElement.serializer(), obj { put("key", key); put("enabled", enabled) }) }
    suspend fun resume(name: String): ChangeView = call(ExtMethods.RESUME, ChangeView.serializer(), obj { put("name", name) })
    suspend fun log(name: String? = null, requestId: String? = null): LogResult =
        call(ExtMethods.LOG, LogResult.serializer(), obj { name?.let { put("name", it) }; requestId?.let { put("requestId", it) } })

    suspend fun marketIndexes(): List<MarketIndexSummary> = call(ExtMethods.MARKET_INDEXES, IndexListDecoder)
    suspend fun setMarketIndexes(urls: List<String>) {
        call(ExtMethods.MARKET_SET_INDEXES, JsonElement.serializer(), obj { putJsonArray("urls") { urls.forEach { add(JsonPrimitive(it)) } } })
    }
    /** A failed read throws [ExtException] with the engine's UserError (`index-unreachable` names the sources tried). */
    suspend fun marketSearch(url: String, text: String?, offset: Int = 0, limit: Int = 30, category: String? = null, refresh: Boolean = false): MarketSearchResult =
        // The engine passes these on as dsh's MarketQuery (flat on the params).
        decoded(ExtMethods.MARKET_SEARCH, MarketSearchResult.serializer(), call(ExtMethods.MARKET_SEARCH, JsonElement.serializer(), obj {
            put("url", url)
            put("text", text?.trim().orEmpty())
            category?.let { put("category", it) }
            putJsonObject("sort") { put("kind", if (text.isNullOrBlank()) "index" else "relevance") }
            put("offset", offset); put("limit", limit)
            if (refresh) put("refresh", true)
        }).let(::checked))
    suspend fun marketEntry(url: String, name: String): MarketEntry? =
        checked(call(ExtMethods.MARKET_ENTRY, JsonElement.serializer(), obj { put("url", url); put("name", name) })).let { el ->
            // `{entry: …|null}`, or the entry itself.
            val e = if (el is JsonObject && "entry" in el) el["entry"] else el
            (e as? JsonObject)?.takeIf { "name" in it }?.let { RpcJson.decodeFromJsonElement(MarketEntry.serializer(), it) }
        }

    suspend fun skills(): SkillList = call(ExtMethods.SKILLS_LIST, SkillList.serializer())
    suspend fun skillPreview(input: String): SkillPreview = call(ExtMethods.SKILLS_PREVIEW, SkillPreview.serializer(), obj { put("input", input) })
    suspend fun skillImport(previewId: String, names: List<String>, overwrite: List<String>): SkillImportResult =
        call(ExtMethods.SKILLS_IMPORT, SkillImportResult.serializer(), obj {
            put("previewId", previewId)
            putJsonArray("names") { names.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("overwrite") { overwrite.forEach { add(JsonPrimitive(it)) } }
        })
    suspend fun skillSetEnabled(name: String, enabled: Boolean) { checked(call(ExtMethods.SKILLS_SET_ENABLED, JsonElement.serializer(), obj { put("name", name); put("enabled", enabled) })) }
    suspend fun skillRemove(name: String) { checked(call(ExtMethods.SKILLS_REMOVE, JsonElement.serializer(), obj { put("name", name) })) }

    /** [config]: one dsh-mcp-client row config (`transport`, `url`, `headers` with real values, or `command`/`args`). */
    suspend fun mcpTest(config: JsonObject): McpTestResult = call(ExtMethods.MCP_TEST, McpTestResult.serializer(), obj { put("config", config) })
    suspend fun mcpStatus(): McpStatusResult = call(ExtMethods.MCP_STATUS, McpStatusResult.serializer())
    suspend fun mcpInstall(pkg: String): McpInstallResult = call(ExtMethods.MCP_INSTALL, McpInstallResult.serializer(), obj { put("package", pkg) })
    suspend fun mcpRemove(pkg: String) { checked(call(ExtMethods.MCP_REMOVE, JsonElement.serializer(), obj { put("package", pkg) })) }

    private fun <T> decoded(method: String, de: DeserializationStrategy<T>, el: JsonElement): T = try {
        RpcJson.decodeFromJsonElement(de, el)
    } catch (e: Exception) {
        throw ExtException(UserError(ExtCodes.BAD_RESULT, detail = "unexpected $method result: ${e.message}"), e)
    }

    companion object {
        /** Methods that answer `{error: UserError}` instead of failing the request. */
        private fun checked(result: JsonElement): JsonElement {
            val err = (result as? JsonObject)?.get("error") as? JsonObject
            if (err != null && err["code"] != null) throw ExtException(RpcJson.decodeFromJsonElement(UserError.serializer(), err))
            return result
        }

        private inline fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

        /** The [UserError] an engine error carries in `data` (or `data.userError`), else one made from its message. */
        fun userErrorOf(e: JsonRpcException): UserError {
            val data = e.data as? JsonObject
            val ue = (data?.get("userError") as? JsonObject ?: data)?.takeIf { it["code"] != null }
            ue?.let { runCatching { return RpcJson.decodeFromJsonElement(UserError.serializer(), it) } }
            // -32601 Method not found: the engine predates plugin support.
            if (e.code == -32601) return UserError(ExtCodes.UNSUPPORTED_ENGINE, e.message ?: "method not found")
            return UserError(ExtCodes.UNKNOWN, e.message ?: "error")
        }
    }
}

/** `listIndexes` answers a bare array; some engines wrap it as `{indexes: [...]}`. */
private object IndexListDecoder : DeserializationStrategy<List<MarketIndexSummary>> {
    private val list = ListSerializer(MarketIndexSummary.serializer())
    override val descriptor = list.descriptor
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<MarketIndexSummary> {
        val el = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement()
        val arr = (el as? JsonObject)?.get("indexes") ?: el
        return decoder.json.decodeFromJsonElement(list, arr)
    }
}
