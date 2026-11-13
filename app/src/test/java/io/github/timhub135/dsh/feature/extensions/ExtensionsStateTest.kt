package io.github.timhub135.dsh.feature.extensions

import io.github.timhub135.dsh.core.extensions.ExtMethods
import io.github.timhub135.dsh.core.extensions.ExtensionsClient
import io.github.timhub135.dsh.core.extensions.FakeExtEngine
import io.github.timhub135.dsh.core.extensions.FormField
import io.github.timhub135.dsh.core.extensions.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionsStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeExtEngine()
    private val client = ExtensionsClient(engine)

    @After fun tearDown() = scope.cancel()

    // ---- settings form ----

    private val configGet = """{"entryId":"e1","rowId":"web-search-bing","schema":{"type":"object","properties":{
        "engine":{"anyOf":[{"anyOf":[{"const":"bing"},{"const":"anysearch"}]},{"${'$'}ref":"#/${'$'}defs/loaderExpression"}]},
        "limit":{"type":"integer","minimum":1},"proxy":{"type":"object","properties":{"host":{"type":"string"}}}}},
        "value":{"engine":"bing"},"overridden":[]}"""

    @Test fun `the form loads from the projected schema and saves each change once, after a pause`() = runBlocking {
        engine.on(ExtMethods.CONFIG_GET, configGet)
        engine.on(ExtMethods.CONFIG_SET, """{"ok":true}""")
        val ed = ConfigEditor(client, scope, "e1", "zh-CN", debounceMs = 40)
        ed.load()
        eventually { !ed.state.value.loading }
        assertEquals(listOf("engine", "limit", "proxy"), ed.state.value.fields.map { it.title })
        assertEquals(listOf("bing", "anysearch"), (ed.state.value.fields[0] as FormField.Text).options)
        ed.set(listOf("limit"), JsonPrimitive(5))
        ed.set(listOf("proxy", "host"), JsonPrimitive("h"))
        eventually { ed.state.value.saved }
        assertEquals(1, engine.count(ExtMethods.CONFIG_SET))
        assertEquals("""{"engine":"bing","limit":5,"proxy":{"host":"h"}}""", engine.last(ExtMethods.CONFIG_SET)["value"].toString())
        assertEquals(JsonPrimitive("e1"), engine.last(ExtMethods.CONFIG_SET)["entryId"])
    }

    @Test fun `validation errors land under their field`() = runBlocking {
        engine.on(ExtMethods.CONFIG_GET, configGet)
        engine.on(ExtMethods.CONFIG_SET, """{"ok":false,"errors":[{"path":"limit","message":"expected a number >= 1"},{"path":"","message":"bad"}]}""")
        val ed = ConfigEditor(client, scope, "e1", "en", debounceMs = 0)
        ed.load()
        eventually { !ed.state.value.loading }
        ed.set(listOf("limit"), JsonPrimitive(0))
        eventually { ed.state.value.errors.isNotEmpty() }
        assertEquals("expected a number >= 1", ed.state.value.errors["limit"])
        assertEquals("bad", ed.state.value.errors[""])
        assertFalse(ed.state.value.saved)
        // Editing the field clears its error until the next save answers.
        ed.invalid(listOf("limit"), "x")
        assertEquals("x", ed.state.value.errors["limit"])
    }

    @Test fun `a row the app manages is shown locked, a row without Config has no fields`() = runBlocking {
        engine.on(ExtMethods.CONFIG_GET, configGet.replace("\"overridden\":[]", "\"overridden\":[\"*\"],\"managedByApp\":true"))
        val ed = ConfigEditor(client, scope, "e1", "en")
        ed.load()
        eventually { !ed.state.value.loading }
        assertTrue(ed.state.value.fields.all { it.locked })
        engine.on(ExtMethods.CONFIG_GET, """{"entryId":"e2","schema":null,"value":{}}""")
        val ed2 = ConfigEditor(client, scope, "e2", "en")
        ed2.load()
        eventually { !ed2.state.value.loading }
        assertTrue(ed2.state.value.fields.isEmpty())
        assertEquals(null, ed2.state.value.loadError)
        assertEquals("a.b", ConfigEditor.normalizePath("/a/b"))
        assertEquals("a.b", ConfigEditor.normalizePath("$.a.b"))
    }

    // ---- skills ----

    @Test fun `skill import ticks valid ones and asks overwrite or skip for existing names`() = runBlocking {
        engine.on(ExtMethods.SKILLS_PREVIEW, """{"previewId":"p1","skills":[
            {"name":"pdf","description":"PDF","path":"pdf/SKILL.md","files":3,"scripts":["py"],"valid":true,"exists":false},
            {"name":"git","description":"Git","path":"git/SKILL.md","files":1,"scripts":[],"valid":true,"exists":true},
            {"name":"broken","path":"b/SKILL.md","valid":false,"problem":"description","exists":false}]}""")
        engine.on(ExtMethods.SKILLS_IMPORT, """{"imported":["pdf","git"],"skipped":[]}""")
        engine.on(ExtMethods.SKILLS_LIST, """{"skills":[{"name":"pdf","description":"PDF","source":"imported","enabled":true,"removable":true},
            {"name":"badge","source":"builtin","enabled":true,"removable":false},{"name":"old","source":"imported","enabled":false,"removable":true}]}""")
        val s = SkillsState(client, scope)
        s.preview("https://github.com/anthropics/skills")
        eventually { s.import.value?.preview != null }
        var i = s.import.value!!
        assertEquals(setOf("pdf", "git"), i.selected)
        assertEquals(listOf("pdf"), s.chosen(i))
        s.setOverwrite("git", true)
        i = s.import.value!!
        assertEquals(listOf("pdf", "git"), s.chosen(i))
        s.confirm()
        eventually { s.import.value?.done != null }
        val p = engine.last(ExtMethods.SKILLS_IMPORT)
        assertEquals("""["pdf","git"]""", p["names"].toString())
        assertEquals("""["git"]""", p["overwrite"].toString())
        eventually { s.skills.value != null }
        val list = s.skills.value!!
        assertEquals(listOf(true, false, true), list.map { it.editable })
        assertEquals("imported", list[0].origin)
    }

    @Test fun `a preview that fails explains why`() = runBlocking {
        engine.on(ExtMethods.SKILLS_PREVIEW, """{"error":{"code":"bad-archive","message":"文件里找不到 SKILL.md"}}""")
        val s = SkillsState(client, scope)
        s.preview("/x.zip")
        eventually { s.import.value?.error != null }
        assertEquals("bad-archive", s.import.value!!.error!!.code)
    }

    // ---- market ----

    @Test fun `discover lists every configured index and de-duplicates by name`() = runBlocking {
        engine.on(ExtMethods.MARKET_INDEXES, """{"indexes":[{"url":"u1","title":"A"},{"url":"u2","title":"B","error":"offline"}]}""")
        engine.on(ExtMethods.MARKET_SEARCH) { p ->
            val url = (p["url"] as JsonPrimitive).content
            kotlinx.serialization.json.Json.parseToJsonElement(if (url == "u1") """{"url":"u1","entries":[{"name":"x"},{"name":"y"}]}""" else """{"url":"u2","entries":[{"name":"y"},{"name":"z"}]}""")
        }
        val m = MarketState(client, scope)
        m.load()
        eventually { !m.state.value.loading && m.state.value.results.size == 2 }
        assertEquals(listOf("u1" to "x", "u1" to "y", "u2" to "z"), m.state.value.entries.map { it.first to it.second.name })
        m.query("网页")
        eventually { engine.calls.any { it.first == ExtMethods.MARKET_SEARCH && (it.second["text"] as? JsonPrimitive)?.content == "网页" } }
    }

    @Test fun `editing indexes writes the whole list`() = runBlocking {
        engine.on(ExtMethods.MARKET_INDEXES, """{"indexes":[]}""")
        engine.on(ExtMethods.MARKET_SET_INDEXES, """{"indexes":[]}""")
        val m = MarketState(client, scope)
        assertEquals(null, m.setIndexes(listOf(" https://a/index.json ", "https://a/index.json", "")))
        assertEquals("""["https://a/index.json"]""", engine.last(ExtMethods.MARKET_SET_INDEXES)["urls"].toString())
    }
}
