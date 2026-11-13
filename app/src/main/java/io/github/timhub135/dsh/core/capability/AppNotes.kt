package io.github.timhub135.dsh.core.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 经验库: short UI facts the agent recorded about apps on this phone (an entry it found, a trap
 * it hit). Kept in one JSON file in the app's files dir; each note remembers the app version it was
 * last confirmed on and counts as stale once a different version is installed.
 */
@Singleton
class AppNotes internal constructor(
    private val file: File,
    /** Installed version ("name (code)") of a package, or null when it is not installed. */
    private val versionOf: (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE), { installedVersion(context.packageManager, it) })

    @Serializable enum class Kind(val zh: String) { entry("入口"), trap("易错"), fact("事实"), route("路线") }

    @Serializable
    data class Note(
        val id: String,
        val pkg: String,
        val appVersion: String,
        val text: String,
        val kind: Kind,
        val createdAt: Long,
        val lastConfirmedAt: Long,
        val uses: Int = 0,
    )

    @Serializable private data class Stored(val nextId: Int = 1, val notes: List<Note> = emptyList())

    sealed interface Outcome {
        data class Saved(val note: Note) : Outcome
        /** A note saying (nearly) the same thing already exists. */
        data class Duplicate(val note: Note) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    private val lock = Any()
    private var nextId = 1
    private val state: MutableStateFlow<List<Note>> by lazy { MutableStateFlow(load()) }

    /** Every note, in storage order ([forPackages] sorts for display). */
    val all: StateFlow<List<Note>> get() = state.asStateFlow()

    fun isInstalled(pkg: String) = versionOf(pkg) != null

    fun isStale(n: Note): Boolean = n.appVersion.isNotEmpty() && versionOf(n.pkg)?.let { it != n.appVersion } == true

    /** Notes for [pkgs], current ones first, then by last confirmation and uses. */
    fun forPackages(pkgs: Collection<String>): List<Note> =
        state.value.filter { it.pkg in pkgs }.sortedWith(compareBy<Note> { isStale(it) }.thenByDescending { it.lastConfirmedAt }.thenByDescending { it.uses })

    fun get(id: String): Note? = state.value.firstOrNull { it.id == id.trim() }

    fun add(pkg: String, text: String, kind: Kind): Outcome = synchronized(lock) {
        val clean = clean(text)
        problem(clean)?.let { return Outcome.Rejected(it) }
        val notes = state.value
        duplicateOf(clean, notes.filter { it.pkg == pkg && it.kind != Kind.route })?.let { return Outcome.Duplicate(it) }
        val now = clock()
        val note = Note("n${nextId++}", pkg, versionOf(pkg).orEmpty(), clean, kind, now, now)
        save(evict(notes + note, note.id))
        Outcome.Saved(note)
    }

    /** The note helped: counts a use and moves it to the installed version (no longer stale). */
    fun confirm(id: String): Note? = change(id) { it.copy(uses = it.uses + 1, lastConfirmedAt = clock(), appVersion = versionOf(it.pkg) ?: it.appVersion) }

    fun update(id: String, text: String, kind: Kind? = null): Outcome = synchronized(lock) {
        val old = get(id) ?: return Outcome.Rejected("No note $id.")
        val clean = clean(text)
        problem(clean)?.let { return Outcome.Rejected(it) }
        duplicateOf(clean, state.value.filter { it.pkg == old.pkg && it.id != old.id && it.kind != Kind.route })?.let { return Outcome.Duplicate(it) }
        Outcome.Saved(change(id) { it.copy(text = clean, kind = kind ?: it.kind, lastConfirmedAt = clock(), appVersion = versionOf(it.pkg) ?: it.appVersion) }!!)
    }

    /**
     * A tap path that reached [steps].last() from the app's launch screen. One route per target: the
     * same path again counts as a confirmation, a different one replaces the old (UIs change).
     */
    fun addRoute(pkg: String, steps: List<String>): Outcome = synchronized(lock) {
        if (steps.size < 2) return Outcome.Rejected("A route needs at least two steps.")
        val text = routeText(steps)
        val old = state.value.firstOrNull { it.pkg == pkg && it.kind == Kind.route && routeTarget(it.text) == steps.last() }
        when {
            old == null -> add(pkg, text, Kind.route)
            old.text == text -> Outcome.Saved(confirm(old.id)!!)
            else -> update(old.id, text)
        }
    }

    fun remove(id: String): Note? = synchronized(lock) { get(id)?.also { n -> save(state.value.filter { it.id != n.id }) } }

    fun clear(pkg: String) = synchronized(lock) { save(state.value.filter { it.pkg != pkg }) }

    fun clearAll() = synchronized(lock) { save(emptyList()) }

    /**
     * The model-facing block for an app's notes ([max] lines at most), or null when there are none.
     * Over the cap, notes sharing words with [query] (the user's request) come first.
     */
    fun notice(pkgs: Collection<String>, label: String, max: Int = MAX_NOTICE, query: String? = null): String? {
        val notes = forPackages(pkgs).takeIf { it.isNotEmpty() } ?: return null
        val shown = if (notes.size > max && !query.isNullOrBlank()) rank(notes, query).take(max) else notes.take(max)
        return buildString {
            append("Notes on $label from earlier runs on this phone (UI facts you recorded; check them against the screen):\n")
            shown.forEach { append(line(it)).append('\n') }
            if (notes.size > max) append("(+${notes.size - max} more: app_guide lists all)\n")
            if (shown.any { it.kind == Kind.route }) append("(路线) lines are tap paths that worked before from the app's start page: hints only, verify each step on screen.\n")
            append("When a note helped, app_note confirm(id); when it is wrong, update or remove it; add a note for a non-obvious entry or trap you find.")
        }
    }

    fun line(n: Note): String = "[${n.id}] (${n.kind.zh}) ${n.text}" +
        if (isStale(n)) " (旧版本 ${n.appVersion.substringBefore(" (")} 记录，可能已变)" else ""

    private fun change(id: String, f: (Note) -> Note): Note? = synchronized(lock) {
        val old = get(id) ?: return null
        val next = f(old)
        save(state.value.map { if (it.id == old.id) next else it })
        next
    }

    /** Over a cap: drop the lowest-scored notes (stale, long unconfirmed, unused), never [keep]. */
    private fun evict(notes: List<Note>, keep: String): List<Note> {
        var out = notes
        val pkg = notes.first { it.id == keep }.pkg
        val mine = out.filter { it.pkg == pkg }
        if (mine.size > MAX_PER_APP) {
            val drop = mine.filter { it.id != keep }.sortedBy(::score).take(mine.size - MAX_PER_APP).map { it.id }.toSet()
            out = out.filter { it.id !in drop }
        }
        if (out.size > MAX_TOTAL) {
            val drop = out.filter { it.id != keep }.sortedBy(::score).take(out.size - MAX_TOTAL).map { it.id }.toSet()
            out = out.filter { it.id !in drop }
        }
        return out
    }

    private fun score(n: Note): Long = n.lastConfirmedAt + minOf(n.uses, 10) * WEEK_MS - if (isStale(n)) STALE_PENALTY_MS else 0

    private fun load(): List<Note> = synchronized(lock) {
        val s = runCatching { if (file.exists()) json.decodeFromString(Stored.serializer(), file.readText()) else Stored() }
            .onFailure { Log.w(TAG, "unreadable notes file, starting empty", it) }.getOrDefault(Stored())
        nextId = maxOf(s.nextId, (s.notes.mapNotNull { it.id.removePrefix("n").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        s.notes
    }

    private fun save(notes: List<Note>) {
        state.value = notes
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), Stored(nextId, notes)))
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "rename failed" } }
        }.onFailure { Log.w(TAG, "could not save notes", it) }
    }

    companion object {
        const val FILE = "app_notes.json"
        /** Kinds the model may set; routes are recorded by the app. */
        val TOOL_KINDS = listOf(Kind.entry, Kind.trap, Kind.fact)
        const val MAX_PER_APP = 30
        const val MAX_TOTAL = 2000
        const val MAX_NOTICE = 12
        const val MAX_LEN = 200
        private const val TAG = "AppNotes"
        private const val WEEK_MS = 7 * 24 * 3600_000L
        private const val STALE_PENALTY_MS = 365 * 24 * 3600_000L
        private val json = Json { ignoreUnknownKeys = true }

        private val RUN_OF_DIGITS = Regex("\\d{6,}")
        private val PHONE = Regex("\\+?\\d(?:[ -]?\\d){6,}")
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")

        fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ")

        /** Why [text] cannot be stored, or null. */
        fun problem(text: String): String? = when {
            text.isEmpty() -> "The note is empty."
            text.length > MAX_LEN -> "The note is ${text.length} characters; keep it to one sentence of at most $MAX_LEN."
            EMAIL.containsMatchIn(text) || RUN_OF_DIGITS.containsMatchIn(text) || PHONE.containsMatchIn(text) ->
                "Not stored: the note looks like it contains personal data (an email address, phone number or long number). " +
                    "Notes are UI facts only; describe the screen without names, numbers or account details."
            else -> null
        }

        fun routeText(steps: List<String>) = "到「${steps.last()}」：${steps.joinToString(" → ")}"
        fun routeTarget(text: String): String? = text.takeIf { it.startsWith("到「") }?.substringAfter("到「")?.substringBefore("」：", "")?.ifEmpty { null }

        /** [notes] (already in display order) by character pairs shared with [query], most first; ties keep their order. */
        fun rank(notes: List<Note>, query: String): List<Note> {
            val q = bigrams(normalize(query))
            return notes.withIndex().sortedWith(compareByDescending<IndexedValue<Note>> { minOf((bigrams(normalize(it.value.text)) intersect q).size, 5) }
                .thenBy { it.index }).map { it.value }
        }

        /** Letters and digits only, lowercased: "点 右上角「+」" equals "点右上角+". */
        fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

        /** An existing note with the same normalized text, or whose character pairs overlap [text]'s by 80% or more. */
        fun duplicateOf(text: String, notes: List<Note>): Note? {
            val n = normalize(text)
            notes.firstOrNull { normalize(it.text) == n }?.let { return it }
            val a = bigrams(n).takeIf { it.size >= 4 } ?: return null
            return notes.firstOrNull { o ->
                val b = bigrams(normalize(o.text))
                b.isNotEmpty() && (a intersect b).size.toDouble() / (a union b).size >= 0.8
            }
        }

        private fun bigrams(s: String): Set<String> = (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()

        fun installedVersion(pm: PackageManager, pkg: String): String? = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
            "${info.versionName ?: "?"} (${info.longVersionCode})"
        }.getOrNull()
    }
}
