package io.github.timhub135.dsh.core.model

import io.github.timhub135.dsh.core.data.Settings
import io.github.timhub135.dsh.core.data.decodeModes
import io.github.timhub135.dsh.core.data.encodeModes
import io.github.timhub135.dsh.core.session.LoggedEvent
import io.github.timhub135.dsh.core.session.SessionLive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModeTest {
    private val custom = SessionMode("u_1", name = "翻译", icon = ModeIcon.Globe.id, prompt = "Translate.", deviceGroups = setOf("clipboard"),
        engineTools = setOf("web"), preset = PermissionPreset.ReadOnly.id, skills = false)

    @Test fun `old sessions and deleted modes resolve to the default mode`() {
        assertEquals(SessionModes.Default, SessionModes.resolve(null, listOf(custom)))
        assertEquals(SessionModes.Default, SessionModes.resolve("u_gone", listOf(custom)))
        assertEquals(custom, SessionModes.resolve("u_1", listOf(custom)))
        assertEquals(SessionModes.Chat, SessionModes.resolve("chat", emptyList()))
        assertEquals(SessionModes.Default, Settings().mode(null))
    }

    @Test fun `built-ins come first and cannot be shadowed by a stored mode`() {
        val all = SessionModes.all(listOf(custom, custom.copy(id = "chat", name = "fake")))
        assertEquals(listOf("default", "phone", "code", "chat", "u_1"), all.map { it.id })
        assertTrue(all.take(4).all { it.builtIn })
        assertFalse(all.last().builtIn)
    }

    @Test fun `the default mode restricts nothing`() {
        val d = SessionModes.Default
        assertTrue(ToolGroup.entries.all(d::allowsGroup))
        assertTrue(EngineTool.entries.all(d::allowsTool))
        assertNull(d.presetOrNull)
        val p = d.payload("默认")
        assertEquals(setOf("id", "name", "skills"), p.keys)
        assertTrue(p["skills"]!!.jsonPrimitive.boolean)
    }

    @Test fun `built-in modes match the product decisions`() {
        with(SessionModes.Chat) {
            assertTrue(ToolGroup.entries.none(::allowsGroup))
            assertEquals(listOf(EngineTool.Web), EngineTool.entries.filter(::allowsTool))
            assertEquals(PermissionPreset.ReadOnly, presetOrNull)
            assertFalse(skills)
        }
        with(SessionModes.Code) {
            assertFalse(allowsGroup(ToolGroup.Screen)); assertFalse(allowsGroup(ToolGroup.System)); assertFalse(allowsGroup(ToolGroup.Apps))
            assertTrue(EngineTool.entries.all(::allowsTool))
            assertTrue(skills)
        }
        with(SessionModes.Phone) {
            assertTrue(ToolGroup.entries.all(::allowsGroup))
            assertFalse(allowsTool(EngineTool.Shell))
            assertEquals(PermissionPreset.Ask, presetOrNull)
        }
    }

    @Test fun `payload carries what plugin-mode reads`() {
        val p = custom.payload("翻译")
        assertEquals("u_1", p["id"]!!.jsonPrimitive.content)
        assertEquals("翻译", p["name"]!!.jsonPrimitive.content)
        assertEquals("Translate.", p["prompt"]!!.jsonPrimitive.content)
        assertEquals(listOf("clipboard"), p["deviceGroups"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("web"), p["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(p["skills"]!!.jsonPrimitive.boolean)
        // Category ids are the engine's (js/packages/plugin-mode CATEGORIES).
        assertEquals(listOf("shell", "files_read", "files_write", "web", "subagent", "extensions"), EngineTool.entries.map { it.id })
    }

    @Test fun `user modes round-trip through settings storage`() {
        val stored = encodeModes(listOf(custom, SessionModes.Code.duplicate("u_2", "编程 副本")))
        val back = decodeModes(stored)
        // Reading stamps the tool-category version (see ExtensionToolsModeTest).
        assertEquals(custom.copy(toolsVersion = SessionModes.TOOLS_VERSION), back[0])
        assertEquals("u_2", back[1].id)
        assertFalse(back[1].builtIn)
        assertEquals(SessionModes.Code.engineTools, back[1].engineTools)
        assertEquals(emptyList<SessionMode>(), decodeModes("not json"))
        assertEquals(emptyList<SessionMode>(), decodeModes(null))
    }

    @Test fun `new custom ids avoid existing ones`() {
        val ids = ArrayDeque(listOf("aaaa", "aaaa", "bbbb"))
        assertEquals("u_bbbb", SessionModes.newCustomId(setOf("u_aaaa")) { ids.removeFirst() })
        assertTrue(SessionModes.newCustomId(emptySet()).startsWith(SessionModes.CUSTOM_PREFIX))
    }

    @Test fun `the mode is changeable only before the first message`() {
        assertTrue(modeChangeable(exists = false, running = false, hasEngineEvents = false))
        assertFalse(modeChangeable(exists = true, running = false, hasEngineEvents = false))
        assertFalse(modeChangeable(exists = false, running = true, hasEngineEvents = false))
        assertFalse(modeChangeable(exists = false, running = false, hasEngineEvents = true))

        val fresh = SessionLive("s")
        assertTrue(fresh.modeChangeable)
        // An app-local record (negative seq, e.g. a failed start) does not start the conversation by itself...
        assertTrue(fresh.copy(events = listOf(LoggedEvent(-1, "app/error", 0, JsonObject(emptyMap())))).modeChangeable)
        // ...but any engine event, a running turn or a stored row does.
        assertFalse(fresh.copy(events = listOf(LoggedEvent(0, "user/message", 0, JsonObject(emptyMap())))).modeChangeable)
        assertFalse(fresh.copy(running = true).modeChangeable)
        assertFalse(fresh.copy(exists = true).modeChangeable)
    }
}
