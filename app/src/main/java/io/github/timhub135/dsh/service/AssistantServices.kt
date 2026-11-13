package io.github.timhub135.dsh.service

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import io.github.timhub135.dsh.feature.quick.QuickActivity
import io.github.timhub135.dsh.feature.quick.ScreenContext

/** Lets the user pick DSH as the system "digital assistant" (long-press power / gesture). */
class DshVoiceInteractionService : VoiceInteractionService()

class DshVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DshVoiceSession(this)
}

/** Receives the current screen's structure from the system, then opens the quick panel with it. */
class DshVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure
        ScreenContext.fromAssist(structure?.let(::flatten), structure?.activityComponent?.packageName)
        startAssistantActivity(Intent(context, QuickActivity::class.java).putExtra(QuickActivity.EXTRA_FROM_ASSIST, true))
        finish()
    }

    private fun flatten(s: AssistStructure): String = buildString {
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) append("  ".repeat(minOf(depth, 10))).append(t ?: d).append('\n')
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, 0)
    }.take(8000)

}

/** Required by the system for an assistant to be selectable; DSH has no speech recognizer (VoiceInput skips this stub). */
class DshRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
