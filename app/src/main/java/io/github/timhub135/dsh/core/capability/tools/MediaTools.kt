package io.github.timhub135.dsh.core.capability.tools

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.service.DshNotificationListener
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaTools @Inject constructor(@ApplicationContext private val context: Context) : ToolSet {
    override val tools = listOf(
        object : Tool("media_control", ToolGroup.Media, Risk.write, false, "Control whatever is playing (music/video/podcast) or get what's playing.", {
            string("action", "", required = true, enum = listOf("status", "play", "pause", "toggle", "next", "previous", "stop"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (DshNotificationListener.instance == null) throw PermissionMissingException("Notification access (needed for media control)")
                val msm = context.getSystemService(MediaSessionManager::class.java)
                val sessions = msm.getActiveSessions(ComponentName(context, DshNotificationListener::class.java))
                val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
                    ?: return ToolResult.text("Nothing is playing.")
                val t = c.transportControls
                val action = args.reqStr("action")
                val wasPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING
                when (action) {
                    "play" -> t.play(); "pause" -> t.pause(); "stop" -> t.stop()
                    "toggle" -> if (wasPlaying) t.pause() else t.play()
                    "next" -> t.skipToNext(); "previous" -> t.skipToPrevious()
                }
                // Report the state after the action, not before it (players update asynchronously).
                if (action != "status") kotlinx.coroutines.delay(400)
                val md = c.metadata
                val state = when (c.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; PlaybackState.STATE_STOPPED -> "stopped"
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "loading"
                    PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skipping"
                    else -> "idle"
                }
                return ToolResult.text((if (action != "status") "Sent $action. " else "") +
                    "${c.packageName}: ${md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "?"} — " +
                    "${md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""} (now $state)")
            }
        },
    )
}
