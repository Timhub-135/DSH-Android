package io.github.timhub135.dsh.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #39: vision models must get the image (not "[image omitted …]"), with an override per model. */
class ModelVisionTest {
    @Test fun engineTableMirrorMatchesTheVendoredAdapter() {
        // llm-service-lite MODEL_DEFAULT_RULES: family order, match patterns, and whether `modalities` names image.
        val js = File("../js/packages/llm-service-lite/lib/index.js").readText()
        val table = js.substringAfter("const MODEL_DEFAULT_RULES = [").substringBefore("\n];")
        val rules = table.split(Regex("""\n\t\{\n""")).drop(1).map { rule ->
            // Up to the array's own `],` (a character class's `]` is followed by `/`, never by `,` + newline).
            val match = rule.substringAfter("match: [").substringBefore("],\n")
            val patterns = Regex("""/((?:\\/|[^/\n])+)/""").findAll(match).map { it.groupValues[1] }.toList()
            val image = Regex("""modalities: \[([^\]]*)\]""").find(rule)?.groupValues?.get(1)?.contains("\"image\"") == true
            patterns to image
        }
        assertEquals(rules, ModelVision.ENGINE_FAMILIES)
    }

    @Test fun visionModelsTheEngineTableMissedAreRecognized() {
        listOf("gemini-2.5-flash", "google/gemini-3-pro-preview", "gpt-4o", "gpt-4o-mini", "openai/gpt-4.1", "qwen-vl-max", "qwen2.5-vl-72b-instruct",
            "Qwen/Qwen2.5-VL-7B-Instruct", "qvq-max", "qwen3-omni-flash", "glm-4v-plus", "glm-4.5v", "llama3.2-vision:11b", "meta-llama/llama-4-maverick",
            "pixtral-large-latest", "llava:13b", "minicpm-v", "internvl3-78b", "deepseek-vl2", "hunyuan-vision", "doubao-1.5-vision-pro", "ernie-4.5-vl-28b",
            "gemma3:27b", "grok-4", "claude-sonnet-4-5", "kimi-k3", "deepseek-flash", "gpt-5.1",
        ).forEach { assertTrue(it, ModelVision.guess(it)) }
    }

    @Test fun textModelsStayTextOnly() {
        listOf("deepseek-v4-pro", "deepseek-chat", "glm-5.3", "glm-4.5", "qwen3-coder-plus", "qwq-32b", "minimax-m2", "longcat-flash-chat", "hunyuan-t1",
            "mistral-7b-instruct-v0.3", "llama-3.3-70b-instruct", "some-unknown-model", "ernie-x1-turbo",
        ).forEach { assertFalse(it, ModelVision.guess(it)) }
        // The engine's own table for ids it knows: same answer as before (nothing regresses).
        assertTrue(ModelVision.engineDefault("deepseek-flash")); assertFalse(ModelVision.engineDefault("deepseek-v4-pro"))
        assertFalse(ModelVision.engineDefault("gemini-2.5-flash"))
    }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun listingsThatStateImageInput() {
        assertEquals(true, ModelVision.listed(obj("""{"id":"x","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}}""")))
        assertEquals(false, ModelVision.listed(obj("""{"id":"x","architecture":{"input_modalities":["text"]}}""")))
        assertEquals(true, ModelVision.listed(obj("""{"id":"x","modalities":{"input":["text","image"]}}""")))
        assertEquals(true, ModelVision.listed(obj("""{"id":"x","capabilities":{"vision":true,"function_calling":true}}""")))
        assertEquals(true, ModelVision.listed(obj("""{"id":"x","capabilities":["completion","vision","tools"]}""")))
        assertEquals(false, ModelVision.listed(obj("""{"id":"x","supports_image_input":false}""")))
        assertNull(ModelVision.listed(obj("""{"id":"x","object":"model","owned_by":"me"}""")))
        assertEquals(true, parseModels("""{"data":[{"id":"m","architecture":{"input_modalities":["image","text"]}}]}""").single().images)
    }

    @Test fun userSettingBeatsTheListingWhichBeatsTheName() {
        assertEquals(true to LimitSource.DEFAULT, ModelVision.resolve("gemini-2.5-pro", null))
        assertEquals(false to LimitSource.DETECTED, ModelVision.resolve("gemini-2.5-pro", ModelLimits(detectedImages = false)))
        assertEquals(true to LimitSource.MANUAL, ModelVision.resolve("my-private-vlm", ModelLimits(images = true, detectedImages = false)))
    }

    @Test fun patchDeclaresTheModalityAndTheChoiceKnowsIt() {
        val p = ProviderConfig("custom", CUSTOM, "gw", "https://gw.example/v1", listOf("gemini-2.5-pro", "private-a", "private-b"),
            limits = mapOf("private-a" to ModelLimits(images = true), "private-b" to ModelLimits(detectedImages = true, images = false)))
        val yml = renderProfilePatch(listOf(p), true)
        assertTrue(yml, yml.contains("""{"id":"gemini-2.5-pro","contextWindow":131072,"maxTokens":16384,"defaults":{"maxTokens":16384},"capabilities":{"modalities":["text","image"]}}"""))
        assertTrue(yml, yml.contains(""""id":"private-a",""") && yml.substringAfter(""""id":"private-a",""").substringBefore("}}").endsWith("""["text","image"]"""))
        assertTrue(yml, yml.substringAfter(""""id":"private-b",""").substringBefore("}}").endsWith("""["text"]"""))
        assertTrue(resolveChoice("custom", "private-a", listOf(p)).acceptsImages)
        assertFalse(resolveChoice("custom", "private-b", listOf(p)).acceptsImages)
        assertTrue(resolveChoice("", "deepseek-flash", listOf(p)).acceptsImages)
        assertFalse(resolveChoice("", "deepseek-v4-pro", listOf(p)).acceptsImages)
        // Stored settings round-trip, and an older record without the fields still decodes.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(p, json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), p)))
        assertNull(json.decodeFromString(ModelLimits.serializer(), """{"contextWindow":8192}""").images)
    }
}
