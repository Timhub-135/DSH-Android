package io.github.timhub135.dsh.core.capability.tools

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.ActivityLauncher
import io.github.timhub135.dsh.core.capability.AlarmCheck
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.core.capability.ShellGate
import io.github.timhub135.dsh.core.capability.ShellUse
import io.github.timhub135.dsh.core.platform.AppVisibility
import io.github.timhub135.dsh.core.platform.Locator
import io.github.timhub135.dsh.core.platform.ShellUnavailableException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gate: ShellGate,
    private val launcher: ActivityLauncher,
) : ToolSet {
    private val shizuku get() = gate.shell

    /** When the system's next alarm clock rings (epoch ms), or null when none is reported. */
    private fun nextAlarm(): Long? = runCatching { context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()

    private fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", java.util.Locale.US))
    private val audio get() = context.getSystemService(AudioManager::class.java)

    private fun streamOf(name: String?) = when (name) {
        "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private suspend fun shell(cmd: String, what: String): ToolResult {
        if (!shizuku.available) throw ShellUnavailableException(gate.access.state.value)
        val r = shizuku.exec(cmd)
        return if (r.code == 0) ToolResult.text("Done via Shizuku: $what. ${r.output.trim()}".trim()) else ToolResult.error("failed", r.output.ifBlank { "exit ${r.code}" })
    }

    /** Shizuku attempt for a [ShellUse.Fallback] tool: null (→ fallback path) when it fails. */
    private suspend fun shellOrNull(cmd: String, what: String): ToolResult? = runCatching { shell(cmd, what) }.getOrNull()?.takeIf { it.ok }

    /**
     * The non-shell path for a toggle: a system panel/page the user finishes with one tap. Android
     * only lets DSH open it while DSH is visible (or may draw overlays); otherwise say so plainly.
     */
    private fun openForUser(intent: Intent, what: String): ToolResult {
        val reason = gate.access.state.value.let { if (it.ready) "the Shizuku command failed" else it.reason }
        if (!AppVisibility.isForeground && !Settings.canDrawOverlays(context))
            return ToolResult.error("needs_user", "Could not $what directly ($reason) and Android blocks opening system panels while DSH is in the background. Ask the user to do it.")
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.fold(
            { ToolResult.text("path=system_panel: $reason, so I opened the system page to $what. The user must tap the switch there; verify with device_status before relying on it.") },
            { ToolResult.error("failed", "Could not $what ($reason) and the system page failed to open: ${it.message}") })
    }

    private companion object {
        const val SCREEN_DESC = io.github.timhub135.dsh.core.capability.SchemaBuilder.SCREEN_DESC
    }

    override val tools = listOf(
        object : Tool("device_status", ToolGroup.System, Risk.read, false,
            "Battery, charging, network (Wi-Fi SSID if permitted), storage, ringer mode, volumes, Do-Not-Disturb, screen lock, time.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
                val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val net = when {
                    caps == null -> "offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi" + (wifiSsid()?.let { " ($it)" } ?: "")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val nm = context.getSystemService(NotificationManager::class.java)
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                return ToolResult.text(buildString {
                    appendLine("time: ${java.time.ZonedDateTime.now()}")
                    appendLine("battery: $level%${if (plugged) ", charging" else ""}")
                    appendLine("network: $net")
                    appendLine("storage: ${"%.1f".format(stat.availableBytes / 1e9)}GB free of ${"%.1f".format(stat.totalBytes / 1e9)}GB")
                    appendLine("ringer: ${when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }}")
                    appendLine("volume music ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, ring ${audio.getStreamVolume(AudioManager.STREAM_RING)}/${audio.getStreamMaxVolume(AudioManager.STREAM_RING)}")
                    appendLine("do-not-disturb: ${nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL}")
                    appendLine("locked: ${km.isKeyguardLocked}${if (km.isKeyguardLocked && !km.isDeviceSecure) " (swipe only)" else ""}, screen ${if (context.getSystemService(android.os.PowerManager::class.java).isInteractive) "on" else "off"}")
                    append("location enabled: ${context.getSystemService(LocationManager::class.java).isLocationEnabled} (location_get for coordinates)")
                })
            }

            @Suppress("DEPRECATION")
            private fun wifiSsid(): String? = runCatching {
                context.getSystemService(WifiManager::class.java).connectionInfo.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
            }.getOrNull()
        },
        object : Tool("sound_mode", ToolGroup.System, Risk.write, false, "Set ringer mode.", {
            string("mode", "Mode", required = true, enum = listOf("normal", "vibrate", "silent"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val nm = context.getSystemService(NotificationManager::class.java)
                val mode = args.reqStr("mode")
                // Entering or leaving silent toggles Do Not Disturb, which needs DND access since Android 7.
                val fromSilent = audio.ringerMode == AudioManager.RINGER_MODE_SILENT
                if ((mode == "silent" || fromSilent) && !nm.isNotificationPolicyAccessGranted) throw PermissionMissingException("Do Not Disturb access")
                try {
                    audio.ringerMode = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                } catch (e: SecurityException) { throw PermissionMissingException("Do Not Disturb access") }
                return ToolResult.text("Ringer mode: $mode")
            }
        },
        object : Tool("volume_set", ToolGroup.System, Risk.write, false, "Set a volume stream to a percentage (0-100).", {
            string("stream", "Stream", required = true, enum = listOf("music", "ring", "alarm", "notification", "call"))
            int("percent", "0-100", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val s = streamOf(args.str("stream"))
                val pct = (args.int("percent") ?: 0).coerceIn(0, 100)
                val max = audio.getStreamMaxVolume(s)
                try { audio.setStreamVolume(s, (max * pct + 50) / 100, 0) }
                catch (e: SecurityException) { throw PermissionMissingException("Do Not Disturb access") } // ring/notification 0 ↔ silent
                return ToolResult.text("Volume ${args.str("stream") ?: "music"} = $pct% (${audio.getStreamVolume(s)}/$max)")
            }
        },
        object : Tool("dnd_set", ToolGroup.System, Risk.write, false, "Turn Do Not Disturb on or off.", {
            bool("on", "true = enable DND", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) throw PermissionMissingException("Do Not Disturb access")
                nm.setInterruptionFilter(if (args.bool("on") == true) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                return ToolResult.text("Do Not Disturb ${if (args.bool("on") == true) "on" else "off"}")
            }
        },
        object : Tool("brightness_set", ToolGroup.System, Risk.write, false,
            "Set screen brightness 0-100 (perceptual, like the system slider), or auto.", {
            int("percent", "0-100"); bool("auto", "Use adaptive brightness")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (!Settings.System.canWrite(context)) throw PermissionMissingException("Modify system settings")
                val cr = context.contentResolver
                if (args.bool("auto") == true) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    return ToolResult.text("Adaptive brightness on")
                }
                val pct = (args.int("percent") ?: throw InvalidArgsException("percent or auto")).coerceIn(0, 100)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                // The system slider is perceptual (roughly gamma 2.2), so 50% on it is ~22% of the raw 0..255 range.
                val raw = (Math.pow(pct / 100.0, 2.2) * 255).toInt().coerceIn(1, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
                return ToolResult.text("Brightness $pct%")
            }
        },
        object : Tool("flashlight", ToolGroup.System, Risk.write, false, "Turn the flashlight on or off.", {
            bool("on", "", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val cm = context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return ToolResult.error("unsupported", "This phone has no camera flash.")
                try { cm.setTorchMode(id, args.bool("on") == true) }
                catch (e: android.hardware.camera2.CameraAccessException) {
                    return ToolResult.error("failed", "The flashlight is unavailable right now (the camera is in use by another app). Try again after closing the camera.")
                }
                return ToolResult.text("Flashlight ${if (args.bool("on") == true) "on" else "off"}")
            }
        },
        // needsUnlock = true: the clock app handles the request in an activity, which Android only starts
        // reliably on an unlocked screen (and some clocks show a confirmation page).
        object : Tool("alarm_set", ToolGroup.System, Risk.write, true,
            "Create an alarm in the clock app (optionally repeating), or start a countdown timer. " +
                "For an alarm the result says whether the system now reports it as the next alarm (verified / earlier_alarm_first / not_seen / unknown); " +
                "unless verified, check the alarm list (alarm_show) before telling the user it is set. not_supported: set it in the clock app on screen.", {
            int("hour", "0-23"); int("minute", "0-59"); string("label", "Label")
            raw("days", buildJsonObject {
                put("type", "array"); put("description", "Repeat on these weekdays (1=Mon … 7=Sun); omit for a one-time alarm")
                put("items", buildJsonObject { put("type", "integer") })
            })
            int("timerSeconds", "If set, start a countdown timer instead (1-86400)")
            string("screen", SCREEN_DESC, enum = listOf("agent", "main"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val timer = args.int("timerSeconds")
                var days: List<Int> = emptyList()
                val i = if (timer != null) {
                    if (timer !in 1..86_400) throw InvalidArgsException("timerSeconds must be 1..86400")
                    Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, timer)
                } else {
                    val hour = args.int("hour") ?: throw InvalidArgsException("missing 'hour' (0-23)")
                    val minute = args.int("minute") ?: 0
                    if (hour !in 0..23 || minute !in 0..59) throw InvalidArgsException("hour must be 0-23 and minute 0-59")
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).also { a ->
                        // AlarmClock uses Calendar days (Sun=1 … Sat=7).
                        days = (args["days"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }.orEmpty()
                        if (days.isNotEmpty()) {
                            if (days.any { it !in 1..7 }) throw InvalidArgsException("days must be 1..7 (Mon=1)")
                            a.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { it % 7 + 1 }))
                        }
                    }
                }
                args.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val what = if (timer != null) "a ${timer}s timer" else "an alarm at ${args.int("hour")}:${"%02d".format(args.int("minute") ?: 0)}"
                if (!launcher.handles(i)) return ToolResult.error("not_supported",
                    "No app on this phone takes ${if (timer != null) "timers" else "alarms"} through the standard Android request. Open the clock app (app_launch) and set it on screen.")
                val before = if (timer == null) nextAlarm() else null
                // Not muted on the agent screen: the clock's sound is the point.
                val sent = try { launcher.open(call, args.str("screen"), "Sent $what to the clock app", i, expect = null, create = false, mute = false) }
                    catch (e: SecurityException) { return ToolResult.error("failed", "The clock app refused the request: ${e.message}") }
                if (!sent.ok) return sent
                if (timer != null) return sent.appendText("Some clock apps show a confirmation page instead of starting silently; check the screen if it matters.")
                val expected = AlarmCheck.nextRing(java.time.ZonedDateTime.now(), args.int("hour")!!, args.int("minute") ?: 0, days).toInstant().toEpochMilli()
                // The clock app saves asynchronously: give it a few seconds to publish the new next alarm.
                var after = nextAlarm()
                var verdict = AlarmCheck.verdict(before, after, expected)
                var waited = 0L
                while (verdict != AlarmCheck.Verdict.Verified && waited < 4_000) {
                    kotlinx.coroutines.delay(400); waited += 400
                    after = nextAlarm(); verdict = AlarmCheck.verdict(before, after, expected)
                }
                val next = after?.let(::clock) ?: "none"
                return sent.appendText("verification: ${verdict.id} — " + when (verdict) {
                    AlarmCheck.Verdict.Verified -> "the system's next alarm is now ${clock(expected)}, the one just set."
                    AlarmCheck.Verdict.EarlierAlarmFirst -> "another alarm rings first ($next), so the new one (due ${clock(expected)}) cannot be confirmed this way. " +
                        "Check the alarm list (alarm_show) if it matters."
                    AlarmCheck.Verdict.NotSeen -> "the system's next alarm is $next, not the new one (due ${clock(expected)}): it was probably not saved " +
                        "(some clock apps show a confirmation page instead). Check the screen and the alarm list (alarm_show) before telling the user it is set."
                    AlarmCheck.Verdict.Unknown -> "the system reports no alarms at all (this clock app may not report them). " +
                        "Check the alarm list (alarm_show) before telling the user it is set."
                })
            }
        },
        object : Tool("alarm_show", ToolGroup.System, Risk.write, true,
            "Open the clock app's alarm list (to check or change alarms on screen with the screen_* tools), and report the system's next alarm. " +
                "Like app_launch it opens on your background screen when there is one.", {
            string("screen", SCREEN_DESC, enum = listOf("agent", "main"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val i = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                val next = "System's next alarm: ${nextAlarm()?.let(::clock) ?: "none reported"}."
                if (!launcher.handles(i)) return ToolResult.error("not_supported",
                    "No app on this phone opens its alarm list through the standard Android request; open the clock app with app_launch instead. $next")
                val r = launcher.open(call, args.str("screen"), "Opened the alarm list of", i, launcher.handler(i), create = true, mute = false)
                return if (r.ok) r.appendText(next) else r
            }
        },
        object : Tool("wifi_toggle", ToolGroup.System, Risk.write, false,
            "Turn Wi-Fi on/off. Directly through Shizuku when available; otherwise opens the system Wi-Fi panel for the user to tap.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "open the system Wi-Fi panel and the user taps the switch") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                if (runCatching { context.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull() == on)
                    return ToolResult.text("Wi-Fi is already ${if (on) "on" else "off"}; nothing to do.")
                if (gate.acquire(call, name, optional = true)) shellOrNull("svc wifi ${if (on) "enable" else "disable"}", "switch Wi-Fi ${if (on) "on" else "off"}")?.let { return it }
                return openForUser(Intent(Settings.Panel.ACTION_WIFI), "switch Wi-Fi ${if (on) "on" else "off"}")
            }
        },
        object : Tool("bluetooth_toggle", ToolGroup.System, Risk.write, false,
            "Turn Bluetooth on/off. Directly through Shizuku when available; otherwise shows the system Bluetooth prompt/page for the user to confirm.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "show the system Bluetooth prompt or page and the user confirms") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                val adapter = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter }.getOrNull()
                if (adapter == null) return ToolResult.error("unsupported", "This phone has no Bluetooth adapter.")
                if (runCatching { adapter.isEnabled }.getOrNull() == on) return ToolResult.text("Bluetooth is already ${if (on) "on" else "off"}; nothing to do.")
                if (gate.acquire(call, name, optional = true)) shellOrNull("cmd bluetooth_manager ${if (on) "enable" else "disable"}", "switch Bluetooth ${if (on) "on" else "off"}")?.let { return it }
                val canAsk = on && (android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                return openForUser(if (canAsk) Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                    "switch Bluetooth ${if (on) "on" else "off"}")
            }
        },
        object : Tool("mobile_data_toggle", ToolGroup.System, Risk.write, false,
            "Turn mobile data on/off. Directly through Shizuku when available; otherwise opens the system Internet panel for the user to tap.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "open the system Internet panel and the user taps the switch") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                if (gate.acquire(call, name, optional = true)) shellOrNull("svc data ${if (on) "enable" else "disable"}", "switch mobile data ${if (on) "on" else "off"}")?.let { return it }
                return openForUser(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), "switch mobile data ${if (on) "on" else "off"}")
            }
        },
        object : Tool("setting_get", ToolGroup.System, Risk.read, false,
            "Read Android settings: one key, or (key omitted, needs Shizuku) every key in a namespace matching filter. Common keys: " +
                "global airplane_mode_on, mobile_data, wifi_on, bluetooth_on, auto_time; system screen_off_timeout (ms), screen_brightness (0-255), " +
                "screen_brightness_mode, accelerometer_rotation (auto-rotate), font_scale; secure location_mode, default_input_method, ui_night_mode.",
            {
                string("namespace", "", required = true, enum = listOf("system", "secure", "global"))
                string("key", "Setting key (omit to list)"); string("filter", "When listing: only keys containing this")
            }, ShellUse.Fallback, "read public keys directly (listing and hidden keys need Shizuku)") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val ns = args.reqStr("namespace")
                val key = args.str("key")?.filter { it.isLetterOrDigit() || it in "_." }
                if (key == null) {
                    val r = runCatching { shell("settings list $ns", "list settings") }.getOrNull()
                        ?: return ToolResult.error("shizuku_unavailable", "Listing settings needs Shizuku (${gate.access.state.value.reason}); read a specific key instead.")
                    if (!r.ok) return r
                    val f = args.str("filter")?.lowercase()
                    val lines = (r.content.firstOrNull() as? io.github.timhub135.dsh.core.capability.Content.Text)?.text.orEmpty()
                        .removePrefix("Done via Shizuku: list settings.").lines().map { it.trim() }.filter { it.isNotEmpty() && (f == null || it.lowercase().contains(f)) }
                    return ToolResult.text(lines.take(300).joinToString("\n").ifEmpty { "No matching keys." } + if (lines.size > 300) "\n…(${lines.size - 300} more; use filter)" else "")
                }
                val cr = context.contentResolver
                val direct = runCatching {
                    when (ns) { "system" -> Settings.System.getString(cr, key); "secure" -> Settings.Secure.getString(cr, key); else -> Settings.Global.getString(cr, key) }
                }
                direct.getOrNull()?.let { return ToolResult.text("$ns $key = $it") }
                if (shizuku.available) {
                    val r = shizuku.exec("settings get $ns $key")
                    if (r.code == 0) return ToolResult.text("$ns $key = ${r.output.trim()} (via Shizuku)")
                }
                return if (direct.isFailure) ToolResult.error("not_authorized", "Android does not let apps read $ns/$key (${direct.exceptionOrNull()?.javaClass?.simpleName}); it needs Shizuku.")
                else ToolResult.text("$ns $key is not set (null).")
            }
        },
        object : Tool("location_get", ToolGroup.System, Risk.sensitive, false,
            "The phone's current location: latitude/longitude, accuracy, age, and the street address when the system geocoder has one. " +
                "Use it for geofence rules (\"remind me when I get home\") and \"where am I\".", {
                bool("precise", "Ask for a fresh GPS-quality fix (slower, default true)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) throw PermissionMissingException("Location")
                val lm = context.getSystemService(LocationManager::class.java)
                if (!lm.isLocationEnabled) return ToolResult.error("location_off", "Location is turned off on the phone. Ask the user to turn it on (quick settings).")
                val loc = Locator.current(context, precise = args.bool("precise") != false && fine)
                    ?: return ToolResult.error("location_unavailable", "No location fix within 20s. " +
                        (if (!AppVisibility.isForeground && context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            "DSH is in the background without 'Allow all the time' location access; ask the user to grant it or open DSH and retry." else "The phone may be indoors; try again or use precise=false."))
                val ageS = ((System.currentTimeMillis() - loc.time) / 1000).coerceAtLeast(0)
                val address = Locator.address(context, loc.latitude, loc.longitude)
                return ToolResult.text(buildString {
                    append("lat=${"%.6f".format(loc.latitude)} lng=${"%.6f".format(loc.longitude)} accuracy=${loc.accuracy.toInt()}m age=${ageS}s provider=${loc.provider}")
                    if (!fine) append(" (approximate: only coarse location is granted)")
                    address?.let { append("\naddress: $it") }
                })
            }
        },
        object : Tool("setting_put", ToolGroup.System, Risk.dangerous, false,
            "Write an Android setting via `settings put <namespace> <key> <value>` (needs Shizuku). For things no dedicated tool covers.", {
                string("namespace", "", required = true, enum = listOf("system", "secure", "global"))
                string("key", "", required = true); string("value", "", required = true)
            }, ShellUse.Required) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                // An empty value is legitimate (e.g. clearing enabled_accessibility_services, #8).
                val v = (args.str("value") ?: throw InvalidArgsException("missing 'value'")).replace("'", "")
                return shell("settings put ${args.reqStr("namespace")} ${args.reqStr("key").filter { it.isLetterOrDigit() || it in "_." }} '$v'", "change setting")
            }
        },
        object : Tool(AdbShell.NAME, ToolGroup.System, Risk.write, false, AdbShell.DESCRIPTION, {
                string("command", "The command line, e.g. \"dumpsys battery\", \"pm list packages -3\", \"ls /sdcard/Android/data\"", required = true)
                int("timeout_s", "Seconds before the command is stopped (default ${AdbShell.DEFAULT_TIMEOUT_S}, max ${AdbShell.MAX_TIMEOUT_S})")
            }, ShellUse.Required) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                // The hardline floor lives in the engine (plugin-guard); a direct rule step would skip it.
                if (!call.viaEngine) return ToolResult.error("not_authorized", AdbShell.NOT_FROM_ENGINE)
                val cmd = args.reqStr("command")
                if (!shizuku.available) throw ShellUnavailableException(gate.access.state.value)
                val timeoutS = AdbShell.timeoutS(args.int("timeout_s"))
                val r = shizuku.exec(cmd, timeoutS * 1000L)
                return ToolResult.text(AdbShell.format(r.code, r.output, timeoutS))
            }
        },
    )
}

/** `adb_shell`: a command as the shell user through Shizuku (= `adb shell <command>`), for what an app's own shell cannot do. */
internal object AdbShell {
    const val NAME = "adb_shell"
    const val DEFAULT_TIMEOUT_S = 30
    const val MAX_TIMEOUT_S = 240
    /** ShellUserService.exec keeps at most this many characters of output. */
    const val OUTPUT_CAP = 64_000
    const val TIMED_OUT = 124

    const val DESCRIPTION = "Run a command as the Android shell user through Shizuku: the same as `adb shell <command>` from a computer, on this phone. " +
        "Only for what the bash tool cannot do as an app: pm (list, enable/disable, grant), am (start, force-stop), cmd, dumpsys, settings, wm, input, " +
        "appops, logcat -d, and files under Android/data or Android/obb. Prefer a dedicated tool when one fits (file_*, setting_put, wifi_toggle, app_*). " +
        "Runs /system/bin/sh from \"/\" without root and without a terminal: no prompts, and a command that never ends (logcat without -d, top without -n) " +
        "is stopped at the timeout. Returns the exit code and stdout+stderr (at most 64 000 characters). The same hard blocks as bash apply."

    const val NOT_FROM_ENGINE = "adb_shell runs only from a conversation or an automation's agent run (where DSH's command safety checks see it), " +
        "not as a direct automation step. Make the automation an agent action instead."

    fun timeoutS(requested: Int?): Int = (requested ?: DEFAULT_TIMEOUT_S).coerceIn(1, MAX_TIMEOUT_S)

    fun format(code: Int, output: String, timeoutS: Int): String = buildString {
        append("exit ").append(code)
        if (code == TIMED_OUT) append(" (stopped after ${timeoutS}s, or the command itself exited 124)")
        val out = output.trimEnd()
        append('\n').append(out.ifEmpty { "(no output)" })
        if (output.length >= OUTPUT_CAP) append("\n…(output cut at $OUTPUT_CAP characters; narrow the command, e.g. with grep or head)")
    }
}
