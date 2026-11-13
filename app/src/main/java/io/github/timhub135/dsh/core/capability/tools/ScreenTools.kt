package io.github.timhub135.dsh.core.capability.tools

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.ActionGuard
import io.github.timhub135.dsh.core.capability.ActionOutcome
import io.github.timhub135.dsh.core.capability.AppKnowledge
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.Frame
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.LimitNotice
import io.github.timhub135.dsh.core.capability.OcrLine
import io.github.timhub135.dsh.core.capability.OutwardAction
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.SchemaBuilder
import io.github.timhub135.dsh.core.capability.ScreenDiff
import io.github.timhub135.dsh.core.capability.ScreenOcr
import io.github.timhub135.dsh.core.capability.StableIds
import io.github.timhub135.dsh.core.capability.TextInput
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolDescriptor
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.core.session.SessionStore
import io.github.timhub135.dsh.core.session.TakeoverAnswer
import io.github.timhub135.dsh.service.DshAccessibilityService
import io.github.timhub135.dsh.service.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * Reading and operating the user's screen through the accessibility service. Element ids are stable
 * across reads, reads after an action only send what changed, and every action reports its measured
 * effect. This version has no background screen: every screen_* call acts where the user is.
 */
@Singleton
class ScreenTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textInput: TextInput,
    private val sessions: dagger.Lazy<SessionStore>,
    private val ocr: ScreenOcr,
    private val knowledge: AppKnowledge,
    private val guard: ActionGuard,
) : ToolSet {
    /** What the model has seen of the screen of one conversation. */
    private class View {
        val ids = StableIds()
        /** The last read sent to the model: the base of the next diff. */
        @Volatile var shown: Frame? = null
        @Volatile var reads = 0
        @Volatile var ocr: List<OcrLine> = emptyList()
        @Volatile var lastPackage: String? = null
    }
    private val views = ConcurrentHashMap<String, View>()
    private fun view(call: CallContext) = views.getOrPut(call.sessionId.substringBefore('/')) { View() }

    /** A live snapshot with stable ids assigned. */
    private class Read(val snap: ScreenSnapshot, val frame: Frame)

    private fun svc() = DshAccessibilityService.instance ?: throw PermissionMissingException("Accessibility service (DSH screen control)")

    private fun read(call: CallContext): Read {
        val snap = svc().snapshot(Display.DEFAULT_DISPLAY)
        return Read(snap, view(call).ids.assign(snap.packageName, snap.elements))
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) { if (cur == null || cur!!.isClickable) return cur; cur = cur!!.parent }
        return null
    }

    private suspend fun settle() = delay(400)

    private fun metrics(): DisplayMetrics = DisplayMetrics().also { m ->
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (d != null) { @Suppress("DEPRECATION") d.getRealMetrics(m) } else m.setTo(context.resources.displayMetrics)
    }

    /** The user-facing name of a package, for result texts. */
    private fun label(pkg: String): String =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun header(f: Frame, g: Int) = metrics().let {
        "App: ${f.packageName} · read #$g · user's screen ${it.widthPixels}x${it.heightPixels} px"
    }

    private fun body(f: Frame, max: Int): String {
        val text = f.render().ifBlank { return "(no readable elements; try screen_capture, or screen_read with ocr: true)" }
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it > 0 } ?: max
        val hidden = text.substring(cut).count { it == '\n' }
        return text.substring(0, cut) + "\n…(truncated: $hidden more elements; use screen_scroll or screen_read for the full list)"
    }

    /**
     * Sends [r] in full and makes it the base of the next diff. Adds recognized text when the tree
     * has too little ([forceOcr]: always), and app knowledge the first time the conversation is in an app.
     */
    private suspend fun full(call: CallContext, r: Read, max: Int, forceOcr: Boolean): String {
        val v = view(call)
        val g = ++v.reads
        if (r.frame.packageName != null || r.frame.elements.isNotEmpty()) v.shown = r.frame
        return "${header(r.frame, g)}\n${body(r.frame, max)}" + ocrSection(r.frame, v, forceOcr) + visit(call, r.frame)
    }

    /** Text recognition fallback (§3.3); also resets the `t` ids when not run. */
    private suspend fun ocrSection(f: Frame, v: View, force: Boolean): String {
        if (!force && f.readableCount >= ScreenOcr.SPARSE) { v.ocr = emptyList(); return "" }
        val bmp = screenshot()
        if (bmp == null) { v.ocr = emptyList(); return if (force) "\n(text recognition unavailable: no screenshot of this screen)" else "" }
        if (isBlank(bmp)) { v.ocr = emptyList(); return if (force) "\n(text recognition skipped: the page is protected)" else "" }
        val lines = runCatching { ocr.read(bmp) }.getOrElse { v.ocr = emptyList(); return "\n(text recognition failed: ${it.message})" }
        v.ocr = lines
        if (lines.isEmpty()) return "\n-- text recognized from the screenshot: none --"
        return "\n-- text recognized from the screenshot (tap with node \"t<n>\") --\n" + ScreenOcr.render(lines)
    }

    /** First time in an app: its usage knowledge, if any. */
    private suspend fun visit(call: CallContext, f: Frame): String {
        val v = view(call)
        val pkg = f.packageName ?: return ""
        if (pkg == v.lastPackage) return ""
        v.lastPackage = pkg
        return knowledge.firstVisitNotice(call.sessionId, pkg)?.let { "\n\n$it" } ?: ""
    }

    private suspend fun screenshot(): Bitmap? =
        DshAccessibilityService.instance?.let { capture(it, Display.DEFAULT_DISPLAY).bitmap }

    /** Accessibility screenshot, retried once when the previous one was less than a second ago. */
    private suspend fun capture(a11y: DshAccessibilityService, d: Int): DshAccessibilityService.Capture {
        val shot = a11y.capture(d)
        if (shot.bitmap != null || shot.errorCode != ERROR_INTERVAL_TOO_SHORT) return shot
        delay(1_100)
        return a11y.capture(d)
    }

    /**
     * The read after an action: effects (no change, rate-limit message, another app), then only the
     * differences from the read the model last saw, unless the page changed.
     */
    private suspend fun after(call: CallContext, before: Frame?, done: String): ToolResult {
        settle()
        if (DshAccessibilityService.instance == null) return ToolResult.text(done)
        val r = read(call)
        val v = view(call)
        // A custom-drawn screen has (almost) no tree: whether it changed is judged by its recognized text.
        val sparse = r.frame.readableCount < ScreenOcr.SPARSE
        val textBefore = v.ocr.map { it.text }
        val shown = v.shown
        // Diffs leave out the status bar and shade: when the shade opened or closed, the full read shows it.
        val shade = before != null && before.systemUiChanged(r.frame)
        val screen = if (shown != null && !sparse && !shade) {
            val diff = ScreenDiff.of(shown, r.frame)
            if (diff.pageChanged) null
            else {
                val g = ++v.reads
                v.shown = r.frame
                val stale = if (v.ocr.isNotEmpty()) "\nText ids (t…) from earlier reads are no longer valid; use screen_read with ocr: true for new ones." else ""
                v.ocr = emptyList()
                (if (diff.empty) "Screen unchanged since read #${g - 1} (read #$g)."
                else "Screen now (read #$g) — changes since read #${g - 1}; everything else is unchanged:\n" + diff.render(shown, r.frame).trimEnd()) +
                    stale + visit(call, r.frame)
            }
        } else null
        val screenText = screen ?: "Screen now — ${full(call, r, 6000, forceOcr = false)}"
        val noEffect = before != null && before.fingerprint == r.frame.fingerprint && !shade &&
            (!sparse || (textBefore.isNotEmpty() && textBefore == v.ocr.map { it.text }))
        val effects = buildList {
            if (noEffect) add("Effect: nothing changed on screen.")
            LimitNotice.find(before, r.frame)?.let {
                add("Effect: the app says \"$it\" — it is limiting these actions. Stop repeating them and tell the user.")
            }
            if (before != null && r.frame.packageName != before.packageName && r.frame.packageName != null)
                add("Effect: switched to ${label(r.frame.packageName)} (${r.frame.packageName}).")
        }
        return ToolResult.text((listOf(done) + effects + screenText).joinToString("\n"))
            .withOutcome(if (noEffect) ActionOutcome.NoEffect else ActionOutcome.Effective, r.frame.fingerprint)
    }

    private companion object {
        const val ERROR_INTERVAL_TOO_SHORT = 3 // AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        const val ERROR_SECURE_WINDOW = 6 /* AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34) */
        const val KINDS = "login, captcha, payment, secure_page, choose, other"
        fun screenshotError(code: Int?) = when (code) {
            null -> "screenshots need Android 11+"
            1 -> "internal error"; 2 -> "accessibility screenshots are not enabled for DSH (re-enable the DSH accessibility service)"
            ERROR_INTERVAL_TOO_SHORT -> "called too often; wait a second"
            4 -> "invalid display"; 5 -> "invalid window"
            else -> "error code $code"
        }

        /**
         * Before Android 14 a FLAG_SECURE window is captured as pure black. Dark UIs are near-black but not
         * uniformly zero, so require every sample on a fine grid to be (almost) exactly black.
         */
        fun isBlank(bmp: Bitmap): Boolean {
            for (y in 0 until 32) for (x in 0 until 32) {
                val p = bmp.getPixel(x * (bmp.width - 1) / 31, y * (bmp.height - 1) / 31)
                if ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff) > 6) return false
            }
            return true
        }
    }

    /** The `screen` argument: this version has only the user's screen. */
    private fun rejectNonMainScreen(args: JsonObject): ToolResult? {
        val s = args.str("screen")
        return if (s != null && s != "main") ToolResult.error("not_supported",
            "Only the user's screen is supported in this version (there is no background screen); pass screen: \"main\" or leave it out.")
        else null
    }

    /** A protected (FLAG_SECURE) page cannot be seen, so it is not operated blind: it goes to the user. */
    private suspend fun protectedScreen(call: CallContext, toolName: String): ToolResult {
        val pkg = DshAccessibilityService.instance?.snapshot(Display.DEFAULT_DISPLAY)?.packageName ?: "this app"
        return takeover(call, toolName, "${label(pkg)} 的当前页面禁止截图（支付、密码等安全页面），需要你在手机上完成这一步。", "secure_page",
            "The screen of $pkg is protected (screenshots are blocked)")
    }

    /**
     * Hands a step to the user (§2): card 我来处理 / 跳过; then 处理好了 (read again) / 我自己做完了 /
     * 不做了. Everything happens on the user's own screen.
     */
    private suspend fun takeover(call: CallContext, toolName: String, reason: String, kind: String, why: String = "This step needs the user"): ToolResult {
        val sid = call.sessionId
        if (call.unattended || sid.isEmpty()) return ToolResult.error("needs_user",
            "$why ($reason). Automations cannot hand steps to the user; stop and report this.")
        val answer = call.waitFor("handle it on the phone") {
            sessions.get().requestTakeover(sid, toolName, reason, kind) { true }
        }
        return when (answer) {
            TakeoverAnswer.Skip, TakeoverAnswer.Start -> ToolResult.text("$why; the user skipped it (did not do it). Continue without it if you can; otherwise stop and summarize.")
            TakeoverAnswer.Abort -> ToolResult.error("user_declined", "$why; the user chose not to do it. End this step and summarize.")
            TakeoverAnswer.DoneByUser -> ToolResult.text("$why; the user finished the task themselves on their screen. " +
                "Do not operate any more: only read the screen to verify, then report.")
            TakeoverAnswer.Handled -> {
                settle()
                val r = DshAccessibilityService.instance?.let { read(call) }
                val screen = r?.let { "\nScreen now — ${full(call, it, 6000, forceOcr = false)}" } ?: ""
                ToolResult.text("$why; the user handled it and tapped 处理好了. Continue from the screen below; do not ask them to repeat it.$screen")
            }
        }
    }

    /** An element (or recognized text line) the model referred to by id or text. */
    private class Hit(val node: AccessibilityNodeInfo?, val x: Int, val y: Int, val label: String?, val note: String? = null)

    /** Resolves `node` (e…, t…, or a bare number) / `text` against the fresh read [r]. Null when neither was given. */
    private fun locate(call: CallContext, args: JsonObject, r: Read): Hit? {
        args.str("node")?.takeIf { it.isNotBlank() }?.let { raw ->
            val id = StableIds.normalize(raw)
            val v = view(call)
            if (id.startsWith("t")) {
                val line = id.drop(1).toIntOrNull()?.let { v.ocr.getOrNull(it - 1) }
                    ?: throw InvalidArgsException("Unknown text id $raw; call screen_read again (text ids come from text recognition)")
                return Hit(null, line.x, line.y, line.text)
            }
            return when (val l = v.ids.resolve(id, r.frame)) {
                is StableIds.Lookup.Found -> hit(r, l.index, null)
                is StableIds.Lookup.Relocated -> hit(r, l.index, "$id was gone; used the matching element now listed as ${l.newId}.")
                StableIds.Lookup.Gone -> throw InvalidArgsException("Element $id is no longer on screen (it changed or disappeared); call screen_read and use a current id.")
                StableIds.Lookup.Unknown -> throw InvalidArgsException("Unknown element id $raw; call screen_read first.")
            }
        }
        val text = args.str("text")?.takeIf { it.isNotBlank() } ?: return null
        val els = r.frame.elements
        val i = els.indexOfFirst { it.text == text || it.desc == text }.takeIf { it >= 0 }
            ?: els.indexOfFirst { (it.label ?: "").contains(text, ignoreCase = true) }.takeIf { it >= 0 }
        if (i != null) return hit(r, i, null)
        val line = view(call).ocr.firstOrNull { it.text.contains(text, ignoreCase = true) }
            ?: throw InvalidArgsException("No element with text '$text'; call screen_read")
        return Hit(null, line.x, line.y, line.text)
    }

    private fun hit(r: Read, i: Int, note: String?): Hit {
        val e = r.frame.elements[i]
        return Hit(r.snap.nodes[i], e.x, e.y, e.label, note)
    }

    /** The labelled element nearest to a coordinate tap (for pacing), within 80 px. */
    private fun labelAt(r: Read, x: Int, y: Int): String? = r.frame.elements.filter { it.label != null }
        .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        ?.takeIf { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= 80 }?.label

    /** A tap that reaches other people: an outward label on something tappable (a plain text label is not). */
    private fun outward(hit: Hit) = OutwardAction.matches(hit.label) && (hit.node == null || clickable(hit.node) != null)

    /**
     * Outward actions (send, post, follow…) in one app are spaced like a person would (§5). Waits
     * before the tap and returns the model-facing note, or null.
     */
    private suspend fun pace(call: CallContext, pkg: String?, hit: Hit): String? {
        if (pkg == null || !outward(hit)) return null
        val budget = call.deadlineMs - SystemClock.elapsedRealtime() - 15_000
        val wait = guard.pacing.waitBefore(pkg, SystemClock.elapsedRealtime()).coerceAtMost(budget.coerceAtLeast(0))
        if (wait < 1_000) return null
        delay(wait)
        return "Waited ${wait / 1000} s before this tap so the app does not take it for automation."
    }

    /** Every screen tool takes `screen`; only the user's screen exists in this version. */
    private abstract inner class ScreenTool(name: String, risk: Risk, needsUnlock: Boolean, description: String, schema: SchemaBuilder.() -> Unit) :
        Tool(name, ToolGroup.Screen, risk, needsUnlock, description, {
            schema()
            screen()
        }) {
        abstract suspend fun run(args: JsonObject, call: CallContext): ToolResult

        override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult =
            rejectNonMainScreen(args) ?: run(args, call)
    }

    override val tools = listOf(
        object : ScreenTool("screen_read", Risk.read, true,
            "Read what is on screen as a list of UI elements: [e12] Type \"text\" #resource-id {flags} @x,y (center, in screen pixels). " +
                "Ids stay the same across reads while the element is unchanged; use them with screen_tap/screen_type/screen_scroll. " +
                "When the tree has little text (custom-drawn apps, games) recognized text lines [t1]… are added, tappable the same way. " +
                "{wheel} marks a picker wheel: set it with screen_set_wheel. Prefer this over screenshots.", {
                bool("ocr", "Also recognize text in a screenshot (when elements are missing from the list)")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val r = read(call)
                val v = view(call)
                val same = v.shown?.fingerprint == r.frame.fingerprint
                val textBefore = v.ocr.map { it.text }
                val text = full(call, r, 12000, forceOcr = args.bool("ocr") == true)
                val unchanged = same && textBefore == v.ocr.map { it.text }
                return ToolResult.text(text).withOutcome(if (unchanged) ActionOutcome.ReadUnchanged else ActionOutcome.ReadChanged, r.frame.fingerprint)
            }
        },
        object : ScreenTool("screen_capture", Risk.read, true,
            "Screenshot of the screen as an image. Use only when screen_read is not enough (images, canvas, games). " +
                "The image is downscaled; the caption gives the factor to convert image pixels to the screen pixels that screen_tap/screen_swipe take.", {}) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val shot = capture(svc(), Display.DEFAULT_DISPLAY)
                if (shot.errorCode == ERROR_SECURE_WINDOW) return protectedScreen(call, name)
                val bmp = shot.bitmap ?: throw IllegalStateException("Screenshot failed: ${screenshotError(shot.errorCode)}")
                val interactive = context.getSystemService(PowerManager::class.java).isInteractive
                if (Build.VERSION.SDK_INT < 34 && interactive && isBlank(bmp)) return protectedScreen(call, name)
                val img = Images.encode(bmp)
                val k = bmp.width.toFloat() / img.width
                val caption = (if (img.width == bmp.width) "image ${img.width}x${img.height} = screen pixels"
                    else "image ${img.width}x${img.height} = screen ${bmp.width}x${bmp.height} scaled ${"%.3f".format(1 / k)}. " +
                        "screen_tap/screen_swipe take SCREEN pixels: multiply image coordinates by ${"%.3f".format(k)}.") + " (user's screen)"
                return ToolResult.image(Images.MIME, Base64.encodeToString(img.bytes, Base64.NO_WRAP), caption)
            }
        },
        object : ScreenTool("screen_tap", Risk.write, true,
            "Tap an element (id from the latest screen read: e12, or a recognized text line: t3), visible text, or screen-pixel coordinates. " +
                "Returns the effect and what changed on screen.", {
                string("node", "Element id from the latest screen read (e12) or recognized text id (t3)")
                string("text", "Visible text/description of the element to tap")
                number("x", "X in screen pixels"); number("y", "Y in screen pixels")
                string("kind", "Tap kind", enum = listOf("tap", "long"))
            }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val long = args.str("kind") == "long"
                val px = args.int("x"); val py = args.int("y")
                fun target(r: Read) = locate(call, args, r) ?: run {
                    val x = px ?: throw InvalidArgsException("Give node, text, or x/y")
                    val y = py ?: throw InvalidArgsException("missing y")
                    Hit(null, x, y, labelAt(r, x, y))
                }
                var r = read(call)
                var hit = target(r)
                // The wait can be long: find the target again on the screen as it is after it.
                val paced = pace(call, r.frame.packageName, hit)?.also { r = read(call); hit = target(r) }
                val c = hit.node?.takeIf { it.refresh() }?.let(::clickable)
                var tapped = true
                val done = if (c != null && c.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Tapped [${hit.label ?: "element"}]"
                } else {
                    val (x, y) = hit.node?.let { n -> Rect().also(n::getBoundsInScreen).let { it.centerX() to it.centerY() } } ?: (hit.x to hit.y)
                    tapped = tapAt(x.toFloat(), y.toFloat(), long)
                    if (tapped) "Tapped at $x,$y" + (hit.label?.let { " [$it]" } ?: "") else "Tap was cancelled"
                }
                if (tapped && outward(hit)) r.frame.packageName?.let { guard.pacing.noteDone(it, SystemClock.elapsedRealtime()) }
                return after(call, r.frame, listOfNotNull(paced, hit.note, done).joinToString("\n"))
            }

            private suspend fun tapAt(x: Float, y: Float, long: Boolean): Boolean {
                val a11y = DshAccessibilityService.instance ?: throw PermissionMissingException("Accessibility service (DSH screen control)")
                return a11y.tap(x, y, if (long) 700 else 60, Display.DEFAULT_DISPLAY)
            }
        },
        object : ScreenTool("screen_type", Risk.write, true,
            "Type text into an editable field (element id, or the focused field). Replaces the field's content unless append=true. " +
                "submit=true then presses the keyboard's Enter/Search key. Works in WebView/Flutter fields too (falls back to the DSH keyboard or paste).", {
                string("text", "Text to enter", required = true)
                string("node", "Editable element id from the latest screen read (default: focused field)")
                bool("append", "Append instead of replacing")
                bool("submit", "Press Enter/Search afterwards (for search boxes without a button)")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val r = read(call)
                val hit = locate(call, args.only("node"), r)
                val text = args.reqStr("text")
                val append = args.bool("append") == true
                val focused = if (hit == null) focusedInput(r) else null
                // Without a field id, typing goes to whatever holds input focus: with no focused field and no
                // keyboard showing, the text would go nowhere (or be pasted somewhere), so do not try.
                if (hit == null && focused?.isEditable != true && !svc().imeShown(Display.DEFAULT_DISPLAY)) return ToolResult.error("no_focus",
                    "No input field has focus on this screen and no keyboard is showing, so nothing was typed. " +
                        "Tap the field first (screen_tap), or pass its id as node, then call screen_type again.")
                val target = hit?.node ?: focused
                val before = target?.takeUnless { it.isPassword }?.let { TextInput.shownText(it) }
                val out = textInput.type(svc(), target, text, append)
                    ?: return ToolResult.error("input_failed", if (target == null) "No focused input field; tap the field first, then call screen_type again."
                        else "The field did not accept text by any method (set text, keyboard, paste). Tap the field and retry, or ask the user to type it.")
                // Read back the field that received the text (the focused one when no field was known beforehand).
                val shown = out.fieldText ?: if (target == null) focusedInput(read(call))?.takeIf { it.isEditable && !it.isPassword }?.let { TextInput.shownText(it) } else null
                val expected = if (append) (before.orEmpty() + text) else text
                val readBack = shown?.let { s ->
                    "; the field now shows: \"${s.take(300)}\"" + if (TextInput.differs(expected, s))
                        " — this differs from the requested text (the field may have rejected, reformatted or truncated it, or kept old text); check and correct it" else ""
                } ?: ""
                val submitted = if (args.bool("submit") == true) " and pressed " + (pressEnter(false) ?: "Enter (failed: no method worked)") else ""
                return after(call, r.frame, listOfNotNull(hit?.note, "Entered text (${out.method})$submitted$readBack").joinToString("\n"))
            }

            private fun focusedInput(r: Read): AccessibilityNodeInfo? =
                svc().findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: r.snap.nodes.firstOrNull { it.isEditable && it.isFocused }
        },
        object : ScreenTool("screen_set_wheel", Risk.write, true,
            "Set a wheel picker to a value in one call (time/date/number spinners, NumberPicker, the scrolling columns in alarm, " +
                "train/flight booking and similar apps) instead of swiping it step by step. Give the wheel's id (marked {wheel} in screen_read) " +
                "or the id of the value it shows now (e12, or t3 for recognized text), and the value as the wheel writes it (\"07\", \"40\", \"下午\", \"2026年\"). " +
                "One column per call: for hour and minute call it twice. Uses the picker's text field, scroll actions or slow drags, reading the value back after each move. " +
                "Returns the value now shown (with the previous value, attempts and method), or wheel_at_end / wheel_unreadable / wheel_not_moving / wheel_not_reached with what it shows.", {
                string("node", "Id of the wheel or of the value it currently shows (e12 / t3)", required = true)
                string("value", "Target value as shown on the wheel", required = true)
            }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val value = args.reqStr("value").trim().ifEmpty { throw InvalidArgsException("value is empty") }
                val r = read(call)
                val hit = locate(call, args.only("node"), r) ?: throw InvalidArgsException("Give node: the id of the wheel or of the value it shows (call screen_read first)")
                val (x, y) = hit.node?.let { n -> Rect().also(n::getBoundsInScreen).let { it.centerX() to it.centerY() } } ?: (hit.x to hit.y)
                val m = metrics()
                val setter = WheelSetter(svc(), Display.DEFAULT_DISPLAY, m.heightPixels, m.density) {
                    screenshot()?.takeUnless { isBlank(it) }?.let { b -> runCatching { ocr.read(b) }.getOrNull() }
                }
                return when (val out = setter.set(hit.node, x, y, value)) {
                    is WheelSetter.Done -> after(call, r.frame, listOfNotNull(hit.note,
                        "Wheel set: value \"${out.value}\" (from \"${out.from}\", ${out.attempts} attempts, method ${out.method})").joinToString("\n"))
                    is WheelSetter.Failed -> ToolResult.error(out.code, listOfNotNull(hit.note, out.message).joinToString("\n"))
                }
            }
        },
        object : ScreenTool("screen_swipe", Risk.write, true, "Swipe/drag from (x1,y1) to (x2,y2), in screen pixels.", {
            number("x1", "Start X (screen pixels)", required = true); number("y1", "Start Y (screen pixels)", required = true)
            number("x2", "End X (screen pixels)", required = true); number("y2", "End Y (screen pixels)", required = true)
            int("durationMs", "Default 300")
        }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                fun i(k: String) = args.int(k) ?: throw InvalidArgsException("missing $k")
                val before = read(call).frame
                svc().swipe(i("x1").toFloat(), i("y1").toFloat(), i("x2").toFloat(), i("y2").toFloat(), (args.int("durationMs") ?: 300).toLong().coerceIn(50, 5000), Display.DEFAULT_DISPLAY)
                return after(call, before, "Swiped")
            }
        },
        object : ScreenTool("screen_scroll", Risk.write, true, "Scroll the screen or a scrollable element.", {
            string("direction", "Direction", required = true, enum = listOf("down", "up", "left", "right"))
            string("node", "Scrollable element id (optional)")
        }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val dir = args.reqStr("direction")
                val r = read(call)
                val n = locate(call, args.only("node"), r)?.node
                if (n != null && n.isScrollable) {
                    n.performAction(if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                } else {
                    val m = metrics()
                    val cx = m.widthPixels / 2f; val cy = m.heightPixels / 2f
                    val dy = m.heightPixels * 0.3f; val dx = m.widthPixels * 0.3f
                    when (dir) {
                        "down" -> svc().swipe(cx, cy + dy, cx, cy - dy, 350, Display.DEFAULT_DISPLAY)
                        "up" -> svc().swipe(cx, cy - dy, cx, cy + dy, 350, Display.DEFAULT_DISPLAY)
                        "right" -> svc().swipe(cx + dx, cy, cx - dx, cy, 350, Display.DEFAULT_DISPLAY)
                        else -> svc().swipe(cx - dx, cy, cx + dx, cy, 350, Display.DEFAULT_DISPLAY)
                    }
                }
                return after(call, r.frame, "Scrolled $dir")
            }
        },
        object : ScreenTool("screen_key", Risk.write, true,
            "Press a key. enter/search = the keyboard's action key in the focused field (submit a search box or a form); delete = backspace; " +
                "back/home/recents; notifications/quick_settings open the shade; lock_screen turns the screen off.", {
            string("key", "Key", required = true, enum = listOf("enter", "search", "delete", "back", "home", "recents", "notifications", "quick_settings", "lock_screen"))
        }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val key = args.reqStr("key")
                val r = read(call)
                val done: String = when (key) {
                    "enter", "search" -> pressEnter(key == "search") ?: return ToolResult.error("failed",
                        "Could not press $key: the focused field takes no editor action. Tap the on-screen search/send button instead (screen_read to find it).")
                    "delete" -> if (svc().deleteBackward()) "Deleted one character" else return ToolResult.error("failed", "No focused text field to delete from.")
                    else -> {
                        val global = when (key) {
                            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
                            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS; "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
                            else -> GLOBAL_ACTION_NOTIFICATIONS
                        }
                        if (svc().performGlobalAction(global)) "Pressed $key" else return ToolResult.error("failed", "Android refused '$key'.")
                    }
                }
                if (key == "lock_screen") return ToolResult.text(done)
                return after(call, r.frame, done)
            }
        },
        object : ScreenTool("screen_wait", Risk.read, true,
            "Wait until an element with this text appears (or disappears with gone=true), or until an app is in front, instead of sleeping and polling. " +
                "Returns the screen when the condition holds or after the timeout.", {
                string("text", "Visible text/description to wait for")
                string("package", "Wait until this app is in front")
                bool("gone", "Wait for the text to disappear instead")
                int("timeoutMs", "Default 10000, max 30000")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext): ToolResult {
                val text = args.str("text"); val pkg = args.str("package")
                if (text == null && pkg == null) throw InvalidArgsException("Give text and/or package")
                val gone = args.bool("gone") == true
                val until = SystemClock.elapsedRealtime() + (args.int("timeoutMs") ?: 10_000).coerceIn(500, 30_000)
                while (true) {
                    val snap = svc().snapshot(Display.DEFAULT_DISPLAY)
                    val hasText = text == null || snap.elements.any { e -> (e.text ?: "").contains(text, true) || (e.desc ?: "").contains(text, true) }
                    val ok = (pkg == null || snap.packageName == pkg) && (if (gone && text != null) !hasText else hasText)
                    if (ok || SystemClock.elapsedRealtime() > until) {
                        val r = Read(snap, view(call).ids.assign(snap.packageName, snap.elements))
                        return ToolResult.text("${if (ok) "Condition met" else "Timed out waiting"} — ${full(call, r, 6000, forceOcr = false)}")
                            .withOutcome(null, r.frame.fingerprint)
                    }
                    delay(500)
                }
            }
        },
        object : Tool("hand_to_user", ToolGroup.Screen, Risk.read, false,
            "Hand the current step to the user when only they can or should do it: signing in, a captcha, a payment, a protected page, " +
                "or a choice that is theirs to make. The user sees a card with the reason and does it on their screen. Returns whether " +
                "they handled it (then the screen is read again), finished the task themselves, skipped or stopped.", {
                string("reason", "What the user should do, in the user's language (shown to them), e.g. \"请登录你的淘宝账号\"", required = true)
                string("kind", "Kind of step", required = true, enum = KINDS.split(", "))
                screen()
            }, timeoutMs = ToolDescriptor.UNLOCK_TIMEOUT_MS) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult =
                rejectNonMainScreen(args) ?: takeover(call, name, args.reqStr("reason"), args.str("kind") ?: "other")
        },
    )

    private fun JsonObject.only(vararg keys: String) = JsonObject(filter { it.key in keys })

    /** Enter/Search in the focused field through the accessibility IME action. Returns how, or null. */
    private fun pressEnter(search: Boolean): String? {
        if (DshAccessibilityService.instance?.pressImeAction(search) == true) return if (search) "Search" else "Enter"
        return null
    }
}
