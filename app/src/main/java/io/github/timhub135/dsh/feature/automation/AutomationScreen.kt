package io.github.timhub135.dsh.feature.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import io.github.timhub135.dsh.core.designsystem.EmptyNote
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.RuleDao
import io.github.timhub135.dsh.core.data.RuleEntity
import io.github.timhub135.dsh.core.data.RuleRunEntity
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.DTopBar
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.FilterPill
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.Hairline
import io.github.timhub135.dsh.core.designsystem.KeyValue
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.StateDot
import io.github.timhub135.dsh.core.designsystem.DotKind
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.designsystem.Whale
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.model.Condition
import io.github.timhub135.dsh.core.model.RuleAction
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.Trigger
import io.github.timhub135.dsh.core.trigger.TriggerHub
import io.github.timhub135.dsh.feature.settings.isZh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleRow(val entity: RuleEntity, val spec: RuleSpec?)

@HiltViewModel
class AutomationViewModel @Inject constructor(private val hub: TriggerHub, private val dao: RuleDao, saved: SavedStateHandle) : ViewModel() {
    val rules = dao.observe().map { list -> list.map { RuleRow(it, hub.decode(it)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val ruleId: Long? = saved.get<String>("id")?.toLongOrNull()
    val rule = (ruleId?.let { id -> dao.observeOne(id).map { e -> e?.let { RuleRow(it, hub.decode(it)) } } } ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val runs: Flow<List<RuleRunEntity>> = ruleId?.let { dao.runs(it) } ?: emptyFlow()
    fun toggle(id: Long, on: Boolean) = viewModelScope.launch { hub.setEnabled(id, on) }
    fun delete(id: Long) = viewModelScope.launch { hub.delete(id) }
}

private enum class RuleFilter { All, On, Off }

/**
 * dsh 自动化任务 (ui-schedule) list: search, 全部 / 已开启 / 已关闭 filter strip, R16 hairline
 * rows with an enable switch and last trigger. Rules are created by talking to the agent.
 */
@Composable
fun AutomationScreen(onBack: () -> Unit, onNew: (String) -> Unit, onOpenRule: (Long) -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val zh = isZh()
    val context = LocalContext.current
    val rules by vm.rules.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RuleFilter.All) }
    val shown = rules.filter { r ->
        when (filter) { RuleFilter.All -> true; RuleFilter.On -> r.entity.enabled; RuleFilter.Off -> !r.entity.enabled }
    }.filter { r -> query.isBlank() || (r.spec?.name ?: "").contains(query, true) || (r.spec?.let { describeTrigger(it.trigger, zh) } ?: "").contains(query, true) }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().imePadding()) {
        DTopBar(stringResource(R.string.automation), onBack) {
            DButton(stringResource(R.string.new_), { onNew(context.getString(R.string.new_rule_prefill)) }, size = BtnSize.Sm, icon = R.drawable.dsw_plus_outline, modifier = Modifier.padding(end = 8.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DInput(query, { query = it }, placeholder = stringResource(R.string.search_rules), minHeight = 36.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill(stringResource(R.string.filter_all_rules), filter == RuleFilter.All) { filter = RuleFilter.All }
                FilterPill(stringResource(R.string.filter_on), filter == RuleFilter.On) { filter = RuleFilter.On }
                FilterPill(stringResource(R.string.filter_off), filter == RuleFilter.Off) { filter = RuleFilter.Off }
            }
        }
        if (rules.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Whale(width = 40.dp, tint = c.labelCaption)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.rules_empty_title), style = DshType.base16m, color = c.labelPrimary)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.rules_empty_body), style = DshType.xs13, color = c.labelTertiary)
            Spacer(Modifier.height(16.dp))
            DButton(stringResource(R.string.new_rule_via_chat), { onNew(context.getString(R.string.new_rule_prefill)) }, variant = BtnVariant.Outline)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) item { EmptyNote(stringResource(R.string.no_matching_rules)) }
            items(shown, key = { it.entity.id }) { r -> RuleCard(r, zh, { onOpenRule(r.entity.id) }) { vm.toggle(r.entity.id, it) } }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun RuleCard(r: RuleRow, zh: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(Radius.lg).background(c.bgLayer1).border(Hairline, c.borderL2, Radius.lg).pressable(onClick, Radius.lg)
        .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(Radius.tile).background(c.selector), contentAlignment = Alignment.Center) {
            DIcon(if (r.spec?.trigger is Trigger.Time) R.drawable.dsw_alarm_clock_outline else R.drawable.dsw_clock_outline, size = 18.dp, tint = c.labelSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.spec?.name ?: "#${r.entity.id}", style = DshType.s14m, color = if (r.entity.enabled) c.labelPrimary else c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.spec?.let { Text(describeTrigger(it.trigger, zh), style = DshType.xs13, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.spec?.let { Tag(stringResource(if (it.action is RuleAction.Steps) R.string.rule_fixed_steps else R.string.rule_agent_run), if (it.action is RuleAction.Steps) Tone.Success else Tone.Info) }
                Text(r.entity.lastFiredAt?.let { stringResource(R.string.rule_last_fired, Fmt.relative(context, it)) } ?: stringResource(R.string.rule_never_fired),
                    style = DshType.xxs12, color = c.labelCaption)
            }
        }
        DSwitch(r.entity.enabled, onToggle)
    }
}

/** Rule detail: trigger, conditions, action, whitelisted tools, recent runs (open their sessions), delete. */
@Composable
fun RuleDetailScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit, vm: AutomationViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val zh = isZh()
    val context = LocalContext.current
    val row by vm.rule.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle(emptyList())
    var deleting by remember { mutableStateOf(false) }
    val r = row
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(r?.spec?.name ?: stringResource(R.string.automation), onBack) { r?.let { DSwitch(it.entity.enabled, { on -> vm.toggle(it.entity.id, on) }) }; Spacer(Modifier.width(8.dp)) }
        if (r == null) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            val s = r.spec
            SettingsGroupTitle(stringResource(R.string.rule_when))
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    KeyValue(stringResource(R.string.rule_trigger), s?.let { describeTrigger(it.trigger, zh) } ?: "—")
                    s?.conditions?.forEach { KeyValue(stringResource(R.string.rule_condition), describeCondition(it, zh)) }
                    KeyValue(stringResource(R.string.rule_created), Fmt.dateTime(r.entity.createdAt))
                    KeyValue(stringResource(R.string.rule_last_trigger), r.entity.lastFiredAt?.let { Fmt.dateTime(it) } ?: stringResource(R.string.rule_never_fired))
                }
            }
            SettingsGroupTitle(stringResource(R.string.rule_then))
            SettingsCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val a = s?.action) {
                        is RuleAction.Agent -> { Tag(stringResource(R.string.rule_agent_run), Tone.Info); Text(a.prompt, style = DshType.s14, color = c.labelPrimary) }
                        is RuleAction.Steps -> { Tag(stringResource(R.string.rule_fixed_steps), Tone.Success); a.steps.forEachIndexed { i, st -> Text("${i + 1}. ${st.tool} ${st.args}", style = DshType.mono12, color = c.labelSecondary) } }
                        null -> Text("—")
                    }
                    if (!s?.allowedTools.isNullOrEmpty()) {
                        Text(stringResource(R.string.rule_allowed_tools), style = DshType.xs13m, color = c.labelTertiary)
                        Text(s!!.allowedTools.joinToString(", "), style = DshType.mono12, color = c.labelSecondary)
                    }
                }
            }
            SettingsGroupTitle(stringResource(R.string.rule_recent_runs))
            SettingsCard {
                if (runs.isEmpty()) SettingsRow(stringResource(R.string.rule_no_runs), divider = false)
                runs.forEachIndexed { i, run ->
                    val outcome = when {
                        run.outcome == "ok" -> stringResource(R.string.run_outcome_ok)
                        run.outcome == "started" -> stringResource(R.string.run_outcome_started)
                        run.outcome.startsWith("fail") || run.outcome.startsWith("error") ->
                            stringResource(R.string.run_outcome_failed, run.outcome.substringAfter(':', "").trim().ifEmpty { run.outcome })
                        else -> run.outcome
                    }
                    SettingsRow(Fmt.dateTime(run.firedAt), outcome, onClick = run.sessionId?.let { sid -> { onOpenSession(sid) } }, divider = i > 0,
                        leading = { StateDot(when { run.outcome == "ok" || run.outcome == "started" -> DotKind.Done; run.outcome.startsWith("fail") || run.outcome.startsWith("error") -> DotKind.Error; else -> DotKind.Idle }) },
                        trailing = if (run.sessionId != null) ({ Text(stringResource(R.string.open_session), style = DshType.xs13, color = c.link) }) else null)
                }
            }
            Spacer(Modifier.height(20.dp))
            DButton(stringResource(R.string.delete_rule), { deleting = true }, variant = BtnVariant.Danger, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
    if (deleting && r != null) DDialog({ deleting = false }, stringResource(R.string.delete_rule), buttons = {
        DButton(stringResource(R.string.cancel), { deleting = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { deleting = false; vm.delete(r.entity.id); onBack() }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.delete_rule_body), style = DshType.s14) }
}

fun describeTrigger(t: Trigger, zh: Boolean): String = when (t) {
    is Trigger.Time -> t.at?.let { (if (zh) "每天 " else "Daily ") + it + if (t.days.isNotEmpty()) " (${t.days.joinToString(if (zh) "、" else ", ") { weekday(it, zh) }})" else "" }
        ?: t.everyMinutes?.let { if (zh) "每 $it 分钟" else "Every $it min" }
        ?: t.onceAtEpochMs?.let { (if (zh) "单次 · " else "Once · ") + Fmt.dateTime(it) } ?: (if (zh) "单次" else "Once")
    is Trigger.Wifi -> (if (t.connected) (if (zh) "连上 Wi‑Fi" else "Wi‑Fi connected") else (if (zh) "断开 Wi‑Fi" else "Wi‑Fi disconnected")) + (t.ssid?.let { " $it" } ?: "")
    is Trigger.Bluetooth -> (if (t.connected) (if (zh) "蓝牙连接" else "Bluetooth connected") else (if (zh) "蓝牙断开" else "Bluetooth disconnected")) + (t.deviceName?.let { " $it" } ?: "")
    is Trigger.Power -> if (t.connected) (if (zh) "开始充电" else "Charger connected") else (if (zh) "拔掉充电器" else "Charger removed")
    is Trigger.BatteryLow -> (if (zh) "电量降到 " else "Battery at ") + "${t.percent}%"
    is Trigger.Headset -> if (t.plugged) (if (zh) "插入耳机" else "Headset plugged") else (if (zh) "拔出耳机" else "Headset unplugged")
    is Trigger.Screen -> when (t.event) { "on" -> if (zh) "屏幕点亮" else "Screen on"; "off" -> if (zh) "屏幕关闭" else "Screen off"; else -> if (zh) "解锁手机" else "Phone unlocked" }
    is Trigger.Notification -> (if (zh) "收到通知 " else "Notification ") + listOfNotNull(t.packageName, t.keyword).joinToString(" · ")
    is Trigger.AppOpened -> (if (zh) "打开 " else "Opened ") + t.packageName
    is Trigger.Geofence -> (if (t.enter) (if (zh) "到达" else "Arrive") else (if (zh) "离开" else "Leave")) + " ${"%.4f".format(t.lat)},${"%.4f".format(t.lng)}"
}

fun describeCondition(c: Condition, zh: Boolean): String = when (c) {
    is Condition.TimeRange -> (if (zh) "时间 " else "Time ") + "${c.from}–${c.to}"
    is Condition.Days -> (if (zh) "星期" else "Days ") + c.days.joinToString(if (zh) "、" else ", ") { weekday(it, zh) }
    is Condition.Battery -> (if (zh) "电量 " else "Battery ") + listOfNotNull(c.min?.let { "≥$it%" }, c.max?.let { "≤$it%" }).joinToString(" ")
    is Condition.Charging -> if (c.charging) (if (zh) "充电中" else "Charging") else (if (zh) "未充电" else "Not charging")
}

/** 1 = Monday … 7 = Sunday (ISO), as 一…日 / Mon…Sun. */
private fun weekday(d: Int, zh: Boolean): String =
    runCatching { java.time.DayOfWeek.of(d) }.getOrNull()?.let {
        if (zh) "一二三四五六日"[it.value - 1].toString() else it.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    } ?: d.toString()
