package io.github.jackwener.dsh.feature.trajectory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.Chevron
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshColors
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.HDivider
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.session.RecordKind
import io.github.jackwener.dsh.core.session.RecordStatus
import io.github.jackwener.dsh.core.session.TrajRecord
import io.github.jackwener.dsh.core.session.TrajTurn
import io.github.jackwener.dsh.feature.conversation.ConversationController
import io.github.jackwener.dsh.feature.conversation.ToolPresentation
import kotlinx.coroutines.launch

private const val PAGE_TURNS = 12

private sealed interface Row_ { val key: String }
private data class TurnRow(val turn: TrajTurn) : Row_ { override val key get() = "h_" + turn.key }
private data class RecRow(val rec: TrajRecord, val turn: TrajTurn) : Row_ { override val key get() = "r_" + rec.id + "_" + turn.key }

/**
 * Port of dsh ui-trajectory for the phone: a timeline overview strip (tap to jump), then the
 * ledger grouped by turn — user / context / assistant / tool / subtool / compaction records with
 * duration and tokens. Follows the tail while running; older turns page in on demand. Tapping a
 * record opens the inspector sheet.
 */
@Composable
fun TrajectoryView(vm: ConversationController) {
    val c = Dsh.colors
    val traj by vm.trajectory.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val focus by vm.focusRecord.collectAsStateWithLifecycle()
    val t = traj
    if (t == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.traj_loading), style = DshType.xs13, color = c.labelTertiary) }
        return
    }
    val context = LocalContext.current
    var shownTurns by remember(live.sessionId) { mutableIntStateOf(PAGE_TURNS) }
    val collapsed = remember(live.sessionId) { mutableStateMapOf<String, Boolean>() }
    var inspecting by remember { mutableStateOf<TrajRecord?>(null) }
    val visibleTurns = t.turns.takeLast(shownTurns)
    val rows = remember(visibleTurns, collapsed.toMap()) {
        visibleTurns.flatMap { turn -> listOf<Row_>(TurnRow(turn)) + if (collapsed[turn.key] == true) emptyList() else turn.records.map { RecRow(it, turn) } }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hasMore = t.turns.size > shownTurns
    val offset = if (hasMore) 1 else 0
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom }
    LaunchedEffect(rows.size, live.running) { if (follow && focus == null && rows.isNotEmpty()) list.scrollToItem(rows.size - 1 + offset) }
    LaunchedEffect(focus, t) {
        val id = focus ?: return@LaunchedEffect
        val turnIdx = t.turns.indexOfFirst { tt -> tt.records.any { it.id == id } }
        if (turnIdx < 0) return@LaunchedEffect
        if (t.turns.size - turnIdx > shownTurns) shownTurns = t.turns.size - turnIdx
        val rec = t.records.first { it.id == id }
        follow = false
        val idx = rows.indexOfFirst { it is RecRow && it.rec.id == id }
        if (idx >= 0) list.scrollToItem(idx + offset)
        inspecting = rec
        vm.focusRecord.value = null
    }

    Column(Modifier.fillMaxSize()) {
        val calls = t.records.count { it.kind == RecordKind.Tool || it.kind == RecordKind.Subtool }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.traj_summary, t.turns.count { it.number != null }, calls, Fmt.duration(context, t.end - t.start), Fmt.tokens(t.usage.total)),
                style = DshType.xxs12, color = c.labelTertiary)
        }
        Timeline(t.records, t.start, t.end, Modifier.padding(horizontal = 16.dp)) { rec ->
            val turnIdx = t.turns.indexOfFirst { tt -> tt.records.any { it.id == rec.id } }
            if (turnIdx >= 0 && t.turns.size - turnIdx > shownTurns) shownTurns = t.turns.size - turnIdx
            follow = false
            scope.launch {
                val idx = rows.indexOfFirst { it is RecRow && it.rec.id == rec.id }
                if (idx >= 0) list.animateScrollToItem(idx + offset)
            }
        }
        HDivider(Modifier.padding(top = 6.dp))
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)) {
            if (hasMore) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    DButton(stringResource(R.string.traj_load_earlier), { shownTurns += PAGE_TURNS }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                }
            }
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is TurnRow -> TurnHeaderRow(r.turn, collapsed[r.turn.key] == true) { collapsed[r.turn.key] = !(collapsed[r.turn.key] ?: false) }
                    is RecRow -> RecordRow(r.rec, r.turn) { inspecting = r.rec }
                }
            }
            if (t.records.isEmpty()) item { Text(stringResource(R.string.traj_empty), Modifier.padding(24.dp), style = DshType.xs13, color = c.labelTertiary) }
        }
    }
    inspecting?.let { rec -> Inspector(rec, live.sessionId) { inspecting = null } }
}

fun kindColor(k: RecordKind, c: DshColors): Color = when (k) {
    RecordKind.User -> c.kindMessages
    RecordKind.Context -> c.kindSystem
    RecordKind.Assistant -> c.business
    RecordKind.Tool -> c.kindTools
    RecordKind.Subtool -> c.kindTools.copy(alpha = 0.6f)
    RecordKind.Compaction -> c.warn
    RecordKind.Error -> c.error
}

fun kindLabel(k: RecordKind) = when (k) {
    RecordKind.User -> R.string.kind_user
    RecordKind.Context -> R.string.kind_context
    RecordKind.Assistant -> R.string.kind_assistant
    RecordKind.Tool -> R.string.kind_tool
    RecordKind.Subtool -> R.string.kind_subtool
    RecordKind.Compaction -> R.string.kind_compacted
    RecordKind.Error -> R.string.kind_error
}

/** Overview strip: every record as a segment on the session's time axis, colored by kind. */
@Composable
private fun Timeline(records: List<TrajRecord>, start: Long, end: Long, modifier: Modifier, onPick: (TrajRecord) -> Unit) {
    val c = Dsh.colors
    val span = (end - start).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(28.dp).clip(Radius.sm).background(c.selector).pointerInput(records, start, end) {
        detectTapGestures { p ->
            val at = start + (p.x / size.width * span).toLong()
            records.minByOrNull { r -> if (at in r.start..(r.end ?: r.start)) 0L else minOf(kotlin.math.abs(r.start - at), kotlin.math.abs((r.end ?: r.start) - at)) }?.let(onPick)
        }
    }) {
        val lanes = mapOf(RecordKind.User to 0, RecordKind.Context to 0, RecordKind.Assistant to 1, RecordKind.Compaction to 1, RecordKind.Error to 1, RecordKind.Tool to 2, RecordKind.Subtool to 2)
        val laneH = (size.height - 8.dp.toPx()) / 3
        records.forEach { r ->
            val x = (r.start - start).toFloat() / span * size.width
            val w = (((r.end ?: end) - r.start).toFloat() / span * size.width).coerceAtLeast(2.dp.toPx())
            val y = 4.dp.toPx() + (lanes[r.kind] ?: 2) * laneH
            drawRoundRect(kindColor(r.kind, c), Offset(x, y + 1), Size(w, laneH - 2), CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
private fun TurnHeaderRow(turn: TrajTurn, collapsed: Boolean, onToggle: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).pressable(onToggle, Radius.sm).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Chevron(open = !collapsed)
            Spacer(Modifier.width(6.dp))
            Text(turn.number?.let { stringResource(R.string.traj_turn, it) } ?: stringResource(R.string.traj_between), style = DshType.xs13m, color = c.labelPrimary)
            Spacer(Modifier.width(8.dp))
            Text(Fmt.dateTime(turn.start).substringAfter(' '), style = DshType.xxs12, color = c.labelCaption)
        }
        if (turn.number != null) Text(turnSummary(context, turn), Modifier.padding(start = 26.dp, end = 4.dp, bottom = 6.dp),
            style = DshType.xxxs11, color = c.labelCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HDivider()
    }
}

/** 用时 · 调用次数 · Token · 缓存命中 · 首次反馈 for one turn; parts the log has no data for are left out. */
private fun turnSummary(context: android.content.Context, turn: TrajTurn): String {
    return listOfNotNull(
        turn.end?.let { context.getString(R.string.traj_turn_took, Fmt.duration(context, it - turn.start)) },
        context.getString(R.string.traj_turn_calls, turn.toolCalls),
        turn.usage.takeIf { !it.isEmpty }?.let { "${Fmt.tokens(it.total)} tok" },
        turn.usage.cacheHitRate?.let { context.getString(R.string.usage_cache_hit_short, Fmt.percent(it)) },
        turn.firstOutputMs?.let { context.getString(R.string.traj_turn_first, Fmt.shortDuration(it)) },
    ).joinToString(" · ")
}

@Composable
private fun RecordRow(r: TrajRecord, turn: TrajTurn, onClick: () -> Unit) {
    val c = Dsh.colors
    val title = when (r.kind) {
        RecordKind.Tool, RecordKind.Subtool -> stringResource(ToolPresentation.info(r.toolName ?: r.title).title) + " · " + (r.toolName ?: r.title)
        RecordKind.Compaction -> stringResource(R.string.traj_compaction, r.title)
        RecordKind.Assistant -> r.title.ifBlank { stringResource(R.string.traj_tool_calls_only) }
        else -> r.title
    }
    val turnSpan = ((turn.end ?: System.currentTimeMillis()) - turn.start).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(onClick, Radius.md).padding(start = if (r.kind == RecordKind.Subtool) 22.dp else 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(28.dp).clip(Radius.xs).background(kindColor(r.kind, c)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(stringResource(kindLabel(r.kind)), when (r.kind) { RecordKind.Error -> Tone.Error; RecordKind.User -> Tone.Info; RecordKind.Compaction -> Tone.Warn; else -> Tone.Neutral })
                if (r.step != null && r.kind == RecordKind.Assistant) Text(stringResource(R.string.traj_step, r.step), style = DshType.xxxs11, color = c.labelCaption)
            }
            Text(title.replace('\n', ' '), style = DshType.xs13, color = if (r.status == RecordStatus.Failed) c.error else c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Relative duration bar inside the turn.
            r.duration?.let { d ->
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp)) {
                    val startFrac = ((r.start - turn.start).toFloat() / turnSpan).coerceIn(0f, 1f)
                    Row(Modifier.fillMaxWidth()) {
                        if (startFrac > 0f) Spacer(Modifier.weight(startFrac))
                        Box(Modifier.weight((d.toFloat() / turnSpan).coerceIn(0.005f, 1f)).height(2.dp).clip(Radius.xs).background(kindColor(r.kind, c).copy(alpha = 0.5f)))
                        val rest = 1f - startFrac - (d.toFloat() / turnSpan).coerceIn(0.005f, 1f)
                        if (rest > 0f) Spacer(Modifier.weight(rest))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (r.status == RecordStatus.Pending) Spinner(12.dp)
            else r.duration?.takeIf { r.kind != RecordKind.User && r.kind != RecordKind.Context }?.let { Text(Fmt.shortDuration(it), style = DshType.mono11, color = c.labelTertiary) }
            r.usage?.takeIf { !it.isEmpty }?.let { Text("${Fmt.tokens(it.total)} tok", style = DshType.mono11, color = c.labelCaption) }
        }
    }
}
