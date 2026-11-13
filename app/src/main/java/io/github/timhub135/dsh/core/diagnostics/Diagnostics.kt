package io.github.timhub135.dsh.core.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * 复制诊断信息: a fixed-section plain-text report for bug reports. The builder only ever receives
 * facts that are safe to share (no key, login token, conversation or screen content is passed in);
 * [Redactor] then scrubs the whole text once more, because free-form parts (error messages, engine
 * log lines) can still carry an address, an email or a credential. Nothing is sent anywhere: the
 * UI shows the text and copies or shares it only on request.
 */

/** Final pass over the report: IPs (with ports), emails, bearer tokens, `sk-…` keys, credential-like `key=value`, URL user-info, JWTs. */
object Redactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        // https://user:pass@host
        Regex("(?<=://)[^/\\s:@]+:[^/\\s@]+@") to "<redacted>@",
        Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{4,}") to "$1 <redacted>",
        Regex("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}(?:\\.[A-Za-z0-9_-]*)?") to "<redacted>",
        Regex("\\bsk-[A-Za-z0-9_-]{6,}") to "sk-<redacted>",
        // api_key=…, "token": "…", Authorization: …, ?key=…, password=… (not max_tokens=…: the name must end in the word).
        Regex("(?i)([A-Za-z0-9_-]*?(?:api[_-]?key|apikey|key|access[_-]?token|refresh[_-]?token|token|secrets?|password|passwd|pwd|authorization|auth|cookie|session[_-]?id|credentials?))(?![A-Za-z])(\"?\\s*[=:]\\s*\"?)([^\\s\"&,;}]+)") to "$1$2<redacted>",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "<email>",
        Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![\\w.])") to "<ip>",
        // IPv6: full form, or compressed with "::" (bracketed with a port too).
        Regex("\\[?(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}(?![\\w:])\\]?(?::\\d{1,5})?") to "<ip>",
        Regex("\\[?(?<![\\w:])(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,5})?(?![\\w:])\\]?(?::\\d{1,5})?") to "<ip>",
        Regex("\\[::1?\\](?::\\d{1,5})?") to "<ip>",
        // Long opaque strings (tokens, hashes of secrets): 40+ characters mixing letters and digits.
        Regex("\\b(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{40,}\\b") to "<redacted>",
    )

    fun redact(text: String): String = rules.fold(text) { acc, (re, with) -> re.replace(acc, with) }
}

/** One configured third-party provider as the report shows it: no key, no URL path, only the host. */
data class ProviderFacts(val id: String, val preset: String, val enabled: Boolean, val baseUrl: String, val models: List<String>, val verified: List<String>,
    val interfaceType: String = "openai-chat-completions")

/** How one recent run ended. */
data class RunOutcome(val time: Long, val status: String, val category: String? = null, val message: String? = null)

data class DiagnosticsInput(
    val appVersion: String,
    val versionCode: Long,
    val buildType: String,
    val engineVersion: String?,
    val device: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val shizuku: String,
    val accessibility: Boolean,
    val overlay: Boolean,
    val batteryExempt: Boolean,
    val notifications: Boolean,
    /** null = supported, else why not. */
    val agentScreenUnavailable: String?,
    val agentScreenSetting: Boolean,
    /** Why the latest agent screen could not be created (AgentScreen.lastCreateError), null when none failed. */
    val agentScreenError: String? = null,
    /** Set when only the degraded, untrusted agent screen can be created here (AgentScreen.degradedReason, #60). */
    val agentScreenDegraded: String? = null,
    /** "api key" / "account" / "none" (never the credential). */
    val deepseekCredential: String,
    /** Role → "route / model …" (main model, effort). */
    val roles: List<Pair<String, String>>,
    val providers: List<ProviderFacts>,
    val runs: List<RunOutcome>,
    val engineState: String,
    val engineErrors: List<String>,
    val lastEngineExit: String?,
    val generatedAt: Long,
    val timeZone: TimeZone = TimeZone.getDefault(),
)

object DiagnosticsReport {
    const val RUNS = 3
    const val ENGINE_ERROR_LINES = 12

    fun build(input: DiagnosticsInput): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = input.timeZone }
        fun yes(b: Boolean, on: String = "yes", off: String = "no") = if (b) on else off
        val sb = StringBuilder()
        fun section(name: String) = sb.append("\n== ").append(name).append(" ==\n")
        fun line(k: String, v: String) = sb.append(k).append(": ").append(v).append('\n')
        sb.append("DSH diagnostics (generated ").append(fmt.format(Date(input.generatedAt))).append(")\n")
        section("App")
        line("version", "${input.appVersion} (${input.versionCode}) ${input.buildType}")
        line("engine", input.engineVersion ?: "unknown")
        section("Device")
        line("model", input.device)
        line("android", "${input.androidRelease} (SDK ${input.sdk})")
        line("abi", input.abis.joinToString(", ").ifEmpty { "unknown" })
        section("Phone access")
        line("shizuku", input.shizuku)
        line("accessibility", yes(input.accessibility, "on", "off"))
        line("overlay", yes(input.overlay, "granted", "not granted"))
        line("battery optimization", yes(input.batteryExempt, "unrestricted", "restricted"))
        line("notifications", yes(input.notifications, "allowed", "not allowed"))
        line("agent screen", agentScreenStatus(input) + ", setting " + yes(input.agentScreenSetting, "on", "off"))
        input.agentScreenError?.let { line("agent screen last failure", oneLine(it, 400)) }
        section("Models")
        line("deepseek sign-in", input.deepseekCredential)
        input.roles.forEach { (role, v) -> line(role, v) }
        section("Providers")
        if (input.providers.isEmpty()) sb.append("none\n")
        input.providers.forEach { p ->
            sb.append("- ").append(p.id).append(" [").append(p.preset).append("] ").append(yes(p.enabled, "on", "off"))
                .append(" · ").append(p.interfaceType).append(" · ").append(endpointLabel(p.baseUrl)).append('\n')
            sb.append("  models: ").append(p.models.joinToString(", ").ifEmpty { "none" }).append('\n')
            sb.append("  passed test: ").append(p.verified.filter { it in p.models }.joinToString(", ").ifEmpty { "none" }).append('\n')
        }
        section("Recent runs (newest first)")
        if (input.runs.isEmpty()) sb.append("none\n")
        input.runs.take(RUNS).forEach { r ->
            sb.append("- ").append(fmt.format(Date(r.time))).append(' ').append(r.status)
            r.category?.let { sb.append(" · ").append(it) }
            r.message?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(oneLine(it, 300)) }
            sb.append('\n')
        }
        section("Engine")
        line("state", oneLine(input.engineState, 300))
        input.lastEngineExit?.let { line("last abnormal exit", oneLine(it, 300)) }
        if (input.engineErrors.isNotEmpty()) {
            sb.append("recent errors:\n")
            input.engineErrors.takeLast(ENGINE_ERROR_LINES).forEach { sb.append("  ").append(oneLine(it, 240)).append('\n') }
        }
        return Redactor.redact(sb.toString())
    }

    /**
     * "supported", "degraded (untrusted display: no ADD_TRUSTED_DISPLAY, SDK 31)", "unsupported (…)" when the
     * latest creation failed with nothing else to go on, or "unavailable (…)" (no Shizuku, Android 10, …).
     */
    fun agentScreenStatus(input: DiagnosticsInput): String = when {
        input.agentScreenUnavailable != null -> "unavailable (${input.agentScreenUnavailable})"
        input.agentScreenError != null && input.agentScreenDegraded == null -> "unsupported (creation failed, see last failure)"
        input.agentScreenDegraded != null -> "degraded (${input.agentScreenDegraded})"
        else -> "supported"
    }

    private fun oneLine(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)

    /**
     * Scheme and host only (no path, query or user-info). An IP literal is described instead of
     * shown (the redactor would hide it anyway): loopback, LAN or public says what matters.
     */
    fun endpointLabel(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]") ?: return "invalid address"
        val scheme = uri.scheme?.lowercase() ?: "?"
        val ipLiteral = host.matches(Regex("[0-9.]+")) || host.contains(':')
        val shown = if (!ipLiteral) host.lowercase() else runCatching { InetAddress.getByName(host) }.getOrNull()?.let { a ->
            when {
                a.isLoopbackAddress || a.isAnyLocalAddress -> "IP (this phone)"
                a.isSiteLocalAddress || a.isLinkLocalAddress || (a !is Inet4Address && (a.address[0].toInt() and 0xfe) == 0xfc) -> "IP (LAN)"
                else -> "IP (public)"
            }
        } ?: "IP"
        return "$scheme · $shown"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A run's end from its logged event (`turn/end`, `app/error`, `app/stopped`); null for other
     * types. Only the error's kind, code and message are read: never `retryText` (the user's prompt).
     */
    fun outcomeOf(type: String, time: Long, data: String): RunOutcome? {
        val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
        return when (type) {
            "turn/end" -> {
                val reason = o["reason"] as? JsonObject
                when (reason?.s("kind")) {
                    "completed" -> RunOutcome(time, "completed")
                    "error" -> {
                        val err = reason["error"] as? JsonObject
                        RunOutcome(time, "failed", listOfNotNull("llm", err?.s("code") ?: err?.s("kind")).joinToString("/"), err?.s("message"))
                    }
                    "max-tokens" -> RunOutcome(time, "stopped at max tokens")
                    else -> RunOutcome(time, "stopped")
                }
            }
            "app/error" -> RunOutcome(time, "failed", listOfNotNull(o.s("kind") ?: "engine", o.s("code")).joinToString("/"), o.s("message"))
            "app/stopped" -> RunOutcome(time, "stopped by user")
            else -> null
        }
    }

    private val ERROR_LINE = Regex("(?i)\\b(error|exception|fatal|unhandled|panic|abort|ECONN|ETIMEDOUT|ENOTFOUND|EAI_AGAIN)")

    /** The error-looking lines of an engine log tail. */
    fun errorLines(log: String?): List<String> =
        log.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && ERROR_LINE.containsMatchIn(it) }.toList().takeLast(ENGINE_ERROR_LINES)
}
