package io.github.timhub135.dsh.feature.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.Chevron
import io.github.timhub135.dsh.core.designsystem.CodeCard
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.DotKind
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshMarkdown
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.ExpandBody
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.KeyValue
import io.github.timhub135.dsh.core.designsystem.Segmented
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.StateDot
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.designsystem.copyText
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.extensions.ExtPlugin
import io.github.timhub135.dsh.core.extensions.ExtStatus
import io.github.timhub135.dsh.core.extensions.ExtText
import io.github.timhub135.dsh.core.extensions.FormField
import io.github.timhub135.dsh.core.extensions.MarketIndex
import io.github.timhub135.dsh.core.extensions.SchemaForm
import kotlinx.serialization.json.JsonPrimitive

/** An installed plugin (§9.4): header, actions, README, what it adds, settings form, advanced. */
@Composable
fun PluginDetailPage(vm: ExtensionsViewModel, name: String, onBack: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val list by vm.store.list.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val errors by vm.actionErrors.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val p = list?.plugins?.firstOrNull { it.name == name }
    LaunchedEffect(name) { vm.loadDetail(name) }
    if (p == null) {
        // Uninstalled from this page (or gone after a restart).
        LaunchedEffect(list) { if (list != null) onBack() }
        return
    }
    var confirmRemove by remember { mutableStateOf(false) }

    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow(p.displayTitle, statusLine(p), divider = false, leading = { PluginIcon(p.iconDataUrl, statusDot(p)) },
            trailing = { if (name in busy) Spinner(14.dp) else if (p.status != ExtStatus.SUSPENDED) DSwitch(p.enabled, { vm.setEnabled(name, it) }) })
        p.descriptionText?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = DshType.xs13, color = c.labelSecondary) }
        p.suspended?.let { s ->
            SettingsRow(if (s.reason == "crash") stringResource(R.string.ext_suspended_crash, Fmt.relative(context, s.at)) else stringResource(R.string.ext_suspended_safe),
                s.message, leading = { StateDot(DotKind.Warn) },
                trailing = { DButton(stringResource(R.string.ext_resume), { vm.resume(name) }, size = BtnSize.Sm) })
        }
        if (p.status in setOf(ExtStatus.FAILED, ExtStatus.WAITING, ExtStatus.INCOMPATIBLE)) {
            val waiting = p.rows.flatMap { it.waitingFor }.distinct()
            SettingsRow(p.statusDetail?.let { ExtText.error(context, it) } ?: stringResource(R.string.ext_status_failed),
                if (waiting.isNotEmpty()) stringResource(R.string.ext_waiting_for, waiting.joinToString(", ")) else null,
                leading = { StateDot(if (p.status == ExtStatus.FAILED) DotKind.Error else DotKind.Warn) })
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            p.update?.let { u ->
                if (u.compatible) DButton(stringResource(R.string.ext_update_to, u.version), { vm.update(name, u.version) }, size = BtnSize.Sm, enabled = name !in busy)
                else Text(stringResource(R.string.ext_update_needs_app, u.version), Modifier.weight(1f), style = DshType.xxs12, color = c.labelTertiary)
            }
            DButton(stringResource(R.string.ext_uninstall), { confirmRemove = true }, variant = BtnVariant.Danger, size = BtnSize.Sm, enabled = name !in busy)
        }
        errors[name]?.let { Text(ExtText.error(context, it), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = DshType.xxs12, color = c.error) }
    }

    SettingsCard(Modifier.padding(top = 12.dp)) {
        SettingsRow(stringResource(R.string.ext_ask_before_call), stringResource(R.string.ext_ask_before_call_desc), divider = false,
            trailing = { DSwitch(p.askBeforeCall, { vm.setAskBeforeCall(name, it) }) })
    }

    Provides(p)

    // Settings: one form per configurable row (instant save through config-editor).
    val configurable = p.rows.filter { it.configurable && it.entryId != null }
    if (configurable.isNotEmpty()) {
        SettingsGroupTitle(stringResource(R.string.ext_settings))
        configurable.forEach { row ->
            if (configurable.size > 1) Text(row.id, Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), style = DshType.mono11, color = c.labelTertiary)
            androidx.compose.runtime.key(row.entryId) { ConfigForm(vm, row.entryId!!) }
        }
    }

    // README (dsh's detail: locale meta.detail → README → repository README).
    val detail = details[name]
    SettingsGroupTitle("README")
    SettingsCard {
        Box(Modifier.padding(16.dp)) {
            when {
                detail == null -> Spinner(14.dp)
                detail.markdown.isNullOrBlank() -> Text(stringResource(R.string.ext_readme_none), style = DshType.xs13, color = c.labelTertiary)
                else -> DshMarkdown(detail.markdown.take(60_000))
            }
        }
    }

    Advanced(vm, p)

    if (confirmRemove) DDialog({ confirmRemove = false }, stringResource(R.string.ext_uninstall), buttons = {
        DButton(stringResource(R.string.cancel), { confirmRemove = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.ext_uninstall), { confirmRemove = false; vm.remove(name) }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.ext_uninstall_confirm, p.displayTitle), style = DshType.s14, color = c.labelSecondary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Provides(p: ExtPlugin) {
    val c = Dsh.colors
    if (p.tools.isEmpty() && p.skills.isEmpty() && p.commands.isEmpty() && p.overrides.isEmpty()) return
    SettingsGroupTitle(stringResource(R.string.ext_provides))
    val groups = listOf(
        Triple(R.string.ext_tools, p.tools, Tone.Neutral),
        Triple(R.string.ext_skills, p.skills, Tone.Neutral),
        Triple(R.string.ext_commands, p.commands.map { "/$it" }, Tone.Neutral),
        Triple(R.string.ext_overrides, p.overrides.map { it.label }, Tone.Warn),
    ).filter { it.second.isNotEmpty() }
    SettingsCard {
        groups.forEachIndexed { i, (title, items, tone) ->
            SettingsRow(stringResource(title), null, divider = i > 0, stacked = true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { Tag(it, tone, mono = tone == Tone.Neutral) } }
            }
        }
    }
    Text(stringResource(R.string.ext_hardline_note), Modifier.padding(start = 4.dp, top = 6.dp), style = DshType.xxs12, color = c.labelTertiary)
}

/** 高级: components (per-row switches), source, size, skipped optional deps, last error, install log. */
@Composable
private fun Advanced(vm: ExtensionsViewModel, p: ExtPlugin) {
    val c = Dsh.colors
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val log by vm.log.collectAsStateWithLifecycle()
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().pressable({ open = !open }).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ext_advanced), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
        Chevron(open = open)
    }
    ExpandBody(open) {
        SettingsCard {
            Text(stringResource(R.string.ext_components), Modifier.padding(start = 16.dp, top = 12.dp), style = DshType.xs13m, color = c.labelTertiary)
            p.rows.forEach { r ->
                SettingsRow(r.id, listOfNotNull(r.module.takeIf { it.isNotBlank() }, r.phase, r.error?.lineSequence()?.firstOrNull(),
                    r.waitingFor.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ext_waiting_for, it.joinToString(", ")) }).joinToString(" · "),
                    leading = { StateDot(when { r.error != null -> DotKind.Error; r.phase == "active" -> DotKind.Done; r.waitingFor.isNotEmpty() -> DotKind.Warn; else -> DotKind.Idle }) },
                    trailing = { r.entryId?.let { e -> DSwitch(r.isEnabled, { vm.setRowEnabled(p.name, e, it) }, enabled = p.enabled) } })
            }
            Column(Modifier.padding(16.dp)) {
                p.source?.let { KeyValue(stringResource(R.string.ext_source_spec), it.spec.ifBlank { it.kind }, mono = true) }
                p.sizeBytes?.let { KeyValue(stringResource(R.string.ext_size), android.text.format.Formatter.formatShortFileSize(context, it)) }
                p.license?.let { KeyValue(stringResource(R.string.ext_card_license), it) }
                p.publisher?.let { KeyValue(stringResource(R.string.ext_card_publisher), it) }
                if (p.skippedOptional.isNotEmpty()) KeyValue(stringResource(R.string.ext_skipped_optional, p.skippedOptional.size), p.skippedOptional.joinToString(", "), mono = true)
            }
            val lastError = p.statusDetail?.detail ?: p.rows.firstNotNullOfOrNull { it.error }
            lastError?.let { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { CodeCard(it, label = stringResource(R.string.ext_last_error), maxHeight = 200.dp) } }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DButton(stringResource(R.string.ext_copy_diag), {
                    copyText(context, listOfNotNull("plugin: ${p.name}@${p.version}", "status: ${p.status}", p.source?.spec, lastError, log?.text?.takeLast(4000)).joinToString("\n"))
                }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                DButton(stringResource(R.string.ext_view_log), { if (log == null) vm.loadLog(p.name) else vm.closeLog() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            }
            log?.let { l -> Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { CodeCard(l.text.ifBlank { "—" }, label = l.path ?: "log", maxHeight = 320.dp) } }
        }
    }
}

/** A plugin from an index that is not installed: what the index says, README, and 安装. */
@Composable
fun MarketEntryPage(vm: ExtensionsViewModel, url: String, name: String, onInstalled: () -> Unit) {
    val c = Dsh.colors
    val market by vm.market.state.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val lang = language()
    val e = market.entries.firstOrNull { it.first == url && it.second.name == name }?.second ?: market.entries.firstOrNull { it.second.name == name }?.second
    LaunchedEffect(name) { vm.loadDetail(name) }
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow(e?.title(lang) ?: name, listOfNotNull(e?.author, e?.installVersion?.version).joinToString(" · ").ifEmpty { null }, divider = false,
            leading = { PluginIcon(e?.icon, null) })
        e?.description?.get(lang)?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = DshType.xs13, color = c.labelSecondary) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            KeyValue(stringResource(R.string.ext_card_source), url, mono = true)
            e?.repository?.let { KeyValue("GitHub", it.url + (it.directory?.let { d -> "/$d" } ?: ""), mono = true) }
            e?.installVersion?.engines?.get("dsh")?.let { KeyValue("engines.dsh", it, mono = true) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            val v = e?.installVersion
            DButton(stringResource(R.string.ext_install), { vm.install.start(v?.let { MarketIndex.installSpec(name, it) } ?: name) }, size = BtnSize.Sm)
        }
    }
    val detail = details[name]
    SettingsGroupTitle("README")
    SettingsCard {
        Box(Modifier.padding(16.dp)) {
            when {
                detail == null -> Spinner(14.dp)
                detail.markdown.isNullOrBlank() -> Text(stringResource(R.string.ext_readme_none), style = DshType.xs13, color = c.labelTertiary)
                else -> DshMarkdown(detail.markdown.take(60_000))
            }
        }
    }
    val list by vm.store.list.collectAsStateWithLifecycle()
    LaunchedEffect(list) { if (list?.plugins?.any { it.name == name } == true) onInstalled() }
}

// ---- settings form (§7.2) -------------------------------------------------------------------------

@Composable
private fun ConfigForm(vm: ExtensionsViewModel, entryId: String) {
    val c = Dsh.colors
    val editor = remember(entryId) { vm.configEditor(entryId) }
    val s by editor.state.collectAsStateWithLifecycle()
    SettingsCard {
        when {
            s.loading -> Box(Modifier.padding(16.dp)) { Spinner(14.dp) }
            s.loadError != null -> SettingsRow(ExtText.error(LocalContext.current, s.loadError!!), divider = false, leading = { StateDot(DotKind.Warn) })
            s.fields.isEmpty() -> SettingsRow(stringResource(R.string.ext_settings_none), divider = false)
            else -> s.fields.forEachIndexed { i, f -> FieldView(editor, s, f, divider = i > 0) }
        }
        if (s.saving || s.saved || s.errors[""] != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.saving) { Spinner(12.dp); Spacer(Modifier.width(6.dp)) }
            Text(s.errors[""] ?: stringResource(if (s.saving) R.string.ext_settings_saving else R.string.ext_settings_saved), style = DshType.xxs12,
                color = if (s.errors[""] != null) c.error else c.labelTertiary)
        }
    }
}

@Composable
private fun FieldView(editor: ConfigEditor, s: ConfigEditor.State, f: FormField, divider: Boolean) {
    val c = Dsh.colors
    val key = f.path.joinToString(".")
    val error = s.errors[key]
    val desc = listOfNotNull(f.description, if (f.locked) stringResource(R.string.ext_setting_locked) else null, error).joinToString("\n").ifEmpty { null }
    val enabled = !f.locked
    when (f) {
        is FormField.Toggle -> SettingsRow(f.title, desc, divider = divider, titleColor = if (error != null) c.error else c.labelPrimary,
            trailing = { DSwitch(SchemaForm.boolOf(s.value, f), { editor.set(f.path, JsonPrimitive(it)) }, enabled = enabled) })
        is FormField.Text -> SettingsRow(f.title, desc, divider = divider, stacked = true) {
            val options = f.options
            if (options != null && options.size <= 4) {
                val cur = SchemaForm.textOf(s.value, f.path).ifEmpty { f.default.orEmpty() }
                Segmented(options, options.indexOf(cur), { i -> if (enabled) editor.set(f.path, JsonPrimitive(options[i])) })
            } else if (options != null) {
                var open by remember { mutableStateOf(false) }
                val cur = SchemaForm.textOf(s.value, f.path).ifEmpty { f.default.orEmpty() }
                Box {
                    DButton(cur.ifEmpty { "—" }, { if (enabled) open = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    io.github.timhub135.dsh.core.designsystem.DMenu(open, { open = false }) {
                        options.forEach { o -> io.github.timhub135.dsh.core.designsystem.MenuItem(o, { open = false; editor.set(f.path, JsonPrimitive(o)) }, selected = o == cur) }
                    }
                }
            } else {
                var text by remember(key) { mutableStateOf(SchemaForm.textOf(s.value, f.path)) }
                if (enabled) DInput(text, { text = it; editor.set(f.path, it.takeIf { v -> v.isNotEmpty() }?.let(::JsonPrimitive)) },
                    placeholder = f.default?.let { if (f.secret) "" else it }.orEmpty(), secret = f.secret, singleLine = !f.multiline,
                    minHeight = if (f.multiline) 80.dp else 40.dp)
                else Text(text.ifEmpty { f.default ?: "—" }.let { if (f.secret && it.isNotEmpty()) "••••••" else it }, style = DshType.s14, color = c.labelSecondary)
                if (f.secret) Text(stringResource(R.string.ext_setting_secret_note), Modifier.padding(top = 4.dp), style = DshType.xxs12, color = c.labelTertiary)
            }
        }
        is FormField.Number -> SettingsRow(f.title, desc, divider = divider, stacked = true) {
            var text by remember(key) { mutableStateOf(SchemaForm.numberText(s.value, f.path)) }
            val invalid = stringResource(R.string.ext_number_invalid)
            if (enabled) DInput(text, { t ->
                text = t
                SchemaForm.parseNumber(t, f).onSuccess { editor.set(f.path, it) }.onFailure { editor.invalid(f.path, invalid) }
            }, placeholder = f.default?.let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() }.orEmpty(),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
            else Text(text.ifEmpty { "—" }, style = DshType.s14, color = c.labelSecondary)
            if (f.min != null && f.max != null) Text(stringResource(R.string.ext_number_range, fmt(f.min), fmt(f.max)), Modifier.padding(top = 4.dp), style = DshType.xxs12, color = c.labelTertiary)
        }
        is FormField.StringList -> SettingsRow(f.title, desc, divider = divider, stacked = true) {
            var text by remember(key) { mutableStateOf(SchemaForm.listText(s.value, f.path)) }
            if (enabled) DInput(text, { text = it; editor.set(f.path, SchemaForm.parseList(it)) }, placeholder = f.default?.joinToString("\n") ?: stringResource(R.string.ext_setting_list_hint),
                singleLine = false, minHeight = 72.dp)
            else Text(text.ifEmpty { "—" }, style = DshType.s14, color = c.labelSecondary)
        }
        is FormField.Group -> {
            var open by rememberSaveable(key) { mutableStateOf(!f.collapsed) }
            SettingsRow(f.title, f.description, divider = divider, onClick = { open = !open }, trailing = { Chevron(open = open) })
            ExpandBody(open) { Column(Modifier.padding(start = 12.dp)) { f.fields.forEachIndexed { i, sub -> FieldView(editor, s, sub, divider = true) } } }
        }
        is FormField.Raw -> RawField(editor, s, f, desc, divider)
    }
}

/** 高级：以代码编辑 — one field as JSON (valid YAML), applied on 应用. */
@Composable
private fun RawField(editor: ConfigEditor, s: ConfigEditor.State, f: FormField.Raw, desc: String?, divider: Boolean) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val current = SchemaForm.valueAt(s.value, f.path)
    var text by remember(f.path) { mutableStateOf(current?.let { RpcJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }.orEmpty()) }
    var bad by remember { mutableStateOf(false) }
    SettingsRow(f.title.ifEmpty { stringResource(R.string.ext_setting_raw) }, desc ?: stringResource(R.string.ext_setting_raw), divider = divider, onClick = { open = !open },
        trailing = { Chevron(open = open) })
    ExpandBody(open) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DInput(text, { text = it; bad = false }, placeholder = f.default?.toString() ?: "{}", singleLine = false, minHeight = 96.dp)
            if (bad) Text(stringResource(R.string.ext_setting_raw_invalid), style = DshType.xxs12, color = c.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DButton(stringResource(R.string.apply), {
                    if (text.isBlank()) editor.set(f.path, null)
                    else runCatching { RpcJson.parseToJsonElement(text) }.onSuccess { editor.set(f.path, it) }.onFailure { bad = true }
                }, size = BtnSize.Sm, enabled = !f.locked)
            }
        }
    }
}

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
