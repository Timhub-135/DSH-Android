package io.github.timhub135.dsh.core.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeExtEngine().apply { on(ExtMethods.LIST, FakeExtEngine.list()) }
    private val host = object : ExtHost {
        override fun busy() = true // never restart during these tests
        override suspend fun restartEngine() {}
    }
    private val store = ExtensionsStore(ExtensionsClient(engine), host, scope, pollMs = 10_000)
    private val flow = InstallFlow(store, scope)

    @After fun tearDown() = scope.cancel()

    private val inspectOk = """{"spec":"dsh-plugin-guide","kind":"npm","name":"dsh-plugin-guide","version":"0.3.39","bundle":true,"compatible":true,
        "publisher":"perry","maintainers":["perry"],"downloads":120,"provenance":true,"sizeBytes":1048576,"license":"Apache-2.0","overrides":[]}"""

    @Test fun `name to card to installed, with exactly one confirmation`() = runBlocking {
        engine.on(ExtMethods.INSPECT, inspectOk)
        engine.on(ExtMethods.INSTALL, """{"result":{"application":"restart-required","restartRequired":true,"version":"0.3.39"}}""")
        flow.start("https://www.npmjs.com/package/dsh-plugin-guide")
        eventually { flow.state.value is InstallFlow.State.Confirm }
        val card = flow.state.value as InstallFlow.State.Confirm
        assertEquals("dsh-plugin-guide", engine.last(ExtMethods.INSPECT)["input"].let { (it as JsonPrimitive).content })
        assertEquals(true, card.info.provenance)
        assertEquals(0, engine.count(ExtMethods.INSTALL))
        flow.confirm()
        eventually { flow.state.value is InstallFlow.State.Done }
        val install = engine.last(ExtMethods.INSTALL)
        assertEquals(JsonPrimitive("dsh-plugin-guide"), install["input"])
        assertTrue(install["requestId"] != null)
        assertEquals(null, install["force"])
        assertEquals(RestartState.WaitingForIdle, store.restart.value)
    }

    @Test fun `git URLs and MCP configs are answered without asking the engine`() = runBlocking {
        flow.start("git@github.com:o/r.git")
        assertEquals("needs-git", (flow.state.value as InstallFlow.State.Failed).error.code)
        flow.start("""{"mcpServers":{}}""")
        assertEquals("mcp-json", (flow.state.value as InstallFlow.State.Failed).error.code)
        flow.start("what is this")
        assertEquals("bad-input", (flow.state.value as InstallFlow.State.Failed).error.code)
        assertEquals(0, engine.calls.size)
    }

    @Test fun `inspect refusals end the flow with the reason`() = runBlocking {
        engine.on(ExtMethods.INSPECT, """{"spec":"x","kind":"npm","error":{"code":"not-bundle","message":"x 不是 DSH 插件"}}""")
        flow.start("x-pkg")
        eventually { flow.state.value is InstallFlow.State.Failed }
        assertEquals("not-bundle", (flow.state.value as InstallFlow.State.Failed).error.code)
        engine.on(ExtMethods.INSPECT, """{"spec":"y","name":"y","bundle":false}""")
        flow.start("y-pkg")
        eventually { (flow.state.value as? InstallFlow.State.Failed)?.input?.spec == "y-pkg" }
        assertEquals("not-bundle", (flow.state.value as InstallFlow.State.Failed).error.code)
    }

    @Test fun `an incompatible version keeps the card and installs anyway with force`() = runBlocking {
        engine.on(ExtMethods.INSPECT, """{"spec":"p@2","name":"p","version":"2.0.0","bundle":true,"compatible":false,
            "incompatible":[{"name":"p","version":"2.0.0","requirements":{"engines.dsh":">=0.2.2"}}],
            "error":{"code":"incompatible-version","message":"p 2.0.0 需要 DSH 引擎 >=0.2.2，手机版目前是 0.2.1-rc.5。"}}""")
        engine.on(ExtMethods.INSTALL, """{"result":{"application":"restart-required","restartRequired":true}}""")
        flow.start("p@2")
        eventually { flow.state.value is InstallFlow.State.Failed }
        val s = flow.state.value as InstallFlow.State.Failed
        assertEquals(">=0.2.2", s.info!!.requiredRange)
        flow.confirm(force = true)
        eventually { flow.state.value is InstallFlow.State.Done }
        assertEquals(JsonPrimitive(true), engine.last(ExtMethods.INSTALL)["force"])
        assertEquals(JsonPrimitive("p@2"), engine.last(ExtMethods.INSTALL)["input"])
    }

    @Test fun `a failed install shows the reason and keeps the input for a retry`() = runBlocking {
        engine.on(ExtMethods.INSPECT, inspectOk)
        engine.on(ExtMethods.INSTALL, """{"result":{"application":"failed","restartRequired":false,"error":{"code":"needs-web-services","message":"需要网页版服务","actions":["uninstall"]}}}""")
        flow.start("dsh-plugin-guide")
        eventually { flow.state.value is InstallFlow.State.Confirm }
        flow.confirm()
        eventually { flow.state.value is InstallFlow.State.Failed }
        val f = flow.state.value as InstallFlow.State.Failed
        assertEquals("needs-web-services", f.error.code)
        assertEquals("dsh-plugin-guide", f.input.spec)
        assertEquals(RestartState.Idle, store.restart.value)
    }

    @Test fun `cancelling while installing asks the engine to cancel that request`() = runBlocking {
        engine.on(ExtMethods.INSPECT, inspectOk)
        engine.on(ExtMethods.INSTALL, """{"result":{"application":"cancelled","restartRequired":false}}""")
        engine.on(ExtMethods.CANCEL, """{"cancelled":true}""")
        engine.hold(ExtMethods.INSTALL)
        flow.start("dsh-plugin-guide")
        eventually { flow.state.value is InstallFlow.State.Confirm }
        flow.confirm()
        eventually { flow.state.value is InstallFlow.State.Installing }
        val requestId = (flow.state.value as InstallFlow.State.Installing).requestId
        flow.cancel()
        eventually { engine.count(ExtMethods.CANCEL) == 1 }
        assertEquals(JsonPrimitive(requestId), engine.last(ExtMethods.CANCEL)["requestId"])
        engine.release(ExtMethods.INSTALL)
        eventually { flow.state.value == InstallFlow.State.Idle }
    }

    @Test fun `engine errors during inspect become user errors`() = runBlocking {
        engine.fail(ExtMethods.INSPECT, -32000, "socket closed")
        flow.start("ds-bot")
        eventually { flow.state.value is InstallFlow.State.Failed }
        assertEquals(ExtCodes.UNKNOWN, (flow.state.value as InstallFlow.State.Failed).error.code)
    }
}
