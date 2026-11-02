package io.github.jackwener.dsh.core.provider

import io.github.jackwener.dsh.core.data.Settings
import io.github.jackwener.dsh.core.data.decodeChoices
import io.github.jackwener.dsh.core.data.withChoice
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #46: third-party models offer the reasoning levels the engine reports, per model, never one they do not accept. */
class ModelReasoningTest {
    private val kimi = ProviderConfig("kimi", "kimi", "Kimi", "https://api.moonshot.cn/v1", listOf("kimi-k3", "kimi-k2.6"))
    private val gw = ProviderConfig("custom", CUSTOM, "Gateway", "https://gw.example/v1", listOf("my-org/model"))

    /** What the engine answered for kimi's two models and the gateway model (an error for the last). */
    private val report = Json.parseToJsonElement("""{"models":[
        {"provider":"kimi","model":"kimi-k3","efforts":[{"id":"low","name":"Low"},{"id":"high","name":"High"},{"id":"max","name":"Max"}],"defaultEffort":"max"},
        {"provider":"kimi","model":"kimi-k2.6","efforts":[{"id":"enabled","name":"Enabled"},{"id":"disabled","name":"Disabled"}]},
        {"provider":"custom","model":"my-org/model","efforts":[]},
        {"provider":"gone","model":"x","error":"no adapter registered for provider \"gone\""}]}""")
    private val catalog = ReasoningLevels.parse(report) { _, _ -> null }

    @Test fun vocabularyMatchesTheVendoredAdapter() {
        val js = File("../js/packages/llm-service-lite/lib/index.js").readText()
        val levels = Regex("\"([a-z]+)\"").findAll(js.substringAfter("const EFFORT_LEVELS = [").substringBefore("];")).map { it.groupValues[1] }.toList()
        assertEquals(levels, ReasoningLevels.EFFORT)
        val labels = Regex("""(\w+): "([^"]+)"""").findAll(js.substringAfter("const LEVEL_LABELS = {").substringBefore("};")).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(labels, ReasoningLevels.LABELS)
    }

    @Test fun engineReportParses() {
        assertEquals(setOf("kimi/kimi-k3", "kimi/kimi-k2.6", "custom/my-org/model"), catalog.keys)
        val k3 = catalog.getValue("kimi/kimi-k3")
        assertEquals(listOf("low", "high", "max"), k3.levels.map { it.id }); assertEquals("max", k3.default)
        assertNull(catalog.getValue("kimi/kimi-k2.6").default)
        assertTrue(catalog.getValue("custom/my-org/model").levels.isEmpty())
        // A default the levels do not contain is dropped; the declaration the engine read is recorded.
        val decl = ReasoningDecl(ReasoningControl.Effort, listOf("low"))
        val odd = ReasoningLevels.parse(Json.parseToJsonElement("""{"models":[{"provider":"p","model":"m","efforts":[{"id":"low","name":"Low"}],"defaultEffort":"max"}]}""")) { _, _ -> decl }
        assertEquals(ModelReasoning(listOf(ReasoningLevel("low", "Low")), null, decl), odd["p/m"])
        assertTrue(ReasoningLevels.parse(Json.parseToJsonElement("{}")) { _, _ -> null }.isEmpty())
    }

    @Test fun menuListsExactlyTheModelsLevelsPlusDefault() {
        val k3 = catalog["kimi/kimi-k3"]
        val menu = ReasoningLevels.menu(k3, null, "默认")!!
        assertEquals(listOf(null, "low", "high", "max"), menu.map { it.id })
        assertEquals("默认 · Max", menu[0].name)
        assertEquals(listOf(true, false, false, false), menu.map { it.selected })
        assertEquals(listOf(false, false, false, true), menu.map { it.isDefault })
        assertEquals("low", ReasoningLevels.menu(k3, "low", "默认")!!.single { it.selected }.id)
        // A thinking switch: Enabled / Disabled, no default level to name.
        val switch = ReasoningLevels.menu(catalog["kimi/kimi-k2.6"], "disabled", "默认")!!
        assertEquals(listOf("默认", "Enabled", "Disabled"), switch.map { it.name })
        assertEquals("disabled", switch.single { it.selected }.id)
        // No control, or not known yet: no section.
        assertNull(ReasoningLevels.menu(catalog["custom/my-org/model"], null, "默认"))
        assertNull(ReasoningLevels.menu(null, "high", "默认"))
    }

    @Test fun unsupportedSavedLevelFallsBackToDefault() {
        val k3 = catalog["kimi/kimi-k3"]
        assertEquals("high", ReasoningLevels.effective(k3, "high"))
        // Saved for another model / an older declaration: never sent, 默认 is selected instead.
        assertNull(ReasoningLevels.effective(k3, "disabled"))
        assertTrue(ReasoningLevels.menu(k3, "disabled", "默认")!![0].selected)
        assertNull(ReasoningLevels.effective(null, "high"))
        assertNull(ReasoningLevels.effective(k3, null))
    }

    @Test fun selectionIsPerProviderAndModel() {
        val choices = withChoice(withChoice(emptyMap(), "kimi", "kimi-k3", "low"), "kimi", "kimi-k2.6", "disabled")
        val st = Settings(provider = "kimi", model = "kimi-k3", providers = listOf(kimi, gw), reasoningChoices = choices, reasoningCatalog = catalog)
        assertEquals("low", st.reasoningEffort)
        // Switching models applies that model's own saved level.
        assertEquals("disabled", st.copy(model = "kimi-k2.6").reasoningEffort)
        assertNull(st.copy(provider = "custom", model = "my-org/model").reasoningEffort)
        // DeepSeek keeps its own Effort setting; the per-model map does not apply.
        assertNull(st.copy(provider = "", model = "deepseek-flash").reasoningEffort)
        // A saved level the model no longer offers (engine update, new declaration) is not sent.
        assertNull(st.copy(reasoningChoices = withChoice(choices, "kimi", "kimi-k3", "xhigh")).reasoningEffort)
        // Unknown until the engine has answered once: nothing is sent.
        assertNull(st.copy(reasoningCatalog = emptyMap()).reasoningEffort)
        // Back to the default removes the entry; the map survives its JSON round trip.
        assertEquals(mapOf("kimi/kimi-k2.6" to "disabled"), withChoice(choices, "kimi", "kimi-k3", null))
        val json = Json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), choices)
        assertEquals(choices, decodeChoices(json))
        assertTrue(decodeChoices("not json").isEmpty())
    }

    @Test fun declarationWinsOverAStaleEngineAnswer() {
        val decl = ReasoningDecl(ReasoningControl.EnableThinking)
        val declared = gw.copy(limits = mapOf("my-org/model" to ModelLimits(reasoning = decl)))
        // The engine answered before the declaration (no levels): until it restarts, the declaration's own levels show.
        val r = ReasoningLevels.of(declared, "my-org/model", catalog)!!
        assertEquals(listOf("enabled", "disabled"), r.levels.map { it.id }); assertNull(r.default)
        // Once the engine answered under the declaration, its answer is used.
        val fresh = mapOf("custom/my-org/model" to ModelReasoning(listOf(ReasoningLevel("enabled", "On?")), null, decl))
        assertEquals("On?", ReasoningLevels.of(declared, "my-org/model", fresh)!!.levels.single().name)
        // Declaration removed again: the old declared answer is stale, nothing known until the next start.
        assertNull(ReasoningLevels.of(gw, "my-org/model", fresh))
        assertEquals(catalog["kimi/kimi-k3"], ReasoningLevels.of(kimi, "kimi-k3", catalog))
        assertTrue(ReasoningDecl(ReasoningControl.None).reasoning().levels.isEmpty())
        assertEquals(listOf("low", "high"), ReasoningDecl(ReasoningControl.Effort, listOf("high", "low", "bogus")).reasoning().levels.map { it.id })
    }

    @Test fun patchCarriesTheDeclarationOnly() {
        val p = gw.copy(models = listOf("a", "b", "c", "d", "e"), limits = mapOf(
            "b" to ModelLimits(reasoning = ReasoningDecl(ReasoningControl.Effort, listOf("high", "low"))),
            "c" to ModelLimits(reasoning = ReasoningDecl(ReasoningControl.EnableThinking)),
            "d" to ModelLimits(reasoning = ReasoningDecl(ReasoningControl.ThinkingType)),
            "e" to ModelLimits(reasoning = ReasoningDecl(ReasoningControl.None)),
        ))
        val yml = renderProfilePatch(listOf(p), true)
        fun caps(m: String) = yml.substringAfter("{\"id\":\"$m\"").substringAfter("\"capabilities\":").substringBefore("}") + "}"
        // 自动: modalities only, the engine's family table decides (unchanged output).
        assertEquals("{\"modalities\":[\"text\"]}", caps("a"))
        assertEquals("{\"modalities\":[\"text\"],\"effort\":[\"low\",\"high\"],\"thinking\":[],\"thinkingStyle\":\"effort_only\"}", caps("b"))
        assertEquals("{\"modalities\":[\"text\"],\"effort\":[],\"thinking\":[\"enabled\",\"disabled\"],\"thinkingStyle\":\"enable_thinking\"}", caps("c"))
        assertEquals("{\"modalities\":[\"text\"],\"effort\":[],\"thinking\":[\"enabled\",\"disabled\"],\"thinkingStyle\":\"thinking\"}", caps("d"))
        assertEquals("{\"modalities\":[\"text\"],\"effort\":[],\"thinking\":[],\"thinkingStyle\":\"effort_only\"}", caps("e"))
        // The declaration is stored with the provider and survives its JSON round trip.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(p, json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), p)))
        assertFalse(ModelLimits(reasoning = ReasoningDecl(ReasoningControl.None)).isEmpty)
    }
}
