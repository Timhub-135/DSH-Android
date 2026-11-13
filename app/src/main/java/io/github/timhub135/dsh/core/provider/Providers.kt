package io.github.timhub135.dsh.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import io.github.timhub135.dsh.R
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Third-party model providers (OpenAI-compatible Chat Completions or OpenAI Responses endpoints, #59),
 * served in the engine by the vendored `@snnh/dsh-llm-service-lite` row. See docs/ENGINE-CONTRACT.md §1.1.
 *
 * Presets: base URLs follow magpie's provider table (github.com/yetone/magpie,
 * internal/provider/presets.go, MIT), restricted to each vendor's OpenAI-compatible endpoint.
 * Default model ids are only a starting point; "获取模型列表" asks the endpoint's `/models`.
 * Adding a preset is one line.
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    /** The custom preset leaves the key optional (e.g. a self-hosted gateway without auth). */
    val keyOptional: Boolean = false,
    /** The user edits the address (custom endpoint; HTTPS only). */
    val editableUrl: Boolean = false,
    val keysUrl: String? = null,
    /**
     * Name prefixes of the vendor's own models (lowercase). The connection test tries these first:
     * on an aggregator they are absent and the listing's own hints decide.
     */
    val family: List<String> = emptyList(),
    /** A server on the user's computer (Ollama, LM Studio): the editor explains how to reach it from the phone. */
    val localHint: Int? = null,
    /** Header the provider wants a per-conversation id in (prefilled in the editor; see [ProviderConfig.sessionHeader]). */
    val sessionHeader: String? = null,
    /** The editor offers the OpenAI Responses protocol ([API_RESPONSES]) besides Chat Completions (#59). */
    val responses: Boolean = false,
)

/*
 * Base URLs and key pages checked against the vendors' docs (2026-10): SiliconFlow
 * docs.siliconflow.cn, Volcano Ark docs.volcengine.com/docs/82379 (models must be 开通 in the
 * console first; whether Ark serves GET /models is undocumented, the editor then asks for a model
 * id), Kimi platform.kimi.com/docs, Zhipu docs.bigmodel.cn (no documented /models either),
 * MiniMax platform.minimax.cn/docs (api.minimax.cn; api.minimaxi.com still answers), Ollama
 * docs.ollama.com/api/openai-compatibility, LM Studio lmstudio.ai/docs/developer/openai-compat.
 */
val PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-5"), keysUrl = "https://platform.openai.com/api-keys", family = listOf("gpt-", "o1", "o3", "o4", "chatgpt"), responses = true),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1", listOf("claude-sonnet-5", "claude-opus-5"), keysUrl = "https://console.anthropic.com/settings/keys", family = listOf("claude")),
    ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-3.5-flash"), keysUrl = "https://aistudio.google.com/apikey", family = listOf("gemini")),
    ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", listOf("grok-4"), keysUrl = "https://console.x.ai", family = listOf("grok")),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("openrouter/auto"), keysUrl = "https://openrouter.ai/keys"),
    ProviderPreset("kimi", "Kimi (Moonshot)", "https://api.moonshot.cn/v1", listOf("kimi-k3"), keysUrl = "https://platform.kimi.com/console/api-keys", family = listOf("kimi", "moonshot")),
    ProviderPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-5.3"), keysUrl = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", family = listOf("glm")),
    ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen3.8-max"), keysUrl = "https://bailian.console.aliyun.com/?tab=model#/api-key", family = listOf("qwen", "qwq")),
    ProviderPreset("ark", "火山方舟 / 豆包", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-seed-2-1-pro-260915"), keysUrl = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey", family = listOf("doubao")),
    ProviderPreset("minimax", "MiniMax", "https://api.minimax.cn/v1", listOf("MiniMax-M3"), keysUrl = "https://platform.minimax.cn/user-center/basic-information/interface-key", family = listOf("minimax")),
    ProviderPreset("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", keysUrl = "https://cloud.siliconflow.cn/account/ak"),
    ProviderPreset("stepfun", "阶跃星辰 StepFun", "https://api.stepfun.com/v1", listOf("step-5"), keysUrl = "https://platform.stepfun.com/interface-key", family = listOf("step")),
    // #32. opencode.ai/docs/go (2026-10): clients should send a stable per-conversation id in x-opencode-session
    // and their own User-Agent; chats without the header are refused (INVALID_REQUEST), /models answers anyway.
    // Base URL as reported working in #32; the docs also list https://opencode.ai/inference/go/openai/v1.
    ProviderPreset("opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1", keysUrl = "https://opencode.ai/docs/go/", sessionHeader = OPENCODE_SESSION_HEADER),
    // On the user's computer: the phone reaches it at the computer's LAN IP (the editor's localhost warning says so).
    ProviderPreset("ollama", "Ollama", "http://localhost:11434/v1", keyOptional = true, editableUrl = true, localHint = R.string.prov_hint_ollama, responses = true),
    ProviderPreset("lmstudio", "LM Studio", "http://localhost:1234/v1", keyOptional = true, editableUrl = true, localHint = R.string.prov_hint_lmstudio, responses = true),
    ProviderPreset(CUSTOM, "自定义（OpenAI 兼容）", "", keyOptional = true, editableUrl = true, responses = true),
)

const val CUSTOM = "custom"
const val OPENCODE_SESSION_HEADER = "x-opencode-session"

/** Wire protocols a provider may speak: the engine's `interfaceType` spellings (llm-service-lite INTERFACE_TYPES). */
const val API_CHAT = "openai-chat-completions"
/** OpenAI Responses API (#59): `POST {base}/responses`. */
const val API_RESPONSES = "openai-responses"

/**
 * The base URL as stored: trimmed, no trailing `/`, and without a pasted endpoint path
 * (`…/v1/chat/completions`, `…/v1/responses`): the engine appends the endpoint itself.
 */
fun normalizeBaseUrl(url: String): String {
    var b = url.trim().trimEnd('/')
    listOf("/chat/completions", "/responses").forEach { if (b.endsWith(it, ignoreCase = true)) b = b.dropLast(it.length).trimEnd('/') }
    return b
}

fun presetOf(id: String?): ProviderPreset? = PRESETS.firstOrNull { it.id == id }

/** One configured provider = one engine route (`id`), all its models on one protocol ([api]). */
@Serializable
data class ProviderConfig(
    /** Route id: the preset id, `-2`, `-3`… for repeats. Never a DeepSeek route name. */
    val id: String,
    val preset: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val enabled: Boolean = true,
    /** A key is stored for it in SecretStore (the value itself never leaves Keystore-encrypted storage except as env). */
    val hasKey: Boolean = false,
    /** User-set context window for every model of this route (null = per model / detected / default). */
    val contextWindow: Long? = null,
    /** User-set output cap (`max_tokens`) for every model of this route. */
    val maxTokens: Int? = null,
    /** Per-model limits: the user's overrides and what `/models` reported. Absent = nothing known. */
    val limits: Map<String, ModelLimits> = emptyMap(),
    /** Models that passed the connection test (a real chat request with a tool call); see ModelVerify.kt. */
    val verified: List<String> = emptyList(),
    /** Extra request headers on every model request; a [ProviderHeader.secret] one keeps its value in SecretStore, like the key. */
    val headers: List<ProviderHeader> = emptyList(),
    /**
     * Header carrying a per-conversation id (e.g. OpenCode Go's `x-opencode-session`): the engine sends a
     * one-way hash of the session id, the same for every request of a conversation (js patch 18).
     */
    val sessionHeader: String? = null,
    /** Wire protocol: [API_CHAT] (default) or [API_RESPONSES] (#59); anything else reads as [API_CHAT]. */
    val api: String = API_CHAT,
) {
    /** The engine's `interfaceType` for this route. */
    val interfaceType: String get() = if (api == API_RESPONSES) API_RESPONSES else API_CHAT
    /** `apiKeyEnv` the engine resolves per request; set in the `:engine` environment only. */
    val keyEnv: String get() = keyEnvOf(id)
    val secretName: String get() = "provider-$id"
    /** SecretStore name of a secret header's value. */
    fun headerSecretName(header: String) = "provider-$id-h-" + header.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    /** Engine environment variable a secret header's value travels in (`headerEnv` in the patch layer). */
    fun headerEnv(header: String) = headerEnvOf(id, header)
}

/** One extra request header. [value] is empty for a [secret] one (the value lives in SecretStore). */
@Serializable
data class ProviderHeader(val name: String, val value: String = "", val secret: Boolean = false)

fun headerEnvOf(routeId: String, header: String) =
    "DSH_ANDROID_HDR_" + (routeId + "_" + header).uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** Headers the app or the HTTP stack sets itself; the editor refuses them. */
private val RESERVED_HEADERS = setOf("host", "content-type", "content-length", "transfer-encoding", "connection", "accept", "accept-encoding", "keep-alive", "upgrade", "te", "trailer")

/** An RFC 9110 field name the user may set (not one the request sets itself). */
fun isUsableHeaderName(name: String): Boolean =
    name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" } && name.lowercase() !in RESERVED_HEADERS

/** A single-line value (no control characters). */
fun isUsableHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' || (it < ' ' && it != '\t') || it == '\u007f' }

private val SECRET_NAME = Regex("key|token|secret|auth|passw|cookie|signature|credential|session-id", RegexOption.IGNORE_CASE)
private val SECRET_VALUE = Regex("^(sk-|sk_|bearer |basic |ey[A-Za-z0-9_-]{10,}\\.)", RegexOption.IGNORE_CASE)

/** Stored like the API key (SecretStore) rather than in settings: a credential-sounding name or a key-shaped value. */
fun looksSecretHeader(name: String, value: String): Boolean = SECRET_NAME.containsMatchIn(name) || SECRET_VALUE.containsMatchIn(value.trim())

/** An editor row of 额外请求头: [value] blank on a stored secret row means "keep the saved value". */
data class HeaderRow(val name: String, val value: String)

/** What saving the editor's header rows does: the stored list, SecretStore writes (secret name → value) and clears. */
data class HeaderPlan(val headers: List<ProviderHeader>, val write: Map<String, String>, val clear: List<String>)

/**
 * Plan the headers of route [routeId] from the editor [rows]. A row whose name is blank, or whose
 * value is blank and was not a saved secret, is dropped. A secret ([looksSecretHeader], or saved as
 * a secret before) keeps its value in SecretStore only; a blank value keeps the saved one. Secrets
 * of [previous] headers no longer present (or no longer secret) are cleared.
 */
fun planHeaders(routeId: String, rows: List<HeaderRow>, previous: ProviderConfig?): HeaderPlan {
    val cfg = ProviderConfig(routeId, "", "", "", emptyList())
    val wasSecret = previous?.headers.orEmpty().filter { it.secret }.map { it.name.lowercase() }.toSet()
    val headers = mutableListOf<ProviderHeader>()
    val write = LinkedHashMap<String, String>()
    rows.map { HeaderRow(it.name.trim(), it.value.trim()) }.filter { it.name.isNotEmpty() }.distinctBy { it.name.lowercase() }.forEach { r ->
        val keepSaved = r.value.isEmpty() && r.name.lowercase() in wasSecret && previous?.id == routeId
        when {
            keepSaved -> headers += ProviderHeader(r.name, secret = true)
            r.value.isEmpty() -> {}
            looksSecretHeader(r.name, r.value) || r.name.lowercase() in wasSecret -> { headers += ProviderHeader(r.name, secret = true); write[cfg.headerSecretName(r.name)] = r.value }
            else -> headers += ProviderHeader(r.name, r.value)
        }
    }
    val kept = headers.filter { it.secret }.map { cfg.headerSecretName(it.name) }.toSet()
    val clear = previous?.headers.orEmpty().filter { it.secret }.map { previous!!.headerSecretName(it.name) }.filter { previous!!.id != routeId || it !in kept }
    return HeaderPlan(headers, write, clear)
}

/**
 * The editor rows resolved to values for a request: typed values, or (blank) the saved secret
 * value [readSecret] returns for a secret header of [existing].
 */
fun resolveHeaderRows(rows: List<HeaderRow>, existing: ProviderConfig?, readSecret: (String) -> String?): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    rows.forEach { r ->
        val name = r.name.trim()
        if (name.isEmpty()) return@forEach
        val saved = existing?.headers?.firstOrNull { it.name.equals(name, true) }
        val v = r.value.trim().ifEmpty { if (saved?.secret == true) readSecret(existing.headerSecretName(saved.name)).orEmpty() else "" }
        if (v.isNotEmpty()) out[name] = v
    }
    return out
}

/** The stored headers of [p] as editor rows (secret values blank: kept unless retyped). */
fun headerRowsOf(p: ProviderConfig?): List<HeaderRow> = p?.headers.orEmpty().map { HeaderRow(it.name, if (it.secret) "" else it.value) }

/** A fresh opaque id for a request outside any conversation (model listing, connection test). */
fun freshSessionId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

/** The engine's User-Agent for third-party providers: `DSH-Android/<version>` in front of the harness one. */
fun appUserAgent(version: String) = "${appProductToken(version)} $HARNESS_USER_AGENT"
/** The product token alone; the engine puts it in front of its own `deepseek-harness/<v> (+url)`. */
fun appProductToken(version: String) = "DSH-Android/$version"

/**
 * Headers for the app's own requests to a provider (获取模型列表, the connection test), matching what
 * the engine sends: [resolved] extra headers (secret values already read), the app User-Agent unless
 * a header sets one, and [sessionHeader] with a fresh id (these requests belong to no conversation).
 */
fun appRequestHeaders(resolved: Map<String, String>, sessionHeader: String?, userAgent: String?): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (userAgent != null && resolved.keys.none { it.equals("user-agent", true) }) out["User-Agent"] = userAgent
    resolved.forEach { (k, v) -> if (k.isNotBlank()) out[k] = v }
    sessionHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { h -> out.keys.removeAll { it.equals(h, true) }; out[h] = freshSessionId() }
    return out
}

fun keyEnvOf(routeId: String) = "DSH_ANDROID_KEY_" + routeId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** A fresh route id for [preset] that no existing provider (or DeepSeek) owns. */
fun newRouteId(preset: String, existing: Collection<String>): String {
    val base = preset.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { CUSTOM }.let { if (it.startsWith("deepseek")) "x-$it" else it }
    if (base !in existing) return base
    return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in existing }
}

/** One model's limits as stored: user overrides and the values the endpoint's `/models` reported. */
@Serializable
data class ModelLimits(
    val contextWindow: Long? = null,
    val maxTokens: Int? = null,
    val detectedContextWindow: Long? = null,
    val detectedMaxTokens: Int? = null,
    /** The user's 图片输入 setting for this model (null = automatic, see ModelVision). */
    val images: Boolean? = null,
    /** Image input as the `/models` listing stated it. */
    val detectedImages: Boolean? = null,
    /** The user's 推理控制 declaration for this model (null = automatic: the engine's family table; see ModelReasoning). */
    val reasoning: ReasoningDecl? = null,
) {
    val isEmpty get() = contextWindow == null && maxTokens == null && detectedContextWindow == null && detectedMaxTokens == null &&
        images == null && detectedImages == null && reasoning == null
}

/** Where an effective limit came from (shown next to the value). */
enum class LimitSource { DETECTED, MANUAL, DEFAULT }

/** The limits a third-party model actually runs with (UI ring and the engine patch agree on these). */
data class EffectiveLimits(
    val contextWindow: Long,
    val contextSource: LimitSource,
    val maxTokens: Int,
    val maxTokensSource: LimitSource,
    /** compaction-basic `headroomTokens`: dsh compacts at min(80 % window, window − maxTokens − headroom). */
    val compactionHeadroom: Int,
)

/** Defaults and sanity bounds for third-party models (no per-model matrices; see ARCHITECTURE). */
object ThirdParty {
    /** Context window when neither the user nor `/models` says. Conservative: most current models have ≥128K. */
    const val DEFAULT_CONTEXT_WINDOW = 131_072L
    /** Output cap (`max_tokens`) when unset: vendors' own defaults are sometimes tiny (1–4K), truncating tool calls. */
    const val DEFAULT_MAX_TOKENS = 16_384
    /**
     * Compaction headroom. dsh's default (64K) on a 128K window would compact at
     * 128K − 16K − 64K = 48K; with 16K it compacts at ~96K (75 %), still well inside the window.
     * Capped at 1/8 of the window so tiny windows keep a positive threshold.
     */
    const val COMPACTION_HEADROOM = 16_384
    const val MIN_CONTEXT_WINDOW = 4_096L
    const val MAX_CONTEXT_WINDOW = 10_000_000L
    const val MIN_MAX_TOKENS = 256

    /**
     * Resolve one model's limits. Precedence: per-model override, provider-level override, the
     * `/models` value, the default. Bounds: window in [MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW];
     * output cap at most half the window when the user set it, a quarter otherwise (a listed
     * `max_completion_tokens` is the model's ceiling, not a good per-request ask, and a big cap
     * pulls the compaction threshold window − maxTokens − headroom down).
     */
    fun resolve(model: ModelLimits?, providerWindow: Long?, providerMaxTokens: Int?): EffectiveLimits {
        val (rawCtx, ctxSrc) = when {
            model?.contextWindow != null -> model.contextWindow to LimitSource.MANUAL
            providerWindow != null -> providerWindow to LimitSource.MANUAL
            model?.detectedContextWindow != null -> model.detectedContextWindow to LimitSource.DETECTED
            else -> DEFAULT_CONTEXT_WINDOW to LimitSource.DEFAULT
        }
        val ctx = rawCtx.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW)
        val (rawMax, maxSrc) = when {
            model?.maxTokens != null -> model.maxTokens to LimitSource.MANUAL
            providerMaxTokens != null -> providerMaxTokens to LimitSource.MANUAL
            model?.detectedMaxTokens != null -> model.detectedMaxTokens to LimitSource.DETECTED
            else -> DEFAULT_MAX_TOKENS to LimitSource.DEFAULT
        }
        val cap = (if (maxSrc == LimitSource.MANUAL) ctx / 2 else ctx / 4).toInt()
        val max = rawMax.coerceIn(MIN_MAX_TOKENS, cap)
        return EffectiveLimits(ctx, ctxSrc, max, maxSrc, minOf(COMPACTION_HEADROOM.toLong(), ctx / 8).toInt())
    }
}

fun ProviderConfig.limitsOf(model: String): EffectiveLimits = ThirdParty.resolve(limits[model], contextWindow, maxTokens)

/** Whether [model] gets images (the engine declaration in [renderProfilePatch]; the composer's notice). */
fun ProviderConfig.acceptsImages(model: String): Boolean = ModelVision.resolve(model, limits[model]).first

/** What the composer / engine route resolves to. `provider == null` is DeepSeek. */
data class ModelChoice(val provider: ProviderConfig?, val model: String) {
    val isDeepSeek get() = provider == null
    /** Third-party limits; null for DeepSeek. */
    val limits: EffectiveLimits? get() = provider?.limitsOf(model)
    val contextWindow get() = limits?.contextWindow ?: DEEPSEEK_CONTEXT
    /** Where [contextWindow] came from; null for DeepSeek (a fixed fact). */
    val contextSource get() = limits?.contextSource
    /** Images reach the model (else the engine sends a text placeholder): DeepSeek per model (V4.1-Flash sees, V4-Pro does not). */
    val acceptsImages: Boolean get() = provider?.acceptsImages(model) ?: ModelVision.engineDefault(model)
    companion object { const val DEEPSEEK_CONTEXT = 1_000_000L }
}

val DEEPSEEK_MODELS = listOf("deepseek-flash" to "DeepSeek-V4.1-Flash", "deepseek-v4-pro" to "DeepSeek-V4-Pro")
const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"

/**
 * Resolve the saved selection: a third-party (provider, model) only while that provider exists, is
 * enabled and still lists the model; otherwise DeepSeek (the saved DeepSeek model, or flash).
 */
fun resolveChoice(provider: String, model: String, providers: List<ProviderConfig>): ModelChoice {
    if (provider.isNotEmpty()) {
        val p = providers.firstOrNull { it.id == provider && it.enabled }
        if (p != null && model in p.models) return ModelChoice(p, model)
    }
    return ModelChoice(null, model.takeIf { m -> DEEPSEEK_MODELS.any { it.first == m } } ?: DEFAULT_DEEPSEEK_MODEL)
}

/**
 * The app's settings layer `$DSH_HOME/android/app-layer.patch.yml` (the engine entry applies it as a
 * `--patch` overlay above every layer). The app is its only writer and rewrites it before each engine start; it carries no
 * secret, only `apiKeyEnv` names. Values are JSON (a YAML subset), so no quoting rules apply.
 *
 * @param userAgent product token the engine puts in front of its User-Agent (`DSH-Android/<version>`).
 * @param webSearch false turns off the `web_search` tool (it needs a DeepSeek credential: the
 *   API key, or the account login only while the conversation uses the account route).
 * @param defaultModel the default model route (#53): dsh-base's `agent-default-model` row, which
 *   plugins read for the sessions they create themselves (heartbeat, chat bots). Conversations the
 *   app runs get their own route per session (`android/session/model`).
 */
fun renderProfilePatch(providers: List<ProviderConfig>, webSearch: Boolean, userAgent: String? = null,
                       defaultModel: io.github.timhub135.dsh.core.engine.EngineRoute? = null): String {
    val active = providers.filter { it.enabled && it.models.isNotEmpty() && it.baseUrl.isNotBlank() }
    val json = Json { prettyPrint = false }
    val sb = StringBuilder()
    sb.append("# Generated by DSH-Android (设置 → 模型服务). Rewritten before every engine start; edits here are lost.\n")
    if (active.isEmpty() && webSearch && defaultModel == null) return sb.append("[]\n").toString()
    if (active.isNotEmpty()) {
        val providersJson = buildJsonObject {
            putJsonObject("providers") {
                active.forEach { p ->
                    putJsonObject(p.id) {
                        put("displayName", p.name)
                        put("interfaceType", p.interfaceType)
                        put("baseURL", p.baseUrl.trim().trimEnd('/'))
                        if (p.hasKey) put("apiKeyEnv", p.keyEnv)
                        // js patch 18: static headers here, secret ones by env name only, the session header, the UA token.
                        val plain = p.headers.filter { !it.secret && isUsableHeaderName(it.name) }
                        if (plain.isNotEmpty()) putJsonObject("headers") { plain.forEach { put(it.name, it.value) } }
                        val secret = p.headers.filter { it.secret && isUsableHeaderName(it.name) }
                        if (secret.isNotEmpty()) putJsonObject("headerEnv") { secret.forEach { put(it.name, p.headerEnv(it.name)) } }
                        p.sessionHeader?.trim()?.takeIf { isUsableHeaderName(it) }?.let { put("sessionHeader", it) }
                        if (userAgent != null) put("userAgent", userAgent)
                        // Chat Completions reports usage only when asked (stream_options); Responses always does, and the engine refuses the flag there.
                        if (p.interfaceType == API_CHAT) put("includeUsage", true)
                        putJsonArray("models") {
                            p.models.forEach { m ->
                                val l = p.limitsOf(m)
                                addJsonObject {
                                    put("id", m)
                                    put("contextWindow", l.contextWindow)
                                    // Capacity and the per-request ask (sent as max_tokens) are the same value.
                                    put("maxTokens", l.maxTokens)
                                    putJsonObject("defaults") { put("maxTokens", l.maxTokens) }
                                    // Always declared (#39): the engine's own table treats unknown ids as text only.
                                    putJsonObject("capabilities") {
                                        putJsonArray("modalities") { add("text"); if (p.acceptsImages(m)) add("image") }
                                        // #46: only a user declaration; without one the engine's family table decides.
                                        p.limits[m]?.reasoning?.writeTo(this)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        sb.append("- id: llm-service-lite\n  config: ").append(json.encodeToString(JsonObject.serializer(), providersJson)).append('\n')
        val compaction = buildJsonObject {
            putJsonArray("modelPolicies") {
                active.forEach { p -> p.models.forEach { m -> addJsonObject { put("provider", p.id); put("model", m); put("headroomTokens", p.limitsOf(m).compactionHeadroom) } } }
            }
        }
        sb.append("- id: compaction-basic\n  config: ").append(json.encodeToString(JsonObject.serializer(), compaction)).append('\n')
    }
    // dsh-base's tool-web config, with search off (a patch replaces the whole config).
    if (!webSearch) sb.append("- id: tool-web\n  config: {\"search\":false,\"fetch\":true,\"searchTimeoutMs\":60000}\n")
    if (defaultModel != null) {
        val row = buildJsonObject {
            put("provider", defaultModel.provider)
            put("model", defaultModel.model)
            if (defaultModel.effort.isNotEmpty()) put("reasoningEffort", defaultModel.effort)
        }
        sb.append("- id: agent-default-model\n  config: ").append(json.encodeToString(JsonObject.serializer(), row)).append('\n')
    }
    return sb.toString()
}

/**
 * One `/models` entry: the id, whatever limits the endpoint reports for it, and what the listing
 * says about its kind: [chat] false = clearly not a chat model (embedding, image, audio…), [tools]
 * true = marked as supporting tool calls. Null = the listing does not say.
 */
data class RemoteModel(val id: String, val contextWindow: Long? = null, val maxTokens: Int? = null, val chat: Boolean? = null, val tools: Boolean? = null,
    /** Image input as the listing marks it (ModelVision.listed). */
    val images: Boolean? = null)

/** A non-2xx answer: [status] and the (truncated) body. The message keeps the `HTTP <code> …` form. */
class HttpStatusException(val status: Int, val body: String) : Exception("HTTP $status ${body.take(160).replace('\n', ' ')}".trim())

/**
 * `GET {baseUrl}/models` (OpenAI shape `{data:[{id}]}`, or a bare array / `{models:[…]}`).
 * Doubles as the connection test. Runs on the caller's thread (call from Dispatchers.IO).
 * https, or http for a user's LAN gateway (network_security_config permits cleartext).
 */
fun fetchModels(baseUrl: String, key: String?, headers: Map<String, String> = emptyMap()): Result<List<RemoteModel>> = runCatching {
    val url = URL(baseUrl.trim().trimEnd('/') + "/models")
    val c = url.openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Accept", "application/json")
        if (!key.isNullOrBlank()) {
            c.setRequestProperty("Authorization", "Bearer $key")
            // Anthropic's listing reads x-api-key (its OpenAI-compatible layer takes the bearer).
            if (url.host.endsWith("anthropic.com")) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString().take(4_000_000) }.orEmpty()
        if (code !in 200..299) throw HttpStatusException(code, body.take(64_000))
        parseModels(body)
    } finally { c.disconnect() }
}

/**
 * A bare-origin base URL (`https://host`, no path) plus `/v1`, else null. OpenAI-compatible APIs
 * live under `/v1`; on e.g. NVIDIA NIM (`https://integrate.api.nvidia.com`) the bare origin's
 * `/models` and `/chat/completions` are 404s (#28).
 */
fun v1Candidate(baseUrl: String): String? {
    val b = baseUrl.trim().trimEnd('/')
    val uri = runCatching { URI(b) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) return null
    return "$b/v1"
}

/** The listed models and the base URL that served them. */
data class FetchedModels<T>(val models: List<T>, val baseUrl: String)

/**
 * [fetchModels], retried once at [v1Candidate] when a bare-origin base URL answers HTTP 404: the
 * user then gets the working address filled in instead of a chat that 404s later. Only a 404 on
 * a pathless URL triggers it, and only a successful listing replaces the address.
 */
fun <T> fetchModelsResolving(baseUrl: String, key: String?, fetch: (String, String?) -> Result<List<T>>): Result<FetchedModels<T>> {
    val base = baseUrl.trim().trimEnd('/')
    val first = fetch(base, key)
    if (first.isSuccess) return first.map { FetchedModels(it, base) }
    val v1 = v1Candidate(base)
    if (v1 == null || first.exceptionOrNull()?.message?.startsWith("HTTP 404") != true) return first.map { FetchedModels(it, base) }
    return fetch(v1, key).map { FetchedModels(it, v1) }.takeIf { it.isSuccess } ?: first.map { FetchedModels(it, base) }
}

fun parseModelIds(body: String): List<String> = parseModels(body).map { it.id }

/**
 * Context-window fields seen in `/models` listings, in order of preference:
 * OpenRouter / Moonshot / Together / Fireworks `context_length`, Groq `context_window`,
 * vLLM / sglang `max_model_len`, Mistral / LM Studio `max_context_length`, Anthropic
 * `max_input_tokens`, llama.cpp `n_ctx`, Gemini `inputTokenLimit`.
 */
private val CONTEXT_KEYS = listOf("context_length", "context_window", "max_model_len", "max_context_length", "max_input_tokens", "n_ctx", "inputTokenLimit", "contextWindow", "context_size")
/** Output-cap fields: OpenAI-style `max_output_tokens`, Groq / OpenRouter `max_completion_tokens`, Gemini `outputTokenLimit`. */
private val OUTPUT_KEYS = listOf("max_output_tokens", "max_completion_tokens", "outputTokenLimit", "max_tokens", "maxOutputTokens", "maxTokens")
/** Nested objects that carry the same fields (OpenRouter `top_provider`, llama.cpp `meta`, DeepInfra `metadata`). */
private val NESTED_KEYS = listOf("top_provider", "meta", "metadata", "limits")

/** A positive whole number from a JSON number or numeric string (`262144`, `262144.0`, `"262144"`). */
private fun positiveCount(e: JsonElement?): Long? {
    val p = e as? JsonPrimitive ?: return null
    val d = p.content.trim().toDoubleOrNull() ?: return null
    if (!d.isFinite() || d < 1 || d != Math.floor(d) || d > Long.MAX_VALUE / 2) return null
    return d.toLong()
}

private fun firstCount(o: JsonObject, keys: List<String>): Long? {
    keys.forEach { k -> positiveCount(o[k])?.let { return it } }
    NESTED_KEYS.forEach { n -> (o[n] as? JsonObject)?.let { inner -> keys.forEach { k -> positiveCount(inner[k])?.let { return it } } } }
    return null
}

/** Parse a `/models` body into entries (sorted by id, distinct) with any reported limits. */
fun parseModels(body: String): List<RemoteModel> {
    val root = Json.parseToJsonElement(body)
    val entries: List<Pair<String?, JsonElement>> = when {
        root is JsonArray -> root.map { null to it }
        root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray.map { null to it }
        root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray.map { null to it }
        root is JsonObject && root["models"] is JsonObject -> root["models"]!!.jsonObject.entries.map { (k, v) -> k to v }
        else -> error("unexpected /models response")
    }
    return entries.mapNotNull { (key, e) ->
        runCatching {
            val o = e as? JsonObject
            val id = o?.let { (it["id"] ?: it["name"] ?: it["model"])?.jsonPrimitive?.content } ?: key ?: e.jsonPrimitive.content
            val ctx = o?.let { firstCount(it, CONTEXT_KEYS) }
            val out = o?.let { firstCount(it, OUTPUT_KEYS) }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            RemoteModel(id.removePrefix("models/"), ctx, out, o?.let(::listedAsChat), o?.let(::listedWithTools), o?.let(ModelVision::listed))
        }.getOrNull()
    }.filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.id }
}

/** The editor's model list after a successful fetch, and which kept entries the endpoint no longer returned. */
data class OfferedRefresh(val offered: List<String>, val missing: List<String>)

/**
 * After a successful `/models` fetch the list is exactly what the endpoint returned, except that
 * [keep] (models the user checked, or typed in by hand) the endpoint did not return stay on top,
 * flagged as missing, so the user decides about them instead of losing them silently.
 */
fun refreshOffered(current: List<String>, fetched: List<String>, keep: Collection<String>): OfferedRefresh {
    val returned = fetched.toSet()
    val keepSet = keep.toSet()
    val missing = (current.filter { it in keepSet } + keep).filter { it !in returned }.distinct()
    return OfferedRefresh(missing + fetched.distinct(), missing)
}

/** User text for a token count: digits (separators `,` `_` and spaces ignored); blank = null; else invalid. */
fun parseTokenInput(text: String): Result<Long?> {
    val t = text.filter { it != ',' && it != '_' && !it.isWhitespace() }
    if (t.isEmpty()) return Result.success(null)
    val n = t.toLongOrNull()?.takeIf { it > 0 } ?: return Result.failure(IllegalArgumentException(text))
    return Result.success(n)
}
