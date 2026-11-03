package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.engine.EngineRoute
import io.github.jackwener.dsh.core.provider.DEEPSEEK_MODELS
import io.github.jackwener.dsh.core.provider.ModelChoice
import io.github.jackwener.dsh.core.provider.ModelReasoning
import io.github.jackwener.dsh.core.provider.ReasoningLevels
import io.github.jackwener.dsh.core.provider.resolveChoice

/**
 * One conversation's own model (#53): [provider] "" = DeepSeek (key or account), else a third-party
 * provider's route id; [model] its model id; [effort] the DeepSeek Effort id, or the third-party
 * level (null = the model's default, nothing sent).
 *
 * Settings → 模型服务 → 默认模型 / 推理等级 is the default: a conversation the user starts takes a copy
 * when its row is written (its first message) and keeps it; the composer's model chip changes only
 * that copy. Rows without one (from before #53, automation runs) follow the default. Plugins'
 * background sessions never go through the app: the engine's `agent-default-model` row (the app's
 * settings layer) gives them the default.
 */
data class SessionModel(val provider: String, val model: String, val effort: String? = null) {
    val isDeepSeek: Boolean get() = provider.isEmpty()
}

/** The model a conversation runs now: its own [selection], or the default when it has none or [fellBack]. */
data class ResolvedModel(
    val selection: SessionModel,
    val choice: ModelChoice,
    /** The conversation's own model is no longer available (provider removed / disabled, model dropped): this is the default. */
    val fellBack: Boolean = false,
    /** The conversation has no model of its own (follows the default). */
    val followsDefault: Boolean = false,
) {
    /** DeepSeek effort (the chip and the menu; meaningless for a third-party model). */
    val effort: Effort get() = Effort.of(selection.effort)
    /** The third-party level the conversation saved (null = the model's default). */
    val reasoningChoice: String? get() = selection.effort.takeUnless { selection.isDeepSeek }
    /** The third-party model's reasoning selector from [catalog] (null for DeepSeek, or while unknown). */
    fun reasoning(catalog: Map<String, ModelReasoning>): ModelReasoning? = choice.provider?.let { ReasoningLevels.of(it, choice.model, catalog) }
}

object SessionModels {
    /** The settings default as a conversation's model. */
    fun default(st: Settings): SessionModel {
        val c = st.choice
        return c.provider?.let { SessionModel(it.id, c.model, st.reasoningChoice) } ?: SessionModel("", c.model, st.effort.id)
    }

    /** The row's model, or null (follows the default). */
    fun of(row: SessionEntity?): SessionModel? =
        row?.modelProvider?.let { p -> row.modelId?.takeIf { it.isNotEmpty() }?.let { SessionModel(p, it, row.modelEffort) } }

    /** Whether [m] can still run: a DeepSeek model the app offers, or an enabled provider that still lists the model. */
    fun available(m: SessionModel, st: Settings): Boolean =
        if (m.isDeepSeek) DEEPSEEK_MODELS.any { it.first == m.model }
        else st.providers.any { it.id == m.provider && it.enabled && m.model in it.models }

    /** What a conversation with [saved] (null = none of its own) runs as with [st]. */
    fun resolve(saved: SessionModel?, st: Settings): ResolvedModel {
        if (saved != null && available(saved, st)) return ResolvedModel(saved, resolveChoice(saved.provider, saved.model, st.providers))
        return ResolvedModel(default(st), st.choice, fellBack = saved != null, followsDefault = saved == null)
    }

    /**
     * The composer picked [model] on [provider] ("" = DeepSeek) for a conversation now on [current].
     * The effort carries over between DeepSeek models; otherwise it starts from the default's: DeepSeek's
     * 推理等级, or the level saved for that third-party model (#46, null = its default).
     */
    fun pick(current: SessionModel, provider: String, model: String, st: Settings): SessionModel = when {
        provider.isEmpty() -> SessionModel("", model, current.effort.takeIf { current.isDeepSeek } ?: st.effort.id)
        provider == current.provider && model == current.model -> current
        else -> SessionModel(provider, model, st.reasoningChoices[ReasoningLevels.key(provider, model)])
    }

    /**
     * The engine route of [m] (an available model, see [resolve]): DeepSeek on [deepSeekRoute]
     * (`deepseek-official` / `deepseek-account`) with its Effort id; a third-party model with its saved
     * level only while [catalog] says the model offers it, else none (dsh refuses any other level).
     */
    fun route(m: SessionModel, st: Settings, catalog: Map<String, ModelReasoning>, deepSeekRoute: String): EngineRoute {
        if (m.isDeepSeek) return EngineRoute(deepSeekRoute, m.model, Effort.of(m.effort).id)
        val reasoning = st.providers.firstOrNull { it.id == m.provider }?.let { ReasoningLevels.of(it, m.model, catalog) }
        return EngineRoute(m.provider, m.model, ReasoningLevels.effective(reasoning, m.effort).orEmpty())
    }
}
