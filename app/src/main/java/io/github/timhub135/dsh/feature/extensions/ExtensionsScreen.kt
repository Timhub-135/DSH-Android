package io.github.timhub135.dsh.feature.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DMenu
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.DotKind
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.EmptyNote
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.MenuItem
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.Segmented
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.StateDot
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.engine.EngineState
import io.github.timhub135.dsh.core.extensions.ExtCodes
import io.github.timhub135.dsh.core.extensions.ExtPlugin
import io.github.timhub135.dsh.core.extensions.ExtStatus
import io.github.timhub135.dsh.core.extensions.ExtText
import io.github.timhub135.dsh.core.extensions.InstallSource
import io.github.timhub135.dsh.core.extensions.MarketEntry
import io.github.timhub135.dsh.core.extensions.MarketIndex
import io.github.timhub135.dsh.core.extensions.PluginRegistries
import io.github.timhub135.dsh.core.extensions.RegistryMode
import io.github.timhub135.dsh.core.extensions.RestartState
import io.github.timhub135.dsh.core.extensions.SourceKind
import io.github.timhub135.dsh.core.extensions.UserError
import io.github.timhub135.dsh.nav.IndexesRoute
import io.github.timhub135.dsh.nav.MarketEntryRoute
import io.github.timhub135.dsh.nav.McpRoute
import io.github.timhub135.dsh.nav.PluginRoute
import io.github.timhub135.dsh.nav.Route
import kotlinx.coroutines.launch

/*
 * 设置 → 扩展 (PLUGINS-DESIGN §9): the section page has the three tabs; each sub-page (an installed
 * plugin, an index entry, 管理索引, an MCP server) is a destination of its own (nav.Routes), so Back
 * always returns to the page it was opened from. All of them share the settings graph's
 * [ExtensionsViewModel], and each shows the install sheet while it is on top.
 */

@Composable
internal fun language(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/** The 扩展 section page (the tabs). [onTop]: this page is the current one (the install sheet shows on one page only). */
@Composable
fun ExtensionsSection(open: (Route) -> Unit, onTop: Boolean = true, vm: ExtensionsViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.openOnce() }
    // Shared to DSH (分享 → 安装为 DSH 扩展): start the matching flow once (the share opened this page).
    val shared by vm.repo.pendingInput.collectAsStateWithLifecycle()
    var sharedJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shared) {
        val input = shared ?: return@LaunchedEffect
        vm.repo.pendingInput.value = null
        when (io.github.timhub135.dsh.core.extensions.ShareTarget.of(input)) {
            io.github.timhub135.dsh.core.extensions.ShareTarget.Plugin -> { vm.tab.value = 0; vm.install.start(input) }
            io.github.timhub135.dsh.core.extensions.ShareTarget.Skill -> { vm.tab.value = 1; vm.skills.preview(input) }
            io.github.timhub135.dsh.core.extensions.ShareTarget.Mcp -> { vm.tab.value = 2; sharedJson = input }
        }
    }
    sharedJson?.let { json -> PasteJsonDialog(vm, initial = json) { sharedJson = null } }
    ExtensionsTabs(vm, open)
    if (onTop) InstallSheet(vm, onOpenMcp = { vm.tab.value = 2 })
}

/** The top bar title of an extensions sub-page. */
@Composable
fun extensionsPageTitle(route: Route, vm: ExtensionsViewModel = hiltViewModel()): String {
    val list by vm.store.list.collectAsStateWithLifecycle()
    val lang = language()
    return when (route) {
        is PluginRoute -> list?.plugins?.firstOrNull { it.name == route.name }?.displayTitle ?: route.name
        is MarketEntryRoute -> vm.market.state.value.entries.firstOrNull { it.second.name == route.name }?.second?.title(lang) ?: route.name
        IndexesRoute -> stringResource(R.string.ext_manage_indexes)
        is McpRoute -> route.id?.let { id -> vm.settings.value.mcpServers.firstOrNull { it.id == id }?.name ?: id } ?: stringResource(R.string.ext_mcp_new)
        else -> stringResource(R.string.set_extensions)
    }
}

/**
 * An extensions sub-page's content. [onBack] is up one level (done / uninstalled / installed);
 * [toSection] returns to the tabs (the install sheet's MCP button).
 */
@Composable
fun ExtensionsSubPage(route: Route, onBack: () -> Unit, toSection: () -> Unit, onTop: Boolean, vm: ExtensionsViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.openOnce() }
    when (route) {
        is PluginRoute -> PluginDetailPage(vm, route.name, onBack = onBack)
        is MarketEntryRoute -> MarketEntryPage(vm, route.url, route.name, onInstalled = onBack)
        IndexesRoute -> IndexesPage(vm)
        is McpRoute -> McpEditorPage(vm, route.id, route.npm, onDone = onBack)
        else -> {}
    }
    if (onTop) InstallSheet(vm, onOpenMcp = { vm.tab.value = 2; toSection() })
}

@Composable
private fun ExtensionsTabs(vm: ExtensionsViewModel, open: (Route) -> Unit) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    Spacer(Modifier.height(8.dp))
    Segmented(listOf(stringResource(R.string.ext_tab_plugins), stringResource(R.string.ext_tab_skills), stringResource(R.string.ext_tab_mcp)), tab, { vm.tab.value = it })
    EngineGate(vm)
    StatusBanners(vm)
    if (tab == 0) KeepAliveHint(vm)
    when (tab) {
        0 -> PluginsTab(vm, open)
        1 -> SkillsTab(vm)
        2 -> McpTab(vm, open)
    }
}

/** Engine starting / failed (§9.2: 引擎未运行 → 启动 / 安全模式启动). */
@Composable
private fun EngineGate(vm: ExtensionsViewModel) {
    val c = Dsh.colors
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val loading by vm.store.loading.collectAsStateWithLifecycle()
    val error by vm.store.loadError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    when {
        engine is EngineState.Starting || (loading && engine !is EngineState.Ready) -> Row(Modifier.padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(14.dp); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ext_engine_starting), style = DshType.xs13, color = c.labelTertiary)
        }
        // Only a dead / unreachable engine is 引擎没能启动; an answer the app could not read is not (the engine runs).
        engine is EngineState.Failed || error?.code == ExtCodes.ENGINE_UNAVAILABLE -> SettingsCard(Modifier.padding(top = 12.dp)) {
            SettingsRow(stringResource(R.string.ext_engine_failed),
                (engine as? EngineState.Failed)?.message?.lineSequence()?.firstOrNull()?.take(200) ?: error?.let { ExtText.error(context, it) },
                divider = false, leading = { StateDot(DotKind.Error) })
            SettingsRow(stringResource(R.string.ext_safe_mode_start), stringResource(R.string.ext_safe_mode_start_desc), stacked = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DButton(stringResource(R.string.ext_engine_retry), { vm.retry() }, size = BtnSize.Sm)
                    DButton(stringResource(R.string.ext_safe_mode_start), { vm.startSafeMode() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                }
            }
        }
        error?.code == ExtCodes.UNSUPPORTED_ENGINE -> Notice(stringResource(R.string.err_unsupported_engine), Tone.Warn)
        error != null -> SettingsCard(Modifier.padding(top = 12.dp)) {
            SettingsRow(stringResource(R.string.ext_list_failed), error?.let { ExtText.error(context, it) }, divider = false, leading = { StateDot(DotKind.Warn) },
                trailing = { DButton(stringResource(R.string.retry), { vm.retry() }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = !loading) })
        }
    }
}

/** Safe mode, and changes waiting for the engine to be idle (§7.4, §7.6). */
@Composable
private fun StatusBanners(vm: ExtensionsViewModel) {
    val list by vm.store.list.collectAsStateWithLifecycle()
    val restart by vm.store.restart.collectAsStateWithLifecycle()
    list?.takeIf { it.showSafeModeBanner }?.let { l ->
        val res = when (l.safeModeReason) {
            "requested" -> R.string.ext_safe_mode_banner_requested
            "crash-loop" -> R.string.ext_safe_mode_banner_crash_loop
            else -> R.string.ext_safe_mode_banner
        }
        Notice(stringResource(res, l.safeModePaused), Tone.Warn)
    }
    when (restart) {
        RestartState.WaitingForIdle -> Notice(stringResource(R.string.ext_restart_pending), Tone.Info) {
            DButton(stringResource(R.string.ext_restart_now), { vm.restartNow() }, size = BtnSize.Sm, variant = BtnVariant.Outline)
        }
        RestartState.Restarting -> Notice(stringResource(R.string.ext_restarting), Tone.Info, busy = true)
        RestartState.Idle -> {}
    }
}

/** 引擎常驻 is off but a plugin works in the background (a heartbeat, a bot): suggest it (#52). */
@Composable
private fun KeepAliveHint(vm: ExtensionsViewModel) {
    val list by vm.store.list.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    if (!io.github.timhub135.dsh.core.engine.EngineKeepAlive.suggest(s.engineKeepAlive, s.pluginBackgroundSeen, list?.plugins.orEmpty())) return
    Notice(stringResource(R.string.engine_keep_alive_hint), Tone.Info) {
        DButton(stringResource(R.string.engine_keep_alive_enable), { vm.enableEngineKeepAlive() }, size = BtnSize.Sm, variant = BtnVariant.Outline)
    }
}

/** An in-page notice (not a dialog): tinted card with optional action. */
@Composable
internal fun Notice(text: String, tone: Tone, busy: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Dsh.colors
    val bg = when (tone) { Tone.Warn -> c.warnTertiary; Tone.Error -> c.hoverDanger; Tone.Success -> c.successTertiary; else -> c.businessTertiary }
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(Radius.lg).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) Spinner(14.dp)
        Text(text, Modifier.weight(1f), style = DshType.xs13, color = c.labelPrimary)
        action?.invoke()
    }
}

// ---- 插件 tab ---------------------------------------------------------------------------------------

@Composable
private fun PluginsTab(vm: ExtensionsViewModel, open: (Route) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val list by vm.store.list.collectAsStateWithLifecycle()
    val market by vm.market.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val errors by vm.actionErrors.collectAsStateWithLifecycle()
    val lang = language()
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.installFile(it) } }

    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { DInput(query, { query = it; vm.market.query(it) }, placeholder = stringResource(R.string.ext_search_hint)) }
        Box {
            IconBtn(R.drawable.dsw_plus_outline, { menu = true }, contentDescription = stringResource(R.string.ext_add_menu_link), tint = c.labelPrimary)
            DMenu(menu, { menu = false }) {
                MenuItem(stringResource(R.string.ext_add_menu_link), { menu = false; linkDialog = true }, icon = R.drawable.dsw_link_outline)
                MenuItem(stringResource(R.string.ext_add_menu_file), { menu = false; pick.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/zip", "application/octet-stream")) }, icon = R.drawable.dsw_folder_open_outline)
                MenuItem(stringResource(R.string.ext_add_menu_index), { menu = false; open(IndexesRoute) }, icon = R.drawable.dsw_flat_list_outline)
            }
        }
    }
    // A name or link in the box: installing it is the first result.
    val direct = remember(query) { InstallSource.looksInstallable(query) }
    if (direct != null && direct.kind != SourceKind.Unknown) SettingsCard(Modifier.padding(top = 8.dp)) {
        SettingsRow(stringResource(R.string.ext_install_spec, direct.spec.take(80)), null, divider = false, onClick = { vm.install.start(query) },
            leading = { DIcon(R.drawable.dsw_download_outline, size = 16.dp, tint = c.business) })
    }

    val plugins = list?.plugins.orEmpty().filter { query.isBlank() || direct != null || MarketIndex.matches(query, listOf(it.name) + it.title?.all.orEmpty() + it.description?.all.orEmpty()) }
    val updates = list?.updates.orEmpty()
    if (updates.isNotEmpty() && query.isBlank()) SettingsCard(Modifier.padding(top = 12.dp)) {
        SettingsRow(stringResource(R.string.ext_updates_title, updates.size), updates.joinToString(", ") { "${it.displayTitle} ${it.update?.version}" },
            divider = false, trailing = { DButton(stringResource(R.string.ext_update_all), { vm.updateAll() }, size = BtnSize.Sm) })
    }
    SettingsGroupTitle(stringResource(R.string.ext_installed, list?.plugins?.size ?: 0))
    SettingsCard {
        if (plugins.isEmpty()) EmptyNote(stringResource(R.string.ext_none_installed))
        plugins.forEachIndexed { i, p ->
            PluginRow(p, divider = i > 0, busy = p.name in busy, onOpen = { open(PluginRoute(p.name)) }, onToggle = { vm.setEnabled(p.name, it) })
            errors[p.name]?.let { e -> Text(ExtText.error(context, e), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), style = DshType.xxs12, color = c.error) }
        }
    }

    // 发现: every configured index (the curated one by default).
    val installedNames = list?.plugins.orEmpty().map { it.name }.toSet()
    val title = market.indexes.firstOrNull()?.title?.get(lang) ?: stringResource(R.string.ext_indexes_curated)
    Row(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ext_discover_from, if (market.indexes.size > 1) "${market.indexes.size}" else title), Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
        if (market.loading) Spinner(12.dp)
        Text(stringResource(R.string.ext_manage_indexes), Modifier.clip(Radius.sm).pressable({ open(IndexesRoute) }, Radius.sm).padding(horizontal = 6.dp, vertical = 2.dp),
            style = DshType.xs13, color = c.link)
    }
    val entries = market.entries
    SettingsCard {
        // The engine's sentence names the cause and every source it tried (index-unreachable); 重试 reads again.
        market.errors.forEach { (url, err) ->
            val name = if (MarketIndex.isCurated(url)) stringResource(R.string.ext_indexes_curated) else market.indexes.firstOrNull { it.url == url }?.title?.get(lang) ?: url
            SettingsRow(if (url.isEmpty()) stringResource(R.string.ext_market_error_list) else stringResource(R.string.ext_market_error, name), ExtText.error(context, err), divider = false, leading = { StateDot(DotKind.Warn) },
                trailing = { DButton(stringResource(R.string.retry), { vm.market.load(refresh = true) }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = !market.loading) })
        }
        if (entries.isEmpty() && !market.loading && market.errors.isEmpty()) EmptyNote(stringResource(R.string.ext_market_empty))
        entries.forEachIndexed { i, (url, e) ->
            MarketRow(e, lang, installed = e.name in installedNames || e.installed, divider = i > 0 || market.errors.isNotEmpty(),
                onOpen = { open(if (e.name in installedNames) PluginRoute(e.name) else MarketEntryRoute(url, e.name)) },
                onInstall = { vm.install.start(e.installVersion?.let { v -> MarketIndex.installSpec(e.name, v) } ?: e.name) })
        }
    }
    RegistryCard(vm)

    if (linkDialog) LinkDialog(onDismiss = { linkDialog = false }, onSubmit = { linkDialog = false; vm.install.start(it) })
}

@Composable
internal fun PluginRow(p: ExtPlugin, divider: Boolean, busy: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    SettingsRow(p.displayTitle, statusLine(p), divider = divider, onClick = onOpen,
        leading = { PluginIcon(p.iconDataUrl, statusDot(p)) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.version?.let { Text(it, style = DshType.mono11, color = c.labelTertiary, maxLines = 1) }
                if (p.update?.compatible == true) Tag(stringResource(R.string.ext_update_available_tag), Tone.Info)
                if (busy) Spinner(14.dp)
                else if (p.status == ExtStatus.SUSPENDED) Tag(stringResource(R.string.ext_status_suspended), Tone.Warn)
                else DSwitch(p.enabled, onToggle)
            }
        })
    if (p.status == ExtStatus.FAILED || p.status == ExtStatus.WAITING || p.status == ExtStatus.INCOMPATIBLE) p.statusDetail?.let { d ->
        Text(ExtText.error(context, d), Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp), style = DshType.xxs12, color = c.warnLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal fun statusDot(p: ExtPlugin) = when (p.status) {
    ExtStatus.ACTIVE -> DotKind.Done
    ExtStatus.FAILED -> DotKind.Error
    ExtStatus.WAITING, ExtStatus.INCOMPATIBLE, ExtStatus.SUSPENDED -> DotKind.Warn
    ExtStatus.RESTART_PENDING -> DotKind.Info
    else -> DotKind.Idle
}

/** "npm · 运行中 · 2 个工具" (§9.2). */
@Composable
internal fun statusLine(p: ExtPlugin): String {
    val parts = mutableListOf<String>()
    if (p.malformed) return stringResource(R.string.err_bad_entry_short)
    sourceLabel(p.source?.kind)?.let { parts += it }
    parts += when (p.status) {
        ExtStatus.ACTIVE -> listOfNotNull(stringResource(R.string.ext_status_active),
            p.tools.size.takeIf { it > 0 }?.let { stringResource(R.string.ext_count_tools, it) },
            p.skills.size.takeIf { it > 0 }?.let { stringResource(R.string.ext_count_skills, it) },
            p.commands.size.takeIf { it > 0 }?.let { stringResource(R.string.ext_count_commands, it) }).joinToString(" · ")
        ExtStatus.RESTART_PENDING -> stringResource(R.string.ext_status_pending)
        ExtStatus.INCOMPATIBLE -> stringResource(R.string.ext_status_incompatible)
        ExtStatus.FAILED -> stringResource(R.string.ext_status_failed)
        ExtStatus.WAITING -> stringResource(R.string.ext_status_waiting)
        ExtStatus.SUSPENDED -> stringResource(R.string.ext_status_suspended)
        else -> stringResource(R.string.ext_status_disabled)
    }
    if (p.update != null && !p.update.compatible) parts += stringResource(R.string.ext_update_needs_app, p.update.version)
    return parts.joinToString(" · ")
}

@Composable
internal fun sourceLabel(kind: String?): String? = when (kind) {
    "npm" -> stringResource(R.string.ext_source_npm)
    "github" -> stringResource(R.string.ext_source_github)
    "file" -> stringResource(R.string.ext_source_file)
    "tarball" -> stringResource(R.string.ext_source_tarball)
    else -> null
}

/**
 * 32 dp icon with the status dot on its corner. Only inline `data:` icons are drawn (the app has no
 * network image loader, and URL icons would disclose the user's IP to the image host); others get the plugin glyph.
 */
@Composable
internal fun PluginIcon(icon: String?, dot: DotKind?) {
    val c = Dsh.colors
    Box(Modifier.size(32.dp)) {
        Box(Modifier.size(32.dp).clip(Radius.sm).background(c.selector), contentAlignment = Alignment.Center) {
            if (icon != null && icon.startsWith("data:image/")) AsyncImage(icon, null, Modifier.size(24.dp))
            else DIcon(R.drawable.dsw_cordis_plugin_outline, size = 16.dp, tint = c.labelSecondary)
        }
        if (dot != null) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(c.bgLayer2), contentAlignment = Alignment.Center) { StateDot(dot, 6.dp) }
    }
}

@Composable
private fun MarketRow(e: MarketEntry, lang: String, installed: Boolean, divider: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    val c = Dsh.colors
    val v = e.installVersion
    val compatible = v?.compatibility?.compatible != false
    val sub = listOfNotNull(e.author, e.description?.get(lang)?.takeIf { it.isNotBlank() }).joinToString(" · ")
    SettingsRow(e.title(lang), sub.ifEmpty { null }, divider = divider, onClick = onOpen,
        leading = { PluginIcon(e.icon, null) },
        trailing = {
            when {
                installed -> Tag(stringResource(R.string.ext_installed_tag), Tone.Success)
                v == null || !compatible -> Tag(stringResource(R.string.ext_market_incompatible), Tone.Warn)
                else -> DButton(stringResource(R.string.ext_install), onInstall, variant = BtnVariant.Outline, size = BtnSize.Sm)
            }
        })
    if (e.metadata.labels.isNotEmpty()) Row(Modifier.padding(start = 60.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        e.metadata.labels.forEach { Tag(it, if (it == "phone-tested") Tone.Success else Tone.Neutral) }
    }
}

/** 插件下载源 (§6.6). */
@Composable
private fun RegistryCard(vm: ExtensionsViewModel) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    var custom by rememberSaveable(s.pluginRegistryCustom) { mutableStateOf(s.pluginRegistryCustom) }
    val modes = RegistryMode.entries
    SettingsGroupTitle(stringResource(R.string.ext_registry))
    SettingsCard {
        SettingsRow(stringResource(R.string.ext_registry), stringResource(R.string.ext_registry_desc), divider = false, stacked = true) {
            Segmented(listOf(stringResource(R.string.ext_registry_auto), stringResource(R.string.ext_registry_npmjs), stringResource(R.string.ext_registry_mirror),
                stringResource(R.string.ext_registry_custom)), modes.indexOf(s.pluginRegistry), { i ->
                val m = modes[i]
                if (m != RegistryMode.Custom || PluginRegistries.normalize(custom) != null) vm.setRegistry(m, custom) else vm.setRegistry(RegistryMode.Custom, custom)
            })
            if (s.pluginRegistry == RegistryMode.Custom) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { DInput(custom, { custom = it }, placeholder = "https://registry.example.com/") }
                    DButton(stringResource(R.string.save), { vm.setRegistry(RegistryMode.Custom, custom) }, size = BtnSize.Sm, enabled = PluginRegistries.normalize(custom) != null)
                }
                if (custom.isNotBlank() && PluginRegistries.normalize(custom) == null)
                    Text(stringResource(R.string.ext_registry_custom_invalid), Modifier.padding(top = 4.dp), style = DshType.xxs12, color = c.error)
            }
        }
    }
}

@Composable
internal fun LinkDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit, title: String = stringResource(R.string.ext_add_menu_link), hint: String = stringResource(R.string.ext_link_hint)) {
    var text by remember { mutableStateOf("") }
    DDialog(onDismiss, title, buttons = {
        DButton(stringResource(R.string.cancel), onDismiss, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.ext_continue), { onSubmit(text.trim()) }, enabled = text.isNotBlank())
    }) {
        DInput(text, { text = it }, placeholder = hint, singleLine = false, minHeight = 64.dp)
    }
}

/** 管理索引: the configured index URLs (§9.5). */
@Composable
private fun IndexesPage(vm: ExtensionsViewModel) {
    val c = Dsh.colors
    val context = LocalContext.current
    val market by vm.market.state.collectAsStateWithLifecycle()
    val lang = language()
    var adding by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UserError?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { if (market.indexes.isEmpty()) vm.market.load() }
    val urls = market.indexes.map { it.url }
    fun save(next: List<String>) = scope.launch { error = vm.market.setIndexes(next) }
    Text(stringResource(R.string.ext_indexes_desc), Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        market.indexes.forEachIndexed { i, ix ->
            val name = if (MarketIndex.isCurated(ix.url)) stringResource(R.string.ext_indexes_curated) else ix.title?.get(lang) ?: ix.url
            SettingsRow(name, listOfNotNull(ix.url, ix.error).joinToString("\n"), divider = i > 0,
                leading = { StateDot(if (ix.error != null) DotKind.Warn else DotKind.Done) },
                trailing = { DButton(stringResource(R.string.ext_indexes_remove), { save(urls - ix.url) }, variant = BtnVariant.Ghost, size = BtnSize.Sm) })
        }
        SettingsRow(stringResource(R.string.ext_add_menu_index), null, divider = market.indexes.isNotEmpty(), stacked = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { DInput(adding, { adding = it }, placeholder = "https://…/index.json") }
                DButton(stringResource(R.string.ext_indexes_add), { save(urls + adding.trim()); adding = "" }, size = BtnSize.Sm,
                    enabled = adding.trim().startsWith("http"))
            }
        }
    }
    if (urls.none(MarketIndex::isCurated) && !market.loading) Box(Modifier.padding(top = 12.dp)) {
        DButton(stringResource(R.string.ext_indexes_restore), { save(listOf(MarketIndex.CURATED_URL) + urls) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
    }
    error?.let { Text(ExtText.error(context, it), Modifier.padding(start = 4.dp, top = 8.dp), style = DshType.xxs12, color = c.error) }
}
