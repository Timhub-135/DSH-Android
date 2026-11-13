package io.github.timhub135.dsh.core.update

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
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.core.platform.AppVisibility
import io.github.timhub135.dsh.core.platform.Notifications
import io.github.timhub135.dsh.core.provider.appUserAgent
import io.github.timhub135.dsh.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app's own update stands (the prompt dialog and 设置 → 关于 draw it). */
sealed interface UpdateState {
    /** No newer release known. */
    data object None : UpdateState
    /** [signerOk] false: the release is signed by another key than this install (a self-built / debug build): manual download only. */
    data class Available(val m: UpdateManifest, val signerOk: Boolean) : UpdateState
    data class Downloading(val m: UpdateManifest, val done: Long, val total: Long) : UpdateState
    data class Verifying(val m: UpdateManifest) : UpdateState
    /** Downloaded and verified; [cancelled]: the system install dialog was dismissed once. */
    data class Ready(val m: UpdateManifest, val file: File, val cancelled: Boolean = false) : UpdateState
    /** Sent to 安装未知应用; the install resumes by itself when the user comes back with it allowed. */
    data class AwaitingUnknownSources(val m: UpdateManifest, val file: File) : UpdateState
    data class Installing(val m: UpdateManifest) : UpdateState
    data class Failed(val m: UpdateManifest, val failure: UpdateFailure) : UpdateState

    val manifest: UpdateManifest? get() = when (this) {
        None -> null
        is Available -> m; is Downloading -> m; is Verifying -> m; is Ready -> m
        is AwaitingUnknownSources -> m; is Installing -> m; is Failed -> m
    }
}

sealed interface UpdateFailure {
    data class Network(val detail: String) : UpdateFailure
    data class NoSpace(val needed: Long) : UpdateFailure
    data class Apk(val problem: ApkProblem) : UpdateFailure
    data object UnknownSourcesDenied : UpdateFailure
    data class Install(val status: Int, val message: String?) : UpdateFailure
}

/** The outcome of the last check (设置 → 关于 shows it with its time). */
sealed interface CheckResult {
    data object UpToDate : CheckResult
    data class Found(val versionName: String) : CheckResult
    data class Unsupported(val versionName: String, val minSdk: Int) : CheckResult
    data class Skipped(val versionName: String) : CheckResult
    data class Failed(val detail: String) : CheckResult
}

data class LastCheck(val at: Long, val result: CheckResult)

/**
 * The in-app updater for this sideloaded app (GitHub Releases, not Play), after F-Droid / NewPipe
 * / Obtainium: [UpdateManifest] from the first answering of its sources, at most one automatic
 * check a day (自动检查更新), a prompt with 更新 / 稍后 / 跳过此版本, a resumable download
 * ([ApkDownloader]) verified by SHA-256 and by package, versionCode and signer against the
 * installed app ([ApkCheck]), then a [PackageInstaller] session like [io.github.timhub135.dsh.core.platform.ShizukuInstaller]'s.
 * A successful install replaces (kills) this process; the next start cleans the download up.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val sessions: dagger.Lazy<SessionStore>,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _lastCheck = MutableStateFlow<LastCheck?>(null)
    val lastCheck: StateFlow<LastCheck?> = _lastCheck.asStateFlow()
    /** The prompt dialog is up (found by a check, or a download finished); 稍后 / closing hides it. */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Blocking HTTP runs here, awaited from [scope]: a cancelled race returns at once instead of waiting out socket timeouts. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val net by lazy { HttpUpdateNet(appUserAgent(BuildConfig.VERSION_NAME)) }
    private val downloader by lazy { ApkDownloader(net) }
    private val dir get() = File(context.cacheDir, "update")
    private var download: Job? = null
    private var host = WeakReference<Activity>(null)
    private var started = false

    val installedVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** Process start (DshApp): restores a known update, cleans up after an install, and runs the daily check. */
    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onStatus(i)
        }, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        _lastCheck.value = readLastCheck()
        restore()
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { resumeAfterSettings(); autoCheck() }
            })
        }
        autoCheck()
    }

    /** A found update from an earlier run (no network needed to show it again); stale ones and their files go. */
    private fun restore() {
        val m = prefs.getString(KEY_MANIFEST, null)?.let { runCatching { UpdateManifest.parse(it, context.packageName) }.getOrNull() }
        if (m == null || Offer.of(m, installedVersionCode, Build.VERSION.SDK_INT, null) != Offer.Offer) {
            prefs.edit().remove(KEY_MANIFEST).apply()
            scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
            return
        }
        if (m.versionCode == skippedCode()) return
        _state.value = UpdateState.Available(m, signerOk(m))
    }

    private fun skippedCode(): Long? = prefs.getLong(KEY_SKIPPED, 0L).takeIf { it > 0 }

    private fun autoCheck() = scope.launch {
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.autoCheckDue(settings.current().autoUpdateCheck, prefs.getLong(KEY_ATTEMPT, 0L), now)) return@launch
        check(manual = false)
    }

    /** 检查更新 (manual): also offers a version skipped before. */
    fun checkNow() { scope.launch { check(manual = true) } }

    private suspend fun check(manual: Boolean) {
        if (_checking.value) return
        _checking.value = true
        prefs.edit().putLong(KEY_ATTEMPT, System.currentTimeMillis()).apply()
        try {
            val (from, m) = withTimeout(CHECK_DEADLINE_MS) {
                firstSuccess(UpdateManifest.SOURCES, STAGGER_MS) { url -> UpdateManifest.parse(io.async { net.text(url) }.await(), context.packageName) }
            }
            Log.i(TAG, "manifest ${m.versionName} (${m.versionCode}) from ${hostOf(from)}")
            val offer = Offer.of(m, installedVersionCode, Build.VERSION.SDK_INT, if (manual) null else skippedCode())
            record(when (offer) {
                Offer.Offer -> CheckResult.Found(m.versionName)
                Offer.NotNewer -> CheckResult.UpToDate
                Offer.Unsupported -> CheckResult.Unsupported(m.versionName, m.minSdk)
                Offer.Skipped -> CheckResult.Skipped(m.versionName)
            })
            if (offer != Offer.Offer) return
            prefs.edit().putString(KEY_MANIFEST, m.raw).apply()
            // A download / install of this version in progress stays; a newer release replaces an older offer.
            val cur = _state.value
            if (cur.manifest?.versionCode != m.versionCode || cur is UpdateState.Available) {
                if (cur is UpdateState.Downloading || cur is UpdateState.Verifying) cancelDownload()
                _state.value = UpdateState.Available(m, signerOk(m))
            }
            if (manual || UpdatePolicy.promptDue(prefs.getLong(KEY_SNOOZED, 0L), System.currentTimeMillis())) {
                _prompt.value = true
                if (!manual && !AppVisibility.isForeground) notifyFound(m)
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) record(CheckResult.Failed("timeout")) else throw e
        } catch (e: Throwable) {
            Log.w(TAG, "update check failed", e)
            record(CheckResult.Failed(e.message ?: e.javaClass.simpleName))
        } finally { _checking.value = false }
    }

    private fun signerOk(m: UpdateManifest) = ApkCheck.manifestSignerMatches(ApkCheck.installed(context), m.signerSha256)

    private fun notifyFound(m: UpdateManifest) = runCatching {
        Notifications.post(context, Notifications.CHANNEL_UPDATES, Notifications.ID_UPDATE,
            context.getString(R.string.upd_notif_title, m.versionName), context.getString(R.string.upd_notif_text), null,
            Notifications.openSection(context, "About"))
    }

    // ── prompt ──
    fun showPrompt() { _prompt.value = true }
    /** 稍后: hidden until the next daily check finds it again. */
    fun later() { _prompt.value = false; prefs.edit().putLong(KEY_SNOOZED, System.currentTimeMillis()).apply() }
    /** Hides the dialog without snoozing (a download keeps going; 设置 → 关于 shows it). */
    fun hidePrompt() { _prompt.value = false }
    /** 跳过此版本: never offered automatically again (检查更新 still shows it). */
    fun skip() {
        val m = _state.value.manifest ?: return
        prefs.edit().putLong(KEY_SKIPPED, m.versionCode).remove(KEY_MANIFEST).apply()
        cancelDownload()
        _state.value = UpdateState.None
        _prompt.value = false
        record(CheckResult.Skipped(m.versionName))
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    // ── download ──
    fun startDownload() {
        val cur = _state.value
        val m = cur.manifest ?: return
        if (cur is UpdateState.Downloading || cur is UpdateState.Verifying || cur is UpdateState.Installing) return
        if (cur is UpdateState.Available && !cur.signerOk) return
        download = scope.launch {
            _state.value = UpdateState.Downloading(m, 0, m.apk.size)
            try {
                val part = downloader.partFile(dir, m.apk).length()
                val needed = m.apk.size - part + SPACE_MARGIN
                if (withContext(Dispatchers.IO) { freeBytes() } < needed) { _state.value = UpdateState.Failed(m, UpdateFailure.NoSpace(needed)); return@launch }
                var shown = -1L
                val file = withContext(Dispatchers.IO) {
                    downloader.download(m.apk, dir) { done, total ->
                        // About 200 steps: enough for the bar, few enough for recomposition.
                        if (done == total || done - shown >= total / 200) { shown = done; _state.value = UpdateState.Downloading(m, done, total) }
                    }
                }
                _state.value = UpdateState.Verifying(m)
                val problem = withContext(Dispatchers.IO) { ApkCheck.check(context, file, m.versionCode) }
                if (problem != null) {
                    Log.w(TAG, "downloaded APK refused: $problem")
                    withContext(Dispatchers.IO) { file.delete() }
                    _state.value = UpdateState.Failed(m, UpdateFailure.Apk(problem))
                    return@launch
                }
                _state.value = UpdateState.Ready(m, file)
                _prompt.value = true
            } catch (e: CancellationException) {
                // 取消 goes back to the offer; skip() / a newer release have set their own state already.
                if (_state.value.let { it is UpdateState.Downloading || it is UpdateState.Verifying }) _state.value = UpdateState.Available(m, true)
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "update download failed", e)
                _state.value = UpdateState.Failed(m, UpdateFailure.Network(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Space a download may use: free space plus cache the system would clear for it. */
    private fun freeBytes(): Long {
        dir.mkdirs()
        return runCatching {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(dir))
        }.getOrElse { dir.usableSpace }
    }

    /** 取消: stops the transfer; the part stays, so 更新 later resumes it. */
    fun cancelDownload() { download?.cancel(); download = null }

    // ── install ──
    /** A conversation or automation run is in progress (installing ends it: the process is replaced). */
    fun agentBusy(): Boolean = runCatching { sessions.get().hasRunning() }.getOrDefault(false)

    /** 安装: asks for 安装未知应用 first when needed. [stopRuns]: the user confirmed ending the running task. */
    fun install(activity: Activity, stopRuns: Boolean) {
        val cur = _state.value as? UpdateState.Ready ?: return
        host = WeakReference(activity)
        if (stopRuns) scope.launch { runCatching { sessions.get().restartEngine() } }
        if (!context.packageManager.canRequestPackageInstalls()) {
            prefs.edit().putLong(KEY_AWAITING, System.currentTimeMillis()).apply()
            _state.value = UpdateState.AwaitingUnknownSources(cur.m, cur.file)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (runCatching { activity.startActivity(settings) }.isFailure) {
                prefs.edit().remove(KEY_AWAITING).apply()
                _state.value = UpdateState.Failed(cur.m, UpdateFailure.Install(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
            }
            return
        }
        startSession(cur.m, cur.file)
    }

    private fun resumeAfterSettings() {
        val since = prefs.getLong(KEY_AWAITING, 0L)
        if (since == 0L) return
        prefs.edit().remove(KEY_AWAITING).apply()
        val cur = _state.value as? UpdateState.AwaitingUnknownSources ?: return
        when {
            System.currentTimeMillis() - since > RESUME_WINDOW_MS -> _state.value = UpdateState.Ready(cur.m, cur.file)
            context.packageManager.canRequestPackageInstalls() -> startSession(cur.m, cur.file)
            else -> _state.value = UpdateState.Failed(cur.m, UpdateFailure.UnknownSourcesDenied)
        }
    }

    private fun startSession(m: UpdateManifest, file: File) {
        _state.value = UpdateState.Installing(m)
        _prompt.value = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { writeAndCommit(file) } }.exceptionOrNull()?.let {
                Log.w(TAG, "install session failed", it)
                _state.value = UpdateState.Failed(m, UpdateFailure.Install(PackageInstaller.STATUS_FAILURE, it.message))
            }
        }
    }

    private fun writeAndCommit(file: File) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // An app updating itself may skip the confirmation on 12+ (the APK was verified above); the system still asks when it must.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out -> input.copyTo(out, 1 shl 16); session.fsync(out) }
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
        Log.i(TAG, "install status $status ${message.orEmpty()}")
        val m = _state.value.manifest ?: return
        val file = downloader.finalFile(dir, m.apk)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(i, Intent.EXTRA_INTENT, Intent::class.java)
                val a = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                val ok = confirm != null && (if (a != null) runCatching { a.startActivity(confirm) }.isSuccess
                    else runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess)
                if (!ok) _state.value = UpdateState.Failed(m, UpdateFailure.Install(status, message))
            }
            // Usually never seen: the update replaces this process first.
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.None
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Ready(m, file, cancelled = true)
            else -> {
                if (status == PackageInstaller.STATUS_FAILURE_INVALID || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) file.delete()
                _state.value = UpdateState.Failed(m, UpdateFailure.Install(status, message))
            }
        }
    }

    // ── last check ──
    private fun record(r: CheckResult) {
        val lc = LastCheck(System.currentTimeMillis(), r)
        _lastCheck.value = lc
        val (kind, a, b) = when (r) {
            CheckResult.UpToDate -> Triple("uptodate", "", "")
            is CheckResult.Found -> Triple("found", r.versionName, "")
            is CheckResult.Unsupported -> Triple("unsupported", r.versionName, r.minSdk.toString())
            is CheckResult.Skipped -> Triple("skipped", r.versionName, "")
            is CheckResult.Failed -> Triple("failed", r.detail.take(300), "")
        }
        prefs.edit().putLong(KEY_LAST_AT, lc.at).putString(KEY_LAST_KIND, kind).putString(KEY_LAST_A, a).putString(KEY_LAST_B, b).apply()
    }

    private fun readLastCheck(): LastCheck? {
        val at = prefs.getLong(KEY_LAST_AT, 0L).takeIf { it > 0 } ?: return null
        val a = prefs.getString(KEY_LAST_A, "").orEmpty()
        val b = prefs.getString(KEY_LAST_B, "").orEmpty()
        val r = when (prefs.getString(KEY_LAST_KIND, null)) {
            "uptodate" -> CheckResult.UpToDate
            "found" -> CheckResult.Found(a)
            "unsupported" -> CheckResult.Unsupported(a, b.toIntOrNull() ?: 0)
            "skipped" -> CheckResult.Skipped(a)
            "failed" -> CheckResult.Failed(a)
            else -> return null
        }
        return LastCheck(at, r)
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val ACTION_STATUS = "io.github.timhub135.dsh.APP_UPDATE_STATUS"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_SKIPPED = "skipped_version_code"
        private const val KEY_SNOOZED = "snoozed_at"
        private const val KEY_ATTEMPT = "last_attempt_at"
        private const val KEY_AWAITING = "awaiting_unknown_sources"
        private const val KEY_LAST_AT = "last_check_at"
        private const val KEY_LAST_KIND = "last_check_kind"
        private const val KEY_LAST_A = "last_check_a"
        private const val KEY_LAST_B = "last_check_b"
        private const val STAGGER_MS = 3_000L
        private const val CHECK_DEADLINE_MS = 60_000L
        private const val RESUME_WINDOW_MS = 10 * 60_000L
        /** Free space kept beyond the APK (the session copies it once more while installing). */
        private const val SPACE_MARGIN = 50_000_000L
    }
}

/** When the background check runs and the prompt shows again (pure, UpdatePolicyTest). */
object UpdatePolicy {
    const val DAY_MS = 24 * 3600_000L

    /** [enabled] = 自动检查更新; at most one attempt (successful or not) per day. A clock set back counts as due. */
    fun autoCheckDue(enabled: Boolean, lastAttempt: Long, now: Long): Boolean = enabled && (lastAttempt <= 0 || now - lastAttempt >= DAY_MS || now < lastAttempt)

    /** 稍后 hides the prompt for a day; the update stays in 设置 → 关于. */
    fun promptDue(snoozedAt: Long, now: Long): Boolean = snoozedAt <= 0 || now - snoozedAt >= DAY_MS || now < snoozedAt
}
