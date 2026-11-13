package io.github.timhub135.dsh.core.session

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a finished turn's end is shown (#37): plugin-guard's turn outcome, turn/end reasons, continuations. */
class TurnEndTest {
    private fun ev(seq: Long, type: String, json: String, time: Long = seq * 1000) =
        LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    private fun todos(seq: Long, vararg items: Pair<String, String>) = ev(seq, "todo/write",
        """{"todos":[${items.joinToString(",") { (c, s) -> """{"content":"$c","status":"$s"}""" }}]}""")

    private fun reply(seq: Long, text: String) = ev(seq, "assistant/message", """{"message":{"content":[{"type":"text","text":"$text"}]}}""")
    private fun outcome(seq: Long, json: String) = ev(seq, TURN_OUTCOME, json)
    private fun end(seq: Long, kind: String) = ev(seq, "turn/end", """{"turn":1,"reason":{"kind":"$kind"}}""")
    private val start = ev(0, "turn/start", """{"turn":1}""")
    private val user = ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"取关不活跃的账号"}]}""")

    private fun turn(vararg events: LoggedEvent, running: Boolean = false) = Projector.conversation(listOf(start, user) + events, running).turns.last()

    @Test fun `done and needs_user read as a normal reply`() {
        val done = turn(todos(2, "a" to "completed"), reply(3, "全部完成"), outcome(4, """{"turn":1,"reason":"done","todos":{"pending":0,"inProgress":0,"completed":1},"nudges":0}"""), end(5, "completed"))
        assertEquals(EndReason.Done, done.endReason)
        assertFalse(done.endReason!!.continuable)
        val ask = turn(todos(2, "a" to "in_progress"), reply(3, "确定吗？"), outcome(4, """{"turn":1,"reason":"needs_user","todos":{"pending":0,"inProgress":1,"completed":0},"nudges":0}"""), end(5, "completed"))
        assertEquals(EndReason.NeedsUser, ask.endReason)
        assertFalse(ask.endReason!!.continuable)
    }

    @Test fun `pending work, the continuation cap and a budget get an end card with progress`() {
        val pending = turn(todos(2, "a" to "completed", "b" to "in_progress", "c" to "pending"), reply(3, "下一轮会自动继续"),
            outcome(4, """{"turn":1,"reason":"pending_work","todos":{"pending":1,"inProgress":1,"completed":1},"nudges":1}"""), end(5, "completed"))
        assertEquals(EndReason.PendingWork, pending.endReason)
        assertTrue(pending.endReason!!.continuable)
        assertEquals(TodoCounts(1, 1, 1), pending.todoCounts)
        assertEquals(1, pending.outcome?.nudges)
        val limit = turn(reply(3, "继续下一个"), outcome(4, """{"turn":1,"reason":"continue_limit","todos":{"pending":5,"inProgress":1,"completed":8},"nudges":8}"""), end(5, "completed"))
        assertEquals(EndReason.ContinueLimit, limit.endReason)
        assertEquals(TodoCounts(5, 1, 8), limit.todoCounts) // from the record when the turn wrote no list itself
        val budget = turn(outcome(4, """{"turn":1,"reason":"budget","kind":"sessionTokenLimit","used":50000,"limit":1000}"""), end(5, "blocked"))
        assertEquals(TurnStatus.Stopped, budget.status)
        assertEquals(EndReason.Budget, budget.endReason)
        assertEquals("sessionTokenLimit", budget.outcome?.budgetKind)
        assertEquals(EndReason.MaxTokens, turn(reply(3, "cut"), end(4, "max-tokens")).endReason)
    }

    @Test fun `errors, stops and running turns`() {
        assertEquals(EndReason.Error, turn(ev(3, "turn/end", """{"turn":1,"reason":{"kind":"error","error":{"message":"x","code":"TRANSPORT"}}}""")).endReason)
        assertEquals(EndReason.Stopped, turn(end(3, "aborted")).endReason)
        assertEquals(EndReason.Stopped, turn(end(3, "blocked")).endReason)
        assertNull(turn(reply(3, "working"), running = true).endReason)
    }

    @Test fun `an engine without the record still flags open todos`() {
        val t = turn(todos(2, "a" to "completed", "b" to "pending"), reply(3, "未停止，仍在处理"), end(4, "completed"))
        assertEquals(EndReason.PendingWork, t.endReason)
        assertEquals(EndReason.Done, turn(reply(3, "好的"), end(4, "completed")).endReason)
    }

    @Test fun `an outcome for another turn number is ignored`() {
        val t = turn(reply(3, "ok"), outcome(4, """{"turn":7,"reason":"pending_work","nudges":1}"""), end(5, "completed"))
        assertEquals(EndReason.Done, t.endReason)
    }

    @Test fun `the engine's continuation nudge is a notice inside the running turn, not a user message`() {
        val t = turn(todos(2, "a" to "in_progress"), reply(3, "下一轮会自动继续"),
            ev(4, "user/message", """{"source":{"kind":"$AUTO_CONTINUE","form":"notice","summary":"continue 1/8"},"content":[{"type":"text","text":"Automatic continuation 1/8"}]}"""),
            running = true)
        assertEquals(TurnStatus.Running, t.status)
        assertEquals("取关不活跃的账号", t.user?.text)
        assertEquals(AUTO_CONTINUE, (t.blocks.last() as Block.Notice).kind)
    }

    @Test fun `继续 on the end card folds the continuation into the same task and keeps its progress`() {
        val first = listOf(start, user, todos(2, "a" to "completed", "b" to "pending"), reply(3, "下一轮会自动继续"),
            outcome(4, """{"turn":1,"reason":"pending_work","todos":{"pending":1,"inProgress":0,"completed":1},"nudges":1}"""), end(5, "completed"))
        val cont = listOf(
            ev(-1, "app/notice", """{"kind":"$USER_CONTINUE","text":"已继续任务"}""", time = 6000),
            ev(6, "turn/start", """{"turn":2}"""),
            ev(7, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"${APP_PROMPT_PREFIX}The user tapped Continue"}]}"""),
        )
        val running = Projector.conversation(first + cont, running = true)
        val t = running.turns.single()
        assertEquals(TurnStatus.Running, t.status)
        assertNull(t.endReason)
        assertTrue(t.blocks.any { it is Block.Notice && it.kind == USER_CONTINUE && it.resumed })
        val done = Projector.conversation(first + cont + listOf(todos(8, "a" to "completed", "b" to "completed"), reply(9, "全部完成"),
            ev(10, TURN_OUTCOME, """{"turn":2,"reason":"done","nudges":0}"""), ev(11, "turn/end", """{"turn":2,"reason":{"kind":"completed"}}""")), running = false)
        assertEquals(EndReason.Done, done.turns.single().endReason)
        assertEquals(TodoCounts(0, 0, 2), done.turns.single().todoCounts)
    }
}
