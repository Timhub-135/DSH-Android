package io.github.timhub135.dsh.core.capability

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One visible item of a wheel (picker) column, bounds in screen pixels. */
data class WheelItem(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        /** An item known only by its centre (text recognition): bounds of one [pitch] around it. */
        fun around(text: String, x: Int, y: Int, pitch: Int, halfWidth: Int = 40) =
            WheelItem(text, x - halfWidth, y - pitch / 2, x + halfWidth, y - pitch / 2 + pitch)
    }
}

/**
 * Values shown by wheels, as text: "09" == "9", "40分" == "40", and the state words screen readers
 * add ("已选中，21", "Selected, 21", "21 (selected)") are not part of the value. Pure.
 */
object WheelValue {
    private val STATE = Regex("(未选中|已选中|已选择|选中|当前值|当前|not selected|unselected|selected|current value|current)", RegexOption.IGNORE_CASE)
    private const val EDGE = " \t\n,，:：;；、。.()（）[]【】"
    private val DIGITS = Regex("\\d+")
    /** A value with a number: at most this many other characters (units like 分, 月, 年, h). */
    private const val MAX_UNIT = 4

    fun clean(raw: String): String {
        var s = STATE.replace(raw, " ")
        s = s.replace(Regex("[(（\\[【]\\s*[)）\\]】]"), " ")
        return s.trim { it in EDGE }.replace(Regex("\\s+"), " ")
    }

    /** The integer a value shows ("09" → 9, "40分" → 40, "2026年" → 2026), or null (no number, or several). */
    fun number(raw: String): Int? {
        val c = clean(raw)
        val runs = DIGITS.findAll(c).toList()
        if (runs.size != 1 || runs[0].value.length > 9) return null
        if (c.length - runs[0].value.length > MAX_UNIT) return null
        val n = runs[0].value.toInt()
        return if (c.startsWith("-") && runs[0].range.first == 1) -n else n
    }

    /** What is around the number ("分" in "40分"), or the whole cleaned text when there is no number. */
    fun unit(raw: String): String = clean(raw).replace(DIGITS, "").replace("-", "").trim()

    fun matches(shown: String?, target: String): Boolean {
        if (shown == null) return false
        val s = clean(shown); val t = clean(target)
        if (s.equals(t, ignoreCase = true)) return true
        val ns = number(s); val nt = number(t)
        if (ns != null && nt != null) {
            val us = unit(s); val ut = unit(t)
            return ns == nt && (us.isEmpty() || ut.isEmpty() || us.equals(ut, ignoreCase = true))
        }
        return s.replace(" ", "").equals(t.replace(" ", ""), ignoreCase = true)
    }

    /** [n] written like its neighbours: "00" between "59" and "01", "40分" between "39分" and "41分". */
    fun format(n: Int, like: List<String>): String {
        val template = like.firstOrNull { DIGITS.containsMatchIn(clean(it)) }?.let(::clean) ?: return n.toString()
        val width = like.mapNotNull { DIGITS.find(clean(it))?.value }.filter { it.length > 1 && it.startsWith("0") }.maxOfOrNull { it.length } ?: 0
        val digits = abs(n).toString().padStart(width, '0')
        val run = DIGITS.find(template)!!
        val start = if (run.range.first == 1 && template[0] == '-') 0 else run.range.first
        return template.substring(0, start) + (if (n < 0) "-" else "") + digits + template.substring(run.range.last + 1)
    }
}

/** A cycle of numbers [min] .. min + size - 1 (minutes 0..59, hours 1..12). */
data class WheelCycle(val min: Int, val size: Int) {
    val max: Int get() = min + size - 1
    fun wrap(n: Int): Int = min + Math.floorMod(n - min, size)

    companion object {
        /** 1-based for 12 (hours, months), 7 (weekdays) and month lengths; 0-based otherwise (24, 60, 100). */
        fun ofSize(size: Int): WheelCycle = WheelCycle(if (size == 12 || size == 7 || size in 28..31) 1 else 0, size)

        private val COMMON = listOf(7, 12, 24, 28, 29, 30, 31, 60, 100)

        /** A cycle of exactly [size] when that is a common wheel size (weekdays, hours, days of a month, minutes). */
        fun common(size: Int): WheelCycle? = if (size in COMMON) ofSize(size) else null

        /** Snaps an estimated size to a common one when it is close (a wrap seen during movement is imprecise). */
        fun guess(estimate: Int): WheelCycle? {
            val best = COMMON.minByOrNull { abs(it - estimate) } ?: return null
            return if (abs(best - estimate) <= 2) ofSize(best) else null
        }
    }
}

/**
 * The items of one wheel column and the wheel's centre line ([centerY]): the shown value is the
 * item whose bounds cover the line, or — when no item covers it (a gap, or the selected item has no
 * text node) — inferred from the neighbours above and below. Pure.
 */
class WheelColumn(items: List<WheelItem>, val centerY: Int) {
    val items: List<WheelItem> = items.sortedBy { it.cy }

    /** Distance between neighbouring items (median), or null with fewer than two items. */
    val pitch: Double? = WheelPlan.pitchOf(this.items)

    private val numbers: List<Int?> = this.items.map { WheelValue.number(it.text) }

    /** Numbers grow downwards (true), upwards (false), or unknown (null). */
    val ascending: Boolean? = run {
        val steps = numbers.zipWithNext { a, b -> if (a != null && b != null) b - a else null }.filterNotNull()
        val up = steps.count { it == 1 }; val down = steps.count { it == -1 }
        when { up > down -> true; down > up -> false; else -> null }
    }

    /** The cycle when a wrap is visible between neighbouring items (…, 58, 59, 00, 01: size 60). */
    val cycle: WheelCycle? = run {
        val asc = ascending ?: return@run null
        val p = pitch ?: return@run null
        numbers.indices.toList().zipWithNext().firstNotNullOfOrNull { (i, j) ->
            val a = numbers[i]; val b = numbers[j]
            if (a == null || b == null || this.items[j].cy - this.items[i].cy > p * 1.5) null
            else if (asc && b < a && b - a != -1) WheelCycle(b, a - b + 1)
            else if (!asc && b > a && b - a != 1) WheelCycle(a, b - a + 1)
            else null
        }
    }

    /** The item covering the centre line (the nearest one when several do). */
    val centerItem: WheelItem? get() = items.filter { it.top <= centerY && centerY < it.bottom }.minByOrNull { abs(it.cy - centerY) }

    /** The value on the centre line: the covering item, else inferred from the neighbours, else null. */
    fun current(): String? = centerItem?.text ?: inferred()

    /** The nearest items above and below the line, when exactly one slot is between them. */
    fun inferred(): String? {
        val above = items.lastOrNull { it.cy < centerY } ?: return null
        val below = items.firstOrNull { it.cy > centerY } ?: return null
        val p = pitch
        if (p != null && items.size > 2 && (below.cy - above.cy) > p * 2.6) return null
        val a = WheelValue.number(above.text) ?: return null
        val b = WheelValue.number(below.text) ?: return null
        val n = between(a, b) ?: return null
        return WheelValue.format(n, listOf(above.text, below.text))
    }

    /**
     * The centre value agrees with its neighbours (40 between 39 and 41). False for a picker whose
     * text field shows a typed value it has not taken (still 29 above and 31 below); true when it
     * cannot be told (words, no neighbours).
     */
    fun consistent(): Boolean {
        val c = centerItem ?: return true
        val n = WheelValue.number(c.text) ?: return true
        val p = pitch ?: return true
        val above = items.lastOrNull { it.cy < c.cy && c.cy - it.cy <= p * 1.5 }?.let { WheelValue.number(it.text) } ?: return true
        val below = items.firstOrNull { it.cy > c.cy && it.cy - c.cy <= p * 1.5 }?.let { WheelValue.number(it.text) } ?: return true
        return between(above, below)?.let { it == n } ?: true
    }

    /** The value the neighbours of the centre imply, ignoring the centre item itself. */
    fun impliedByNeighbours(): String? = centerItem?.let { c -> WheelColumn(items - c, centerY).inferred() }

    /** The number between [a] (above) and [b] (below): 39/41 → 40, 41/39 → 40, 59/01 → 0, 11/1 → 12, 01/59 → 0. */
    internal fun between(a: Int, b: Int): Int? {
        if (b - a == 2) return a + 1
        if (a - b == 2) return a - 1
        // A wrap between them: on an ascending wheel b is past the end (b < a); on a descending one, the reverse.
        val asc = ascending ?: (b < a)
        if (asc && b < a) { val c = cycle ?: WheelCycle.common(a - b + 2) ?: return null; return c.wrap(a + 1).takeIf { c.wrap(it + 1) == b } }
        if (!asc && b > a) { val c = cycle ?: WheelCycle.common(b - a + 2) ?: return null; return c.wrap(b + 1).takeIf { c.wrap(it + 1) == a } }
        return null
    }

    /** Where [value] is relative to the centre line, in items (+2 = two items below), or null when not visible. */
    fun offsetOf(value: String): Int? {
        val p = pitch ?: return null
        val hit = items.filter { WheelValue.matches(it.text, value) }.minByOrNull { abs(it.cy - centerY) } ?: return null
        return ((hit.cy - centerY) / p).roundToInt()
    }
}

/**
 * Finding a wheel column among the visible texts, and planning moves. Steps are counted in items:
 * +n brings the item n places below the centre line onto it (scroll forward, finger moving up). Pure.
 */
object WheelPlan {
    /** Longest text (after [WheelValue.clean]) a wheel item has. */
    const val MAX_ITEM_CHARS = 8

    /**
     * The column of short, evenly spaced, horizontally aligned items through the seed point
     * ([seedX], [seedY]); side-by-side wheels (hour next to minute) are other columns. Null when
     * fewer than [minItems] items form one.
     */
    fun column(all: List<WheelItem>, seedX: Int, seedY: Int, seedHalfWidth: Int = 40, minItems: Int = 3): List<WheelItem>? {
        val tol = seedHalfWidth.coerceIn(24, 120)
        val aligned = dedupe(all.filter { abs(it.cx - seedX) <= tol && WheelValue.clean(it.text).length in 1..MAX_ITEM_CHARS })
        if (aligned.size < minItems) return null
        val gaps = aligned.zipWithNext { a, b -> (b.cy - a.cy).toDouble() }
        val pitch = pitchOf(aligned) ?: return null
        // The run of evenly spaced items (one missing item allowed) nearest the seed.
        val runs = mutableListOf(mutableListOf(aligned[0]))
        gaps.forEachIndexed { i, g ->
            val k = g / pitch
            val even = abs(k - 1) <= 0.25 || abs(k - 2) <= 0.25
            if (even) runs.last() += aligned[i + 1] else runs += mutableListOf(aligned[i + 1])
        }
        val run = runs.minByOrNull { r -> if (seedY in r.first().top..r.last().bottom) 0 else minOf(abs(r.first().cy - seedY), abs(r.last().cy - seedY)) }!!
        return run.takeIf { it.size >= minItems }
    }

    /** Items sorted top to bottom, one per row (a text node and its labelled container count once). */
    fun dedupe(items: List<WheelItem>): List<WheelItem> = items.sortedBy { it.cy }.fold(mutableListOf()) { out, it ->
        if (out.isEmpty() || it.cy - out.last().cy > 4) out += it
        out
    }

    /** Distance between neighbouring items: the median of the regular gaps (one missing item, a duplicate node do not count). */
    fun pitchOf(items: List<WheelItem>): Double? {
        val d = items.sortedBy { it.cy }.zipWithNext { a, b -> (b.cy - a.cy).toDouble() }.filter { it > 0 }.sorted()
        if (d.isEmpty()) return null
        val sane = d.filter { it >= d[d.size / 2] * 0.5 }
        val regular = sane.filter { it <= sane.first() * 1.5 }
        return regular[regular.size / 2]
    }

    /**
     * Whether a node is worth marking `wheel` in a screen read: a NumberPicker, or a picker/wheel
     * class (or a plain scrollable) whose texts are one column of consecutive numbers. Conservative:
     * lists of words or of unrelated numbers are not marked.
     */
    fun looksLikeWheel(cls: String, scrollable: Boolean, texts: List<WheelItem>): Boolean {
        if (cls.contains("NumberPicker")) return true
        val hint = cls.contains("Wheel", ignoreCase = true) || cls.endsWith("PickerView") || cls.contains("LoopView")
        if (!hint && !scrollable) return false
        if (texts.size < 3 || texts.size > 15) return false
        val mid = texts.sortedBy { it.cy }[texts.size / 2]
        val col = column(texts, mid.cx, mid.cy, maxOf(mid.width / 2, 24)) ?: return false
        if (col.size * 10 < texts.size * 8) return false
        if (hint) return true
        val nums = col.map { WheelValue.number(it.text) }
        if (nums.any { it == null }) return false
        val steps = nums.zipWithNext { a, b -> b!! - a!! }
        val consecutive = steps.count { abs(it) == 1 }
        return consecutive >= 2 && steps.size - consecutive <= 1
    }

    /**
     * Signed steps from [current] to [target]: by the target's position when it is visible, else
     * by the numbers (the shorter way round a known [cycle]), else null.
     */
    fun steps(current: String, target: String, column: WheelColumn?, cycle: WheelCycle?, ascending: Boolean?): Int? {
        column?.offsetOf(target)?.let { return it }
        val c = WheelValue.number(current) ?: return null
        val t = WheelValue.number(target) ?: return null
        var d = t - c
        if (cycle != null && c in cycle.min..cycle.max && t in cycle.min..cycle.max) d = shortest(d, cycle.size)
        return if (ascending == false) -d else d
    }

    /** [d] taken the shorter way round a cycle of [size]: 59 → 0 is +1, 0 → 59 is -1. */
    fun shortest(d: Int, size: Int): Int {
        val m = Math.floorMod(d, size)
        return if (m > size / 2) m - size else m
    }

    /**
     * Steps the wheel moved from [from] to [to] ([after] = the column read after the move): by
     * where the old value now is when it is still visible, else by the numbers. Null: unknown.
     */
    fun moved(from: String, to: String, after: WheelColumn?, cycle: WheelCycle?, ascending: Boolean?): Int? {
        if (WheelValue.matches(to, from)) return 0
        after?.offsetOf(from)?.let { return -it }
        val a = WheelValue.number(from) ?: return null
        val b = WheelValue.number(to) ?: return null
        var d = b - a
        if (cycle != null) d = shortest(d, cycle.size)
        return if (ascending == false) -d else d
    }

    /**
     * A wrap seen while moving: [expected] steps were made but the number jumped the other way
     * (59 → 01 after +2 on an ascending wheel). Returns the cycle, or null when it does not look like one.
     */
    fun wrapSeen(from: Int, to: Int, expected: Int, ascending: Boolean?): WheelCycle? {
        val d = (to - from) * (if (ascending == false) -1 else 1)
        if (expected == 0 || d == 0 || (d > 0) == (expected > 0)) return null
        // A small step the other way is a wheel running backwards, not a wrap: a wrap jumps across the range.
        if (abs(d) <= 2 * abs(expected) + 2) return null
        return WheelCycle.guess(abs(d) + abs(expected))
    }

    /** One slow drag (no fling: the finger rests at the end before lifting). */
    data class Drag(val x: Int, val fromY: Int, val toY: Int, val durationMs: Long)

    /**
     * Drags that move the wheel by [steps] items of [pitch] px: from inside the wheel
     * ([wheelTop]..[wheelBottom]) and within the finger's range ([minY]..[maxY]), each lengthened by
     * the touch [slop] the view swallows before it scrolls. At most [maxDrags].
     */
    fun drags(steps: Int, pitch: Double, x: Int, wheelTop: Int, wheelBottom: Int, minY: Int, maxY: Int, slop: Int, maxDrags: Int = 6): List<Drag> {
        if (steps == 0 || pitch <= 0) return emptyList()
        var left = abs(steps) * pitch
        val up = steps > 0
        val inset = (pitch / 2).toInt().coerceAtLeast(4)
        val start = if (up) (wheelBottom - inset).coerceIn(minY, maxY) else (wheelTop + inset).coerceIn(minY, maxY)
        val room = (if (up) start - minY else maxY - start) - slop
        if (room < pitch / 2) return emptyList()
        val out = mutableListOf<Drag>()
        while (left > pitch / 4 && out.size < maxDrags) {
            val d = minOf(left, room.toDouble())
            val travel = (d + slop).roundToInt()
            out += Drag(x, start, if (up) start - travel else start + travel, durationFor(travel))
            left -= d
        }
        return out
    }

    /** Slow enough not to fling: ~0.5 px/ms, at least 400 ms. */
    fun durationFor(travel: Int): Long = (travel * 2L).coerceIn(400, 4000)

    /** Scroll actions for [remaining] steps when one action has moved [perAction] steps: at least one, at most [batch]. */
    fun actions(remaining: Int, perAction: Double, batch: Int = 5): Int =
        ceil(abs(remaining) / perAction.coerceAtLeast(0.2)).toInt().coerceIn(1, batch)
}
