package io.github.timhub135.dsh.core.session

import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.model.Risk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectorTest {
    private fun ev(seq: Long, type: String, json: String, time: Long = seq * 1000) =
        LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    private fun result(seq: Long, text: String, replace: Boolean = false) = ev(seq, "tool/result",
        """{"message":{"toolCallId":"c1","content":[{"type":"text","text":"$text"}]}${if (replace) ""","$SURFACE_OP_KEY":{"op":"replace"}""" else ""}}""")

    @Test fun `pruned replacement does not overwrite the tool result`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "tool/call", """{"callId":"c1","name":"file_read","arguments":"{}"}"""),
            result(2, "full"),
            ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            result(900, "[pruned]", replace = true),
        )
        val tool = Projector.conversation(events, running = false).tool("c1")!!
        assertEquals("full", tool.result)
        assertEquals(2000L, tool.endTime)
        val rec = Projector.trajectory(events, emptyList(), running = false).records.first { it.callId == "c1" }
        assertEquals("full", rec.output)
    }

    @Test fun `context counts cached input and todos survive a new turn`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "todo/write", """{"todos":[{"content":"a","status":"pending"}]}"""),
            ev(2, "assistant/message", """{"message":{"content":[{"type":"text","text":"hi"}]},"usage":{"inputTokens":100,"cacheReadTokens":9000,"cacheWriteTokens":50,"outputTokens":7}}"""),
            ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            ev(4, "turn/start", """{"turn":2}"""),
        )
        val conv = Projector.conversation(events, running = true)
        assertEquals(9150L, conv.contextTokens)
        assertEquals(9150L, conv.turns.first().usage.input)
        assertEquals(1, conv.todos?.size)
    }

    @Test fun `a model switch shows as a quiet notice after the prompt it came with (#53)`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":2}"""),
            ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"再仔细想想"}]}"""),
            ev(2, "user/message", """{"source":{"kind":"model-selection","form":"notice","summary":"deepseek-official/deepseek-flash → kimi/kimi-k3"},"content":[{"type":"text","text":"[model changed: …]"}]}"""),
            ev(3, "assistant/message", """{"message":{"content":[{"type":"text","text":"好"}]}}"""),
        )
        val turn = Projector.conversation(events, running = true).turns.single()
        assertEquals("再仔细想想", turn.user?.text)
        val notice = turn.blocks.filterIsInstance<Block.Notice>().single()
        assertEquals(MODEL_SWITCH, notice.kind)
        assertEquals("deepseek-official/deepseek-flash → kimi/kimi-k3", notice.text)
    }

    @Test fun `app prompt is not shown as a user message`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"${APP_PROMPT_PREFIX}continue"}]}"""),
        )
        assertNull(Projector.conversation(events, running = true).turns.single().user)
    }

    @Test fun `offline failure followed by the network wait notice is not a final failure`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "tool/call", """{"callId":"c1","name":"wifi_set","arguments":"{}"}"""),
            result(2, "Wi-Fi off"),
            ev(3, "turn/end", """{"turn":1,"reason":{"kind":"error","error":{"message":"DeepSeek Messages transport failed","code":"TRANSPORT"}}}"""),
            ev(-1, "app/notice", """{"kind":"$NETWORK_WAIT","text":"offline"}""", time = 4000),
        )
        val turn = Projector.conversation(events, running = true).turns.single()
        assertEquals(TurnStatus.Running, turn.status)
        assertNull(turn.error)
        assertTrue(turn.blocks.last() is Block.Notice)
        // Without the notice the same log is a failed turn.
        assertEquals(TurnStatus.Failed, Projector.conversation(events.dropLast(1), running = false).turns.single().status)
    }

    private val offlineTurn = listOf(
        ev(0, "turn/start", """{"turn":1}"""),
        ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"turn off wifi"}]}"""),
        ev(2, "tool/call", """{"callId":"c1","name":"wifi_set","arguments":"{}"}"""),
        result(3, "Wi-Fi off"),
        ev(4, "turn/end", """{"turn":1,"reason":{"kind":"error","error":{"message":"transport failed","code":"TRANSPORT"}}}"""),
        ev(-1, "app/notice", """{"kind":"$NETWORK_WAIT","text":"offline"}""", time = 4500),
    )
    private val continuation = listOf(
        ev(5, "turn/start", """{"turn":2}"""),
        ev(6, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"${APP_PROMPT_PREFIX}continue"}]}"""),
        ev(7, "assistant/message", """{"message":{"content":[{"type":"text","text":"Wi-Fi is off."}]}}"""),
    )

    @Test fun `offline failure continued after the network is back is one turn with the continuation's status`() {
        val waiting = Projector.conversation(offlineTurn, running = true).turns.single()
        assertEquals(TurnStatus.Running, waiting.status)
        assertEquals(false, (waiting.blocks.last() as Block.Notice).resumed)

        val running = Projector.conversation(offlineTurn + continuation, running = true).turns.single()
        assertEquals(TurnStatus.Running, running.status)

        val done = offlineTurn + continuation + ev(8, "turn/end", """{"turn":2,"reason":{"kind":"completed"}}""")
        val turn = Projector.conversation(done, running = false).turns.single()
        assertEquals(TurnStatus.Completed, turn.status)
        assertNull(turn.error)
        assertEquals("turn off wifi", turn.user?.text)
        assertEquals("Wi-Fi is off.", turn.reply?.text)
        val notice = turn.blocks.filterIsInstance<Block.Notice>().single()
        assertTrue(notice.resumed)
        assertEquals(ToolState.Done, (turn.blocks.first { it is Block.Tool } as Block.Tool).state)
    }

    @Test fun `continuation that fails again shows one failure`() {
        val events = offlineTurn + continuation.take(2) +
            ev(8, "turn/end", """{"turn":2,"reason":{"kind":"error","error":{"message":"transport failed","code":"TRANSPORT"}}}""")
        val conv = Projector.conversation(events, running = false)
        val turn = conv.turns.single()
        assertEquals(TurnStatus.Failed, turn.status)
        assertEquals("TRANSPORT", turn.error?.code)
        assertTrue(turn.blocks.filterIsInstance<Block.Notice>().single().resumed)
    }

    @Test fun `continuation prompt logged before its turn start continues the same turn`() {
        val events = offlineTurn + listOf(continuation[1], continuation[0], continuation[2], ev(8, "turn/end", """{"turn":2,"reason":{"kind":"completed"}}"""))
        val turn = Projector.conversation(events, running = false).turns.single()
        assertEquals(TurnStatus.Completed, turn.status)
        assertTrue(turn.blocks.filterIsInstance<Block.Notice>().single().resumed)
    }

    @Test fun `engine crash continuation is folded into the interrupted turn`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "tool/call", """{"callId":"c1","name":"shell","arguments":"{}"}"""),
            ev(-1, "app/notice", """{"kind":"$ENGINE_RECOVERING","text":"restarting"}""", time = 1500),
        ) + continuation + ev(8, "turn/end", """{"turn":2,"reason":{"kind":"completed"}}""")
        val turn = Projector.conversation(events, running = false).turns.single()
        assertEquals(TurnStatus.Completed, turn.status)
        assertTrue(turn.blocks.filterIsInstance<Block.Notice>().single().resumed)
    }

    @Test fun `a user prompt after a wait notice starts a new turn`() {
        val events = offlineTurn + ev(5, "turn/start", """{"turn":2}""") +
            ev(6, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"never mind"}]}""")
        assertEquals(2, Projector.conversation(events, running = true).turns.size)
    }

    @Test fun `pending requests queue and the next becomes visible`() {
        val a = PendingApproval("a", "s", "t1", null, null, null, Risk.read, true)
        val b = PendingApproval("b", "s", "t2", null, null, null, Risk.read, true)
        var live = SessionLive("s").withPending(a).withPending(b)
        assertEquals("a", live.approval?.requestKey)
        live = live.withoutPending("b")
        assertEquals("a", live.approval?.requestKey)
        live = live.withoutPending("a")
        assertNull(live.approval)
        assertTrue(!live.hasPendingRequest)
    }

    @Test fun `idle hands the queue back to the composer`() {
        val live = SessionLive("s", running = true, queue = listOf(QueuedMessage("1", "one"), QueuedMessage("2", "two"))).idle()
        assertTrue(live.queue.isEmpty() && !live.running)
        assertNotNull(live.restoredDraft)
        assertEquals("one\n\ntwo", live.restoredDraft)
        assertNull(SessionLive("s", running = true).idle().restoredDraft)
    }

    @Test fun `turn summary counts calls and time to first output`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "step/start", """{"turn":1,"step":1}"""),
            ev(3, "assistant/message", """{"turn":1,"step":1,"firstTokenTime":1500,"message":{"content":[{"type":"tool-call","name":"screen_read"}]}}"""),
            ev(4, "tool/call", """{"callId":"c1","name":"screen_read","arguments":"{}"}"""),
            result(5, "ok"),
            ev(6, "assistant/message", """{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"done"}]}}"""),
            ev(7, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
        )
        val turn = Projector.trajectory(events, emptyList(), running = false).turns.single { it.number == 1 }
        assertEquals(1, turn.toolCalls)
        assertEquals(1500L, turn.firstOutputMs)
        assertEquals(7000L, turn.end!! - turn.start)
        val noToken = Projector.trajectory(events.map { if (it.seq == 3L) ev(3, "assistant/message", """{"turn":1,"step":1,"message":{"content":[]}}""") else it },
            emptyList(), running = false).turns.single { it.number == 1 }
        assertEquals(3000L, noToken.firstOutputMs)
    }
}
