package io.github.timhub135.dsh.core.capability

import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Whether an alarm handed to the clock app shows up as the system's next alarm clock
 * (AlarmManager.getNextAlarmClock, which clock apps set for every enabled alarm). Pure.
 */
object AlarmCheck {
    enum class Verdict(val id: String) {
        /** The next alarm is the one just set. */
        Verified("verified"),
        /** Another alarm rings earlier, so the new one cannot be seen this way. */
        EarlierAlarmFirst("earlier_alarm_first"),
        /** The next alarm is later than the new one, or there is none: it was most likely not saved. */
        NotSeen("not_seen"),
        /** The system reports no alarm before or after (some clock apps do not report theirs). */
        Unknown("unknown"),
    }

    const val TOLERANCE_MS = 60_000L

    /** When an alarm at [hour]:[minute] next rings after [now]; [days] are ISO weekdays (1 = Mon … 7 = Sun), empty = once. */
    fun nextRing(now: ZonedDateTime, hour: Int, minute: Int, days: Collection<Int> = emptyList()): ZonedDateTime {
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        for (i in 0..7) {
            val t = today.plusDays(i.toLong())
            if (!t.isAfter(now)) continue
            if (days.isEmpty() || t.dayOfWeek.value in days) return t
        }
        return today.plusDays(1)
    }

    fun verdict(before: Long?, after: Long?, expected: Long): Verdict = when {
        after == null -> if (before == null) Verdict.Unknown else Verdict.NotSeen
        abs(after - expected) <= TOLERANCE_MS -> Verdict.Verified
        after < expected -> Verdict.EarlierAlarmFirst
        else -> Verdict.NotSeen
    }
}
