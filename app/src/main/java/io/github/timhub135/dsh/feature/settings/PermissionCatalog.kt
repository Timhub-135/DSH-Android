package io.github.timhub135.dsh.feature.settings

import io.github.timhub135.dsh.core.capability.tools.hasAllFilesAccess
import io.github.timhub135.dsh.core.trigger.canExact
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import io.github.timhub135.dsh.core.designsystem.findActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.service.DshAccessibilityService
import io.github.timhub135.dsh.service.DshNotificationListener

/** One grantable thing: how to check it, how to ask for it, and which capabilities need it. */
data class PermissionItem(
    val id: String,
    val titleZh: String, val titleEn: String,
    val whyZh: String, val whyEn: String,
    val groups: Set<ToolGroup>,
    /** Runtime permissions requested in-app; null when granted through a Settings screen. */
    val runtime: List<String>? = null,
    val restrictedSetting: Boolean = false,
    val check: (Context) -> Boolean,
    val intent: (Context) -> Intent? = { null },
) {
    fun title(zh: Boolean) = if (zh) titleZh else titleEn
    fun why(zh: Boolean) = if (zh) whyZh else whyEn
}

object PermissionCatalog {
    private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")
    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c))

    /**
     * After a denied request: true when none of [perms] can be asked again ("don't ask again", or
     * the second denial on Android 11+), so only the app's settings page can grant them.
     */
    fun permanentlyDenied(c: Context, perms: Collection<String>): Boolean {
        val act = c.findActivity() ?: return false
        return perms.isNotEmpty() && perms.none { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, it) } &&
            perms.none { androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    val all: List<PermissionItem> = listOf(
        PermissionItem("notifications", "通知", "Notifications", "任务完成、需要确认时提醒你", "Tell you when tasks finish or need approval",
            ToolGroup.entries.toSet(), runtime = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else null,
            check = { Build.VERSION.SDK_INT < 33 || granted(it, Manifest.permission.POST_NOTIFICATIONS) }),
        PermissionItem("battery", "不受电池优化限制", "Unrestricted battery", "锁屏和后台时也能执行任务、调用模型", "Run tasks and reach the model while the phone sleeps",
            ToolGroup.entries.toSet(),
            check = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            intent = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(it)) }),
        PermissionItem("files", "所有文件访问", "All files access", "查找、整理、清理你的文件", "Find, organise and clean up files",
            setOf(ToolGroup.Files),
            // Android 10 has no "All files access"; legacy storage + WRITE_EXTERNAL_STORAGE covers it there.
            runtime = if (Build.VERSION.SDK_INT < 30) listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else null,
            check = { hasAllFilesAccess(it) },
            intent = { if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(it)) else null }),
        PermissionItem("accessibility", "无障碍（屏幕操作）", "Accessibility (screen control)", "读取屏幕、替你点按和输入", "Read the screen, tap and type for you",
            setOf(ToolGroup.Screen, ToolGroup.Apps), restrictedSetting = true,
            check = { DshAccessibilityService.instance != null || a11yEnabled(it) },
            intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        PermissionItem("notif_access", "通知使用权", "Notification access", "读取通知、通知触发自动化、控制媒体播放", "Read notifications, notification triggers, media control",
            setOf(ToolGroup.Notifications, ToolGroup.Media, ToolGroup.Rules), restrictedSetting = true,
            check = { DshNotificationListener.instance != null || listenerEnabled(it) },
            intent = { c -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, DshNotificationListener::class.java).flattenToString())
                else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        PermissionItem("exact_alarm", "闹钟和提醒", "Alarms & reminders", "定时自动化准点执行", "Run timed automations on time",
            setOf(ToolGroup.Rules),
            check = { it.getSystemService(AlarmManager::class.java).canExact() },
            intent = { if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(it)) else null }),
        PermissionItem("dnd", "勿扰模式权限", "Do Not Disturb access", "切换静音和勿扰", "Switch silent mode and Do Not Disturb",
            setOf(ToolGroup.System),
            check = { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            intent = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),
        PermissionItem("write_settings", "修改系统设置", "Modify system settings", "调节亮度", "Adjust brightness",
            setOf(ToolGroup.System),
            check = { Settings.System.canWrite(it) },
            intent = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(it)) }),
        PermissionItem("location", "位置", "Location", "按 Wi‑Fi 名称或地点触发自动化", "Trigger automations by Wi‑Fi name or place",
            setOf(ToolGroup.Rules), runtime = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            check = { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) }),
        PermissionItem("bg_location", "后台位置", "Background location", "离开 App 时也能按地点触发", "Place triggers while DSH is closed",
            setOf(ToolGroup.Rules),
            check = { granted(it, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            intent = { appDetails(it) }),
        PermissionItem("bluetooth", "附近设备（蓝牙）", "Nearby devices", "按蓝牙设备名称触发自动化", "Trigger automations by Bluetooth device",
            setOf(ToolGroup.Rules), runtime = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else null,
            check = { Build.VERSION.SDK_INT < 31 || granted(it, Manifest.permission.BLUETOOTH_CONNECT) }),
        PermissionItem("comms", "联系人、短信、通话记录、日历", "Contacts, SMS, calls, calendar", "查联系人、读短信、记日程、发短信", "Look up contacts, read SMS, add events, send SMS",
            setOf(ToolGroup.Comms), runtime = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            restrictedSetting = true,
            check = { granted(it, Manifest.permission.READ_CONTACTS) && granted(it, Manifest.permission.READ_SMS) }),
        PermissionItem("overlay", "悬浮窗", "Display over other apps", "显示悬浮球", "Show the floating button",
            emptySet(),
            check = { Settings.canDrawOverlays(it) },
            intent = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(it)) }),
    )

    fun forGroups(groups: Set<ToolGroup>) = all.filter { it.groups.any(groups::contains) }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    private fun a11yEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.contains(ComponentName(c, DshAccessibilityService::class.java).flattenToString())
    }

    private fun listenerEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(c, DshNotificationListener::class.java).flattenToString())
    }
}
