package io.github.timhub135.dsh.core.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.feature.deferred.DeferredRunActivity

/**
 * Fallback when Android refuses to start the engine's foreground service from the background
 * (ForegroundServiceStartNotAllowedException, Android 12+; typically battery optimization was
 * re-enabled so the trigger is not exempt): a high-priority notification 点此执行：<rule>. Tapping it
 * is a user interaction, which is exempt, so [DeferredRunActivity] can start the engine and run it.
 */
object DeferredRuns {
    fun post(context: Context, sessionId: String, text: String, ruleId: Long?, label: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, DeferredRunActivity::class.java)
            .putExtra(DeferredRunActivity.EXTRA_SESSION, sessionId)
            .putExtra(DeferredRunActivity.EXTRA_TEXT, text)
            .putExtra(DeferredRunActivity.EXTRA_RULE, ruleId ?: -1L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, sessionId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(context.getString(R.string.deferred_title, label))
            .setContentText(context.getString(R.string.deferred_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.deferred_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(sessionId.hashCode() + 3, n)
    }
}
