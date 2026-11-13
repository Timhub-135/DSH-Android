package io.github.timhub135.dsh.core.capability.tools

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.timhub135.dsh.core.capability.OcrLine
import io.github.timhub135.dsh.core.capability.WheelColumn
import io.github.timhub135.dsh.core.capability.WheelCycle
import io.github.timhub135.dsh.core.capability.WheelItem
import io.github.timhub135.dsh.core.capability.WheelPlan
import io.github.timhub135.dsh.core.capability.WheelValue
import io.github.timhub135.dsh.service.DshAccessibilityService
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

/**
 * Sets one wheel (picker column) to a value, using what the control supports, in order: an
 * editable field (set text, commit), accessibility scroll actions in batches, then slow drags sized
 * from the item pitch. Every move is judged by reading the value back once the wheel has settled
 * (the item on the wheel's centre line; text recognition when the tree has no labels). The
 * arithmetic lives in [WheelPlan] / [WheelColumn] (pure, unit-tested).
 */
internal class WheelSetter(
    private val svc: DshAccessibilityService,
    private val displayId: Int,
    private val screenHeight: Int,
    private val density: Float,
    /** Text lines recognized in a fresh screenshot of this screen, or null when there is none. */
    private val recognize: suspend () -> List<OcrLine>?,
) {
    sealed interface Outcome
    class Done(val value: String, val from: String, val attempts: Int, val method: String) : Outcome
    class Failed(val code: String, val message: String) : Outcome

    /** Where values are read from: the wheel's own nodes, nodes around the seed, or recognized text. */
    private interface Source { suspend fun column(): WheelColumn? }

    private var cur: String = ""
    private var col: WheelColumn? = null
    private var cycle: WheelCycle? = null
    private var ascending: Boolean? = null
    /** +1: forward / finger-up moves to the items below, as on almost every wheel; flipped when a read-back shows otherwise. */
    private var mapping = 1
    private var flipped = false
    private var attempts = 0
    private var everMoved = false
    private var atEnd = false
    /** A read-back found no value (the wheel left the screen, or recognition failed). */
    private var lostRead = false
    private val methods = LinkedHashSet<String>()

    suspend fun set(seed: AccessibilityNodeInfo?, seedX: Int, seedY: Int, target: String): Outcome {
        val seedBounds = seed?.let { Rect().also(it::getBoundsInScreen) }
        val half = ((seedBounds?.width() ?: 80) / 2).coerceIn(24, 120)
        val container = seed?.let(::wheelOf)
        if (container == null && seed != null && isWholePicker(seed)) return Failed("wheel_unreadable",
            "This is a whole date/time picker with several wheels; pass the id of one wheel (marked {wheel} in screen_read) or of the value it shows, and set each wheel separately.")
        val x = if (container != null && seedBounds != null && Rect().also(container::getBoundsInScreen).contains(seedX, seedY)) seedX
            else container?.let { Rect().also(it::getBoundsInScreen).centerX() } ?: seedX

        val source: Source = when {
            container != null -> {
                val box = Rect().also(container::getBoundsInScreen)
                object : Source {
                    override suspend fun column(): WheelColumn? {
                        container.refresh()
                        val items = DshAccessibilityService.textItems(container).map { it.first }.filter { abs(it.cx - x) <= maxOf(half, box.width() / 3) }
                        return WheelColumn(WheelPlan.dedupe(items), box.centerY())
                    }
                }
            }
            else -> treeSource(x, seedY, half) ?: ocrSource(x, seedY, half)
                ?: return Failed("wheel_unreadable", "Could not find a wheel at this element: no column of values around it in the screen tree or in the recognized text. " +
                    "Use screen_read with ocr: true to check, then screen_swipe on the wheel.")
        }

        settled(source) ?: return Failed("wheel_unreadable", "Could not read the value on the wheel's centre line (no item covers it and the neighbours do not tell). " +
            "Use screen_read with ocr: true or screen_capture to see it.")
        val from = cur
        cycle = col?.cycle; ascending = col?.ascending
        if (WheelValue.matches(cur, target)) return Done(cur, from, 0, "none (already set)")

        container?.let { c -> editable(c)?.let { if (byText(it, source, target)) return done(from) } }
        if (container != null && scrolls(container)) { if (byScroll(container, source, target)) return done(from) }
        if (!atEnd && !lostRead && byDrag(container, source, target, x)) return done(from)

        val shown = "The wheel now shows \"$cur\" (was \"$from\"; $attempts attempts${methods.takeIf { it.isNotEmpty() }?.let { ", tried ${it.joinToString("+")}" } ?: ""})."
        return when {
            lostRead -> Failed("wheel_unreadable", "$shown After a move its value could no longer be read; check with screen_read (ocr: true) or screen_capture.")
            atEnd -> Failed("wheel_at_end", "$shown It stopped moving at its end before reaching \"$target\": that value is probably not offered (out of range or another format). Check the options shown.")
            !everMoved -> Failed("wheel_not_moving", "$shown Neither ${if (container != null) "scroll actions nor " else ""}dragging moved it. Try screen_swipe on it, or tap it to type the value.")
            else -> Failed("wheel_not_reached", "$shown \"$target\" was not reached within the step limit; check the value format against the items shown, or continue with screen_swipe.")
        }
    }

    private fun done(from: String) = Done(cur, from, attempts, methods.joinToString("+"))

    // ── sources ──────────────────────────────────────────────────────────────

    /** Text nodes on screen that form a column through the seed (custom-drawn wheels that still label their items). */
    private fun treeSource(x: Int, y: Int, half: Int): Source? {
        fun items(): List<WheelItem> = svc.snapshot(displayId).nodes.mapNotNull { n ->
            val label = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val r = Rect().also(n::getBoundsInScreen)
            if (r.isEmpty || n.isEditable) null else WheelItem(label.take(40), r.left, r.top, r.right, r.bottom)
        }
        val first = WheelPlan.column(items(), x, y, half) ?: return null
        val center = (first.first().cy + first.last().cy) / 2
        return object : Source {
            override suspend fun column(): WheelColumn? =
                WheelPlan.column(items(), x, center, half, minItems = 2)?.let { WheelColumn(it, center) }
        }
    }

    /** Recognized text lines that form a column through the seed (fully custom-drawn wheels). */
    private suspend fun ocrSource(x: Int, y: Int, half: Int): Source? {
        suspend fun items(): List<WheelItem>? {
            val lines = recognize() ?: return null
            val points = lines.map { WheelItem(it.text, it.x - 1, it.y, it.x + 1, it.y) }
            val pitch = WheelPlan.column(points, x, y, half)?.let { WheelPlan.pitchOf(it) }?.toInt() ?: return points
            return lines.map { WheelItem.around(it.text, it.x, it.y, pitch) }
        }
        val first = WheelPlan.column(items() ?: return null, x, y, half) ?: return null
        val center = (first.first().cy + first.last().cy) / 2
        methods += "ocr"
        return object : Source {
            override suspend fun column(): WheelColumn? =
                items()?.let { WheelPlan.column(it, x, center, half, minItems = 2) }?.let { WheelColumn(it, center) }
        }
    }

    /** Reads until two consecutive reads ~350 ms apart agree (a wheel snaps after it moves), at most ~2.5 s. */
    private suspend fun settled(source: Source): String? {
        var c = source.column(); var v = c?.current()
        repeat(7) {
            delay(SETTLE_MS)
            val c2 = source.column(); val v2 = c2?.current()
            if (v2 == v) { if (v != null) { cur = v; col = c2 }; return v }
            c = c2; v = v2
        }
        if (v != null) { cur = v; col = c }
        return v
    }

    /**
     * Reads after a move of about [intended] steps and returns the steps it made (null: unknown),
     * learning a wrap when one shows.
     */
    private suspend fun moveResult(source: Source, before: String, intended: Int): Int? {
        attempts++
        if (settled(source) == null) { lostRead = true; return null }
        if (cycle == null) col?.cycle?.let { cycle = it }
        if (ascending == null) col?.ascending?.let { ascending = it }
        val a = WheelValue.number(before); val b = WheelValue.number(cur)
        if (cycle == null && a != null && b != null && col?.offsetOf(before) == null)
            WheelPlan.wrapSeen(a, b, intended, ascending)?.let { cycle = it }
        val m = WheelPlan.moved(before, cur, col, cycle, ascending)
        if (m != null && m != 0) everMoved = true
        return m
    }

    /** Direction correction after the first read-back that moved: the wheel runs the other way. */
    private fun correct(intended: Int, m: Int) {
        if (!flipped && m != 0 && intended != 0 && m.sign != intended.sign) { mapping = -mapping; flipped = true }
    }

    // ── a: editable field ────────────────────────────────────────────────────

    private fun editable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun find(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (n.isEditable && n.isVisibleToUser) return n
            if (depth >= 3) return null
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c, depth + 1)?.let { return it } }
            return null
        }
        return find(root, 0)
    }

    private suspend fun byText(field: AccessibilityNodeInfo, source: Source, target: String): Boolean {
        methods += "text"
        val before = cur
        // The picker takes a typed value when its field loses focus (or gets IME "done"), so it needs focus first.
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!setText(field, WheelValue.clean(target))) return false
        commit(field)
        moveResult(source, before, 0)
        val c = col
        if (c != null && !c.consistent()) {
            // The field shows the typed text but the picker kept its value (its neighbours did not move):
            // put the real value back so the field does not lie, and move the wheel another way.
            c.impliedByNeighbours()?.let { real -> field.performAction(AccessibilityNodeInfo.ACTION_FOCUS); setText(field, WheelValue.clean(real)); commit(field) }
            settled(source)
            return false
        }
        return WheelValue.matches(cur, target)
    }

    private fun setText(field: AccessibilityNodeInfo, text: String) = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    })

    private fun commit(field: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= 30) field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
    }

    // ── b: accessibility scroll actions ──────────────────────────────────────

    private fun scrolls(n: AccessibilityNodeInfo) = n.isScrollable ||
        n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }

    private suspend fun byScroll(container: AccessibilityNodeInfo, source: Source, target: String): Boolean {
        methods += "scroll"
        var perAction = 1.0
        var explore = 1
        var actions = 0
        var movedHere = false
        while (actions < MAX_ACTIONS && attempts < MAX_ATTEMPTS) {
            if (WheelValue.matches(cur, target)) return true
            val known = WheelPlan.steps(cur, target, col, cycle, ascending)
            if (known == 0) return false
            // A page-scrolling wheel (one action moves several items) cannot land on a near item: drag instead.
            if (known != null && perAction > 1.5 && abs(known) < perAction) return false
            val steps = known ?: (explore * EXPLORE_STEPS)
            val dir = steps.sign * mapping
            val k = WheelPlan.actions(steps, perAction)
            var done = 0
            for (i in 0 until k) {
                container.refresh()
                if (!container.performAction(if (dir > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
                done++
                delay(ACTION_GAP_MS)
            }
            actions += maxOf(done, 1)
            val before = cur
            val m = if (done > 0) moveResult(source, before, (done * perAction).toInt().coerceAtLeast(1) * steps.sign) else { attempts++; 0 }
            if (lostRead) return false
            if (m == 0 || (m == null && WheelValue.matches(cur, before))) {
                if (known == null && explore == 1) { explore = -1; continue }
                if (!movedHere) return false // the actions do nothing here: try dragging
                atEnd = true
                return false
            }
            movedHere = true
            if (m != null) {
                correct(steps, m)
                if (m.sign == steps.sign || flipped) perAction = (abs(m).toDouble() / maxOf(done, 1)).coerceAtLeast(0.2)
            }
        }
        return WheelValue.matches(cur, target)
    }

    // ── c: slow drags ────────────────────────────────────────────────────────

    private suspend fun byDrag(container: AccessibilityNodeInfo?, source: Source, target: String, x: Int): Boolean {
        val c0 = col ?: return false
        val pitch = c0.pitch ?: return false
        methods += "drag"
        val box = container?.let { Rect().also(it::getBoundsInScreen) }
        val top = box?.top ?: (c0.items.first().top)
        val bottom = box?.bottom ?: (c0.items.last().bottom)
        val slop = (8 * density).toInt()
        var gain = 1.0
        var explore = 1
        var rounds = 0
        while (rounds < MAX_DRAG_ROUNDS && attempts < MAX_ATTEMPTS) {
            if (WheelValue.matches(cur, target)) return true
            rounds++
            val known = WheelPlan.steps(cur, target, col, cycle, ascending)
            if (known == 0) return false
            val steps = known ?: (explore * maxOf(c0.items.size - 1, 2))
            val drags = WheelPlan.drags(steps * mapping, pitch / gain, x, top, bottom,
                (screenHeight * 0.06).toInt(), (screenHeight * 0.94).toInt(), slop)
            if (drags.isEmpty()) return false
            for (d in drags) svc.drag(d.x.toFloat(), d.fromY.toFloat(), d.toY.toFloat(), d.durationMs, HOLD_MS, displayId)
            val planned = drags.sumOf { abs(it.toY - it.fromY) - slop }.toDouble() / pitch
            val before = cur
            val m = moveResult(source, before, maxOf(planned.toInt(), 1) * steps.sign)
            if (lostRead) return false
            if (m == 0 || (m == null && WheelValue.matches(cur, before))) {
                if (known == null && explore == 1) { explore = -1; continue }
                if (everMoved) atEnd = true
                return false
            }
            if (m != null) {
                correct(steps, m)
                if (m.sign == steps.sign && planned > 0) gain = (abs(m) / planned).coerceIn(0.3, 3.0)
            }
        }
        return WheelValue.matches(cur, target)
    }

    // ── finding the wheel ────────────────────────────────────────────────────

    /** The wheel the seed belongs to: itself or a close ancestor that is a picker class or a scrollable holding one column. */
    private fun wheelOf(seed: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = seed
        repeat(5) {
            val cur = n ?: return null
            val cls = cur.className?.toString().orEmpty()
            if (cls.contains("TimePicker") || cls.contains("DatePicker")) return null
            val hint = cls.contains("NumberPicker") || cls.contains("Wheel", ignoreCase = true) || cls.endsWith("PickerView") || cls.contains("LoopView")
            if (hint) return cur
            if (scrolls(cur) && plausible(cur, seed)) return cur
            n = cur.parent
        }
        return null
    }

    /** A scrollable counts as the wheel only when it is about one column high: not the page scrolling around a custom wheel. */
    private fun plausible(container: AccessibilityNodeInfo, seed: AccessibilityNodeInfo): Boolean {
        val box = Rect().also(container::getBoundsInScreen)
        val s = Rect().also(seed::getBoundsInScreen)
        val items = DshAccessibilityService.textItems(container).map { it.first }
        val column = WheelPlan.column(items, s.centerX(), s.centerY(), maxOf(s.width() / 2, 24), minItems = 2) ?: return false
        val pitch = WheelPlan.pitchOf(column) ?: return false
        return box.height() <= maxOf(pitch * 9, (column.last().bottom - column.first().top) * 1.8) && column.size * 10 >= items.size * 7
    }

    private fun isWholePicker(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString().orEmpty()
        return cls.contains("TimePicker") || cls.contains("DatePicker")
    }

    private companion object {
        const val SETTLE_MS = 350L
        const val ACTION_GAP_MS = 60L
        const val HOLD_MS = 250L
        const val EXPLORE_STEPS = 3
        const val MAX_ACTIONS = 150
        const val MAX_ATTEMPTS = 30
        const val MAX_DRAG_ROUNDS = 5
    }
}
