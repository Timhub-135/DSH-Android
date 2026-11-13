package io.github.timhub135.dsh.core.capability.tools

import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.long
import io.github.timhub135.dsh.core.data.RuleDao
import io.github.timhub135.dsh.core.engine.RpcJson
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.RuleSpec
import io.github.timhub135.dsh.core.model.ToolGroup
import io.github.timhub135.dsh.core.trigger.RuleSpecException
import io.github.timhub135.dsh.core.trigger.TriggerHub
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class RuleTools @Inject constructor(
    private val rules: RuleDao,
    private val hub: Provider<TriggerHub>,
) : ToolSet {
    private fun parse(el: kotlinx.serialization.json.JsonElement?): RuleSpec {
        el ?: throw InvalidArgsException("missing 'rule' (a RuleSpec object)")
        val spec = runCatching { RpcJson.decodeFromJsonElement(RuleSpec.serializer(), el) }
            .getOrElse { throw InvalidArgsException("Invalid rule JSON: ${it.message?.lineSequence()?.firstOrNull()}. Expected the RuleSpec format from rule_create.") }
        return try { hub.get().validate(spec) } catch (e: RuleSpecException) {
            throw InvalidArgsException("Invalid rule (nothing was saved): " + e.problems.joinToString("; "))
        }
    }

    private fun notes(spec: RuleSpec): String {
        val h = hub.get()
        val missing = h.missingPrerequisites(spec)
        val caveats = h.caveats(spec)
        return buildString {
            if (missing.isNotEmpty()) append("\nWARNING: it cannot fire until DSH has: ${missing.joinToString()}. Ask the user to grant it (DSH → Settings → Permissions).")
            caveats.forEach { append("\nNote: $it.") }
        }
    }

    override val tools = listOf(
        object : Tool("rule_create", ToolGroup.Rules, Risk.write, false, RULE_DOC, {
            obj("rule", "RuleSpec JSON as documented", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (call.unattended) throw InvalidArgsException("Automations cannot create automations")
                val spec = parse(args["rule"])
                val id = hub.get().create(spec)
                return ToolResult.text("Automation #$id \"${spec.name}\" created and enabled. Allowed tools: ${spec.allowedTools}" + notes(spec))
            }
        },
        object : Tool("rule_list", ToolGroup.Rules, Risk.read, false, "List automations with their specs and status.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = ToolResult.text(rules.all().joinToString("\n") {
                "#${it.id} ${if (it.enabled) "on" else "off"} last=${it.lastFiredAt?.let { t -> java.util.Date(t) } ?: "-"} ${it.specJson}"
            }.ifEmpty { "No automations." })
        },
        object : Tool("rule_update", ToolGroup.Rules, Risk.write, false, "Enable/disable an automation, or replace its whole spec (same RuleSpec format as rule_create).", {
            int("id", "Automation id", required = true); bool("enabled", "On/off"); obj("rule", "New RuleSpec (optional)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (call.unattended) throw InvalidArgsException("Automations cannot change automations")
                val id = args.long("id") ?: throw InvalidArgsException("missing 'id'")
                val spec = args["rule"]?.let(::parse)
                val saved = hub.get().update(id, spec, args.bool("enabled")) ?: throw InvalidArgsException("No automation #$id")
                return ToolResult.text("Updated #$id" + (if (spec != null) ". Allowed tools: ${saved.allowedTools}" + notes(saved) else ""))
            }
        },
        object : Tool("rule_delete", ToolGroup.Rules, Risk.write, false, "Delete an automation.", {
            int("id", "Automation id", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                hub.get().delete(args.long("id") ?: throw InvalidArgsException("id")); return ToolResult.text("Deleted")
            }
        },
    )

    private companion object {
        val RULE_DOC = """
Create an automation that runs when something happens on the phone, even while locked. Confirm the details with the user first.
RuleSpec: {"name": str, "trigger": T, "conditions": [C...], "action": A, "allowedTools": [tool names the unattended run may use]}
T (pick one, field "type"):
 {"type":"time","at":"HH:mm" (24h, e.g. "07:30"),"days":[1..7 Mon=1, empty=every day]} | {"type":"time","everyMinutes":n>=15} | {"type":"time","onceAtEpochMs":ms}
 {"type":"wifi","connected":bool,"ssid":opt} | {"type":"bluetooth","connected":bool,"deviceName":opt}
 {"type":"power","connected":bool} | {"type":"battery_low","percent":n} (fires when the level drops to n while not charging) | {"type":"headset","plugged":bool}
 {"type":"screen","event":"on|off|unlock"} | {"type":"notification","packageName":opt,"keyword":opt} (with packageName the agent gets that app's notification text; without it, only apps on the read allowlist)
 {"type":"app_opened","packageName":str} | {"type":"geofence","lat":d,"lng":d,"radiusM":200,"enter":bool}
C: {"type":"time_range","from":"HH:mm","to":"HH:mm"} | {"type":"days","days":[..]} | {"type":"battery","min":n,"max":n} | {"type":"charging","charging":bool}
A: {"type":"steps","steps":[{"tool":"sound_mode","args":{"mode":"silent"}}]}  (fixed tool calls, no model, preferred when possible)
 | {"type":"agent","prompt":"what the agent should do"}  (an unattended agent run; it can only use allowedTools and cannot ask the user)
Tools needing an unlocked screen will wait for the user to unlock; prefer tools that work while locked.
The rule is validated before it is saved; the result lists any permission the trigger still needs.""".trim()
    }
}
