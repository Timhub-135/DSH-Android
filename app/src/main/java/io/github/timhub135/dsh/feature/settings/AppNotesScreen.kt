package io.github.timhub135.dsh.feature.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.capability.AppNotes
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.DTopBar
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppNotesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: AppNotes,
    private val repo: io.github.timhub135.dsh.core.data.SettingsRepository,
) : ViewModel() {
    val reflect: StateFlow<Boolean> = repo.settings.map { it.reflectExperience }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setReflect(on: Boolean) { viewModelScope.launch { repo.setReflectExperience(on) } }

    class AppGroup(val pkg: String, val label: String, val icon: ImageBitmap?, val notes: List<AppNotes.Note>)

    private val icons = HashMap<String, ImageBitmap?>()

    val apps: StateFlow<List<AppGroup>?> = notes.all.map { all ->
        all.map { it.pkg }.distinct().map { pkg -> AppGroup(pkg, label(pkg), icon(pkg), notes.forPackages(listOf(pkg))) }
            .sortedWith(compareByDescending<AppGroup> { it.notes.size }.thenBy { it.label.lowercase() })
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun isStale(n: AppNotes.Note) = notes.isStale(n)

    private fun label(pkg: String) = runCatching { context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    private fun icon(pkg: String) = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }
    fun remove(id: String) = io { notes.remove(id) }
    fun clear(pkg: String) = io { notes.clear(pkg) }
    fun clearAll() = io { notes.clearAll() }
}

/**
 * 设置 → App 经验: apps with notes ([open] null), or one app's notes ([open] = its package; delete
 * one, or clear the app / everything). Both are pages of their own; [onOpen] opens an app's.
 */
@Composable
fun AppNotesScreen(open: String?, onBack: () -> Unit, onOpen: (String) -> Unit = {}, vm: AppNotesViewModel = hiltViewModel()) {
    val c = Dsh.colors
    val apps by vm.apps.collectAsStateWithLifecycle()
    val reflect by vm.reflect.collectAsStateWithLifecycle()
    /** "" = everything, else a package. */
    var confirm by remember { mutableStateOf<String?>(null) }
    val group = apps?.firstOrNull { it.pkg == open }
    // An app's page whose notes are all gone (deleted / cleared) goes back to the list.
    if (open != null && apps != null && group == null) LaunchedEffect(Unit) { onBack() }
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
        DTopBar(group?.label ?: stringResource(R.string.app_notes), onBack) {
            val count = group?.notes?.size ?: apps.orEmpty().sumOf { it.notes.size }
            if (count > 0) DButton(stringResource(if (group != null) R.string.app_notes_clear else R.string.app_notes_clear_all),
                { confirm = group?.pkg ?: "" }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding()) {
            item { Text(stringResource(R.string.app_notes_desc), Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp), style = DshType.xs13, color = c.labelTertiary) }
            if (group == null) item {
                SettingsCard(Modifier.padding(bottom = 12.dp)) {
                    SettingsRow(stringResource(R.string.reflect_experience), stringResource(R.string.reflect_experience_desc), divider = false,
                        trailing = { DSwitch(reflect, vm::setReflect) })
                }
            }
            val list = apps
            when {
                list == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { io.github.timhub135.dsh.core.designsystem.Spinner(20.dp) } }
                list.isEmpty() -> item { Text(stringResource(R.string.app_notes_empty), Modifier.padding(start = 4.dp, top = 24.dp), style = DshType.s14, color = c.labelTertiary) }
                group == null -> item {
                    SettingsCard {
                        list.forEachIndexed { i, g ->
                            SettingsRow(g.label, g.pkg, divider = i > 0, onClick = { onOpen(g.pkg) },
                                leading = { AppIcon(g.icon) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.app_notes_count, g.notes.size), style = DshType.xs13, color = c.labelTertiary)
                                        DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = c.labelTertiary)
                                    }
                                })
                        }
                    }
                }
                else -> items(group.notes, key = { it.id }) { n -> NoteRow(n, vm.isStale(n)) { vm.remove(n.id) } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    confirm?.let { target ->
        val g = apps?.firstOrNull { it.pkg == target }
        DDialog({ confirm = null }, stringResource(R.string.app_notes_clear_title), buttons = {
            DButton(stringResource(R.string.cancel), { confirm = null }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.app_notes_clear), {
                if (target.isEmpty()) vm.clearAll() else vm.clear(target)
                confirm = null
            }, variant = BtnVariant.Danger)
        }) {
            Text(if (g == null) stringResource(R.string.app_notes_clear_all_body, apps.orEmpty().sumOf { it.notes.size })
                else stringResource(R.string.app_notes_clear_app_body, g.label, g.notes.size), style = DshType.s14)
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) Image(icon, null, Modifier.size(28.dp))
    else DIcon(R.drawable.dsw_apps_add_outline, size = 18.dp, tint = Dsh.colors.labelSecondary)
}

@Composable
private fun NoteRow(n: AppNotes.Note, stale: Boolean, onDelete: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    SettingsCard(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(stringResource(kindLabel(n.kind)), if (n.kind == AppNotes.Kind.trap) Tone.Warn else Tone.Neutral)
                    if (stale) Tag(stringResource(R.string.app_note_stale), Tone.Warn)
                }
                Text(n.text, style = DshType.s14, color = c.labelPrimary)
                Text(stringResource(R.string.app_note_meta, n.appVersion.substringBefore(" (").ifEmpty { "?" }, n.uses, Fmt.relative(context, n.lastConfirmedAt)),
                    style = DshType.xxs12, color = c.labelTertiary)
            }
            IconBtn(R.drawable.dsw_trash_outline, onDelete, contentDescription = stringResource(R.string.delete))
        }
    }
}

private fun kindLabel(k: AppNotes.Kind) = when (k) {
    AppNotes.Kind.entry -> R.string.app_note_kind_entry
    AppNotes.Kind.trap -> R.string.app_note_kind_trap
    AppNotes.Kind.fact -> R.string.app_note_kind_fact
    AppNotes.Kind.route -> R.string.app_note_kind_route
}
