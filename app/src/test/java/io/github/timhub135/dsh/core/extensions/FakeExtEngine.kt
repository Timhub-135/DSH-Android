package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.JsonRpcException
import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-memory `android-extensions` engine for tests: answers `android/ext/…` from handlers the
 * test installs (JSON text or a lambda), records every call, and can hold a call until released.
 * Unknown methods fail like an engine without the method (-32601).
 */
class FakeExtEngine : ExtRpc {
    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val handlers = HashMap<String, suspend (JsonObject) -> JsonElement>()
    private val gates = HashMap<String, CompletableDeferred<Unit>>()

    fun on(method: String, json: String) { handlers[method] = { RpcJson.parseToJsonElement(json) } }
    fun on(method: String, handler: suspend (JsonObject) -> JsonElement) { handlers[method] = handler }
    fun fail(method: String, code: Int, message: String, data: String? = null) {
        handlers[method] = { throw JsonRpcException(code, message, data?.let { RpcJson.parseToJsonElement(it) }) }
    }
    /** The next calls of [method] wait until [release]. */
    fun hold(method: String) { gates[method] = CompletableDeferred() }
    fun release(method: String) { gates.remove(method)?.complete(Unit) }

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        gates[method]?.await()
        val h = handlers[method] ?: throw JsonRpcException(-32601, "Method not found: $method")
        return h(params)
    }

    fun count(method: String) = calls.count { it.first == method }
    fun last(method: String): JsonObject = calls.last { it.first == method }.second
    fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        fun list(vararg plugins: String, extra: String = "") = """{"plugins":[${plugins.joinToString(",")}],"dshVersion":"0.2.1-rc.5","registries":["https://registry.npmjs.org/"]$extra}"""
        fun plugin(name: String, status: String = "active", enabled: Boolean = true, extra: String = "") =
            """{"name":"$name","title":"T-$name","version":"1.0.0","source":{"kind":"npm","spec":"$name"},"enabled":$enabled,"status":"$status","rows":[]$extra}"""
    }
}

/** Polls [cond] until true (real time; the code under test runs on Dispatchers.Default). */
suspend fun eventually(timeoutMs: Long = 3_000, cond: () -> Boolean) {
    withTimeout(timeoutMs) { while (!cond()) delay(5) }
}
