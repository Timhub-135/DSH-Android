package io.github.jackwener.dsh.core.capability

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.DshAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts activities for tools: on the conversation's agent screen (Shizuku `am start --display`)
 * or on the user's screen, confirming with accessibility that the expected app came to the front.
 * Shared by the app, alarm and calendar tools so every launch lands on the same screen.
 */
@Singleton
class ActivityLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
    private val agent: AgentScreen,
    private val knowledge: AppKnowledge,
) {
    private val pm get() = context.packageManager

    /** Android drops (without an error) activity starts from a background app unless one of these exempts DSH. */
    private fun mayStartFromBackground(): Boolean = AppVisibility.isForeground || Settings.canDrawOverlays(context) ||
        DshAccessibilityService.instance != null ||
        runCatching { context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    private class LaunchBlocked(message: String) : Exception(message)

    /**
     * Starts an activity from the app process, or through Shizuku `am start -W` when Android would
     * block it (background start) or for a virtual display. With accessibility on, waits briefly for
     * [expect] to reach the front so "ok" means it actually opened.
     */
    private suspend fun start(intent: Intent, displayId: Int? = null, expect: String? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == null && (mayStartFromBackground() || !shizuku.available)) {
            if (!mayStartFromBackground()) throw LaunchBlocked("Android does not let DSH open other apps while it is in the background " +
                "(no overlay permission, accessibility service or Shizuku). Ask the user to open it, or to enable DSH's accessibility service.")
            try { context.startActivity(intent) }
            catch (e: ActivityNotFoundException) { throw InvalidArgsException("No app can handle this: ${e.message}") }
            catch (e: SecurityException) { if (!shizuku.available) throw e; return viaShell(intent, null, expect) }
            return "ok" + confirm(expect)
        }
        return viaShell(intent, displayId, expect)
    }

    private suspend fun viaShell(intent: Intent, displayId: Int?, expect: String?): String {
        // An intent: URI keeps only scalar extras; integer lists (alarm repeat days) go as `--eial`.
        val lists = intent.extras?.keySet().orEmpty().mapNotNull { k ->
            @Suppress("DEPRECATION") (intent.extras?.get(k) as? ArrayList<*>)?.takeIf { l -> l.all { it is Int } }?.let { k to it }
        }
        val plain = Intent(intent).apply { lists.forEach { removeExtra(it.first) } }
        val uri = plain.toUri(Intent.URI_INTENT_SCHEME)
        val r = shizuku.exec(buildString {
            append("am start -W")
            if (displayId != null) append(" --display $displayId")
            lists.forEach { (k, l) -> if (l.isNotEmpty()) append(" --eial ${shellQuote(k)} ${l.joinToString(",")}") }
            append(" ${shellQuote(uri)}")
        })
        // `am start` exits 0 on several Android versions even after "Error: Activity not started".
        val out = r.output
        if (r.code != 0 || out.contains("Error") || (out.contains("Status:") && !out.contains("Status: ok")))
            throw IllegalStateException(out.lines().firstOrNull { it.contains("Error") || it.contains("Status") }?.trim() ?: out.take(300))
        return "ok (via Shizuku)" + if (displayId == null) confirm(expect) else confirmOn(displayId, expect)
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun confirm(expect: String?): String {
        if (expect == null || DshAccessibilityService.instance == null) return ""
        repeat(10) { if (DshAccessibilityService.foregroundPackage.value == expect) return ""; kotlinx.coroutines.delay(250) }
        return " — but $expect is not in front yet (it may still be loading, or Android blocked it); check with screen_read"
    }

    private suspend fun confirmOn(displayId: Int, expect: String?): String {
        val svc = DshAccessibilityService.instance ?: return ""
        if (expect == null) return ""
        repeat(10) { if (runCatching { svc.snapshot(displayId).packageName }.getOrNull() == expect) return ""; kotlinx.coroutines.delay(250) }
        return " — but $expect is not in front of the agent screen yet (it may still be loading); check with screen_read"
    }

    suspend fun launchResult(prefix: String, intent: Intent, displayId: Int? = null, expect: String? = null): ToolResult =
        try { ToolResult.text(prefix + start(intent, displayId, expect)) } catch (e: LaunchBlocked) { ToolResult.error("needs_user", e.message ?: "blocked") }

    /**
     * Opens [intent] where this conversation works: its agent screen (created on first use when
     * [create]) or the user's screen ([screen] = the tool's `screen` argument). Never takes an app
     * away from the user: one they are using on their screen is not started on the agent screen.
     * Adds app knowledge on the first visit. [mute]: an app opened on the agent screen is muted
     * (not for apps whose sound matters later, like the clock).
     */
    suspend fun open(call: CallContext, screen: String?, verb: String, intent: Intent, expect: String?, create: Boolean, mute: Boolean = true): ToolResult {
        val t = agent.target(call.sessionId, screen, create)
        t.off?.let { return ToolResult.error("agent_display_off", it) }
        val name = expect?.let { agent.label(it) }
        val result = if (t.agent) {
            if (agent.userTookOver(call.sessionId)) return ToolResult.error("user_took_over",
                "The user took the app over from the agent screen and is using it on their own screen. Do not operate it; wait for their message or ask them.")
            if (expect != null && agent.inUseOnMain(expect)) return ToolResult.error("app_in_use",
                "The user is using $name ($expect) on their screen right now, so it was not opened on the agent screen (that would take it away from them). " +
                    "Wait and try again later, ask the user, or pass screen: \"main\" to work on their screen.")
            launchResult("$verb${name?.let { " $it" } ?: ""} on the agent screen (the user's screen is not affected): ", intent, t.displayId, expect)
                .also { if (it.ok && mute) agent.mute(call.sessionId, expect) }
        } else launchResult("$verb${name?.let { " $it" } ?: ""} on the user's screen: ", intent, expect = expect)
        val known = expect?.takeIf { result.ok }?.let { knowledge.firstVisitNotice(call.sessionId, it) }
        return (t.note?.let { result.prependText(it) } ?: result).let { r -> known?.let { r.appendText(it) } ?: r }
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
