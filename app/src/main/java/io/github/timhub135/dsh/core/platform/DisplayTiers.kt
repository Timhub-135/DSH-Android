package io.github.timhub135.dsh.core.platform

import java.io.File

/**
 * Flag sets for creating a virtual display, most capable first. This is plain flag arithmetic and is
 * kept independent of who creates the display: this version has no background screen, so the app does
 * not currently create one.
 *
 * TRUSTED needs ADD_TRUSTED_DISPLAY, a signature permission AOSP grants to the shell user but some
 * ROMs do not (Huawei EMUI 14.2 / Android 12, #60: "Requires ADD_TRUSTED_DISPLAY permission to create
 * a trusted virtual display"). OWN_DISPLAY_GROUP checks the same permission, ALWAYS_UNLOCKED needs its
 * own and OWN_FOCUS is ignored without TRUSTED, so the untrusted tiers carry none of them.
 * PRESENTATION is left out on purpose: it would offer the display to other apps as a presentation
 * (second-screen) display.
 */
internal object DisplayTiers {
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden)
    const val PUBLIC = 1 shl 0
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** Every tier's common part, and the degraded (untrusted) tier itself. */
    const val UNTRUSTED = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL

    /**
     * Flag sets to try on [sdk]: Android 14 adds its own focus (no keyboard stealing), 13 its own display group;
     * then the untrusted ones. [skipTrusted]: TRUSTED is known to be refused on this device ([TrustedDenial]).
     */
    fun tiers(sdk: Int, skipTrusted: Boolean): List<Int> {
        val base = UNTRUSTED or TRUSTED
        val t = base or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        val u = t or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED
        val trusted = listOfNotNull(
            u.takeIf { sdk >= 34 },
            t.takeIf { sdk >= 33 },
            base,
            PUBLIC or OWN_CONTENT_ONLY or TRUSTED,
        )
        val untrusted = listOf(UNTRUSTED, PUBLIC or OWN_CONTENT_ONLY)
        return if (skipTrusted) untrusted else trusted + untrusted
    }

    fun trusted(flags: Int) = flags and TRUSTED != 0
    fun ownGroup(flags: Int) = flags and OWN_DISPLAY_GROUP != 0

    /**
     * [error], thrown creating a display with [flags], means this device refuses trusted displays altogether
     * (so every TRUSTED tier would fail the same way). OWN_DISPLAY_GROUP's refusal names the same permission
     * but not a trusted display; with a group it only counts when the message says "trusted".
     */
    fun trustedDenied(flags: Int, error: Throwable): Boolean {
        if (!trusted(flags) || error !is SecurityException) return false
        val msg = error.message.orEmpty()
        if (!msg.contains("ADD_TRUSTED_DISPLAY")) return false
        return !ownGroup(flags) || msg.contains("trusted virtual display", ignoreCase = true)
    }

    /** Diagnostics wording for a display created with [flags] on [sdk]; null when it is the full (trusted) kind. */
    fun degradedReason(flags: Int, sdk: Int): String? = if (trusted(flags)) null else "untrusted display: no ADD_TRUSTED_DISPLAY, SDK $sdk"
}

/**
 * Remembers that TRUSTED was refused, so later displays skip straight to the untrusted tier instead of
 * logging the same SecurityException each time. Kept in memory and in [file] (survives a restarted service
 * process), keyed by SDK, build fingerprint and boot: an OTA or a reboot tries trusted displays again once.
 * File line: "sdk\tfingerprint\tbootId\treason".
 */
internal class TrustedDenial(private val file: File?, private val key: Key) {
    data class Key(val sdk: Int, val fingerprint: String, val bootId: String) {
        fun line(reason: String) = "$sdk\t${fingerprint.clean()}\t${bootId.clean()}\t${reason.clean()}\n"
        private fun String.clean() = replace(Regex("[\\t\\r\\n]"), " ")
    }

    @Volatile private var reason: String? = load()

    /** Why trusted displays are refused here (the remembered error), or null when not known to be. */
    fun reason(): String? = reason

    fun remember(reason: String) {
        this.reason = reason
        runCatching { file?.writeText(key.line(reason)) }
    }

    private fun load(): String? = runCatching {
        val p = file?.takeIf { it.exists() }?.readText()?.trimEnd('\n')?.split('\t') ?: return null
        if (p.size < 4) return null
        val now = key.line("").split('\t')
        if (p[0] != now[0] || p[1] != now[1] || p[2] != now[2]) return null
        p[3].ifBlank { null }
    }.getOrNull()

    companion object {
        /** This boot's id (/proc/sys/kernel/random/boot_id), else the boot time to the minute. */
        fun bootId(): String = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000).toString()
    }
}
