package io.github.timhub135.dsh.feature.settings

import android.content.Context
import android.os.PowerManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.timhub135.dsh.core.data.RuleDao
import io.github.timhub135.dsh.core.engine.EngineDiagnostics
import io.github.timhub135.dsh.core.engine.EngineManager
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.Trigger
import io.github.timhub135.dsh.core.trigger.RuleMonitorService
import io.github.timhub135.dsh.core.trigger.TriggerHub
import io.github.timhub135.dsh.service.DshInputMethodService

/**
 * Doctor rows for background reliability and crash evidence (review §6/§8/§12): the battery
 * exemption as a hard dependency of automations, the rule monitor, geofence backend, the engine
 * crash breaker and the last recorded `:engine` exit.
 */
object ReliabilityDoctor {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun rules(): RuleDao
        fun triggers(): TriggerHub
        fun engine(): EngineManager
    }

    suspend fun checks(context: Context, zh: Boolean): List<DoctorCheck> {
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        EngineDiagnostics.collectExitInfo(context)
        val specs = deps.rules().all().filter { it.enabled }.mapNotNull { runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }.getOrNull() }
        val out = mutableListOf<DoctorCheck>()
        if (specs.isNotEmpty()) {
            val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            val battery = PermissionCatalog.byId("battery")
            out += DoctorCheck(if (zh) "自动化：不受电池优化限制（必需）" else "Automations: unrestricted battery (required)", exempt,
                when {
                    exempt -> if (zh) "后台触发可以直接启动引擎" else "Triggers can start the engine in the background"
                    zh -> "未豁免时 Android 会拒绝在后台启动引擎，自动化只能降级为“点此执行”通知"
                    else -> "Without it Android refuses to start the engine from the background; automations degrade to a tap-to-run notification"
                }, battery?.intent?.invoke(context))
            val needsMonitor = specs.any { it.trigger is Trigger.Wifi || it.trigger is Trigger.Bluetooth || it.trigger is Trigger.Power ||
                it.trigger is Trigger.BatteryLow || it.trigger is Trigger.Headset || it.trigger is Trigger.Screen }
            if (needsMonitor) out += DoctorCheck(if (zh) "规则监听服务" else "Rule monitor", RuleMonitorService.running,
                when {
                    RuleMonitorService.running -> if (zh) "运行中" else "Running"
                    RuleMonitorService.lastStartRefused -> if (zh) "系统拒绝在后台启动；打开 DSH 一次或授予电池豁免" else "Start refused in the background; open DSH or grant the battery exemption"
                    else -> if (zh) "未运行" else "Not running"
                })
            if (specs.any { it.trigger is Trigger.Geofence }) {
                val gms = deps.triggers().playServicesGeofencing
                out += DoctorCheck(if (zh) "地点触发" else "Place triggers", if (gms) true else null,
                    if (gms) (if (zh) "使用 Google Play 服务地理围栏" else "Google Play services geofencing")
                    else if (zh) "无 Play 服务：使用系统位置提醒，后台可能延迟数分钟触发" else "No Play services: platform proximity alerts, may fire minutes late in the background")
            }
        }
        val restarts = deps.engine().restarts
        if (restarts.open || restarts.recentFailures > 0) out += DoctorCheck(if (zh) "引擎崩溃保护" else "Engine crash protection", !restarts.open,
            if (restarts.open) (if (zh) "引擎连续崩溃 ${restarts.recentFailures} 次，已暂停自动重启；点“重启引擎”重试" else "Engine crashed ${restarts.recentFailures} times; automatic restarts paused — use Restart engine")
            else if (zh) "最近崩溃 ${restarts.recentFailures} 次，已自动恢复" else "${restarts.recentFailures} recent crash(es), recovered")
        EngineDiagnostics.exitRecords(context).firstOrNull()?.let { r ->
            out += DoctorCheck(if (zh) "上次引擎异常退出" else "Last engine exit", null, r.summary)
        }
        out += DoctorCheck(if (zh) "DSH 输入法（可选）" else "DSH keyboard (optional)", null,
            when {
                DshInputMethodService.isCurrent(context) -> if (zh) "当前输入法" else "Current keyboard"
                else -> if (zh) "WebView/Flutter 等输入框的兜底；可在输入法选择器里手动切换" else "Fallback for WebView/Flutter fields; switch to it in the keyboard picker"
            })
        return out
    }

    /** Full text of the newest exit record (About → crash record). */
    fun lastExitText(context: Context): String? = EngineDiagnostics.exitRecords(context).firstOrNull()?.file?.readText()?.take(6000)
}
