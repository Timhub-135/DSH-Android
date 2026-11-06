package io.github.jackwener.dsh.feature.trajectory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.ShotStore
import io.github.jackwener.dsh.core.designsystem.CodeCard
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.JsonTree
import io.github.jackwener.dsh.core.designsystem.KeyValue
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Screenshot
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.designsystem.copyText
import io.github.jackwener.dsh.core.session.RecordKind
import io.github.jackwener.dsh.core.session.RecordStatus
import io.github.jackwener.dsh.core.session.TrajRecord
import io.github.jackwener.dsh.core.session.parseArgs
import io.github.jackwener.dsh.feature.conversation.ToolPresentation
import io.github.jackwener.dsh.feature.conversation.prettyJson

private enum class Tab(val label: Int) { Input(R.string.tab_input), Output(R.string.tab_output), Timing(R.string.tab_timing), Images(R.string.tab_images) }

/** Record inspector (dsh trajectory cell detail) as a bottom sheet: 输入 / 输出 / 耗时 / 图片. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Inspector(r: TrajRecord, sessionId: String, onDismiss: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val shots = remember(r.id) { r.callId?.let { ShotStore.files(context, sessionId, it) } ?: emptyList() }
    val tabs = remember(shots) { Tab.entries.filter { it != Tab.Images || shots.isNotEmpty() } }
    var tab by remember(r.id) { mutableIntStateOf(if (r.kind == RecordKind.Tool || r.kind == RecordKind.Subtool) 0 else 1) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = c.bgLayer2, shape = Radius.panel.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
        scrimColor = c.scrim, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp).navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(stringResource(kindLabel(r.kind)), if (r.status == RecordStatus.Failed) Tone.Error else Tone.Neutral)
                Spacer(Modifier.width(8.dp))
                Text(when (r.kind) {
                    RecordKind.Tool, RecordKind.Subtool -> stringResource(ToolPresentation.info(r.toolName ?: "").title) + " · " + (r.toolName ?: "")
                    else -> r.title.lineSequence().firstOrNull() ?: ""
                }, Modifier.weight(1f), style = DshType.s14m, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(R.drawable.dsw_copy_outline, { copyText(context, prettyJson((r.resultRaw ?: r.raw).toString())) }, contentDescription = stringResource(R.string.copy_json))
            }
            Spacer(Modifier.padding(4.dp))
            Segmented(tabs.map { stringResource(it.label) }, tab.coerceAtMost(tabs.lastIndex), { tab = it })
            Spacer(Modifier.padding(6.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tabs[tab.coerceAtMost(tabs.lastIndex)]) {
                    Tab.Input -> InputTab(r)
                    Tab.Output -> OutputTab(r)
                    Tab.Timing -> TimingTab(r)
                    Tab.Images -> shots.forEach { Screenshot(it, maxHeight = 480.dp) }
                }
            }
        }
    }
}

@Composable
private fun InputTab(r: TrajRecord) {
    val c = Dsh.colors
    when (r.kind) {
        RecordKind.Tool, RecordKind.Subtool -> {
            val args = parseArgs(r.input)
            if (args != null) SettingsCard { Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) { JsonTree(args) } }
            CodeCard(prettyJson(r.input).ifBlank { stringResource(R.string.traj_no_payload) }, label = stringResource(R.string.traj_params_json))
        }
        RecordKind.Assistant -> if (r.reasoning != null) CodeCard(r.reasoning, label = stringResource(R.string.think), maxHeight = 600.dp)
            else Text(stringResource(R.string.traj_no_content), style = DshType.xs13, color = c.labelTertiary)
        else -> CodeCard(r.output ?: r.title, label = r.source ?: stringResource(kindLabel(r.kind)), maxHeight = 600.dp)
    }
}

@Composable
private fun OutputTab(r: TrajRecord) {
    val c = Dsh.colors
    val text = r.output
    if (text.isNullOrBlank()) Text(stringResource(if (r.status == RecordStatus.Pending) R.string.code_running else R.string.traj_no_output), style = DshType.xs13, color = c.labelTertiary)
    else CodeCard(text, label = stringResource(R.string.col_output), maxHeight = 600.dp)
    if (r.tools.isNotEmpty()) KeyValue(stringResource(R.string.col_tools), r.tools.joinToString(", "), mono = true)
    (r.resultRaw ?: r.raw).let { raw -> SettingsCard { Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) { JsonTree(raw) } } }
}

@Composable
private fun TimingTab(r: TrajRecord) {
    val context = LocalContext.current
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            KeyValue(stringResource(R.string.timing_started), Fmt.dateTime(r.start) + "." + (r.start % 1000).toString().padStart(3, '0'))
            r.end?.let { KeyValue(stringResource(R.string.timing_ended), Fmt.clock(it)) }
            KeyValue(stringResource(R.string.timing_total), r.duration?.let { Fmt.shortDuration(it) + "  (" + Fmt.duration(context, it) + ")" } ?: stringResource(R.string.timing_not_recorded))
            r.firstToken?.let { ft -> KeyValue(stringResource(R.string.timing_ttft), Fmt.shortDuration((ft - r.start).coerceAtLeast(0))) }
            r.usage?.let { u ->
                KeyValue(stringResource(R.string.usage_input), u.input.toString(), mono = true)
                KeyValue(stringResource(R.string.usage_cached), u.cacheRead.toString(), mono = true)
                u.cacheHitRate?.let { KeyValue(stringResource(R.string.usage_cache_hit), Fmt.percent(it), mono = true) }
                KeyValue(stringResource(R.string.usage_output), u.output.toString(), mono = true)
                if (u.reasoning > 0) KeyValue(stringResource(R.string.usage_reasoning), u.reasoning.toString(), mono = true)
                r.duration?.takeIf { it > 0 && u.output > 0 }?.let { d -> KeyValue(stringResource(R.string.timing_throughput), String.format(java.util.Locale.US, "%.1f tok/s", u.output * 1000.0 / d)) }
            } ?: KeyValue(stringResource(R.string.usage_tokens), stringResource(R.string.usage_not_reported))
            r.cumulative?.let { KeyValue(stringResource(R.string.usage_session_total), "${Fmt.tokens(it.total)} tok") }
            KeyValue("seq", r.seq.toString(), mono = true)
            r.callId?.let { KeyValue("callId", it, mono = true) }
        }
    }
}
