package io.github.timhub135.dsh.core.engine

import android.os.SystemClock

/**
 * Crash-loop protection for the `:engine` process (docs/ARCHITECTURE.md): exponential backoff
 * (1 s, 2 s, 4 s) between automatic restarts and a circuit breaker that opens after [MAX_FAILURES]
 * unexpected exits or start failures within [WINDOW_MS] with no stable run in between.
 *
 * While the breaker is open nothing restarts the engine automatically and unattended starts
 * (automations, external tasks) fail fast; an explicit user action (sending, 继续, Doctor's
 * restart) is a half-open trial — success after [STABLE_MS] of uptime closes the breaker again.
 */
class RestartPolicy(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val failures = ArrayDeque<Long>()

    @Volatile var open: Boolean = false; private set
    val recentFailures: Int @Synchronized get() = failures.size

    /** Records a crash; returns the delay before an automatic restart, or null when the breaker opened. */
    @Synchronized fun recordFailure(): Long? {
        val now = clock()
        failures.addLast(now)
        while (failures.isNotEmpty() && failures.first() < now - WINDOW_MS) failures.removeFirst()
        if (failures.size >= MAX_FAILURES) { open = true; return null }
        return BASE_DELAY_MS shl (failures.size - 1)
    }

    /** The engine stayed up for [STABLE_MS] (or a user restarted it from Doctor): forget past crashes. */
    @Synchronized fun recordHealthy() { failures.clear(); open = false }

    companion object {
        const val MAX_FAILURES = 3
        const val BASE_DELAY_MS = 1_000L
        const val WINDOW_MS = 10 * 60_000L
        const val STABLE_MS = 60_000L
    }
}
