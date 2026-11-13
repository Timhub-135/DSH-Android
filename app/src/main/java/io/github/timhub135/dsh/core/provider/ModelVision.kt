package io.github.timhub135.dsh.core.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a model accepts image input (#39: "a vision model says it cannot see images").
 *
 * The engine (llm-service-lite) sends a request image only to a model whose profile declares the
 * `image` modality; any other model gets the text `[image omitted because this model accepts text
 * only …]` in its place, so the model honestly answers that it sees no image. Without a declaration
 * the engine falls back to its family table, which knows only some vendors' ids: gemini, gpt-4o /
 * 4.1, qwen-vl, glm-4v, llava, pixtral… were all treated as text only. The app therefore always
 * declares the modality itself, from (in order) the user's per-model setting, what the provider's
 * `/models` listing says, and [guess].
 */
object ModelVision {
    /**
     * The engine's family table (llm-service-lite MODEL_DEFAULT_RULES, same order, first match wins):
     * patterns and whether the family takes images. ModelVisionTest checks it against the vendored JS.
     */
    internal val ENGINE_FAMILIES: List<Pair<List<String>, Boolean>> = listOf(
        listOf("^claude-(fable-5|sonnet-5)") to true,
        listOf("^claude-opus-5") to true,
        listOf("^claude-(opus-5|sonnet-5|haiku-5|fable-5)") to true,
        listOf("^claude-(opus-4-[678]|sonnet-4-6)") to true,
        listOf("^claude-(opus-4-5|sonnet-4-5|haiku-4-5)") to true,
        listOf("^claude-") to true,
        listOf("^k3(-256k)?$") to true,
        listOf("^kimi-for-coding") to true,
        listOf("^minimax-m3\\.1") to true,
        listOf("^minimax-m3") to true,
        listOf("^minimax-m2") to false,
        listOf("^deepseek-(v4-flash|v4\\.1|flash)") to true,
        listOf("^deepseek-v4") to false,
        listOf("^deepseek-") to false,
        listOf("^kimi-k3") to true,
        listOf("^kimi-k2\\.7") to true,
        listOf("^kimi-k2\\.6") to true,
        listOf("^kimi-", "^moonshot") to true,
        listOf("^glm-5\\.3-flash") to true,
        listOf("^glm-5v", "^glm-4\\.6v") to true,
        listOf("^glm-5\\.3") to false,
        listOf("^glm-5\\.2") to false,
        listOf("^glm-") to false,
        listOf("^qwen3\\.8-max") to true,
        listOf("^qwen3\\.8") to true,
        listOf("^qwen3\\.7-max") to false,
        listOf("^qwen3\\.[5-8]") to true,
        listOf("^qwen", "^qwq", "^qvq") to false,
        listOf("^doubao-seed-2-0", "^doubao-seed-character") to true,
        listOf("^doubao-", "^seed-", "\\/seed-") to true,
        listOf("^hy4", "^hunyuan-4") to false,
        listOf("^hy\\d", "^hunyuan") to false,
        listOf("^ernie-5\\.0") to true,
        listOf("^ernie-5\\.1") to false,
        listOf("^ernie-x1") to false,
        listOf("^ernie-.*-vl") to true,
        listOf("^step-5") to true,
        listOf("^step-\\d") to true,
        listOf("^mimo-") to true,
        listOf("^sensenova-") to true,
        listOf("^longcat") to false,
        listOf("^gpt-5\\.1") to true,
        listOf("^gpt-5", "^gpt-6", "^o[1-4]") to true,
    )
    private val engineRules = ENGINE_FAMILIES.map { (p, img) -> p.map(::Regex) to img }

    /** A marker in the id that names a vision variant: qwen2.5-vl, glm-4.5v, llama-3.2-11b-vision, qwen3-omni, minicpm-v… */
    private val VISION_MARKER = Regex("""(^|[-_/.:])(vl|vision|omni|multimodal)($|[-_/.:\d])|\dvl($|[-_/.:])|\d(\.\d+)?v($|[-_/.:])|(^|[-_/])minicpm-v|internvl|llava|pixtral|qvq""")
    /** Vision families the engine table does not know (the first matching spelling wins, like the engine's). */
    private val VISION_FAMILIES = listOf(
        "^gemini", "^gemma-?3", "^gpt-4o", "^chatgpt-4o", "^gpt-4\\.1", "^gpt-4-turbo", "^llama-?4", "^grok-(4|2-vision)",
        "^mistral-(medium|small-3\\.[12])", "^moondream", "^phi-4-multimodal",
    ).map(::Regex)

    /** The engine's id spellings: lowercased, without a leading `~`, and every suffix after a `vendor/` prefix. */
    private fun spellings(id: String): List<String> {
        val lower = id.lowercase()
        var rest = lower.removePrefix("~")
        val out = mutableListOf(lower, rest)
        while ('/' in rest) { rest = rest.substringAfter('/'); out += rest }
        return out
    }

    /** What the engine decides for [id] without a declaration (its family table; unknown = text only). */
    fun engineDefault(id: String): Boolean {
        val s = spellings(id)
        return engineRules.firstOrNull { (patterns, _) -> patterns.any { p -> s.any { p.containsMatchIn(it) } } }?.second ?: false
    }

    /** Name-based guess: an explicit vision marker, a known vision family, else the engine's own table. */
    fun guess(id: String): Boolean {
        val s = spellings(id)
        if (s.any { VISION_MARKER.containsMatchIn(it) }) return true
        if (VISION_FAMILIES.any { p -> s.any { p.containsMatchIn(it) } }) return true
        return engineDefault(id)
    }

    /**
     * Image input as a `/models` entry states it: OpenRouter `architecture.input_modalities`,
     * `input_modalities` / `modalities.input` (models.dev style), a `capabilities` array with
     * "vision" (Ollama) or object with `vision` (Mistral), `supports_vision` / `supports_image_input`
     * (LiteLLM, Fireworks). Null = the listing does not say.
     */
    internal fun listed(o: JsonObject): Boolean? {
        fun strings(e: Any?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        val inputs = strings((o["architecture"] as? JsonObject)?.get("input_modalities")) ?: strings(o["input_modalities"])
            ?: strings((o["modalities"] as? JsonObject)?.get("input"))
        if (!inputs.isNullOrEmpty()) return "image" in inputs
        when (val caps = o["capabilities"]) {
            is JsonArray -> strings(caps)?.takeIf { it.isNotEmpty() }?.let { return "vision" in it || "image" in it }
            is JsonObject -> (caps["vision"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()?.let { return it }
            else -> {}
        }
        return listOf("supports_vision", "supports_image_input", "vision").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
    }

    /** Effective image input and where it came from: the user's setting, the listing, else the name ([LimitSource.DEFAULT]). */
    fun resolve(id: String, limits: ModelLimits?): Pair<Boolean, LimitSource> = when {
        limits?.images != null -> limits.images to LimitSource.MANUAL
        limits?.detectedImages != null -> limits.detectedImages to LimitSource.DETECTED
        else -> guess(id) to LimitSource.DEFAULT
    }
}
