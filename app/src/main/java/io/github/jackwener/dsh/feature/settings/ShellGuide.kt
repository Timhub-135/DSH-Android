package io.github.jackwener.dsh.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DotKind
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.StateDot
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.platform.Browser
import io.github.jackwener.dsh.core.platform.ShellAccess
import io.github.jackwener.dsh.core.platform.ShellState
import io.github.jackwener.dsh.core.platform.ShizukuInstallState
import io.github.jackwener.dsh.core.platform.ShizukuInstaller

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShellAccessEntryPoint { fun shellAccess(): ShellAccess; fun shizukuInstaller(): ShizukuInstaller }

fun shellAccessOf(context: Context): ShellAccess =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shellAccess()

fun shizukuInstallerOf(context: Context): ShizukuInstaller =
    EntryPointAccessors.fromApplication(context.applicationContext, ShellAccessEntryPoint::class.java).shizukuInstaller()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The live Shizuku state; also re-checks on every resume (permission can be revoked inside Shizuku). */
@Composable
fun rememberShellState(): Pair<ShellAccess, ShellState> {
    val context = LocalContext.current
    val access = remember { shellAccessOf(context) }
    val state by access.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access.refresh() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return access to state
}

/** Short status label for rows and Doctor; a too-old server says which API it has. */
fun shellStateLabel(context: Context, s: ShellState): String = when (s) {
    ShellState.NotInstalled -> context.getString(R.string.shizuku_missing)
    is ShellState.InstalledNotRunning -> context.getString(R.string.shizuku_stopped)
    is ShellState.RunningNoPermission -> context.getString(R.string.shizuku_unauthorized)
    is ShellState.Unsupported -> context.getString(R.string.shizuku_unsupported_v, s.version, ShellAccess.MIN_VERSION)
    ShellState.Ready -> context.getString(R.string.shizuku_ready)
}

/** The next step for the state in one line (Doctor rows, the card's caption); null when ready. */
fun shellStateHint(context: Context, s: ShellState): String? = when (s) {
    ShellState.NotInstalled -> context.getString(R.string.shz_hint_missing)
    is ShellState.InstalledNotRunning -> context.getString(if (s.wasReady) R.string.shz_hint_stopped else R.string.shz_hint_start)
    is ShellState.RunningNoPermission -> context.getString(if (s.denied) R.string.shz_hint_denied else R.string.shz_hint_permission)
    is ShellState.Unsupported -> context.getString(R.string.shz_hint_old)
    ShellState.Ready -> null
}

/**
 * The one-tap fix for the current state (Doctor rows), or null when there is nothing to open.
 * [bundled]: install/update happens in-app from 设置 → 手机权限, so there is no store page to send people to.
 */
fun shellFixIntent(access: ShellAccess, s: ShellState, bundled: Boolean): Intent? = when (s) {
    ShellState.NotInstalled, is ShellState.Unsupported -> if (bundled) null else access.playStoreIntent()
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ShellAccess.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    is ShellState.InstalledNotRunning, is ShellState.RunningNoPermission -> access.launchIntent()
    ShellState.Ready -> null
}

/**
 * Remembers what the user already did in this guide so the next step happens by itself:
 * after 打开 Shizuku, the moment its server comes up DSH asks for the permission without another tap.
 */
class ShellGuideFlow(
    private val access: ShellAccess,
    val installer: ShizukuInstaller,
    private val installState: State<ShizukuInstallState>,
    private val context: Context,
) {
    var openedShizuku by mutableStateOf(false)
    var askedPermission by mutableStateOf(false)
    /** Progress of the in-app install/update of the bundled Shizuku APK. */
    val install: ShizukuInstallState get() = installState.value
    /** The bundled install failed (or is impossible): offer the store / GitHub as a way out. */
    val showStoreFallback: Boolean get() = !installer.bundled || install is ShizukuInstallState.Failed

    /** 安装 Shizuku / 一键更新: the bundled APK through PackageInstaller; the store only without one. */
    fun install() {
        val activity = context.findActivity()
        if (installer.bundled && activity != null) installer.install(activity) else storeFallback()
    }
    fun storeFallback() {
        val play = access.playStoreIntent()
        if (play != null && runCatching { context.startActivity(play) }.isSuccess) return
        Browser.openExternal(context, ShellAccess.RELEASES_URL)
    }
    fun openShizuku() {
        access.launchIntent()?.let { i -> if (runCatching { context.startActivity(i) }.isSuccess) { openedShizuku = true; return } }
        install()
    }
    /** Unsupported only because the old server is still running after an in-app update: restart it in Shizuku. */
    val updatedNeedsRestart: Boolean get() = installer.bundled && !installer.updateAvailable
    fun developerOptions() { runCatching { context.startActivity(access.developerOptionsIntent()) } }
    fun requestPermission() { askedPermission = true; access.requestPermission() }
}

@Composable
fun rememberShellGuideFlow(access: ShellAccess, state: ShellState): ShellGuideFlow {
    val context = LocalContext.current
    val installer = remember { shizukuInstallerOf(context) }
    val installState = installer.state.collectAsStateWithLifecycle()
    val flow = remember(access) { ShellGuideFlow(access, installer, installState, context) }
    LaunchedEffect(state) {
        // Installed / updated (or done elsewhere): the install step is over.
        if (state !is ShellState.NotInstalled && state !is ShellState.Unsupported) installer.reset()
    }
    LaunchedEffect(state, flow.openedShizuku) {
        // Came back from Shizuku with its server running: go straight to the permission prompt.
        if (flow.openedShizuku && state is ShellState.RunningNoPermission && !state.denied && !flow.askedPermission) flow.requestPermission()
    }
    return flow
}

/** Title + explanation (+ the 3-step mini guide) for the state. [toolTitle]: the waiting tool, JIT only. */
@Composable
fun ColumnScope.ShellGuideBody(s: ShellState, toolTitle: String?, flow: ShellGuideFlow) {
    val c = Dsh.colors
    val title = when (s) {
        ShellState.NotInstalled -> stringResource(R.string.shz_title_install)
        is ShellState.InstalledNotRunning -> stringResource(if (s.wasReady) R.string.shz_title_stopped else R.string.shz_title_start)
        is ShellState.RunningNoPermission -> stringResource(R.string.shz_title_permission)
        is ShellState.Unsupported -> stringResource(R.string.shz_title_old)
        ShellState.Ready -> stringResource(R.string.shz_title_ready)
    }
    Text(title, style = DshType.s14m.copy(lineHeight = DshType.base16.lineHeight), color = c.labelPrimary)
    if (toolTitle != null && !s.ready) Text(stringResource(R.string.shz_jit_body, toolTitle), style = DshType.xs13, color = c.labelSecondary)
    val installing = s == ShellState.NotInstalled || s is ShellState.Unsupported
    val body = when (s) {
        ShellState.NotInstalled -> stringResource(R.string.shz_body_install)
        is ShellState.InstalledNotRunning -> if (s.wasReady) stringResource(R.string.shz_body_stopped) else null
        is ShellState.RunningNoPermission -> stringResource(if (s.denied) R.string.shz_body_denied else R.string.shz_body_permission)
        is ShellState.Unsupported -> stringResource(if (flow.updatedNeedsRestart) R.string.shz_body_old_restart else R.string.shz_body_old)
        ShellState.Ready -> stringResource(if (toolTitle != null) R.string.shz_body_resuming else R.string.shz_body_ready)
    }
    body?.let { Text(it, style = DshType.xs13, color = if (toolTitle != null) c.labelTertiary else c.labelSecondary) }
    if (installing) installProgress(flow.install)?.let { (text, error) ->
        Text(text, style = DshType.xs13, color = if (error) c.error else c.labelSecondary)
    }
    if (s == ShellState.NotInstalled && flow.install == ShizukuInstallState.Idle && flow.installer.bundled)
        Text(stringResource(R.string.shz_bundled_note, flow.installer.bundledVersionName), style = DshType.xxs12, color = c.labelTertiary)
    if (s is ShellState.InstalledNotRunning && !s.wasReady) {
        listOf(R.string.shz_step1, R.string.shz_step2, R.string.shz_step3).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text("${i + 1}", Modifier.width(16.dp), style = DshType.xs13m, color = c.labelTertiary)
                Text(stringResource(step), style = DshType.xs13, color = c.labelSecondary)
            }
        }
    }
}

/** What the in-app install is doing, for the card body (text, isError); null when there is nothing to say. */
@Composable
private fun installProgress(st: ShizukuInstallState): Pair<String, Boolean>? = when (st) {
    ShizukuInstallState.Idle -> null
    ShizukuInstallState.Installing -> stringResource(R.string.shz_inst_progress) to false
    ShizukuInstallState.AwaitingUnknownSources -> stringResource(R.string.shz_inst_unknown_sources) to false
    ShizukuInstallState.UnknownSourcesDenied -> stringResource(R.string.shz_inst_unknown_denied) to true
    ShizukuInstallState.Cancelled -> stringResource(R.string.shz_inst_cancelled) to false
    is ShizukuInstallState.Failed -> stringResource(R.string.shz_inst_failed, when (st.status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> stringResource(R.string.shz_err_storage)
        PackageInstaller.STATUS_FAILURE_CONFLICT -> stringResource(R.string.shz_err_conflict)
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> stringResource(R.string.shz_err_incompatible)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> stringResource(R.string.shz_err_blocked)
        else -> st.message?.takeIf { it.isNotBlank() }?.take(160) ?: stringResource(R.string.shz_err_unknown)
    }) to true
}

/** 安装 Shizuku / 一键更新 and its in-progress / retry / store-fallback variants. */
@Composable
private fun InstallActions(update: Boolean, flow: ShellGuideFlow, enabled: Boolean, size: BtnSize) {
    val st = flow.install
    if (flow.showStoreFallback && flow.installer.bundled)
        DButton(stringResource(R.string.shz_inst_elsewhere), flow::storeFallback, variant = BtnVariant.Outline, enabled = enabled, size = size)
    val label = when (st) {
        ShizukuInstallState.Installing -> R.string.shz_installing
        ShizukuInstallState.UnknownSourcesDenied, ShizukuInstallState.AwaitingUnknownSources -> R.string.shz_inst_allow
        is ShizukuInstallState.Failed, ShizukuInstallState.Cancelled -> R.string.shz_retry
        ShizukuInstallState.Idle -> if (update) R.string.shz_update else R.string.shz_install
    }
    DButton(stringResource(label), flow::install, enabled = enabled && !st.busy, size = size)
}

/** Buttons for the state; [onDecline] (不用了) is shown when the caller has a fallback to go to. */
@Composable
fun ShellGuideActions(s: ShellState, flow: ShellGuideFlow, onDecline: (() -> Unit)?, enabled: Boolean = true, size: BtnSize = BtnSize.Md) {
    if (onDecline != null && !s.ready) DButton(stringResource(R.string.shz_decline), onDecline, variant = BtnVariant.Outline, enabled = enabled, size = size)
    when (s) {
        ShellState.NotInstalled -> InstallActions(update = false, flow, enabled, size)
        is ShellState.Unsupported ->
            if (flow.updatedNeedsRestart) DButton(stringResource(R.string.shz_open), flow::openShizuku, enabled = enabled, size = size)
            else InstallActions(update = true, flow, enabled, size)
        is ShellState.InstalledNotRunning -> {
            if (!s.wasReady) DButton(stringResource(R.string.shz_dev_options), flow::developerOptions, variant = BtnVariant.Outline, enabled = enabled, size = size)
            DButton(stringResource(R.string.shz_open), flow::openShizuku, enabled = enabled, size = size)
        }
        is ShellState.RunningNoPermission ->
            if (s.denied) DButton(stringResource(R.string.shz_open), flow::openShizuku, enabled = enabled, size = size)
            else DButton(stringResource(R.string.shz_grant), flow::requestPermission, enabled = enabled, size = size)
        ShellState.Ready -> {}
    }
}

/**
 * Standalone Shizuku card for 设置 → 手机权限 and the onboarding 增强能力 step: status, guidance and
 * — once ready, and again whenever it stopped after a reboot — the "start on boot (wireless debugging)" tip.
 */
@Composable
fun ShellAccessCard(modifier: Modifier = Modifier, onDecline: (() -> Unit)? = null) {
    val c = Dsh.colors
    val (access, s) = rememberShellState()
    val flow = rememberShellGuideFlow(access, s)
    var tipDismissed by remember { mutableStateOf(access.bootTipDismissed) }
    SettingsCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateDot(when (s) { ShellState.Ready -> DotKind.Done; is ShellState.Unsupported -> DotKind.Error; ShellState.NotInstalled -> DotKind.Idle; else -> DotKind.Warn })
                Text("Shizuku", Modifier.weight(1f), style = DshType.xs13m, color = c.labelTertiary)
                Tag(shellStateLabel(LocalContext.current, s), when (s) { ShellState.Ready -> Tone.Success; is ShellState.Unsupported -> Tone.Warn; else -> Tone.Neutral })
            }
            ShellGuideBody(s, null, flow)
            // Which Shizuku this is, so "installed" / "too old" can be checked against the app itself (#42).
            if (s != ShellState.NotInstalled) {
                val versions = remember(s) { access.managerVersion()?.ifEmpty { null } to access.serverVersion() }
                Text(stringResource(R.string.shz_versions, versions.first ?: "?", versions.second?.toString() ?: stringResource(R.string.shz_server_down)),
                    style = DshType.xxs12, color = c.labelTertiary)
            }
            // After a reboot (was ready, now stopped) always repeat the tip: having to restart it proves boot-start is off.
            val rebootTip = s is ShellState.InstalledNotRunning && s.wasReady && access.bootTipApplicable
            if ((s.ready && access.bootTipApplicable && !tipDismissed) || rebootTip) {
                Box(Modifier.size(2.dp))
                Text(stringResource(R.string.shz_boot_tip), style = DshType.xs13, color = c.labelSecondary)
            }
        }
        val showTip = s.ready && access.bootTipApplicable && !tipDismissed
        if (!s.ready || showTip) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (showTip) {
                DButton(stringResource(R.string.shz_tip_ok), { access.dismissBootTip(); tipDismissed = true }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.shz_open), { access.dismissBootTip(); tipDismissed = true; flow.openShizuku() }, size = BtnSize.Sm)
            } else ShellGuideActions(s, flow, onDecline, size = BtnSize.Sm)
        }
    }
}
