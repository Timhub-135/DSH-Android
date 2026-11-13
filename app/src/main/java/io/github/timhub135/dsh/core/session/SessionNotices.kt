package io.github.timhub135.dsh.core.session

import android.content.Context
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.SessionEntity
import io.github.timhub135.dsh.core.platform.NotificationText
import io.github.timhub135.dsh.core.platform.Notifications

/**
 * What SessionStore announces for a conversation (#43): who may notify ([SessionVisibility]), the title
 * (the conversation's), the text (readable, never a payload) and the slot (one per conversation).
 *
 * 免打扰 ([SessionEntity.muted]) silences finished turns only. A card that blocks the run until the user
 * answers (approval, question, permission, takeover, unlock) still notifies: otherwise the task would
 * just sit there until its wait times out, which muting a chatty conversation never asked for.
 */
object SessionNotices {
    /** Posts [last]'s outcome for [sessionId] (the app is in the background); false when it must stay silent. */
    fun turnFinished(context: Context, sessionId: String, row: SessionEntity?, liveTitle: String, last: Turn?): Boolean {
        // No row = a plugin's background session; a subagent speaks through its root conversation.
        if (row == null || !SessionVisibility.userFacing(sessionId, row) || row.muted) return false
        // A failed / stopped turn has no reply: the conversation shows why; there is no "done" to announce.
        val reply = last?.reply ?: return false
        val summary = NotificationText.summary(reply.text)
        val text = when {
            last.endReason?.continuable != true -> summary ?: context.getString(R.string.notif_done_generic)
            summary != null -> context.getString(R.string.notify_not_finished, summary)
            else -> context.getString(R.string.notif_not_finished_generic)
        }
        Notifications.postSession(context, Notifications.CHANNEL_TASKS, sessionId, title(context, row, liveTitle), text)
        return true
    }

    /**
     * A card in [owner] waits for the user: "[headline]：[detail]" in the conversation's slot [id] (also
     * when muted, see above). False for a session that is not the user's.
     */
    fun waiting(context: Context, owner: String, row: SessionEntity?, liveTitle: String, headline: String, detail: String?,
                id: Int = Notifications.ID_SESSION): Boolean {
        if (!SessionVisibility.userFacing(owner, row)) return false
        val text = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.notif_attention, headline, it) } ?: headline
        Notifications.postSession(context, Notifications.CHANNEL_ATTENTION, owner, title(context, row, liveTitle), text, id)
        return true
    }

    /** The conversation's title as the list shows it; the app name before it has one. */
    fun title(context: Context, row: SessionEntity?, liveTitle: String): String =
        liveTitle.ifBlank { row?.title.orEmpty() }.ifBlank { context.getString(R.string.app_name) }
}
