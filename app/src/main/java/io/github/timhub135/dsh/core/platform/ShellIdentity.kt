package io.github.timhub135.dsh.core.platform

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import androidx.annotation.RequiresApi

/**
 * Who the Shizuku shell user service is when it calls system services. The process runs as the
 * shell uid, but the ActivityThread system context it borrows reports package "android": services
 * that check the package against the calling uid (DisplayManagerService.createVirtualDisplay on
 * recent Android, #34) refuse that ("packageName must match the calling uid"). Every call that
 * passes a package name must therefore use [ShellContext], which claims the shell package.
 */
internal object ShellIdentity {
    const val PACKAGE = "com.android.shell"
    const val UID = 2000
    /** No attribution tag: the shell package declares none, and an unknown tag is rejected too. */
    val ATTRIBUTION_TAG: String? = null

    /** "SecurityException: packageName must match the calling uid" — one line, bounded, for the app and the model. */
    fun describe(t: Throwable): String {
        // Reflection wraps the real failure; the cause says what the system refused.
        var e = t
        while ((e is java.lang.reflect.InvocationTargetException || e is java.lang.reflect.UndeclaredThrowableException) && e.cause != null) e = e.cause!!
        val msg = e.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return (e.javaClass.simpleName.ifEmpty { e.javaClass.name } + if (msg.isNotEmpty()) ": $msg" else "").take(MAX_ERROR)
    }

    const val MAX_ERROR = 400
}

/**
 * The single Context the shell user service hands to system services: the system context's
 * resources and services, with the shell package as package, op package and attribution source.
 * Managers that cache their creating Context (DisplayManager) are built on this wrapper instead of
 * coming from the system context, so the package they send is the shell's.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = ShellIdentity.PACKAGE
    override fun getOpPackageName(): String = ShellIdentity.PACKAGE
    override fun getAttributionTag(): String? = ShellIdentity.ATTRIBUTION_TAG
    override fun getApplicationContext(): Context = this

    @RequiresApi(31)
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(ShellIdentity.UID).setPackageName(ShellIdentity.PACKAGE).setAttributionTag(ShellIdentity.ATTRIBUTION_TAG).build()

    /** DisplayManager(Context) is hidden; without it the system context's manager is the only one (and reports "android"). */
    private val displayManager: DisplayManager? by lazy {
        runCatching { DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(this) }
            .onFailure { android.util.Log.w("DshShell", "DisplayManager(ShellContext) unavailable", it) }.getOrNull()
    }

    override fun getSystemService(name: String): Any? = when (name) {
        DISPLAY_SERVICE -> displayManager ?: super.getSystemService(name)
        else -> super.getSystemService(name)
    }

    companion object {
        /** The ActivityThread system context of this (shell) process, wrapped. */
        fun create(): ShellContext {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
            return ShellContext(at.getMethod("getSystemContext").invoke(thread) as Context)
        }
    }
}
