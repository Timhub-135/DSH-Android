package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * dsh plugin-market index format 1 (scratch/dsh `.agents/notes/implemented/feature/
 * 2026-10-02-plugin-market-index-format.md`). The engine's `pluginMarket` is the reader the UI
 * normally talks to; this one exists so the app can (a) validate the curated index it ships the URL
 * of (`plugins/index.json`, tested), and (b) draw a fetched index when the engine is not running.
 * It applies the reader rules that matter for display and installation and reports the first
 * violation with its JSON path, like dsh's validator.
 */
object MarketIndex {
    /**
     * DSH-Android's curated index (§9.5), served from this repository through jsDelivr. The engine
     * fetches it (and every GitHub-hosted index) from the first of its equivalent CDNs that answers
     * (js/packages/plugin-extensions/fetch-sources.js); the app never fetches an index itself.
     */
    const val CURATED_URL = "https://cdn.jsdelivr.net/gh/Timhub-135/DSH-Android@main/plugins/index.json"

    /** Former addresses of [CURATED_URL] (the repository was Timhub-135/DSH-Android); the engine migrates stored lists. */
    val LEGACY_CURATED_URLS = listOf("https://cdn.jsdelivr.net/gh/Timhub-135/DSH-Android@main/plugins/index.json")

    fun isCurated(url: String): Boolean = url == CURATED_URL || url in LEGACY_CURATED_URLS

    /** [urls] with former curated addresses replaced by [CURATED_URL], order kept, duplicates dropped (same rule as the engine's migrateIndexes). */
    fun migrateIndexes(urls: List<String>): List<String> = urls.map { if (it in LEGACY_CURATED_URLS) CURATED_URL else it }.distinct()

    data class Document(val title: LocalizedText, val description: LocalizedText?, val metadata: MarketIndexMetadata, val plugins: List<MarketEntry>)

    class InvalidIndex(val path: String, reason: String) : Exception("$path: $reason")

    private val NAME = Regex("^(?:@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$")
    private val LANG = Regex("^[a-z]{2,8}(-[a-z0-9]{1,8})*$")
    private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val COMMIT = Regex("^[0-9a-f]{40}$")
    private val PATH_SEGMENT = Regex("^[A-Za-z0-9._@+-]+$")

    fun parse(text: String, indexUrl: String): Document {
        val root = runCatching { RpcJson.parseToJsonElement(text) }.getOrElse { throw InvalidIndex("$", "not JSON") } as? JsonObject
            ?: throw InvalidIndex("$", "not an object")
        val format = (root["format"] as? JsonPrimitive)?.longOrNull
        if (format != 1L) throw InvalidIndex("$.format", "must be 1")
        val title = localized(root["title"], "$.title") ?: throw InvalidIndex("$.title", "required")
        val description = root["description"]?.let { localized(it, "$.description") }
        val meta = root["metadata"]?.let {
            if (it !is JsonObject) throw InvalidIndex("$.metadata", "must be an object")
            runCatching { RpcJson.decodeFromJsonElement(MarketIndexMetadata.serializer(), it) }.getOrElse { throw InvalidIndex("$.metadata", "invalid") }
        } ?: MarketIndexMetadata()
        val plugins = root["plugins"] as? JsonArray ?: throw InvalidIndex("$.plugins", "must be an array")
        val seen = HashSet<String>()
        val entries = plugins.mapIndexed { i, el ->
            val p = "$.plugins[$i]"
            val o = el as? JsonObject ?: throw InvalidIndex(p, "not an object")
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: throw InvalidIndex("$p.name", "required")
            if (!NAME.matches(name) || name.length > 214) throw InvalidIndex("$p.name", "not a valid package name")
            if (!seen.add(name)) throw InvalidIndex("$p.name", "duplicate")
            o["title"]?.let { localized(it, "$p.title") }
            o["description"]?.let { localized(it, "$p.description") }
            val versions = o["versions"] as? JsonArray ?: throw InvalidIndex("$p.versions", "required")
            if (versions.isEmpty()) throw InvalidIndex("$p.versions", "must not be empty")
            val versionSet = HashSet<String>()
            versions.forEachIndexed { j, v -> checkVersion(v, "$p.versions[$j]", versionSet) }
            (o["dist-tags"] as? JsonObject)?.forEach { (tag, target) ->
                val t = (target as? JsonPrimitive)?.contentOrNull
                if (t == null || t !in versionSet) throw InvalidIndex("$p.dist-tags.$tag", "target is not a listed version")
            }
            val entry = runCatching { RpcJson.decodeFromJsonElement(MarketEntry.serializer(), o) }.getOrElse { throw InvalidIndex(p, it.message ?: "invalid") }
            entry.metadata.category?.let { if (it !in meta.categories) throw InvalidIndex("$p.metadata.category", "undeclared category $it") }
            entry.metadata.labels.forEach { if (it !in meta.labels) throw InvalidIndex("$p.metadata.labels", "undeclared label $it") }
            entry.metadata.sortKeys.keys.forEach { if (it !in meta.sortKeys) throw InvalidIndex("$p.metadata.sortKeys", "undeclared sort key $it") }
            entry.homepage?.let { if (!it.startsWith("https://") && !it.startsWith("http://")) throw InvalidIndex("$p.homepage", "must be an http(s) URL") }
            entry.copy(icon = entry.icon?.let { icon(it, indexUrl, "$p.icon") })
        }
        return Document(title, description, meta, entries)
    }

    private fun localized(el: JsonElement?, path: String): LocalizedText? {
        when (el) {
            null -> return null
            is JsonPrimitive -> {
                val s = el.contentOrNull
                if (!el.isString || s.isNullOrBlank()) throw InvalidIndex(path, "must be non-blank text")
                return LocalizedText.of(s)
            }
            is JsonObject -> {
                if ("en" !in el) throw InvalidIndex(path, "must contain en")
                val map = el.mapValues { (k, v) ->
                    if (!LANG.matches(k)) throw InvalidIndex("$path.$k", "bad language id")
                    val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (s.isNullOrBlank()) throw InvalidIndex("$path.$k", "must be non-blank text")
                    s
                }
                return LocalizedText(map)
            }
            else -> throw InvalidIndex(path, "must be text")
        }
    }

    private fun checkVersion(el: JsonElement, p: String, seen: MutableSet<String>) {
        val o = el as? JsonObject ?: throw InvalidIndex(p, "not an object")
        val version = (o["version"] as? JsonPrimitive)?.contentOrNull ?: throw InvalidIndex("$p.version", "required")
        if (!SEMVER.matches(version)) throw InvalidIndex("$p.version", "not an exact version")
        if (!seen.add(version)) throw InvalidIndex("$p.version", "duplicate")
        val ts = (o["publishTimestamp"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw InvalidIndex("$p.publishTimestamp", "required integer")
        if (ts < 0 || ts >= 100_000_000_000L) throw InvalidIndex("$p.publishTimestamp", "out of range")
        val engines = o["engines"] as? JsonObject ?: throw InvalidIndex("$p.engines", "required")
        val dsh = (engines["dsh"] as? JsonPrimitive)?.contentOrNull
        if (dsh.isNullOrBlank()) throw InvalidIndex("$p.engines.dsh", "required")
        val source = o["source"] as? JsonObject ?: throw InvalidIndex("$p.source", "required")
        when ((source["type"] as? JsonPrimitive)?.contentOrNull) {
            "npm" -> (source["registry"] as? JsonPrimitive)?.contentOrNull?.let { r ->
                val u = runCatching { URI(r) }.getOrNull()
                if (u == null || (u.scheme != "https" && u.scheme != "http") || u.userInfo != null || u.query != null || u.fragment != null)
                    throw InvalidIndex("$p.source.registry", "must be an http(s) URL without credentials, query or fragment")
            }
            "git" -> {
                val url = (source["url"] as? JsonPrimitive)?.contentOrNull ?: throw InvalidIndex("$p.source.url", "required")
                val u = runCatching { URI(url) }.getOrNull()
                val segs = u?.path.orEmpty().split('/').filter { it.isNotEmpty() }
                if (u == null || u.scheme != "https" || segs.size < 2 || url.endsWith("/") || u.query != null || u.fragment != null || u.userInfo != null ||
                    url.endsWith(".tgz") || url.endsWith(".tar.gz")) throw InvalidIndex("$p.source.url", "must be a canonical https repository URL")
                val commit = (source["commit"] as? JsonPrimitive)?.contentOrNull
                if (commit == null || !COMMIT.matches(commit)) throw InvalidIndex("$p.source.commit", "must be a 40-hex commit")
                (source["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                    if (path.split('/').any { it == "." || it == ".." || !PATH_SEGMENT.matches(it) }) throw InvalidIndex("$p.source.path", "invalid")
                }
            }
            else -> throw InvalidIndex("$p.source.type", "must be npm or git")
        }
    }

    /** Resolves a relative icon against the index URL; only https (or same-origin) URLs and small data URLs pass. */
    private fun icon(icon: String, indexUrl: String, path: String): String {
        if (icon.startsWith("data:")) {
            if (!Regex("^data:image/(svg\\+xml|png|jpeg|webp);base64,").containsMatchIn(icon)) throw InvalidIndex(path, "unsupported data URL")
            return icon
        }
        val base = runCatching { URI(indexUrl) }.getOrNull() ?: throw InvalidIndex(path, "bad index URL")
        val resolved = runCatching { base.resolve(icon) }.getOrNull() ?: throw InvalidIndex(path, "bad URL")
        val sameOrigin = resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port
        if (resolved.scheme != "https" && !sameOrigin) throw InvalidIndex(path, "must be https")
        return resolved.toString()
    }

    /** The install arguments of the format (`<name>@<version>` for npm; `git+<url>.git#<commit>[&path:/<path>]`). */
    fun installSpec(name: String, v: MarketVersion): String = v.install?.spec ?: when (v.source?.type) {
        "git" -> {
            val url = v.source.url.orEmpty().removeSuffix(".git")
            "git+$url.git#${v.source.commit}" + (v.source.path?.let { "&path:/$it" } ?: "")
        }
        else -> "$name@${v.version}"
    }

    /**
     * Plain local matching for the installed list and an offline index: every word of [text] must
     * occur in the name, author, keywords or any language of the title/description. CJK text also
     * matches by two-character pieces (dsh's search splits Chinese the same way).
     */
    fun matches(text: String, haystack: List<String?>): Boolean {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = haystack.filterNotNull().joinToString("\n").lowercase()
        return words.all { w ->
            if (w in hay) return@all true
            val cjk = w.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            cjk && w.length > 2 && w.windowed(2).all { it in hay }
        }
    }

    fun haystack(e: MarketEntry): List<String?> =
        listOf(e.name, e.author) + e.metadata.keywords + e.title?.all.orEmpty() + e.description?.all.orEmpty()
}
