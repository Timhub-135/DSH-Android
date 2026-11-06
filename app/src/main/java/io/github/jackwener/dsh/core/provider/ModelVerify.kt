package io.github.jackwener.dsh.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * 测试: a provider counts as working only when one real chat request with a tool succeeds.
 * A listing (`/models`) proves the address and usually the key, not that chats work: the key may
 * lack chat scope, the model may not be enabled for the account, may not support tools, or the
 * gateway may break streaming. The probe sends what the engine sends (llm-service-lite's
 * chatRequest: POST {base}/chat/completions, stream + include_usage, bearer key, the harness
 * User-Agent) through HttpURLConnection, which takes the same system proxy the engine is given
 * (EngineRuntimeEnv), plus one no-argument tool the model is told to call. A Responses route (#59)
 * gets the engine's responsesRequest shape instead: POST {base}/responses with `instructions`,
 * `input` and a flat function tool.
 */

/** Why a model failed the connection test. [modelSpecific]: another model of the same provider may still work. */
sealed interface VerifyFailure {
    val modelSpecific: Boolean

    /** HTTP 401 or a key complaint: the key is wrong, expired or lacks scope. */
    data class KeyInvalid(val message: String) : VerifyFailure { override val modelSpecific get() = false }
    /** The account has not enabled (开通) the model or has no permission for it; [message] is the server's own text. */
    data class ModelNotAvailable(val message: String) : VerifyFailure { override val modelSpecific get() = true }
    data class ModelNotFound(val message: String) : VerifyFailure { override val modelSpecific get() = true }
    data class ToolsUnsupported(val message: String) : VerifyFailure { override val modelSpecific get() = true }
    /** The model answered but did not call the tool it was told to call; [reply] is the start of its text. */
    data class NoToolCall(val reply: String) : VerifyFailure { override val modelSpecific get() = true }
    /** Balance, quota or rate limit (402 / 429): the account, not the model. */
    data class Quota(val message: String) : VerifyFailure { override val modelSpecific get() = false }
    data class Network(val diagnosis: NetDiagnosis) : VerifyFailure { override val modelSpecific get() = false }
    /**
     * Any other answer. A plain 400/422 rejected this request for this model (try another); 5xx,
     * a bare 404 (wrong address) or an unreadable response stop the test.
     */
    data class Server(val status: Int, val message: String) : VerifyFailure {
        override val modelSpecific get() = status == 400 || status == 422
    }
}

object VerifyErrors {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val KEY = listOf("invalid api key", "invalid_api_key", "incorrect api key", "api key not valid", "invalid authentication",
        "authentication fails", "authenticationerror", "invalid token", "token is invalid", "unauthorized", "api key is invalid",
        "令牌已过期", "验证不正确", "身份验证失败", "鉴权失败", "api key 无效", "密钥无效")
    private val TOOLS = listOf("does not support tools", "not support tools", "tools are not supported", "tools is not supported",
        "tool use is not supported", "tool calling is not supported", "tool calls are not supported", "does not support tool",
        "function calling is not", "function call is not supported", "does not support function", "not support function",
        "enable-auto-tool-choice", "tool-call-parser", "不支持工具", "不支持函数", "不支持 function", "不支持function")
    private val NOT_FOUND = listOf("model_not_found", "model not found", "model does not exist", "model not exist", "model_not_exist",
        "no such model", "unknown model", "invalid model", "not found the model", "invalidendpointormodel", "invalidendpoint",
        "模型不存在", "找不到模型")
    /** The account has to switch the model on first (Ark ModelNotOpen, 未开通): checked before "not found", which Ark's type also says. */
    private val NOT_ACTIVATED = listOf("not activated", "not been activated", "has not activated", "activate the model", "modelnotopen",
        "not enabled", "not subscribed", "未开通", "请开通", "需要开通", "开通后")
    private val NOT_AVAILABLE = listOf("not open", "no access", "do not have access", "don't have access", "does not have access", "no permission", "permission denied",
        "not authorized", "access denied", "accessdenied", "not enabled", "not available", "disabled", "insufficient permission",
        "not subscribed", "forbidden", "not allowed",
        "未开通", "无权限", "无权访问", "没有权限", "未授权", "暂未开放", "未开放", "开通", "无访问权限")
    private val QUOTA = listOf("insufficient balance", "insufficient_quota", "insufficient quota", "exceeded_current_quota", "quota",
        "balance", "billing", "rate limit", "rate_limit", "too many requests", "余额不足", "欠费", "额度", "限流", "频率")
    private val REGION = listOf("country", "region", "territory", "地区")

    /**
     * The server's own error text: `error.message` (OpenAI, DeepSeek, Kimi, Zhipu, Ark), `message`
     * (SiliconFlow), `error` as a string, `msg`, `detail`, MiniMax `base_resp.status_msg`; else
     * the body itself (HTML stripped), trimmed to 300 characters.
     */
    fun serverMessage(body: String): String {
        val text = body.trim()
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (o != null) {
            val err = o["error"]
            val msg = (err as? JsonObject)?.let { str(it["message"]) ?: str(it["msg"]) } ?: str(err) ?: str(o["message"]) ?: str(o["msg"])
                ?: str(o["detail"]) ?: (o["detail"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.let { d -> str(d["msg"]) } }
                ?: str(o["error_msg"]) ?: (o["base_resp"] as? JsonObject)?.let { str(it["status_msg"]) }
            if (msg != null) return msg.take(300)
        }
        val plain = if (text.startsWith("<")) text.replace(Regex("<[^>]*>"), " ") else text
        return plain.replace(Regex("\\s+"), " ").trim().take(300)
    }

    /** Error code / type next to the message (`model_not_found`, `ModelNotOpen`, `1211`…), for matching only. */
    private fun errorTags(body: String): String {
        val o = runCatching { json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull() ?: return ""
        val err = o["error"] as? JsonObject
        return listOfNotNull(err?.get("code"), err?.get("type"), o["code"], o["type"]).mapNotNull { str(it) }.joinToString(" ")
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

    /** Classify a failed answer: [status] is the HTTP status (an error frame inside a 200 stream is classified as 400). */
    fun classify(status: Int, body: String): VerifyFailure {
        val message = serverMessage(body).ifEmpty { "HTTP $status" }
        val text = (message + " " + errorTags(body)).lowercase()
        fun has(words: List<String>) = words.any { it in text }
        // Codes some vendors put in the body instead of the status: Zhipu 1211 (model missing), 1113 (arrears), 1220 (no permission);
        // SiliconFlow 20012 (model missing), 30011 (model not for this account).
        val tags = errorTags(body).split(' ').toSet()
        return when {
            status == 401 -> VerifyFailure.KeyInvalid(message)
            status == 402 || status == 429 || "1113" in tags -> VerifyFailure.Quota(message)
            status in 400..499 && has(TOOLS) -> VerifyFailure.ToolsUnsupported(message)
            status in 400..499 && has(NOT_ACTIVATED) -> VerifyFailure.ModelNotAvailable(message)
            // A 403 that says access is denied is about the account, even with a model_not_found code (OpenAI projects); js patch 18 agrees.
            status == 403 && !has(KEY) && !has(REGION) && has(NOT_AVAILABLE) -> VerifyFailure.ModelNotAvailable(message)
            status in 400..499 && ("1211" in tags || "20012" in tags || has(NOT_FOUND) || modelNotFoundPhrase(message.lowercase())) -> VerifyFailure.ModelNotFound(message)
            status in 400..499 && has(KEY) -> VerifyFailure.KeyInvalid(message)
            status == 403 && has(REGION) -> VerifyFailure.Server(status, message)
            status in 400..499 && ("1220" in tags || "30011" in tags || has(NOT_AVAILABLE)) -> VerifyFailure.ModelNotAvailable(message)
            status == 403 -> VerifyFailure.ModelNotAvailable(message)
            status in 400..499 && has(QUOTA) -> VerifyFailure.Quota(message)
            else -> VerifyFailure.Server(status, message)
        }
    }

    /** `model "x" not found, try pulling it first` (Ollama), `The model x does not exist…` and similar. */
    private fun modelNotFoundPhrase(text: String) = "model" in text && ("not found" in text || "does not exist" in text || "not exist" in text || "不存在" in text)

    /** A failure without an HTTP answer: network causes via [NetDiagnose], anything else as an unreadable response. */
    fun ofThrowable(t: Throwable): VerifyFailure =
        if (t is HttpStatusException) classify(t.status, t.body)
        else NetDiagnose.ofException(t)?.let { VerifyFailure.Network(it) } ?: VerifyFailure.Server(0, t.message ?: t.javaClass.simpleName)
}

// ── candidates ──

private val NON_CHAT_TOKENS = listOf("embed", "rerank", "tts", "whisper", "asr", "image", "imagen", "dall", "moderation", "ocr", "bge",
    "speech", "transcribe", "realtime", "audio", "sora", "veo", "video", "flux", "kolors", "seedream", "seedance", "cosyvoice",
    "sensevoice", "stable-diffusion", "sdxl", "guard")
private val NON_CHAT_TYPES = setOf("embedding", "embeddings", "rerank", "reranker", "image", "images", "audio", "tts", "stt", "asr",
    "moderation", "video", "speech", "transcription", "image-generation", "text-to-image")

/** Name looks like an embedding / rerank / speech / image / moderation model. Tokens are split at `/ - _ . :`. */
fun looksNonChat(id: String): Boolean {
    val lower = id.lowercase()
    if ("stable-diffusion" in lower) return true
    val tokens = lower.split('/', '-', '_', '.', ':', ' ').filter { it.isNotEmpty() }
    return tokens.any { t -> NON_CHAT_TOKENS.any { t.startsWith(it) } }
}

/** What a listing entry says about its kind (`type`, Together/LM Studio; `architecture.output_modalities`, OpenRouter). */
internal fun listedAsChat(o: JsonObject): Boolean? {
    (o["type"] as? JsonPrimitive)?.content?.lowercase()?.let { t ->
        if (t in NON_CHAT_TYPES) return false
        if (t in setOf("chat", "llm", "vlm", "language", "text")) return true
    }
    val out = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    if (out != null && out.isNotEmpty()) return "text" in out
    return null
}

/**
 * Tool support as the listing marks it: OpenRouter `supported_parameters`, Ollama-style
 * `capabilities` array, Mistral `capabilities.function_calling`, Fireworks `supports_tools`.
 */
internal fun listedWithTools(o: JsonObject): Boolean? {
    (o["supported_parameters"] as? JsonArray)?.let { a -> return a.any { (it as? JsonPrimitive)?.content in setOf("tools", "tool_choice") } }
    when (val caps = o["capabilities"]) {
        is JsonArray -> return caps.any { (it as? JsonPrimitive)?.content?.lowercase() in setOf("tools", "tool_use", "function_calling", "tool-calling") }
        is JsonObject -> listOf("function_calling", "tools", "tool_calling").firstNotNullOfOrNull { (caps[it] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }?.let { return it }
        else -> {}
    }
    listOf("supports_tools", "supports_function_calling", "tool_calling", "function_calling").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }?.let { return it }
    return null
}

/**
 * The models to test, best first, at most [limit]. [main] (the user's chosen main model) always
 * leads. The pool is [checked] (what the user ticked) when non-empty, else everything [listed];
 * non-chat models are skipped. Order: the preset's suggested models, the vendor's own family, models
 * the listing marks as tool-capable, then listing order.
 */
fun rankCandidates(listed: List<RemoteModel>, preset: ProviderPreset?, main: String? = null, checked: List<String> = emptyList(), limit: Int = 3): List<String> {
    val byId = listed.associateBy { it.id }
    val pool = (if (checked.isNotEmpty()) checked.map { byId[it] ?: RemoteModel(it) } else listed)
        .filter { it.chat != false && !looksNonChat(it.id) && it.id != main }
    val family = preset?.family.orEmpty()
    fun tier(m: RemoteModel): Int {
        val name = m.id.lowercase().substringAfterLast('/')
        return when {
            preset != null && m.id in preset.models -> 0
            family.any { name.startsWith(it) } -> 1
            m.tools == true -> 2
            else -> 3
        }
    }
    val ranked = pool.withIndex().sortedWith(compareBy({ tier(it.value) }, { it.index })).map { it.value.id }
    return (listOfNotNull(main?.takeIf { it.isNotBlank() }) + ranked).distinct().take(limit)
}

// ── the probe ──

/**
 * The harness part of the engine's User-Agent (dsh-llm attributionHeaders), so gateways that filter clients
 * treat the test like a chat; the app puts `DSH-Android/<version>` in front ([appUserAgent]), as the engine does.
 */
const val HARNESS_USER_AGENT = "deepseek-harness (+https://github.com/deepseek-ai/deepseek-harness)"
const val PROBE_TOOL = "dsh_connection_check"

/** The probe's request body: one no-argument tool, and (when [forceTool]) a tool_choice naming it. */
fun probeBody(model: String, forceTool: Boolean): String = buildJsonObject {
    put("model", model)
    put("stream", true)
    putJsonObject("stream_options") { put("include_usage", true) }
    // Small, but with room for models that think before they call (MiniMax and some GLM / Kimi models always do).
    put("max_tokens", 1024)
    putJsonArray("messages") {
        addJsonObject { put("role", "system"); put("content", "This is a connection check. Call the $PROBE_TOOL tool once. Do not reply with text.") }
        addJsonObject { put("role", "user"); put("content", "Call $PROBE_TOOL now.") }
    }
    putJsonArray("tools") {
        addJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", PROBE_TOOL)
                put("description", "Confirms that tool calls work. Takes no arguments.")
                putJsonObject("parameters") { put("type", "object"); putJsonObject("properties") {}; putJsonArray("required") {} }
            }
        }
    }
    if (forceTool) putJsonObject("tool_choice") { put("type", "function"); putJsonObject("function") { put("name", PROBE_TOOL) } }
}.toString()

/** The probe's body on the Responses API (#59): the same check in that protocol's shape. */
fun probeResponsesBody(model: String, forceTool: Boolean): String = buildJsonObject {
    put("model", model)
    put("stream", true)
    put("instructions", "This is a connection check. Call the $PROBE_TOOL tool once. Do not reply with text.")
    putJsonArray("input") { addJsonObject { put("role", "user"); put("content", "Call $PROBE_TOOL now.") } }
    put("max_output_tokens", 1024)
    putJsonArray("tools") {
        addJsonObject {
            put("type", "function")
            put("name", PROBE_TOOL)
            put("description", "Confirms that tool calls work. Takes no arguments.")
            putJsonObject("parameters") { put("type", "object"); putJsonObject("properties") {}; putJsonArray("required") {} }
        }
    }
    if (forceTool) putJsonObject("tool_choice") { put("type", "function"); put("name", PROBE_TOOL) }
}.toString()

/** The probe's endpoint and body for [api] ([API_CHAT] or [API_RESPONSES]). */
fun probeRequest(baseUrl: String, model: String, forceTool: Boolean, api: String = API_CHAT): Pair<String, String> {
    val base = baseUrl.trim().trimEnd('/')
    return if (api == API_RESPONSES) "$base/responses" to probeResponsesBody(model, forceTool)
    else "$base/chat/completions" to probeBody(model, forceTool)
}

/**
 * One real chat request. Returns null when the answer contains a tool call, else why not.
 * Runs on the caller's thread (call from Dispatchers.IO). The read timeout is generous: a local
 * server (Ollama, LM Studio) may load the model before the first byte.
 */
fun probeToolCall(baseUrl: String, key: String?, model: String, forceTool: Boolean = true, connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 90_000,
    headers: Map<String, String> = emptyMap(), api: String = API_CHAT): VerifyFailure? = try {
    val (url, body) = probeRequest(baseUrl, model, forceTool, api)
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("accept", "text/event-stream")
        c.setRequestProperty("user-agent", HARNESS_USER_AGENT)
        // The provider's extra headers, session header and app User-Agent (appRequestHeaders), as the engine sends them.
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (!key.isNullOrBlank()) c.setRequestProperty("authorization", "Bearer ${key.trim()}")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val body = c.errorStream?.use { it.readBytes().decodeToString().take(64_000) }.orEmpty()
            VerifyErrors.classify(code, body)
        } else c.inputStream.bufferedReader().use { readProbeAnswer(it.lineSequence()) }
    } finally { c.disconnect() }
} catch (t: Throwable) {
    VerifyErrors.ofThrowable(t)
}

/**
 * Reads a chat answer, SSE (`data: {…}` lines) or one JSON object (a server that ignores `stream`),
 * and stops at the first tool call. An `error` frame is classified like a 400 answer. Reads both
 * Chat Completions chunks and Responses events (`function_call` items, `response.failed`).
 */
fun readProbeAnswer(lines: Sequence<String>): VerifyFailure? {
    val reply = StringBuilder()
    val plain = StringBuilder()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** null = keep reading; else the verdict ([PASS] for a tool call). */
    fun frame(payload: String): VerifyFailure? {
        val o = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return VerifyFailure.Server(200, "unreadable response: " + payload.take(120))
        if (o["error"] != null && o["error"] !is JsonNull) return VerifyErrors.classify(errorStatus(o), payload)
        // Responses: an `error` event carries code / message at the top level; `response.failed` nests them.
        when ((o["type"] as? JsonPrimitive)?.content) {
            "error" -> return VerifyErrors.classify(errorStatus(o), payload)
            "response.failed" -> ((o["response"] as? JsonObject)?.get("error") as? JsonObject)?.let { e ->
                val wrapped = JsonObject(mapOf("error" to e))
                return VerifyErrors.classify(errorStatus(wrapped), wrapped.toString())
            }
            "response.output_text.delta" -> (o["delta"] as? JsonPrimitive)?.content?.let { if (reply.length < 300) reply.append(it) }
        }
        val items = listOfNotNull(o["item"]) + (o["output"] as? JsonArray).orEmpty() + ((o["response"] as? JsonObject)?.get("output") as? JsonArray).orEmpty()
        if (items.any { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "function_call" }) return PASS
        (o["choices"] as? JsonArray)?.forEach { ch ->
            val choice = ch as? JsonObject ?: return@forEach
            val msg = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return@forEach
            val calls = msg["tool_calls"] as? JsonArray
            if (!calls.isNullOrEmpty() || (msg["function_call"] as? JsonObject) != null) return PASS
            (msg["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { if (reply.length < 300) reply.append(it) }
        }
        return null
    }
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("data:")) {
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            frame(payload)?.let { return it.takeIf { v -> v !== PASS } }
        } else if (line.isNotEmpty() && !line.startsWith(":") && !line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:")) {
            plain.append(line).append('\n')
            if (plain.length > 256_000) break
        }
    }
    if (plain.isNotBlank()) frame(plain.toString())?.let { return it.takeIf { v -> v !== PASS } }
    return VerifyFailure.NoToolCall(reply.toString().trim().replace(Regex("\\s+"), " ").take(120))
}

private val PASS = VerifyFailure.NoToolCall("\u0000pass")

/** The status an error frame stands for: a numeric `error.code` / `http_code` in 400..599, else 400. */
private fun errorStatus(o: JsonObject): Int {
    val err = o["error"] as? JsonObject
    return listOfNotNull(err?.get("http_code"), err?.get("code"), err?.get("status"), o["status"])
        .mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.firstOrNull { it in 400..599 } ?: 400
}

/**
 * [probeToolCall], and once more without `tool_choice` when the server rejects that field: the
 * engine never sends it, so a model that calls the tool on its own still works in chats.
 */
fun probeModel(baseUrl: String, key: String?, model: String, headers: Map<String, String> = emptyMap(), api: String = API_CHAT,
    probe: (Boolean) -> VerifyFailure? = { force -> probeToolCall(baseUrl, key, model, force, headers = headers, api = api) }): VerifyFailure? {
    val first = probe(true) ?: return null
    val msg = when (first) {
        is VerifyFailure.ToolsUnsupported -> first.message
        is VerifyFailure.Server -> first.message.takeIf { first.status == 400 || first.status == 422 }
        is VerifyFailure.ModelNotAvailable -> first.message
        else -> null
    }
    return if (msg != null && "tool_choice" in msg.lowercase()) probe(false) else first
}

// ── the flow ──

sealed interface VerifyOutcome {
    /** All failures so far, in test order (model to why). */
    val failed: List<Pair<String, VerifyFailure>>

    data class Passed(val model: String, override val failed: List<Pair<String, VerifyFailure>>) : VerifyOutcome
    /** The provider answered, but no tested model works with DSH. */
    data class NoModelWorked(override val failed: List<Pair<String, VerifyFailure>>) : VerifyOutcome
    /** Key, network, address or server trouble: testing other models would not help. [failure] is the last entry of [failed]. */
    data class Stopped(val failure: VerifyFailure, override val failed: List<Pair<String, VerifyFailure>>) : VerifyOutcome
}

/** Test [candidates] in order (at most [max]); the next one only after a model-specific failure. */
fun verifyCandidates(candidates: List<String>, max: Int = 3, onTesting: (String) -> Unit = {}, probe: (String) -> VerifyFailure?): VerifyOutcome {
    val failed = mutableListOf<Pair<String, VerifyFailure>>()
    for (m in candidates.distinct().take(max)) {
        onTesting(m)
        val f = probe(m) ?: return VerifyOutcome.Passed(m, failed.toList())
        failed += m to f
        if (!f.modelSpecific) return VerifyOutcome.Stopped(f, failed.toList())
    }
    return VerifyOutcome.NoModelWorked(failed.toList())
}

/** What 测试 does with an outcome: the editor's models and verified set, and the model a 保存 makes the main one. */
data class TestPlan(val apply: Boolean, val models: List<String>, val verified: List<String>, val main: String?)

/**
 * [checked]: the models the user ticked, in display order; [previouslyVerified]: the editor's
 * verified set before the test; [makeMain]: a passing model becomes the main model on save (a new
 * provider, or one that already is the main route).
 * - Passed: the passing model is ticked (and is the only model when nothing was ticked).
 * - NoModelWorked: the provider answers, no main model; with nothing ticked the tested models are
 *   ticked so the user can see them and pick or retest.
 * - Stopped: the editor is left as it was.
 * Nothing is stored: 保存 is a separate action (#59), see [verifiedOnSave].
 */
fun planTest(outcome: VerifyOutcome, checked: List<String>, previouslyVerified: List<String>, makeMain: Boolean): TestPlan {
    val failedIds = outcome.failed.map { it.first }.toSet()
    return when (outcome) {
        is VerifyOutcome.Passed -> {
            val models = (checked + outcome.model).distinct()
            TestPlan(true, models, (previouslyVerified.filter { it in models && it !in failedIds } + outcome.model).distinct(), outcome.model.takeIf { makeMain })
        }
        is VerifyOutcome.NoModelWorked -> {
            val models = checked.ifEmpty { outcome.failed.map { it.first } }
            TestPlan(true, models, previouslyVerified.filter { it in models && it !in failedIds }, null)
        }
        is VerifyOutcome.Stopped -> TestPlan(false, checked, previouslyVerified, null)
    }
}

/** What a test result is valid for: protocol, base URL and the typed key (blank = the stored one). */
fun endpointKey(api: String, baseUrl: String, typedKey: String): String = listOf(api, normalizeBaseUrl(baseUrl), typedKey.trim()).joinToString("\n")

/**
 * The verified set 保存 stores (#59: saving does not test): the editor's [verified] set (as saved, or
 * from this visit's 测试) while it was established for the [endpoint] being saved, kept to [models];
 * after a change of protocol, address or key, nothing counts as tested.
 */
fun verifiedOnSave(models: List<String>, verified: List<String>, verifiedFor: String, endpoint: String): List<String> =
    if (verifiedFor == endpoint) verified.filter { it in models }.distinct() else emptyList()
