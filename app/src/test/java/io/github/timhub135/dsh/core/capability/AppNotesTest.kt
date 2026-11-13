package io.github.timhub135.dsh.core.capability

import io.github.timhub135.dsh.core.capability.AppNotes.Kind
import io.github.timhub135.dsh.core.capability.AppNotes.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val versions = HashMap(mapOf("com.a" to "1.0 (10)", "com.b" to "2.0 (20)"))
    private var now = 1_000_000L
    private fun store(file: File = File(tmp.root, AppNotes.FILE)) = AppNotes(file, { versions[it] }, { now })
    private fun AppNotes.saved(pkg: String, text: String, kind: Kind = Kind.fact) = (add(pkg, text, kind) as Outcome.Saved).note

    private fun cjk(i: Int) = (0x4E00 + i * 37).toChar()

    @Test fun `adds, persists and reloads with fresh ids`() {
        val file = File(tmp.root, AppNotes.FILE)
        val s = store(file)
        val n = s.saved("com.a", "  收藏在「我」→「收藏」，\n不在首页 ", Kind.entry)
        assertEquals("n1", n.id)
        assertEquals("收藏在「我」→「收藏」， 不在首页", n.text)
        assertEquals("1.0 (10)", n.appVersion)
        assertFalse(File(tmp.root, "${AppNotes.FILE}.tmp").exists())
        val again = store(file)
        assertEquals(listOf(n), again.all.value)
        assertEquals("n2", again.saved("com.a", "设置在右上角齿轮").id)
    }

    @Test fun `near duplicates return the existing note`() {
        val s = store()
        val n = s.saved("com.a", "搜索框在首页顶部，点放大镜图标进入")
        assertEquals(n, (s.add("com.a", "搜索框在首页顶部, 点放大镜图标进入。", Kind.fact) as Outcome.Duplicate).note)
        assertEquals(n, (s.add("com.a", "搜索框在首页顶部，点放大镜图标进入页面", Kind.fact) as Outcome.Duplicate).note)
        assertTrue("other apps are separate", s.add("com.b", "搜索框在首页顶部，点放大镜图标进入", Kind.fact) is Outcome.Saved)
        assertTrue(s.add("com.a", "消息列表长按可以置顶会话", Kind.fact) is Outcome.Saved)
        val other = s.all.value.last()
        assertEquals(n, (s.update(other.id, "搜索框在首页顶部，点放大镜图标进入") as Outcome.Duplicate).note)
    }

    @Test fun `secrets and overlong text are rejected`() {
        val s = store()
        for (t in listOf("验证码 123456 在短信里", "联系 138 1234 5678", "账号 someone@example.com 登录", "电话 +86-138-1234-5678", "", "长".repeat(201)))
            assertTrue(t, s.add("com.a", t, Kind.fact) is Outcome.Rejected)
        assertTrue(s.add("com.a", "版本 8.0.50 起，转账在「我」→「服务」里", Kind.entry) is Outcome.Saved)
        assertTrue(s.add("com.a", "长".repeat(200), Kind.fact) is Outcome.Saved)
    }

    @Test fun `stale after an app update until confirmed`() {
        val s = store()
        val n = s.saved("com.a", "底部第三个标签是「发现」")
        assertFalse(s.isStale(n))
        versions["com.a"] = "1.1 (11)"
        assertTrue(s.isStale(s.get(n.id)!!))
        assertEquals("[n1] (事实) 底部第三个标签是「发现」 (旧版本 1.0 记录，可能已变)", s.line(s.get(n.id)!!))
        now += 10
        val c = s.confirm(n.id)!!
        assertEquals(1, c.uses)
        assertEquals("1.1 (11)", c.appVersion)
        assertEquals(now, c.lastConfirmedAt)
        assertFalse(s.isStale(c))
        versions.remove("com.a")
        assertFalse("uninstalled apps are not stale", s.isStale(c))
    }

    @Test fun `update, remove and clear`() {
        val s = store()
        val a = s.saved("com.a", "设置在右上角齿轮")
        s.saved("com.b", "首页有扫一扫入口")
        val u = (s.update(a.id, "设置在「我」页右上角齿轮", Kind.entry) as Outcome.Saved).note
        assertEquals(Kind.entry, u.kind)
        assertTrue(s.update("n99", "x", null) is Outcome.Rejected)
        assertNull(s.confirm("n99"))
        assertNotNull(s.remove(a.id))
        assertNull(s.get(a.id))
        s.clear("com.b")
        assertTrue(s.all.value.isEmpty())
        s.saved("com.a", "另一条经验")
        s.clearAll()
        assertTrue(s.all.value.isEmpty())
    }

    @Test fun `ordering puts current, recently confirmed, used notes first`() {
        val s = store()
        val old = s.saved("com.a", "第一条：消息在底部第一个标签")
        now += 1000
        val mid = s.saved("com.a", "第二条：通讯录在底部第二个标签")
        now += 1000
        val recent = s.saved("com.a", "第三条：朋友圈在发现页里")
        assertEquals(listOf(recent, mid, old).map { it.id }, s.forPackages(listOf("com.a")).map { it.id })
        versions["com.a"] = "1.1 (11)"
        now += 1000
        s.confirm(old.id)
        assertEquals(listOf(old.id, recent.id, mid.id), s.forPackages(listOf("com.a")).map { it.id })
    }

    @Test fun `per-app cap evicts stale, old, unused notes first`() {
        val s = store()
        val ids = (1..AppNotes.MAX_PER_APP).map { i -> now += 1000; s.saved("com.a", "功能${cjk(i)}${cjk(i + 50)}在菜单里").id }
        // ids[0] is the oldest; keep it alive by using it, and make ids[5] stale.
        s.confirm(ids[0])
        versions["com.a"] = "1.1 (11)"
        ids.filter { it != ids[5] }.forEach { s.confirm(it) }
        now += 1000
        val newest = s.saved("com.a", "全新的入口在顶部横幅")
        val left = s.forPackages(listOf("com.a")).map { it.id }
        assertEquals(AppNotes.MAX_PER_APP, left.size)
        assertTrue(newest.id in left)
        assertFalse("the stale note went first", ids[5] in left)
    }

    @Test fun `notice lists at most twelve lines with the instructions`() {
        val s = store()
        repeat(14) { i -> now += 1; s.saved("com.a", "功能${'A' + i}在第${'a' + i}页") }
        val text = s.notice(listOf("com.a"), "测试应用")!!
        val lines = text.lines()
        assertTrue(lines.first().startsWith("Notes on 测试应用 from earlier runs on this phone"))
        assertEquals(12, lines.count { it.startsWith("[n") })
        assertTrue(lines[1].startsWith("[n14] (事实) "))
        assertTrue(text.contains("(+2 more: app_guide lists all)"))
        assertTrue(lines.last().contains("app_note confirm(id)"))
        assertNull(s.notice(listOf("com.b"), "B"))
    }

    @Test fun `first visit combines notes and the guide, and covers apps without a guide`() {
        val s = store()
        s.saved("com.a", "购物车在底部第四个标签", Kind.entry)
        val guide = "---\nname: x\ndisplay: X\npackages: [\"com.b\"]\n---\n## 界面\nbody"
        val k = AppKnowledge({ listOf("x" to guide) }, s) { "App $it" }
        val a = k.firstVisitNotice("s1", "com.a")!!
        assertTrue(a.startsWith("Notes on App com.a"))
        assertTrue(a.contains("[n1] (入口) 购物车在底部第四个标签"))
        assertNull(k.firstVisitNotice("s1", "com.a"))
        assertTrue(k.firstVisitNotice("s1", "com.b")!!.startsWith("App guide for X"))
        s.saved("com.b", "搜索在顶部")
        val b = k.firstVisitNotice("s2", "com.b")!!
        assertTrue(b.startsWith("Notes on X") && b.indexOf("App guide for X") > b.indexOf("[n2]"))
        assertNull(k.firstVisitNotice("s1", "com.c"))
        assertTrue(k.lookup("s3", null, "com.a")!!.contains("[n1]"))
        assertNull("app_guide counts as given", k.firstVisitNotice("s3", "com.a"))
        assertNull(k.lookup("s3", null, "com.c"))
    }
}
