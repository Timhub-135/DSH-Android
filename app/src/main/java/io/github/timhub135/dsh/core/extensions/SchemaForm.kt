package io.github.timhub135.dsh.core.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A plugin row's settings form, generated from the JSON Schema that app-boot's config projector
 * makes of the row's Schemastery `Config` (PLUGINS-DESIGN §7.2). The projector wraps every value
 * position as `anyOf: [<schema>, {$ref: loaderExpression}]`, adds `null` for optional values and
 * keeps Schemastery metadata under `x-cordis` (role, hidden, localized descriptions); this mapper
 * unwraps all of that into the handful of controls a phone form needs. Anything else (unions of
 * objects, tuples, dictionaries, `!!js` values) becomes [FormField.Raw]: edited as JSON (valid
 * YAML) for just that field.
 */
sealed interface FormField {
    /** Property path from the row config's root. */
    val path: List<String>
    val title: String
    val description: String?
    /** Decided by the app's own settings layer (the overlay wins); shown, not editable. */
    val locked: Boolean

    data class Text(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val default: String?, val options: List<String>? = null, val secret: Boolean = false, val multiline: Boolean = false,
    ) : FormField

    data class Number(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val default: Double?, val integer: Boolean, val min: Double? = null, val max: Double? = null,
    ) : FormField

    data class Toggle(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val default: Boolean?,
    ) : FormField

    data class StringList(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val default: List<String>?,
    ) : FormField

    data class Group(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val fields: List<FormField>, val collapsed: Boolean = false,
    ) : FormField

    /** Not representable as a control: edited as code. */
    data class Raw(
        override val path: List<String>, override val title: String, override val description: String?, override val locked: Boolean,
        val default: JsonElement? = null,
    ) : FormField
}

object SchemaForm {
    private const val LOADER_EXPRESSION = "#/\$defs/loaderExpression"

    /**
     * Fields of a row config [schema] (the projection's root object). [overridden]: dotted paths the
     * engine reports as decided by a higher layer. Returns one [FormField.Raw] for the whole value when
     * the root itself is not an object.
     */
    fun fields(schema: JsonObject, language: String = "en", overridden: Collection<String> = emptyList()): List<FormField> {
        val defs = (schema["\$defs"] as? JsonObject) ?: (schema["definitions"] as? JsonObject) ?: JsonObject(emptyMap())
        val ctx = Ctx(defs, language, overridden.toSet())
        val root = ctx.normalize(schema, 0) ?: return emptyList()
        if (root.type != "object" || root.properties == null) return listOf(FormField.Raw(emptyList(), "", root.description, false, root.default))
        return ctx.objectFields(root, emptyList(), 0)
    }

    /** A schema node with the projector's wrappers removed. */
    private data class Node(
        val type: String?,
        val raw: JsonObject,
        val description: String?,
        val default: JsonElement?,
        val cordis: JsonObject?,
        val options: List<String>? = null,
        val properties: JsonObject? = null,
        val items: JsonObject? = null,
        val unsupported: Boolean = false,
    ) {
        val hidden: Boolean get() = cordis?.get("hidden")?.let { (it as? JsonPrimitive)?.booleanOrNull ?: true } == true
        val role: String? get() = (cordis?.get("role") as? JsonPrimitive)?.contentOrNull
        val collapse: Boolean get() = (cordis?.get("collapse") as? JsonPrimitive)?.booleanOrNull == true
    }

    private class Ctx(val defs: JsonObject, val language: String, val overridden: Set<String>) {

        fun objectFields(node: Node, path: List<String>, depth: Int): List<FormField> =
            node.properties!!.mapNotNull { (key, value) -> (value as? JsonObject)?.let { field(key, it, path + key, depth + 1) } }

        fun field(key: String, schema: JsonObject, path: List<String>, depth: Int): FormField? {
            val n = normalize(schema, depth) ?: return FormField.Raw(path, key, null, locked(path))
            if (n.hidden) return null
            val locked = locked(path)
            if (n.unsupported) return FormField.Raw(path, key, n.description, locked, n.default)
            n.options?.let { return FormField.Text(path, key, n.description, locked, n.default?.str(), options = it) }
            return when (n.type) {
                "string" -> FormField.Text(path, key, n.description, locked, n.default?.str(), secret = n.role == "secret",
                    multiline = n.role == "textarea")
                "number", "integer" -> FormField.Number(path, key, n.description, locked, (n.default as? JsonPrimitive)?.doubleOrNull,
                    integer = n.type == "integer", min = n.raw.num("minimum"), max = n.raw.num("maximum"))
                "boolean" -> FormField.Toggle(path, key, n.description, locked, (n.default as? JsonPrimitive)?.booleanOrNull)
                "array" -> {
                    val item = n.items?.let { normalize(it, depth + 1) }
                    if (item != null && !item.unsupported && item.type == "string" && item.options == null)
                        FormField.StringList(path, key, n.description, locked, (n.default as? JsonArray)?.mapNotNull { it.str() })
                    else FormField.Raw(path, key, n.description, locked, n.default)
                }
                "object" -> if (n.properties != null && depth < 6)
                    FormField.Group(path, key, n.description, locked, objectFields(n, path, depth), collapsed = n.collapse || depth > 1)
                else FormField.Raw(path, key, n.description, locked, n.default)
                else -> FormField.Raw(path, key, n.description, locked, n.default)
            }
        }

        fun locked(path: List<String>): Boolean {
            if (overridden.isEmpty()) return false
            val dotted = path.joinToString(".")
            return overridden.any { it.isEmpty() || it == "*" || it == dotted || dotted.startsWith("$it.") }
        }

        /** Resolves `$ref`, unwraps expression / null alternatives, merges outer annotations. */
        fun normalize(schema: JsonObject, depth: Int): Node? {
            if (depth > 12) return null
            var s = schema
            (s["\$ref"] as? JsonPrimitive)?.contentOrNull?.let { ref ->
                if (ref == LOADER_EXPRESSION) return null
                val name = ref.substringAfterLast('/')
                val target = defs[name] as? JsonObject ?: return Node(null, s, describe(s), s["default"], s.cordis(), unsupported = true)
                s = JsonObject(target + s.filterKeys { it != "\$ref" })
            }
            val anyOf = (s["anyOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            if (anyOf != null) {
                val outer = JsonObject(s.filterKeys { it != "anyOf" })
                val branches = anyOf.filterNot { isExpression(it) || isNull(it) }
                if (branches.size == 1) {
                    val inner = normalize(branches[0], depth + 1) ?: return null
                    // Outer annotations (the projector hoists description/default/x-cordis there) win.
                    return inner.copy(
                        description = describe(outer) ?: inner.description,
                        default = outer["default"] ?: inner.default,
                        cordis = mergeCordis(inner.cordis, outer.cordis()),
                        raw = JsonObject(inner.raw + outer.filterKeys { it in setOf("minimum", "maximum") }),
                    )
                }
                // A union of string literals is a choice.
                val consts = branches.map { b -> (b["const"] as? JsonPrimitive)?.takeIf { it.isString }?.content }
                if (branches.isNotEmpty() && consts.all { it != null })
                    return Node("string", outer, describe(outer), outer["default"], outer.cordis(), options = consts.filterNotNull())
                return Node(null, outer, describe(outer), outer["default"], outer.cordis(), unsupported = true)
            }
            (s["enum"] as? JsonArray)?.let { e ->
                val values = e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (values.size == e.size && values.isNotEmpty()) return Node("string", s, describe(s), s["default"], s.cordis(), options = values)
            }
            (s["const"] as? JsonPrimitive)?.let { return Node(null, s, describe(s), s["default"], s.cordis(), unsupported = true) }
            val type = typeOf(s)
            return Node(
                type = type, raw = s, description = describe(s), default = s["default"], cordis = s.cordis(),
                properties = s["properties"] as? JsonObject,
                items = s["items"] as? JsonObject,
                unsupported = type == null || (type == "object" && s["properties"] == null),
            )
        }

        private fun isExpression(b: JsonObject) = (b["\$ref"] as? JsonPrimitive)?.contentOrNull == LOADER_EXPRESSION
        private fun isNull(b: JsonObject) = typeOf(b, keepNull = true) == "null" && b["anyOf"] == null

        /** The single non-null `type` (or null when absent / ambiguous). */
        private fun typeOf(s: JsonObject, keepNull: Boolean = false): String? = when (val t = s["type"]) {
            is JsonPrimitive -> t.contentOrNull
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.let { all ->
                if (keepNull && all == listOf("null")) "null" else all.filter { it != "null" }.singleOrNull()
            }
            else -> null
        }

        private fun describe(s: JsonObject): String? {
            val localized = (s.cordis()?.get("descriptions") as? JsonObject)?.let { d ->
                runCatching { LocalizedText(d.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k.lowercase() to it } }.toMap()).get(language) }.getOrNull()
            }
            return (localized ?: (s["description"] as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotBlank() }
        }

        private fun mergeCordis(a: JsonObject?, b: JsonObject?): JsonObject? = when {
            a == null -> b
            b == null -> a
            else -> JsonObject(a + b)
        }
    }

    private fun JsonObject.cordis(): JsonObject? = this["x-cordis"] as? JsonObject
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    // ---- values ---------------------------------------------------------------------------------

    fun valueAt(root: JsonElement, path: List<String>): JsonElement? {
        var cur: JsonElement? = root
        for (k in path) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }

    /** [root] with [path] set to [value]; null removes the key (falls back to the default). Missing objects are created. */
    fun withValue(root: JsonElement, path: List<String>, value: JsonElement?): JsonElement {
        if (path.isEmpty()) return value ?: JsonObject(emptyMap())
        val obj = root as? JsonObject ?: JsonObject(emptyMap())
        val head = path.first()
        val next = if (path.size == 1) value else withValue(obj[head] ?: JsonObject(emptyMap()), path.drop(1), value)
        val map = LinkedHashMap(obj)
        if (next == null) map.remove(head) else map[head] = next
        return JsonObject(map)
    }

    /** The text a field shows: the set value, else blank (the default is the placeholder). */
    fun textOf(root: JsonElement, path: List<String>): String = when (val v = valueAt(root, path)) {
        null, is JsonNull -> ""
        is JsonPrimitive -> v.contentOrNull.orEmpty()
        else -> v.toString()
    }

    /** Parses a number field's text: blank → unset (null), invalid → [Result.failure]. */
    fun parseNumber(text: String, field: FormField.Number): Result<JsonPrimitive?> {
        val t = text.trim()
        if (t.isEmpty()) return Result.success(null)
        val n = t.toDoubleOrNull() ?: return Result.failure(IllegalArgumentException("not a number"))
        if (field.integer && n % 1.0 != 0.0) return Result.failure(IllegalArgumentException("not an integer"))
        if (field.min != null && n < field.min) return Result.failure(IllegalArgumentException("min ${field.min}"))
        if (field.max != null && n > field.max) return Result.failure(IllegalArgumentException("max ${field.max}"))
        return Result.success(if (field.integer) JsonPrimitive(n.toLong()) else JsonPrimitive(n))
    }

    /** One entry per line, blanks dropped. */
    fun parseList(text: String): JsonArray = JsonArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })

    fun listText(root: JsonElement, path: List<String>): String =
        (valueAt(root, path) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n").orEmpty()

    fun boolOf(root: JsonElement, field: FormField.Toggle): Boolean =
        (valueAt(root, field.path) as? JsonPrimitive)?.booleanOrNull ?: field.default ?: false

    /** Integer-valued numbers print without `.0`. */
    fun numberText(root: JsonElement, path: List<String>): String = when (val v = valueAt(root, path)) {
        is JsonPrimitive -> v.longOrNull?.toString() ?: v.doubleOrNull?.let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() } ?: v.contentOrNull.orEmpty()
        else -> ""
    }
}
