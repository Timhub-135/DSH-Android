package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.extensions.FakeExtEngine.Companion.list
import io.github.timhub135.dsh.core.extensions.FakeExtEngine.Companion.plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ExtensionsStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeExtEngine()
    private val busy = AtomicBoolean(false)
    private val restarts = AtomicInteger(0)
    private var badge = -1
    /** What the engine lists after the next restart. */
    @Volatile private var afterRestart: String? = null
    private val host = object : ExtHost {
        override fun busy() = busy.get()
        override suspend fun restartEngine() { restarts.incrementAndGet(); afterRestart?.let { engine.on(ExtMethods.LIST, it) } }
        override suspend fun setUpdateBadge(count: Int) { badge = count }
    }
    private var cached: ExtList? = null
    private val cache = object : ExtListCache {
        override fun read() = cached
        override fun write(list: ExtList) { cached = list }
    }
    private var clock = 1_000_000L
    private fun store() = ExtensionsStore(ExtensionsClient(engine), host, scope, cache, pollMs = 10, now = { clock })

    @After fun tearDown() = scope.cancel()

    @Test fun `refresh lists, caches, and the cache draws the page before the engine is up`() = runBlocking {
        engine.on(ExtMethods.LIST, list(plugin("a")))
        val s = store()
        assertNull(s.list.value)
        assertEquals("a", s.refresh()!!.plugins.single().name)
        assertEquals("a", cached!!.plugins.single().name)
        assertEquals("a", store().list.value!!.plugins.single().name)
    }

    @Test fun `an engine without plugin support is reported, not crashed on`() = runBlocking {
        val s = store()
        assertNull(s.refresh())
        assertEquals(ExtCodes.UNSUPPORTED_ENGINE, s.loadError.value!!.code)
    }

    @Test fun `a restart-required change restarts right away when idle and reports what was activated`() = runBlocking {
        engine.on(ExtMethods.LIST, list(plugin("a", status = "restart-pending")))
        engine.on(ExtMethods.SET_ENABLED, """{"application":"restart-required","restartRequired":true}""")
        afterRestart = list(plugin("a", extra = ""","tools":["web_search_bing"],"skills":["guide"]"""))
        val s = store()
        s.refresh()
        val event = async { s.events.first() }
        val view = s.change("a") { setEnabled("a", true) }
        assertTrue(view.restartRequired)
        val activated = (event.await() as ExtEvent.Activated).results.single()
        assertEquals(Activation("a", "T-a", ok = true, tools = listOf("web_search_bing"), skills = listOf("guide")), activated)
        assertEquals(1, restarts.get())
        eventually { s.restart.value == RestartState.Idle }
    }

    @Test fun `while a task runs the restart waits, then happens when it ends`() = runBlocking {
        engine.on(ExtMethods.LIST, list())
        engine.on(ExtMethods.INSTALL, """{"result":{"application":"restart-required","restartRequired":true,"version":"1.0.0"}}""")
        busy.set(true)
        val s = store()
        s.change("a") { install("a", "r1") }
        eventually { s.restart.value == RestartState.WaitingForIdle }
        Thread.sleep(50)
        assertEquals(0, restarts.get())
        busy.set(false)
        eventually { restarts.get() == 1 && s.restart.value == RestartState.Idle }
    }

    @Test fun `restart now does not wait for the task`() = runBlocking {
        engine.on(ExtMethods.LIST, list())
        busy.set(true)
        val s = store()
        s.scheduleRestart()
        eventually { s.restart.value == RestartState.WaitingForIdle }
        s.restartNow()
        eventually { restarts.get() == 1 }
    }

    @Test fun `the engine's pending restart is picked up after the app was killed`() = runBlocking {
        engine.on(ExtMethods.LIST, list(extra = ""","restartPending":true"""))
        afterRestart = list()
        store().refresh()
        eventually { restarts.get() == 1 }
    }

    @Test fun `a failed change is returned in place and restarts nothing`() = runBlocking {
        engine.on(ExtMethods.LIST, list())
        engine.on(ExtMethods.REMOVE, """{"application":"failed","restartRequired":false,"error":{"code":"not-installed","message":"没装过"}}""")
        val s = store()
        val v = s.change("x") { remove("x") }
        assertTrue(v.failed)
        assertEquals("not-installed", v.error!!.code)
        Thread.sleep(30)
        assertEquals(0, restarts.get())
        assertEquals(0, engine.count(ExtMethods.LIST))
    }

    @Test fun `request errors carry the engine's user error`() = runBlocking {
        engine.fail(ExtMethods.SET_ENABLED, -32000, "boom", """{"code":"management-required","message":"内置插件不能停用"}""")
        val v = store().change("x") { setEnabled("x", false) }
        assertEquals(UserError("management-required", "内置插件不能停用"), v.error)
    }

    @Test fun `notifications update progress, refresh on change, and surface notices`() = runBlocking {
        engine.on(ExtMethods.LIST, list(plugin("a")))
        val s = store()
        s.onNotification(ExtMethods.N_PROGRESS, buildJsonObject { put("requestId", "r1"); put("phase", "resolving"); put("index", 2); put("total", 2) })
        assertEquals(ExtProgress("r1", "resolving", index = 2, total = 2), s.progress.value["r1"])
        s.clearProgress("r1")
        assertTrue(s.progress.value.isEmpty())
        s.onNotification(ExtMethods.N_CHANGED, JsonObject(emptyMap()))
        eventually { s.list.value != null }
        val e = async { s.events.first() }
        yield()
        s.onNotification(ExtMethods.N_NOTICE, buildJsonObject { put("kind", "suspended"); put("name", "a"); put("message", "a 让引擎崩溃了，已暂停它。") })
        assertEquals("suspended", (e.await() as ExtEvent.Notice).notice.kind)
    }

    @Test fun `the engine's activation notice right after our own restart is not repeated`() = runBlocking {
        engine.on(ExtMethods.LIST, list(plugin("a")))
        afterRestart = list(plugin("a"))
        val s = store()
        s.restartNow()
        eventually { restarts.get() == 1 && s.restart.value == RestartState.Idle }
        val got = mutableListOf<ExtEvent>()
        val job = scope.async { s.events.collect { got += it } }
        yield(); Thread.sleep(20)
        s.onNotification(ExtMethods.N_NOTICE, buildJsonObject { put("kind", "activated"); put("name", "a") })
        Thread.sleep(50)
        assertTrue(got.isEmpty())
        // Much later (a boot the store did not cause): reported.
        clock += ExtensionsStore.OWN_RESTART_WINDOW_MS + 1
        s.onNotification(ExtMethods.N_NOTICE, buildJsonObject { put("kind", "activated"); put("name", "a") })
        eventually { got.size == 1 }
        job.cancel()
    }

    @Test fun `update check sets the badge to compatible updates`() = runBlocking {
        engine.on(ExtMethods.UPDATES, """{"updates":[{"name":"a","current":"1.0.0","latest":"1.1.0","compatible":true},{"name":"b","current":"1","latest":"2","compatible":false}]}""")
        val u = store().checkUpdates()
        assertEquals(2, u.size)
        assertEquals(1, badge)
        assertFalse(engine.last(ExtMethods.UPDATES).containsKey("refresh"))
    }

    @Test fun `activation report covers changed plugins, failures, and switching off`() {
        val before = ExtList(listOf(ExtPlugin("a", status = "restart-pending", enabled = true), ExtPlugin("b", status = "active", enabled = true), ExtPlugin("c", status = "active", enabled = true)))
        val after = ExtList(listOf(
            ExtPlugin("a", title = LocalizedText.of("A"), status = "active", enabled = true, tools = listOf("t1")),
            ExtPlugin("b", status = "active", enabled = true),
            ExtPlugin("c", status = "disabled", enabled = false),
            ExtPlugin("d", status = "failed", enabled = true, statusDetail = UserError("activation-failed", "d 已安装，但启动时出错：boom")),
        ))
        val all = ExtensionsStore.activationReport(before, after, emptySet()).associateBy { it.name }
        assertEquals(setOf("a", "c", "d"), all.keys)
        assertEquals(listOf("t1"), all["a"]!!.tools)
        assertTrue(all["c"]!!.disabled)
        assertFalse(all["d"]!!.ok)
        assertEquals(listOf("b"), ExtensionsStore.activationReport(before, after, setOf("b")).map { it.name })
    }

    @Test fun `client sends flat market queries and unwraps wrapped index lists`() = runBlocking {
        engine.on(ExtMethods.MARKET_INDEXES, """{"indexes":[{"url":"u","title":{"en":"M"}}]}""")
        engine.on(ExtMethods.MARKET_SEARCH, """{"url":"u","entries":[],"total":0,"nextOffset":null}""")
        engine.on(ExtMethods.MARKET_ENTRY, """{"entry":null}""")
        val c = ExtensionsClient(engine)
        assertEquals("u", c.marketIndexes().single().url)
        c.marketSearch("u", "web")
        val p = engine.last(ExtMethods.MARKET_SEARCH)
        assertEquals(JsonPrimitive("web"), p["text"])
        assertEquals(JsonPrimitive(0), p["offset"])
        assertEquals("""{"kind":"relevance"}""", p["sort"].toString())
        assertNull(c.marketEntry("u", "x"))
    }

    @Test fun `operations that answer with an error object throw it`() = runBlocking {
        engine.on(ExtMethods.SKILLS_REMOVE, """{"error":{"code":"not-installed","message":"没有这个技能"}}""")
        val e = runCatching { ExtensionsClient(engine).skillRemove("x") }.exceptionOrNull() as ExtException
        assertEquals("not-installed", e.error.code)
    }

    @Test fun `an unreadable plugin list is a page error, never an engine restart or a safe-mode request`() = runBlocking {
        // v0.3.1: a title sent as a locale map failed to decode and the page offered 安全模式启动 as if the engine had not started.
        engine.on(ExtMethods.LIST, """{"plugins":[],"safeMode":"maybe"}""")
        val s = store()
        assertNull(s.refresh())
        assertEquals(ExtCodes.BAD_RESULT, s.loadError.value!!.code)
        yield()
        assertEquals(0, restarts.get())
        assertNull("nothing queued a safe-mode start", SafeModeRequest.take(breakerOpen = false) { true })
    }

    @Test fun `the safe-mode banner needs a plugin safe mode still pauses`() = runBlocking {
        val paused = plugin("a", status = "suspended", enabled = false, extra = ""","suspended":{"reason":"safe-mode","at":1}""")
        engine.on(ExtMethods.LIST, list(paused, extra = ""","safeMode":true,"safeModeReason":"requested""""))
        val l = store().refresh()!!
        assertTrue(l.showSafeModeBanner)
        assertEquals(1, l.safeModePaused)
        assertEquals("requested", l.safeModeReason)
        // the only plugin uninstalled: an older engine may still say safeMode=true; nothing is paused, no banner
        engine.on(ExtMethods.LIST, list(extra = ""","safeMode":true"""))
        val empty = store().refresh()!!
        assertFalse(empty.showSafeModeBanner)
        assertEquals(0, empty.safeModePaused)
        // a crash suspension is not safe mode
        engine.on(ExtMethods.LIST, list(plugin("b", status = "suspended", enabled = false, extra = ""","suspended":{"reason":"crash","at":1}"""), extra = ""","safeMode":true"""))
        assertFalse(store().refresh()!!.showSafeModeBanner)
    }
}
