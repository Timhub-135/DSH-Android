package io.github.timhub135.dsh.core.capability.tools

/**
 * Android/data and Android/obb: on Android 11+ no app may enter them, even with "All files access",
 * but the shell user (Shizuku) can, so FileTools serves them through the shell user service. Android
 * 10 (legacy storage) reads them like any other folder. Pure, unit-tested.
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

    /** Whether [path] must go through the shell user on Android [sdk]. */
    fun needsShell(path: String, sdk: Int): Boolean = sdk >= 30 && isPrivate(path)

    const val NEEDS_SHIZUKU = "Android/data and Android/obb are closed to apps on Android 11 and newer; DSH can reach them only through Shizuku " +
        "(shell access), and %s. Ask the user to turn Shizuku on (访问 Android/data 需要 Shizuku：去 设置 → 手机权限 → Shizuku 打开; " +
        "DSH → Settings → Phone permissions → Shizuku), then try again."

    fun needsShizuku(reason: String) = NEEDS_SHIZUKU.format(reason)
}
