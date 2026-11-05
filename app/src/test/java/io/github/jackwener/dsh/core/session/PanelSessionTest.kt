package io.github.jackwener.dsh.core.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * #57: the quick panel (floating ball, QS tile, assistant) reopened its last conversation by id even after
 * it was deleted; the engine still had that session, so every message from the panel failed with
 * `session "…" already exists`. A deleted conversation is forgotten; and dsh's refusal is recognized so
 * SessionStore can resume such a session instead of failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class PanelSessionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val idle: (String) -> Boolean = { false }

    @Test fun `reopens the recent or busy panel conversation`() {
        val now = 1_000_000_000L
        assertEquals("s", PanelSession.reopenable("s", now - 60_000, now, idle))
        assertNull(PanelSession.reopenable("s", now - PanelSession.RECENT_MS, now, idle))
        assertEquals("s", PanelSession.reopenable("s", 0, now) { it == "s" })
        assertNull(PanelSession.reopenable(null, now, now) { true })
    }

    @Test fun `a deleted panel conversation is never reopened`() {
        PanelSession.remember(app, "a")
        assertEquals("a", PanelSession.reopen(app, System.currentTimeMillis(), idle))
        PanelSession.forget(app, "other")
        assertEquals("a", PanelSession.last(app))
        PanelSession.forget(app, "a")
        assertNull(PanelSession.last(app))
        assertNull(PanelSession.reopen(app, System.currentTimeMillis()) { true })
        // Leaving the panel later does not bring it back.
        PanelSession.touch(app, "a")
        assertNull(PanelSession.reopen(app, System.currentTimeMillis(), idle))
    }

    @Test fun `dsh's duplicate-create refusal is recognized`() {
        assertTrue(SessionOpen.alreadyExists("""session "3b37232f-6889-425f-a61d-1260a991f7f7" already exists"""))
        assertFalse(SessionOpen.alreadyExists("""subagent "x" already exists"""))
        assertFalse(SessionOpen.alreadyExists("""SDK session is already open or opening: x"""))
        assertFalse(SessionOpen.alreadyExists("""session "x" not found"""))
        assertFalse(SessionOpen.alreadyExists(null))
    }
}
