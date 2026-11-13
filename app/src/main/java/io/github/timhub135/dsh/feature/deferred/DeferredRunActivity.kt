package io.github.timhub135.dsh.feature.deferred

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import io.github.timhub135.dsh.MainActivity
import io.github.timhub135.dsh.core.session.SessionStore
import javax.inject.Inject

/**
 * Notification trampoline for [io.github.timhub135.dsh.core.platform.DeferredRuns]: the tap makes the
 * app foreground, so the engine's foreground service may start. Runs the deferred prompt in the
 * store's scope and shows the session in the app (keeping the app visible while the engine starts).
 */
@AndroidEntryPoint
class DeferredRunActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_SESSION)
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (sessionId != null && text != null && savedInstanceState == null) {
            sessions.launchPrompt(sessionId, text, intent.getLongExtra(EXTRA_RULE, -1L).takeIf { it >= 0 })
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    companion object {
        const val EXTRA_SESSION = "session"
        const val EXTRA_TEXT = "text"
        const val EXTRA_RULE = "rule"
    }
}
