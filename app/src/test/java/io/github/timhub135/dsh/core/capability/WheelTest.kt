package io.github.timhub135.dsh.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTest {
    /** A column of items [pitch] px apart, the first centred at [y0], centred horizontally at [x]. */
    private fun col(vararg texts: String, x: Int = 300, y0: Int = 1000, pitch: Int = 100, h: Int = 80, w: Int = 120) =
        texts.mapIndexed { i, t -> WheelItem(t, x - w / 2, y0 + i * pitch - h / 2, x + w / 2, y0 + i * pitch - h / 2 + h) }

    // ── values ───────────────────────────────────────────────────────────────

    @Test fun valuesNormalize() {
        assertTrue(WheelValue.matches("09", "9"))
        assertTrue(WheelValue.matches("9", "09"))
        assertTrue(WheelValue.matches("40分", "40"))
        assertTrue(WheelValue.matches("40", "40分"))
        assertTrue(WheelValue.matches("已选中，21", "21"))
        assertTrue(WheelValue.matches("Selected, 21", "21"))
        assertTrue(WheelValue.matches("21 (selected)", "21"))
        assertTrue(WheelValue.matches("21（已选中）", "21"))
        assertTrue(WheelValue.matches("2026年", "2026"))
        assertTrue(WheelValue.matches("下午", " 下午 "))
        assertTrue(WheelValue.matches("PM", "pm"))
        assertFalse(WheelValue.matches("10", "1"))
        assertFalse(WheelValue.matches("40分", "40秒"))
        assertFalse(WheelValue.matches("上午", "下午"))
        assertFalse(WheelValue.matches(null, "1"))
    }

    @Test fun numbersOfValues() {
        assertEquals(9, WheelValue.number("09"))
        assertEquals(40, WheelValue.number("40分"))
        assertEquals(21, WheelValue.number("已选中，21"))
        assertEquals(-5, WheelValue.number("-5"))
        assertNull(WheelValue.number("12:30"))
        assertNull(WheelValue.number("下午"))
        assertNull(WheelValue.number("3 hours ago"))
    }

    @Test fun formatFollowsNeighbours() {
        assertEquals("00", WheelValue.format(0, listOf("59", "01")))
        assertEquals("40", WheelValue.format(40, listOf("39", "41")))
        assertEquals("40分", WheelValue.format(40, listOf("39分", "41分")))
        assertEquals("10", WheelValue.format(10, listOf("9", "11")))
        assertEquals("7", WheelValue.format(7, emptyList()))
    }

    // ── reading the centre line ──────────────────────────────────────────────

    @Test fun currentIsTheItemCoveringTheCentreLine() {
        val c = WheelColumn(col("38", "39", "40", "41", "42"), centerY = 1200)
        assertEquals("40", c.current())
        assertEquals(100.0, c.pitch!!, 0.01)
        assertEquals(true, c.ascending)
        assertNull(c.cycle)
    }

    @Test fun missingCentreItemIsInferredFromNeighbours() {
        val items = col("38", "39", "40", "41", "42").filter { it.text != "40" }
        assertEquals("40", WheelColumn(items, 1200).current())
        // Descending wheel.
        assertEquals("40", WheelColumn(col("42", "41", "40", "39", "38").filter { it.text != "40" }, 1200).current())
        // Cyclic: 59 above, 01 below → 00 (zero-padded like the neighbours).
        assertEquals("00", WheelColumn(col("58", "59", "00", "01", "02").filter { it.text != "00" }, 1200).current())
        // 12-hour wheel: 11 above, 1 below → 12; 12 above, 2 below → 1.
        assertEquals("12", WheelColumn(col("10", "11", "12", "1", "2").filter { it.text != "12" }, 1200).current())
        assertEquals("1", WheelColumn(col("11", "12", "1", "2", "3").filter { it.text != "1" }, 1200).current())
        // Hours 0..23.
        assertEquals("00", WheelColumn(col("22", "23", "00", "01", "02").filter { it.text != "00" }, 1200).current())
    }

    @Test fun shortItemsStillCoverTheLineAndAGapBetweenTwoValuesIsNotGuessed() {
        val items = col("39", "40", "41", h = 40)
        assertEquals("40", WheelColumn(items, 1100).current())
        assertEquals("39", WheelColumn(items, 1010).current())
        // Halfway between 40 and 41 there is no value to infer.
        assertNull(WheelColumn(items, 1150).current())
    }

    @Test fun unreadableWhenNeighboursDoNotTell() {
        assertNull(WheelColumn(col("北京", "上海", "广州").filter { it.text != "上海" }, 1100).current())
        assertNull(WheelColumn(col("1", "7"), 1050).current())
    }

    @Test fun typedValueThePickerDidNotTakeIsInconsistent() {
        assertTrue(WheelColumn(col("39", "40", "41"), 1100).consistent())
        assertTrue(WheelColumn(col("59", "00", "01"), 1100).consistent())
        val stale = WheelColumn(col("29", "40", "31"), 1100)
        assertFalse(stale.consistent())
        assertEquals("30", stale.impliedByNeighbours())
        assertTrue(WheelColumn(col("上午", "下午"), 1100).consistent())
    }

    @Test fun cycleSeenInVisibleItems() {
        assertEquals(WheelCycle(0, 60), WheelColumn(col("58", "59", "00", "01"), 1150).cycle)
        assertEquals(WheelCycle(1, 12), WheelColumn(col("11", "12", "1", "2"), 1150).cycle)
        assertEquals(WheelCycle(0, 60), WheelColumn(col("01", "00", "59", "58"), 1150).cycle)
    }

    @Test fun cycleGuess() {
        assertEquals(WheelCycle(0, 60), WheelCycle.guess(59))
        assertEquals(WheelCycle(1, 12), WheelCycle.guess(12))
        assertEquals(WheelCycle(0, 24), WheelCycle.guess(25))
        assertNull(WheelCycle.guess(45))
        assertEquals(WheelCycle(1, 31), WheelCycle.common(31))
        assertNull(WheelCycle.common(8))
    }

    // ── planning ─────────────────────────────────────────────────────────────

    @Test fun stepsFromVisiblePosition() {
        val c = WheelColumn(col("38", "39", "40", "41", "42"), 1200)
        assertEquals(2, WheelPlan.steps("40", "42", c, null, c.ascending))
        assertEquals(-1, WheelPlan.steps("40", "39", c, null, c.ascending))
        val words = WheelColumn(col("上午", "下午"), 1000)
        assertEquals(1, WheelPlan.steps("上午", "下午", words, null, null))
    }

    @Test fun stepsFromNumbersAndTheShorterWayRound() {
        assertEquals(15, WheelPlan.steps("10", "25", null, null, true))
        assertEquals(-15, WheelPlan.steps("10", "25", null, null, false))
        // 55 → 05 on a minute wheel: forward 10, not back 50.
        assertEquals(10, WheelPlan.steps("55", "05", null, WheelCycle(0, 60), true))
        assertEquals(-10, WheelPlan.steps("05", "55", null, WheelCycle(0, 60), true))
        // Without a known cycle: straight.
        assertEquals(-50, WheelPlan.steps("55", "05", null, null, true))
        assertEquals(2, WheelPlan.steps("11", "1", null, WheelCycle(1, 12), true))
        assertNull(WheelPlan.steps("北京", "广州", null, null, null))
    }

    @Test fun shortest() {
        assertEquals(1, WheelPlan.shortest(-59, 60))
        assertEquals(-1, WheelPlan.shortest(59, 60))
        assertEquals(30, WheelPlan.shortest(30, 60))
        assertEquals(-11, WheelPlan.shortest(13, 24))
    }

    @Test fun movedByPositionOrNumbers() {
        val after = WheelColumn(col("40", "41", "42", "43", "44"), 1200)
        assertEquals(2, WheelPlan.moved("40", "42", after, null, true))
        assertEquals(3, WheelPlan.moved("10", "13", null, null, true))
        assertEquals(1, WheelPlan.moved("59", "00", null, WheelCycle(0, 60), true))
        assertEquals(0, WheelPlan.moved("09", "9", null, null, true))
        assertNull(WheelPlan.moved("北京", "广州", null, null, null))
    }

    @Test fun wrapSeenWhileMoving() {
        assertEquals(WheelCycle(0, 60), WheelPlan.wrapSeen(58, 1, 3, true))
        // A step the other way is a wheel running backwards, not a wrap.
        assertNull(WheelPlan.wrapSeen(30, 27, 3, true))
        assertNull(WheelPlan.wrapSeen(30, 33, 3, true))
    }

    @Test fun dragsMoveUpForItemsBelow() {
        val d = WheelPlan.drags(3, 100.0, x = 300, wheelTop = 1000, wheelBottom = 1500, minY = 100, maxY = 2300, slop = 24)
        assertEquals(1, d.size)
        assertTrue(d[0].toY < d[0].fromY)
        assertEquals(324, d[0].fromY - d[0].toY)
        assertTrue(d[0].fromY in 1000..1500)
        assertTrue(d[0].durationMs >= 400)
    }

    @Test fun dragsDownAndSplitWhenLong() {
        val down = WheelPlan.drags(-2, 100.0, 300, 1000, 1500, 100, 2300, 24)
        assertTrue(down.single().toY > down.single().fromY)
        // 30 items of 100 px from a wheel near the top: several strokes, each within the finger range.
        val long = WheelPlan.drags(30, 100.0, 300, 300, 800, 100, 2300, 24)
        assertTrue(long.size > 1)
        assertTrue(long.all { it.toY >= 100 && it.fromY <= 800 })
        assertEquals(3000.0, long.sumOf { it.fromY - it.toY - 24 }.toDouble(), 2.0 * long.size)
        assertTrue(WheelPlan.drags(0, 100.0, 300, 1000, 1500, 100, 2300, 24).isEmpty())
    }

    @Test fun actionsPerBatch() {
        assertEquals(5, WheelPlan.actions(40, 1.0))
        assertEquals(3, WheelPlan.actions(-3, 1.0))
        assertEquals(1, WheelPlan.actions(4, 5.0))
        assertEquals(2, WheelPlan.actions(8, 5.0))
    }

    // ── finding columns ──────────────────────────────────────────────────────

    @Test fun columnThroughTheSeedIgnoresTheNextWheel() {
        val hours = col("07", "08", "09", "10", "11", x = 300)
        val minutes = col("28", "29", "30", "31", "32", x = 600)
        val title = listOf(WheelItem("选择出发时间", 100, 600, 800, 660))
        val found = WheelPlan.column(hours + minutes + title, 600, 1200)
        assertNotNull(found)
        assertEquals(listOf("28", "29", "30", "31", "32"), found!!.map { it.text })
    }

    @Test fun columnNeedsEvenSpacing() {
        val uneven = listOf(WheelItem("1", 280, 960, 320, 1040), WheelItem("2", 280, 1060, 320, 1140), WheelItem("3", 280, 1460, 320, 1540))
        assertNull(WheelPlan.column(uneven, 300, 1000))
        // One missing item (the selected value drawn without a text node) is fine.
        assertNotNull(WheelPlan.column(col("38", "39", "41", "42").let { it.take(2) + it.drop(2).map { i -> i.copy(top = i.top + 100, bottom = i.bottom + 100) } }, 300, 1150))
    }

    @Test fun columnSkipsLongTexts() {
        val items = col("1", "这是一段很长的说明文字不是选项", "3", "4")
        assertNull(WheelPlan.column(items, 300, 1050, minItems = 4))
    }

    @Test fun pitchIgnoresDuplicatesAndGaps() {
        val items = col("1", "2", "3", "4") + WheelItem("2", 240, 1061, 360, 1141)
        assertEquals(100.0, WheelPlan.pitchOf(WheelPlan.dedupe(items))!!, 0.01)
        val gap = col("1", "2", "3", "4").filter { it.text != "3" }
        assertEquals(100.0, WheelPlan.pitchOf(gap)!!, 0.01)
    }

    @Test fun wheelMarkingIsConservative() {
        assertTrue(WheelPlan.looksLikeWheel("android.widget.NumberPicker", true, emptyList()))
        assertTrue(WheelPlan.looksLikeWheel("androidx.recyclerview.widget.RecyclerView", true, col("08", "09", "10", "11", "12")))
        assertTrue(WheelPlan.looksLikeWheel("com.contrarywind.view.WheelView", false, col("北京", "上海", "广州")))
        // A list of words, unrelated numbers, or too few items: not a wheel.
        assertFalse(WheelPlan.looksLikeWheel("androidx.recyclerview.widget.RecyclerView", true, col("设置", "通知", "隐私", "关于")))
        assertFalse(WheelPlan.looksLikeWheel("androidx.recyclerview.widget.RecyclerView", true, col("12", "57", "3", "88")))
        assertFalse(WheelPlan.looksLikeWheel("android.widget.ScrollView", true, col("1", "2")))
        assertFalse(WheelPlan.looksLikeWheel("android.widget.TextView", false, col("1", "2", "3")))
        // Two wheels side by side in one scrollable: neither column is most of it.
        assertFalse(WheelPlan.looksLikeWheel("android.widget.FrameLayout", true, col("1", "2", "3", x = 200) + col("10", "11", "12", x = 600)))
    }
}
