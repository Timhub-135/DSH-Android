package io.github.timhub135.dsh.core.trigger

import io.github.timhub135.dsh.core.model.Condition
import io.github.timhub135.dsh.core.model.RuleAction
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.Step
import io.github.timhub135.dsh.core.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuleValidationTest {
    private val tools = setOf("sound_mode", "media_control")
    private fun steps(vararg t: String) = RuleAction.Steps(t.map { Step(it) })

    @Test fun normalizesLenientTimesAndAddsStepTools() {
        val spec = validateRule(RuleSpec("m", Trigger.Time(at = "7:30", days = listOf(1, 5)),
            listOf(Condition.TimeRange("9:00", "18:05")), steps("sound_mode")), tools)
        assertEquals("07:30", (spec.trigger as Trigger.Time).at)
        assertEquals(Condition.TimeRange("09:00", "18:05"), spec.conditions[0])
        assertEquals(listOf("sound_mode"), spec.allowedTools)
    }

    @Test fun rejectsBadSpecsWithEveryProblem() {
        try {
            validateRule(RuleSpec("", Trigger.Time(at = "24:00", days = listOf(0, 8)), listOf(Condition.TimeRange("7点", "9:00")), steps("nope")), tools)
            fail("expected RuleSpecException")
        } catch (e: RuleSpecException) {
            val all = e.problems.joinToString("\n")
            listOf("name", "trigger.at", "days", "time_range.from", "nope").forEach { assertTrue("missing '$it' in $all", it in all) }
        }
    }

    @Test fun rejectsScreenEventAndShortIntervals() {
        listOf(
            RuleSpec("a", Trigger.Screen("unlocked"), action = RuleAction.Agent("x")),
            RuleSpec("b", Trigger.Time(everyMinutes = 5), action = RuleAction.Agent("x")),
            RuleSpec("c", Trigger.Time(), action = RuleAction.Agent("x")),
        ).forEach { s -> try { validateRule(s, tools); fail("accepted ${s.name}") } catch (_: RuleSpecException) {} }
    }

    @Test fun parserNeverThrows() {
        assertNull(RuleTime.parse("7点"))
        assertNull(RuleTime.parse("24:00"))
        assertEquals("23:59", RuleTime.parse("23:59")?.let(RuleTime::format))
        assertEquals("08:05", RuleTime.parse("8：05")?.let(RuleTime::format))
    }

    @Test fun adbShellIsNeverADirectStep() {
        val withAdb = tools + "adb_shell"
        try {
            validateRule(RuleSpec("x", Trigger.Time(at = "07:00"), emptyList(), steps("adb_shell")), withAdb)
            fail("accepted adb_shell as a step")
        } catch (e: RuleSpecException) {
            assertTrue(e.problems.toString(), e.problems.any { "adb_shell cannot be a direct step" in it })
        }
        // As a tool an agent action may use, it is fine (the engine checks each command).
        val agent = validateRule(RuleSpec("y", Trigger.Time(at = "07:00"), emptyList(), RuleAction.Agent("check battery"), listOf("adb_shell")), withAdb)
        assertEquals(listOf("adb_shell"), agent.allowedTools)
    }
}
