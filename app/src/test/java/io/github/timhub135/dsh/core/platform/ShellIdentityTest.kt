package io.github.timhub135.dsh.core.platform

import io.github.timhub135.dsh.core.capability.AgentScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ShellIdentityTest {
    @Test fun shellIdentityIsTheShellPackageAndUid() {
        // DisplayManagerService checks the package against the calling uid: shell uid 2000 owns com.android.shell (#34).
        assertEquals("com.android.shell", ShellIdentity.PACKAGE)
        assertEquals(2000, ShellIdentity.UID)
        assertNull(ShellIdentity.ATTRIBUTION_TAG)
    }

    @Test fun shellContextReportsTheShellIdentity() {
        // The overrides are what services read (getPackageName / getOpPackageName / getAttributionTag / getAttributionSource).
        val m = ShellContext::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(m.containsAll(listOf("getPackageName", "getOpPackageName", "getAttributionTag", "getAttributionSource", "getSystemService")))
    }

    @Test fun describeKeepsClassAndMessageAndUnwrapsReflection() {
        val sec = SecurityException("packageName must match the calling uid")
        assertEquals("SecurityException: packageName must match the calling uid", ShellIdentity.describe(sec))
        assertEquals("SecurityException: packageName must match the calling uid", ShellIdentity.describe(InvocationTargetException(sec)))
        assertEquals("IllegalStateException", ShellIdentity.describe(IllegalStateException()))
        assertEquals("IOException: a b", ShellIdentity.describe(java.io.IOException("a\n  b")))
        assertTrue(ShellIdentity.describe(RuntimeException("x".repeat(5000))).length <= ShellIdentity.MAX_ERROR)
    }

    @Test fun displayFailureLeadsWithTheLastFlagSet() {
        val s = DisplayFailure.summary(listOf("flags 0x7fc9: SecurityException: a", "flags 0x5c9: SecurityException: b", "flags 0x409: SecurityException: c"))
        assertTrue(s, s.startsWith("flags 0x409: SecurityException: c; flags 0x5c9"))
        assertTrue(DisplayFailure.summary(emptyList()).isNotEmpty())
    }

    @Test fun agentScreenFailureReachesTheModelAndTheUser() {
        assertEquals("SecurityException: x", AgentScreen.createFailure("SecurityException: x", RuntimeException("ignored")))
        assertEquals("IllegalStateException: the Shizuku shell service did not start", AgentScreen.createFailure(null, IllegalStateException("the Shizuku shell service did not start")))
        assertTrue(AgentScreen.createFailure(" ", null).isNotBlank())
        val note = AgentScreen.failureNote("SecurityException: packageName must match the calling uid")
        assertTrue(note, note.contains("后台屏创建失败：SecurityException: packageName must match the calling uid"))
        assertFalse(AgentScreen.failureNote(null).contains("后台屏创建失败"))
    }
}
