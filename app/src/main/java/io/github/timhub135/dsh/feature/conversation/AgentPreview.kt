package io.github.timhub135.dsh.feature.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.hairline
import io.github.timhub135.dsh.core.designsystem.pressable
import kotlinx.coroutines.delay

/** Live frames of the agent screen (~2 per second) while the screen is started. */
@Composable
private fun rememberAgentFrame(vm: HomeViewModel, displayId: Int, maxWidth: Int): ImageBitmap? {
    val owner = LocalLifecycleOwner.current
    val frame by produceState<ImageBitmap?>(null, displayId, maxWidth, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.agentFrame(displayId, maxWidth)?.let { value = it }
                delay(500)
            }
        }
    }
    return frame
}

/**
 * The strip above the conversation while the agent works on its own screen (docs/AGENT-SCREEN-V2.md
 * §1): a small live picture; tapping it opens a large view with 我来接管.
 */
@Composable
fun AgentPreview(vm: HomeViewModel, displayId: Int) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val frame = rememberAgentFrame(vm, displayId, PREVIEW_WIDTH)
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(Radius.md).background(c.bgModule)
        .pressable({ open = true }, Radius.md).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(64.dp).aspectRatio(frame?.let { it.width.toFloat() / it.height } ?: 0.46f).clip(Radius.sm).background(c.bgBase).hairline(Radius.sm, c.borderL2),
            contentAlignment = Alignment.Center) {
            if (frame != null) Image(frame, null, contentScale = ContentScale.Fit) else Spinner()
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.agent_preview_working), style = DshType.xs13m, color = c.labelPrimary)
            Text(stringResource(R.string.agent_preview_open), style = DshType.xxs12, color = c.labelTertiary)
        }
    }
    if (open) AgentScreenDialog(vm, displayId) { open = false }
}

@Composable
private fun AgentScreenDialog(vm: HomeViewModel, displayId: Int, onClose: () -> Unit) {
    val c = Dsh.colors
    val frame = rememberAgentFrame(vm, displayId, FULL_WIDTH)
    var busy by remember { mutableStateOf(false) }
    var taken by remember { mutableStateOf(false) }
    DDialog(onClose, stringResource(R.string.agent_preview_working), buttons = {
        DButton(stringResource(R.string.agent_preview_close), onClose, variant = BtnVariant.Outline)
        DButton(stringResource(if (taken) R.string.agent_preview_taken else R.string.agent_preview_take_over), {
            busy = true
            vm.takeOverAgentScreen { ok -> busy = false; if (ok) { taken = true; onClose() } }
        }, enabled = !busy && !taken)
    }) {
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentAlignment = Alignment.Center) {
            if (frame != null) Image(frame, null, Modifier.clip(Radius.sm), contentScale = ContentScale.Fit)
            else Box(Modifier.height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
    }
}

private const val PREVIEW_WIDTH = 360
private const val FULL_WIDTH = 720
