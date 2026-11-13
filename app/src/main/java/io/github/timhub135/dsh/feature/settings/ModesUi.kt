package io.github.timhub135.dsh.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.RiskConfirmDialog
import io.github.timhub135.dsh.core.designsystem.Segmented
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.model.EngineTool
import io.github.timhub135.dsh.core.model.ModeIcon
import io.github.timhub135.dsh.core.model.PermissionPreset
import io.github.timhub135.dsh.core.model.SessionMode
import io.github.timhub135.dsh.core.model.SessionModes
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.core.model.modeDisplayName
import io.github.timhub135.dsh.feature.conversation.ModeUi
import io.github.timhub135.dsh.feature.conversation.PresetUi
import kotlinx.coroutines.launch

/** Settings sub-page target for a new mode (other targets are mode ids). */
const val NEW_MODE = "\u0000new"

private fun toolLabel(t: EngineTool) = when (t) {
    EngineTool.Shell -> R.string.mode_tool_shell to R.string.mode_tool_shell_desc
    EngineTool.FilesRead -> R.string.mode_tool_files_read to R.string.mode_tool_files_read_desc
    EngineTool.FilesWrite -> R.string.mode_tool_files_write to R.string.mode_tool_files_write_desc
    EngineTool.Web -> R.string.mode_tool_web to R.string.mode_tool_web_desc
    EngineTool.Subagent -> R.string.mode_tool_subagent to R.string.mode_tool_subagent_desc
    EngineTool.Extensions -> R.string.mode_tool_extensions to R.string.mode_tool_extensions_desc
}

@Composable
private fun chevron() = DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary)

/** 设置 → 会话模式: built-in modes (view / duplicate) and the user's own (edit). */
@Composable
fun ModesSection(vm: SettingsViewModel, onOpen: (String) -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    Text(stringResource(R.string.set_modes_desc), Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsGroupTitle(stringResource(R.string.modes_builtin))
    SettingsCard {
        SessionModes.builtIns.forEachIndexed { i, m ->
            SettingsRow(ModeUi.name(m), ModeUi.summary(m), divider = i > 0, onClick = { onOpen(m.id) },
                leading = { DIcon(ModeUi.icon(m), size = 18.dp, tint = c.labelSecondary) }, trailing = { chevron() })
        }
    }
    SettingsGroupTitle(stringResource(R.string.modes_custom))
    SettingsCard {
        s.modes.forEachIndexed { i, m ->
            SettingsRow(ModeUi.name(m), ModeUi.summary(m), divider = i > 0, onClick = { onOpen(m.id) },
                leading = { DIcon(ModeUi.icon(m), size = 18.dp, tint = c.labelSecondary) }, trailing = { chevron() })
        }
        SettingsRow(stringResource(R.string.mode_new), divider = s.modes.isNotEmpty(), onClick = { onOpen(NEW_MODE) },
            leading = { DIcon(R.drawable.dsw_plus_outline, size = 18.dp, tint = c.labelSecondary) })
    }
}

/** One mode: a read-only view with 复制 for built-ins, the editor for the user's modes. */
@Composable
fun ModePage(vm: SettingsViewModel, target: String, onDone: () -> Unit, onOpen: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val existing = if (target == NEW_MODE) null else s.allModes.firstOrNull { it.id == target }
    if (target != NEW_MODE && existing == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }; return }
    val copyName = existing?.let { stringResource(R.string.mode_copy_name, ModeUi.name(it)) } ?: ""
    val duplicate: (SessionMode) -> Unit = { m ->
        val id = SessionModes.newCustomId(s.allModes.map { it.id })
        val copy = m.duplicate(id, copyName)
        scope.launch { vm.saveMode(copy); onOpen(id) }
    }
    androidx.compose.runtime.key(target) {
        ModeEditor(
            initial = existing ?: SessionMode(SessionModes.newCustomId(s.allModes.map { it.id }), icon = ModeIcon.Pen.id),
            readOnly = existing?.builtIn == true,
            isNew = existing == null,
            onSave = { m -> scope.launch { vm.saveMode(m); onDone() } },
            onDelete = { m -> scope.launch { vm.deleteMode(m.id); onDone() } },
            onDuplicate = duplicate,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeEditor(
    initial: SessionMode,
    readOnly: Boolean,
    isNew: Boolean,
    onSave: (SessionMode) -> Unit,
    onDelete: (SessionMode) -> Unit,
    onDuplicate: (SessionMode) -> Unit,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(if (initial.builtIn) modeDisplayName(context, initial) else initial.name) }
    var icon by rememberSaveable { mutableStateOf(initial.icon) }
    var prompt by rememberSaveable { mutableStateOf(initial.prompt) }
    /** Empty = 跟随通用设置. */
    var preset by rememberSaveable { mutableStateOf(initial.preset ?: "") }
    var skills by rememberSaveable { mutableStateOf(initial.skills) }
    val groups = remember { mutableStateListOf<String>().apply { addAll(initial.deviceGroups ?: ToolGroup.entries.map { it.id }) } }
    val tools = remember { mutableStateListOf<String>().apply { addAll(initial.engineTools ?: EngineTool.entries.map { it.id }) } }
    var nameError by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val enabled = !readOnly

    if (readOnly) Text(stringResource(R.string.mode_builtin_note), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    Spacer(Modifier.height(8.dp))
    SettingsCard {
        SettingsRow(stringResource(R.string.mode_name), if (nameError) stringResource(R.string.mode_name_required) else null, divider = false, stacked = true) {
            if (enabled) DInput(name, { name = it.take(30); nameError = false }) else Text(name, style = DshType.s14, color = c.labelSecondary)
        }
        SettingsRow(stringResource(R.string.mode_icon), stacked = true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeIcon.entries.forEach { i ->
                    val on = i.id == icon
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) c.businessTertiary else c.selector)
                        .pressable({ if (enabled) icon = i.id }, CircleShape, enabled), contentAlignment = Alignment.Center) {
                        DIcon(ModeUi.icon(i), size = 18.dp, tint = if (on) c.business else c.labelSecondary)
                    }
                }
            }
        }
        SettingsRow(stringResource(R.string.mode_prompt), stringResource(R.string.mode_prompt_desc), stacked = true) {
            if (enabled) DInput(prompt, { prompt = it.take(4000) }, placeholder = stringResource(R.string.mode_prompt_hint), singleLine = false, minHeight = 96.dp)
            else Text(prompt.ifBlank { "—" }, style = DshType.xs13, color = c.labelSecondary)
        }
    }

    SettingsGroupTitle(stringResource(R.string.mode_preset))
    SettingsCard {
        val options = listOf("") + PermissionPreset.entries.map { it.id }
        val labels = listOf(stringResource(R.string.mode_preset_follow)) + PermissionPreset.entries.map { stringResource(PresetUi.label(it)) }
        Box(Modifier.padding(12.dp)) {
            Segmented(labels, options.indexOf(preset).coerceAtLeast(0), { i ->
                if (!enabled) return@Segmented
                val next = options[i]
                if (next == PermissionPreset.Full.id && preset != next) confirmFull = true else preset = next
            })
        }
    }

    SettingsGroupTitle(stringResource(R.string.mode_device_groups))
    Text(stringResource(R.string.mode_device_groups_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        ToolGroup.entries.forEachIndexed { i, g ->
            val (title, desc) = groupLabel(g)
            SettingsRow(stringResource(title), stringResource(desc), divider = i > 0, trailing = {
                DSwitch(g.id in groups, { on -> if (on) groups.add(g.id) else groups.remove(g.id) }, enabled = enabled)
            })
        }
    }

    SettingsGroupTitle(stringResource(R.string.mode_engine_tools))
    SettingsCard {
        EngineTool.entries.forEachIndexed { i, t ->
            val (title, desc) = toolLabel(t)
            SettingsRow(stringResource(title), stringResource(desc), divider = i > 0, trailing = {
                DSwitch(t.id in tools, { on -> if (on) tools.add(t.id) else tools.remove(t.id) }, enabled = enabled)
            })
        }
        SettingsRow(stringResource(R.string.mode_skills), stringResource(R.string.mode_skills_desc), trailing = { DSwitch(skills, { skills = it }, enabled = enabled) })
    }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (!isNew) DButton(stringResource(R.string.mode_duplicate), { onDuplicate(initial) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
        if (enabled && !isNew) DButton(stringResource(R.string.mode_delete), { confirmDelete = true }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
        if (enabled) DButton(stringResource(R.string.save), {
            if (name.isBlank()) nameError = true
            else onSave(SessionMode(
                id = initial.id, name = name.trim(), icon = icon, prompt = prompt.trim(),
                // "Everything on" is stored as null, so groups / tools added later are included too.
                deviceGroups = groups.toSet().takeUnless { it.containsAll(ToolGroup.entries.map { g -> g.id }) },
                engineTools = tools.toSet().takeUnless { it.containsAll(EngineTool.entries.map { t -> t.id }) },
                preset = preset.ifEmpty { null }, skills = skills,
            ))
        }, size = BtnSize.Sm)
    }

    if (confirmFull) RiskConfirmDialog(
        stringResource(R.string.full_confirm_title), stringResource(R.string.full_confirm_body), stringResource(R.string.full_confirm_check),
        stringResource(R.string.full_confirm_ok), stringResource(R.string.cancel),
        onConfirm = { confirmFull = false; preset = PermissionPreset.Full.id }, onDismiss = { confirmFull = false },
    )
    if (confirmDelete) DDialog({ confirmDelete = false }, stringResource(R.string.mode_delete), buttons = {
        DButton(stringResource(R.string.cancel), { confirmDelete = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { confirmDelete = false; onDelete(initial) })
    }) {
        Text(stringResource(R.string.mode_delete_confirm, name), style = DshType.s14, color = c.labelSecondary)
    }
}
