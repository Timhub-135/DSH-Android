package io.github.timhub135.dsh.core.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *  4. clipboard + ACTION_PASTE on the field (any language; the clipboard keeps the text).
 */
@Singleton
class TextInput @Inject constructor(@ApplicationContext private val context: Context) {

    /** How text got in, plus what the field shows afterwards (null when it cannot be read back). */
    class Typed(val method: String, val fieldText: String?)

    /**
     * Returns how the text was entered, or null when every path failed. Before each fallback the field
     * is re-read: if it changed at all, an earlier path did commit something (masked/reformatting
     * fields fail a strict check), so we stop rather than type the text a second time.
     */
    suspend fun type(svc: DshAccessibilityService, target: AccessibilityNodeInfo?, text: String, append: Boolean): Typed? {
        // The input connection and the keyboard belong to the user's focused field (the only screen
        // this version controls), so they are safe to use for every target.
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
        if (svc.commitViaA11yInput(text, replace = !append)) {
            if (verify(target, text, trustWithoutNode = true)) return done("a11y_input" + unverified(target))
            if (changed()) return done("a11y_input (partial: the field reformatted the text; check it)")
        }
        if (DshInputMethodService.isCurrent(context) && DshInputMethodService.instance?.commit(text, replace = !append) == true) {
            if (verify(target, text, trustWithoutNode = true)) return done("dsh_ime" + unverified(target))
            if (changed()) return done("dsh_ime (partial: the field reformatted the text; check it)")
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
