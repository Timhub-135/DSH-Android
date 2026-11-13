package io.github.timhub135.dsh.core.extensions

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Wire shapes of the `android/ext/…` SDK methods (PLUGINS-DESIGN §13–§14, docs/ENGINE-CONTRACT.md
 * §2.6). The engine is the single source of truth for plugin state; the app only caches the last
 * [ExtList] to draw the page before the engine is up. Every field the app can live without has a
 * default, so an older or newer engine that omits or adds members still decodes.
 */

/**
 * Display text a plugin, its manifest, a config schema or a market index provides: a plain string or
 * a locale map `{en: …, zh: …, "zh-CN": …}` (dsh's LocalizedText; third-party packages use any tag
 * case). Every such field of the `android/ext/…` results and the index decodes through this one
 * type. Keys are kept lower-case; blank and non-string values are dropped.
 */
@Serializable(with = LocalizedTextSerializer::class)
data class LocalizedText(val values: Map<String, String>) {
    /**
     * The text for [language] (a BCP 47 tag, any case, `_` or `-`): the exact tag, then the tag
     * without its last subtags (`zh-hans-cn` → `zh-hans` → `zh`), then any tag of the same language
     * (`zh` → `zh-cn`), then English, then the first; "" when empty.
     */
    fun get(language: String): String {
        if (values.isEmpty()) return ""
        var lang = language.lowercase().replace('_', '-')
        while (lang.isNotEmpty()) {
            values[lang]?.let { return it }
            lang = lang.substringBeforeLast('-', "")
        }
        val base = language.lowercase().replace('_', '-').substringBefore('-')
        // A Chinese UI prefers the mainland variant over another script's (zh → zh-cn / zh-hans before zh-tw).
        val sameLanguage = values.entries.filter { it.key.substringBefore('-') == base }
            .sortedBy { if (it.key == "zh-cn" || it.key == "zh-hans" || it.key == "zh-sg") 0 else 1 }
        sameLanguage.firstOrNull()?.let { return it.value }
        return values["en"] ?: values.entries.firstOrNull { it.key.startsWith("en-") }?.value ?: values.values.first()
    }

    /** The text for the device locale. */
    fun get(): String = get(java.util.Locale.getDefault().toLanguageTag())

    /** Every language's text, for search. */
    val all: Collection<String> get() = values.values

    companion object {
        fun of(text: String) = LocalizedText(mapOf("en" to text))
    }
}

object LocalizedTextSerializer : KSerializer<LocalizedText> {
    /** `{tag: text}` → lower-case tags (`zh_CN` → `zh-cn`), string values only, blank ones dropped. */
    fun fromMap(o: JsonObject): LocalizedText = LocalizedText(o.entries.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { k.lowercase().replace('_', '-') to it }
    }.toMap())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): LocalizedText {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> LocalizedText(el.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { mapOf("en" to it) } ?: emptyMap())
            is JsonObject -> fromMap(el)
            else -> LocalizedText(emptyMap())
        }
    }
    override fun serialize(encoder: Encoder, value: LocalizedText) {
        val out = encoder as JsonEncoder
        if (value.values.keys == setOf("en")) out.encodeJsonElement(JsonPrimitive(value.values.getValue("en")))
        else out.encodeJsonElement(JsonObject(value.values.mapValues { JsonPrimitive(it.value) }))
    }
}

/** A text field the app shows as one string: accepts the [LocalizedText] forms and keeps the device locale's text. */
object LocalizedStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): String = LocalizedTextSerializer.deserialize(decoder).get()
    override fun serialize(encoder: Encoder, value: String) = (encoder as JsonEncoder).encodeJsonElement(JsonPrimitive(value))
}

/** A failure explained in plain words (§15): [code] picks the copy, [message] is already localized by the engine. */
@Serializable
data class UserError(
    val code: String,
    val message: String = "",
    val detail: String? = null,
    val logPath: String? = null,
    /** 'retry-mirror' | 'retry' | 'force-install' | 'uninstall' | 'disable' | 'open-log'. */
    val actions: List<String> = emptyList(),
) {
    companion object {
        const val RETRY_MIRROR = "retry-mirror"
        /** Read again (an index the engine could not fetch from any of its sources). */
        const val RETRY = "retry"
        const val FORCE_INSTALL = "force-install"
        const val UNINSTALL = "uninstall"
        const val DISABLE = "disable"
        const val OPEN_LOG = "open-log"
    }
}

@Serializable
data class ExtSource(
    /** 'npm' | 'tarball' | 'file' | 'github'. */
    val kind: String = "npm",
    val spec: String = "",
    val registry: String? = null,
    val repo: String? = null,
)

/** One Loader row a bundle contributes ("组件"). */
@Serializable
data class ExtRow(
    val id: String,
    val module: String = "",
    val entryId: String? = null,
    /** cordis fiber phase: 'active' | 'pending' | 'loading' | 'failed' | 'disabled' | 'dispose' … */
    val phase: String? = null,
    val error: String? = null,
    val waitingFor: List<String> = emptyList(),
    val configurable: Boolean = false,
    /** Explicit per-row switch state when the engine reports one; otherwise derived from [phase]. */
    val enabled: Boolean? = null,
) {
    val isEnabled: Boolean get() = enabled ?: (phase != "disabled")
}

@Serializable data class ExtOverride(val rowId: String, val label: String = rowId)
@Serializable data class ExtUpdate(val version: String, val compatible: Boolean = true)
@Serializable data class ExtSuspension(
    /** 'crash' | 'safe-mode'. */
    val reason: String,
    val at: Long = 0,
    val message: String? = null,
)

/** Status values of [ExtPlugin.status] (§7.5). */
object ExtStatus {
    const val ACTIVE = "active"
    const val DISABLED = "disabled"
    const val RESTART_PENDING = "restart-pending"
    const val INCOMPATIBLE = "incompatible"
    const val FAILED = "failed"
    const val WAITING = "waiting"
    const val SUSPENDED = "suspended"
}

@Serializable
data class ExtPlugin(
    val name: String,
    /** The manifest's display name: a string or a locale map ([LocalizedText]). */
    val title: LocalizedText? = null,
    val description: LocalizedText? = null,
    val iconDataUrl: String? = null,
    val version: String? = null,
    val source: ExtSource? = null,
    val publisher: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val enabled: Boolean = false,
    val status: String = ExtStatus.DISABLED,
    val statusDetail: UserError? = null,
    val rows: List<ExtRow> = emptyList(),
    val tools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val overrides: List<ExtOverride> = emptyList(),
    val skippedOptional: List<String> = emptyList(),
    val sizeBytes: Long? = null,
    val updatable: Boolean = false,
    val update: ExtUpdate? = null,
    val askBeforeCall: Boolean = false,
    val suspended: ExtSuspension? = null,
    /** Set by the app when the engine's entry for this plugin could not be read ([TolerantList]). */
    val malformed: Boolean = false,
) {
    val displayTitle: String get() = title?.get()?.takeIf { it.isNotBlank() } ?: name
    val descriptionText: String? get() = description?.get()?.takeIf { it.isNotBlank() }

    companion object {
        /** What the list shows for an entry that did not decode: its name (when readable) and a note. */
        fun malformed(el: JsonElement, error: Throwable): ExtPlugin? {
            val name = ((el as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
            return ExtPlugin(name = name, status = ExtStatus.FAILED, malformed = true,
                statusDetail = UserError(ExtCodes.BAD_ENTRY, detail = error.message?.lineSequence()?.firstOrNull()?.take(300)))
        }
    }
}

/**
 * A list read element by element: an element that does not decode becomes [onError]'s replacement
 * (or is skipped when it returns null), so one third-party entry cannot break the whole result.
 */
class TolerantList<T>(private val element: KSerializer<T>, private val onError: (JsonElement, Throwable) -> T?) : KSerializer<List<T>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as JsonDecoder
        val arr = json.decodeJsonElement() as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            try { json.json.decodeFromJsonElement(element, el) } catch (e: kotlinx.serialization.SerializationException) { onError(el, e) } catch (e: IllegalArgumentException) { onError(el, e) }
        }
    }
    override fun serialize(encoder: Encoder, value: List<T>) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(kotlinx.serialization.json.JsonArray(value.map { json.json.encodeToJsonElement(element, it) }))
    }
}

object ExtPluginListSerializer : KSerializer<List<ExtPlugin>> by TolerantList(ExtPlugin.serializer(), ExtPlugin::malformed)
object MarketEntryListSerializer : KSerializer<List<MarketEntry>> by TolerantList(MarketEntry.serializer(), { _, _ -> null })

@Serializable
data class ExtList(
    @Serializable(with = ExtPluginListSerializer::class)
    val plugins: List<ExtPlugin> = emptyList(),
    val safeMode: Boolean = false,
    /** Why safe mode is on: 'boot-failures' | 'requested' | 'crash-loop' (absent from older engines). */
    val safeModeReason: String? = null,
    val restartPending: Boolean = false,
    val dshVersion: String = "",
    val registries: List<String> = emptyList(),
    /** This boot's notices (also sent as `android.ext.notice` once the engine is ready). */
    val notices: List<ExtNotice> = emptyList(),
) {
    val updates: List<ExtPlugin> get() = plugins.filter { it.update?.compatible == true }

    /** Plugins safe mode keeps paused; the banner shows only while there is at least one (an empty set is no safe mode). */
    val safeModePaused: Int get() = plugins.count { it.suspended?.reason == "safe-mode" }
    val showSafeModeBanner: Boolean get() = safeMode && safeModePaused > 0
}

/** Result of every state-changing call (§14). */
@Serializable
data class ChangeView(
    /** 'applied' | 'restart-required' | 'failed' | 'cancelled'. */
    val application: String = "applied",
    val restartRequired: Boolean = false,
    val version: String? = null,
    val error: UserError? = null,
    val warnings: List<String> = emptyList(),
) {
    val failed: Boolean get() = application == "failed" || error != null
    val cancelled: Boolean get() = application == "cancelled"
}

@Serializable
data class ResolveResult(val spec: String, val kind: String = "npm", val note: String? = null)

/** `android/ext/inspect`: everything the one confirmation card shows (§8.1, §9.3). */
@Serializable
data class InspectResult(
    val spec: String = "",
    val name: String = "",
    val version: String? = null,
    val title: LocalizedText? = null,
    /** What the user typed, normalized for display (`owner/repo`, the file name …). */
    val display: String? = null,
    val description: LocalizedText? = null,
    val bundle: Boolean = true,
    val compatible: Boolean = true,
    /** Unmet requirements, e.g. `{"engines.dsh": ">=0.2.2"}` (shape kept loose). */
    val incompatible: JsonElement? = null,
    val publisher: String? = null,
    val maintainers: List<String> = emptyList(),
    val downloads: Long? = null,
    val provenance: Boolean? = null,
    val sizeBytes: Long? = null,
    val license: String? = null,
    val publishedAt: Long? = null,
    val overrides: List<ExtOverride> = emptyList(),
    /** 'npm' | 'tarball' | 'file' | 'github'. */
    val kind: String? = null,
    val registry: String? = null,
    val repo: String? = null,
    /** A reason the package cannot be installed at all (not-bundle, needs-git …), found before the card. */
    val error: UserError? = null,
) {
    val titleText: String? get() = title?.get()?.takeIf { it.isNotBlank() }
    val descriptionText: String? get() = description?.get()?.takeIf { it.isNotBlank() }

    /** The `engines.dsh` range the version asks for, when the engine reported one. */
    val requiredRange: String? get() = when (val i = incompatible) {
        null, is JsonNull -> null
        is JsonPrimitive -> i.contentOrNull
        is JsonObject -> rangeOf(i)
        else -> (i as? kotlinx.serialization.json.JsonArray)?.firstNotNullOfOrNull { e -> (e as? JsonObject)?.let(::rangeOf) }
    }

    /** `{requirements: {"engines.dsh": r}}`, `{"engines.dsh": r}`, `{range: r}`. */
    private fun rangeOf(o: JsonObject): String? {
        (o["requirements"] as? JsonObject)?.let { req -> return (req["engines.dsh"] as? JsonPrimitive)?.contentOrNull ?: req.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull } }
        return (o["engines.dsh"] as? JsonPrimitive)?.contentOrNull ?: (o["range"] as? JsonPrimitive)?.contentOrNull ?: (o["requirement"] as? JsonPrimitive)?.contentOrNull
    }
}

@Serializable data class InstallResult(val result: ChangeView)

@Serializable data class UpdateEntry(val name: String, val current: String? = null, val latest: String, val compatible: Boolean = true)
@Serializable data class UpdatesResult(val updates: List<UpdateEntry> = emptyList())

@Serializable data class DetailResult(val markdown: String? = null, val origin: String? = null, val documentId: String? = null)

@Serializable
data class ConfigGetResult(
    /** null: the row has no `Config` (nothing to edit). */
    val schema: JsonObject? = null,
    val value: JsonElement? = null,
    /** Paths (dot-separated, `*` = all) the app's own settings layer decides; shown read-only. */
    val overridden: List<String> = emptyList(),
    val managedByApp: Boolean = false,
    /** Projection limits (validation the form cannot do); informational. */
    val limitations: List<String> = emptyList(),
    val error: UserError? = null,
)

@Serializable data class FieldError(val path: String = "", val message: String)
@Serializable data class ConfigSetResult(val ok: Boolean = true, val errors: List<FieldError> = emptyList())

@Serializable data class LogResult(val text: String = "", val path: String? = null)

/** `android.ext.progress`. */
@Serializable
data class ExtProgress(
    val requestId: String,
    /** 'resolving' | 'installing' | 'checking' | 'applying'. */
    val phase: String,
    val registry: String? = null,
    val index: Int? = null,
    val total: Int? = null,
)

/** `android.ext.notice` (sent after `ready`). */
@Serializable
data class ExtNotice(
    /** 'suspended' | 'safe-mode' | 'safe-mode-cleared' | 'activated' | 'activation-failed'. */
    val kind: String,
    val name: String? = null,
    val message: String = "",
    /** For 'activated': the tools it added. */
    val tools: List<String> = emptyList(),
) {
    val isActivation: Boolean get() = kind == "activated" || kind == "activation-failed"
}

// ---- plugin market (passthrough of dsh's pluginMarket Remote) ------------------------------------

@Serializable
data class MarketIndexSummary(
    val url: String,
    val title: LocalizedText? = null,
    val description: LocalizedText? = null,
    val error: String? = null,
)

@Serializable data class MarketInstallTarget(val spec: String, val registry: String? = null)

@Serializable
data class MarketCompatibility(
    val compatible: Boolean = true,
    val runtimeVersion: String? = null,
    val requirements: Map<String, String> = emptyMap(),
    val exempted: Boolean = false,
)

@Serializable
data class MarketVersionSource(val type: String, val registry: String? = null, val url: String? = null, val commit: String? = null, val path: String? = null)

@Serializable
data class MarketVersion(
    val version: String,
    val publishTimestamp: Long = 0,
    val source: MarketVersionSource? = null,
    val engines: Map<String, String> = emptyMap(),
    /** Present in search results (MarketListingVersion), absent in a raw index. */
    val install: MarketInstallTarget? = null,
    val compatibility: MarketCompatibility? = null,
)

@Serializable data class MarketRepository(val url: String, val directory: String? = null)

@Serializable
data class MarketPluginMetadata(
    val category: String? = null,
    val labels: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val sortKeys: Map<String, Double> = emptyMap(),
)

@Serializable data class MarketInstallation(val status: String = "not-installed", val installedVersion: String? = null)

/** One index plugin; with search results also its default version and installation status. */
@Serializable
data class MarketEntry(
    val name: String,
    val title: LocalizedText? = null,
    val description: LocalizedText? = null,
    val icon: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val repository: MarketRepository? = null,
    val metadata: MarketPluginMetadata = MarketPluginMetadata(),
    @SerialName("dist-tags") val distTags: Map<String, String> = emptyMap(),
    val versions: List<MarketVersion> = emptyList(),
    val defaultVersion: String? = null,
    val installation: MarketInstallation = MarketInstallation(),
) {
    fun title(language: String): String = title?.get(language)?.takeIf { it.isNotBlank() } ?: name
    /** The version a tap on 安装 installs: the engine's default, else the recorded `latest` (never `name@latest`). */
    val installVersion: MarketVersion? get() {
        val want = defaultVersion ?: distTags["latest"]
        return versions.firstOrNull { it.version == want }
    }
    val installed: Boolean get() = installation.status == "installed" || installation.status == "update-available" || installation.status == "different-version"
}

@Serializable data class MarketTitled(val title: LocalizedText, val order: String? = null)

@Serializable
data class MarketIndexMetadata(
    val categories: Map<String, MarketTitled> = emptyMap(),
    val labels: Map<String, MarketTitled> = emptyMap(),
    val sortKeys: Map<String, MarketTitled> = emptyMap(),
)

@Serializable
data class MarketSearchResult(
    val url: String = "",
    val title: LocalizedText? = null,
    val description: LocalizedText? = null,
    val metadata: MarketIndexMetadata = MarketIndexMetadata(),
    val fetchedAt: Long = 0,
    /** A third-party index entry that does not decode is left out (the engine validated the index already). */
    @Serializable(with = MarketEntryListSerializer::class)
    val entries: List<MarketEntry> = emptyList(),
    val total: Int = 0,
    val nextOffset: Int? = null,
)

// ---- skills ---------------------------------------------------------------------------------------

object SkillOrigin {
    /** `$DSH_HOME/skills` (imported here): the only ones the app can switch off or delete. */
    const val IMPORTED = "imported"
    /** Imported and switched off (moved to `skills-disabled/`). */
    const val DISABLED = "disabled"
    const val PLUGIN = "plugin"
    const val BUILTIN = "builtin"
    const val AGENTS = "agents"
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class SkillInfo(
    val name: String,
    @Serializable(with = LocalizedStringSerializer::class) val description: String = "",
    /** The engine calls it `source`. */
    @kotlinx.serialization.json.JsonNames("source") val origin: String = SkillOrigin.BUILTIN,
    /** Imported here: may be switched off or deleted. */
    val removable: Boolean = false,
    val path: String? = null,
    /** Providing plugin, for [SkillOrigin.PLUGIN]. */
    val plugin: String? = null,
    val enabled: Boolean = true,
    /** Script files that likely won't run on a phone (`*.py`, `*.sh` …). */
    val scripts: List<String> = emptyList(),
) {
    val editable: Boolean get() = removable || origin == SkillOrigin.IMPORTED || origin == SkillOrigin.DISABLED
}

@Serializable data class SkillList(val skills: List<SkillInfo> = emptyList())

@Serializable
data class SkillCandidate(
    val name: String,
    @Serializable(with = LocalizedStringSerializer::class) val description: String = "",
    val path: String = "",
    val valid: Boolean = true,
    /** Why it is not valid: `frontmatter`, `name`, or the missing field names. */
    val problem: String? = null,
    val error: UserError? = null,
    val files: Int = 0,
    /** A skill of the same name is already imported (offer 覆盖 / 跳过 in place). */
    val exists: Boolean = false,
    val scripts: List<String> = emptyList(),
)

@Serializable data class SkillPreview(val previewId: String = "", val skills: List<SkillCandidate> = emptyList(), val error: UserError? = null)
@Serializable
data class SkillImportResult(
    val imported: List<String> = emptyList(),
    /** `{name, reason}` objects (or names). */
    val skipped: List<JsonElement> = emptyList(),
    val errors: List<UserError> = emptyList(),
    val error: UserError? = null,
)

// ---- MCP ------------------------------------------------------------------------------------------

@Serializable data class McpTool(val name: String, @Serializable(with = LocalizedStringSerializer::class) val description: String? = null)
@Serializable data class McpTestResult(val ok: Boolean = false, val tools: List<McpTool> = emptyList(), val error: UserError? = null)

/** One `dsh-mcp-client` row as running now. */
@Serializable
data class McpServerStatus(
    /** Loader row id (`mcp-<id>`). */
    val id: String,
    /** The app's server id (`mcp__<serverName>__tool`). */
    val serverName: String? = null,
    /** Loader phase: 'active' | 'loading' | 'waiting' | 'failed' | 'disabled' | 'inactive'. */
    val phase: String? = null,
    /** Tool names (`mcp__…`) or `{name, description}` objects. */
    val tools: List<JsonElement> = emptyList(),
    /** A first-line message, or a UserError object. */
    val error: JsonElement? = null,
) {
    val server: String get() = serverName ?: id.removePrefix("mcp-")
    val toolNames: List<String> get() = tools.mapNotNull { t ->
        when (t) {
            is JsonPrimitive -> t.contentOrNull
            is JsonObject -> (t["name"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.map { it.substringAfter("mcp__$server" + "__") }
    /** 'connected' | 'connecting' | 'failed' | 'disabled'. */
    val state: String get() = when (phase) {
        "active" -> "connected"
        "failed" -> "failed"
        "disabled", "inactive" -> "disabled"
        else -> "connecting"
    }
    val errorText: String? get() = when (val e = error) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull ?: (e["code"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}

@Serializable data class McpStatusResult(val servers: List<McpServerStatus> = emptyList(), val maxLocalServers: Int? = null)

/** `android/ext/mcp/install`: a JS stdio server installed into `dsh-home/mcp-servers/`. */
@Serializable
data class McpInstallResult(
    val ok: Boolean = true,
    val `package`: String = "",
    val version: String? = null,
    /** The server's bin script relative to `dsh-home/mcp-servers/node_modules` (`<package>/<path>`). */
    val bin: String? = null,
    val error: UserError? = null,
)
