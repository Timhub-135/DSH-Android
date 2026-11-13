package io.github.timhub135.dsh.core.session

import io.github.timhub135.dsh.core.data.Effort
import io.github.timhub135.dsh.core.data.SessionEntity
import io.github.timhub135.dsh.core.data.Settings
import io.github.timhub135.dsh.core.engine.EngineRoute
import io.github.timhub135.dsh.core.provider.ModelReasoning
import io.github.timhub135.dsh.core.provider.ProviderConfig
import io.github.timhub135.dsh.core.provider.ReasoningLevel
import io.github.timhub135.dsh.core.provider.renderProfilePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #53: each conversation keeps its own model and effort; the settings value is the default for new ones. */
class SessionModelTest {
    private val kimi = ProviderConfig("kimi", "kimi", "Kimi", "https://api.moonshot.cn/v1", listOf("kimi-k3", "kimi-k2.6"))
    private val catalog = mapOf(
        "kimi/kimi-k3" to ModelReasoning(listOf(ReasoningLevel("low", "Low"), ReasoningLevel("high", "High"), ReasoningLevel("max", "Max")), "max"),
    )
    /** Default: DeepSeek flash on Low; Kimi K3 remembers Max (#46). */
    private val st = Settings(provider = "", model = "deepseek-flash", effort = Effort.Low, providers = listOf(kimi),
        reasoningChoices = mapOf("kimi/kimi-k3" to "max"), reasoningCatalog = catalog)

    @Test fun defaultIsTheSettingsSelection() {
        assertEquals(SessionModel("", "deepseek-flash", "low"), SessionModels.default(st))
        assertEquals(SessionModel("kimi", "kimi-k3", "max"), SessionModels.default(st.copy(provider = "kimi", model = "kimi-k3")))
        // A third-party default without a saved level sends none (the model's default).
        assertEquals(SessionModel("kimi", "kimi-k2.6", null), SessionModels.default(st.copy(provider = "kimi", model = "kimi-k2.6")))
    }

    @Test fun aConversationWithoutItsOwnModelFollowsTheDefault() {
        val r = SessionModels.resolve(null, st)
        assertTrue(r.followsDefault); assertFalse(r.fellBack)
        assertEquals(SessionModels.default(st), r.selection)
        assertTrue(r.choice.isDeepSeek); assertEquals(Effort.Low, r.effort)
        // Changing the default changes it (rows from before #53, automation runs).
        assertEquals("deepseek-v4-pro", SessionModels.resolve(null, st.copy(model = "deepseek-v4-pro")).choice.model)
    }

    @Test fun aConversationKeepsItsOwnModelWhateverTheDefaultBecomes() {
        val own = SessionModel("kimi", "kimi-k3", "high")
        for (default in listOf(st, st.copy(model = "deepseek-v4-pro", effort = Effort.Max), st.copy(provider = "kimi", model = "kimi-k2.6"))) {
            val r = SessionModels.resolve(own, default)
            assertEquals(own, r.selection); assertFalse(r.fellBack); assertFalse(r.followsDefault)
            assertEquals("kimi", r.choice.provider?.id); assertEquals("kimi-k3", r.choice.model)
            assertEquals("high", r.reasoningChoice)
            assertEquals(catalog["kimi/kimi-k3"], r.reasoning(default.reasoningCatalog))
        }
        val ds = SessionModels.resolve(SessionModel("", "deepseek-v4-pro", "max"), st)
        assertEquals(Effort.Max, ds.effort); assertNull(ds.reasoningChoice); assertNull(ds.reasoning(catalog))
    }

    @Test fun aGoneModelFallsBackToTheDefault() {
        val own = SessionModel("kimi", "kimi-k3", "high")
        for (gone in listOf(
            st.copy(providers = emptyList()),                                  // provider removed
            st.copy(providers = listOf(kimi.copy(enabled = false))),           // disabled
            st.copy(providers = listOf(kimi.copy(models = listOf("kimi-k2.6")))), // model dropped
        )) {
            val r = SessionModels.resolve(own, gone)
            assertTrue(r.fellBack); assertFalse(r.followsDefault)
            assertEquals(SessionModels.default(gone), r.selection)
            assertTrue(r.choice.isDeepSeek)
        }
        // A DeepSeek model id the app no longer offers.
        assertTrue(SessionModels.resolve(SessionModel("", "deepseek-v3", "high"), st).fellBack)
    }

    @Test fun pickingAModelInTheComposer() {
        val ds = SessionModel("", "deepseek-flash", "max")
        // Between DeepSeek models the conversation's effort carries over.
        assertEquals(SessionModel("", "deepseek-v4-pro", "max"), SessionModels.pick(ds, "", "deepseek-v4-pro", st))
        // A third-party model starts from the level remembered for it (#46), or its default.
        assertEquals(SessionModel("kimi", "kimi-k3", "max"), SessionModels.pick(ds, "kimi", "kimi-k3", st))
        assertEquals(SessionModel("kimi", "kimi-k2.6", null), SessionModels.pick(ds, "kimi", "kimi-k2.6", st))
        // Re-picking the same third-party model keeps the conversation's level.
        val k3 = SessionModel("kimi", "kimi-k3", "low")
        assertEquals(k3, SessionModels.pick(k3, "kimi", "kimi-k3", st))
        // Back to DeepSeek: the default 推理等级.
        assertEquals(SessionModel("", "deepseek-flash", "low"), SessionModels.pick(k3, "", "deepseek-flash", st))
    }

    @Test fun engineRoutes() {
        assertEquals(EngineRoute("deepseek-account", "deepseek-flash", "max"), SessionModels.route(SessionModel("", "deepseek-flash", "max"), st, catalog, "deepseek-account"))
        // An unknown stored DeepSeek effort reads as the default level (Effort.of).
        assertEquals("high", SessionModels.route(SessionModel("", "deepseek-flash", null), st, catalog, "deepseek-official").effort)
        assertEquals(EngineRoute("kimi", "kimi-k3", "low"), SessionModels.route(SessionModel("kimi", "kimi-k3", "low"), st, catalog, "deepseek-official"))
        // A level the engine does not list for the model is not sent (dsh would refuse it).
        assertEquals(EngineRoute("kimi", "kimi-k3", ""), SessionModels.route(SessionModel("kimi", "kimi-k3", "xhigh"), st, catalog, "deepseek-official"))
        // Levels not known yet: none sent.
        assertEquals("", SessionModels.route(SessionModel("kimi", "kimi-k2.6", "high"), st, catalog, "deepseek-official").effort)
    }

    @Test fun rowsCarryTheModel() {
        val row = SessionEntity("s", "t", 0, 0)
        assertNull(SessionModels.of(row)); assertNull(SessionModels.of(null))
        assertEquals(SessionModel("kimi", "kimi-k3", null), SessionModels.of(row.copy(modelProvider = "kimi", modelId = "kimi-k3")))
        assertEquals(SessionModel("", "deepseek-flash", "off"), SessionModels.of(row.copy(modelProvider = "", modelId = "deepseek-flash", modelEffort = "off")))
        assertNull(SessionModels.of(row.copy(modelProvider = "kimi", modelId = null)))
    }

    @Test fun pluginsSessionsGetTheDefaultThroughTheAgentDefaultModelRow() {
        val yml = renderProfilePatch(listOf(kimi), webSearch = true, defaultModel = EngineRoute("deepseek-official", "deepseek-flash", "low"))
        assertTrue(yml, yml.contains("- id: agent-default-model\n  config: {\"provider\":\"deepseek-official\",\"model\":\"deepseek-flash\",\"reasoningEffort\":\"low\"}\n"))
        val noLevel = renderProfilePatch(emptyList(), webSearch = true, defaultModel = EngineRoute("kimi", "kimi-k2.6", ""))
        assertTrue(noLevel, noLevel.endsWith("- id: agent-default-model\n  config: {\"provider\":\"kimi\",\"model\":\"kimi-k2.6\"}\n"))
        assertFalse(renderProfilePatch(emptyList(), webSearch = true).contains("agent-default-model"))
    }
}
