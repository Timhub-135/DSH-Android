package io.github.timhub135.dsh.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val kimi = ProviderConfig("kimi", "kimi", "Kimi", "https://api.moonshot.cn/v1/", listOf("kimi-k3"), hasKey = true)
    private val custom = ProviderConfig("custom", CUSTOM, "My gateway", "https://gw.example/v1", listOf("a", "b"))

    @Test fun routeIdsAreUniqueAndNeverDeepSeekRoutes() {
        assertEquals("kimi", newRouteId("kimi", emptyList()))
        assertEquals("kimi-2", newRouteId("kimi", listOf("kimi")))
        assertEquals("kimi-3", newRouteId("kimi", listOf("kimi", "kimi-2")))
        assertFalse(newRouteId("deepseek-official", emptyList()).startsWith("deepseek"))
    }

    @Test fun bareOriginGetsV1OnlyWhenItHelps() {
        assertEquals("https://integrate.api.nvidia.com/v1", v1Candidate("https://integrate.api.nvidia.com/ "))
        assertEquals("http://192.168.1.5:8080/v1", v1Candidate("http://192.168.1.5:8080"))
        for (u in listOf("https://api.openai.com/v1", "https://gw.example/openai", "https://h?x=1", "ftp://h", "not a url", ""))
            assertNull(u, v1Candidate(u))

        val asked = mutableListOf<String>()
        val nim: (String, String?) -> Result<List<String>> = { url, _ ->
            asked += url
            if (url.endsWith("/v1")) Result.success(listOf("nvidia/m")) else Result.failure(IllegalStateException("HTTP 404"))
        }
        assertEquals(FetchedModels(listOf("nvidia/m"), "https://integrate.api.nvidia.com/v1"), fetchModelsResolving("https://integrate.api.nvidia.com/", "k", nim).getOrThrow())
        assertEquals(listOf("https://integrate.api.nvidia.com", "https://integrate.api.nvidia.com/v1"), asked)
        // Not a 404, or the base already has a path: no second request, the original failure stands.
        asked.clear()
        val down: (String, String?) -> Result<List<String>> = { url, _ -> asked += url; Result.failure(IllegalStateException("HTTP 401 unauthorized")) }
        assertEquals("HTTP 401 unauthorized", fetchModelsResolving("https://h", null, down).exceptionOrNull()?.message)
        assertEquals("HTTP 404", fetchModelsResolving("https://h/api", null, nim).exceptionOrNull()?.message)
        assertEquals(listOf("https://h", "https://h/api"), asked)
        // /v1 fails too: the original 404 is reported.
        val gone: (String, String?) -> Result<List<String>> = { url, _ -> Result.failure(IllegalStateException(if (url.endsWith("/v1")) "HTTP 500" else "HTTP 404")) }
        assertEquals("HTTP 404", fetchModelsResolving("https://h", null, gone).exceptionOrNull()?.message)
    }

    @Test fun keyEnvIsAValidCredentialRef() {
        assertEquals("DSH_ANDROID_KEY_KIMI_2", keyEnvOf("kimi-2"))
        assertTrue(Regex("^[A-Z_][A-Z0-9_]*$").matches(keyEnvOf("custom-12")))
    }

    @Test fun responsesRouteSpeaksItsProtocol() {
        // #59: interfaceType from the provider's api; includeUsage only on Chat Completions (the engine refuses it elsewhere).
        val yml = renderProfilePatch(listOf(kimi, custom.copy(api = API_RESPONSES)), webSearch = true)
        assertTrue(yml.contains("\"custom\":{\"displayName\":\"My gateway\",\"interfaceType\":\"openai-responses\",\"baseURL\":\"https://gw.example/v1\""))
        assertEquals(1, Regex("\"includeUsage\":true").findAll(yml).count())
        assertTrue(yml.contains("\"interfaceType\":\"openai-chat-completions\""))
        // An unknown value (a newer app's) reads as Chat Completions.
        assertEquals(API_CHAT, custom.copy(api = "something-else").interfaceType)
        // Settings saved before #59 have no api field.
        assertEquals(API_CHAT, kotlinx.serialization.json.Json.decodeFromString(ProviderConfig.serializer(),
            """{"id":"k","preset":"kimi","name":"K","baseUrl":"https://h/v1","models":["m"]}""").api)
    }

    @Test fun pastedEndpointPathsAreDropped() {
        assertEquals("https://api.openai.com/v1", normalizeBaseUrl(" https://api.openai.com/v1/responses/ "))
        assertEquals("https://gw.example/v1", normalizeBaseUrl("https://gw.example/v1/chat/completions"))
        assertEquals("https://gw.example/v1", normalizeBaseUrl("https://gw.example/v1/"))
        assertEquals("https://gw.example/api/v3", normalizeBaseUrl("https://gw.example/api/v3"))
    }

    @Test fun patchCarriesRoutesWithoutSecrets() {
        val yml = renderProfilePatch(listOf(kimi, custom), webSearch = true)
        assertTrue(yml.contains("- id: llm-service-lite"))
        assertTrue(yml.contains("\"kimi\":{\"displayName\":\"Kimi\",\"interfaceType\":\"openai-chat-completions\",\"baseURL\":\"https://api.moonshot.cn/v1\",\"apiKeyEnv\":\"DSH_ANDROID_KEY_KIMI\""))
        // No key stored → no apiKeyEnv (the request goes unauthenticated, e.g. an open gateway).
        assertFalse(yml.contains("DSH_ANDROID_KEY_CUSTOM"))
        assertTrue(yml.contains("\"maxTokens\":${ThirdParty.DEFAULT_MAX_TOKENS}"))
        assertTrue(yml.contains("- id: compaction-basic"))
        assertTrue(yml.contains("{\"provider\":\"custom\",\"model\":\"b\",\"headroomTokens\":${ThirdParty.COMPACTION_HEADROOM}}"))
        assertFalse(yml.contains("tool-web"))
    }

    @Test fun disabledProvidersAndWebSearch() {
        assertTrue(renderProfilePatch(listOf(kimi.copy(enabled = false)), webSearch = true).trimEnd().endsWith("[]"))
        val off = renderProfilePatch(emptyList(), webSearch = false)
        assertTrue(off.contains("- id: tool-web\n  config: {\"search\":false,\"fetch\":true,\"searchTimeoutMs\":60000}"))
        assertFalse(off.contains("llm-service-lite"))
    }

    @Test fun choiceFallsBackToDeepSeek() {
        assertEquals("kimi-k3", resolveChoice("kimi", "kimi-k3", listOf(kimi)).model)
        assertEquals(kimi, resolveChoice("kimi", "kimi-k3", listOf(kimi)).provider)
        assertNull(resolveChoice("kimi", "kimi-k3", listOf(kimi.copy(enabled = false))).provider)
        assertNull(resolveChoice("kimi", "gone", listOf(kimi)).provider)
        assertEquals(DEFAULT_DEEPSEEK_MODEL, resolveChoice("kimi", "kimi-k3", emptyList()).model)
        assertEquals("deepseek-v4-pro", resolveChoice("", "deepseek-v4-pro", listOf(kimi)).model)
        assertEquals(ThirdParty.DEFAULT_CONTEXT_WINDOW, resolveChoice("kimi", "kimi-k3", listOf(kimi)).contextWindow)
    }

    @Test fun modelListShapes() {
        assertEquals(listOf("a", "b"), parseModelIds("""{"object":"list","data":[{"id":"b"},{"id":"a"}]}"""))
        assertEquals(listOf("gemini-x"), parseModelIds("""{"models":[{"name":"models/gemini-x"}]}"""))
        assertEquals(listOf("m"), parseModelIds("""[{"id":"m"}]"""))
    }

    @Test fun presetsAreHttpsOpenAiCompatible() {
        // Cloud presets are https; the local ones (Ollama, LM Studio) are http on the computer, key optional, address editable.
        PRESETS.filter { it.id != CUSTOM && it.localHint == null }.forEach { assertTrue(it.id, it.baseUrl.startsWith("https://")) }
        PRESETS.filter { it.localHint != null }.forEach {
            assertTrue(it.id, it.baseUrl.startsWith("http://") && it.keyOptional && it.editableUrl && NetDiagnose.isOnDeviceAddress(it.baseUrl))
        }
        assertEquals("http://localhost:11434/v1", presetOf("ollama")!!.baseUrl)
        assertEquals("http://localhost:1234/v1", presetOf("lmstudio")!!.baseUrl)
        listOf("siliconflow", "ark", "kimi", "zhipu", "minimax").forEach { assertTrue(it, presetOf(it) != null) }
        assertEquals(PRESETS.size, PRESETS.map { it.id }.toSet().size)
    }

    @Test fun limitsFromModelsShapes() {
        // sglang / vLLM
        assertEquals(RemoteModel("/m/Qwen", 262_144), parseModels("""{"object":"list","data":[{"id":"/m/Qwen","object":"model","owned_by":"sglang","max_model_len":262144}]}""").single())
        // OpenRouter: root context_length wins, output from top_provider
        val or = parseModels("""{"data":[{"id":"a/b","context_length":200000,"top_provider":{"context_length":190000,"max_completion_tokens":64000}}]}""").single()
        assertEquals(RemoteModel("a/b", 200_000, 64_000), or)
        assertEquals(RemoteModel("x", 128_000), parseModels("""{"data":[{"id":"x","top_provider":{"context_length":128000,"max_completion_tokens":null}}]}""").single())
        // Moonshot / Groq / Mistral / llama.cpp / Gemini native / numeric strings and floats
        assertEquals(131_072L, parseModels("""{"data":[{"id":"kimi","context_length":131072}]}""").single().contextWindow)
        assertEquals(RemoteModel("g", 131_072, 32_768), parseModels("""{"data":[{"id":"g","context_window":131072,"max_completion_tokens":32768}]}""").single())
        assertEquals(32_000L, parseModels("""{"data":[{"id":"mi","max_context_length":32000}]}""").single().contextWindow)
        assertEquals(8_192L, parseModels("""{"data":[{"id":"l","meta":{"n_ctx_train":32768,"n_ctx":8192}}]}""").single().contextWindow)
        assertEquals(RemoteModel("gem", 1_048_576, 65_536), parseModels("""{"models":[{"name":"models/gem","inputTokenLimit":1048576,"outputTokenLimit":65536}]}""").single())
        assertEquals(65_536L, parseModels("""[{"id":"s","context_length":"65536"}]""").single().contextWindow)
        assertEquals(65_536L, parseModels("""[{"id":"f","context_length":65536.0}]""").single().contextWindow)
        // Plain OpenAI / SiliconFlow / DashScope / Ollama: nothing reported; junk values ignored
        assertEquals(RemoteModel("gpt"), parseModels("""{"data":[{"id":"gpt","object":"model","owned_by":"openai","created":1}]}""").single())
        assertEquals(RemoteModel("bad"), parseModels("""{"data":[{"id":"bad","context_length":-1,"max_tokens":"lots"}]}""").single())
        // Keyed object listing
        assertEquals(listOf("k1"), parseModelIds("""{"models":{"k1":{"context_length":4096}}}"""))
    }

    @Test fun effectiveLimitsPrecedenceAndClamps() {
        val d = ThirdParty.resolve(null, null, null)
        assertEquals(EffectiveLimits(131_072, LimitSource.DEFAULT, 16_384, LimitSource.DEFAULT, 16_384), d)
        val det = ModelLimits(detectedContextWindow = 262_144, detectedMaxTokens = 32_768)
        ThirdParty.resolve(det, null, null).let {
            assertEquals(262_144L, it.contextWindow); assertEquals(LimitSource.DETECTED, it.contextSource)
            assertEquals(32_768, it.maxTokens); assertEquals(LimitSource.DETECTED, it.maxTokensSource)
        }
        // Provider-level beats detected; per-model beats provider-level.
        ThirdParty.resolve(det, 200_000, 20_000).let { assertEquals(200_000L, it.contextWindow); assertEquals(LimitSource.MANUAL, it.contextSource); assertEquals(20_000, it.maxTokens) }
        ThirdParty.resolve(det.copy(contextWindow = 1_000_000, maxTokens = 64_000), 200_000, 20_000).let { assertEquals(1_000_000L, it.contextWindow); assertEquals(64_000, it.maxTokens) }
        // Window bounds.
        assertEquals(ThirdParty.MIN_CONTEXT_WINDOW, ThirdParty.resolve(ModelLimits(contextWindow = 10), null, null).contextWindow)
        assertEquals(ThirdParty.MAX_CONTEXT_WINDOW, ThirdParty.resolve(null, 50_000_000, null).contextWindow)
        // Output: manual ≤ half the window, detected/default ≤ a quarter; headroom ≤ 1/8.
        assertEquals(65_536, ThirdParty.resolve(ModelLimits(maxTokens = 500_000), 131_072, null).maxTokens)
        assertEquals(50_000, ThirdParty.resolve(ModelLimits(detectedContextWindow = 200_000, detectedMaxTokens = 128_000), null, null).maxTokens)
        ThirdParty.resolve(ModelLimits(contextWindow = 8_192), null, null).let {
            assertEquals(2_048, it.maxTokens); assertEquals(1_024, it.compactionHeadroom)
            assertTrue(it.contextWindow - it.maxTokens - it.compactionHeadroom > 0)
        }
    }

    @Test fun choiceUsesEffectiveWindow() {
        val p = custom.copy(limits = mapOf("a" to ModelLimits(detectedContextWindow = 262_144)))
        val a = resolveChoice("custom", "a", listOf(p))
        assertEquals(262_144L, a.contextWindow); assertEquals(LimitSource.DETECTED, a.contextSource)
        assertEquals(LimitSource.DEFAULT, resolveChoice("custom", "b", listOf(p)).contextSource)
        assertEquals(200_000L, resolveChoice("custom", "b", listOf(p.copy(contextWindow = 200_000))).contextWindow)
        assertNull(resolveChoice("", "deepseek-flash", listOf(p)).contextSource)
        assertEquals(ModelChoice.DEEPSEEK_CONTEXT, resolveChoice("", "deepseek-flash", listOf(p)).contextWindow)
    }

    @Test fun patchCarriesPerModelLimits() {
        val p = custom.copy(maxTokens = 32_768, limits = mapOf("a" to ModelLimits(detectedContextWindow = 262_144), "b" to ModelLimits(contextWindow = 8_192)))
        val yml = renderProfilePatch(listOf(p), webSearch = true)
        assertTrue(yml, yml.contains("{\"id\":\"a\",\"contextWindow\":262144,\"maxTokens\":32768,\"defaults\":{\"maxTokens\":32768},\"capabilities\":{\"modalities\":[\"text\"]}}"))
        // b: 8K window → manual cap is half the window, headroom an eighth.
        assertTrue(yml, yml.contains("{\"id\":\"b\",\"contextWindow\":8192,\"maxTokens\":4096,\"defaults\":{\"maxTokens\":4096},\"capabilities\":{\"modalities\":[\"text\"]}}"))
        assertTrue(yml, yml.contains("{\"provider\":\"custom\",\"model\":\"a\",\"headroomTokens\":16384}"))
        assertTrue(yml, yml.contains("{\"provider\":\"custom\",\"model\":\"b\",\"headroomTokens\":1024}"))
        // Unconfigured provider: the old defaults.
        assertTrue(renderProfilePatch(listOf(kimi), true).contains("{\"id\":\"kimi-k3\",\"contextWindow\":131072,\"maxTokens\":16384,\"defaults\":{\"maxTokens\":16384},\"capabilities\":{\"modalities\":[\"text\",\"image\"]}}"))
    }

    @Test fun storedProvidersStayReadable() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // A v0.2.4 record (no limit fields) still decodes.
        val old = json.decodeFromString(ProviderConfig.serializer(), """{"id":"custom","preset":"custom","name":"g","baseUrl":"http://x/v1","models":["a"],"enabled":true,"hasKey":false}""")
        assertEquals(null, old.contextWindow); assertTrue(old.limits.isEmpty())
        val p = old.copy(contextWindow = 262_144, limits = mapOf("a" to ModelLimits(maxTokens = 8_000, detectedContextWindow = 131_072)))
        assertEquals(p, json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), p)))
    }

    @Test fun preR5ProvidersDecodeWithoutHeaders() {
        // Settings migration for the header fields: stored JSON is decoded with defaults, no rewrite needed.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val v026 = """{"id":"kimi","preset":"kimi","name":"Kimi","baseUrl":"https://api.moonshot.cn/v1","models":["kimi-k3"],"enabled":true,"hasKey":true,"contextWindow":null,"maxTokens":null,"limits":{},"verified":["kimi-k3"]}"""
        val old = json.decodeFromString(ProviderConfig.serializer(), v026)
        assertTrue(old.headers.isEmpty()); assertNull(old.sessionHeader); assertEquals(listOf("kimi-k3"), old.verified)
        // Unchanged output for a provider without headers: no headers / headerEnv / sessionHeader keys.
        val yml = renderProfilePatch(listOf(old), true)
        assertFalse(yml.contains("headers")); assertFalse(yml.contains("sessionHeader")); assertFalse(yml.contains("headerEnv"))
        val p = old.copy(headers = listOf(ProviderHeader("X-Team", "blue"), ProviderHeader("X-Api-Token", secret = true)), sessionHeader = "x-opencode-session")
        assertEquals(p, json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), p)))
    }

    @Test fun profilePatchCarriesHeadersButNoSecret() {
        val p = ProviderConfig("opencode-go", "opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1", listOf("glm-5.3"), hasKey = true,
            headers = listOf(ProviderHeader("X-Team", "blue"), ProviderHeader("X-Api-Token", secret = true)), sessionHeader = OPENCODE_SESSION_HEADER)
        val yml = renderProfilePatch(listOf(p), true, appProductToken("0.2.6"))
        assertTrue(yml, yml.contains("\"headers\":{\"X-Team\":\"blue\"}"))
        assertTrue(yml, yml.contains("\"headerEnv\":{\"X-Api-Token\":\"DSH_ANDROID_HDR_OPENCODE_GO_X_API_TOKEN\"}"))
        assertTrue(yml, yml.contains("\"sessionHeader\":\"x-opencode-session\""))
        assertTrue(yml, yml.contains("\"userAgent\":\"DSH-Android/0.2.6\""))
        assertEquals("DSH_ANDROID_HDR_OPENCODE_GO_X_API_TOKEN", p.headerEnv("X-Api-Token"))
        assertEquals("provider-opencode-go-h-x-api-token", p.headerSecretName("X-Api-Token"))
    }

    @Test fun openCodeGoPreset() {
        val go = presetOf("opencode-go")!!
        assertEquals("https://opencode.ai/zen/go/v1", go.baseUrl)
        assertEquals(OPENCODE_SESSION_HEADER, go.sessionHeader)
        assertFalse(go.keyOptional)
    }

    @Test fun headerRules() {
        assertTrue(isUsableHeaderName("x-opencode-session")); assertTrue(isUsableHeaderName("X-Api-Key"))
        assertFalse(isUsableHeaderName("bad header")); assertFalse(isUsableHeaderName("Content-Type")); assertFalse(isUsableHeaderName("host")); assertFalse(isUsableHeaderName(""))
        assertFalse(isUsableHeaderValue("a\nb")); assertTrue(isUsableHeaderValue("a b\tc"))
        assertTrue(looksSecretHeader("X-Api-Key", "abc")); assertTrue(looksSecretHeader("Authorization", "x")); assertTrue(looksSecretHeader("X-Thing", "sk-abc"))
        assertTrue(looksSecretHeader("X-Thing", "Bearer abc")); assertFalse(looksSecretHeader("X-Team", "blue")); assertFalse(looksSecretHeader("HTTP-Referer", "https://x"))
        val h = appRequestHeaders(mapOf("user-agent" to "mine/1", OPENCODE_SESSION_HEADER to "typed"), OPENCODE_SESSION_HEADER, appUserAgent("1"))
        assertEquals("mine/1", h["user-agent"]); assertFalse(h.containsKey("User-Agent"))
        assertTrue(h[OPENCODE_SESSION_HEADER]!!.matches(Regex("[0-9a-f]{32}")))
        assertEquals(mapOf("User-Agent" to "DSH-Android/1 $HARNESS_USER_AGENT"), appRequestHeaders(emptyMap(), null, appUserAgent("1")))
    }

    @Test fun headerPlanKeepsSecretsOutOfSettings() {
        val first = planHeaders("gw", listOf(HeaderRow(" X-Team ", "blue"), HeaderRow("X-Api-Key", "s3cret"), HeaderRow("", "x"), HeaderRow("X-Empty", "")), null)
        assertEquals(listOf(ProviderHeader("X-Team", "blue"), ProviderHeader("X-Api-Key", secret = true)), first.headers)
        assertEquals(mapOf("provider-gw-h-x-api-key" to "s3cret"), first.write)
        assertTrue(first.clear.isEmpty())
        val saved = ProviderConfig("gw", CUSTOM, "g", "https://gw/v1", listOf("m"), headers = first.headers)
        // Editing: the secret row comes back blank and keeps the saved value; nothing is rewritten.
        assertEquals(listOf(HeaderRow("X-Team", "blue"), HeaderRow("X-Api-Key", "")), headerRowsOf(saved))
        val keep = planHeaders("gw", headerRowsOf(saved), saved)
        assertEquals(first.headers, keep.headers); assertTrue(keep.write.isEmpty()); assertTrue(keep.clear.isEmpty())
        assertEquals(mapOf("X-Team" to "blue", "X-Api-Key" to "s3cret"), resolveHeaderRows(headerRowsOf(saved), saved) { if (it == "provider-gw-h-x-api-key") "s3cret" else null })
        // Removing the secret row clears its stored value.
        val removed = planHeaders("gw", listOf(HeaderRow("X-Team", "blue")), saved)
        assertEquals(listOf("provider-gw-h-x-api-key"), removed.clear)
        // A saved secret stays secret even when the new value does not look like one.
        assertEquals(mapOf("provider-gw-h-x-api-key" to "plain"), planHeaders("gw", listOf(HeaderRow("X-Api-Key", "plain")), saved).write)
    }

    @Test fun refreshReplacesListKeepingCheckedOnTop() {
        // Stale unchecked models go, new ones come in the fetched order; checked-but-gone stay on top, flagged.
        val r = refreshOffered(current = listOf("old1", "old2", "keep", "a"), fetched = listOf("a", "n1", "n2"), keep = listOf("keep", "a"))
        assertEquals(listOf("keep", "a", "n1", "n2"), r.offered)
        assertEquals(listOf("keep"), r.missing)
        // A hand-typed name stays even unchecked.
        assertEquals(listOf("typed", "x"), refreshOffered(listOf("typed", "stale"), listOf("x"), listOf("typed")).offered)
        // Nothing kept: exactly the fetch.
        assertEquals(OfferedRefresh(listOf("x", "y"), emptyList()), refreshOffered(listOf("p", "q"), listOf("x", "y"), emptyList()))
    }

    @Test fun tokenInput() {
        assertEquals(null, parseTokenInput("  ").getOrThrow())
        assertEquals(262_144L, parseTokenInput("262,144").getOrThrow())
        assertTrue(parseTokenInput("128k").isFailure)
        assertTrue(parseTokenInput("0").isFailure)
    }
}
