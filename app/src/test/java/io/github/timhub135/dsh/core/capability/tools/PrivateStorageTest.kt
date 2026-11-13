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

    @Test fun restrictedOnAndroid11AndNewerOnly() {
        val p = "/storage/emulated/0/Android/data/com.example/files/log.txt"
        assertFalse("Android 10: legacy storage reads it directly", PrivateStorage.isRestricted(p, 29))
        assertTrue(PrivateStorage.isRestricted(p, 30))
        assertTrue(PrivateStorage.isRestricted(p, 37))
        assertFalse(PrivateStorage.isRestricted("/storage/emulated/0/Download/a.txt", 37))
    }

    @Test fun scopeRootsAreTheFoldersThemselves() {
        assertTrue(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/data"))
        assertTrue(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/obb/"))
        assertFalse(PrivateStorage.isScopeRoot("/storage/emulated/0/Android/data/com.example"))
        assertFalse(PrivateStorage.isScopeRoot("/storage/emulated/0/Download"))
    }

    @Test fun restrictionMessageSaysWhatToDoInstead() {
        val m = PrivateStorage.ANDROID_DATA_RESTRICTED
        assertTrue(m, m.contains("Android/data") && m.contains("Android/obb"))
        assertTrue(m, m.contains("share sheet") && m.contains("manually"))
        assertEquals("android_data_restricted", PrivateStorage.ERROR_CODE)
    }
}
