package io.github.jackwener.dsh.core.session

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.platform.NotificationText
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #43: which sessions notify, one notification per conversation, readable text, the tap target. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class SessionNoticesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        Notifications.createChannels(app)
        nm.cancelAll()
    }

    private fun ev(seq: Long, type: String, json: String) = LoggedEvent(seq, type, seq * 1000, RpcJson.parseToJsonElement(json) as JsonObject)

    /** A finished turn whose closing reply is [text]. */
    private fun turn(text: String): Turn = Projector.conversation(listOf(
        ev(0, "turn/start", """{"turn":1}"""),
        ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}"""),
        ev(2, "assistant/message", """{"message":{"content":[{"type":"text","text":${JsonPrimitive(text)}}]}}"""),
        ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
    ), running = false).turns.last()

    private fun row(id: String, title: String = "整理下载", parentId: String? = null, ruleId: Long? = null, muted: Boolean = false) =
        SessionEntity(id, title, 1, 2, ruleId = ruleId, parentId = parentId, muted = muted)

    private fun active(): List<StatusBarNotification> = nm.activeNotifications.toList()
    private fun sessionNotifications() = active().filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    private val StatusBarNotification.title get() = notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
    private val StatusBarNotification.text get() = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    @Test fun `plugin background sessions, subagents and muted conversations never notify a finished turn`() {
        // A plugin's session (heartbeat, QQ bot) has no row: the app never wrote one.
        assertFalse(SessionNotices.turnFinished(app, "plugin-heartbeat", null, "", turn("""{"items":[{"id":1}]}""")))
        assertFalse(SessionNotices.turnFinished(app, "root/child", row("root/child", parentId = "root"), "", turn("done")))
        assertFalse(SessionNotices.turnFinished(app, "s1", row("s1", muted = true), "", turn("done")))
        assertTrue(active().isEmpty())
        // Nor does a card in a plugin's session reach the user.
        assertFalse(SessionNotices.waiting(app, "plugin-qqbot", null, "", "等待审批", "sms_send"))
        assertTrue(active().isEmpty())
    }

    @Test fun `a waiting card still notifies in a muted conversation and in an automation run`() {
        assertTrue(SessionNotices.waiting(app, "s1", row("s1", muted = true), "", app.getString(R.string.notif_approval_title), "sms_send"))
        // Rule steps without an agent have no row; their id says they are an automation.
        assertTrue(SessionNotices.waiting(app, "auto_3_x", null, "", app.getString(R.string.notif_unlock_title), null, Notifications.ID_SESSION_UNLOCK))
        assertEquals(2, sessionNotifications().size)
    }

    @Test fun `one notification per conversation, replaced, titled and readable`() {
        assertTrue(SessionNotices.turnFinished(app, "s1", row("s1"), "", turn("**已整理** 下载目录：\n\n- 删除 3 个文件\n- [详情](https://x)")))
        assertTrue(SessionNotices.turnFinished(app, "s1", row("s1"), "整理下载目录", turn("""{"speak":true,"text":"x"}""")))
        val mine = sessionNotifications()
        assertEquals(1, mine.size)
        assertEquals("s1", mine[0].tag)
        assertEquals("整理下载目录", mine[0].title)
        // JSON from a plugin-style reply is never shown: the generic line instead.
        assertEquals(app.getString(R.string.notif_done_generic), mine[0].text)
        assertEquals(Notifications.GROUP_SESSIONS, mine[0].notification.group)
    }

    @Test fun `several conversations are grouped under one summary, which goes with the last but one`() {
        SessionNotices.turnFinished(app, "s1", row("s1", "A"), "", turn("好了"))
        assertNull(active().firstOrNull { it.id == Notifications.ID_SESSIONS_SUMMARY })
        SessionNotices.turnFinished(app, "s2", row("s2", "B"), "", turn("也好了"))
        val summary = active().firstOrNull { it.id == Notifications.ID_SESSIONS_SUMMARY }
        assertNotNull(summary)
        assertTrue(summary!!.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(Notifications.GROUP_SESSIONS, summary.notification.group)
        Notifications.cancelSession(app, "s1")
        assertEquals(listOf("s2"), active().map { it.tag })
    }

    @Test fun `tapping opens exactly that conversation in MainActivity`() {
        SessionNotices.turnFinished(app, "s1", row("s1"), "", turn("好了"))
        SessionNotices.turnFinished(app, "s2", row("s2"), "", turn("好了"))
        for (id in listOf("s1", "s2")) {
            val n = sessionNotifications().first { it.tag == id }
            val intent = shadowOf(n.notification.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(id, intent.getStringExtra(MainActivity.EXTRA_SESSION))
        }
        // Opening it (SessionStore.setViewing) removes it.
        Notifications.cancelSession(app, "s1")
        assertEquals(listOf("s2"), sessionNotifications().map { it.tag })
    }

    @Test fun `summaries are one readable line`() {
        assertEquals("已整理 下载目录： 删除 3 个文件 详情", NotificationText.summary("**已整理** 下载目录：\n\n- 删除 3 个文件\n- [详情](https://x)"))
        assertNull(NotificationText.summary("  "))
        assertNull(NotificationText.summary(null))
        assertNull(NotificationText.summary("""{"items":[{"title":"x"}]}"""))
        assertNull(NotificationText.summary("""{"speak":true,"text":"cut off"""))
        assertNull(NotificationText.summary("```json\n{\"a\":1}\n```"))
        assertNull(NotificationText.summary("[1, 2, 3]"))
        assertEquals("[注意] 明天有雨", NotificationText.summary("[注意] 明天有雨"))
        val long = NotificationText.summary("字".repeat(500))!!
        assertEquals(NotificationText.MAX, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun `the session list and notifications share one visibility rule`() {
        assertTrue(SessionVisibility.listed(row("s1")))
        assertFalse(SessionVisibility.listed(row("auto_1_x", ruleId = 1)))
        assertFalse(SessionVisibility.listed(row("r/c", parentId = "r")))
        assertTrue(SessionVisibility.userFacing("s1", row("s1")))
        assertTrue(SessionVisibility.userFacing("auto_1_x", row("auto_1_x", ruleId = 1)))
        assertTrue(SessionVisibility.userFacing("auto_1_x", null))
        assertFalse(SessionVisibility.userFacing("r/c", row("r/c", parentId = "r")))
        assertFalse(SessionVisibility.userFacing("2b6f0c1e-plugin", null))
    }
}
