package io.github.timhub135.dsh.core.capability

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.platform.AppVisibility
import io.github.timhub135.dsh.service.DshAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts activities for tools on the user's screen, confirming with accessibility that the expected
 * app came to the front. Shared by the app, alarm and calendar tools so every launch lands on the
 * same screen. This version has no background/virtual screen: everything opens where the user is.
 */
@Singleton
class ActivityLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knowledge: AppKnowledge,
) {
    private val pm get() = context.packageManager

    /** Android drops (without an error) activity starts from a background app unless one of these exempts DSH. */
    private fun mayStartFromBackground(): Boolean = AppVisibility.isForeground || Settings.canDrawOverlays(context) ||
        DshAccessibilityService.instance != null ||
        runCatching { context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    private class LaunchBlocked(message: String) : Exception(message)

    /**
     * Starts an activity from the app process. With accessibility on, waits briefly for [expect] to
     * reach the front so "ok" means it actually opened.
     */
    private suspend fun start(intent: Intent, expect: String? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!mayStartFromBackground()) throw LaunchBlocked("Android does not let DSH open other apps while it is in the background " +
            "(no overlay permission or accessibility service). Ask the user to open it, or to enable DSH's accessibility service.")
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw InvalidArgsException("No app can handle this: ${e.message}")
        } catch (e: SecurityException) {
            throw LaunchBlocked("Android blocked opening it from the background (${e.message}); ask the user to open it themselves.")
        }
        return "ok" + confirm(expect)
    }

    private suspend fun confirm(expect: String?): String {
        if (expect == null || DshAccessibilityService.instance == null) return ""
        repeat(10) { if (DshAccessibilityService.foregroundPackage.value == expect) return ""; kotlinx.coroutines.delay(250) }
        return " — but $expect is not in front yet (it may still be loading, or Android blocked it); check with screen_read"
    }

    /** The user-facing name of a package, for result texts. */
    private fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    suspend fun launchResult(prefix: String, intent: Intent, expect: String? = null): ToolResult =
        try { ToolResult.text(prefix + start(intent, expect)) } catch (e: LaunchBlocked) { ToolResult.error("needs_user", e.message ?: "blocked") }

    /**
     * Opens [intent] on the user's screen, where this conversation works. [screen] is kept for schema
     * compatibility: the only accepted value is "main". Adds app knowledge on the first visit.
     * [create] and [mute] belonged to the removed background screen and are ignored.
     */
    suspend fun open(call: CallContext, screen: String?, verb: String, intent: Intent, expect: String?, create: Boolean, mute: Boolean = true): ToolResult {
        if (screen != null && screen != "main")
            return ToolResult.error("not_supported", "Only the user's screen is supported in this version (there is no background screen); pass screen: \"main\" or leave it out.")
        val name = expect?.let { label(it) }
        val result = launchResult("$verb${name?.let { " $it" } ?: ""} on the user's screen: ", intent, expect)
        val known = expect?.takeIf { result.ok }?.let { knowledge.firstVisitNotice(call.sessionId, it) }
        return known?.let { result.appendText(it) } ?: result
    }

    /** The app that would handle [intent] (null: none, or only the system chooser). */
    fun targetOf(intent: Intent): String? = intent.`package` ?: intent.component?.packageName ?: handler(intent)

    /** The package of the activity that handles [intent], or null when no app does (needs a manifest `<queries>` entry). */
    fun handler(intent: Intent): String? = runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    /** Whether any app handles [intent] (the chooser/resolver counts: several apps do). */
    fun handles(intent: Intent): Boolean = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
}
