package io.github.timhub135.dsh.core.session

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插话发送 / 立即发送 (#41): the pending bubble, its hand-off to the logged message, the fallbacks, and the steered turn. */
class SteerTest {
    private fun ev(seq: Long, type: String, json: String, time: Long = seq * 1000) =
        LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    private fun user(seq: Long, text: String, id: String = "m$seq") =
        ev(seq, "user/message", """{"id":"$id","source":{"kind":"user"},"content":[{"type":"text","text":"$text"}]}""")

    /** The repro of #41: a shell call running when the user steers. */
    private val running = listOf(
        ev(0, "turn/start", """{"turn":1}"""),
        user(1, "Use the shell tool to run sleep 40 four times"),
        ev(2, "tool/call", """{"callId":"c1","name":"bash","arguments":"{}"}"""),
    )

    private fun SessionLive.log(e: LoggedEvent) = copy(events = events + e).withLogged(e)

    @Test fun `a steer shows at once and is replaced by its logged message, not shown twice`() {
        var live = SessionLive("s", events = running, running = true).withSteer(SteerMessage("l1", "STEERTEST: also tell me the battery level."))
        assertEquals(listOf("STEERTEST: also tell me the battery level."), live.steers.map { it.text })
        live = live.steerAccepted("l1", "m9")
        assertEquals("m9", live.steers.single().messageId)
        // Other events, and another user message, leave it pending.
        live = live.log(ev(3, "tool/result", """{"message":{"toolCallId":"c1","content":[]}}"""))
        live = live.log(user(4, "something else", id = "other"))
        assertEquals(1, live.steers.size)
        live = live.log(user(5, "STEERTEST: also tell me the battery level.", id = "m9"))
        assertTrue(live.steers.isEmpty())
        // The conversation has it once, as the logged message; the turn ending changes nothing.
        live = live.log(ev(6, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}""")).copy(running = false)
        val texts = Projector.conversation(live.events, running = false).turns.mapNotNull { it.user?.text }
        assertEquals(1, texts.count { it.startsWith("STEERTEST") })
        assertTrue(live.steers.isEmpty())
    }

    @Test fun `a steer logged before the engine's answer arrived is not left pending`() {
        val live = SessionLive("s", events = running + user(3, "fast", id = "m3"), running = true)
            .withSteer(SteerMessage("l1", "fast"))
            .steerAccepted("l1", "m3")
        assertTrue(live.steers.isEmpty())
    }

    @Test fun `a refused steer goes to the front of the queue, still visible`() {
        val live = SessionLive("s", events = running, running = true, queue = listOf(QueuedMessage("q", "later")))
            .withSteer(SteerMessage("l1", "now please"))
            .steersQueued(listOf("l1"))
        assertTrue(live.steers.isEmpty())
        assertEquals(listOf("now please", "later"), live.queue.map { it.text })
    }

    @Test fun `stop hands undelivered steers back to the composer before the queue`() {
        val live = SessionLive("s", running = true, queue = listOf(QueuedMessage("q", "queued")))
            .withSteer(SteerMessage("l1", "steered").let { it.copy(messageId = "m1") })
            .idle()
        assertTrue(live.steers.isEmpty() && live.queue.isEmpty())
        assertEquals("steered\n\nqueued", live.restoredDraft)
    }

    @Test fun `a steered message continues the same turn under the user's bubble`() {
        val events = running + listOf(
            ev(3, "tool/result", """{"message":{"toolCallId":"c1","content":[{"type":"text","text":"ok"}]}}"""),
            user(4, "STEERTEST: also tell me the battery level."),
            ev(5, "tool/call", """{"callId":"c2","name":"bash","arguments":"{}"}"""),
        )
        var conv = Projector.conversation(events, running = true)
        assertEquals(2, conv.turns.size)
        val (before, after) = conv.turns
        assertEquals(TurnStatus.Steered, before.status)
        assertEquals(ToolState.Done, (before.blocks.single() as Block.Tool).state)
        assertNull(before.endReason)
        assertEquals("STEERTEST: also tell me the battery level.", after.user?.text)
        assertEquals(1, after.number)
        assertEquals(TurnStatus.Running, after.status)
        assertEquals("c2", (after.blocks.single() as Block.Tool).callId)

        // The turn's end (and plugin-guard's outcome) closes the part after the steer.
        conv = Projector.conversation(events + listOf(
            ev(6, "tool/result", """{"message":{"toolCallId":"c2","content":[]}}"""),
            ev(7, "assistant/message", """{"message":{"content":[{"type":"text","text":"电量 80%"}]}}"""),
            ev(8, TURN_OUTCOME, """{"turn":1,"reason":"done"}"""),
            ev(9, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
        ), running = false)
        assertEquals(listOf(TurnStatus.Steered, TurnStatus.Completed), conv.turns.map { it.status })
        assertEquals(EndReason.Done, conv.turns.last().endReason)
        assertEquals("电量 80%", conv.turns.last().reply?.text)
    }

    @Test fun `a turn's own prompt after a compaction is not a steer`() {
        val conv = Projector.conversation(listOf(
            ev(0, "turn/start", """{"turn":2}"""),
            ev(1, "compaction/start", """{"compactionId":"x"}"""),
            user(2, "next task"),
        ), running = true)
        assertEquals("next task", conv.turns.single().user?.text)
    }
}
