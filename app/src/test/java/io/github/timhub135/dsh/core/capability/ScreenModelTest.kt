package io.github.timhub135.dsh.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private fun el(text: String?, x: Int = 100, y: Int = 100, cls: String = "TextView", editable: Boolean = false, flags: List<String> = emptyList(), system: Boolean = false) =
        UiElement(cls, text, null, null, x, y, flags = flags, editable = editable, system = system)

    @Test fun unchangedElementsKeepTheirIds() {
        val ids = StableIds()
        val a = ids.assign("app", listOf(el("Title", y = 50), el("OK", y = 900)))
        assertEquals(listOf("e1", "e2"), a.ids)
        val b = ids.assign("app", listOf(el("New", y = 10), el("Title", y = 60), el("OK", y = 930)))
        assertEquals(listOf("e3", "e1", "e2"), b.ids)
    }

    @Test fun driftBeyond48pxGetsANewId() {
        val ids = StableIds()
        ids.assign("app", listOf(el("Row", y = 100)))
        assertEquals("e1", ids.assign("app", listOf(el("Row", y = 148))).ids[0])
        assertEquals("e2", ids.assign("app", listOf(el("Row", y = 300))).ids[0])
    }

    @Test fun anotherAppStartsFresh() {
        val ids = StableIds()
        ids.assign("a", listOf(el("OK")))
        assertEquals("e2", ids.assign("b", listOf(el("OK"))).ids[0])
    }

    @Test fun editFieldKeepsIdWhileItsTextChanges() {
        val ids = StableIds()
        ids.assign("app", listOf(el("", cls = "EditText", editable = true)))
        assertEquals("e1", ids.assign("app", listOf(el("hello", cls = "EditText", editable = true))).ids[0])
    }

    @Test fun duplicatesMatchTheNearest() {
        val ids = StableIds()
        ids.assign("app", listOf(el("Follow", y = 100), el("Follow", y = 200)))
        val f = ids.assign("app", listOf(el("Follow", y = 205), el("Follow", y = 102)))
        assertEquals(listOf("e2", "e1"), f.ids)
    }

    @Test fun retiredIdIsFoundAgainByItsFeatures() {
        val ids = StableIds()
        ids.assign("app", listOf(el("Send", y = 100)))
        val f = ids.assign("app", listOf(el("Send", y = 600)))
        assertEquals(StableIds.Lookup.Relocated(0, "e2"), ids.resolve("e1", f))
        assertEquals(StableIds.Lookup.Found(0), ids.resolve("e2", f))
        val g = ids.assign("app", listOf(el("Other")))
        assertEquals(StableIds.Lookup.Gone, ids.resolve("e2", g))
        assertEquals(StableIds.Lookup.Unknown, ids.resolve("e99", g))
    }

    @Test fun aRetiredIdIsRelocatedOnlyToOneNearbyMatch() {
        val ids = StableIds()
        ids.assign("app", listOf(el("关注", y = 100)))
        val two = ids.assign("app", listOf(el("关注", y = 400), el("关注", y = 700)))
        assertEquals("two candidates: which one is unknown", StableIds.Lookup.Gone, ids.resolve("e1", two))
        val far = StableIds()
        far.assign("app", listOf(el("确定", y = 100)))
        assertEquals("moved across the page", StableIds.Lookup.Gone, far.resolve("e1", far.assign("app", listOf(el("确定", y = 1500)))))
    }

    @Test fun anotherAppForgetsRetiredIds() {
        val ids = StableIds()
        ids.assign("a", listOf(el("确定", y = 100)))
        ids.assign("a", listOf(el("确定", y = 300)))
        val other = ids.assign("b", listOf(el("确定", y = 300)))
        assertEquals(StableIds.Lookup.Gone, ids.resolve("e1", other))
        assertEquals(StableIds.Lookup.Gone, ids.resolve("e2", other))
    }

    @Test fun anEmptyReadDoesNotBecomeTheBase() {
        val ids = StableIds()
        ids.assign("app", listOf(el("Title"), el("OK", y = 900)))
        val empty = ids.assign(null, emptyList())
        assertTrue(empty.ids.isEmpty())
        assertEquals("app", ids.latest?.packageName)
        assertEquals(listOf("e1", "e2"), ids.assign("app", listOf(el("Title"), el("OK", y = 900))).ids)
    }

    @Test fun normalizeIds() {
        assertEquals("e12", StableIds.normalize("12"))
        assertEquals("e12", StableIds.normalize("[E12]"))
        assertEquals("t3", StableIds.normalize("t3"))
    }

    @Test fun diffListsAddedRemovedAndChanged() {
        val ids = StableIds()
        val list = (1..8).map { el("item $it", y = it * 100) }
        val old = ids.assign("app", list + el("Wi-Fi", y = 1000, cls = "Switch", flags = listOf("unchecked")))
        val new = ids.assign("app", list.drop(1) + el("Wi-Fi", y = 1000, cls = "Switch", flags = listOf("checked")) + el("Saved", y = 1100))
        val d = ScreenDiff.of(old, new)
        assertFalse(d.pageChanged)
        assertEquals(1, d.added.size); assertEquals(1, d.removed.size); assertEquals(1, d.changed.size)
        val text = d.render(old, new)
        assertTrue(text, text.contains("+ [e10] TextView \"Saved\""))
        assertTrue(text, text.contains("- [e1] TextView \"item 1\""))
        assertTrue(text, text.contains("~ [e9] Switch \"Wi-Fi\" {checked}"))
    }

    @Test fun mostlyNewElementsIsAPageChange() {
        val ids = StableIds()
        val old = ids.assign("app", (1..6).map { el("a$it", y = it * 100) })
        val new = ids.assign("app", (1..6).map { el("b$it", y = it * 100) })
        assertTrue(ScreenDiff.of(old, new).pageChanged)
        assertTrue(ScreenDiff.of(old, ids.assign("other", new.elements)).pageChanged)
    }

    @Test fun fingerprintIgnoresDriftAndSystemUi() {
        val ids = StableIds()
        val a = ids.assign("app", listOf(el("OK"), el("12:00", system = true)))
        val b = ids.assign("app", listOf(el("OK", y = 104), el("12:01", system = true)))
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.fingerprint, ids.assign("app", listOf(el("Done"))).fingerprint)
    }

    @Test fun systemUiChangesWhenTheShadeOpensNotWithTheClock() {
        val ids = StableIds()
        val bar = listOf(el("12:00", system = true), el("Wi-Fi", system = true))
        val a = ids.assign("app", listOf(el("OK")) + bar)
        val clock = ids.assign("app", listOf(el("OK"), el("12:01", system = true), el("Wi-Fi", system = true)))
        val shade = ids.assign("app", listOf(el("OK")) + bar + (1..8).map { el("notification $it", y = it * 100, system = true) })
        assertFalse(a.systemUiChanged(clock))
        assertTrue(a.systemUiChanged(shade))
        assertTrue(shade.systemUiChanged(a))
    }

    @Test fun limitNoticeOnlyWhenNewAndShort() {
        val ids = StableIds()
        val before = ids.assign("app", listOf(el("Send")))
        assertEquals("操作过于频繁，请稍后再试", LimitNotice.find(before, ids.assign("app", listOf(el("Send"), el("操作过于频繁，请稍后再试")))))
        assertEquals("今日已达到沟通上限", LimitNotice.find(before, ids.assign("app", listOf(el("今日已达到沟通上限")))))
        assertEquals("Too many requests", LimitNotice.find(before, ids.assign("app", listOf(el("Too many requests")))))
        val help = ids.assign("app", listOf(el("Limit reached")))
        assertNull(LimitNotice.find(help, ids.assign("app", listOf(el("Limit reached")))))
        assertNull(LimitNotice.find(before, ids.assign("app", listOf(el("x".repeat(130) + "稍后再试")))))
    }

    @Test fun outwardActions() {
        listOf("发送", "立即投递", "发表评论", "Send", "post", "关注", "回复").forEach { assertTrue(it, OutwardAction.matches(it)) }
        listOf(null, "", "设置", "Posts", "Sender info", "这是一段很长的说明文字里提到了发送").forEach { assertFalse("$it", OutwardAction.matches(it)) }
        listOf("已关注", "取消关注", "查看3条回复", "查看全部评论", "发送验证码", "获取验证码").forEach { assertFalse(it, OutwardAction.matches(it)) }
    }
}
