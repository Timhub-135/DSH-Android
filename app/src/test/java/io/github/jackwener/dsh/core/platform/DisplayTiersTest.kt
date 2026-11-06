package io.github.jackwener.dsh.core.platform

import io.github.jackwener.dsh.core.platform.DisplayTiers.OWN_DISPLAY_GROUP
import io.github.jackwener.dsh.core.platform.DisplayTiers.OWN_FOCUS
import io.github.jackwener.dsh.core.platform.DisplayTiers.PUBLIC
import io.github.jackwener.dsh.core.platform.DisplayTiers.OWN_CONTENT_ONLY
import io.github.jackwener.dsh.core.platform.DisplayTiers.TRUSTED
import io.github.jackwener.dsh.core.platform.DisplayTiers.UNTRUSTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DisplayTiersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val issue60 = SecurityException("Requires ADD_TRUSTED_DISPLAY permission to create a trusted virtual display.")

    @Test fun trustedTiersFirstThenUntrusted() {
        val t31 = DisplayTiers.tiers(31, skipTrusted = false)
        assertEquals(listOf(0x5c9, 0x409, 0x1c9, 0x9), t31)
        assertEquals(listOf(true, true, false, false), t31.map(DisplayTiers::trusted))
        val t34 = DisplayTiers.tiers(34, skipTrusted = false)
        assertEquals(6, t34.size)
        assertTrue(t34.first() and OWN_FOCUS != 0 && DisplayTiers.ownGroup(t34.first()))
        assertEquals(listOf(UNTRUSTED, PUBLIC or OWN_CONTENT_ONLY), t34.takeLast(2))
        assertEquals(5, DisplayTiers.tiers(33, false).size)
    }

    @Test fun untrustedTiersNeedNoSignaturePermission() {
        for (sdk in 29..37) for (f in DisplayTiers.tiers(sdk, skipTrusted = true)) {
            assertFalse(DisplayTiers.trusted(f))
            assertFalse(DisplayTiers.ownGroup(f))
            assertEquals(0, f and (DisplayTiers.ALWAYS_UNLOCKED or OWN_FOCUS or DisplayTiers.STEAL_TOP_FOCUS_DISABLED))
            assertEquals("public, own content only", PUBLIC or OWN_CONTENT_ONLY, f and (PUBLIC or OWN_CONTENT_ONLY))
            assertEquals("no PRESENTATION", 0, f and (1 shl 1))
        }
        assertEquals(listOf(UNTRUSTED, PUBLIC or OWN_CONTENT_ONLY), DisplayTiers.tiers(31, skipTrusted = true))
    }

    @Test fun recognisesTheTrustedRefusal() {
        assertTrue(DisplayTiers.trustedDenied(UNTRUSTED or TRUSTED, issue60))
        assertTrue(DisplayTiers.trustedDenied(UNTRUSTED or TRUSTED or OWN_DISPLAY_GROUP, issue60))
        // OWN_DISPLAY_GROUP's own refusal names the permission but says nothing about TRUSTED being refused.
        assertFalse(DisplayTiers.trustedDenied(UNTRUSTED or TRUSTED or OWN_DISPLAY_GROUP,
            SecurityException("Requires ADD_TRUSTED_DISPLAY permission to create a virtual display which is not in the default DisplayGroup.")))
        assertTrue(DisplayTiers.trustedDenied(PUBLIC or OWN_CONTENT_ONLY or TRUSTED,
            SecurityException("Requires ADD_TRUSTED_DISPLAY permission to create a virtual display which is not in the default DisplayGroup.")))
        assertFalse(DisplayTiers.trustedDenied(UNTRUSTED, issue60))
        assertFalse(DisplayTiers.trustedDenied(UNTRUSTED or TRUSTED, IllegalArgumentException("ADD_TRUSTED_DISPLAY")))
        assertFalse(DisplayTiers.trustedDenied(UNTRUSTED or TRUSTED, SecurityException("Requires CAPTURE_VIDEO_OUTPUT")))
    }

    @Test fun degradedReason() {
        assertNull(DisplayTiers.degradedReason(UNTRUSTED or TRUSTED, 31))
        assertEquals("untrusted display: no ADD_TRUSTED_DISPLAY, SDK 31", DisplayTiers.degradedReason(UNTRUSTED, 31))
    }

    @Test fun denialIsRememberedPerBootAndBuild() {
        val f = tmp.root.resolve("dsh_vd_untrusted")
        val key = TrustedDenial.Key(31, "HUAWEI/xx/yy:12/abc", "boot-1")
        val a = TrustedDenial(f, key)
        assertNull(a.reason())
        a.remember("SecurityException: Requires ADD_TRUSTED_DISPLAY\tpermission")
        assertEquals("SecurityException: Requires ADD_TRUSTED_DISPLAY\tpermission", a.reason())
        // A restarted service process on the same boot and build skips straight to the untrusted tiers.
        assertEquals("SecurityException: Requires ADD_TRUSTED_DISPLAY permission", TrustedDenial(f, key).reason())
        // A reboot, an OTA or another SDK tries trusted displays again.
        assertNull(TrustedDenial(f, key.copy(bootId = "boot-2")).reason())
        assertNull(TrustedDenial(f, key.copy(fingerprint = "HUAWEI/xx/yy:12/def")).reason())
        assertNull(TrustedDenial(f, key.copy(sdk = 33)).reason())
        // No file (or an unreadable one): per process only.
        val mem = TrustedDenial(null, key)
        mem.remember("x")
        assertEquals("x", mem.reason())
        f.writeText("garbage")
        assertNull(TrustedDenial(f, key).reason())
    }
}
