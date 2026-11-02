package io.github.jackwener.dsh.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Reasoning effort for third-party models (#46).
 *
 * The engine (llm-service-lite) knows each model family's levels: `reasoning_effort` levels (Kimi
 * K3: low / high / max), a thinking switch (Qwen 3.5+: enabled / disabled, sent as
 * `enable_thinking`) or nothing, and the level it applies when none is sent. The app asks it for
 * them (`android/models/reasoning`, plugin-guard) at every engine start and keeps the answer
 * ([ModelReasoning], `Settings.reasoningCatalog`); the composer lists exactly those levels plus
 * 默认 (send none), and only a level of that list ever reaches `initialize { reasoningEffort }`
 * (dsh refuses any other with UNSUPPORTED_REASONING_EFFORT, which would fail the route).
 *
 * For ids the family table does not know (or misreads) the user declares the control in the
 * provider editor (推理控制, [ReasoningDecl]); the app writes it into the model's profile
 * (`capabilities.effort` / `thinking` / `thinkingStyle`, js patch 22 makes it replace the family's)
 * and the engine's own mapping spells the wire fields. Priority: the user's declaration › the
 * engine's family table (a `/models` listing does not report reasoning levels).
 */
@Serializable
enum class ReasoningControl {
    /** No reasoning control: nothing is sent, not even a family default. */
    None,
    /** `reasoning_effort` with the declared [ReasoningDecl.levels]. */
    Effort,
    /** A thinking switch sent as top-level `enable_thinking: true|false` (DashScope / Qwen style). */
    EnableThinking,
    /** A thinking switch sent as `thinking: { type: "enabled"|"disabled" }` (Zhipu / Moonshot style). */
    ThinkingType,
}

/** The user's 推理控制 declaration for one model (null in [ModelLimits.reasoning] = 自动, the engine's family table). */
@Serializable
data class ReasoningDecl(val control: ReasoningControl, val levels: List<String> = emptyList()) {
    /** The `capabilities` fields the engine profile gets for this declaration. */
    fun writeTo(caps: JsonObjectBuilder) {
        val effort = if (control == ReasoningControl.Effort) ReasoningLevels.EFFORT.filter { it in levels } else emptyList()
        val thinking = if (control == ReasoningControl.EnableThinking || control == ReasoningControl.ThinkingType) ReasoningLevels.SWITCH else emptyList()
        caps.putJsonArray("effort") { effort.forEach { add(it) } }
        caps.putJsonArray("thinking") { thinking.forEach { add(it) } }
        // The style is what marks the lists as declared (js patch 22; the engine's schema turns an
        // absent list into []): a style with no levels = no control, no family default level either.
        when (control) {
            ReasoningControl.None, ReasoningControl.Effort -> caps.put("thinkingStyle", "effort_only")
            ReasoningControl.EnableThinking -> caps.put("thinkingStyle", "enable_thinking")
            ReasoningControl.ThinkingType -> caps.put("thinkingStyle", "thinking")
        }
    }

    /** What the engine resolves this declaration to (its `reasoningLevels`: effort levels, else the modes; no default level). */
    fun reasoning(): ModelReasoning = ModelReasoning(
        when (control) {
            ReasoningControl.None -> emptyList()
            ReasoningControl.Effort -> ReasoningLevels.EFFORT.filter { it in levels }
            ReasoningControl.EnableThinking, ReasoningControl.ThinkingType -> ReasoningLevels.SWITCH
        }.map { ReasoningLevel(it, ReasoningLevels.label(it)) },
        default = null, decl = this,
    )
}

/** One selectable level: the engine's id (sent as `reasoningEffort`) and label. */
@Serializable
data class ReasoningLevel(val id: String, val name: String)

/**
 * One model's reasoning selector as the engine resolved it: [levels] (empty = no control), the
 * level it applies when none is sent ([default], null = none, the endpoint's own behaviour), and
 * the user's declaration it was resolved under ([decl]; a changed declaration makes the entry stale).
 */
@Serializable
data class ModelReasoning(val levels: List<ReasoningLevel> = emptyList(), val default: String? = null, val decl: ReasoningDecl? = null) {
    fun level(id: String?): ReasoningLevel? = levels.firstOrNull { it.id == id }
}

/** One row of the composer's 推理强度 section: [id] null = 默认 (no level sent). */
data class ReasoningEntry(val id: String?, val name: String, val isDefault: Boolean, val selected: Boolean)

object ReasoningLevels {
    /** llm-service-lite EFFORT_LEVELS (the profile vocabulary, in its order). */
    val EFFORT = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    /** The thinking modes a declared switch offers. */
    val SWITCH = listOf("enabled", "disabled")
    /** Levels a new 推理档位 declaration starts with (the common OpenAI-style trio). */
    val EFFORT_START = listOf("low", "medium", "high")

    /** llm-service-lite LEVEL_LABELS (ReasoningTest checks them against the vendored JS); the engine's answer carries the same. */
    internal val LABELS = mapOf(
        "minimal" to "Minimal", "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra high", "max" to "Max",
        "ultra" to "Ultra", "adaptive" to "Adaptive", "enabled" to "Enabled", "disabled" to "Disabled",
    )
    fun label(id: String) = LABELS[id] ?: id

    /** Settings map key of one route + model (route ids never contain `/`). */
    fun key(provider: String, model: String) = "$provider/$model"

    /**
     * The selector of [model] on [p]: the engine's answer while it was resolved under the current
     * declaration; after the user changed the declaration (the engine reads it at its next start)
     * what the engine will resolve it to; null = not known yet (no engine start since the model was added).
     */
    fun of(p: ProviderConfig, model: String, catalog: Map<String, ModelReasoning>): ModelReasoning? {
        val decl = p.limits[model]?.reasoning
        val known = catalog[key(p.id, model)]
        return when {
            known != null && known.decl == decl -> known
            decl != null -> decl.reasoning()
            else -> null
        }
    }

    /** The level to send: the saved choice while the model still offers it, else none (the model's default). */
    fun effective(reasoning: ModelReasoning?, saved: String?): String? = saved?.takeIf { reasoning?.level(it) != null }

    /** The composer rows: 默认 (naming the default level when there is one), then each level; null = no section. */
    fun menu(reasoning: ModelReasoning?, saved: String?, defaultLabel: String): List<ReasoningEntry>? {
        if (reasoning == null || reasoning.levels.isEmpty()) return null
        val chosen = effective(reasoning, saved)
        val def = reasoning.level(reasoning.default)
        return listOf(ReasoningEntry(null, def?.let { "$defaultLabel · ${it.name}" } ?: defaultLabel, isDefault = false, selected = chosen == null)) +
            reasoning.levels.map { ReasoningEntry(it.id, it.name, isDefault = it.id == def?.id, selected = it.id == chosen) }
    }

    /**
     * Parse the engine's `android/models/reasoning` answer into catalog entries, each recorded with
     * the declaration [declOf] says the running engine read. Routes that answered an error are left out.
     */
    fun parse(result: JsonElement, declOf: (String, String) -> ReasoningDecl?): Map<String, ModelReasoning> {
        val models = ((result as? JsonObject)?.get("models") as? JsonArray) ?: return emptyMap()
        val out = LinkedHashMap<String, ModelReasoning>()
        models.forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val provider = str("provider") ?: return@forEach
            val model = str("model") ?: return@forEach
            if (o["error"] != null) return@forEach
            val levels = (o["efforts"] as? JsonArray).orEmpty().mapNotNull { l ->
                val lo = l as? JsonObject ?: return@mapNotNull null
                val id = (lo["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ReasoningLevel(id, (lo["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: label(id))
            }.distinctBy { it.id }
            val default = str("defaultEffort")?.takeIf { d -> levels.any { it.id == d } }
            out[key(provider, model)] = ModelReasoning(levels, default, declOf(provider, model))
        }
        return out
    }
}
