package io.github.timhub135.dsh.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where shell-user (uid 2000) access through the official Shizuku app stands right now. */
sealed interface ShellState {
    /** The Shizuku app is not installed (and no Shizuku-compatible binder is alive). */
    data object NotInstalled : ShellState
    /** Installed but its server is not running — typical after a reboot. [wasReady]: it worked here before. */
    data class InstalledNotRunning(val wasReady: Boolean) : ShellState
    /** Server running, DSH not authorized. [denied]: the user chose "deny" before, so Shizuku won't ask again. */
    data class RunningNoPermission(val denied: Boolean) : ShellState
    data object Ready : ShellState
    /** Server older than the API DSH needs (user services need v11+). */
    data class Unsupported(val version: Int) : ShellState

    val ready get() = this == Ready
    /** Short model-facing reason, e.g. for tool descriptions and results. */
    val reason get() = when (this) {
        NotInstalled -> "Shizuku is not installed"
        is InstalledNotRunning -> "Shizuku is installed but not running" + if (wasReady) " (it stops after a reboot)" else ""
        is RunningNoPermission -> "DSH is not authorized in Shizuku"
        Ready -> "Shizuku is ready"
        is Unsupported -> "the installed Shizuku is too old (API $version)"
    }
    /** What the user has to do next, in words (diagnostics; the UI has localized ones in ShellGuide). */
    val hint get() = when (this) {
        NotInstalled -> "install Shizuku from DSH → Settings → Phone permissions (optional)"
        is InstalledNotRunning -> if (wasReady) "open Shizuku and tap Start (needed again after every reboot)"
            else "turn on Wireless debugging in Developer options, then pair and start it in Shizuku"
        is RunningNoPermission -> if (denied) "denied before: enable DSH under Authorized apps in Shizuku" else "tap Authorize in DSH (Shizuku shows its prompt)"
        Ready -> "nothing to do"
        is Unsupported -> "update Shizuku to the latest version, then start it again"
    }
}

/**
 * One look at Shizuku; [ShellAccess.classify] turns it into a [ShellState]. Kept apart from the
 * Shizuku calls so the mapping is testable: the binder answering (pingBinder) is what "running"
 * means, the package lookup only tells "not installed" from "installed but stopped".
 */
data class ShellProbe(
    val binderAlive: Boolean,
    /** Server API version; null when asking failed (the binder died between the calls). */
    val serverVersion: Int?,
    /** Whether DSH holds the Shizuku permission; null when asking failed. */
    val granted: Boolean?,
    /** The user chose "deny" before (Shizuku will not prompt again). */
    val denied: Boolean,
    /** The Shizuku manager app is installed (package visible through `<queries>`). */
    val managerInstalled: Boolean,
    /** Shizuku was Ready here before (so a stopped server is the after-reboot case). */
    val wasReady: Boolean,
)

/**
 * The single source of truth for Shizuku: a [StateFlow] driven by Shizuku's binder received/dead
 * and permission-result callbacks, package add/remove broadcasts and app-foreground refreshes —
 * no polling. DSH never runs its own ADB client or privileged server; it only talks to the
 * official Shizuku app (Sui/root are not offered, though a running Sui binder simply counts as Ready).
 */
@Singleton
class ShellAccess @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<ShellState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("shell_access", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Sessions whose user tapped 不用了: no more Shizuku cards there (tools use their fallback). */
    private val declined = ConcurrentHashMap.newKeySet<String>()

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { code, _ -> if (code == REQUEST_CODE) refresh() }
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (i.data?.schemeSpecificPart == PACKAGE) refresh() }
        }, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Permission can be revoked inside the Shizuku app without any callback to us.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { refresh() }
            })
        }
    }

    /** Re-evaluates now (cheap: a ping, a permission check and a package lookup). */
    fun refresh(): ShellState {
        val s = compute()
        if (s.ready && !prefs.getBoolean(KEY_WAS_READY, false)) prefs.edit().putBoolean(KEY_WAS_READY, true).apply()
        _state.value = s
        return s
    }

    private fun compute(): ShellState = classify(probe())

    private fun probe(): ShellProbe {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        // A failed call (the binder died meanwhile) reads as unknown, never as "too old" or "not authorized".
        val version = if (!alive) null else runCatching { if (Shizuku.isPreV11()) 10 else Shizuku.getVersion() }.getOrNull()
        val granted = if (version == null || version < MIN_VERSION) null
            else runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrNull()
        val denied = granted == false && runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        return ShellProbe(alive, version, granted, denied, managerVersion() != null, wasReadyBefore())
    }

    /** Server API version while the binder answers, else null (diagnostics). */
    fun serverVersion(): Int? = if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) null
        else runCatching { if (Shizuku.isPreV11()) 10 else Shizuku.getVersion() }.getOrNull()

    /** One line for 诊断信息: state, versions and the next step. */
    fun describe(s: ShellState = state.value): String =
        "${s.reason}; app ${managerVersion()?.ifEmpty { "?" } ?: "not installed"}; server API ${serverVersion() ?: "-"} (needs $MIN_VERSION+); next: ${s.hint}"

    private fun wasReadyBefore() = runCatching {
        context.getSharedPreferences("shell_access", Context.MODE_PRIVATE).getBoolean(KEY_WAS_READY, false)
    }.getOrDefault(false)

    /** versionName of the installed Shizuku manager, or null when it is not installed. */
    fun managerVersion(): String? = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0).versionName ?: "" }.getOrNull()

    /** Shows Shizuku's own permission dialog (no-op unless the server is running). */
    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    // ---- per-session decline (shared by the composer card and the capability layer) -------------

    fun decline(sessionId: String) { if (sessionId.isNotEmpty()) declined += sessionId }
    fun isDeclined(sessionId: String) = sessionId in declined

    // ---- "start on boot (wireless debugging)" tip, shown once -------------------------------------

    /** Shizuku 13.6+ on Android 11+ can restart itself after boot via wireless debugging (no root). */
    val bootTipApplicable: Boolean get() = Build.VERSION.SDK_INT >= 30 && managerVersion()?.let { atLeast(it, 13, 6) } == true
    val bootTipDismissed: Boolean get() = prefs.getBoolean(KEY_BOOT_TIP, false)
    fun dismissBootTip() = prefs.edit().putBoolean(KEY_BOOT_TIP, true).apply()

    // ---- intents ------------------------------------------------------------------------------

    fun launchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Play Store when Google Play is there, else null (caller opens [RELEASES_URL] in the browser). */
    fun playStoreIntent(): Intent? {
        val hasPlay = runCatching { context.packageManager.getPackageInfo("com.android.vending", 0); true }.getOrDefault(false)
        if (!hasPlay) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        /**
         * The state for one [ShellProbe]: running = the binder answers with a version; a binder that
         * stops answering mid-check counts as not running. Without the manager app it is "not installed"
         * (a stopped server cannot be started without it anyway).
         */
        fun classify(p: ShellProbe): ShellState {
            if (p.binderAlive && p.serverVersion != null) {
                if (p.serverVersion < MIN_VERSION) return ShellState.Unsupported(p.serverVersion)
                when (p.granted) {
                    true -> return ShellState.Ready
                    false -> return ShellState.RunningNoPermission(p.denied)
                    null -> {}
                }
            }
            return if (p.managerInstalled) ShellState.InstalledNotRunning(p.wasReady) else ShellState.NotInstalled
        }

        const val PACKAGE = "moe.shizuku.privileged.api"
        const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
        const val REQUEST_CODE = 7
        const val MIN_VERSION = 11
        private const val KEY_WAS_READY = "was_ready"
        private const val KEY_BOOT_TIP = "boot_tip_dismissed"

        /** "13.6.0.r1086.2650830c" ≥ major.minor? */
        fun atLeast(versionName: String, major: Int, minor: Int): Boolean {
            val p = versionName.split('.', '-', ' ').mapNotNull { it.toIntOrNull() }
            val ma = p.getOrNull(0) ?: return false
            return ma > major || (ma == major && (p.getOrNull(1) ?: 0) >= minor)
        }
    }
}
