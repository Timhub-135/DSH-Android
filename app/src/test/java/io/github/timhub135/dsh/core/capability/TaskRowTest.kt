package io.github.timhub135.dsh.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRowTest {
    private val raw = """
        12	0	com.tencent.mm	com.tencent.mm.ui.LauncherUI	1	1
        30	5	com.android.settings	com.android.settings.Settings	1	1
        3	0	com.miui.home	com.miui.home.launcher.Launcher	0	0
        bad line
    """.trimIndent()

    @Test fun parse() {
        val rows = TaskRow.parse(raw)
        assertEquals(3, rows.size)
        assertEquals(TaskRow(30, 5, "com.android.settings", "com.android.settings.Settings", true, true), rows[1])
        assertFalse(rows[2].standard)
        assertTrue(TaskRow.parse(null).isEmpty())
    }

    @Test fun inUseOnMain() {
        val rows = TaskRow.parse(raw)
        assertTrue(TaskRow.inUseOnMain(rows, "com.tencent.mm"))
        assertFalse(TaskRow.inUseOnMain(rows, "com.android.settings"))
        // Visibility unknown: only the top task counts.
        val unknown = listOf(TaskRow(1, 0, "a", "", true, null), TaskRow(2, 0, "b", "", true, null))
        assertTrue(TaskRow.inUseOnMain(unknown, "a"))
        assertFalse(TaskRow.inUseOnMain(unknown, "b"))
    }

    @Test fun spilled() {
        val pay = TaskRow(40, 0, "com.eg.android.AlipayGphone", "Pay", true, true)
        val rows = listOf(pay, TaskRow(12, 0, "com.tencent.mm", "", true, false))
        assertEquals(pay, TaskRow.spilled(rows, topBefore = 12, excluded = setOf("io.github.timhub135.dsh"), handedOver = null))
        assertNull("still the same top", TaskRow.spilled(rows, 40, emptySet(), null))
        assertNull("given to the user", TaskRow.spilled(rows, 12, emptySet(), 40))
        assertNull("excluded", TaskRow.spilled(rows, 12, setOf("com.eg.android.AlipayGphone"), null))
        assertNull("home", TaskRow.spilled(listOf(TaskRow(3, 0, "launcher", "", false, true)), 12, emptySet(), null))
    }

    @Test fun chooser() {
        assertTrue(TaskRow.isChooser(TaskRow(1, 0, "android", "com.android.internal.app.ResolverActivity", true, true)))
        assertTrue(TaskRow.isChooser(TaskRow(1, 0, "com.android.intentresolver", "x", true, true)))
        assertFalse(TaskRow.isChooser(TaskRow(1, 0, "com.tencent.mm", "x", true, true)))
    }

    @Test fun muteLedgerModes() {
        assertEquals("ignore", io.github.timhub135.dsh.core.platform.MuteLedger.parseMode("PLAY_AUDIO: ignore; time=+1m"))
        assertEquals("default", io.github.timhub135.dsh.core.platform.MuteLedger.parseMode("No operations."))
        assertEquals("allow", io.github.timhub135.dsh.core.platform.MuteLedger.restoreMode("default"))
    }
}
