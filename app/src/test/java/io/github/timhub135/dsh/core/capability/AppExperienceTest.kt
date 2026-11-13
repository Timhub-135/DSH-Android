package io.github.timhub135.dsh.core.capability

import io.github.timhub135.dsh.core.capability.AppNotes.Kind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppExperienceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun args(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
    private fun RouteTracker.launch(pkg: String = "com.shop") = observe("s1", "app_launch", args("package" to pkg), true, "Launched", null, null)
    private fun RouteTracker.tap(label: String?, effective: Boolean = true, vararg a: Pair<String, String> = arrayOf("node" to "e3")) =
        observe("s1", "screen_tap", args(*a), true, if (label != null) "Tapped [$label]\nEffect: page changed." else "Tapped at 10,20",
            if (effective) ActionOutcome.Effective else ActionOutcome.NoEffect, null)

    @Test fun `labelled taps after a launch make a route`() {
        val r = RouteTracker()
        r.newTurn("s1", "帮我看看最近买了什么")
        r.launch()
        r.tap("我的"); r.tap("没反应", effective = false); r.tap("全部订单")
        assertEquals("com.shop" to listOf("我的", "全部订单"), r.route("s1/sub-1"))
        val end = r.finish("s1", ok = true)
        assertTrue(end.success)
        assertEquals("com.shop", end.pkg)
        assertEquals(listOf("我的", "全部订单"), end.route?.second)
        assertNull("the next turn starts without a route", r.route("s1"))
    }

    @Test fun `back pops a step and the route survives`() {
        val r = RouteTracker()
        r.newTurn("s1", "")
        r.launch(); r.tap("我的"); r.tap("钱包")
        r.observe("s1", "screen_key", args("key" to "back"), true, "", ActionOutcome.Effective, null)
        r.tap("设置"); r.tap("返回"); r.tap("全部订单")
        assertEquals(listOf("我的", "全部订单"), r.route("s1")?.second)
    }

    @Test fun `typing, coordinates, long taps and other apps end the route`() {
        fun broken(block: RouteTracker.() -> Unit): Boolean {
            val r = RouteTracker(); r.newTurn("s1", "给张三发消息"); r.launch(); r.tap("我的"); r.block(); r.tap("设置")
            return r.route("s1") == null
        }
        assertTrue(broken { observe("s1", "screen_type", args("text" to "hello"), true, "Typed", ActionOutcome.Effective, null) })
        assertTrue(broken { tap(null, a = arrayOf("x" to "10", "y" to "20")) })
        assertTrue(broken { tap("收藏", a = arrayOf("node" to "e1", "kind" to "long")) })
        assertTrue(broken { observe("s1", "screen_key", args("key" to "enter"), true, "", ActionOutcome.Effective, null) })
        assertTrue(broken { observe("s1", "screen_read", args(), true, "Effect: switched to 微信 (com.tencent.mm).", null, null) })
        assertTrue("named in the request", broken { tap("张三") })
        assertTrue("digits", broken { tap("订单 3") })
        assertTrue("long label", broken { tap("这是一个很长很长的商品标题啊") })
        assertTrue("unlabelled", broken { tap(null) })
        assertFalse(broken { observe("s1", "screen_scroll", args("direction" to "down"), true, "", ActionOutcome.Effective, null) })
        val r = RouteTracker(); r.newTurn("s1", ""); r.launch()
        repeat(RouteTracker.MAX_STEPS + 1) { r.tap("页$it".replace(Regex("\\d"), "") + ('a' + it)) }
        assertNull("too many steps", r.route("s1"))
    }

    @Test fun `only successful turns teach`() {
        val r = RouteTracker()
        r.newTurn("s1", ""); r.launch(); r.tap("我的"); r.tap("全部订单")
        val aborted = r.finish("s1", ok = false)
        assertFalse(aborted.success); assertNull(aborted.route)
        r.newTurn("s1", ""); r.launch(); r.tap("我的"); r.tap("全部订单")
        r.observe("s1", "screen_tap", args("node" to "e1"), false, "x\n\n[Loop guard] Stopped: 3 calls in a row failed. Do not…", null, null)
        val stopped = r.finish("s1", ok = true)
        assertFalse(stopped.success); assertNull(stopped.route)
        r.newTurn("s1", ""); r.launch(); r.tap("我的")
        assertTrue("one step is not a route", r.finish("s1", ok = true).let { it.success && it.route == null })
    }

    @Test fun `routes are kept once per target, replaced when the path changes`() {
        var version = "1 (1)"
        val notes = AppNotes(File(tmp.root, AppNotes.FILE), { if (it == "com.shop") version else null })
        val a = (notes.addRoute("com.shop", listOf("我的", "全部订单")) as AppNotes.Outcome.Saved).note
        assertEquals(Kind.route, a.kind)
        assertEquals("到「全部订单」：我的 → 全部订单", a.text)
        assertEquals("全部订单", AppNotes.routeTarget(a.text))
        assertEquals(1, (notes.addRoute("com.shop", listOf("我的", "全部订单")) as AppNotes.Outcome.Saved).note.uses)
        version = "2 (2)"
        val b = (notes.addRoute("com.shop", listOf("我", "订单中心", "全部订单")) as AppNotes.Outcome.Saved).note
        assertEquals(a.id, b.id)
        assertEquals("2 (2)", b.appVersion)
        assertTrue(notes.addRoute("com.shop", listOf("我的", "待付款")) is AppNotes.Outcome.Saved)
        assertTrue("a route does not block a similar fact", notes.add("com.shop", "到「待付款」：我的 → 待付款", Kind.fact) is AppNotes.Outcome.Saved)
        assertTrue(notes.addRoute("com.shop", listOf("我的")) is AppNotes.Outcome.Rejected)
        val notice = notes.notice(listOf("com.shop"), "商城")!!
        assertTrue(notice.contains("(路线) 到「全部订单」：我 → 订单中心 → 全部订单"))
        assertTrue(notice.contains("hints only, verify each step"))
    }

    @Test fun `over the cap, notes matching the request come first`() {
        val now = longArrayOf(0)
        val notes = AppNotes(File(tmp.root, AppNotes.FILE), { "1 (1)" }, { ++now[0] })
        val fillers = listOf("首页顶部有扫一扫", "消息页可以左滑删除", "通讯录在底部第二格", "视频号在发现页", "钱包在我页面中间", "设置在我页面最下面")
        // The oldest note: left out by default, but the request mentions 朋友圈.
        val target = (notes.add("com.a", "朋友圈发图要长按右上角相机图标", Kind.trap) as AppNotes.Outcome.Saved).note
        fillers.forEach { notes.add("com.a", it, Kind.fact) }
        val plain = notes.notice(listOf("com.a"), "微信", max = 3)!!
        assertFalse(plain.contains(target.id + "]"))
        val ranked = notes.notice(listOf("com.a"), "微信", max = 3, query = "帮我发一条朋友圈")!!
        assertTrue(ranked.lines()[1].startsWith("[${target.id}]"))
        assertEquals(listOf(target.text), AppNotes.rank(notes.forPackages(listOf("com.a")), "朋友圈").take(1).map { it.text })
    }

    @Test fun `turn end records the route and asks for reflection only after success`() = runBlocking {
        val notes = AppNotes(File(tmp.root, AppNotes.FILE), { if (it == "com.shop") "1 (1)" else null })
        var on = true
        val x = AppExperience(notes, { on }) { "商城" }
        x.newTurn("s1", "看看订单")
        x.tracker.launch(); x.tracker.tap("我的"); x.tracker.tap("全部订单")
        val reply = x.turnEnded(buildJsonObject { put("sessionId", "s1"); put("ok", true) })
        assertEquals("true", reply["reflect"]?.jsonPrimitive?.content)
        assertEquals("com.shop", reply["package"]?.jsonPrimitive?.content)
        assertEquals("到「全部订单」：我的 → 全部订单", notes.all.value.single().text)
        on = false
        assertEquals("false", x.turnEnded(buildJsonObject { put("sessionId", "s1"); put("ok", true) })["reflect"]?.jsonPrimitive?.content)
        on = true
        assertEquals("false", x.turnEnded(buildJsonObject { put("sessionId", "s1"); put("ok", false) })["reflect"]?.jsonPrimitive?.content)

        val added = x.reflected(buildJsonObject {
            put("sessionId", "s1"); put("package", "com.shop")
            putJsonArray("notes") {
                addJsonObject { put("text", "退款入口在订单详情页底部"); put("kind", "entry") }
                addJsonObject { put("text", "首页弹窗要点右上角关闭"); put("kind", "route") }
                addJsonObject { put("text", "客服电话 400 123 4567") }
                addJsonObject { put("text", "beyond the cap of three") }
            }
        })
        assertEquals(2, added)
        assertEquals(Kind.fact, notes.all.value.last().kind)
        assertEquals(1, x.reflected(buildJsonObject {
            put("package", "com.shop")
            putJsonArray("notes") { addJsonObject { put("text", "退款入口在订单详情页底部。") }; add(JsonPrimitive("not an object")); addJsonObject { put("text", "搜索框在首页顶部") } }
        }))
        assertEquals(0, x.reflected(buildJsonObject { put("package", "com.gone"); putJsonArray("notes") { addJsonObject { put("text", "x 在 y") } } }))
    }

    @Test fun `an entry note or a confirmation records the route that led there`() {
        val notes = AppNotes(File(tmp.root, AppNotes.FILE), { "1 (1)" })
        val x = AppExperience(notes, { true }) { it }
        x.newTurn("s1", "")
        x.tracker.launch(); x.tracker.tap("我的"); x.tracker.tap("设置")
        x.noteUsed("s1", "com.other")
        assertTrue(notes.all.value.isEmpty())
        x.noteUsed("s1", "com.shop")
        x.noteUsed("s1", "com.shop")
        assertEquals(listOf("到「设置」：我的 → 设置"), notes.all.value.map { it.text })
        assertEquals(0, notes.all.value.single().uses)
    }
}
