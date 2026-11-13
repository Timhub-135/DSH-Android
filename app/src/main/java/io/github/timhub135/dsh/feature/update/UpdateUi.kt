package io.github.timhub135.dsh.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.BuildConfig
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshMarkdown
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.circleBg
import io.github.timhub135.dsh.core.designsystem.findActivity
import io.github.timhub135.dsh.core.update.ApkProblem
import io.github.timhub135.dsh.core.update.AppUpdater
import io.github.timhub135.dsh.core.update.CheckResult
import io.github.timhub135.dsh.core.update.UpdateFailure
import io.github.timhub135.dsh.core.update.UpdateManifest
import io.github.timhub135.dsh.core.update.UpdateState

private fun size(context: Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

/**
 * The update prompt over whatever page is showing (MainActivity): a dialog, so Back / outside tap
 * hides it like 稍后 / 后台下载 (no back handler of its own). It follows [AppUpdater.state]:
 * offer → progress → 安装 → the system installer; failures say why and offer a retry.
 */
@Composable
fun UpdateHost(updater: AppUpdater) {
    val prompt by updater.prompt.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    if (prompt && state != UpdateState.None) UpdateDialog(updater, state)
}

@Composable
private fun UpdateDialog(updater: AppUpdater, state: UpdateState) {
    val context = LocalContext.current
    val c = Dsh.colors
    val m = state.manifest ?: return
    var confirmStop by remember { mutableStateOf(false) }
    val install: () -> Unit = { if (updater.agentBusy()) confirmStop = true else context.findActivity()?.let { updater.install(it, stopRuns = false) } }
    if (confirmStop) {
        DDialog({ confirmStop = false }, stringResource(R.string.upd_busy_title), buttons = {
            DButton(stringResource(R.string.cancel), { confirmStop = false }, variant = BtnVariant.Outline)
            DButton(stringResource(R.string.upd_busy_confirm), { confirmStop = false; context.findActivity()?.let { updater.install(it, stopRuns = true) } }, variant = BtnVariant.Danger)
        }) { Text(stringResource(R.string.upd_busy_body), style = DshType.s14, color = c.labelSecondary) }
        return
    }
    val title = when (state) {
        is UpdateState.Ready, is UpdateState.Installing, is UpdateState.AwaitingUnknownSources -> stringResource(R.string.upd_ready_title, m.versionName)
        is UpdateState.Failed -> stringResource(R.string.upd_failed_title)
        else -> stringResource(R.string.upd_title, m.versionName)
    }
    val dismiss: () -> Unit = if (state is UpdateState.Available) updater::later else updater::hidePrompt
    DDialog(dismiss, title, buttons = {
        when (state) {
            is UpdateState.Available -> {
                DButton(stringResource(R.string.upd_skip), updater::skip, variant = BtnVariant.Ghost, size = BtnSize.Sm)
                DButton(stringResource(R.string.upd_later), updater::later, variant = BtnVariant.Outline, size = BtnSize.Sm)
                if (state.signerOk) DButton(stringResource(R.string.upd_update), updater::startDownload, size = BtnSize.Sm)
                else DButton(stringResource(R.string.upd_open_releases), { openReleases(context); updater.later() }, size = BtnSize.Sm)
            }
            is UpdateState.Downloading -> {
                DButton(stringResource(R.string.upd_cancel), updater::cancelDownload, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.upd_background), updater::hidePrompt, size = BtnSize.Sm)
            }
            is UpdateState.Ready -> {
                DButton(stringResource(R.string.upd_later), updater::hidePrompt, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.upd_install), install, size = BtnSize.Sm)
            }
            is UpdateState.Failed -> {
                DButton(stringResource(R.string.close), updater::hidePrompt, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.retry), updater::startDownload, size = BtnSize.Sm)
            }
            else -> DButton(stringResource(R.string.upd_background), updater::hidePrompt, variant = BtnVariant.Outline, size = BtnSize.Sm)
        }
    }) {
        Text(stringResource(R.string.upd_sizes, BuildConfig.VERSION_NAME, size(context, m.apk.size)), style = DshType.xs13, color = c.labelTertiary)
        when (state) {
            is UpdateState.Available -> if (!state.signerOk) Text(stringResource(R.string.upd_signer_mismatch), style = DshType.s14, color = c.labelSecondary)
            is UpdateState.Downloading -> Progress(state.done, state.total)
            is UpdateState.Verifying -> Busy(stringResource(R.string.upd_verifying))
            is UpdateState.Ready -> Text(stringResource(if (state.cancelled) R.string.upd_install_cancelled else R.string.upd_ready_body), style = DshType.s14, color = c.labelSecondary)
            is UpdateState.AwaitingUnknownSources -> Text(stringResource(R.string.upd_awaiting), style = DshType.s14, color = c.labelSecondary)
            is UpdateState.Installing -> Busy(stringResource(R.string.upd_installing))
            is UpdateState.Failed -> Text(failureText(context, state.failure), style = DshType.s14, color = c.error)
            UpdateState.None -> {}
        }
        if (state is UpdateState.Available || state is UpdateState.Downloading) Notes(m)
    }
}

@Composable
private fun Notes(m: UpdateManifest) {
    val notes = m.notesFor(LocalConfiguration.current.locales[0].language) ?: return
    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { DshMarkdown(notes, compact = true) }
}

@Composable
private fun Progress(done: Long, total: Long) {
    val context = LocalContext.current
    val c = Dsh.colors
    val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).circleBg(c.bgModule)) { Box(Modifier.fillMaxWidth(f).height(4.dp).circleBg(c.brandPrimary)) }
        Text(stringResource(R.string.upd_progress, size(context, done), size(context, total)) + "  ·  ${(f * 100).toInt()}%", style = DshType.xxs12, color = c.labelTertiary)
    }
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Spinner(); Text(text, style = DshType.s14, color = Dsh.colors.labelSecondary)
}

private fun openReleases(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManifest.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun failureText(context: Context, f: UpdateFailure): String = when (f) {
    is UpdateFailure.Network -> context.getString(R.string.upd_err_network, f.detail.take(200))
    is UpdateFailure.NoSpace -> context.getString(R.string.upd_err_space, size(context, f.needed))
    UpdateFailure.UnknownSourcesDenied -> context.getString(R.string.upd_err_unknown_sources)
    is UpdateFailure.Install -> context.getString(R.string.upd_err_install, listOfNotNull("status ${f.status}", f.message).joinToString(": "))
    is UpdateFailure.Apk -> when (val p = f.problem) {
        ApkProblem.Unreadable -> context.getString(R.string.upd_err_unreadable)
        is ApkProblem.OtherPackage -> context.getString(R.string.upd_err_package, p.packageName)
        is ApkProblem.NotNewer -> context.getString(R.string.upd_err_not_newer, p.versionCode, p.installed)
        is ApkProblem.WrongVersion -> context.getString(R.string.upd_err_wrong_version, p.versionCode, p.expected)
        ApkProblem.OtherSigner -> context.getString(R.string.upd_err_signer)
    }
}

/**
 * 设置 → 关于: 检查更新 (current version, last check and its result, or the update in progress)
 * and the 自动检查更新 switch.
 */
@Composable
fun UpdateSettingsRows(updater: AppUpdater, autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit) {
    val context = LocalContext.current
    val c = Dsh.colors
    val state by updater.state.collectAsStateWithLifecycle()
    val checking by updater.checking.collectAsStateWithLifecycle()
    val last by updater.lastCheck.collectAsStateWithLifecycle()
    val detail = when (val s = state) {
        is UpdateState.Downloading -> stringResource(R.string.upd_downloading, s.m.versionName) + " · " +
            stringResource(R.string.upd_progress, size(context, s.done), size(context, s.total))
        is UpdateState.Ready -> stringResource(R.string.upd_ready_title, s.m.versionName)
        is UpdateState.Failed -> failureText(context, s.failure)
        else -> {
            val l = last
            if (l == null) stringResource(R.string.upd_never)
            else stringResource(R.string.upd_last, Fmt.stamp(context, l.at), when (val r = l.result) {
                CheckResult.UpToDate -> stringResource(R.string.upd_res_uptodate)
                is CheckResult.Found -> stringResource(R.string.upd_res_found, r.versionName)
                is CheckResult.Unsupported -> stringResource(R.string.upd_res_unsupported, r.versionName, r.minSdk)
                is CheckResult.Skipped -> stringResource(R.string.upd_res_skipped, r.versionName)
                is CheckResult.Failed -> stringResource(R.string.upd_res_failed, r.detail.take(160))
            })
        }
    }
    SettingsRow(stringResource(R.string.upd_check), detail, trailing = {
        when {
            checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spinner(); Text(stringResource(R.string.upd_checking), style = DshType.xs13, color = c.labelTertiary)
            }
            state != UpdateState.None -> DButton(stringResource(R.string.upd_view), updater::showPrompt, size = BtnSize.Sm)
            else -> DButton(stringResource(R.string.upd_check), updater::checkNow, variant = BtnVariant.Outline, size = BtnSize.Sm)
        }
    })
    SettingsRow(stringResource(R.string.upd_auto), stringResource(R.string.upd_auto_desc), trailing = { DSwitch(autoCheck, onAutoCheck) })
}
