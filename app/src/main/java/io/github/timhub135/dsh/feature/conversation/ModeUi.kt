package io.github.timhub135.dsh.feature.conversation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.Chevron
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DMenu
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.MenuDivider
import io.github.timhub135.dsh.core.designsystem.MenuItem
import io.github.timhub135.dsh.core.designsystem.MenuSectionTitle
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.model.ModeIcon
import io.github.timhub135.dsh.core.model.SessionMode
import io.github.timhub135.dsh.core.model.SessionModes
import io.github.timhub135.dsh.core.model.modeDisplayName

object ModeUi {
    @DrawableRes fun icon(icon: ModeIcon) = when (icon) {
        ModeIcon.Default -> R.drawable.dsw_sparkle
        ModeIcon.Phone -> R.drawable.dsw_apps_add_outline
        ModeIcon.Code -> R.drawable.dsw_code_outline
        ModeIcon.Chat -> R.drawable.dsw_new_chat_outline
        ModeIcon.Globe -> R.drawable.dsw_globe_outline
        ModeIcon.Pen -> R.drawable.dsw_list_pen_outline
        ModeIcon.Goal -> R.drawable.dsw_goal_outline
        ModeIcon.Skill -> R.drawable.dsw_skill_outline
    }
    @DrawableRes fun icon(mode: SessionMode) = icon(ModeIcon.of(mode.icon))

    @Composable fun name(mode: SessionMode): String = modeDisplayName(LocalContext.current, mode)

    /** One-line summary under a mode's name: the built-in's description, else the start of its prompt. */
    @Composable fun summary(mode: SessionMode): String? = when (mode.id) {
        SessionModes.DEFAULT_ID -> stringResource(R.string.mode_default_desc)
        SessionModes.PHONE_ID -> stringResource(R.string.mode_phone_desc)
        SessionModes.CODE_ID -> stringResource(R.string.mode_code_desc)
        SessionModes.CHAT_ID -> stringResource(R.string.mode_chat_desc)
        else -> mode.prompt.lineSequence().firstOrNull()?.take(60)?.ifBlank { null }
    }
}

/**
 * Composer chip for the conversation's session mode. Before the first message it opens the mode
 * menu; afterwards the mode is fixed and the chip only explains why (see [SessionLive.modeChangeable]).
 */
@Composable
fun ModeChip(
    mode: SessionMode,
    modes: List<SessionMode>,
    locked: Boolean,
    narrow: Boolean,
    onMode: (String) -> Unit,
    onManage: (() -> Unit)?,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    settled: () -> Boolean,
) {
    val c = Dsh.colors
    val label = ModeUi.name(mode)
    val desc = stringResource(R.string.mode_chip_desc, label)
    Box {
        Row(Modifier.heightIn(min = 28.dp).widthIn(max = 140.dp).clip(Radius.sm).pressable({ if (settled()) onOpen(true) }, Radius.sm)
            .semantics(mergeDescendants = true) { contentDescription = desc }.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon(ModeUi.icon(mode), size = 14.dp, tint = c.labelSecondary)
            // The default mode stays icon-only so the toolbar looks as before for most conversations.
            if (!narrow && !mode.isDefault) {
                Spacer(Modifier.width(4.dp))
                Text(label, Modifier.weight(1f, fill = false), style = DshType.xs13m, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!locked) Chevron(Modifier.padding(start = 2.dp), size = 12.dp)
        }
        DMenu(open, { onOpen(false) }) {
            MenuSectionTitle(stringResource(R.string.mode_menu_title))
            if (locked) {
                MenuItem(label, { onOpen(false) }, ModeUi.icon(mode), ModeUi.summary(mode), selected = true)
                Text(stringResource(R.string.mode_locked), Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp), style = DshType.xxs12, color = c.labelCaption)
            } else {
                modes.forEach { m ->
                    MenuItem(ModeUi.name(m), { onOpen(false); onMode(m.id) }, ModeUi.icon(m), ModeUi.summary(m), selected = m.id == mode.id)
                }
                Text(stringResource(R.string.mode_menu_hint), Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp), style = DshType.xxs12, color = c.labelCaption)
            }
            onManage?.let {
                MenuDivider()
                MenuItem(stringResource(R.string.mode_manage), { onOpen(false); it() }, R.drawable.dsw_settings_outline)
            }
        }
    }
}
