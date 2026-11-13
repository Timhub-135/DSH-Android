package io.github.timhub135.dsh.core.engine

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class JsonRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

val RpcJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Newline-delimited JSON-RPC 2.0 over a byte-stream pair. Used for both the dsh SDK channel
 * (stdio) and the capability channel (socketpair). See docs/ENGINE-CONTRACT.md.
 *
 * Notifications are delivered to [onNotification] one at a time, in wire order (dsh's
 * `session.event` stream and its `session.status` transitions are only meaningful in order);
 * requests from the peer run concurrently. [onClosed] fires after the last notification that
 * arrived before end-of-stream was handled, so the owner sees a dead channel's tail first.
 *
 * Each incoming request runs in its own coroutine under [handlers]; see [EngineCalls] for the
 * cancellation contract (all in-flight handlers are cancelled when the channel or its engine
 * generation ends, and a cancelled request is never answered).
 */
class JsonRpcPeer(
    private val name: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onRequest: suspend (method: String, params: JsonObject) -> JsonElement,
    private val onNotification: suspend (method: String, params: JsonObject) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()
    private val notifications = Channel<Pair<String, JsonObject>>(Channel.UNLIMITED)
    /** Completed once the channel closed and every notification received before that was handled. */
    val closed = CompletableDeferred<Unit>()
    /** Parent of every in-flight incoming-request handler (child of [scope], so it never outlives it). */
    private val handlers = SupervisorJob(scope.coroutineContext[Job])
    private val handlerScope = scope + handlers + CoroutineExceptionHandler { _, e -> Log.e(TAG, "$name handler crashed", e) }

    /**
     * Cancels every in-flight incoming request with [cause] (their responses are dropped). Called
     * when the engine generation ends; also happens on its own once the read side reaches EOF.
     */
    fun cancelRequests(cause: EngineCallCancelled) {
        handlers.cancel(cause)
    }

    fun start() {
        scope.launch {
            try {
                for ((method, params) in notifications) {
                    runCatching { onNotification(method, params) }.onFailure { Log.e(TAG, "$name notify $method", it) }
                }
            } finally {
                closed.complete(Unit)
                onClosed()
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                input.bufferedReader().useLines { lines -> lines.forEach { if (it.isNotBlank()) dispatch(it) } }
            } catch (e: Exception) {
                Log.w(TAG, "$name read loop ended", e)
            } finally {
                pending.values.forEach { it.completeExceptionally(JsonRpcException(-32000, "$name channel closed")) }
                pending.clear()
                // Nobody is left to read a response: stop the handlers (and the device work they drive).
                cancelRequests(EngineCallCancelled("$name channel closed"))
                notifications.close()
            }
        }
    }

    suspend fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            return deferred.await()
        } catch (e: IOException) {
            throw JsonRpcException(-32000, "$name channel closed: ${e.message}")
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JsonElement = JsonObject(emptyMap())) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    private suspend fun send(msg: JsonObject) = writeLock.withLock {
        val bytes = (msg.toString() + "\n").toByteArray()
        kotlinx.coroutines.withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
    }

    private fun dispatch(line: String) {
        val msg = runCatching { RpcJson.parseToJsonElement(line).jsonObject }.getOrElse {
            Log.w(TAG, "$name non-JSON line: ${line.take(200)}"); return
        }
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.contentOrNull
        val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
        when {
            method != null && id != null && id !is JsonNull -> handlerScope.launch(EngineCalls.Call(name, method)) {
                val response = try {
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", onRequest(method, params)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: JsonRpcException) {
                    errorFrame(id, e.code, e.message ?: "error")
                } catch (e: Throwable) {
                    // Throwable, not Exception: an Error (e.g. NoSuchMethodError from an API missing on an
                    // older Android, #8) must still be answered, or the engine waits on this call forever.
                    Log.e(TAG, "$name handler $method failed", e)
                    errorFrame(id, -32603, e.message ?: e.javaClass.simpleName)
                }
                // A handler that finished after its generation ended (or that swallowed the
                // cancellation) has nobody to answer.
                ensureActive()
                try { send(response) } catch (e: IOException) { Log.w(TAG, "$name response to $method dropped: channel closed") }
            }
            method != null -> notifications.trySend(method to params)
            id != null -> {
                val deferred = pending.remove((id as? JsonPrimitive)?.longOrNull ?: return) ?: return
                val err = msg["error"] as? JsonObject
                if (err != null) deferred.completeExceptionally(
                    JsonRpcException(err["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                        err["message"]?.jsonPrimitive?.contentOrNull ?: "error", err["data"]))
                else deferred.complete(msg["result"] ?: JsonNull)
            }
        }
    }

    private fun errorFrame(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private companion object { const val TAG = "JsonRpc" }
}
