package io.github.jackwener.dsh.core.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.session.SessionVisibility
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #43: 免打扰 is stored on the session row and survives reopening the database. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class SessionMuteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val name = "mute-test.db"

    private fun open() = Room.databaseBuilder(app, DshDatabase::class.java, name).setDriver(JdbcSQLiteDriver()).build()

    @After fun tearDown() { app.deleteDatabase(name) }

    @Test fun `mute persists and the list keeps only the user's conversations`() { runBlocking {
        open().apply {
            val dao = sessions()
            dao.upsert(SessionEntity("s1", "整理下载", 1, 2))
            dao.upsert(SessionEntity("s2", "天气", 1, 3))
            dao.upsert(SessionEntity("s1/c", "", 1, 4, parentId = "s1"))
            dao.upsert(SessionEntity("auto_1_x", "规则", 1, 5, ruleId = 1))
            assertFalse(dao.get("s1")!!.muted)
            dao.setMuted("s1", true)
            close()
        }
        open().apply {
            val dao = sessions()
            assertTrue(dao.get("s1")!!.muted)
            assertFalse(dao.get("s2")!!.muted)
            val listed = dao.observeTopLevel().first().filter(SessionVisibility::listed)
            assertEquals(listOf("s2", "s1"), listed.map { it.id })
            assertTrue(listed.first { it.id == "s1" }.muted)
            dao.setMuted("s1", false)
            assertFalse(dao.get("s1")!!.muted)
            close()
        }
    } }
}
