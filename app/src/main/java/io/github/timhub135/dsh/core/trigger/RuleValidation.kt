package io.github.timhub135.dsh.core.trigger

import io.github.timhub135.dsh.core.model.Condition
import io.github.timhub135.dsh.core.model.RuleAction
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.Trigger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lenient wall-clock parsing for rules: "7:30", "07:30", "7:30:00". Never throws. */
object RuleTime {
    private val formats = listOf("H:mm", "HH:mm", "H:mm:ss").map { DateTimeFormatter.ofPattern(it).withResolverStyle(java.time.format.ResolverStyle.STRICT) }
    fun parse(s: String?): LocalTime? {
        val t = s?.trim()?.replace('：', ':') ?: return null
        for (f in formats) runCatching { return LocalTime.parse(t, f) }
        return null
    }
    fun format(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
}

class RuleSpecException(val problems: List<String>) : Exception(problems.joinToString("; "))

/**
 * Checks a RuleSpec before it is stored, and returns it normalized (times as HH:mm, step tools added
 * to allowedTools). Throws [RuleSpecException] listing every problem in model-readable form.
 */
fun validateRule(spec: RuleSpec, knownTools: Set<String>, now: Long = System.currentTimeMillis()): RuleSpec {
    val p = mutableListOf<String>()
    if (spec.name.isBlank()) p += "name must not be empty"
    fun time(field: String, v: String?): String? {
        if (v == null) return null
        val t = RuleTime.parse(v)
        if (t == null) { p += "$field '$v' is not a time; use 24-hour \"HH:mm\" such as \"07:30\" (00:00–23:59)"; return v }
        return RuleTime.format(t)
    }
    fun days(field: String, d: List<Int>) { if (d.any { it !in 1..7 }) p += "$field must be numbers 1..7 (Mon=1 … Sun=7), got $d" }

    val trigger = when (val t = spec.trigger) {
        is Trigger.Time -> {
            val set = listOfNotNull(t.at, t.everyMinutes, t.onceAtEpochMs).size
            if (set != 1) p += "time trigger needs exactly one of \"at\" (HH:mm), \"everyMinutes\" (>=15) or \"onceAtEpochMs\""
            days("trigger.days", t.days)
            if (t.everyMinutes != null && t.everyMinutes < 15) p += "everyMinutes must be at least 15 (Android limits background alarms), got ${t.everyMinutes}"
            if (t.onceAtEpochMs != null && t.onceAtEpochMs <= now) p += "onceAtEpochMs is in the past"
            if (t.days.isNotEmpty() && t.at == null) p += "\"days\" only applies together with \"at\""
            t.copy(at = time("trigger.at", t.at))
        }
        is Trigger.Screen -> t.also { if (it.event !in setOf("on", "off", "unlock")) p += "screen.event must be \"on\", \"off\" or \"unlock\", got \"${it.event}\"" }
        is Trigger.BatteryLow -> t.also { if (it.percent !in 1..99) p += "battery_low.percent must be 1..99, got ${it.percent}" }
        is Trigger.Geofence -> t.also {
            if (it.lat !in -90.0..90.0 || it.lng !in -180.0..180.0) p += "geofence lat/lng out of range"
            if (it.radiusM !in 50f..100_000f) p += "geofence.radiusM must be 50..100000 metres (100+ recommended), got ${it.radiusM}"
        }
        is Trigger.AppOpened -> t.also { if (it.packageName.isBlank()) p += "app_opened.packageName must not be empty" }
        is Trigger.Notification -> t.copy(packageName = t.packageName?.takeIf { it.isNotBlank() }, keyword = t.keyword?.takeIf { it.isNotBlank() })
        else -> t
    }
    val conditions = spec.conditions.map { c ->
        when (c) {
            is Condition.TimeRange -> c.copy(from = time("time_range.from", c.from) ?: c.from, to = time("time_range.to", c.to) ?: c.to)
            is Condition.Days -> c.also { days("days condition", it.days) }
            is Condition.Battery -> c.also {
                if ((it.min != null && it.min !in 0..100) || (it.max != null && it.max !in 0..100)) p += "battery condition min/max must be 0..100"
                if (it.min != null && it.max != null && it.min > it.max) p += "battery condition min must be <= max"
            }
            is Condition.Charging -> c
        }
    }
    var allowed = spec.allowedTools.map { it.trim() }.filter { it.isNotEmpty() }
    when (val a = spec.action) {
        is RuleAction.Agent -> if (a.prompt.isBlank()) p += "agent action needs a non-empty prompt"
        is RuleAction.Steps -> {
            if (a.steps.isEmpty()) p += "steps action needs at least one step"
            a.steps.map { it.tool }.filter { it !in knownTools }.distinct().takeIf { it.isNotEmpty() }?.let { p += "unknown step tool(s): ${it.joinToString()}" }
            allowed = allowed + a.steps.map { it.tool }
        }
    }
    // allowedTools may also name engine built-ins (web_search, read, …), so unknown names are not an error.
    if (p.isNotEmpty()) throw RuleSpecException(p)
    return spec.copy(trigger = trigger, conditions = conditions, allowedTools = allowed.distinct())
}
