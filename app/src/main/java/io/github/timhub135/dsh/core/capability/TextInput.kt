package io.github.timhub135.dsh.core.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.platform.ShizukuShell
import io.github.timhub135.dsh.service.DshAccessibilityService
import io.github.timhub135.dsh.service.DshInputMethodService
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text entry with fallbacks (review §7/G7). ACTION_SET_TEXT is the cleanest path but fails, or
 * "succeeds" without effect, in WebView, Flutter, games and some custom editors, so each step is
 * verified by re-reading the node and the next path is tried:
 *  1. AccessibilityNodeInfo ACTION_SET_TEXT (verified);
 *  2. Android 13+: the accessibility service's own input connection (flagInputMethodEditor);
 *  3. DSH 输入法 ([DshInputMethodService]) when it is the current keyboard;
 *  4. Shizuku: `input text` for ASCII; otherwise switch to DSH 输入法 with `ime set`, commit, and
 *     switch back to the user's keyboard;
 *  5. clipboard + ACTION_PASTE on the field (any language; the clipboard keeps the text).
 */
@Singleton
class TextInput @Inject constructor(@ApplicationContext private val context: Context, private val shizuku: ShizukuShell) {

    /** How text got in, plus what the field shows afterwards (null when it cannot be read back). */
    class Typed(val method: String, val fieldText: String?)

    /**
     * Returns how the text was entered, or null when every path failed. Before each fallback the field
     * is re-read: if it changed at all, an earlier path did commit something (masked/reformatting
     * fields fail a strict check), so we stop rather than type the text a second time.
     */
    suspend fun type(svc: DshAccessibilityService, target: AccessibilityNodeInfo?, text: String, append: Boolean, displayId: Int = Display.DEFAULT_DISPLAY): Typed? {
        // The input connection, the keyboard and `ime set` belong to the user's focused field: on the
        // agent screen only paths bound to the target node (or to that display) are safe.
        val main = displayId == Display.DEFAULT_DISPLAY
        val before = target?.let(::shownText)
        val full = if (append) (before.orEmpty() + text) else text
        fun done(method: String) = Typed(method, readBack(target))
        fun changed(): Boolean {
            if (target == null || target.isPassword) return false
            target.refresh()
            return shownText(target) != before
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
            })) {
            if (verify(target, text)) return done("set_text")
            if (changed()) return done("set_text (partial: the field reformatted the text; check it)")
        }

        target?.let { focus(it) }
        if (main && svc.commitViaA11yInput(text, replace = !append)) {
            if (verify(target, text, trustWithoutNode = true)) return done("a11y_input" + unverified(target))
            if (changed()) return done("a11y_input (partial: the field reformatted the text; check it)")
        }
        if (main && DshInputMethodService.isCurrent(context) && DshInputMethodService.instance?.commit(text, replace = !append) == true) {
            if (verify(target, text, trustWithoutNode = true)) return done("dsh_ime" + unverified(target))
            if (changed()) return done("dsh_ime (partial: the field reformatted the text; check it)")
        }

        if (shizuku.available) {
            if (text.all { it.code in 0x20..0x7e }) {
                if (!append && target != null) clear(target)
                if (shizuku.exec((if (main) "input text " else "input -d $displayId text ") + shellQuote(text.replace(" ", "%s"))).code == 0) {
                    if (verify(target, text, trustWithoutNode = true)) return done("shizuku_input" + unverified(target))
                    if (changed()) return done("shizuku_input (partial: the field reformatted the text; check it)")
                }
            } else if (main && viaImeSwitch(text, !append)) {
                if (verify(target, text, trustWithoutNode = true)) return done("shizuku_ime" + unverified(target))
                if (changed()) return done("shizuku_ime (partial: the field reformatted the text; check it)")
            }
        }

        if (target != null) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val saved = runCatching { cm.primaryClip }.getOrNull()
            cm.setPrimaryClip(ClipData.newPlainText("dsh", text))
            if (!append) clear(target)
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE) && (verify(target, text) || changed())
            // Give the user's clipboard back (paste is synchronous for the target app by now).
            runCatching { if (saved != null) cm.setPrimaryClip(saved) else if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() }
            if (ok) return done("paste")
        }
        return null
    }

    private fun unverified(n: AccessibilityNodeInfo?) = if (n == null) " (no field to read back; verify on screen)" else ""

    private fun readBack(n: AccessibilityNodeInfo?): String? {
        if (n == null || n.isPassword) return null
        n.refresh()
        return shownText(n)
    }

    private fun focus(n: AccessibilityNodeInfo) {
        if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun clear(n: AccessibilityNodeInfo) {
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
        })
    }

    /** Temporarily selects DSH 输入法 through Shizuku, commits, then restores the user's keyboard. */
    private suspend fun viaImeSwitch(text: String, replace: Boolean): Boolean {
        val ours = DshInputMethodService.id(context)
        val previous = DshInputMethodService.currentId(context)
        if (shizuku.exec("ime enable $ours && ime set $ours").code != 0) return false
        try {
            repeat(20) {
                DshInputMethodService.instance?.let { if (it.commit(text, replace)) return true }
                delay(100)
            }
            return false
        } finally {
            if (previous != null && previous != ours) shizuku.exec("ime set ${shellQuote(previous)}")
        }
    }

    /** The field shows the text now (password fields cannot be read back: trust the action). */
    private suspend fun verify(n: AccessibilityNodeInfo?, expected: String, trustWithoutNode: Boolean = false): Boolean {
        if (n == null) return trustWithoutNode
        if (n.isPassword) return true
        // Editors update their text asynchronously; poll for ~600 ms before calling it a failure.
        repeat(4) {
            delay(150)
            n.refresh()
            val now = n.text?.toString()
            if (now != null && (now.contains(expected) || now.replace(Regex("\\s"), "").contains(expected.replace(Regex("\\s"), "")))) return true
        }
        return false
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        /** What the field contains: an empty field reports its hint as its text, which is not content. */
        fun shownText(n: AccessibilityNodeInfo): String? =
            if (n.isShowingHintText) "" else n.text?.toString()

        private val FORMATTING = Regex("[\\s\\-()]")

        /**
         * The field shows something other than [expected] (spaces, dashes and brackets that fields
         * insert while formatting are ignored): text rejected, truncated, or added to old content.
         */
        fun differs(expected: String, shown: String): Boolean =
            FORMATTING.replace(expected, "") != FORMATTING.replace(shown, "")
    }
}
