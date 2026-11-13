package io.github.timhub135.dsh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** "When [trigger] (and all [conditions]) then [action]". */
@Serializable
data class RuleSpec(
    val name: String,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val action: RuleAction,
    /** Tools the unattended run may use; anything else is refused. */
    val allowedTools: List<String> = emptyList(),
)

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("time")
    data class Time(val at: String? = null, val days: List<Int> = emptyList(), val everyMinutes: Int? = null, val onceAtEpochMs: Long? = null) : Trigger
    @Serializable @SerialName("wifi")
    data class Wifi(val connected: Boolean, val ssid: String? = null) : Trigger
    @Serializable @SerialName("bluetooth")
    data class Bluetooth(val connected: Boolean, val deviceName: String? = null) : Trigger
    @Serializable @SerialName("power")
    data class Power(val connected: Boolean) : Trigger
    @Serializable @SerialName("battery_low")
    data class BatteryLow(val percent: Int) : Trigger
    @Serializable @SerialName("headset")
    data class Headset(val plugged: Boolean) : Trigger
    @Serializable @SerialName("screen")
    data class Screen(val event: String /* on | off | unlock */) : Trigger
    @Serializable @SerialName("notification")
    data class Notification(val packageName: String? = null, val keyword: String? = null) : Trigger
    @Serializable @SerialName("app_opened")
    data class AppOpened(val packageName: String) : Trigger
    @Serializable @SerialName("geofence")
    data class Geofence(val lat: Double, val lng: Double, val radiusM: Float = 200f, val enter: Boolean = true) : Trigger
}

@Serializable
sealed interface Condition {
    @Serializable @SerialName("time_range")
    data class TimeRange(val from: String, val to: String) : Condition
    @Serializable @SerialName("days")
    data class Days(val days: List<Int>) : Condition
    @Serializable @SerialName("battery")
    data class Battery(val min: Int? = null, val max: Int? = null) : Condition
    @Serializable @SerialName("charging")
    data class Charging(val charging: Boolean) : Condition
}

@Serializable
sealed interface RuleAction {
    /** Hand the event to the agent with this instruction. */
    @Serializable @SerialName("agent")
    data class Agent(val prompt: String) : RuleAction
    /** Run fixed tool calls directly — no model, no tokens. */
    @Serializable @SerialName("steps")
    data class Steps(val steps: List<Step>) : RuleAction
}

@Serializable
data class Step(val tool: String, val args: JsonObject = JsonObject(emptyMap()))
