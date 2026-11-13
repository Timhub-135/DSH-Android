package io.github.timhub135.dsh.core.capability.tools

/**
 * Android/data and Android/obb: on Android 11+ no ordinary app may enter them, even with "All files
 * access". This version has no shell access at all, so those paths are refused with a clear error
 * (`android_data_restricted`) instead of being served. Android 10 (legacy storage) reads them like
 * any other folder. Pure, unit-tested.
 */
internal object PrivateStorage {
    /** A storage volume root as the app may spell it, then Android/data|obb (shared storage is case-insensitive). */
    private val DIR = Regex(
        "^(?:/storage/emulated/\\d+|/storage/self/primary|/storage/[0-9a-f]{4}-[0-9a-f]{4}|/sdcard|/mnt/sdcard|/mnt/user/\\d+/primary)" +
            "/android/(?:data|obb)(/.*)?$",
        RegexOption.IGNORE_CASE,
    )

    /** [path] (canonical) is Android/data or Android/obb or inside them. */
    fun isPrivate(path: String): Boolean = DIR.matches(path.trimEnd('/').ifEmpty { "/" })

    /** Android/data or Android/obb itself: never deleted or moved as a whole. */
    fun isScopeRoot(path: String): Boolean = DIR.matchEntire(path.trimEnd('/'))?.groupValues?.get(1).isNullOrEmpty() && isPrivate(path)

    /** Whether [path] is closed to DSH on Android [sdk] (Android 11+), so it must be refused. */
    fun isRestricted(path: String, sdk: Int): Boolean = sdk >= 30 && isPrivate(path)

    /** Model-facing error text for [isRestricted] paths (error code: [ERROR_CODE]). */
    const val ERROR_CODE = "android_data_restricted"

    const val ANDROID_DATA_RESTRICTED = "Android 11 and newer do not allow ordinary apps to access Android/data or Android/obb, " +
        "and this version has no shell access to reach them. Use the system share sheet to move files, or ask the user to " +
        "copy them to a folder such as Download manually."
}
