package io.github.timhub135.dsh.core.capability.tools

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.capability.strs
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.core.platform.ActivityStarts
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.service.DshNotificationListener
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ToolSet {
    private fun listener() = DshNotificationListener.instance ?: throw PermissionMissingException("Notification access")

    /** Apps the user blocked from being read (default: none). */
    private suspend fun blocked(): Set<String> = settings.current().notificationBlocked


    /** A notification from an allowed app by key, or a model-readable error. */
    private suspend fun find(key: String): StatusBarNotification {
        val sbn = listener().activeNotifications.firstOrNull { it.key == key }
            ?: throw InvalidArgsException("No current notification with key $key (it may have been dismissed); call notification_list again")
        if (sbn.packageName in blocked()) throw InvalidArgsException("The user blocked reading ${sbn.packageName} notifications (DSH settings → Notifications)")
        return sbn
    }

    /** Title + text, including the latest chat messages of MessagingStyle notifications (WeChat, Telegram, WhatsApp…). */
    private fun describe(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE) ?: e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: ""
        val messages = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages }.getOrNull()
            ?.takeLast(5)?.joinToString(" | ") { m -> "${m.person?.name ?: ""}${if (m.person?.name != null) ": " else ""}${m.text}" }
        val text = messages?.takeIf { it.isNotBlank() }
            ?: (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        val actions = n.actions.orEmpty().mapNotNull { a ->
            a.title?.toString()?.let { t -> if (a.remoteInputs?.isNotEmpty() == true) "$t(reply)" else t }
        }
        return "[${sbn.packageName}] $title: ${text.take(400)}" +
            (if (actions.isNotEmpty()) "  actions=${actions.joinToString("/")}" else "") +
            (if (!sbn.isClearable) "  (ongoing)" else "")
    }

    /** PendingIntents of other apps that start activities need the sender's background-start privilege on Android 14+. */
    private fun send(pi: PendingIntent, fill: Intent? = null) =
        pi.send(context, 0, fill, null, null, null, ActivityStarts.sendOptions())

    override val tools = listOf(
        object : Tool("notification_list", ToolGroup.Notifications, Risk.sensitive, false,
            "List current notifications (apps the user blocked in settings are skipped), with their latest messages, action buttons (\"(reply)\" = can reply) and key.", {
                string("package", "Only this app"); int("limit", "Default 30")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val blocked = blocked()
                val pkg = args.str("package")
                if (pkg != null && pkg in blocked) return ToolResult.text("The user blocked reading $pkg notifications (DSH settings → Notifications); ask them to unblock it if needed.")
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                val list = listener().activeNotifications.filter { it.packageName !in blocked && (pkg == null || it.packageName == pkg) }
                    .sortedByDescending { it.postTime }.take((args.int("limit") ?: 30).coerceIn(1, 100))
                return ToolResult.text(list.joinToString("\n") { "${fmt.format(Date(it.postTime))} ${describe(it)}  key=${it.key}" }.ifEmpty { "No notifications." })
            }
        },
        object : Tool("notification_dismiss", ToolGroup.Notifications, Risk.write, false, "Dismiss notifications by key (or all dismissable ones).", {
            strings("keys", "Keys from notification_list; empty = all")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val keys = args.strs("keys").toSet()
                val blocked = blocked()
                val l = listener()
                val targets = l.activeNotifications.filter { it.packageName !in blocked && it.isClearable && (keys.isEmpty() || it.key in keys) }
                l.cancelNotifications(targets.map { it.key }.toTypedArray())
                val skipped = if (keys.isEmpty()) 0 else keys.size - targets.size
                return ToolResult.text("Dismissed ${targets.size}" + if (skipped > 0) " ($skipped not found, ongoing, or blocked)" else "")
            }
        },
        object : Tool("notification_open", ToolGroup.Notifications, Risk.write, true,
            "Open a notification (like tapping it), or press one of its action buttons by title (e.g. \"Mark as read\"). Use notification_reply to reply.", {
                string("key", "Key from notification_list", required = true)
                string("action", "Action button title to press instead of opening")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val sbn = find(args.reqStr("key"))
                val n = sbn.notification
                val actionTitle = args.str("action")
                val pi = if (actionTitle != null) {
                    n.actions.orEmpty().firstOrNull { it.title?.toString().equals(actionTitle, true) }?.actionIntent
                        ?: n.actions.orEmpty().firstOrNull { it.title?.toString()?.contains(actionTitle, true) == true }?.actionIntent
                        ?: throw InvalidArgsException("No action \"$actionTitle\"; available: ${n.actions.orEmpty().joinToString { it.title.toString() }.ifEmpty { "none" }}")
                } else n.contentIntent ?: return ToolResult.error("unsupported", "This notification does not open anything when tapped.")
                try { send(pi) } catch (e: PendingIntent.CanceledException) {
                    return ToolResult.error("failed", "The app withdrew this notification's action; open the app instead.")
                }
                if (actionTitle == null && (n.flags and Notification.FLAG_AUTO_CANCEL) != 0) runCatching { listener().cancelNotification(sbn.key) }
                return ToolResult.text(if (actionTitle != null) "Pressed \"$actionTitle\" on the ${sbn.packageName} notification." else
                    "Opened the ${sbn.packageName} notification. Use screen_read to see the app.")
            }
        },
        object : Tool("notification_reply", ToolGroup.Notifications, Risk.dangerous, false,
            "Reply to a chat notification through its inline Reply action (WeChat, QQ, Telegram, WhatsApp, SMS…) without opening the app. " +
                "Confirm the text with the user first. Only works when notification_list shows an action marked (reply).", {
                string("key", "Key from notification_list", required = true)
                string("text", "Reply text", required = true)
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val sbn = find(args.reqStr("key"))
                val text = args.reqStr("text")
                val action = sbn.notification.actions.orEmpty().firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
                    ?: return ToolResult.error("unsupported", "This notification has no inline reply. Open it (notification_open) and type with screen_type instead.")
                val inputs = action.remoteInputs!!.filter { it.allowFreeFormInput }
                val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                RemoteInput.addResultsToIntent(inputs.toTypedArray(), fill, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
                try { send(action.actionIntent, fill) } catch (e: PendingIntent.CanceledException) {
                    return ToolResult.error("failed", "The app withdrew the reply action (the conversation may have changed); call notification_list again.")
                }
                return ToolResult.text("Replied to ${sbn.packageName} (${sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "conversation"}): \"${text.take(200)}\". " +
                    "The app normally updates or clears the notification; check notification_list if delivery matters.")
            }
        },
    )
}
