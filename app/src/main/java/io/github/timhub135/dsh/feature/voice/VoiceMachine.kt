package io.github.timhub135.dsh.feature.voice

/** What the voice strip shows; [VoiceMachine] is the only thing that changes it. */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** [ready]: the recognizer is taking audio. [level]: 0..1 input level. */
    data class Listening(val text: String = "", val level: Float = 0f, val ready: Boolean = false) : VoiceState
    /** The final text sits in the composer and is sent when [leftMs] runs out, unless the user edits or cancels. */
    data class Countdown(val text: String, val leftMs: Long, val totalMs: Long) : VoiceState
    data class Failed(val error: VoiceError) : VoiceState
}

enum class VoiceError { NoRecognizer, Permission, NoMatch, Network, Busy, Audio, Language, Other }

sealed interface VoiceEvent {
    data object Start : VoiceEvent
    data object Ready : VoiceEvent
    data class Level(val rmsDb: Float) : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Error(val error: VoiceError) : VoiceEvent
    data class Tick(val elapsedMs: Long) : VoiceEvent
    /** The user typed, tapped the countdown, or the panel went away: keep the text, send nothing. */
    data object Cancel : VoiceEvent
}

sealed interface VoiceEffect {
    data class Send(val text: String) : VoiceEffect
    /** Stop and free the recognizer. */
    data object Release : VoiceEffect
}

/**
 * Listening → partial → final → countdown → send, as a pure reducer (Android-free, unit-tested).
 * Without [autoSend] a final result just stays in the composer.
 */
class VoiceMachine(private val autoSend: Boolean = true, private val countdownMs: Long = COUNTDOWN_MS) {
    var state: VoiceState = VoiceState.Idle
        private set

    fun on(e: VoiceEvent): VoiceEffect? {
        val s = state
        return when (e) {
            VoiceEvent.Start -> { state = VoiceState.Listening(); null }
            VoiceEvent.Ready -> { if (s is VoiceState.Listening) state = s.copy(ready = true); null }
            is VoiceEvent.Level -> { if (s is VoiceState.Listening) state = s.copy(level = level(e.rmsDb), ready = true); null }
            is VoiceEvent.Partial -> { if (s is VoiceState.Listening && e.text.isNotBlank()) state = s.copy(text = e.text.trim()); null }
            is VoiceEvent.Final -> if (s is VoiceState.Listening) finish(e.text.trim().ifEmpty { s.text }) else null
            // Many recognizers end with NO_MATCH / a client error after partials: what was heard still counts.
            is VoiceEvent.Error -> when {
                s !is VoiceState.Listening -> null
                s.text.isNotEmpty() && e.error != VoiceError.Permission -> finish(s.text)
                else -> { state = VoiceState.Failed(e.error); VoiceEffect.Release }
            }
            is VoiceEvent.Tick -> if (s is VoiceState.Countdown) {
                val left = s.leftMs - e.elapsedMs
                if (left > 0) { state = s.copy(leftMs = left); null } else { state = VoiceState.Idle; VoiceEffect.Send(s.text) }
            } else null
            VoiceEvent.Cancel -> { val wasListening = s is VoiceState.Listening; state = VoiceState.Idle; if (wasListening) VoiceEffect.Release else null }
        }
    }

    private fun finish(text: String): VoiceEffect {
        state = when {
            text.isEmpty() -> VoiceState.Failed(VoiceError.NoMatch)
            autoSend -> VoiceState.Countdown(text, countdownMs, countdownMs)
            else -> VoiceState.Idle
        }
        return VoiceEffect.Release
    }

    companion object {
        const val COUNTDOWN_MS = 1500L

        /** onRmsChanged reports roughly -2..10 dB. */
        fun level(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

        /** Spoken text appended to an existing draft: no space between CJK characters. */
        fun join(draft: String, spoken: String): String {
            val d = draft.trimEnd()
            if (d.isEmpty() || spoken.isEmpty()) return d + spoken
            return if (cjk(d.last()) || cjk(spoken.first())) d + spoken else "$d $spoken"
        }

        private fun cjk(ch: Char) = ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF || ch.code in 0xFF00..0xFFEF

        /** android.speech.SpeechRecognizer error codes (literals: some are API 31+ constants). */
        fun errorOf(code: Int): VoiceError = when (code) {
            9 -> VoiceError.Permission
            6, 7 -> VoiceError.NoMatch
            1, 2, 4, 11 -> VoiceError.Network
            8, 10 -> VoiceError.Busy
            3 -> VoiceError.Audio
            12, 13 -> VoiceError.Language
            else -> VoiceError.Other
        }
    }
}
