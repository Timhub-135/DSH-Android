package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.JsonRpcException
import io.github.timhub135.dsh.core.engine.RpcJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The engine's JSON (plugin-extensions/index.js) decodes into the app's models. */
class ExtModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `list decodes with rows, status detail, suspension and notices`() {
        val l = RpcJson.decodeFromString(ExtList.serializer(), """
          {"plugins":[{"name":"ds-bot","title":"DS Bot","version":"0.2.4","source":{"kind":"npm","spec":"ds-bot"},"enabled":true,"status":"waiting",
            "statusDetail":{"code":"waiting-service","message":"ds-bot 在等待服务 connection"},
            "rows":[{"id":"bot","module":"ds-bot","entryId":"e9","phase":"waiting","waitingFor":["connection"],"configurable":true},
                    {"id":"off","module":"ds-bot","phase":"disabled","configurable":false}],
            "tools":[],"skills":[],"commands":[],"overrides":[{"rowId":"web","label":"web"}],"skippedOptional":["cpu-features"],"updatable":true,
            "update":{"version":"0.2.5","compatible":true},"askBeforeCall":false,"suspended":{"reason":"crash","at":5,"message":"boom"},"futureField":1}],
           "safeMode":false,"restartPending":true,"dshVersion":"0.2.1-rc.5","registries":["https://registry.npmmirror.com/"],
           "notices":[{"kind":"activated","name":"ds-bot","tools":["a"],"message":"","at":1}]}""")
        val p = l.plugins.single()
        assertEquals("waiting-service", p.statusDetail!!.code)
        assertEquals(listOf("connection"), p.rows[0].waitingFor)
        assertTrue(p.rows[0].isEnabled)
        assertFalse(p.rows[1].isEnabled)
        assertEquals("crash", p.suspended!!.reason)
        assertEquals(listOf(p), l.updates)
        assertTrue(l.notices.single().isActivation)
    }

    @Test fun `skills use the engine's source field`() {
        val s = RpcJson.decodeFromString(SkillList.serializer(), """{"skills":[{"name":"x","source":"plugin","enabled":true,"removable":false,"path":"/n/node_modules/p/skills/x"}]}""")
        assertEquals(SkillOrigin.PLUGIN, s.skills.single().origin)
        assertFalse(s.skills.single().editable)
    }

    @Test fun `mcp status rows map to server ids and tool names`() {
        val r = RpcJson.decodeFromString(McpStatusResult.serializer(), """{"servers":[
            {"id":"mcp-github","serverName":"github","phase":"active","tools":["mcp__github__search","mcp__github__issue"],"askBeforeCall":false},
            {"id":"mcp-x","serverName":"x","phase":"failed","error":"HTTP 401","tools":[]}],"installed":[],"maxLocalServers":4}""")
        val gh = r.servers[0]
        assertEquals("github", gh.server)
        assertEquals("connected", gh.state)
        assertEquals(listOf("search", "issue"), gh.toolNames)
        assertEquals("failed", r.servers[1].state)
        assertEquals("HTTP 401", r.servers[1].errorText)
    }

    @Test fun `inspect results carry the unmet range in any of the engine's shapes`() {
        fun range(json: String) = RpcJson.decodeFromString(InspectResult.serializer(), """{"spec":"p","name":"p","incompatible":$json}""").requiredRange
        assertEquals(">=0.2.2", range("""[{"name":"p","version":"1","requirements":{"engines.dsh":">=0.2.2"}}]"""))
        assertEquals(">=0.3", range("""{"engines.dsh":">=0.3"}"""))
        assertNull(range("null"))
    }

    @Test fun `config get without a schema decodes`() {
        val r = RpcJson.decodeFromString(ConfigGetResult.serializer(), """{"entryId":"e","schema":null,"value":{},"overridden":["*"],"managedByApp":true,"limitations":["x"]}""")
        assertNull(r.schema)
        assertTrue(r.managedByApp)
    }

    @Test fun `engine errors map to user errors`() {
        assertEquals(UserError("x", "y"), ExtensionsClient.userErrorOf(JsonRpcException(-32000, "m", RpcJson.parseToJsonElement("""{"code":"x","message":"y"}"""))))
        assertEquals(UserError("x", "y"), ExtensionsClient.userErrorOf(JsonRpcException(-32000, "m", RpcJson.parseToJsonElement("""{"userError":{"code":"x","message":"y"}}"""))))
        assertEquals(ExtCodes.UNSUPPORTED_ENGINE, ExtensionsClient.userErrorOf(JsonRpcException(-32601, "Method not found")).code)
        assertEquals(UserError(ExtCodes.UNKNOWN, "m"), ExtensionsClient.userErrorOf(JsonRpcException(-32000, "m")))
    }

    @Test fun `localized text picks the closest language`() {
        val t = LocalizedText(mapOf("en" to "Search", "zh" to "搜索", "zh-tw" to "搜尋"))
        assertEquals("搜索", t.get("zh-CN"))
        assertEquals("搜尋", t.get("zh-TW"))
        assertEquals("Search", t.get("fr"))
        assertEquals("""{"t":"plain"}""", RpcJson.encodeToString(Holder.serializer(), Holder(LocalizedText.of("plain"))))
        assertEquals("plain", RpcJson.decodeFromString(Holder.serializer(), """{"t":"plain"}""").t.get("zh"))
    }

    @kotlinx.serialization.Serializable data class Holder(val t: LocalizedText)

    @Test fun `shared items go to the right tab`() {
        assertEquals(ShareTarget.Plugin, ShareTarget.of("/cache/ext-staging/p.tgz"))
        assertEquals(ShareTarget.Plugin, ShareTarget.of("https://github.com/o/r"))
        assertEquals(ShareTarget.Skill, ShareTarget.of("/cache/ext-staging/SKILL.md"))
        assertEquals(ShareTarget.Skill, ShareTarget.of("/cache/ext-staging/pdf.skill"))
        assertEquals(ShareTarget.Mcp, ShareTarget.of(" {\"mcpServers\":{}}"))
        assertEquals("a_b.tgz", ExtensionsRepository.safeFileName("../x/a:b.tgz").substringAfterLast('/'))
        assertEquals("upload", ExtensionsRepository.safeFileName("  "))
    }

    @Test fun `safe mode is automatic only when the breaker is open and plugins are installed`() {
        val pkg = tmp.newFile("package.json")
        pkg.writeText("""{"dependencies":{}}""")
        assertFalse(SafeModeRequest.hasThirdPartyBundles(pkg))
        pkg.writeText("""{"dependencies":{"ds-bot":"0.2.4"},"dsh":{"profile":{"bundles":["ds-bot"]}}}""")
        assertTrue(SafeModeRequest.hasThirdPartyBundles(pkg))
        assertFalse(SafeModeRequest.hasThirdPartyBundles(tmp.root.resolve("missing.json")))
        // the engine is told which: the user's request, or the crash breaker
        assertNull(SafeModeRequest.take(breakerOpen = true) { false })
        assertEquals(SafeModeRequest.CRASH_LOOP, SafeModeRequest.take(breakerOpen = true) { true })
        assertNull(SafeModeRequest.take(breakerOpen = false) { true })
        SafeModeRequest.request()
        assertEquals(SafeModeRequest.USER, SafeModeRequest.take(breakerOpen = true) { true })
        assertNull("consumed", SafeModeRequest.take(breakerOpen = false) { true })
    }
}
