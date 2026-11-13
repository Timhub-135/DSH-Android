package io.github.timhub135.dsh.service

import io.github.timhub135.dsh.core.session.RowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #39: a ball / capsule / ongoing-notification tap during a background run opens that run, never a new conversation. */
class TapTargetTest {
    @Test fun `ball tap while a task runs opens the running conversation`() {
        val st = mapOf("old" to RowStatus.Idle, "run" to RowStatus.Running)
        assertEquals(TapTarget.Conversation("run"), TapTargets.ball(st, focused = "run", panelSession = "other"))
        // The capsule may not have caught up yet: the running one is found anyway.
        assertEquals(TapTarget.Conversation("run"), TapTargets.ball(st, focused = null, panelSession = null))
    }

    @Test fun `waiting for the user beats running and a subagent counts for its root`() {
        val st = mapOf("a" to RowStatus.Running, "b" to RowStatus.Idle, "b/sub" to RowStatus.Approval)
        assertEquals(TapTarget.Conversation("b"), TapTargets.ball(st, focused = "a", panelSession = null))
    }

    @Test fun `a run started in the quick panel reopens there`() {
        val st = mapOf("p" to RowStatus.Running)
        assertEquals(TapTarget.Panel("p"), TapTargets.ball(st, focused = "p", panelSession = "p"))
        assertEquals(TapTarget.Panel("p"), TapTargets.capsule("p", panelSession = "p"))
    }

    @Test fun `nothing running keeps the plain summon`() {
        assertEquals(TapTarget.Panel(), TapTargets.ball(emptyMap(), null, "p"))
        assertEquals(TapTarget.Panel(), TapTargets.ball(mapOf("a" to RowStatus.Idle), "a", null))
    }

    @Test fun `capsule opens the conversation it speaks for, even just ended`() {
        assertEquals(TapTarget.Conversation("done"), TapTargets.capsule("done", panelSession = "p"))
    }

    @Test fun `ongoing notification resolves the running conversation at tap time`() {
        assertEquals("run", TapTargets.running(mapOf("a" to RowStatus.Idle, "run" to RowStatus.Running)))
        assertEquals("q", TapTargets.running(mapOf("run" to RowStatus.Running, "q" to RowStatus.Question)))
        assertNull(TapTargets.running(mapOf("a" to RowStatus.Idle)))
    }
}
