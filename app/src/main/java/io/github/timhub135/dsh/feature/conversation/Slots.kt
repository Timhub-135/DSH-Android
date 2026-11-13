package io.github.timhub135.dsh.feature.conversation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DCheckbox
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Hairline
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.designsystem.elevated
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.session.ApprovalOutcome
import io.github.timhub135.dsh.core.session.Block
import io.github.timhub135.dsh.core.session.PendingApproval
import io.github.timhub135.dsh.core.session.PendingPermission
import io.github.timhub135.dsh.core.session.PendingTakeover
import io.github.timhub135.dsh.core.session.TakeoverAnswer
import io.github.timhub135.dsh.core.session.PendingQuestion
import io.github.timhub135.dsh.core.session.ToolState
import io.github.timhub135.dsh.core.session.parseArgs
import io.github.timhub135.dsh.feature.settings.PermissionCatalog
import io.github.timhub135.dsh.feature.settings.isZh

/** The approval-style card frame: warn border, warn strip, scrolling body, right-aligned actions. */
@Composable
private fun AttentionCard(strip: String, answered: Boolean, body: @Composable ColumnScope.() -> Unit, actions: @Composable () -> Unit) {
    val c = Dsh.colors
    val settled = rememberSettled(400)
    Column(Modifier.fillMaxWidth().ignoreTapsUntil(settled).elevated(Radius.xl, c.warnSecondary, 6.dp).border(1.dp, c.warnSecondary, Radius.xl).clip(Radius.xl).background(c.inputMajor)) {
        Row(Modifier.fillMaxWidth().background(c.warnTertiary).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (answered) Spinner(10.dp, c.warn) else Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
            Spacer(Modifier.width(8.dp))
            Text(strip, style = DshType.xs13.copy(lineHeight = DshType.xxs12.lineHeight), color = c.warnLabel)
        }
        Column(Modifier.weight(1f, fill = false).heightIn(max = 336.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = body)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { actions() }
    }
}

/** dsh ApprovalCard — replaces the composer: 拒绝 / 允许一次 / 始终允许. */
@Composable
fun ApprovalCard(a: PendingApproval, onAnswer: (ApprovalOutcome) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    var answered by remember(a.requestKey) { mutableStateOf(false) }
    ReenableAfter(answered) { answered = false }
    val info = ToolPresentation.info(a.toolName)
    val pseudo = Block.Tool("", a.callId ?: "", a.toolName, a.arguments ?: "", parseArgs(a.arguments), ToolState.Running, null, null, null, 0, null)
    val summary = ToolPresentation.summary(context, pseudo)
    AttentionCard(stringResource(R.string.waiting_approval), answered, body = {
        Text(a.reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.approval_headline, stringResource(info.title)),
            style = DshType.s14m.copy(fontSize = DshType.base16.fontSize * 0.94f, lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(a.toolName, mono = true)
            if (a.risk == Risk.dangerous) Tag(stringResource(R.string.risk_dangerous), Tone.Error)
            else if (a.risk == Risk.sensitive) Tag(stringResource(R.string.risk_sensitive), Tone.Warn)
        }
        if (!summary.isNullOrBlank()) Text(summary, style = DshType.xs13, color = c.labelSecondary)
        if (!a.arguments.isNullOrBlank() && a.arguments != "{}") Text(prettyJson(a.arguments).take(1500), style = DshType.mono13, color = c.labelTertiary)
        if (a.risk == Risk.dangerous) Text(stringResource(R.string.approval_dangerous_note), style = DshType.xs13, color = c.labelSecondary)
    }) {
        DButton(stringResource(R.string.reject), { answered = true; onAnswer(ApprovalOutcome.Reject) }, variant = BtnVariant.Outline, enabled = !answered)
        DButton(stringResource(R.string.allow_once), { answered = true; onAnswer(ApprovalOutcome.AllowOnce) },
            variant = if (a.canAlwaysAllow) BtnVariant.Outline else BtnVariant.Primary, enabled = !answered)
        if (a.canAlwaysAllow) DButton(stringResource(R.string.always_allow), { answered = true; onAnswer(ApprovalOutcome.AllowSession) }, enabled = !answered)
    }
}

/** The composer slot's permission wait: the Android permission card. */
@Composable
fun PermissionCard(p: PendingPermission, onAnswer: (Boolean) -> Unit) {
    SystemPermissionCard(p, onAnswer)
}

/**
 * Just-in-time Android permission card in the composer slot: 拒绝 / 去授权, then 继续 after the
 * user comes back from the system page (auto-continues when the permission is now granted).
 */
@Composable
private fun SystemPermissionCard(p: PendingPermission, onAnswer: (Boolean) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val zh = isZh()
    val item = remember(p.permissionId) { p.permissionId?.let { PermissionCatalog.byId(it) } }
    var launched by remember(p.requestKey) { mutableStateOf(false) }
    var answered by remember(p.requestKey) { mutableStateOf(false) }
    ReenableAfter(answered) { answered = false }
    /** "Don't ask again": the system dialog no longer shows, so 去授权 opens the app's settings page. */
    var blocked by remember(p.requestKey) { mutableStateOf(false) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) { answered = true; onAnswer(true) }
        else { launched = true; blocked = PermissionCatalog.permanentlyDenied(context, res.keys) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, item, launched) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && launched && !answered && item?.check?.invoke(context) == true) { answered = true; onAnswer(true) }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    AttentionCard(stringResource(R.string.perm_card_strip), answered, body = {
        Text(item?.title(zh) ?: p.what, style = DshType.s14m.copy(lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
        Text(stringResource(R.string.perm_card_body, stringResource(ToolPresentation.info(p.toolName).title)), style = DshType.xs13, color = c.labelSecondary)
        item?.why(zh)?.let { Text(it, style = DshType.xs13, color = c.labelTertiary) }
        if (blocked) Text(stringResource(R.string.perm_denied_forever), style = DshType.xxs12, color = c.labelSecondary)
        if (item?.restrictedSetting == true) Text(stringResource(R.string.restricted_hint), style = DshType.xxs12, color = c.labelSecondary)
        if (item == null) Text(stringResource(R.string.perm_card_manual), style = DshType.xxs12, color = c.labelSecondary)
    }) {
        DButton(stringResource(R.string.reject), { answered = true; onAnswer(false) }, variant = BtnVariant.Outline, enabled = !answered)
        if (item != null) DButton(stringResource(if (blocked) R.string.open_system_settings else R.string.go_grant), {
            val rt = item.runtime
            if (rt != null && !blocked) runtime.launch(rt.toTypedArray())
            else if (rt != null) runCatching { context.startActivity(PermissionCatalog.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else (item.intent(context) ?: PermissionCatalog.appDetails(context)).let { i ->
                runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { context.startActivity(PermissionCatalog.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                launched = true
            }
        }, variant = if (launched) BtnVariant.Outline else BtnVariant.Primary, enabled = !answered)
        if (launched || item == null) DButton(stringResource(R.string.continue_), { answered = true; onAnswer(true) }, enabled = !answered)
    }
}

/**
 * Takeover card (docs/AGENT-SCREEN-V2.md §2): 跳过 / 我来处理, then — with the app on the user's
 * screen — 不做了 / 我自己做完了 / 处理好了.
 */
@Composable
fun TakeoverCard(t: PendingTakeover, onAnswer: (TakeoverAnswer) -> Unit) {
    val c = Dsh.colors
    var answered by remember(t.requestKey, t.stage) { mutableStateOf(false) }
    ReenableAfter(answered) { answered = false }
    fun answer(a: TakeoverAnswer) { answered = true; onAnswer(a) }
    AttentionCard(stringResource(R.string.takeover_title), answered, body = {
        Text(t.reason, style = DshType.s14m.copy(lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
        Tag(stringResource(takeoverKind(t.kind)), Tone.Warn)
        if (t.stage == PendingTakeover.Stage.HandedOver)
            Text(stringResource(if (t.moved) R.string.takeover_moved else R.string.takeover_here), style = DshType.xs13, color = c.labelSecondary)
    }) {
        if (t.stage == PendingTakeover.Stage.Asking) {
            DButton(stringResource(R.string.takeover_skip), { answer(TakeoverAnswer.Skip) }, variant = BtnVariant.Outline, enabled = !answered)
            DButton(stringResource(R.string.takeover_start), { answer(TakeoverAnswer.Start) }, enabled = !answered)
        } else {
            DButton(stringResource(R.string.takeover_abort), { answer(TakeoverAnswer.Abort) }, variant = BtnVariant.Outline, enabled = !answered)
            DButton(stringResource(R.string.takeover_done_by_user), { answer(TakeoverAnswer.DoneByUser) }, variant = BtnVariant.Outline, enabled = !answered)
            DButton(stringResource(R.string.takeover_handled), { answer(TakeoverAnswer.Handled) }, enabled = !answered)
        }
    }
}

private fun takeoverKind(kind: String) = when (kind) {
    "login" -> R.string.takeover_kind_login
    "captcha" -> R.string.takeover_kind_captcha
    "payment" -> R.string.takeover_kind_payment
    "secure_page" -> R.string.takeover_kind_secure_page
    "choose" -> R.string.takeover_kind_choose
    else -> R.string.takeover_kind_other
}

/** dsh QuestionComposer — replaces the composer: options with index badges, custom answer, pager. */
@Composable
fun QuestionCard(q: PendingQuestion, onSubmit: (Map<String, Pair<List<String>, String?>>) -> Unit, onSkip: () -> Unit) {
    val c = Dsh.colors
    var page by remember(q.requestKey) { mutableStateOf(0) }
    val selected = remember(q.requestKey) { mutableStateMapOf<String, Set<String>>() }
    val custom = remember(q.requestKey) { mutableStateMapOf<String, String>() }
    var submitting by remember(q.requestKey) { mutableStateOf(false) }
    var invalid by remember(q.requestKey) { mutableStateOf(false) }
    val question = q.questions.getOrNull(page) ?: return
    val settled = rememberSettled(400)
    LaunchedEffect(submitting) { if (submitting) { delay(15_000); submitting = false } }
    Column(Modifier.fillMaxWidth().ignoreTapsUntil(settled).elevated(Radius.xl, c.borderL2, 6.dp).clip(Radius.xl).background(c.inputMajor)) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 12.dp, top = 12.dp)) {
            question.header?.let { Text(it, style = DshType.xxxs11, color = c.labelTertiary) }
            Text(question.text, style = DshType.base16m.copy(fontSize = DshType.base16.fontSize * 0.94f, lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
            question.detail?.let { Text(it, Modifier.padding(top = 4.dp), style = DshType.xs13, color = c.labelSecondary) }
            Spacer(Modifier.size(8.dp))
            question.options.forEachIndexed { i, opt ->
                val on = opt.label in (selected[question.id] ?: emptySet())
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Radius.md)
                    .then(if (on) Modifier.background(c.hover).border(Hairline, c.borderL2, Radius.md) else Modifier)
                    .pressable({
                        invalid = false
                        val cur = selected[question.id] ?: emptySet()
                        selected[question.id] = if (question.multi) (if (on) cur - opt.label else cur + opt.label) else setOf(opt.label)
                    }, Radius.md).padding(6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (question.multi) Box(Modifier.padding(top = 4.dp)) { DCheckbox(on, null) }
                    else Box(Modifier.padding(top = 2.dp).size(20.dp).clip(Radius.xs).background(if (on) c.brandPrimary else c.selector), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = DshType.xxs12m, color = if (on) c.labelOnPrimary else c.labelSecondary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(opt.label, style = DshType.s14m.copy(lineHeight = DshType.body.lineHeight), color = c.labelPrimary)
                        opt.description?.let { Text(it, style = DshType.xs13, color = c.labelTertiary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                }
            }
            Box(Modifier.padding(vertical = 6.dp)) {
                DInput(custom[question.id] ?: "", { custom[question.id] = it; invalid = false }, placeholder = stringResource(R.string.q_custom), singleLine = false)
            }
            if (invalid) Text(stringResource(R.string.q_invalid), style = DshType.xxs12, color = c.error)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (q.questions.size > 1) {
                IconBtn(R.drawable.dsw_chevron_left_outline, { page-- }, enabled = page > 0, contentDescription = stringResource(R.string.prev_question))
                Text("${page + 1} / ${q.questions.size}", style = DshType.xxs12, color = c.labelTertiary)
                IconBtn(R.drawable.dsw_chevron_right_outline, { page++ }, enabled = page < q.questions.lastIndex, contentDescription = stringResource(R.string.next))
            }
            Spacer(Modifier.weight(1f))
            DButton(stringResource(R.string.skip), onSkip, variant = BtnVariant.Outline, enabled = !submitting)
            Spacer(Modifier.width(8.dp))
            val last = page == q.questions.lastIndex
            DButton(stringResource(if (submitting) R.string.submitting else if (last) R.string.submit else R.string.next), {
                val has = !selected[question.id].isNullOrEmpty() || !custom[question.id].isNullOrBlank()
                if (!has) { invalid = true; return@DButton }
                if (!last) { page++; return@DButton }
                submitting = true
                onSubmit(q.questions.associate { it.id to ((selected[it.id]?.toList() ?: emptyList()) to custom[it.id]) })
            }, enabled = !submitting)
        }
    }
}

@Composable
fun FieldLabel(text: String) = Text(text, style = DshType.xs13.copy(fontWeight = FontWeight(500)), color = Dsh.colors.labelSecondary)

/** A card stays answered (buttons disabled) while the reply is in flight; re-enable if it is still here after a while. */
@Composable
private fun ReenableAfter(answered: Boolean, ms: Long = 15_000, reset: () -> Unit) {
    LaunchedEffect(answered) { if (answered) { delay(ms); reset() } }
}
