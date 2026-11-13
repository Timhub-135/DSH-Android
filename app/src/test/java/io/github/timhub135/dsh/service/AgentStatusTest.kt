package io.github.timhub135.dsh.service

import io.github.timhub135.dsh.core.capability.LiveCall
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.session.LoggedEvent
import io.github.timhub135.dsh.core.session.PendingApproval
import io.github.timhub135.dsh.core.session.PendingTakeover
import io.github.timhub135.dsh.core.session.RowStatus
import io.github.timhub135.dsh.core.session.SessionLive
import io.github.timhub135.dsh.service.CapsuleStatus.End
import io.github.timhub135.dsh.service.CapsuleStatus.Need
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {
    private fun ev(seq: Long, type: String, json: String) = LoggedEvent(seq, type, seq * 1000, RpcJson.parseToJsonElement(json) as JsonObject)
    private fun call(seq: Long, id: String, name: String, args: String = "{}") =
        LoggedEvent(seq, "tool/call", seq * 1000, buildJsonObject { put("callId", id); put("name", name); put("arguments", args) })
    private fun result(seq: Long, id: String) = ev(seq, "tool/result", """{"message":{"toolCallId":"$id","content":[{"type":"text","text":"ok"}]}}""")
    private fun end(seq: Long, kind: String) = ev(seq, "turn/end", """{"turn":2,"reason":{"kind":"$kind"}}""")
    /** A finished earlier turn: only the newest one may count. */
    private val earlier = listOf(ev(0, "turn/start", """{"turn":1}"""), call(1, "old", "screen_tap"), result(2, "old"),
        ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""))
    private fun live(events: List<LoggedEvent>, running: Boolean = true) = SessionLive("s", events = earlier + events, running = running)

    @Test fun `waiting conversation wins and the followed one is kept`() {
        val st = mapOf("a" to RowStatus.Running, "b" to RowStatus.Question, "c" to RowStatus.Running)
        assertEquals("b", AgentStatus.focus(st, "a"))
        assertEquals("c", AgentStatus.focus(st - "b", "c"))
        assertEquals("a", AgentStatus.focus(mapOf("a" to RowStatus.Idle, "a/child" to RowStatus.Approval), null))
        assertNull(AgentStatus.focus(mapOf("a" to RowStatus.Idle), "a"))
    }

    @Test fun `running tool is the step, thinking between steps`() {
        val tap = call(11, "c1", "screen_tap", """{"text":"提交订单"}""")
        val running = AgentStatus.of(live(listOf(ev(10, "turn/start", """{"turn":2}"""), tap)), false) as CapsuleStatus.Working
        assertEquals("c1", running.step?.callId)
        assertEquals(false, running.background)
        val thinking = AgentStatus.of(live(listOf(ev(10, "turn/start", """{"turn":2}"""), tap, result(12, "c1"))), false) as CapsuleStatus.Working
        assertNull(thinking.step)
    }

    @Test fun `agent screen work is background unless the call targets the main screen`() {
        val start = ev(10, "turn/start", """{"turn":2}""")
        val bg = AgentStatus.of(live(listOf(start, call(11, "c1", "screen_tap"))), hasAgentScreen = true) as CapsuleStatus.Working
        assertTrue(bg.background)
        val main = AgentStatus.of(live(listOf(start, call(11, "c1", "screen_tap", """{"screen":"main"}"""))), hasAgentScreen = true) as CapsuleStatus.Working
        assertEquals(false, main.background)
        val web = AgentStatus.of(live(listOf(start, call(11, "c1", "web_search"))), hasAgentScreen = true) as CapsuleStatus.Working
        assertEquals(false, web.background)
    }

    @Test fun `pending card says why it needs the user`() {
        val base = live(listOf(ev(10, "turn/start", """{"turn":2}"""), call(11, "c1", "screen_tap")))
        val approval = PendingApproval("k", "s", "sms_send", "c1", null, null, Risk.write, true)
        assertEquals(CapsuleStatus.NeedsYou(Need.Approval, "sms_send"), AgentStatus.of(base.withPending(approval), false))
        val takeover = PendingTakeover("t", "s", "hand_to_user", "请登录", "login")
        assertEquals(CapsuleStatus.NeedsYou(Need.Takeover, "请登录"), AgentStatus.of(base.withPending(approval).withPending(takeover), false))
    }

    @Test fun `ended turn maps to its outcome`() {
        val start = ev(10, "turn/start", """{"turn":2}""")
        assertEquals(CapsuleStatus.Ended(End.Done), AgentStatus.of(live(listOf(start, end(11, "completed")), running = false), false))
        assertEquals(CapsuleStatus.Ended(End.Stopped), AgentStatus.of(live(listOf(start, ev(11, "app/stopped", "{}")), running = false), false))
        assertEquals(CapsuleStatus.Ended(End.Failed), AgentStatus.of(live(listOf(start, end(11, "error")), running = false), false))
        assertNull(AgentStatus.of(SessionLive("s"), false))
    }

    @Test fun `border shows during main screen calls and lingers only while the turn runs`() {
        val running = mapOf("s" to RowStatus.Running)
        val tap = LiveCall("s", "screen_tap", mainScreen = true, acts = true)
        assertEquals(Long.MAX_VALUE, AgentStatus.borderUntil(listOf(tap), emptyMap(), running, 0))
        assertEquals(Long.MAX_VALUE, AgentStatus.borderUntil(listOf(tap.copy(sessionId = "s/child")), emptyMap(), running, 0))
        assertNull(AgentStatus.borderUntil(listOf(tap.copy(mainScreen = false)), emptyMap(), running, 0))
        assertNull(AgentStatus.borderUntil(listOf(tap), emptyMap(), mapOf("s" to RowStatus.Approval), 0))
        assertEquals(Long.MAX_VALUE, AgentStatus.borderUntil(listOf(tap.copy(sessionId = "")), emptyMap(), emptyMap(), 0))
        assertEquals(1_000 + AgentStatus.BORDER_LINGER_MS, AgentStatus.borderUntil(emptyList(), mapOf("s" to 1_000L), running, 2_000))
        assertNull(AgentStatus.borderUntil(emptyList(), mapOf("s" to 1_000L), running, 1_000 + AgentStatus.BORDER_LINGER_MS))
        assertNull(AgentStatus.borderUntil(emptyList(), mapOf("s" to 1_000L), mapOf("s" to RowStatus.Question), 2_000))
    }
}
