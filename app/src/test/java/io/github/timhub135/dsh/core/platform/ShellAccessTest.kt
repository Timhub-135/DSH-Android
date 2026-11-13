package io.github.timhub135.dsh.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAccessTest {
    @Test fun versionGate() {
        assertTrue(ShellAccess.atLeast("13.6.0.r1086.2650830c", 13, 6))
        assertTrue(ShellAccess.atLeast("14.0.1", 13, 6))
        assertFalse(ShellAccess.atLeast("13.5.4.r1049.0e53409", 13, 6))
        assertFalse(ShellAccess.atLeast("", 13, 6))
    }

    private fun probe(alive: Boolean = true, version: Int? = 13, granted: Boolean? = true, denied: Boolean = false,
                      installed: Boolean = true, wasReady: Boolean = false) = ShellProbe(alive, version, granted, denied, installed, wasReady)

    /** #42: not installed / installed but stopped / running without permission / ready / too old. */
    @Test fun `each Shizuku situation maps to its own state`() {
        assertEquals(ShellState.NotInstalled, ShellAccess.classify(probe(alive = false, version = null, granted = null, installed = false)))
        assertEquals(ShellState.InstalledNotRunning(false), ShellAccess.classify(probe(alive = false, version = null, granted = null)))
        assertEquals(ShellState.InstalledNotRunning(true), ShellAccess.classify(probe(alive = false, version = null, granted = null, wasReady = true)))
        assertEquals(ShellState.RunningNoPermission(false), ShellAccess.classify(probe(granted = false)))
        assertEquals(ShellState.RunningNoPermission(true), ShellAccess.classify(probe(granted = false, denied = true)))
        assertEquals(ShellState.Ready, ShellAccess.classify(probe()))
        assertEquals(ShellState.Unsupported(10), ShellAccess.classify(probe(version = 10, granted = null)))
        // The permission does not matter for a server too old to use.
        assertEquals(ShellState.Unsupported(10), ShellAccess.classify(probe(version = 10, granted = true)))
    }

    @Test fun `a binder that stops answering mid-check reads as not running, never as too old or unauthorized`() {
        assertEquals(ShellState.InstalledNotRunning(false), ShellAccess.classify(probe(version = null, granted = null)))
        assertEquals(ShellState.InstalledNotRunning(true), ShellAccess.classify(probe(granted = null, wasReady = true)))
        assertEquals(ShellState.NotInstalled, ShellAccess.classify(probe(version = null, granted = null, installed = false)))
    }

    @Test fun `every state says what to do next`() {
        val states = listOf(ShellState.NotInstalled, ShellState.InstalledNotRunning(false), ShellState.InstalledNotRunning(true),
            ShellState.RunningNoPermission(false), ShellState.RunningNoPermission(true), ShellState.Ready, ShellState.Unsupported(10))
        assertEquals(states.size, states.map { it.hint }.toSet().size)
        assertTrue(ShellState.Unsupported(10).reason.contains("10"))
    }
}
