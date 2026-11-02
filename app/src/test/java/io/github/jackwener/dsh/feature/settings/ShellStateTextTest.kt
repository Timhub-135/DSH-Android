package io.github.jackwener.dsh.feature.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.jackwener.dsh.core.platform.ShellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** #42: the status UI (card tag, Doctor row) tells the Shizuku states apart and gives each its next step. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Suppress("DEPRECATION") @SQLiteMode(SQLiteMode.Mode.LEGACY)
class ShellStateTextTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `labels and hints differ per state`() {
        val notReady = listOf(ShellState.NotInstalled, ShellState.InstalledNotRunning(false), ShellState.RunningNoPermission(false), ShellState.Unsupported(10))
        val labels = (notReady + ShellState.Ready).map { shellStateLabel(app, it) }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(shellStateLabel(app, ShellState.Unsupported(10)).contains("10"))
        val hints = (notReady + ShellState.InstalledNotRunning(true) + ShellState.RunningNoPermission(true)).map { shellStateHint(app, it) }
        assertTrue(hints.all { !it.isNullOrBlank() })
        assertEquals(hints.size, hints.toSet().size)
        assertNull(shellStateHint(app, ShellState.Ready))
    }
}
