package io.github.timhub135.dsh.core.platform

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Activity starts from a service (the floating ball and its capsule). A plain startActivity from
 * a service is a background activity start: AOSP lets it through for an app with a visible overlay
 * window, but HyperOS / MIUI ("后台弹出界面") and Android 14+ can still drop it silently, so the tap
 * would do nothing. A PendingIntent sent with the sender's background-start privilege
 * ([sendOptions]) is the supported path; a refused send falls back to startActivity.
 */
object ActivityStarts {
    /** Our own request code for these one-shot sends (distinct from notification PendingIntents, whose extras it must not touch). */
    private const val REQUEST = 0x0BA11

    /** Grants the sender's background-activity-start privilege to the PendingIntent (Android 14+ requires the opt-in). */
    fun sendOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34)
        @Suppress("DEPRECATION")
        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null

    fun start(context: Context, intent: Intent) {
        val i = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sent = runCatching {
            PendingIntent.getActivity(context, REQUEST, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                .send(context, 0, null, null, null, null, sendOptions())
        }
        if (sent.isFailure) {
            Log.w("ActivityStarts", "PendingIntent start refused; trying startActivity", sent.exceptionOrNull())
            runCatching { context.startActivity(i) }.onFailure { Log.w("ActivityStarts", "startActivity refused", it) }
        }
    }
}
