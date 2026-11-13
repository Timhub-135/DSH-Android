package io.github.timhub135.dsh.feature.voice

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.feature.settings.PermissionCatalog

/** Composer mic: tap to talk, tap again to finish; while listening a halo follows the input level. */
@Composable
fun MicButton(voice: VoiceInput) {
    val c = Dsh.colors
    val listening = voice.state as? VoiceState.Listening
    val level by animateFloatAsState(listening?.level ?: 0f, tween(90), label = "mic")
    val label = stringResource(if (listening != null) R.string.voice_stop else R.string.voice_input)
    Box(Modifier.size(34.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (listening != null) Box(Modifier.size(34.dp).scale(0.8f + 0.2f * level).clip(CircleShape).background(c.businessTertiary))
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (listening != null) c.info else c.selector).pressable({ voice.toggle() }, CircleShape),
            contentAlignment = Alignment.Center) {
            DIcon(R.drawable.dsw_microphone_outline, size = 14.dp, tint = if (listening != null) androidx.compose.ui.graphics.Color.White else c.labelPrimary)
        }
    }
}

/**
 * One line between the editor and the toolbar: 正在听… / the send countdown (tap to edit instead) /
 * an error with its way out. [onKeyboard] focuses the editor.
 */
@Composable
fun VoiceStrip(voice: VoiceInput, onKeyboard: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    when (val s = voice.state) {
        VoiceState.Idle -> {}
        is VoiceState.Listening -> Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).scale(0.6f + 0.6f * s.level).clip(CircleShape).background(if (s.ready) c.info else c.labelDimmed))
            Text(stringResource(if (s.ready) R.string.voice_listening else R.string.voice_starting), Modifier.weight(1f), style = DshType.xxs12, color = c.labelSecondary)
            Text(stringResource(R.string.cancel), Modifier.clip(Radius.sm).pressable({ voice.cancel() }, Radius.sm).padding(horizontal = 8.dp, vertical = 2.dp),
                style = DshType.xxs12m, color = c.labelSecondary)
        }
        is VoiceState.Countdown -> Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(Radius.sm)
            .pressable({ voice.cancel(); onKeyboard() }, Radius.sm).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.voice_sending), style = DshType.xxs12, color = c.labelSecondary)
            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(c.selector)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(s.leftMs.toFloat() / s.totalMs).background(c.info))
            }
            Text(stringResource(R.string.voice_tap_edit), style = DshType.xxs12m, color = c.business)
        }
        is VoiceState.Failed -> Text(stringResource(errorText(s.error)), Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(Radius.sm)
            .pressable({
                when (s.error) {
                    VoiceError.Permission -> runCatching { context.startActivity(PermissionCatalog.appDetails(context)) }
                    VoiceError.NoRecognizer, VoiceError.Language -> { voice.cancel(); onKeyboard() }
                    else -> voice.start()
                }
            }, Radius.sm).padding(horizontal = 6.dp, vertical = 4.dp), style = DshType.xxs12, color = c.warnLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun errorText(e: VoiceError) = when (e) {
    VoiceError.NoRecognizer -> R.string.voice_err_no_recognizer
    VoiceError.Permission -> R.string.voice_err_permission
    VoiceError.NoMatch -> R.string.voice_err_no_match
    VoiceError.Network -> R.string.voice_err_network
    VoiceError.Busy -> R.string.voice_err_busy
    VoiceError.Audio -> R.string.voice_err_audio
    VoiceError.Language -> R.string.voice_err_language
    VoiceError.Other -> R.string.voice_err_other
}
