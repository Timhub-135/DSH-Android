package io.github.timhub135.dsh.feature.conversation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.SessionEntity
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DMenu
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.EmptyNote
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.MenuDivider
import io.github.timhub135.dsh.core.designsystem.MenuItem
import io.github.timhub135.dsh.core.designsystem.MenuSectionTitle
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.Wordmark
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.session.RowStatus
import kotlinx.coroutines.delay

private enum class ArchiveFilter { Hide, All, Only }

/** dsh left sidebar as a phone drawer: brand, 新会话, 自动化任务, flat session list (last updated), account footer. */
@Composable
fun SessionDrawer(
    sessions: List<SessionEntity>,
    status: Map<String, RowStatus>,
    currentId: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onAutomation: () -> Unit,
    /** Opens Settings, optionally at a section (SettingsSection name). */
    onSettings: (String?) -> Unit,
    onClose: () -> Unit,
    onRename: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    /** 免打扰 on / off (#43). */
    onMute: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = Dsh.colors
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ArchiveFilter.Hide) }
    var filterOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val shown = sessions.filter {
        when (filter) { ArchiveFilter.Hide -> !it.archived; ArchiveFilter.All -> true; ArchiveFilter.Only -> it.archived }
    }.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxHeight().background(c.sidebar).statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f).pressable({ onNew() }, Radius.sm))
            IconBtn(R.drawable.dsw_panel_left_outline, onClose, contentDescription = stringResource(R.string.collapse_sidebar))
        }
        Row(Modifier.padding(horizontal = 2.dp).padding(bottom = 12.dp).fillMaxWidth().height(40.dp).clip(Radius.md).background(c.hoverSolid).pressable(onNew, Radius.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            DIcon(R.drawable.dsw_new_chat_outline, size = 14.dp, tint = c.labelPrimary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.new_session), style = DshType.s14, color = c.labelPrimary)
        }
        PanelEntry(R.drawable.dsw_clock_outline, stringResource(R.string.automation), onAutomation)
        PanelEntry(R.drawable.dsw_shield_outline, stringResource(R.string.drawer_permissions)) { onSettings("Phone") }
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sessions), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
            IconBtn(R.drawable.dsw_search_outline, { searching = !searching; if (!searching) query = "" }, size = 40.dp, iconSize = 14.dp, tint = c.labelTertiary,
                contentDescription = stringResource(R.string.search_sessions))
            Box {
                IconBtn(R.drawable.dsw_sliders_two_outline, { filterOpen = true }, size = 40.dp, iconSize = 14.dp, tint = c.labelTertiary,
                    contentDescription = stringResource(R.string.filter_sessions))
                DMenu(filterOpen, { filterOpen = false }) {
                    MenuSectionTitle(stringResource(R.string.filter_sessions))
                    MenuItem(stringResource(R.string.filter_hide_archived), { filter = ArchiveFilter.Hide; filterOpen = false }, selected = filter == ArchiveFilter.Hide)
                    MenuItem(stringResource(R.string.filter_all), { filter = ArchiveFilter.All; filterOpen = false }, selected = filter == ArchiveFilter.All)
                    MenuItem(stringResource(R.string.filter_only_archived), { filter = ArchiveFilter.Only; filterOpen = false }, selected = filter == ArchiveFilter.Only)
                }
            }
        }
        if (searching) Box(Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) { DInput(query, { query = it }, placeholder = stringResource(R.string.search_sessions), minHeight = 34.dp) }
        // dsh: 会话已归档，可 [撤销].
        var archived by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(archived) { if (archived != null) { delay(5000); archived = null } }
        val archive: (String, Boolean) -> Unit = { id, on -> onArchive(id, on); archived = if (on) id else null }
        LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty()) item { EmptyNote(stringResource(if (query.isBlank()) R.string.no_sessions else R.string.no_matching_sessions)) }
            items(shown, key = { it.id }) { s ->
                SessionRow(s, status[s.id] ?: RowStatus.Idle, s.id == currentId, now, { onOpen(s.id) }, onRename, onPin, onMute, archive, onDelete)
            }
        }
        archived?.let { id ->
            Row(Modifier.padding(vertical = 4.dp).fillMaxWidth().heightIn(min = 44.dp).clip(Radius.md).background(c.toastBg).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.session_archived), Modifier.weight(1f), style = DshType.xs13, color = androidx.compose.ui.graphics.Color.White)
                Text(stringResource(R.string.undo), Modifier.clip(Radius.sm).pressable({ onArchive(id, false); archived = null }, Radius.sm).padding(horizontal = 12.dp, vertical = 10.dp),
                    style = DshType.xs13m, color = c.link)
            }
        }
        // dsh sidebar footer: the account launcher (avatar + name, or 更多 before sign-in) owns 设置.
        val account = io.github.timhub135.dsh.feature.account.LocalAccount.current
        if (account != null) Box(Modifier.padding(top = 4.dp)) { io.github.timhub135.dsh.feature.account.DrawerAccountRow(account) { onSettings(null) } }
        else PanelEntry(R.drawable.dsw_settings_outline, stringResource(R.string.settings)) { onSettings(null) }
    }
}

@Composable
private fun PanelEntry(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val c = Dsh.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).pressable(onClick, Radius.md).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DIcon(icon, size = 16.dp, tint = c.labelSecondary)
        Text(label, style = DshType.s14, color = c.labelPrimary)
    }
}

@Composable
private fun SessionRow(
    s: SessionEntity, st: RowStatus, selected: Boolean, now: Long, onClick: () -> Unit,
    onRename: (String, String) -> Unit, onPin: (String, Boolean) -> Unit, onMute: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit, onDelete: (String) -> Unit,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Radius.md).background(if (selected) c.sidebarActive else androidx.compose.ui.graphics.Color.Transparent)
            .pressable(onClick, Radius.md, onLongClick = { menu = true }).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    st == RowStatus.Approval -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.warn))
                    st == RowStatus.Question -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.business))
                    st == RowStatus.Running -> Spinner(12.dp, c.labelTertiary)
                    s.unread -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.business))
                    s.pinned -> DIcon(R.drawable.dsw_pin_outline, size = 12.dp, tint = c.labelCaption)
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(s.title.ifBlank { stringResource(R.string.untitled) }, Modifier.weight(1f), style = DshType.s14.copy(lineHeight = DshType.xs13.lineHeight),
                color = if (s.archived) c.labelCaption else c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            if (s.muted) {
                DIcon(R.drawable.dsw_bell_off_outline, size = 12.dp, tint = c.labelCaption, contentDescription = stringResource(R.string.session_muted))
                Spacer(Modifier.width(4.dp))
            }
            val trailing = when (st) {
                RowStatus.Approval -> stringResource(R.string.row_approval)
                RowStatus.Question -> stringResource(R.string.row_answer)
                else -> Fmt.relative(context, s.updatedAt, now)
            }
            Text(trailing, style = DshType.tiny10, color = if (st == RowStatus.Approval) c.warnLabel else c.labelTertiary, maxLines = 1)
            // Explicit "…" (spec §1.2): rename / pin / archive / delete are not long-press-only.
            IconBtn(R.drawable.dsw_ellipsis_outline, { menu = true }, size = 36.dp, iconSize = 14.dp, tint = c.labelTertiary,
                contentDescription = stringResource(R.string.more_actions))
        }
        DMenu(menu, { menu = false }) {
            MenuItem(stringResource(if (s.pinned) R.string.unpin_session else R.string.pin_session), { menu = false; onPin(s.id, !s.pinned) }, R.drawable.dsw_pin_outline)
            MenuItem(stringResource(if (s.muted) R.string.unmute_session else R.string.mute_session), { menu = false; onMute(s.id, !s.muted) }, R.drawable.dsw_bell_off_outline)
            MenuItem(stringResource(R.string.rename), { menu = false; renaming = true }, R.drawable.dsw_edit_outline)
            MenuItem(stringResource(if (s.archived) R.string.unarchive_session else R.string.archive_session), { menu = false; onArchive(s.id, !s.archived) },
                if (s.archived) R.drawable.dsw_unarchive_outline else R.drawable.dsw_archive_outline)
            MenuDivider()
            MenuItem(stringResource(R.string.delete_session), { menu = false; deleting = true }, R.drawable.dsw_trash_outline, danger = true)
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(s.title) }
        DDialog({ renaming = false }, stringResource(R.string.rename), buttons = {
            DButton(stringResource(R.string.cancel), { renaming = false }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.save), { renaming = false; onRename(s.id, title.trim()) }, enabled = title.isNotBlank())
        }) { DInput(title, { title = it }) }
    }
    if (deleting) DDialog({ deleting = false }, stringResource(R.string.delete_session_title), buttons = {
        DButton(stringResource(R.string.cancel), { deleting = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { deleting = false; onDelete(s.id) }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.delete_session_body), style = DshType.s14, color = c.labelSecondary) }
}
