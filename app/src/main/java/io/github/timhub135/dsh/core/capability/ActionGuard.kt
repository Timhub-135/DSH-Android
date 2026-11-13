package io.github.timhub135.dsh.core.capability

import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What a screen/app action actually did, as measured by the tool (see ScreenTools). */
enum class ActionOutcome { Effective, NoEffect, ReadChanged, ReadUnchanged }

/**
 * Loop detection for one session (docs/AGENT-SCREEN-V2.md §3.5). Counts four signals and climbs a
 * ladder for each: reminders at the lower rungs, a stop at the last one. Once stopped, every
 * guarded call is refused until the user speaks again ([reset]). Pure.
 */
class LoopGuard {
    enum class Signal(val ladder: List<Int>) {
        Repeat(listOf(3, 6, 12)), NoEffect(listOf(3, 6, 9, 12)), Reread(listOf(5, 8, 12)), Failure(listOf(2, 4, 8));
        val stopAt: Int get() = ladder.last()
    }

    private val recent = ArrayDeque<String>()
    private var noEffect = 0
    private var rereads = 0
    private var failures = 0
    private val warned = HashMap<Signal, Int>()
    var stopped: Signal? = null
        private set
    /** Fingerprint of the screen the last call left (see [ActionGuard.signature]). */
    @Volatile var screen: Int? = null

    /** Refusal reason before a call runs: already stopped, or this call would be the [Signal.Repeat] stop. */
    @Synchronized
    fun check(signature: String?): Signal? {
        stopped?.let { return it }
        if (signature != null && recent.count { it == signature } + 1 >= Signal.Repeat.stopAt) return Signal.Repeat.also { stopped = it }
        return null
    }

    /** Records a finished call; returns the signal and count that reached a new rung (null = nothing to say). */
    @Synchronized
    fun record(signature: String?, ok: Boolean, outcome: ActionOutcome?): Pair<Signal, Int>? {
        var hit: Pair<Signal, Int>? = null
        fun climb(s: Signal, count: Int) {
            val rung = s.ladder.lastOrNull { count >= it } ?: return
            if (rung <= (warned[s] ?: 0)) return
            warned[s] = rung
            if (rung == s.stopAt) stopped = s
            if (hit == null || rung == s.stopAt) hit = s to count
        }
        if (signature != null) {
            recent.addLast(signature); while (recent.size > WINDOW) recent.removeFirst()
            climb(Signal.Repeat, recent.count { it == signature })
        }
        failures = if (ok) 0 else failures + 1
        if (failures == 0) warned.remove(Signal.Failure) else climb(Signal.Failure, failures)
        when (outcome) {
            ActionOutcome.NoEffect -> climb(Signal.NoEffect, ++noEffect)
            ActionOutcome.Effective -> { noEffect = 0; rereads = 0; warned.remove(Signal.NoEffect); warned.remove(Signal.Reread) }
            ActionOutcome.ReadUnchanged -> climb(Signal.Reread, ++rereads)
            ActionOutcome.ReadChanged -> { rereads = 0; warned.remove(Signal.Reread) }
            null -> {}
        }
        return hit
    }

    @Synchronized
    fun reset() { recent.clear(); noEffect = 0; rereads = 0; failures = 0; warned.clear(); stopped = null; screen = null }

    companion object {
        const val WINDOW = 16

        fun message(signal: Signal, count: Int, stop: Boolean): String {
            val what = when (signal) {
                Signal.Repeat -> "you have made the same action on the same target of the same screen $count times in a row of recent steps"
                Signal.NoEffect -> "$count actions in a row changed nothing on screen"
                Signal.Reread -> "you re-read an unchanged screen $count times"
                Signal.Failure -> "$count calls in a row failed"
            }
            return if (stop) "[Loop guard] Stopped: $what. Do not operate the screen any more in this turn: tell the user what you tried, where it is stuck, and what they could do."
            else "[Loop guard] Note: $what. Try a clearly different approach, ask the user, or report what you have and what is missing."
        }
    }
}

/**
 * Spacing of outward actions (send, post, follow…) inside one app (§5): the first is immediate,
 * later ones wait a random 8–20 s after the previous one, a bit longer the more were done in the
 * last 30 minutes, never more than 30 s. Time spent between the actions counts. Pure (clock passed in).
 */
class OutwardPacing(private val random: Random = Random.Default) {
    private class Done(val app: String, val atMs: Long, val gapMs: Long)
    private val done = ArrayDeque<Done>()

    @Synchronized
    fun waitBefore(app: String, nowMs: Long): Long {
        prune(nowMs)
        val last = done.lastOrNull { it.app == app } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, MAX_GAP_MS)
    }

    @Synchronized
    fun noteDone(app: String, nowMs: Long) {
        prune(nowMs)
        val extra = done.count { it.app == app }.toLong()
        val lo = (FIRST_GAP_MS.first + extra * 500).coerceAtMost(MAX_GAP_MS)
        val hi = (FIRST_GAP_MS.last + extra * 1_000).coerceAtMost(MAX_GAP_MS)
        done.addLast(Done(app, nowMs, if (lo >= hi) hi else random.nextLong(lo, hi + 1)))
    }

    private fun prune(nowMs: Long) { while (done.isNotEmpty() && nowMs - done.first().atMs > WINDOW_MS) done.removeFirst() }

    companion object {
        val FIRST_GAP_MS = 8_000L..20_000L
        const val MAX_GAP_MS = 30_000L
        const val WINDOW_MS = 30 * 60_000L
    }
}

/**
 * Per-session loop guard around screen and app tools, plus the process-wide outward pacing.
 * CapabilityHub calls [before]/[after] for every guarded call; the user's next message clears it.
 */
@Singleton
class ActionGuard @Inject constructor() {
    private val guards = ConcurrentHashMap<String, LoopGuard>()
    val pacing = OutwardPacing()

    private fun guard(sessionId: String) = guards.getOrPut(root(sessionId)) { LoopGuard() }

    fun guarded(tool: Tool) = tool.group == ToolGroup.Screen || tool.group == ToolGroup.Apps

    /**
     * A refusal when the session was stopped, or this call is the 12th identical action. After a stop
     * the model may still look at the screen (unless re-reading was the loop) and hand the step to the user.
     */
    fun before(sessionId: String, tool: Tool, args: JsonObject): ToolResult? {
        if (!guarded(tool)) return null
        val g = guard(sessionId)
        if (tool.name == HAND_TO_USER || tool.name in READS && g.stopped != LoopGuard.Signal.Reread) return null
        val signal = g.check(signature(tool, args, g.screen)) ?: return null
        return ToolResult.error("loop_guard", LoopGuard.message(signal, signal.stopAt, stop = true))
    }

    /** Records the call and returns [result] with a reminder appended when a threshold was reached. */
    fun after(sessionId: String, tool: Tool, args: JsonObject, result: ToolResult): ToolResult {
        if (!guarded(tool) || result.errorCode == "loop_guard") return result
        val g = guard(sessionId)
        val signature = signature(tool, args, g.screen)
        result.screen?.let { g.screen = it }
        val (signal, count) = g.record(signature, result.ok, result.outcome) ?: return result
        return result.appendText(LoopGuard.message(signal, count, stop = signal.stopAt <= count))
    }

    /** The user spoke (new turn or steering): every counter starts again. */
    fun reset(sessionId: String) { guards.remove(root(sessionId)) }

    private fun root(sessionId: String) = sessionId.substringBefore('/')

    companion object {
        /** Reads repeat by nature: they are covered by the "unchanged re-read" signal instead. */
        private val READS = setOf("screen_read", "screen_capture", "screen_wait", "app_list", "app_info")
        private const val HAND_TO_USER = "hand_to_user"

        /**
         * The same action on the same target of the same screen ([screen]: the fingerprint the previous
         * call left): scrolling a feed, deleting characters or paging on change the screen, so they never repeat.
         */
        fun signature(tool: Tool, args: JsonObject, screen: Int? = null): String? =
            if (tool.name in READS) null else tool.name + args.entries.filter { it.key !in setOf("durationMs", "timeoutMs") }.sortedBy { it.key }
                .joinToString { "${it.key}=${it.value}" } + "@${screen ?: ""}"
    }
}
