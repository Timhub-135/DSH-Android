package io.github.timhub135.dsh.core.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.capability.CapabilityHub
import io.github.timhub135.dsh.core.data.RuleDao
import io.github.timhub135.dsh.core.data.RuleEntity
import io.github.timhub135.dsh.core.data.RuleRunEntity
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.model.AUTO_SESSION_PREFIX
import io.github.timhub135.dsh.core.model.Condition
import io.github.timhub135.dsh.core.model.RuleAction
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.Trigger
import io.github.timhub135.dsh.core.platform.Notifications
import io.github.timhub135.dsh.core.session.SessionStore
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import io.github.timhub135.dsh.core.data.SettingsRepository
import io.github.timhub135.dsh.service.DshAccessibilityService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automation engine: keeps OS registrations in sync with the enabled rules, matches incoming
 * [DeviceEvent]s, and runs the rule's action — fixed tool steps directly, or an unattended agent session.
 */
@Singleton
class TriggerHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rules: RuleDao,
    private val capabilities: CapabilityHub,
    private val sessions: dagger.Lazy<SessionStore>,
    private val settings: SettingsRepository,
) {
    // A failure in one rule must never take the process down (C1): log it and carry on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "automation task failed", e) })
    private val lastFired = ConcurrentHashMap<Long, Long>()
    private val syncLock = Mutex()
    @Volatile private var cache: List<Pair<RuleEntity, RuleSpec>> = emptyList()

    fun decode(e: RuleEntity): RuleSpec? = runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), e.specJson) }.getOrNull()

    /** Validates and normalizes a spec (throws [RuleSpecException] with model-readable problems). */
    fun validate(spec: RuleSpec): RuleSpec = validateRule(spec, capabilities.allTools.keys)

    suspend fun create(spec: RuleSpec): Long {
        val normalized = validate(spec)
        val id = rules.insert(RuleEntity(specJson = RpcJson.encodeToString(RuleSpec.serializer(), normalized)))
        sync(); return id
    }

    /** Replaces a rule's spec (validated and normalized like [create]); the enabled state is kept unless given. */
    suspend fun update(id: Long, spec: RuleSpec?, enabled: Boolean?): RuleSpec? {
        val e = rules.get(id) ?: return null
        val normalized = spec?.let(::validate)
        rules.upsert(e.copy(
            specJson = normalized?.let { RpcJson.encodeToString(RuleSpec.serializer(), it) } ?: e.specJson,
            enabled = enabled ?: e.enabled,
        ))
        sync()
        return normalized ?: decode(e)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) { rules.get(id)?.let { rules.upsert(it.copy(enabled = enabled)) }; sync() }
    suspend fun delete(id: Long) { runCatching { cancelAlarm(id); cancelGeofence(id) }; rules.delete(id); sync() }

    /**
     * What the rule needs but does not have right now (so it would silently never fire). Shown to the
     * model by rule_create/rule_update and usable by the UI.
     */
    fun missingPrerequisites(spec: RuleSpec): List<String> = buildList {
        fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        when (val t = spec.trigger) {
            is Trigger.Geofence -> {
                if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("precise location permission")
                if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add("location 'Allow all the time' (background location)")
            }
            is Trigger.Bluetooth -> if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) add("Nearby devices (Bluetooth) permission")
            is Trigger.Wifi -> if (t.ssid != null && !(granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
                add("location 'Allow all the time' (Android hides the Wi-Fi name otherwise)")
            is Trigger.Notification -> if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) add("Notification access")
            is Trigger.AppOpened -> if (DshAccessibilityService.instance == null) add("Accessibility service (DSH screen control)")
            else -> {}
        }
    }

    /** Non-blocking caveats (e.g. inexact alarms) to mention when a rule is created. */
    fun caveats(spec: RuleSpec): List<String> = buildList {
        if (spec.trigger is Trigger.Time && !context.getSystemService(AlarmManager::class.java).canExact())
            add("exact alarms are not allowed for DSH, so this time rule may fire several minutes late (grant 'Alarms & reminders' to fix)")
        if (spec.trigger is Trigger.Geofence && !playServicesGeofencing)
            add("no Google Play services: the geofence uses the platform location service and may fire minutes late")
    }

    /** Re-registers OS hooks for all enabled rules. Call at boot and after any change. Never throws. */
    suspend fun sync() = syncLock.withLock {
        val all = runCatching { rules.all() }.getOrElse { Log.e(TAG, "cannot read rules", it); return@withLock }
        cache = all.filter { it.enabled }.mapNotNull { e -> decode(e)?.let { e to it } }
        all.forEach { e -> runCatching { cancelAlarm(e.id) } }
        cache.forEach { (e, s) ->
            val t = s.trigger
            if (t is Trigger.Time) runCatching { scheduleAlarm(e, t) }.onFailure { Log.w(TAG, "rule ${e.id}: cannot schedule", it) }
        }
        runCatching { syncGeofences(all.map { it.id }, cache.mapNotNull { (e, s) -> (s.trigger as? Trigger.Geofence)?.let { e.id to it } }) }
            .onFailure { Log.w(TAG, "geofence sync failed", it) }
        val needsMonitor = cache.any { it.second.trigger is Trigger.Wifi || it.second.trigger is Trigger.Bluetooth ||
            it.second.trigger is Trigger.Power || it.second.trigger is Trigger.BatteryLow || it.second.trigger is Trigger.Headset || it.second.trigger is Trigger.Screen }
        runCatching { RuleMonitorService.setRunning(context, needsMonitor) }
    }

    /**
     * Matches [event] against the enabled rules. Time alarms are rescheduled first, then each matching
     * rule fires in its own job, so a slow rule (waiting for an unlock) neither delays the next alarm
     * nor blocks other rules. The returned job completes once dispatch is done (not the runs).
     */
    fun onEvent(event: DeviceEvent): Job = scope.launch {
        if (cache.isEmpty()) cache = rules.all().filter { it.enabled }.mapNotNull { e -> decode(e)?.let { e to it } }
        if (event is DeviceEvent.Time) rules.get(event.ruleId)?.let { e ->
            (decode(e)?.trigger as? Trigger.Time)?.let { t -> if (e.enabled) runCatching { scheduleAlarm(e.copy(lastFiredAt = System.currentTimeMillis()), t) } }
        }
        cache.filter { (e, s) -> runCatching { matches(e.id, s.trigger, event) && s.conditions.all(::holds) }
            .onFailure { Log.w(TAG, "rule ${e.id}: cannot evaluate", it) }.getOrDefault(false) }
            .forEach { (e, s) -> scope.launch { fire(e, s, event) } }
    }

    private fun matches(id: Long, t: Trigger, ev: DeviceEvent): Boolean = when (t) {
        is Trigger.Time -> ev is DeviceEvent.Time && ev.ruleId == id
        is Trigger.Geofence -> ev is DeviceEvent.Geofence && ev.ruleId == id && ev.entering == t.enter
        is Trigger.Wifi -> ev is DeviceEvent.Wifi && ev.connected == t.connected && (t.ssid == null || t.ssid.equals(ev.ssid, true))
        is Trigger.Bluetooth -> ev is DeviceEvent.Bluetooth && ev.connected == t.connected &&
            (t.deviceName == null || ev.deviceName?.contains(t.deviceName, true) == true)
        is Trigger.Power -> ev is DeviceEvent.Power && ev.connected == t.connected
        // Fires when the level crosses down through the threshold while discharging (re-arms above it).
        is Trigger.BatteryLow -> ev is DeviceEvent.Battery && !ev.charging && ev.previous != null && ev.previous > t.percent && ev.percent <= t.percent
        is Trigger.Headset -> ev is DeviceEvent.Headset && ev.plugged == t.plugged
        is Trigger.Screen -> ev is DeviceEvent.Screen && ev.event == t.event
        is Trigger.Notification -> ev is DeviceEvent.Notification && (t.packageName == null || t.packageName == ev.packageName) &&
            (t.keyword == null || "${ev.title} ${ev.text}".contains(t.keyword, true))
        is Trigger.AppOpened -> ev is DeviceEvent.AppOpened && ev.packageName == t.packageName
    }

    /** Unparseable stored values (rules from before validation) make the condition false, never a crash. */
    private fun holds(c: Condition): Boolean = when (c) {
        is Condition.TimeRange -> {
            val now = LocalTime.now(); val from = RuleTime.parse(c.from); val to = RuleTime.parse(c.to)
            if (from == null || to == null) false else if (from <= to) now in from..to else now >= from || now <= to
        }
        is Condition.Days -> LocalDate.now().dayOfWeek.value in c.days
        is Condition.Battery -> battery().let { (pct, _) -> (c.min == null || pct >= c.min) && (c.max == null || pct <= c.max) }
        is Condition.Charging -> battery().second == c.charging
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        return pct to ((i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private suspend fun fire(e: RuleEntity, s: RuleSpec, event: DeviceEvent) {
        val now = System.currentTimeMillis()
        // Claim the debounce slot atomically before suspending: a notification burst (message + group
        // summary, updates) must not run the same rule twice.
        val claimed = lastFired.compute(e.id) { _, prev -> if (prev == null || now - prev >= DEBOUNCE_MS) now else prev }
        if (claimed != now) return
        if (rules.runsSince(e.id, now - 86_400_000L) >= DAILY_CAP) return
        rules.get(e.id)?.let { rules.upsert(it.copy(lastFiredAt = now)) }
        val sessionId = "$AUTO_SESSION_PREFIX${e.id}_${UUID.randomUUID()}"
        Log.i(TAG, "rule ${e.id} '${s.name}' fired by $event")
        val outcome = try {
            when (val a = s.action) {
                is RuleAction.Steps -> {
                    var failure: String? = null
                    for (step in a.steps) {
                        val r = capabilities.invoke(buildJsonObject {
                            put("tool", step.tool); put("sessionId", sessionId); put("callId", UUID.randomUUID().toString()); put("args", step.args)
                        })
                        if (!r.ok) { failure = "${step.tool}: ${r.errorMessage}"; break }
                    }
                    failure?.let { "failed: $it" } ?: "ok"
                }
                is RuleAction.Agent -> {
                    sessions.get().prompt(sessionId, "[Automation \"${s.name}\" triggered by ${describe(s, event)}]\n${a.prompt}", ruleId = e.id)
                    "started"
                }
            }
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "rule ${e.id} failed", ex); "error: ${ex.message}"
        }
        rules.insertRun(RuleRunEntity(ruleId = e.id, firedAt = now, sessionId = sessionId.takeIf { s.action is RuleAction.Agent }, outcome = outcome))
        if (outcome != "started") Notifications.post(context, Notifications.CHANNEL_TASKS, e.id.toInt() + 1000,
            context.getString(R.string.notif_rule_ran, s.name), outcome, null)
    }

    private suspend fun describe(s: RuleSpec, e: DeviceEvent) = when (e) {
        is DeviceEvent.Notification -> {
            // A rule bound to one app is the user's consent for that app; an "any app" rule only passes
            // the text of apps the user has not blocked (Settings → Notifications).
            val readable = (s.trigger as? Trigger.Notification)?.packageName != null || e.packageName !in settings.current().notificationBlocked
            if (readable) "notification from ${e.packageName}: ${e.title} — ${e.text.take(500)}"
            else "notification from ${e.packageName} (content hidden: the user blocked reading this app's notifications)"
        }
        is DeviceEvent.Wifi -> "Wi-Fi ${if (e.connected) "connected" else "disconnected"}${e.ssid?.let { " ($it)" } ?: ""}"
        is DeviceEvent.Battery -> "battery at ${e.percent}%"
        else -> e.toString()
    }

    // ---- time --------------------------------------------------------------------------------

    private fun alarmIntent(id: Long) = PendingIntent.getBroadcast(context, id.toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_TIME).putExtra("ruleId", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun cancelAlarm(id: Long) = context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(id))

    private fun scheduleAlarm(e: RuleEntity, t: Trigger.Time) {
        val next = nextFire(e, t) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(e.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(e.id))
    }

    private fun nextFire(e: RuleEntity, t: Trigger.Time): Long? {
        val now = System.currentTimeMillis()
        t.onceAtEpochMs?.let { return it.takeIf { it > now } }
        t.everyMinutes?.let { m ->
            // Anchored to the last run (or creation), so re-syncing on every app start or edit does not
            // keep pushing the next run into the future. A missed run fires a minute from now.
            val period = m.coerceAtLeast(15) * 60_000L
            val anchor = e.lastFiredAt ?: e.createdAt
            val due = anchor + period
            return if (due > now) due else now + 60_000L
        }
        val at = RuleTime.parse(t.at) ?: return null
        var dt = LocalDateTime.of(LocalDate.now(), at)
        repeat(8) {
            if (dt.isAfter(LocalDateTime.now()) && (t.days.isEmpty() || dt.dayOfWeek.value in t.days))
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dt = dt.plusDays(1)
        }
        return null
    }

    // ---- geofence ------------------------------------------------------------------------------
    // With Play services: GeofencingClient. Its transition broadcasts are on Android's list of
    // background foreground-service-start exemptions, so an agent rule can start the engine, and
    // fused location keeps it cheap. Without Play services (many CN ROMs): the platform
    // LocationManager.addProximityAlert, which is NOT exempt and only sees background location a few
    // times an hour — such rules may fire minutes late, and if the FGS start is refused the run
    // degrades to the 点此执行 notification (DeferredRuns). The rule UI/Doctor say so.

    val playServicesGeofencing: Boolean by lazy {
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)
    }

    private fun geofenceIntent(id: Long) = PendingIntent.getBroadcast(context, (id + 500_000).toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE).putExtra("ruleId", id),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One shared PendingIntent for all Play-services geofences (request id = rule id). */
    private val gmsGeofenceIntent by lazy {
        PendingIntent.getBroadcast(context, 499_999, Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE_GMS),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Re-registers geofences. Play services: remove all of ours, and only add once the removal has
     * completed (removal and add are async; unchained they could end with the fence removed).
     */
    private fun syncGeofences(allIds: List<Long>, fences: List<Pair<Long, Trigger.Geofence>>) {
        val canLocate = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (playServicesGeofencing) {
            val client = LocationServices.getGeofencingClient(context)
            client.removeGeofences(gmsGeofenceIntent).addOnCompleteListener {
                if (!canLocate || fences.isEmpty()) return@addOnCompleteListener
                val request = GeofencingRequest.Builder().setInitialTrigger(0).apply {
                    fences.forEach { (id, g) ->
                        addGeofence(Geofence.Builder().setRequestId(id.toString()).setCircularRegion(g.lat, g.lng, g.radiusM)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build())
                    }
                }.build()
                @Suppress("MissingPermission")
                runCatching { client.addGeofences(request, gmsGeofenceIntent).addOnFailureListener { Log.w(TAG, "geofences not registered", it) } }
                    .onFailure { Log.w(TAG, "geofences not registered", it) }
            }
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        allIds.forEach { id -> runCatching { lm.removeProximityAlert(geofenceIntent(id)) } }
        if (!canLocate) return
        @Suppress("MissingPermission")
        fences.forEach { (id, g) -> runCatching { lm.addProximityAlert(g.lat, g.lng, g.radiusM, -1, geofenceIntent(id)) } }
    }

    private fun cancelGeofence(id: Long) = runCatching {
        if (playServicesGeofencing) LocationServices.getGeofencingClient(context).removeGeofences(listOf(id.toString()))
        else context.getSystemService(LocationManager::class.java).removeProximityAlert(geofenceIntent(id))
    }

    private companion object {
        const val TAG = "TriggerHub"
        const val DEBOUNCE_MS = 60_000L
        const val DAILY_CAP = 50
    }
}

/** Exact alarms need no grant before Android 12, where `canScheduleExactAlarms()` does not exist (#8). */
fun AlarmManager.canExact(): Boolean = android.os.Build.VERSION.SDK_INT < 31 || canScheduleExactAlarms()
