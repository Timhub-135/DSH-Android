package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.diagnostics.DiagnosticsExport
import io.github.timhub135.dsh.core.engine.EngineRuntimeEnv
import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Third-party display text (string or locale map) and tolerant decoding of `android/ext/…` results:
 * a plugin whose manifest says `"title": {"en": …, "zh-cn": …}` (@fffangchenghao/dsh-web-search
 * 0.24.0) must not break the list, and a list the app cannot read is not "the engine didn't start".
 */
class LocalizedDecodingTest {
    /** The plugin entry of the field report, as v0.3.1's engine sent it. */
    private val webSearch = """{"name":"@fffangchenghao/dsh-web-search","title":{"en":"Web Search (Bing)","zh-cn":"网页搜索（Bing）"},
        "description":{"en":"Search the web","zh-CN":"联网搜索"},"version":"0.24.0","source":{"kind":"npm","spec":"@fffangchenghao/dsh-web-search"},
        "enabled":true,"status":"active","rows":[],"tools":["web_search"],"skills":[],"commands":[],"overrides":[],"updatable":true,"askBeforeCall":false}"""

    @Test fun `the reported list decodes, title and description follow the locale`() {
        val l = RpcJson.decodeFromString(ExtList.serializer(), """{"plugins":[$webSearch],"safeMode":false,"restartPending":false,"dshVersion":"0.2.1-rc.5","registries":[],"notices":[]}""")
        val p = l.plugins.single()
        assertFalse(p.malformed)
        assertEquals("网页搜索（Bing）", p.title!!.get("zh-CN"))
        assertEquals("网页搜索（Bing）", p.title!!.get("zh-Hans-CN"))
        assertEquals("网页搜索（Bing）", p.title!!.get("zh"))
        assertEquals("Web Search (Bing)", p.title!!.get("en-US"))
        assertEquals("Web Search (Bing)", p.title!!.get("fr-FR"))
        assertEquals("联网搜索", p.description!!.get("zh_CN"))
        // the cache writes and reads it back
        val again = RpcJson.decodeFromString(ExtList.serializer(), RpcJson.encodeToString(ExtList.serializer(), l))
        assertEquals(l, again)
    }

    @Test fun `LocalizedText fallback chain`() {
        fun lt(json: String) = RpcJson.decodeFromString(LocalizedText.serializer(), json)
        assertEquals("plain", lt("\"plain\"").get("zh-CN"))
        assertEquals("", lt("\"  \"").get("en"))
        val m = lt("""{"EN":"en","zh":"中","zh-TW":"繁","ja":"日","bad":3,"empty":""}""")
        assertEquals(setOf("en", "zh", "zh-tw", "ja"), m.values.keys)
        assertEquals("繁", m.get("zh-TW"))          // exact
        assertEquals("中", m.get("zh-Hant-HK"))     // shorter tag → language
        assertEquals("日", m.get("ja-JP"))
        assertEquals("en", m.get("de"))             // → en
        val tw = lt("""{"zh-tw":"繁","zh-cn":"简"}""")
        assertEquals("简", tw.get("zh"))            // same language: mainland first
        assertEquals("first", lt("""{"fr":"first","de":"zweite"}""").get("ko"))  // → first
        assertEquals("", lt("[1]").get("en"))
        assertEquals("x", lt("""{"en-GB":"x"}""").get("pt"))
    }

    @Test fun `a malformed plugin entry is degraded with a note, a nameless one skipped, unknown keys ignored`() {
        val l = RpcJson.decodeFromString(ExtList.serializer(), """{"plugins":[
            $webSearch,
            {"name":"broken-plugin","title":"B","rows":"not a list","status":"active"},
            {"title":"no name"},
            {"name":"ok","title":["weird"],"status":"disabled","brandNewField":{"x":1}}]}""")
        assertEquals(listOf("@fffangchenghao/dsh-web-search", "broken-plugin", "ok"), l.plugins.map { it.name })
        val broken = l.plugins[1]
        assertTrue(broken.malformed)
        assertEquals(ExtStatus.FAILED, broken.status)
        assertEquals(ExtCodes.BAD_ENTRY, broken.statusDetail!!.code)
        assertTrue(broken.statusDetail!!.detail!!.isNotBlank())
        assertEquals("broken-plugin", broken.displayTitle)
        // a title of an unknown shape is just absent
        assertEquals("ok", l.plugins[2].displayTitle)
        assertFalse(l.plugins[2].malformed)
    }

    @Test fun `inspect, skills and market entries take localized text too`() {
        val i = RpcJson.decodeFromString(InspectResult.serializer(), """{"spec":"x","name":"x","title":{"en":"X","zh":"叉"},"description":{"en":"d"}}""")
        assertEquals("叉", i.title!!.get("zh-CN")); assertEquals("d", i.description!!.get("zh"))
        val s = RpcJson.decodeFromString(SkillList.serializer(), """{"skills":[{"name":"s","description":{"en":"skill"},"source":"plugin"}]}""")
        assertEquals("skill", s.skills.single().description)
        val r = RpcJson.decodeFromString(MarketSearchResult.serializer(), """{"url":"u","entries":[{"name":"a","title":{"en":"A"}},{"title":"no name"},{"name":"b"}],"total":3}""")
        assertEquals(listOf("a", "b"), r.entries.map { it.name })
    }

    @Test fun `an unreadable result is bad-result, not engine-unavailable`() = runBlocking {
        val engine = FakeExtEngine()
        engine.on(ExtMethods.LIST, """{"plugins":[],"safeMode":"maybe"}""")
        try { ExtensionsClient(engine).list(); fail("expected ExtException") } catch (e: ExtException) {
            assertEquals(ExtCodes.BAD_RESULT, e.error.code)
            assertTrue(e.error.detail!!.contains("android/ext/list"))
        }
        // and the reported plugin list goes through the client fine
        engine.on(ExtMethods.LIST, """{"plugins":[$webSearch]}""")
        assertEquals("网页搜索（Bing）", ExtensionsClient(engine).list().plugins.single().title!!.get("zh-cn"))
    }

    @Test fun `market search answers its error as a UserError`() = runBlocking {
        val engine = FakeExtEngine()
        engine.on(ExtMethods.MARKET_SEARCH, """{"error":{"code":"index-unreachable","message":"连不上插件索引。试过：cdn.jsdelivr.net（连接超时）。","actions":["retry"],
            "params":{"attempts":[{"host":"cdn.jsdelivr.net","reason":"timeout"}]}}}""")
        try { ExtensionsClient(engine).marketSearch(MarketIndex.CURATED_URL, ""); fail("expected ExtException") } catch (e: ExtException) {
            assertEquals("index-unreachable", e.error.code)
            assertEquals(listOf(UserError.RETRY), e.error.actions)
        }
    }

    @Test fun `curated index URL moved and legacy lists migrate`() {
        assertEquals("https://cdn.jsdelivr.net/gh/Timhub-135/DSH-Android@main/plugins/index.json", MarketIndex.CURATED_URL)
        val old = MarketIndex.LEGACY_CURATED_URLS.single()
        assertTrue(old.contains("DeepseekHarness-Android"))
        assertTrue(MarketIndex.isCurated(old)); assertTrue(MarketIndex.isCurated(MarketIndex.CURATED_URL)); assertFalse(MarketIndex.isCurated("https://x/i.json"))
        assertEquals(listOf("https://x/i.json", MarketIndex.CURATED_URL), MarketIndex.migrateIndexes(listOf("https://x/i.json", old, MarketIndex.CURATED_URL)))
        assertEquals(listOf(MarketIndex.CURATED_URL), MarketIndex.migrateIndexes(listOf(old)))
    }

    @Test fun `engine node options lengthen the happy-eyeballs attempt timeout`() {
        assertEquals("--max-old-space-size=768 --network-family-autoselection-attempt-timeout=1500",
            EngineRuntimeEnv.mergeNodeOptions("--max-old-space-size=768", EngineRuntimeEnv.RUNTIME_NODE_OPTIONS))
        assertEquals("--network-family-autoselection-attempt-timeout=1500",
            EngineRuntimeEnv.mergeNodeOptions("--network-family-autoselection-attempt-timeout=250", EngineRuntimeEnv.RUNTIME_NODE_OPTIONS))
    }

    @Test fun `diagnostics export redacts the log tail like the report`() {
        val t = DiagnosticsExport.text("== app ==\nversion: 1\n", "boot\nAuthorization: Bearer abcdefghijkl\nconnect 192.168.1.5:443\n")
        assertTrue(t.startsWith("== app ==\nversion: 1\n\n== engine log (tail) ==\n"))
        assertFalse(t.contains("abcdefghijkl")); assertFalse(t.contains("192.168.1.5"))
        assertTrue(DiagnosticsExport.text("r", null).endsWith("(no engine log)\n"))
        assertTrue(DiagnosticsExport.fileName(0).matches(Regex("dsh-diagnostics-\\d{8}-\\d{6}\\.txt")))
    }
}
