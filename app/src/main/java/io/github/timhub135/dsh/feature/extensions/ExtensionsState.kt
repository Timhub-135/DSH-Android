package io.github.timhub135.dsh.feature.extensions

import io.github.timhub135.dsh.core.extensions.ConfigGetResult
import io.github.timhub135.dsh.core.extensions.ExtException
import io.github.timhub135.dsh.core.extensions.ExtensionsClient
import io.github.timhub135.dsh.core.extensions.FieldError
import io.github.timhub135.dsh.core.extensions.FormField
import io.github.timhub135.dsh.core.extensions.MarketEntry
import io.github.timhub135.dsh.core.extensions.MarketIndex
import io.github.timhub135.dsh.core.extensions.MarketIndexSummary
import io.github.timhub135.dsh.core.extensions.MarketSearchResult
import io.github.timhub135.dsh.core.extensions.SchemaForm
import io.github.timhub135.dsh.core.extensions.SkillInfo
import io.github.timhub135.dsh.core.extensions.SkillPreview
import io.github.timhub135.dsh.core.extensions.UserError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One plugin row's settings form (§7.2): fields from the projected schema, the value being edited,
 * and instant save — each change is written through config-editor after a short pause (no restart),
 * with field errors shown under their field.
 */
class ConfigEditor(
    private val client: ExtensionsClient,
    private val scope: CoroutineScope,
    val entryId: String,
    private val language: String,
    private val debounceMs: Long = 600,
) {
    data class State(
        val loading: Boolean = true,
        val fields: List<FormField> = emptyList(),
        val value: JsonElement = JsonObject(emptyMap()),
        val errors: Map<String, String> = emptyMap(),
        val saving: Boolean = false,
        val saved: Boolean = false,
        val loadError: UserError? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var saveJob: Job? = null

    fun load() = scope.launch {
        _state.value = try {
            val r: ConfigGetResult = client.configGet(entryId)
            when {
                r.error != null -> State(loading = false, loadError = r.error)
                else -> State(loading = false, fields = r.schema?.let { SchemaForm.fields(it, language, r.overridden + if (r.managedByApp) listOf("*") else emptyList()) }.orEmpty(),
                    value = r.value ?: JsonObject(emptyMap()))
            }
        } catch (e: ExtException) {
            State(loading = false, loadError = e.error)
        }
    }

    /** Sets one field ([value] null = back to its default) and saves after [debounceMs]. */
    fun set(path: List<String>, value: JsonElement?) {
        _state.update { it.copy(value = SchemaForm.withValue(it.value, path, value), errors = it.errors - path.joinToString("."), saved = false) }
        saveJob?.cancel()
        saveJob = scope.launch { delay(debounceMs); save() }
    }

    /** A field whose text does not parse: shown in place, nothing saved. */
    fun invalid(path: List<String>, message: String) = _state.update { it.copy(errors = it.errors + (path.joinToString(".") to message)) }

    suspend fun save() {
        val value = _state.value.value
        _state.update { it.copy(saving = true) }
        val errors: List<FieldError> = try {
            client.configSet(entryId, value).let { if (it.ok) emptyList() else it.errors.ifEmpty { listOf(FieldError("", "invalid")) } }
        } catch (e: ExtException) {
            listOf(FieldError("", e.error.message.ifEmpty { e.error.code }))
        }
        _state.update { s ->
            s.copy(saving = false, saved = errors.isEmpty(),
                errors = errors.associate { normalizePath(it.path) to it.message })
        }
    }

    companion object {
        /** Schemastery / JSON-pointer style paths (`/a/b`, `a.b`, `$.a.b`) → `a.b`. */
        fun normalizePath(p: String): String = p.removePrefix("$").trim('.', '/').replace('/', '.')
    }
}

/**
 * 发现 (§9.2, §9.5): the configured indexes (curated by default) and one search page each. The text
 * box searches installed plugins locally and every index through the engine's plugin-market.
 */
class MarketState(private val client: ExtensionsClient, private val scope: CoroutineScope) {
    data class State(
        val indexes: List<MarketIndexSummary> = emptyList(),
        val results: Map<String, MarketSearchResult> = emptyMap(),
        /** Per index URL ("" = the index list itself). */
        val errors: Map<String, UserError> = emptyMap(),
        val loading: Boolean = false,
        val query: String = "",
    ) {
        /** Every result, de-duplicated by name (first index wins), with the index URL each came from. */
        val entries: List<Pair<String, MarketEntry>> get() {
            val seen = HashSet<String>()
            return indexes.flatMap { ix -> results[ix.url]?.entries.orEmpty().map { ix.url to it } }.filter { seen.add(it.second.name) }
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun load(refresh: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(loading = true) }
            val indexes = try { client.marketIndexes() } catch (e: ExtException) {
                _state.update { it.copy(loading = false, errors = mapOf("" to e.error)) }
                return@launch
            }
            _state.update { it.copy(indexes = indexes) }
            search(_state.value.query, refresh)
        }
    }

    /** Debounced text search over every index. */
    fun query(text: String) {
        _state.update { it.copy(query = text) }
        job?.cancel()
        job = scope.launch { delay(300); search(text, false) }
    }

    private suspend fun search(text: String, refresh: Boolean) {
        _state.update { it.copy(loading = true) }
        val results = LinkedHashMap<String, MarketSearchResult>()
        val errors = LinkedHashMap<String, UserError>()
        for (ix in _state.value.indexes) {
            try { results[ix.url] = client.marketSearch(ix.url, text, refresh = refresh) }
            catch (e: ExtException) { errors[ix.url] = e.error }
        }
        _state.update { it.copy(results = results, errors = errors, loading = false) }
    }

    /** 管理索引: add / remove a third-party index (the curated one can be removed too, and re-added). */
    suspend fun setIndexes(urls: List<String>): UserError? = try {
        client.setMarketIndexes(MarketIndex.migrateIndexes(urls.map { it.trim() }.filter { it.isNotEmpty() }))
        load(refresh = false); null
    } catch (e: ExtException) { e.error }

    companion object {
        val CURATED = MarketIndex.CURATED_URL
    }
}

/** 技能 (§10): the catalog and the import preview (pick → 覆盖 / 跳过 in place → import). */
class SkillsState(private val client: ExtensionsClient, private val scope: CoroutineScope) {
    data class Import(
        val preview: SkillPreview? = null,
        val selected: Set<String> = emptySet(),
        val overwrite: Set<String> = emptySet(),
        val loading: Boolean = false,
        val error: UserError? = null,
        val done: List<String>? = null,
    )

    private val _skills = MutableStateFlow<List<SkillInfo>?>(null)
    val skills: StateFlow<List<SkillInfo>?> = _skills.asStateFlow()
    private val _error = MutableStateFlow<UserError?>(null)
    val error: StateFlow<UserError?> = _error.asStateFlow()
    private val _import = MutableStateFlow<Import?>(null)
    val import: StateFlow<Import?> = _import.asStateFlow()

    fun load() = scope.launch {
        try { _skills.value = client.skills().skills; _error.value = null } catch (e: ExtException) { _error.value = e.error }
    }

    /** [input]: a staged file path, a raw SKILL.md URL or a GitHub repository / tree URL. */
    fun preview(input: String) {
        _import.value = Import(loading = true)
        scope.launch {
            _import.value = try {
                val p = client.skillPreview(input.trim())
                if (p.error != null) Import(error = p.error)
                // Valid and new ones are ticked; same-name ones wait for 覆盖 / 跳过.
                else Import(preview = p, selected = p.skills.filter { it.valid }.map { it.name }.toSet())
            } catch (e: ExtException) { Import(error = e.error) }
        }
    }

    fun toggle(name: String, on: Boolean) = _import.update { it?.copy(selected = if (on) it.selected + name else it.selected - name) }
    fun setOverwrite(name: String, on: Boolean) = _import.update { it?.copy(overwrite = if (on) it.overwrite + name else it.overwrite - name) }

    /** Names that will be written: ticked, and either new or explicitly 覆盖. */
    fun chosen(i: Import): List<String> = i.preview?.skills.orEmpty()
        .filter { it.valid && it.name in i.selected && (!it.exists || it.name in i.overwrite) }.map { it.name }

    fun confirm() {
        val i = _import.value ?: return
        val p = i.preview ?: return
        val names = chosen(i)
        _import.value = i.copy(loading = true)
        scope.launch {
            _import.value = try {
                val r = client.skillImport(p.previewId, names, names.filter { it in i.overwrite })
                load()
                i.copy(loading = false, done = r.imported, error = r.error ?: r.errors.firstOrNull())
            } catch (e: ExtException) { i.copy(loading = false, error = e.error) }
        }
    }

    fun closeImport() { _import.value = null }

    fun setEnabled(name: String, on: Boolean) = scope.launch {
        // Optimistic: the directory watch makes it real within a moment.
        _skills.update { l -> l?.map { if (it.name == name) it.copy(enabled = on) else it } }
        try { client.skillSetEnabled(name, on) } catch (e: ExtException) { _error.value = e.error }
        load()
    }

    fun remove(name: String) = scope.launch {
        try { client.skillRemove(name) } catch (e: ExtException) { _error.value = e.error }
        load()
    }
}
