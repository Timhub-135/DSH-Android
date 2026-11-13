package io.github.timhub135.dsh.feature.settings

import io.github.timhub135.dsh.feature.settings.RomKeepAlive.Kind
import io.github.timhub135.dsh.feature.settings.RomKeepAlive.RowStatus
import io.github.timhub135.dsh.feature.settings.RomKeepAlive.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RomKeepAliveTest {
    private fun detect(m: String, b: String = m, vararg props: Pair<String, String>) = RomKeepAlive.detect(m, b) { props.toMap()[it] }

    @Test fun vendorsFromManufacturerAndBrand() {
        assertEquals(Vendor.Xiaomi, detect("Xiaomi", "Redmi").vendor)
        assertEquals(Vendor.Xiaomi, detect("Xiaomi", "POCO").vendor)
        assertEquals(Vendor.Huawei, detect("HUAWEI").vendor)
        assertEquals(Vendor.Honor, detect("HONOR").vendor)
        assertEquals(Vendor.OnePlus, detect("OnePlus").vendor)
        assertEquals(Vendor.Realme, detect("realme").vendor)
        assertEquals(Vendor.Oppo, detect("OPPO").vendor)
        assertEquals(Vendor.Vivo, detect("vivo", "iQOO").vendor)
        assertEquals(Vendor.Samsung, detect("samsung").vendor)
        assertEquals(Vendor.Meizu, detect("Meizu").vendor)
        assertEquals(Vendor.Motorola, detect("motorola").vendor)
        assertEquals(Vendor.Google, detect("Google").vendor)
        assertEquals(Vendor.Other, detect("Fairphone").vendor)
    }

    @Test fun vendorFromPropsWhenManufacturerIsOdd() {
        assertEquals(Vendor.Xiaomi, detect("Unknown", "x", "ro.miui.ui.version.name" to "V140").vendor)
        assertEquals(Vendor.Huawei, detect("x", "x", "ro.build.version.emui" to "EmotionUI_12.0.0").vendor)
        assertEquals(Vendor.Oppo, detect("x", "x", "ro.build.version.oplusrom" to "V13.1.0").vendor)
        assertEquals(Vendor.Vivo, detect("x", "x", "ro.vivo.os.version" to "13.0").vendor)
        assertEquals(Vendor.Meizu, detect("x", "x", "ro.build.display.id" to "Flyme 10.5.0.1A").vendor)
    }

    @Test fun osNames() {
        assertEquals("HyperOS 1.0", detect("Xiaomi", "Xiaomi", "ro.mi.os.version.name" to "OS1.0", "ro.miui.ui.version.name" to "V816").os)
        assertEquals("MIUI 14", detect("Xiaomi", "Redmi", "ro.miui.ui.version.name" to "V14").os)
        assertEquals("HarmonyOS 3.0.0", detect("HUAWEI", "HUAWEI", "hw_sc.build.platform.version" to "3.0.0", "ro.build.version.emui" to "EmotionUI_13.0.0").os)
        assertEquals("EMUI 12.0.0", detect("HUAWEI", "HUAWEI", "ro.build.version.emui" to "EmotionUI_12.0.0").os)
        assertEquals("ColorOS 13.1.0", detect("OPPO", "OPPO", "ro.build.version.oplusrom" to "V13.1.0").os)
        assertEquals("One UI 6.1", detect("samsung", "samsung", "ro.build.version.oneui" to "60100").os)
        assertEquals("Flyme 10.5.0.1", detect("Meizu", "meizu", "ro.build.display.id" to "Flyme 10.5.0.1A").os)
        assertNull(detect("Google").os)
        assertEquals("三星 · One UI 6.1", detect("samsung", "samsung", "ro.build.version.oneui" to "60100").label(true))
    }

    @Test fun vivoNeverGuessesFuntouch() {
        // iQOO on OriginOS 6 reports ro.vivo.os.name=Funtouch and an Android-like version: stay neutral.
        val iqoo = detect("vivo", "iQOO", "ro.vivo.os.name" to "Funtouch", "ro.vivo.os.version" to "16.0")
        assertNull(iqoo.os)
        assertEquals("vivo / iQOO 系统", iqoo.label(true))
        assertEquals("vivo / iQOO OS", iqoo.label(false))
        assertEquals("vivo / iQOO 系统", detect("vivo").label(true))
        // Positive OriginOS evidence is still shown.
        assertEquals("OriginOS", detect("vivo", "vivo", "ro.vivo.os.name" to "OriginOS", "ro.vivo.os.version" to "4.0").os)
        assertEquals("OriginOS 6", detect("vivo", "iQOO", "ro.vivo.os.name" to "Funtouch", "ro.vivo.os.build.display.id" to "OriginOS 6").os)
        assertEquals("vivo · OriginOS 5.0", detect("vivo", "vivo", "ro.vivo.os.build.display.id" to "OriginOS 5.0").label(false))
        // Settings candidates are unchanged.
        assertEquals("com.vivo.permissionmanager", RomKeepAlive.targets(Vendor.Vivo, Kind.AutoStart).first().pkg)
    }

    @Test fun rowStatus() {
        assertEquals(RowStatus.Exempt, RomKeepAlive.status(Kind.Battery) { true })
        assertEquals(RowStatus.NotExempt, RomKeepAlive.status(Kind.Battery) { false })
        assertEquals(RowStatus.Unreadable, RomKeepAlive.status(Kind.Battery) { throw SecurityException("denied") })
        // Vendor switches are never read (or inferred), even if the battery read would succeed.
        for (k in listOf(Kind.AutoStart, Kind.Background)) {
            assertEquals(RowStatus.ManualCheck, RomKeepAlive.status(k) { true })
            assertEquals(RowStatus.ManualCheck, RomKeepAlive.status(k) { error("must not be read") })
        }
    }

    @Test fun propFailuresAreSoft() {
        val rom = RomKeepAlive.detect("Xiaomi", null) { throw SecurityException("denied") }
        assertEquals(Vendor.Xiaomi, rom.vendor)
        assertNull(rom.os)
        assertEquals(Vendor.Other, RomKeepAlive.detect(null, null) { null }.vendor)
    }

    @Test fun candidates() {
        val miui = RomKeepAlive.targets(Vendor.Xiaomi, Kind.AutoStart).first()
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", miui.cls)
        val hidden = RomKeepAlive.targets(Vendor.Xiaomi, Kind.Background).first()
        assertEquals(listOf("package_name"), hidden.pkgExtras)
        assertEquals(listOf("package_label"), hidden.labelExtras)
        assertEquals(RomKeepAlive.APP_DETAILS, RomKeepAlive.targets(Vendor.Oppo, Kind.Background).first())
        assertEquals("com.oneplus.security", RomKeepAlive.targets(Vendor.OnePlus, Kind.AutoStart).first().pkg)
        assertEquals("com.hihonor.systemmanager", RomKeepAlive.targets(Vendor.Honor, Kind.AutoStart).first().pkg)
        assertTrue(RomKeepAlive.targets(Vendor.Google, Kind.AutoStart).isEmpty())
        assertFalse(Kind.AutoStart in RomKeepAlive.kinds(Vendor.Samsung))
        for (v in Vendor.entries) {
            assertTrue(RomKeepAlive.targets(v, Kind.Battery).first().pkgUri)
            for (k in RomKeepAlive.kinds(v)) {
                assertTrue("$v $k", RomKeepAlive.targets(v, k).isNotEmpty())
                assertTrue(RomKeepAlive.targets(v, k).all { it.cls == null || it.pkg != null })
                assertTrue(RomKeepAlive.hint(v, k).zh.isNotBlank() && RomKeepAlive.hint(v, k).en.isNotBlank())
            }
        }
    }

    @Test fun manifestQueriesCoverEveryPackageAndAction() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        for (p in RomKeepAlive.PACKAGES) assertTrue(p, manifest.contains("<package android:name=\"$p\" />"))
        val actions = Vendor.entries.flatMap { v -> Kind.entries.flatMap { RomKeepAlive.targets(v, it) } }.filter { it.pkg == null }.mapNotNull { it.action }.toSet()
        for (a in actions) assertTrue(a, manifest.contains("<action android:name=\"$a\" />"))
    }
}
