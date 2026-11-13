package io.github.timhub135.dsh.core.trigger

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Raw device events, as seen by receivers/services. */
sealed interface DeviceEvent {
    data class Time(val ruleId: Long) : DeviceEvent
    data class Wifi(val connected: Boolean, val ssid: String?) : DeviceEvent
    data class Bluetooth(val connected: Boolean, val deviceName: String?) : DeviceEvent
    data class Power(val connected: Boolean) : DeviceEvent
    /** [previous] is the last level the monitor saw (null right after it started). */
    data class Battery(val percent: Int, val previous: Int?, val charging: Boolean) : DeviceEvent
    data class Headset(val plugged: Boolean) : DeviceEvent
    data class Screen(val event: String) : DeviceEvent
    data class Notification(val packageName: String, val title: String, val text: String) : DeviceEvent
    data class AppOpened(val packageName: String) : DeviceEvent
    data class Geofence(val ruleId: Long, val entering: Boolean) : DeviceEvent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TriggerEntryPoint { fun triggerHub(): TriggerHub }

/** Static funnel from Android components (which are not Hilt-injected everywhere) into [TriggerHub]. */
object TriggerEvents {
    @Volatile private var hub: TriggerHub? = null

    fun init(context: Context) { hub = EntryPointAccessors.fromApplication(context, TriggerEntryPoint::class.java).triggerHub() }

    fun emit(event: DeviceEvent) { runCatching { hub?.onEvent(event) } }
    fun appOpened(pkg: String) = emit(DeviceEvent.AppOpened(pkg))
    fun notificationPosted(pkg: String, title: String, text: String) = emit(DeviceEvent.Notification(pkg, title, text))
}
