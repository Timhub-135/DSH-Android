package io.github.timhub135.dsh.feature.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DCheckbox
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.EmptyNote
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.Segmented
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.extensions.ExtText
import io.github.timhub135.dsh.core.extensions.SkillInfo
import io.github.timhub135.dsh.core.extensions.SkillOrigin

/** 技能 (§10): import from a file / zip / URL / GitHub, list with origins, switch off or delete imported ones. */
@Composable
fun SkillsTab(vm: ExtensionsViewModel) {
    val c = Dsh.colors
    val context = LocalContext.current
    val skills by vm.skills.skills.collectAsStateWithLifecycle()
    val error by vm.skills.error.collectAsStateWithLifecycle()
    var linkDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importSkillFile(it) } }
    LaunchedEffect(Unit) { vm.skills.load() }

    Text(stringResource(R.string.ext_skills_desc), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        SettingsRow(stringResource(R.string.ext_skills_import_file), null, divider = false,
            onClick = { pick.launch(arrayOf("text/markdown", "text/plain", "application/zip", "application/octet-stream", "*/*")) },
            leading = { DIcon(R.drawable.dsw_folder_open_outline, size = 16.dp, tint = c.labelSecondary) })
        SettingsRow(stringResource(R.string.ext_skills_import_url), null, onClick = { linkDialog = true },
            leading = { DIcon(R.drawable.dsw_link_outline, size = 16.dp, tint = c.labelSecondary) })
    }
    error?.let { Notice(ExtText.error(context, it), Tone.Warn) }

    val all = skills
    val mine = all.orEmpty().filter { it.editable }
    val others = all.orEmpty().filter { !it.editable }
    SettingsGroupTitle(stringResource(R.string.ext_skills_imported))
    SettingsCard {
        if (all == null) Box(Modifier.padding(16.dp)) { Spinner(14.dp) }
        else if (mine.isEmpty()) EmptyNote(stringResource(R.string.ext_skills_empty))
        mine.forEachIndexed { i, s ->
            SettingsRow(s.name, skillLine(s), divider = i > 0,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(R.drawable.dsw_trash_outline, { confirmDelete = s.name }, contentDescription = stringResource(R.string.delete), size = 36.dp)
                        DSwitch(s.enabled, { vm.skills.setEnabled(s.name, it) })
                    }
                })
        }
    }
    if (others.isNotEmpty()) {
        SettingsGroupTitle(stringResource(R.string.ext_skills_others))
        SettingsCard {
            others.forEachIndexed { i, s ->
                SettingsRow(s.name, s.description.take(140).ifEmpty { null }, divider = i > 0, trailing = { Tag(originLabel(s), Tone.Neutral) })
            }
        }
    }

    if (linkDialog) LinkDialog(onDismiss = { linkDialog = false }, onSubmit = { linkDialog = false; vm.skills.preview(it) },
        title = stringResource(R.string.ext_skills_import_url), hint = stringResource(R.string.ext_skills_url_hint))
    confirmDelete?.let { name ->
        DDialog({ confirmDelete = null }, stringResource(R.string.delete), buttons = {
            DButton(stringResource(R.string.cancel), { confirmDelete = null }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.delete), { confirmDelete = null; vm.skills.remove(name) }, variant = BtnVariant.Danger)
        }) { Text(stringResource(R.string.ext_skills_delete_confirm, name), style = DshType.s14, color = c.labelSecondary) }
    }
    SkillImportDialog(vm)
}

@Composable
private fun skillLine(s: SkillInfo): String? = listOfNotNull(
    s.description.take(140).ifEmpty { null },
    s.scripts.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ext_skills_scripts, it.take(3).joinToString(", ")) },
).joinToString("\n").ifEmpty { null }

@Composable
private fun originLabel(s: SkillInfo): String = when (s.origin) {
    SkillOrigin.PLUGIN -> stringResource(R.string.ext_skills_from_plugin, s.plugin ?: "?")
    SkillOrigin.AGENTS -> stringResource(R.string.ext_skills_agents)
    else -> stringResource(R.string.ext_skills_builtin)
}

/** Preview of what an import would add: tick which, choose 覆盖 / 跳过 in place for same-name ones. */
@Composable
private fun SkillImportDialog(vm: ExtensionsViewModel) {
    val c = Dsh.colors
    val context = LocalContext.current
    val imp by vm.skills.import.collectAsStateWithLifecycle()
    val i = imp ?: return
    val p = i.preview
    val chosen = vm.skills.chosen(i)
    DDialog({ vm.skills.closeImport() }, stringResource(R.string.ext_skills_import), buttons = {
        DButton(stringResource(R.string.close), { vm.skills.closeImport() }, variant = BtnVariant.Outline)
        if (p != null && i.done == null) DButton(stringResource(R.string.ext_skills_do_import, chosen.size), { vm.skills.confirm() }, enabled = chosen.isNotEmpty() && !i.loading)
    }) {
        when {
            i.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spinner(14.dp); Text(stringResource(R.string.ext_inspecting), style = DshType.xs13, color = c.labelTertiary)
            }
            i.done != null -> Text(stringResource(R.string.ext_skills_done, i.done.joinToString(", ")), style = DshType.s14, color = c.labelPrimary)
            p != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ext_skills_found, p.skills.size), style = DshType.xs13, color = c.labelTertiary)
                p.skills.forEach { s ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DCheckbox(s.valid && s.name in i.selected, if (s.valid) { on -> vm.skills.toggle(s.name, on) } else null)
                            Text(s.name, Modifier.weight(1f), style = DshType.s14m, color = if (s.valid) c.labelPrimary else c.labelTertiary)
                        }
                        val sub = when {
                            !s.valid -> s.error?.let { ExtText.error(context, it) } ?: (stringResource(R.string.err_skill_invalid) + (s.problem?.let { " ($it)" } ?: ""))
                            else -> listOfNotNull(s.description.take(120).ifEmpty { null },
                                s.scripts.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ext_skills_scripts, it.take(3).joinToString(", ")) }).joinToString("\n")
                        }
                        if (sub.isNotEmpty()) Text(sub, Modifier.padding(start = 24.dp), style = DshType.xxs12, color = if (s.valid) c.labelTertiary else c.warnLabel)
                        if (s.exists && s.valid && s.name in i.selected) Row(Modifier.padding(start = 24.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ext_skills_exists), style = DshType.xxs12, color = c.warnLabel)
                            Segmented(listOf(stringResource(R.string.ext_skills_skip), stringResource(R.string.ext_skills_overwrite)), if (s.name in i.overwrite) 1 else 0,
                                { vm.skills.setOverwrite(s.name, it == 1) }, compact = true)
                        }
                    }
                }
            }
        }
        i.error?.let { Text(ExtText.error(context, it), style = DshType.xs13, color = c.error) }
    }
}
