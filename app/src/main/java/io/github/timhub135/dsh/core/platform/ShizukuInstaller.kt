package io.github.timhub135.dsh.core.platform

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** Where the in-app install/update of the bundled Shizuku APK stands. */
sealed interface ShizukuInstallState {
    data object Idle : ShizukuInstallState
    /** Sent to 安装未知应用 for DSH; the install resumes by itself when the user comes back with it allowed. */
    data object AwaitingUnknownSources : ShizukuInstallState
    /** Came back from that page without allowing it. */
    data object UnknownSourcesDenied : ShizukuInstallState
    /** Writing the session / waiting for the system confirmation dialog / installing. */
    data object Installing : ShizukuInstallState
    /** The user dismissed the system confirmation. */
    data object Cancelled : ShizukuInstallState
    data class Failed(val status: Int, val message: String?) : ShizukuInstallState

    val busy get() = this == Installing
}

/**
 * Installs (or updates) the official Shizuku app from the APK bundled at `assets/shizuku/shizuku.apk`
 * (see tools/fetch-shizuku.sh) through a [PackageInstaller] session, so users never leave DSH to get it.
 * Same signer as the Play/GitHub builds, so it also updates an existing install in place.
 * Completion is picked up by [ShellAccess] through its package-added/replaced receiver; this class
 * also refreshes it directly on success.
 */
@Singleton
class ShizukuInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: ShellAccess,
) {
    private val _state = MutableStateFlow<ShizukuInstallState>(ShizukuInstallState.Idle)
    val state: StateFlow<ShizukuInstallState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("shizuku_install", Context.MODE_PRIVATE)
    /** The screen that started the install: used for the settings page and the confirmation dialog. */
    private var host = WeakReference<Activity>(null)

    /** The bundled APK is in this build (it is absent when tools/fetch-shizuku.sh was not run). */
    val bundled: Boolean by lazy { runCatching { context.assets.list("shizuku")?.contains(ASSET_NAME) == true }.getOrDefault(false) }
    val bundledVersionName: String get() = BuildConfig.SHIZUKU_VERSION

    /** versionCode of the installed Shizuku, or null when it is not installed. */
    fun installedVersionCode(): Long? = runCatching {
        val pi = context.packageManager.getPackageInfo(ShellAccess.PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    /** An installed Shizuku older than the bundled one (the bundled APK can update it in place). */
    val updateAvailable: Boolean get() = bundled && (installedVersionCode() ?: Long.MAX_VALUE) < BuildConfig.SHIZUKU_VERSION_CODE

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onStatus(i)
        }, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Back from 安装未知应用 (some Android versions restart the app when that switch flips, hence prefs).
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = resumeAfterSettings()
            })
        }
    }

    /** Starts (or retries) the install from [activity]; asks for 安装未知应用 first when needed. */
    fun install(activity: Activity) {
        host = WeakReference(activity)
        if (_state.value.busy || !bundled) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putLong(KEY_AWAITING, System.currentTimeMillis()).apply()
            _state.value = ShizukuInstallState.AwaitingUnknownSources
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (runCatching { activity.startActivity(settings) }.isFailure) {
                prefs.edit().remove(KEY_AWAITING).apply()
                _state.value = ShizukuInstallState.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
            }
            return
        }
        startSession()
    }

    /** Clears a finished/failed attempt (e.g. when the card is no longer showing an install step). */
    fun reset() { if (!_state.value.busy && _state.value != ShizukuInstallState.AwaitingUnknownSources) _state.value = ShizukuInstallState.Idle }

    private fun resumeAfterSettings() {
        val since = prefs.getLong(KEY_AWAITING, 0L)
        if (since == 0L) return
        if (System.currentTimeMillis() - since > RESUME_WINDOW_MS) { prefs.edit().remove(KEY_AWAITING).apply(); _state.value = ShizukuInstallState.Idle; return }
        prefs.edit().remove(KEY_AWAITING).apply()
        if (context.packageManager.canRequestPackageInstalls()) startSession()
        else _state.value = ShizukuInstallState.UnknownSourcesDenied
    }

    private fun startSession() {
        _state.value = ShizukuInstallState.Installing
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { writeAndCommit() } }
            result.exceptionOrNull()?.let {
                Log.w(TAG, "install session failed", it)
                _state.value = ShizukuInstallState.Failed(PackageInstaller.STATUS_FAILURE, it.message)
            }
        }
    }

    /** Streams the asset straight into a session (no temp copy; the asset is stored compressed, so length is unknown). */
    private fun writeAndCommit() {
        val installer = context.packageManager.packageInstaller
        // Drop sessions abandoned by an earlier attempt (process killed mid-install).
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ShellAccess.PACKAGE)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                context.assets.open(ASSET_PATH).use { input ->
                    session.openWrite("shizuku.apk", 0, -1).use { out -> input.copyTo(out, 1 shl 16); session.fsync(out) }
                }
                val status = Intent(ACTION_STATUS).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
    }

    private fun onStatus(i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "status $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) { _state.value = ShizukuInstallState.Failed(status, message); return }
                val a = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                val ok = if (a != null) runCatching { a.startActivity(confirm) }.isSuccess
                else runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!ok) _state.value = ShizukuInstallState.Failed(status, message)
            }
            PackageInstaller.STATUS_SUCCESS -> { _state.value = ShizukuInstallState.Idle; access.refresh() }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = ShizukuInstallState.Cancelled
            else -> _state.value = ShizukuInstallState.Failed(status, message)
        }
    }

    companion object {
        private const val TAG = "ShizukuInstaller"
        private const val ASSET_NAME = "shizuku.apk"
        private const val ASSET_PATH = "shizuku/$ASSET_NAME"
        private const val ACTION_STATUS = "io.github.timhub135.dsh.SHIZUKU_INSTALL_STATUS"
        private const val KEY_AWAITING = "awaiting_unknown_sources"
        private const val RESUME_WINDOW_MS = 10 * 60_000L
    }
}
