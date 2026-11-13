package io.github.timhub135.dsh.core.capability

import io.github.timhub135.dsh.core.capability.LoopGuard.Signal
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ActionGuardTest {
    private fun tool(name: String, risk: Risk = Risk.write) = object : Tool(name, ToolGroup.Screen, risk, true, "", {}) {
        override suspend fun invoke(args: JsonObject, call: CallContext) = ToolResult.text("")
    }
    private val scroll = tool("screen_scroll")
    private val down = JsonObject(mapOf("direction" to JsonPrimitive("down")))

    /** One guarded call through [ActionGuard] that leaves the screen with fingerprint [screen]; returns its refusal, if any. */
    private fun ActionGuard.call(t: Tool, args: JsonObject, screen: Int, outcome: ActionOutcome = ActionOutcome.Effective): ToolResult? =
        before("s1", t, args) ?: run { after("s1", t, args, ToolResult.text("done").withOutcome(outcome, screen)); null }

    @Test fun signatureIncludesTheScreen() {
        assertNotEquals(ActionGuard.signature(scroll, down, 1), ActionGuard.signature(scroll, down, 2))
        assertEquals(ActionGuard.signature(scroll, down, 1), ActionGuard.signature(scroll, down, 1))
        assertNull(ActionGuard.signature(tool("screen_read", Risk.read), JsonObject(emptyMap()), 1))
    }

    @Test fun scrollingAFeedIsNotARepeat() {
        val g = ActionGuard()
        repeat(30) { i -> assertNull("scroll $i", g.call(scroll, down, screen = i)) }
    }

    @Test fun theSameActionOnTheSameScreenIsStoppedAtTheTwelfth() {
        val g = ActionGuard()
        g.call(tool("screen_read", Risk.read), JsonObject(emptyMap()), screen = 7, outcome = ActionOutcome.ReadChanged)
        repeat(11) { assertNull(g.call(scroll, down, screen = 7, outcome = ActionOutcome.Effective)) }
        assertEquals("loop_guard", g.call(scroll, down, screen = 7)?.errorCode)
    }

    @Test fun afterAStopReadsAndHandToUserStillRun() {
        val g = ActionGuard()
        repeat(12) { g.call(tool("screen_tap"), JsonObject(mapOf("node" to JsonPrimitive("e$it"))), screen = it, outcome = ActionOutcome.NoEffect) }
        assertNotNull(g.before("s1", tool("screen_tap"), JsonObject(emptyMap())))
        assertNull(g.before("s1", tool("screen_read", Risk.read), JsonObject(emptyMap())))
        assertNull(g.before("s1", tool("hand_to_user", Risk.read), JsonObject(emptyMap())))
        g.reset("s1")
        assertNull(g.before("s1", tool("screen_tap"), JsonObject(emptyMap())))
    }

    @Test fun afterARereadStopReadsAreRefusedToo() {
        val g = ActionGuard()
        val read = tool("screen_read", Risk.read)
        repeat(12) { g.call(read, JsonObject(emptyMap()), screen = 1, outcome = ActionOutcome.ReadUnchanged) }
        assertNotNull(g.before("s1", read, JsonObject(emptyMap())))
        assertNull(g.before("s1", tool("hand_to_user", Risk.read), JsonObject(emptyMap())))
    }

    @Test fun repeatRemindsAt3And6AndRefusesThe12th() {
        val g = LoopGuard()
        val hits = (1..11).map { assertNull(g.check("tap e1")); g.record("tap e1", true, ActionOutcome.Effective) }
        assertEquals(listOf(3, 6), hits.mapNotNull { it?.second })
        assertTrue(hits.filterNotNull().all { it.first == Signal.Repeat })
        assertEquals(Signal.Repeat, g.check("tap e1"))
        assertEquals("stopped stays until reset", Signal.Repeat, g.check("something else"))
        g.reset()
        assertNull(g.check("tap e1"))
    }

    @Test fun repeatCountsOnlyTheLast16() {
        val g = LoopGuard()
        repeat(40) { i ->
            val sig = if (i % 4 == 0) "tap e1" else "other $i"
            assertNull(g.check(sig))
            g.record(sig, true, ActionOutcome.Effective)
        }
    }

    @Test fun noEffectLadderAndStop() {
        val g = LoopGuard()
        val hits = (1..12).mapNotNull { g.record("a$it", true, ActionOutcome.NoEffect)?.second }
        assertEquals(listOf(3, 6, 9, 12), hits)
        assertEquals(Signal.NoEffect, g.stopped)
    }

    @Test fun anEffectiveActionResetsNoEffect() {
        val g = LoopGuard()
        repeat(2) { g.record("a$it", true, ActionOutcome.NoEffect) }
        g.record("b", true, ActionOutcome.Effective)
        assertNull(g.record("c", true, ActionOutcome.NoEffect))
        assertNull(g.record("d", true, ActionOutcome.NoEffect))
        assertEquals(Signal.NoEffect to 3, g.record("e", true, ActionOutcome.NoEffect))
    }

    @Test fun rereadsAndFailures() {
        val g = LoopGuard()
        assertEquals(listOf(5, 8, 12), (1..12).mapNotNull { g.record(null, true, ActionOutcome.ReadUnchanged)?.second })
        val f = LoopGuard()
        assertEquals(listOf(2, 4, 8), (1..8).mapNotNull { f.record("x$it", false, null)?.second })
        assertEquals(Signal.Failure, f.stopped)
        val ok = LoopGuard()
        ok.record("a", false, null); ok.record("b", true, null)
        assertNull(ok.record("c", false, null))
    }

    @Test fun pacingFirstFreeThenSpaced() {
        val p = OutwardPacing(Random(1))
        assertEquals(0, p.waitBefore("wx", 0))
        p.noteDone("wx", 0)
        val w = p.waitBefore("wx", 1_000)
        assertTrue("$w", w in 7_000..19_000)
        assertEquals("other apps are separate", 0, p.waitBefore("qq", 1_000))
        assertEquals("time already spent counts", 0, p.waitBefore("wx", 21_000))
    }

    @Test fun pacingGrowsButIsCapped() {
        val p = OutwardPacing(Random(7))
        var now = 0L
        repeat(40) { p.noteDone("app", now); now += 31_000 }
        // 39 earlier ones in the window: 8–20 s grew by 0.5/1 s each, capped at 30 s.
        val gap = p.waitBefore("app", now - 31_000)
        assertTrue("$gap", gap in 27_500..OutwardPacing.MAX_GAP_MS)
    }
}
