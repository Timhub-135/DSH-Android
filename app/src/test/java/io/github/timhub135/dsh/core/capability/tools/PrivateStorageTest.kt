package io.github.timhub135.dsh.core.capability.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateStorageTest {
    @Test fun recognisesAndroidDataAndObbOnEveryVolumeSpelling() {
        listOf(
            "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/data/com.tencent.mm/files",
            "/storage/emulated/10/Android/obb/com.game", "/sdcard/Android/data/x", "/storage/self/primary/Android/obb",
            "/storage/1A2B-3C4D/Android/data/x", "/storage/emulated/0/android/DATA/x", "/storage/emulated/0/Android/data/",
        ).forEach { assertTrue(it, PrivateStorage.isPrivate(it)) }
        listOf(
            "/storage/emulated/0/Android", "/storage/emulated/0/Android/media/x", "/storage/emulated/0/Android/database",
            "/storage/emulated/0/Android/data_backup", "/storage/emulated/0/Download/Android/data", "/storage/emulated/0",
        ).forEach { assertFalse(it, PrivateStorage.isPrivate(it)) }
    }

    @Test fun routesThroughTheShellOnlyOnAndroid11AndNewer() {
        val p = "/storage/emulated/0/Android/data/com.example/files/log.txt"
        assertFalse("Android 10: legacy storage reads it directly", PrivateStorage.needsShell(p, 29))
        assertTrue(PrivateStorage.needsShell(p, 30))
        assertTrue(PrivateStorage.needsShell(p, 37))
        assertFalse(PrivateStorage.needsShell("/storage/emulated/0/Download/a.txt", 37))
    }

    @Test fun scopeRootsAreTheFoldersThemselves() {
        assertTrue(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/data"))
        assertTrue(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/obb/"))
        assertFalse(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/data/com.example"))
        assertFalse(PrivateStorage.isScopeRoot("/storage/emulated/0/Download"))
    }

    @Test fun shizukuMessageNamesTheSetting() {
        val m = PrivateStorage.needsShizuku("Shizuku is installed but not running")
        assertTrue(m, m.contains("访问 Android/data 需要 Shizuku") && m.contains("Shizuku is installed but not running") && m.contains("Settings"))
    }

    @Test fun adbShellResultFormat() {
        assertEquals(30, AdbShell.timeoutS(null))
        assertEquals(AdbShell.MAX_TIMEOUT_S, AdbShell.timeoutS(100_000))
        assertEquals(1, AdbShell.timeoutS(0))
        assertEquals("exit 0\nlevel: 80", AdbShell.format(0, "level: 80\n", 30))
        assertEquals("exit 1\n(no output)", AdbShell.format(1, "", 30))
        assertTrue(AdbShell.format(124, "partial", 5).startsWith("exit 124 (stopped after 5s"))
        assertTrue(AdbShell.format(0, "x".repeat(AdbShell.OUTPUT_CAP), 30).endsWith("grep or head)"))
    }
}
