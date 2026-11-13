package io.github.timhub135.dsh.core.model

import io.github.timhub135.dsh.core.data.decodeModes
import io.github.timhub135.dsh.core.data.encodeModes
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扩展工具 (plugin + MCP tools) as a session-mode category (PLUGINS-DESIGN §8.2). */
class ExtensionToolsModeTest {
    private val ext = EngineTool.Extensions

    @Test fun `read-only chat leaves extension tools out, the other built-ins keep them`() {
        assertFalse(SessionModes.Chat.allowsTool(ext))
        assertEquals(PermissionPreset.ReadOnly.id, SessionModes.Chat.preset)
        assertTrue(SessionModes.Default.allowsTool(ext))
        assertTrue(SessionModes.Phone.allowsTool(ext))
        assertTrue(SessionModes.Code.allowsTool(ext))
    }

    @Test fun `the engine is told which categories a mode allows`() {
        fun tools(m: SessionMode) = m.payload("x")["tools"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("web"), tools(SessionModes.Chat))
        assertTrue("extensions" in tools(SessionModes.Phone)!!)
        assertNull(tools(SessionModes.Default))
    }

    @Test fun `a stored mode that had every built-in category gets extension tools on reading`() {
        val legacyAll = SessionMode("u_1", name = "全开", engineTools = EngineTool.legacy)
        val legacySome = SessionMode("u_2", name = "部分", engineTools = setOf("web", "files_read"))
        val legacyNull = SessionMode("u_3", name = "默认", engineTools = null)
        val read = decodeModes(encodeModes(listOf(legacyAll, legacySome, legacyNull))).associateBy { it.id }
        assertEquals(EngineTool.entries.map { it.id }.toSet(), read["u_1"]!!.engineTools)
        assertTrue(read["u_1"]!!.allowsTool(ext))
        assertEquals(setOf("web", "files_read"), read["u_2"]!!.engineTools)
        assertFalse(read["u_2"]!!.allowsTool(ext))
        assertTrue(read["u_3"]!!.allowsTool(ext))
        assertTrue(read.values.all { it.toolsVersion == SessionModes.TOOLS_VERSION })
    }

    @Test fun `a current mode that switched extension tools off stays off`() {
        val m = SessionMode("u_4", name = "无扩展", engineTools = EngineTool.legacy, toolsVersion = SessionModes.TOOLS_VERSION)
        val read = decodeModes(encodeModes(listOf(m))).single()
        assertEquals(EngineTool.legacy, read.engineTools)
        assertFalse(read.allowsTool(ext))
    }

    @Test fun `a duplicate of the read-only chat mode still has no extension tools`() {
        val copy = SessionModes.Chat.duplicate("u_5", "我的聊天")
        assertFalse(SessionModes.migrate(copy).allowsTool(ext))
        assertEquals(ext, EngineTool.of("extensions"))
    }
}
