package io.github.timhub135.dsh.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.CodeCard
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.KeyValue
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.ShimmerText
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.copyText
import io.github.timhub135.dsh.core.extensions.ExtProgress
import io.github.timhub135.dsh.core.extensions.ExtText
import io.github.timhub135.dsh.core.extensions.InspectResult
import io.github.timhub135.dsh.core.extensions.InstallFlow
import io.github.timhub135.dsh.core.extensions.UserError

/**
 * The one confirmation of an install (§8.1, §9.3): checking → card (source, publisher, downloads,
 * provenance, size, the built-in settings it changes, the honest trust notice) → progress → result.
 * There is no other dialog: an incompatible version offers 仍要安装 as a link on the same card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallSheet(vm: ExtensionsViewModel, onOpenMcp: () -> Unit) {
    val state by vm.install.state.collectAsStateWithLifecycle()
    if (state == InstallFlow.State.Idle) return
    val c = Dsh.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet({ if (state is InstallFlow.State.Installing) vm.install.cancel() else vm.install.dismiss() }, sheetState = sheet, containerColor = c.bgLayer2,
        shape = Radius.panel.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
        scrimColor = c.scrim, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val s = state) {
                is InstallFlow.State.Inspecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(16.dp); Spacer(Modifier.width(10.dp))
                    Column {
                        Text(s.input.spec, style = DshType.s14m, color = c.labelPrimary, maxLines = 2)
                        ShimmerText(stringResource(R.string.ext_inspecting), DshType.xs13, c.labelTertiary, true)
                    }
                }
                is InstallFlow.State.Confirm -> ConfirmCard(vm, s.info, error = null)
                is InstallFlow.State.Installing -> {
                    Header(s.info?.titleText ?: s.info?.name ?: s.input.spec, s.info?.version)
                    val progress by vm.store.progress.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(14.dp); Spacer(Modifier.width(8.dp))
                        ShimmerText(phaseText(progress[s.requestId]), DshType.xs13, c.labelSecondary, true)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DButton(stringResource(R.string.cancel), { vm.install.cancel() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    }
                }
                is InstallFlow.State.Done -> {
                    Header(s.info?.titleText ?: s.info?.name.orEmpty(), s.view.version ?: s.info?.version)
                    Text(stringResource(R.string.ext_installed_done, s.info?.titleText ?: s.info?.name.orEmpty()), style = DshType.s14, color = c.labelPrimary)
                    Text(stringResource(R.string.ext_installed_restart), style = DshType.xs13, color = c.labelTertiary)
                    s.view.warnings.forEach { Text(it, style = DshType.xxs12, color = c.warnLabel) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        DButton(stringResource(R.string.close), { vm.install.dismiss() }, size = BtnSize.Sm)
                    }
                }
                is InstallFlow.State.Failed -> if (s.info != null && s.error.code == "incompatible-version") ConfirmCard(vm, s.info, s.error)
                    else FailedCard(vm, s, onOpenMcp)
                InstallFlow.State.Idle -> {}
            }
        }
    }
}

@Composable
private fun Header(title: String, version: String?) {
    val c = Dsh.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        PluginIcon(null, null)
        Spacer(Modifier.width(10.dp))
        Text(title, Modifier.weight(1f), style = DshType.base16m, color = c.labelPrimary, maxLines = 2)
        version?.let { Text(it, style = DshType.mono12, color = c.labelTertiary) }
    }
}

@Composable
private fun phaseText(p: ExtProgress?): String = when (p?.phase) {
    "resolving" -> if (p.index != null && p.total != null && p.total > 1) stringResource(R.string.ext_phase_resolving_n, p.index, p.total) else stringResource(R.string.ext_phase_resolving)
    "installing" -> stringResource(R.string.ext_phase_installing)
    "checking" -> stringResource(R.string.ext_phase_checking)
    "applying" -> stringResource(R.string.ext_phase_applying)
    else -> stringResource(R.string.ext_phase_resolving)
}

@Composable
private fun ConfirmCard(vm: ExtensionsViewModel, info: InspectResult, error: UserError?) {
    val c = Dsh.colors
    val context = LocalContext.current
    val list by vm.store.list.collectAsStateWithLifecycle()
    Header(info.titleText ?: info.name, info.version)
    info.descriptionText?.let { Text(it, style = DshType.xs13, color = c.labelSecondary) }
    Column(Modifier.fillMaxWidth().clip(Radius.lg).background(c.bgModule).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val source = listOfNotNull(sourceLabel(info.kind) ?: info.kind, info.repo ?: info.registry?.removePrefix("https://")?.trimEnd('/')).joinToString(" · ")
        if (source.isNotBlank()) KeyValue(stringResource(R.string.ext_card_source), source)
        KeyValue("spec", info.spec, mono = true)
        (info.publisher ?: info.maintainers.firstOrNull())?.let { KeyValue(stringResource(R.string.ext_card_publisher), (listOf(it) + info.maintainers.filter { m -> m != it }.take(2)).joinToString(", ")) }
        info.downloads?.let { KeyValue(stringResource(R.string.ext_card_downloads), "%,d".format(it)) }
        info.license?.let { KeyValue(stringResource(R.string.ext_card_license), it) }
        info.publishedAt?.let { KeyValue(stringResource(R.string.ext_card_published), Fmt.relative(context, if (it < 100_000_000_000L) it * 1000 else it)) }
        info.sizeBytes?.let { KeyValue(stringResource(R.string.ext_card_size), android.text.format.Formatter.formatShortFileSize(context, it)) }
    }
    if (info.provenance == true) Row(verticalAlignment = Alignment.CenterVertically) {
        DIcon(R.drawable.dsw_check_circle_outline, size = 14.dp, tint = c.success); Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.ext_card_provenance), style = DshType.xs13, color = c.labelSecondary)
    }
    if (info.overrides.isNotEmpty()) Text(stringResource(R.string.ext_card_changes, info.overrides.joinToString("、") { it.label }), style = DshType.xs13m, color = c.warnLabel)
    // The trust notice is the card body, not an extra dialog (§8.1).
    Text(stringResource(R.string.ext_trust_notice), style = DshType.xs13, color = c.labelTertiary)
    val incompatible = error?.code == "incompatible-version" || !info.compatible
    if (incompatible) {
        Text(error?.let { ExtText.error(context, it) }
            ?: stringResource(R.string.ext_incompatible, info.titleText ?: info.name, info.requiredRange ?: "?", list?.dshVersion?.ifBlank { null } ?: "?"),
            style = DshType.xs13, color = c.warnLabel)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        DButton(stringResource(R.string.cancel), { vm.install.dismiss() }, variant = BtnVariant.Outline, size = BtnSize.Md)
        if (!incompatible) DButton(stringResource(R.string.ext_install), { vm.install.confirm() }, size = BtnSize.Md)
    }
    if (incompatible) DButton(stringResource(R.string.ext_force_install), { vm.install.confirm(force = true) }, Modifier.fillMaxWidth(), variant = BtnVariant.Ghost, size = BtnSize.Sm)
}

@Composable
private fun FailedCard(vm: ExtensionsViewModel, s: InstallFlow.State.Failed, onOpenMcp: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val log by vm.log.collectAsStateWithLifecycle()
    Header(s.info?.titleText ?: s.info?.name ?: s.input.spec.take(80), s.info?.version)
    Text(ExtText.error(context, s.error), style = DshType.s14, color = c.labelPrimary)
    s.error.detail?.takeIf { it.isNotBlank() }?.let { CodeCard(it, maxHeight = 160.dp) }
    log?.let { l -> CodeCard(l.text.ifBlank { "—" }, label = l.path ?: "log", maxHeight = 240.dp) }
    val actions = s.error.actions
    val networkish = s.error.code in setOf("network", "integrity", "not-found") || UserError.RETRY_MIRROR in actions
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (s.error.code == "mcp-json" || s.error.code == "not-bundle") DButton(stringResource(R.string.ext_tab_mcp), { vm.install.dismiss(); onOpenMcp() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
        if (UserError.OPEN_LOG in actions || s.error.code == "pnpm-failed" || s.error.logPath != null)
            DButton(stringResource(R.string.ext_view_log_btn), { if (log == null) vm.loadLog(s.info?.name) else vm.closeLog() }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
        DButton(stringResource(R.string.ext_copy_diag), {
            copyText(context, listOfNotNull("input: ${s.input.spec}", "code: ${s.error.code}", s.error.message, s.error.detail, log?.text?.takeLast(4000)).joinToString("\n"))
        }, variant = BtnVariant.Ghost, size = BtnSize.Sm)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        DButton(stringResource(R.string.close), { vm.closeLog(); vm.install.dismiss() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
        if (networkish) DButton(stringResource(R.string.ext_retry_mirror), { vm.closeLog(); vm.retryOtherRegistry(s.input.spec) }, size = BtnSize.Sm)
        else if (s.input.installable && s.error.code !in setOf("needs-web-services", "native-addon", "prebuilt-binary", "needs-desktop-browser", "not-bundle", "needs-git", "needs-build", "already-installed"))
            DButton(stringResource(R.string.retry), { vm.closeLog(); vm.install.start(s.input.spec) }, size = BtnSize.Sm)
    }
    Spacer(Modifier.height(4.dp))
}
