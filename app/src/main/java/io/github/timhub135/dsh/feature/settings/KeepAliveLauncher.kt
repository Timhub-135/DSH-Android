package io.github.timhub135.dsh.feature.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import io.github.timhub135.dsh.feature.settings.RomKeepAlive.Kind
import io.github.timhub135.dsh.feature.settings.RomKeepAlive.Target

/** Opens the vendor keep-alive screens from [RomKeepAlive], trying candidates until one starts. */
object KeepAliveLauncher {
    val rom: RomKeepAlive.Rom by lazy { RomKeepAlive.detect(Build.MANUFACTURER, Build.BRAND, ::sysProp) }

    private val getProp by lazy { propGetter() }

    @SuppressLint("PrivateApi") // Read-only ROM props; every call fails soft.
    private fun propGetter() = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()

    private fun sysProp(key: String): String? = runCatching { getProp?.invoke(null, key) as? String }.getOrNull()

    /** Live state of [kind]; only Battery is readable, and a failed read is [RomKeepAlive.RowStatus.Unreadable], not "not exempt". */
    fun status(c: Context, kind: Kind): RomKeepAlive.RowStatus =
        RomKeepAlive.status(kind) { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) }

    fun intent(c: Context, t: Target): Intent {
        val i = if (t.action != null) Intent(t.action) else Intent()
        if (t.pkg != null) { if (t.cls != null) i.component = ComponentName(t.pkg, t.cls) else i.setPackage(t.pkg) }
        if (t.pkgUri) i.data = Uri.parse("package:${c.packageName}")
        t.pkgExtras.forEach { i.putExtra(it, c.packageName) }
        if (t.labelExtras.isNotEmpty()) {
            val label = c.applicationInfo.loadLabel(c.packageManager).toString()
            t.labelExtras.forEach { i.putExtra(it, label) }
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Resolves, is exported and needs no permission we lack. */
    private fun launchable(c: Context, i: Intent): Boolean {
        // startActivity adds CATEGORY_DEFAULT to implicit intents, so only those need MATCH_DEFAULT_ONLY.
        val flags = if (i.component != null) 0 else PackageManager.MATCH_DEFAULT_ONLY
        val ai = runCatching { c.packageManager.resolveActivity(i, flags) }.getOrNull()?.activityInfo ?: return false
        if (!ai.exported && ai.packageName != c.packageName) return false
        return ai.permission == null || c.checkSelfPermission(ai.permission) == PackageManager.PERMISSION_GRANTED
    }

    /** True when a vendor-specific screen (not plain App info) will open for [kind]. */
    fun hasVendorScreen(c: Context, kind: Kind): Boolean =
        RomKeepAlive.targets(rom.vendor, kind).any { it != RomKeepAlive.APP_DETAILS && launchable(c, intent(c, it)) }

    /** Opens the first candidate that starts, else App info. Already exempt → the exemption list, not the request dialog. */
    fun open(c: Context, kind: Kind) {
        var targets = RomKeepAlive.targets(rom.vendor, kind)
        if (kind == Kind.Battery && status(c, kind) == RomKeepAlive.RowStatus.Exempt) targets = targets.filterNot { it.action == "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" }
        for (t in targets + RomKeepAlive.APP_DETAILS) {
            val i = intent(c, t)
            if (!launchable(c, i)) continue
            // SecurityException (unexported / guarded on this build) or ActivityNotFoundException: try the next one.
            try { c.startActivity(i); return } catch (_: SecurityException) {} catch (_: ActivityNotFoundException) {}
        }
    }
}
