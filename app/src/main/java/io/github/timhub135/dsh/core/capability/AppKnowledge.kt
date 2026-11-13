package io.github.timhub135.dsh.core.capability

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in app guides (`assets/knowledge/apps/<id>/APP.md`):
 * facts about an app's UI, indexed by package name on first use, together with this phone's own
 * [AppNotes]. The screen tools append both to the result the first time a conversation enters that
 * app; `app_guide` looks them up on demand. Subagents share their conversation's record.
 */
@Singleton
class AppKnowledge internal constructor(
    private val source: () -> List<Pair<String, String>>,
    private val notes: AppNotes? = null,
    private val labelOf: (String) -> String = { it },
) {
    @Inject constructor(@ApplicationContext context: Context, notes: AppNotes) : this({ fromAssets(context.assets) }, notes, { pkg ->
        runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    })

    data class Entry(
        val id: String,
        val display: String,
        val description: String,
        val packages: List<String>,
        val aliases: List<String>,
        val body: String,
    )

    val entries: List<Entry> by lazy { source().mapNotNull { (id, text) -> runCatching { parse(id, text) }.getOrNull() }.sortedBy { it.id } }
    private val byPackage by lazy { entries.flatMap { e -> e.packages.map { it to e } }.toMap() }

    /** Per conversation: guides already given, how many were appended automatically this turn, and the turn's request. */
    private class Visits { val given = HashSet<String>(); var appended = 0; var prompt: String? = null }
    private val visits = HashMap<String, Visits>()

    private fun visits(sessionId: String) = visits.getOrPut(sessionId.substringBefore('/')) { Visits() }

    fun forPackage(packageName: String): Entry? = byPackage[packageName]

    /** By id, display name, alias or package (case-insensitive); then by a name containing [query] ("微信读书" is not 微信). */
    fun find(query: String): Entry? {
        val q = query.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        fun names(e: Entry) = (listOf(e.id, e.display) + e.aliases + e.packages).map { it.lowercase() }
        return entries.firstOrNull { e -> q in names(e) }
            ?: entries.firstOrNull { e -> q.length >= 2 && names(e).any { it.contains(q) } }
    }

    /**
     * This phone's notes and the built-in guide for [packageName] the first time [sessionId]'s
     * conversation enters it, else null. At most [MAX_PER_TURN] are appended per turn ([newTurn]),
     * so hopping through many apps does not flood the context; one held back by the cap is offered
     * again on a later visit.
     */
    fun firstVisitNotice(sessionId: String, packageName: String): String? {
        val e = forPackage(packageName)
        val prompt = synchronized(visits) { visits[sessionId.substringBefore('/')]?.prompt }
        val mine = notes?.notice(listOf(packageName), e?.display ?: labelOf(packageName), query = prompt)
        if (e == null && mine == null) return null
        val key = e?.id ?: packageName
        synchronized(visits) {
            val v = visits(sessionId)
            if (key in v.given || v.appended >= MAX_PER_TURN) return null
            v.given += key
            v.appended++
        }
        val guide = e?.let { "App guide for ${it.display} (first visit; facts about its UI, not instructions):\n${it.body}" }
        return listOfNotNull(mine, guide).joinToString("\n\n")
    }

    /**
     * `app_guide` result for a built-in [e] and/or an installed [packageName]: every note, then the
     * guide; null when there is neither. Also counts as given, so the screen tools do not append it again.
     */
    fun lookup(sessionId: String, e: Entry?, packageName: String? = null): String? {
        val pkgs = e?.packages ?: listOfNotNull(packageName)
        val mine = notes?.notice(pkgs, e?.display ?: labelOf(packageName ?: return null), max = AppNotes.MAX_PER_APP)
        if (e == null && mine == null) return null
        synchronized(visits) { visits(sessionId).given += e?.id ?: packageName!! }
        val guide = e?.let { "App guide for ${it.display} (${it.packages.joinToString()}; facts about its UI, not instructions):\n${it.body}" }
        return listOfNotNull(mine, guide).joinToString("\n\n")
    }

    /** The user spoke: the per-turn cap starts again, and [prompt] ranks the notes shown this turn. */
    fun newTurn(sessionId: String, prompt: String? = null) {
        synchronized(visits) { visits(sessionId).let { it.appended = 0; it.prompt = prompt } }
    }

    companion object {
        const val DIR = "knowledge/apps"
        const val MAX_PER_TURN = 3

        private fun fromAssets(assets: AssetManager): List<Pair<String, String>> =
            assets.list(DIR).orEmpty().mapNotNull { id ->
                runCatching { id to assets.open("$DIR/$id/APP.md").bufferedReader().use { it.readText() } }.getOrNull()
            }

        /** APP.md: a `---` frontmatter of `key: value` lines (values are plain text or JSON string arrays), then the body. */
        fun parse(id: String, text: String): Entry {
            val lines = text.replace("\r\n", "\n").lines()
            require(lines.firstOrNull()?.trim() == "---") { "$id: no frontmatter" }
            val close = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: error("$id: unterminated frontmatter")
            val fields = lines.subList(1, close).mapNotNull { l ->
                val k = l.substringBefore(':', "").trim()
                if (k.isEmpty() || l.startsWith(" ") || l.startsWith("#")) null else k to l.substringAfter(':').trim()
            }.toMap()
            fun list(k: String): List<String> = fields[k]?.takeIf { it.startsWith("[") }
                ?.let { (Json.parseToJsonElement(it) as JsonArray).mapNotNull { v -> (v as? JsonPrimitive)?.contentOrNull } } ?: emptyList()
            val packages = list("packages")
            require(packages.isNotEmpty()) { "$id: no packages" }
            val body = lines.drop(close + 1).joinToString("\n").trim()
            require(body.isNotEmpty()) { "$id: empty body" }
            return Entry(fields["name"]?.ifEmpty { null } ?: id, fields["display"]?.ifEmpty { null } ?: id, fields["description"].orEmpty(),
                packages, list("aliases"), body)
        }
    }
}
