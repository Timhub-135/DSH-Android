package io.github.timhub135.dsh.core.capability

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LockState @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /**
     * The screen cannot be operated: the keyguard is showing (secure or swipe-only) or the screen is
     * off (including the grace period before a secure lock engages, where isDeviceLocked is still false).
     */
    val isLocked: Boolean get() = keyguard.isKeyguardLocked || !power.isInteractive

    /** Suspends until the user unlocks, or returns false after [timeoutMs]. Cancellable. */
    suspend fun awaitUnlock(timeoutMs: Long): Boolean {
        if (!isLocked) return true
        if (timeoutMs <= 0) return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (!isLocked && cont.isActive) { runCatching { context.unregisterReceiver(this) }; cont.resume(true) }
                    }
                }
                // Register first, then re-check: an unlock between the caller's check and the
                // registration would otherwise leave the call waiting for the whole timeout.
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                if (!isLocked && cont.isActive) { runCatching { context.unregisterReceiver(receiver) }; cont.resume(true) }
            }
        } ?: false
    }
}
