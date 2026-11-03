package io.github.jackwener.dsh.core.engine

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.trigger.BootReceiver
import java.io.File
import java.io.FileDescriptor
import io.github.jackwener.dsh.engine.NodeRuntime

/**
 * Hosts Node + dsh in the dedicated `:engine` process. Node can be started only once per process,
 * so when it exits the whole process exits and the app restarts it on demand (or right away with
 * 引擎常驻, see [EngineKeepAlive]).
 */
class EngineService : Service() {
    @Volatile private var started = false

    override fun onCreate() {
        super.onCreate()
        val n: Notification = NotificationCompat.Builder(this, Notifications.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(getString(R.string.engine_running))
            .setOngoing(true)
            .setSilent(true)
            // Without a content intent a tap did nothing (#39): open the running conversation.
            .setContentIntent(Notifications.openRunning(this))
            .build()
        // Background-start denials surface in EngineManager.bind(); never crash the process here.
        runCatching { startForeground(Notifications.ID_ENGINE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { Log.w(TAG, "startForeground refused", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is Android re-creating a 引擎常驻 (sticky) service after its process was killed:
        // the process has no Node yet, so ask :main (cold-starting it if needed) to start the engine again.
        if (intent == null) {
            Log.i(TAG, "re-created by the system; asking the app to restore the engine")
            runCatching { sendBroadcast(Intent(this, BootReceiver::class.java).setAction(EngineKeepAlive.ACTION_RESTORE)) }
                .onFailure { Log.w(TAG, "restore broadcast failed", it) }
            return START_STICKY
        }
        return EngineKeepAlive.startMode(intent.getBooleanExtra(EngineKeepAlive.EXTRA_KEEP_ALIVE, false))
    }

    private val binder = object : IEngineService.Stub() {
        override fun isRunning() = started

        override fun start(env: Bundle): Bundle = synchronized(this) {
            check(!started) { "engine already started in this process" }
            started = true
            val engineDir = EngineInstaller.install(this@EngineService)
            val stdin = ParcelFileDescriptor.createPipe()   // [read, write]
            val stdout = ParcelFileDescriptor.createPipe()
            val nodeSide = FileDescriptor()
            val appSide = FileDescriptor()
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, nodeSide, appSide)
            // Keep the Node side open for the lifetime of the process (detached = never auto-closed).
            val bridgeFd = ParcelFileDescriptor.dup(nodeSide).detachFd().also { Os.close(nodeSide) }
            val stderrFile = EngineDiagnostics.rotateLogs(this@EngineService)
            val stderr = ParcelFileDescriptor.open(stderrFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)

            EngineRuntimeEnv.apply(this@EngineService, env)
            Os.setenv("DSH_BRIDGE_FD", bridgeFd.toString(), true)
            File(env.getString("TMPDIR") ?: cacheDir.path).mkdirs()
            File(env.getString("HOME") ?: filesDir.path).mkdirs()

            // argv[0] ("node") is prepended by libdshengine.
            // Entry: dsh's CLI for profile android plus crash/safe-mode bookkeeping and the app-layer overlay (PLUGINS-CONTRACT §1).
            val args = (EngineRuntimeEnv.nodeArgv(engineDir, env) + listOf(File(engineDir, "node_modules/@dsh-android/bundle-android/main.mjs").path)).toTypedArray()
            val inRead = stdin[0].detachFd()
            val outWrite = stdout[1].detachFd()
            val errWrite = stderr.detachFd()
            Thread(null, {
                val code = runCatching { NodeRuntime.nativeStart(args, inRead, outWrite, errWrite) }
                    .onFailure { Log.e(TAG, "node crashed", it) }.getOrDefault(-1)
                Log.i(TAG, "node exited with $code")
                if (code == 0) EngineDiagnostics.markCleanExit(this@EngineService, Process.myPid())
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "node-main", 16L * 1024 * 1024).start()
            Bundle().apply {
                putParcelable("sdk_in", stdin[1])
                putParcelable("sdk_out", stdout[0])
                putParcelable("bridge", ParcelFileDescriptor.dup(appSide).also { Os.close(appSide) })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object { const val TAG = "EngineService" }
}
