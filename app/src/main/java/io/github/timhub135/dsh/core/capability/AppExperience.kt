package io.github.timhub135.dsh.core.capability

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.data.SettingsRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learning for the App 经验库 without asking the model: tap routes from a turn's own successful
 * steps ([RouteTracker]), and the notes the engine's post-turn reflection sends back. Only turns that
 * ended normally, without a loop-guard stop, teach anything.
 */
@Singleton
class AppExperience internal constructor(
    private val notes: AppNotes,
    private val reflectOn: suspend () -> Boolean,
    private val labelOf: (String) -> String,
) {
    @Inject constructor(@ApplicationContext context: Context, notes: AppNotes, settings: SettingsRepository) : this(notes,
        { settings.current().reflectExperience },
        { pkg -> runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg) })

    val tracker = RouteTracker()

    fun newTurn(sessionId: String, prompt: String) = tracker.newTurn(sessionId, prompt)

    fun observe(sessionId: String, tool: String, args: JsonObject, result: ToolResult) =
        tracker.observe(sessionId, tool, args, result.ok, result.content.filterIsInstance<Content.Text>().joinToString("\n") { it.text }, result.outcome, result.errorCode)

    /** The model added an entry or confirmed a note in [pkg]: the path that got it there is worth keeping. */
    fun noteUsed(sessionId: String, pkg: String) {
        val (p, steps) = tracker.route(sessionId) ?: return
        if (p == pkg && notes.addRoute(pkg, steps) is AppNotes.Outcome.Saved) tracker.recorded(sessionId, steps)
    }

    /** `experience/turn {sessionId, ok}`: records the route and tells the engine whether to reflect on the turn. */
    suspend fun turnEnded(params: JsonObject): JsonObject {
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ok = params["ok"]?.jsonPrimitive?.contentOrNull == "true"
        val end = tracker.finish(sessionId, ok)
        end.route?.let { (pkg, steps) -> notes.addRoute(pkg, steps) }
        val pkg = end.pkg
        val reflect = end.success && pkg != null && reflectOn()
        return buildJsonObject {
            put("reflect", reflect)
            if (pkg != null) { put("package", pkg); put("label", labelOf(pkg)) }
        }
    }

    /** `experience/notes {sessionId, package, notes: [{text, kind}]}`: the reflection's findings (filtered like app_note). */
    fun reflected(params: JsonObject): Int {
        val pkg = params["package"]?.jsonPrimitive?.contentOrNull ?: return 0
        if (!notes.isInstalled(pkg)) return 0
        return (params["notes"] as? JsonArray).orEmpty().take(MAX_REFLECTED).count { e ->
            val o = e as? JsonObject ?: return@count false
            val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@count false
            val kind = AppNotes.TOOL_KINDS.firstOrNull { it.name == o["kind"]?.jsonPrimitive?.contentOrNull } ?: AppNotes.Kind.fact
            notes.add(pkg, text, kind) is AppNotes.Outcome.Saved
        }
    }

    companion object { const val MAX_REFLECTED = 3 }
}

/**
 * Per conversation: the labelled taps since the last `app_launch` in this turn, as a route from the
 * app's start page. Conservative: typing, coordinate-only or long taps, other keys, leaving the app,
 * a label that is long, has digits or names something from the user's request end the route. Pure.
 */
class RouteTracker {
    class End(val pkg: String?, val success: Boolean, val route: Pair<String, List<String>>?)

    private class Turn {
        var prompt = ""
        /** The app this turn last worked in. */
        var pkg: String? = null
        /** Route since app_launch of [pkg]; null = none or broken. */
        var steps: MutableList<String>? = null
        var failed = false
        val recorded = HashSet<List<String>>()
    }

    private val turns = HashMap<String, Turn>()
    private fun turn(sessionId: String) = turns.getOrPut(sessionId.substringBefore('/')) { Turn() }

    fun newTurn(sessionId: String, prompt: String) = synchronized(turns) {
        turns[sessionId.substringBefore('/')] = Turn().also { it.prompt = prompt }
    }

    fun observe(sessionId: String, tool: String, args: JsonObject, ok: Boolean, text: String, outcome: ActionOutcome?, errorCode: String?) = synchronized(turns) {
        val t = turn(sessionId)
        if (errorCode == "loop_guard" || text.contains(LOOP_STOP)) { t.failed = true; t.steps = null; return }
        if (errorCode == "user_took_over") { t.steps = null; return }
        SWITCHED.find(text)?.groupValues?.get(1)?.let { p -> if (p != t.pkg) { t.pkg = p; t.steps = null } }
        val steps = t.steps
        when (tool) {
            "app_launch" -> if (ok) {
                t.pkg = args.str("package")
                t.steps = if (args.str("activity") == null) mutableListOf() else null
            }
            "app_open_link", "app_send_intent", "share_to_app" -> if (ok) { t.pkg = args.str("package") ?: t.pkg; t.steps = null }
            "screen_tap" -> {
                if (steps == null || !ok || outcome != ActionOutcome.Effective) return
                val label = TAPPED.find(text)?.groupValues?.get(1)?.trim()
                when {
                    args.str("kind") == "long" || (args.str("node") == null && args.str("text") == null) -> t.steps = null
                    label != null && label in BACK -> steps.removeLastOrNull()
                    label == null || !usable(label, t.prompt) -> t.steps = null
                    else -> { steps += label; if (steps.size > MAX_STEPS) t.steps = null }
                }
            }
            "screen_key" -> if (steps != null && ok) { if (args.str("key") == "back") steps.removeLastOrNull() else t.steps = null }
            "screen_type", "browser_type" -> if (steps != null) t.steps = null
        }
    }

    /** The current route when it is worth keeping (two steps or more) and not recorded yet. */
    fun route(sessionId: String): Pair<String, List<String>>? = synchronized(turns) {
        val t = turns[sessionId.substringBefore('/')] ?: return null
        val pkg = t.pkg ?: return null
        val steps = t.steps?.toList()?.takeIf { it.size >= 2 && it !in t.recorded } ?: return null
        pkg to steps
    }

    fun recorded(sessionId: String, steps: List<String>) = synchronized(turns) { turn(sessionId).recorded += steps }

    /** The turn is over ([ok]: it ended normally); a later turn starts without a route. */
    fun finish(sessionId: String, ok: Boolean): End = synchronized(turns) {
        val t = turn(sessionId)
        val success = ok && !t.failed
        val end = End(t.pkg, success, if (success) route(sessionId) else null)
        turns[sessionId.substringBefore('/')] = Turn().also { it.pkg = t.pkg }
        end
    }

    companion object {
        const val MAX_STEPS = 6
        const val MAX_LABEL = 10
        private const val LOOP_STOP = "[Loop guard] Stopped"
        private val TAPPED = Regex("Tapped(?: at -?\\d+,-?\\d+)? \\[([^\\]\\n]+)]")
        private val SWITCHED = Regex("Effect: switched to .* \\(([\\w.]+)\\)\\.")
        private val BACK = setOf("返回", "Back", "back", "Navigate up", "向上导航")
        /** Navigation labels that may appear in a request without making the route task-specific. */
        private val COMMON = setOf("我", "我的", "首页", "消息", "发现", "通讯录", "设置", "搜索", "更多", "订单", "全部订单", "购物车",
            "收藏", "关注", "推荐", "钱包", "服务", "个人中心", "通用", "隐私", "账号与安全", "历史记录", "浏览记录")

        /** A UI label fit for a route: short, no digits, no personal data, not named in the user's request. */
        fun usable(label: String, prompt: String): Boolean =
            label.isNotEmpty() && label.length <= MAX_LABEL && label != "element" && label.none { it.isDigit() } && AppNotes.problem(label) == null &&
                (label in COMMON || !prompt.contains(label, ignoreCase = true))
    }
}
