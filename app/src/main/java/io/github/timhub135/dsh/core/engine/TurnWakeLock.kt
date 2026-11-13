package io.github.timhub135.dsh.core.engine

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the CPU awake while a turn is actively running (review §6/G3). A foreground service does
 * not prevent CPU suspend, so with the screen off a model stream or tool call could stall.
 *
 * Held from `:main` (a wake lock is per-app/uid, so it covers the `:engine` process too) while any
 * session is Running — not while it waits for an approval, an answer or an unlock. Each acquire
 * carries a [TIMEOUT_MS] timeout and is renewed on session activity (at most every [RENEW_MS]), so a
 * wedged turn that produces no events lets the lock lapse on its own; it is released as soon as no
 * session is running (finish, error, stop, engine loss).
 */
@Singleton
class TurnWakeLock @Inject constructor(@ApplicationContext context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:agent-turn")
        .apply { setReferenceCounted(false) }
    private var renewedAt = 0L

    /** Acquire, or extend the timeout of, the lock (cheap to call on every session event). */
    @Synchronized fun hold() {
        val now = SystemClock.elapsedRealtime()
        if (lock.isHeld && now - renewedAt < RENEW_MS) return
        lock.acquire(TIMEOUT_MS)
        renewedAt = now
    }

    @Synchronized fun release() {
        if (lock.isHeld) runCatching { lock.release() }
    }

    val isHeld: Boolean get() = lock.isHeld

    private companion object {
        const val TIMEOUT_MS = 10 * 60_000L
        const val RENEW_MS = 30_000L
    }
}
