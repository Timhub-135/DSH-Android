package io.github.timhub135.dsh.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.BuildConfig
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(val code: Int, val output: String)

/**
 * Thrown when a step needs the shell user and Shizuku is not [ShellState.Ready]. The capability
 * layer turns it into the Shizuku card in the composer slot and retries the call once it is ready.
 */
class ShellUnavailableException(val state: ShellState) : PermissionMissingException(SHIZUKU_WHAT)

/** PendingPermission.what for Shizuku; the composer slot renders the Shizuku card for it. */
const val SHIZUKU_WHAT = "Shizuku"

/** Shell-user privileges through Shizuku (optional enhancement); availability comes from [ShellAccess]. */
@Singleton
class ShizukuShell @Inject constructor(@ApplicationContext private val context: Context, val access: ShellAccess) {
    private val mutex = Mutex()
    @Volatile private var service: IShellService? = null

    init { Shizuku.addBinderDeadListener { service = null } }

    /** Live check (refreshes [ShellAccess]); true when shell commands can run now. */
    val available: Boolean get() = access.refresh().ready

    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name))
        .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    suspend fun service(): IShellService = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        access.refresh().let { if (!it.ready) throw ShellUnavailableException(it) }
        val deferred = CompletableDeferred<IShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) deferred.complete(IShellService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, conn) }
        // Starting the shell user service can take >10 s on slow phones (#8). If it never connects (a
        // stale binding after Shizuku restarted), unbind it so the next call binds from scratch.
        withTimeoutOrNull(30_000) { deferred.await() }?.also { service = it }
            ?: run {
                withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, conn, true) } }
                error("the Shizuku shell service did not start within 30 s; try again")
            }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        val raw = service().exec(cmd, timeoutMs)
        ShellResult(raw.substringBefore('\n').toIntOrNull() ?: -1, raw.substringAfter('\n'))
    }

}
