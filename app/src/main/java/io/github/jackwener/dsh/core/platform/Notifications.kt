package io.github.jackwener.dsh.core.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object Notifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TASKS = "tasks"
    const val CHANNEL_ATTENTION = "attention"
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_UPDATES = "updates"
    const val ID_ENGINE = 1
    const val ID_MONITOR = 2
    const val ID_BALL = 3
    const val ID_UPDATE = 4
    /** The group summary over several conversations' notifications. */
    const val ID_SESSIONS_SUMMARY = 5

    /*
     * Conversation notifications (#43), the way chat apps do it: one per conversation (tag = session id),
     * replaced rather than stacked, grouped under a summary once there are several, and a tap opens
     * exactly that conversation. [ID_SESSION] is the conversation's slot (a turn finished, a card waits:
     * the newest wins); takeover and unlock have their own, because their waits cancel them.
     */
    const val GROUP_SESSIONS = "io.github.jackwener.dsh.SESSIONS"
    const val ID_SESSION = 10
    const val ID_SESSION_TAKEOVER = 11
    const val ID_SESSION_UNLOCK = 12

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        ))
    }

    /**
     * Opens [sessionId]'s conversation (MainActivity → home showing it, home as the root). The data URI
     * keeps each session's PendingIntent its own: extras do not count, and request codes can collide.
     */
    fun openSession(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getActivity(context, sessionId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .setData(Uri.fromParts(SESSION_SCHEME, sessionId, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private const val SESSION_SCHEME = "dsh-session"

    /**
     * The ongoing notifications (engine running, floating ball): tapping one brings the app to the front on the
     * conversation that needs the user or is running (MainActivity resolves it at tap time, so the
     * PendingIntent can be built once, even in the `:engine` process); nothing running = the app as it was.
     * A notification tap is a user interaction, so this start is allowed from the background (HyperOS included).
     */
    fun openRunning(context: Context): PendingIntent =
        PendingIntent.getActivity(context, "running".hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RUNNING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 设置 at [section] (a SettingsSection name), e.g. 关于 for an app update. */
    fun openSection(context: Context, section: String): PendingIntent =
        PendingIntent.getActivity(context, "section:$section".hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [open] overrides the tap target ([sessionId]'s conversation otherwise). */
    fun post(context: Context, channel: String, id: Int, title: String, text: String, sessionId: String?, open: PendingIntent? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        (open ?: sessionId?.let { openSession(context, it) })?.let(b::setContentIntent)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    /**
     * [sessionId]'s notification in slot [id]: titled with the conversation, replacing its previous one,
     * in the conversations group. Callers decide whether the session may notify at all (SessionVisibility).
     */
    fun postSession(context: Context, channel: String, sessionId: String, title: String, text: String, id: Int = ID_SESSION) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (channel == CHANNEL_ATTENTION) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_SESSIONS)
            .setContentIntent(openSession(context, sessionId))
            .setAutoCancel(true)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(sessionId, id, b.build())
        updateSessionsSummary(context)
    }

    /** Removes [sessionId]'s notification in slot [id] (the user opened the conversation, or the wait is over). */
    fun cancelSession(context: Context, sessionId: String, id: Int = ID_SESSION) {
        NotificationManagerCompat.from(context).cancel(sessionId, id)
        updateSessionsSummary(context)
    }

    /** The group summary exists while two or more conversations have a notification (one alone needs none). */
    private fun updateSessionsSummary(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val children = runCatching { nm.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == GROUP_SESSIONS && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
        if (children.size < 2) { nm.cancel(ID_SESSIONS_SUMMARY); return }
        val text = context.getString(R.string.notif_sessions_summary, children.map { it.tag }.distinct().size)
        val inbox = NotificationCompat.InboxStyle().setSummaryText(text)
        children.mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) }.take(5).forEach(inbox::addLine)
        val summary = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(inbox)
            .setGroup(GROUP_SESSIONS)
            .setGroupSummary(true)
            // The conversations' own notifications alert; the summary never does.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(PendingIntent.getActivity(context, "sessions".hashCode(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ID_SESSIONS_SUMMARY, summary) }
    }
}

/** Notification text for a finished turn (#43): what a person can read at a glance, never a data payload. */
object NotificationText {
    const val MAX = 120
    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val linePrefix = Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)")
    private val emphasis = Regex("(\\*\\*|__|\\*|`|~~)")

    /**
     * One line from an assistant reply, at most [MAX] characters; null when there is nothing readable
     * (blank, JSON such as a plugin's `{"items":[…]}`, only code), so the caller says "任务已完成" instead.
     */
    fun summary(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || looksLikeData(t)) return null
        val plain = t.replace(fence, " ").replace(link, "$1").replace(linePrefix, "").replace(emphasis, "")
            .replace(Regex("\\s+"), " ").trim()
        if (plain.isEmpty() || looksLikeData(plain)) return null
        return if (plain.length <= MAX) plain else plain.take(MAX - 1).trimEnd() + "…"
    }

    /** A JSON object / array (also cut off, or inside a code fence) rather than prose. */
    fun looksLikeData(text: String): Boolean {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.startsWith("{\"") || t.startsWith("[{")) return true
        if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) return false
        return runCatching { Json.parseToJsonElement(t) }.map { it is JsonObject || it is JsonArray }.getOrDefault(false)
    }
}
