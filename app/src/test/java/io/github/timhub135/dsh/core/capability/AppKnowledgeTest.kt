package io.github.timhub135.dsh.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppKnowledgeTest {
    private val dir = File("src/main/assets/${AppKnowledge.DIR}")
    private fun assetFiles() = dir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name to File(it, "APP.md").readText() }

    @Test fun `every bundled guide parses and names its packages`() {
        val files = assetFiles()
        assertEquals(18, files.size)
        val seen = HashMap<String, String>()
        files.forEach { (id, text) ->
            val e = AppKnowledge.parse(id, text)
            assertEquals(id, e.id)
            assertTrue("$id: packages", e.packages.isNotEmpty() && e.packages.all { it.matches(Regex("[a-zA-Z][\\w]*(\\.[\\w]+)+")) })
            assertTrue("$id: display", e.display.isNotBlank())
            assertTrue("$id: body", e.body.startsWith("## "))
            e.packages.forEach { p -> assertNull("$p in both ${seen[p]} and $id", seen.put(p, id)) }
        }
    }

    @Test fun `lookup by name, alias and package`() {
        val k = AppKnowledge(::assetFiles)
        assertEquals("wechat", k.find("微信")?.id)
        assertEquals("wechat", k.find("com.tencent.mm")?.id)
        assertEquals("bilibili", k.find("B站")?.id)
        assertEquals("xiaohongshu", k.find("xhs")?.id)
        assertEquals("boss-zhipin", k.find("直聘")?.id)
        assertNull(k.find("微信读书"))
        assertNull(k.find("不存在的应用"))
    }

    @Test fun `a guide is given once per conversation and at most three per turn`() {
        val k = AppKnowledge(::assetFiles)
        val first = k.firstVisitNotice("s1", "com.tencent.mm")
        assertNotNull(first)
        assertTrue(first!!.startsWith("App guide for 微信 (first visit"))
        assertNull(k.firstVisitNotice("s1", "com.tencent.mm"))
        assertNull("a subagent shares its conversation's guides", k.firstVisitNotice("s1/sub-1", "com.tencent.mm"))
        assertNotNull(k.firstVisitNotice("s2", "com.tencent.mm"))
        assertNull(k.firstVisitNotice("s1", "com.example.unknown"))

        assertNotNull(k.firstVisitNotice("s1/sub-1", "tv.danmaku.bili"))
        assertNotNull(k.firstVisitNotice("s1", "com.zhihu.android"))
        // Fourth app in the turn is held back, and offered again in the next turn.
        assertNull(k.firstVisitNotice("s1", "com.sina.weibo"))
        k.newTurn("s1")
        assertNotNull(k.firstVisitNotice("s1", "com.sina.weibo"))
        assertNull(k.firstVisitNotice("s1", "com.sina.weibo"))
    }

    @Test fun `app_guide lookup counts as given`() {
        val k = AppKnowledge(::assetFiles)
        k.lookup("s1", k.find("知乎")!!)
        assertNull(k.firstVisitNotice("s1", "com.zhihu.android"))
    }

    @Test fun `frontmatter errors are reported`() {
        assertTrue(runCatching { AppKnowledge.parse("x", "## no frontmatter") }.isFailure)
        assertTrue(runCatching { AppKnowledge.parse("x", "---\nname: x\n---\n## body") }.isFailure)
        assertEquals(listOf("a.b"), AppKnowledge.parse("x", "---\r\nname: x\r\npackages: [\"a.b\"]\r\n---\r\n## body").packages)
    }
}
