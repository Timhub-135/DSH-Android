package io.github.timhub135.dsh.feature.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.WorkDetails
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.Chevron
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshMarkdown
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.HDivider
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.ShimmerText
import io.github.timhub135.dsh.core.designsystem.SwimmingWhale
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.designsystem.copyText
import io.github.timhub135.dsh.core.designsystem.elevated
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.session.Block
import io.github.timhub135.dsh.core.session.Conversation
import io.github.timhub135.dsh.core.session.EndReason
import io.github.timhub135.dsh.core.session.SessionLive
import io.github.timhub135.dsh.core.session.SteerMessage
import io.github.timhub135.dsh.core.session.ToolState
import io.github.timhub135.dsh.core.session.Turn
import io.github.timhub135.dsh.core.provider.NetDiagnose
import io.github.timhub135.dsh.core.session.TurnError
import io.github.timhub135.dsh.core.session.TurnStatus
import io.github.timhub135.dsh.core.session.UserMsg
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The message stream (dsh ChatView): user bubbles, per-turn header, folded process rows, plain
 * markdown replies, error rows and the running whale. Follows the tail while within 24 dp of
 * the bottom; any manual scroll stops following until "回到底部".
 */
@Composable
fun ChatStream(
    conv: Conversation,
    live: SessionLive,
    details: WorkDetails,
    listState: LazyListState,
    onInspect: (String) -> Unit,
    onRetry: (TurnError) -> Unit,
    onOpenSettings: (section: String) -> Unit,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) follow = atBottom }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }
    val lastIndex = conv.turns.size + live.steers.size
    val tailKey = conv.turns.lastOrNull()?.let { it.blocks.size to it.status } to (live.running to live.steers.size)
    LaunchedEffect(tailKey, live.sessionId) { if (follow && lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE / 2) }

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
            items(conv.turns, key = { it.key }) { turn ->
                val isLast = turn === conv.turns.last()
                TurnView(turn, isLast, live, details, onInspect, onRetry, onOpenSettings, onContinue, compact)
            }
            // Sent into the running turn, not logged yet: the logged user/message takes its place.
            items(live.steers, key = { "steer/${it.id}" }) { PendingSteer(it) }
            item(key = "tail") {
                Box(Modifier.fillMaxWidth().heightIn(min = 8.dp)) {
                    if (live.running) RunningIndicator(live, conv.turns.lastOrNull())
                }
            }
        }
        AnimatedVisibility(!follow && !atBottom, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp), enter = fadeIn(), exit = fadeOut()) {
            val c = Dsh.colors
            Box(Modifier.size(34.dp).elevated(CircleShape, c.borderL2).clip(CircleShape).background(c.floating)
                .pressable({ follow = true; scope.launch { listState.animateScrollToItem(lastIndex, Int.MAX_VALUE / 2) } }, CircleShape),
                contentAlignment = Alignment.Center) {
                Chevron(size = 16.dp, tint = c.labelSecondary)
                val label = stringResource(R.string.back_to_bottom)
                Box(Modifier.matchParentSize().semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button })
            }
        }
    }
}

@Composable
private fun TurnView(
    turn: Turn, isLast: Boolean, live: SessionLive, details: WorkDetails,
    onInspect: (String) -> Unit, onRetry: (TurnError) -> Unit, onOpenSettings: (String) -> Unit, onContinue: () -> Unit, compact: Boolean,
) {
    val running = turn.status == TurnStatus.Running
    val process = turn.process
    val defaultOpen = when (details) {
        WorkDetails.Compact -> false
        WorkDetails.Standard -> isLast
        WorkDetails.Detailed -> true
    }
    var open by rememberSaveable(turn.key) { mutableStateOf(defaultOpen) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        turn.user?.let { if (DshNotice.isNotice(it.text)) DshNoticeRow(it.text) else UserMessage(it, compact) }
        if (process.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            if (!running) TurnHeader(turn, open) { open = !open }
            if (running || open) {
                Spacer(Modifier.height(if (running) 0.dp else 12.dp))
                ProcessBlocks(turn.key, process, running, live.sessionId, onInspect)
            }
        }
        turn.reply?.let { reply ->
            Spacer(Modifier.height(12.dp))
            SelectionContainer { DshMarkdown(reply.text) }
        }
        val end = turn.endReason
        if (turn.status == TurnStatus.Stopped && turn.error == null && !running && end != EndReason.Budget) {
            Spacer(Modifier.height(8.dp)); Tag(stringResource(R.string.stopped), Tone.Neutral)
        }
        if (end != null && end.continuable) EndCard(turn, end, canContinue = isLast && !live.running, onContinue, onOpenSettings)
        turn.error?.let { ErrorRow(it, onRetry, onOpenSettings, retryable = isLast && !live.running) }
        if (turn.reply != null && !running) TailActions(turn)
    }
}

/** Process rows: thinking/tool runs become step groups; interim assistant text renders as markdown. */
@Composable
private fun ProcessBlocks(turnKey: String, blocks: List<Block>, live: Boolean, sessionId: String, onInspect: (String) -> Unit) {
    val segments = remember(blocks) {
        val out = mutableListOf<List<Block>>()
        var run = mutableListOf<Block>()
        for (b in blocks) {
            if (b is Block.Reasoning || b is Block.Tool) run += b
            else { if (run.isNotEmpty()) { out += run; run = mutableListOf() }; out += listOf(b) }
        }
        if (run.isNotEmpty()) out += run
        out
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { i, seg ->
            val segLive = live && i == segments.lastIndex
            when (val b = seg.first()) {
                is Block.Text -> Box(Modifier.padding(vertical = 8.dp)) { DshMarkdown(b.text) }
                is Block.Compaction -> CompactionRow(b)
                is Block.Notice -> NoticeRow(b)
                else -> StepGroup("$turnKey/$i", seg, segLive, sessionId, onInspect)
            }
        }
    }
}

/** Turn header (dsh TurnProcessNodeView): "已完成，用时 …" + rotating chevron, hairline underline. */
@Composable
private fun TurnHeader(turn: Turn, open: Boolean, onToggle: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val text = when (turn.status) {
        TurnStatus.Completed, TurnStatus.MaxTokens -> turn.endTime?.let { stringResource(R.string.turn_done_in, Fmt.duration(context, it - (turn.user?.time ?: turn.startTime))) }
            ?: stringResource(R.string.turn_done)
        TurnStatus.Failed -> stringResource(R.string.turn_failed)
        TurnStatus.Stopped -> stringResource(R.string.turn_stopped)
        TurnStatus.Steered -> stringResource(R.string.turn_steered)
        TurnStatus.Running -> ""
    }
    Column(Modifier.fillMaxWidth().pressable(onToggle, Radius.sm)) {
        Row(Modifier.fillMaxWidth().height(33.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(text, Modifier.weight(1f), style = DshType.s14, color = c.labelTertiary)
            Chevron(open = open)
        }
        HDivider()
    }
}

@Composable
private fun UserMessage(u: UserMsg, compact: Boolean) {
    val c = Dsh.colors
    val context = LocalContext.current
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (u.images > 0 || u.files > 0) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (u.images > 0) Tag(stringResource(R.string.images_n, u.images))
                if (u.files > 0) Tag(stringResource(R.string.files_n, u.files))
            }
        }
        if (u.text.isNotBlank()) SelectionContainer {
            Text(u.text, Modifier.widthIn(max = maxW).clip(Radius.xl).background(c.bubble).padding(horizontal = 16.dp, vertical = 10.dp),
                style = DshType.s14, color = c.labelPrimary)
        }
        if (!compact) Row(Modifier.padding(top = 6.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.stamp(context, u.time), style = DshType.xs13, color = c.labelTertiary)
            IconBtn(R.drawable.dsw_copy_outline, { copyText(context, u.text) }, iconSize = 15.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.copy_message))
        }
    }
}

/** A message steered into the running turn that dsh has not logged yet: the user bubble, dimmed, with its state. */
@Composable
private fun PendingSteer(m: SteerMessage) {
    val c = Dsh.colors
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).alpha(0.6f), horizontalAlignment = Alignment.End) {
        if (m.images > 0) Tag(stringResource(R.string.images_n, m.images), modifier = Modifier.padding(bottom = 8.dp))
        if (m.text.isNotBlank()) Text(m.text, Modifier.widthIn(max = maxW).clip(Radius.xl).background(c.bubble).padding(horizontal = 16.dp, vertical = 10.dp),
            style = DshType.s14, color = c.labelPrimary)
        Spacer(Modifier.height(6.dp))
        Tag(stringResource(R.string.steer_pending), Tone.Neutral)
    }
}

@Composable
private fun TailActions(turn: Turn) {
    val c = Dsh.colors
    val context = LocalContext.current
    Row(Modifier.padding(top = 10.dp).offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(R.drawable.dsw_copy_outline, { copyText(context, turn.reply?.text ?: "") }, iconSize = 17.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.copy_reply))
        if (!turn.usage.isEmpty) {
            Spacer(Modifier.width(4.dp))
            val hit = turn.usage.cacheHitRate?.let { " · " + stringResource(R.string.usage_cache_hit_short, Fmt.percent(it)) }.orEmpty()
            Text("↑${Fmt.tokens(turn.usage.input)} ↓${Fmt.tokens(turn.usage.output)}$hit", Modifier.clip(Radius.sm).background(c.selector).padding(horizontal = 8.dp, vertical = 1.dp),
                style = DshType.xxs12, color = c.labelTertiary)
        }
        Spacer(Modifier.width(8.dp))
        turn.endTime?.let { Text(Fmt.stamp(context, it), style = DshType.xxs12, color = c.labelCaption) }
    }
}

/** dsh turn error: red dot | title + message + code, with a retry affordance. */
@Composable
private fun ErrorRow(e: TurnError, onRetry: (TurnError) -> Unit, onOpenSettings: (String) -> Unit, retryable: Boolean) {
    val c = Dsh.colors
    val account = io.github.timhub135.dsh.feature.account.LocalAccount.current
    val openPlatform = io.github.timhub135.dsh.feature.account.rememberPlatformOpener()
    // No credential at all, or the account route refused the request (dsh modelSignInRequired).
    val needsSignIn = e.kind == "no_credentials" || e.kind == "no_key" || e.code == "ACCOUNT_SIGN_IN_REQUIRED" || e.code == "ACCOUNT_TOKEN_INVALID"
    val accountQuota = e.code == "ACCOUNT_QUOTA"
    // A third-party endpoint that could not be reached: what it means + what to check (js patch 14).
    val net = remember(e) { NetDiagnose.ofEngineError(e.code, e.message) }
    // The provider answered in a shape the adapter cannot read (js patch 16): not the network.
    val format = remember(e) { NetDiagnose.formatErrorOf(e.code, e.message) }
    // The account has not enabled the model (js patch 18): not the key, not the network.
    val unavailable = remember(e) { NetDiagnose.modelUnavailableOf(e.code, e.message) }
    val title = when {
        needsSignIn -> stringResource(R.string.acct_model_sign_in_required)
        net != null -> stringResource(R.string.net_title)
        unavailable != null -> stringResource(R.string.err_model_unavailable_title)
        accountQuota -> stringResource(R.string.acct_quota_title)
        e.kind == "engine" -> stringResource(R.string.err_engine_title)
        e.kind == "engine_lost" -> stringResource(R.string.err_engine_lost_title)
        else -> stringResource(R.string.turn_error_title)
    }
    val message = when {
        e.kind == "no_credentials" || e.kind == "no_key" -> stringResource(R.string.err_no_key)
        needsSignIn -> ""
        accountQuota -> stringResource(R.string.acct_quota_desc)
        net != null -> stringResource(net.cause.message) + (net.cause.hint?.let { " " + stringResource(it) } ?: "")
        format != null -> stringResource(R.string.err_provider_format, format)
        unavailable != null -> stringResource(R.string.err_model_unavailable, unavailable.model ?: "?", unavailable.serverText)
        e.code?.contains("auth", true) == true || e.message.contains("401") -> stringResource(R.string.err_invalid_key)
        e.message.contains("402") || e.message.contains("insufficient", true) -> stringResource(R.string.err_quota)
        else -> e.message
    }
    Row(Modifier.padding(top = 12.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(c.error))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.error)
            if (message.isNotBlank()) SelectionContainer { Text(message.take(600), style = DshType.xs13, color = c.labelSecondary) }
            if (net != null) SelectionContainer { Text(net.detail.take(300), style = DshType.mono11, color = c.labelTertiary) }
            else if (e.code != null && e.kind != "engine" && !needsSignIn && !accountQuota) Text(e.code.take(300), style = DshType.mono11, color = c.labelTertiary)
            if (e.kind == "engine" && e.code != null) Text(e.code.take(600), style = DshType.mono11, color = c.labelTertiary)
            if (retryable) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    needsSignIn -> {
                        DButton(stringResource(R.string.acct_sign_in), { if (account != null) account.startSignIn() else onOpenSettings("Account") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.acct_add_api_key), { onOpenSettings("Model") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
                        if (e.kind != "no_credentials" && e.kind != "no_key" || account?.signedIn == true)
                            DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    accountQuota -> {
                        DButton(stringResource(R.string.acct_quota_top_up), { openPlatform(account?.state?.value?.topUpUrl ?: "https://platform.deepseek.com/top_up") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    unavailable != null -> {
                        DButton(stringResource(R.string.err_open_model_settings), { onOpenSettings("Model") }, size = BtnSize.Sm)
                        DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                    }
                    e.kind == "engine" || e.kind == "engine_lost" -> {
                        DButton(stringResource(R.string.restart_engine_retry), { onRetry(e) }, size = BtnSize.Sm, icon = R.drawable.dsw_refresh_outline)
                        // Settings → 关于: engine status, 自检 (crash breaker, last exit), engine log, exit record.
                        DButton(stringResource(R.string.err_open_doctor), { onOpenSettings("About") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
                    }
                    else -> DButton(stringResource(R.string.retry), { onRetry(e) }, size = BtnSize.Sm, variant = BtnVariant.Outline, icon = R.drawable.dsw_refresh_outline)
                }
            }
        }
    }
}

/**
 * A turn that ended with work left (#37): why it stopped, the todo progress, what is next, and 继续
 * (only on the last turn while nothing runs). Never looks like a normal reply.
 */
@Composable
private fun EndCard(turn: Turn, end: EndReason, canContinue: Boolean, onContinue: () -> Unit, onOpenSettings: (String) -> Unit) {
    val c = Dsh.colors
    val todos = turn.todoCounts
    val title = when (end) {
        EndReason.PendingWork -> stringResource(R.string.end_pending_title)
        EndReason.ContinueLimit -> stringResource(R.string.end_limit_title, turn.outcome?.nudges ?: 0)
        EndReason.Budget -> stringResource(R.string.end_budget_title)
        else -> stringResource(R.string.output_cap)
    }
    val hint = when (end) {
        EndReason.Budget -> stringResource(R.string.end_budget_hint)
        EndReason.MaxTokens -> stringResource(R.string.output_cap_hint)
        else -> stringResource(R.string.end_continue_hint)
    }
    val next = turn.todos?.firstOrNull { it.status == "in_progress" } ?: turn.todos?.firstOrNull { it.status == "pending" }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(Radius.md).background(c.selector).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.amber)
        if (todos != null && todos.total > 0) Text(stringResource(R.string.end_progress, todos.completed, todos.total), style = DshType.xs13, color = c.labelSecondary)
        next?.let { Text(stringResource(R.string.end_next, it.content), style = DshType.xs13, color = c.labelSecondary) }
        Text(hint, style = DshType.xs13, color = c.labelTertiary)
        if (canContinue) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DButton(stringResource(R.string.continue_task), onContinue, size = BtnSize.Sm)
            if (end == EndReason.Budget) DButton(stringResource(R.string.end_budget_settings), { onOpenSettings("Agent") }, size = BtnSize.Sm, variant = BtnVariant.Outline)
        }
    }
}

/** dsh RunningStatus: whale + "深度求索中，用时 …" (or the running tool's activity, "正在读取屏幕，用时 …") in deep-diving blue with shimmer, ticking each second. */
@Composable
fun RunningIndicator(live: SessionLive, lastTurn: Turn?) {
    val c = Dsh.colors
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val start = lastTurn?.takeIf { it.status == TurnStatus.Running }?.let { it.user?.time ?: it.startTime } ?: live.runStartedAt ?: now
    val text = when {
        live.startingEngine -> stringResource(R.string.starting_engine)
        live.waitingForUnlock -> stringResource(R.string.waiting_unlock)
        live.waitingForNetwork -> stringResource(R.string.waiting_network)
        live.takeover != null -> stringResource(R.string.takeover_title)
        live.approval != null || live.permission != null -> stringResource(R.string.waiting_approval_inline)
        live.question != null -> stringResource(R.string.waiting_answer_inline)
        else -> {
            // A tool is running: name what it does (正在读取屏幕); otherwise the model is thinking / writing.
            val tool = lastTurn?.takeIf { it.status == TurnStatus.Running }?.blocks?.lastOrNull { it is Block.Tool && it.state == ToolState.Running } as Block.Tool?
            if (tool != null) stringResource(R.string.deep_diving_activity, stringResource(ToolPresentation.info(tool.name).activity.live), Fmt.duration(context, now - start))
            else stringResource(R.string.deep_diving, Fmt.duration(context, now - start))
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        if (lastTurn?.blocks?.isNotEmpty() == true) HDivider(Modifier.padding(bottom = 4.dp))
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwimmingWhale(14.dp, c.deepDiving)
            ShimmerText(text, DshType.xxs12.copy(lineHeight = DshType.xxs12.lineHeight), c.deepDiving, true, highlight = c.deepDivingShimmer)
        }
    }
}

/**
 * Prompts DSH itself sends as the user (e.g. the continuation after an engine crash) start with
 * this marker; they render as a system notice, not as the user's own bubble. SessionStore owns the
 * texts (CONTINUE_AFTER_CRASH); keep the prefix in sync with it.
 */
object DshNotice {
    const val PREFIX = "[DSH]"
    fun isNotice(text: String) = text.startsWith(PREFIX)
    fun isCrashContinuation(text: String) = isNotice(text) && "stopped unexpectedly" in text
}

@Composable
private fun DshNoticeRow(text: String) {
    val c = Dsh.colors
    val shown = if (DshNotice.isCrashContinuation(text)) stringResource(R.string.dsh_notice_crash) else text.removePrefix(DshNotice.PREFIX).trim()
    val label = stringResource(R.string.dsh_notice)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $shown" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
        Spacer(Modifier.width(8.dp))
        Text(shown, Modifier.weight(1f, fill = false), style = DshType.xs13, color = c.labelSecondary)
    }
}
