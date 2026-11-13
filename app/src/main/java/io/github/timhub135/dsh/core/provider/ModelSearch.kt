package io.github.timhub135.dsh.core.provider

/**
 * The search field of the model lists (composer model menu, 默认模型, a provider's model list):
 * shown only once a list is long, matching model id, display name or provider name.
 */
object ModelSearch {
    /** More models than this get a search field. */
    const val THRESHOLD = 8

    fun shown(count: Int) = count > THRESHOLD

    /** Every whitespace-separated word of [query] occurs (case-insensitively) in one of [fields]; a blank query matches all. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.all { w -> fields.any { it?.contains(w, ignoreCase = true) == true } }
    }
}
