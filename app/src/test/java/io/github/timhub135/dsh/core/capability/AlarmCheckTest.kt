package io.github.timhub135.dsh.core.capability

import io.github.timhub135.dsh.core.capability.AlarmCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmCheckTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // Thursday 2026-10-29 08:30.
    private val now = ZonedDateTime.of(2026, 10, 29, 8, 30, 0, 0, zone)
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)

    @Test fun oneTimeAlarmRingsTodayOrTomorrow() {
        assertEquals(at(29, 9, 0), AlarmCheck.nextRing(now, 9, 0))
        assertEquals(at(30, 7, 0), AlarmCheck.nextRing(now, 7, 0))
        // The current minute has passed already.
        assertEquals(at(30, 8, 30), AlarmCheck.nextRing(now, 8, 30))
    }

    @Test fun repeatingAlarmRingsOnTheNextListedDay() {
        // Mon(1) and Fri(5): Friday the 30th.
        assertEquals(at(30, 7, 0), AlarmCheck.nextRing(now, 7, 0, listOf(1, 5)))
        // Thursday later today counts.
        assertEquals(at(29, 20, 0), AlarmCheck.nextRing(now, 20, 0, listOf(4)))
        // Only Thursdays, but today's time has passed: next week.
        assertEquals(ZonedDateTime.of(2026, 11, 5, 7, 0, 0, 0, zone), AlarmCheck.nextRing(now, 7, 0, listOf(4)))
    }

    @Test fun verdicts() {
        val expected = at(30, 7, 0).toInstant().toEpochMilli()
        val earlier = at(29, 22, 0).toInstant().toEpochMilli()
        val later = at(31, 7, 0).toInstant().toEpochMilli()
        assertEquals(Verdict.Verified, AlarmCheck.verdict(null, expected, expected))
        assertEquals(Verdict.Verified, AlarmCheck.verdict(later, expected + 30_000, expected))
        assertEquals(Verdict.EarlierAlarmFirst, AlarmCheck.verdict(earlier, earlier, expected))
        assertEquals(Verdict.NotSeen, AlarmCheck.verdict(later, later, expected))
        assertEquals(Verdict.NotSeen, AlarmCheck.verdict(null, later, expected))
        assertEquals(Verdict.NotSeen, AlarmCheck.verdict(later, null, expected))
        assertEquals(Verdict.Unknown, AlarmCheck.verdict(null, null, expected))
        assertEquals(listOf("verified", "earlier_alarm_first", "not_seen", "unknown"), Verdict.entries.map { it.id })
    }
}
