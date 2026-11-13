package io.github.timhub135.dsh.core.extensions

import io.github.timhub135.dsh.core.engine.RpcJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaFormTest {
    /** Shaped like app-boot's projector output: value positions wrapped with the loader-expression alternative. */
    private val schema = RpcJson.parseToJsonElement("""
    {
      "type": "object",
      "properties": {
        "endpoint": { "description": "Base URL", "default": "https://api.example.com",
                      "anyOf": [ { "type": ["string", "null"] }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "apiKey": { "x-cordis": { "role": "secret", "descriptions": { "en": "API key", "zh": "API 密钥" } },
                    "anyOf": [ { "type": ["string", "null"] }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "mode": { "default": "fast",
                  "anyOf": [ { "anyOf": [ { "const": "fast" }, { "const": "careful" }, { "type": "null" } ] }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "limit": { "default": 10, "anyOf": [ { "type": ["integer", "null"], "minimum": 1, "maximum": 50 }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "ratio": { "type": "number" },
        "enabled": { "default": true, "anyOf": [ { "type": ["boolean", "null"] }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "engines": { "anyOf": [ { "type": ["array", "null"], "items": { "anyOf": [ { "type": "string" }, { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] } },
                                { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "proxy": { "x-cordis": { "collapse": true },
                   "anyOf": [ { "type": ["object", "null"], "properties": {
                       "host": { "type": "string" }, "port": { "type": "integer" } } },
                     { "${'$'}ref": "#/${'$'}defs/loaderExpression" } ] },
        "secretHidden": { "type": "string", "x-cordis": { "hidden": true } },
        "union": { "anyOf": [ { "type": "string" }, { "type": "object", "properties": { "a": { "type": "string" } } } ] },
        "shared": { "${'$'}ref": "#/${'$'}defs/shared" },
        "dict": { "type": "object", "additionalProperties": { "type": "string" } }
      },
      "${'$'}defs": {
        "loaderExpression": { "type": "object", "properties": { "__jsExpr": { "type": "string" } } },
        "shared": { "type": "boolean", "description": "From defs" }
      }
    }
    """).jsonObject

    private fun fields(language: String = "en", overridden: List<String> = emptyList()) = SchemaForm.fields(schema, language, overridden).associateBy { it.path.last() }

    @Test fun `projector wrappers unwrap to plain controls`() {
        val f = fields()
        assertEquals(FormField.Text(listOf("endpoint"), "endpoint", "Base URL", false, "https://api.example.com"), f["endpoint"])
        val key = f["apiKey"] as FormField.Text
        assertTrue(key.secret)
        assertEquals("API key", key.description)
        assertEquals(listOf("fast", "careful"), (f["mode"] as FormField.Text).options)
        assertEquals("fast", (f["mode"] as FormField.Text).default)
        val limit = f["limit"] as FormField.Number
        assertTrue(limit.integer); assertEquals(1.0, limit.min); assertEquals(50.0, limit.max); assertEquals(10.0, limit.default)
        assertFalse((f["ratio"] as FormField.Number).integer)
        assertEquals(true, (f["enabled"] as FormField.Toggle).default)
        assertTrue(f["engines"] is FormField.StringList)
        assertEquals("From defs", (f["shared"] as FormField.Toggle).description)
    }

    @Test fun `localized descriptions follow the language`() {
        assertEquals("API 密钥", (fields("zh-CN")["apiKey"] as FormField.Text).description)
    }

    @Test fun `nested objects are groups, hidden fields are skipped, unions and dictionaries are code`() {
        val f = fields()
        val proxy = f["proxy"] as FormField.Group
        assertTrue(proxy.collapsed)
        assertEquals(listOf(listOf("proxy", "host"), listOf("proxy", "port")), proxy.fields.map { it.path })
        assertNull(f["secretHidden"])
        assertTrue(f["union"] is FormField.Raw)
        assertTrue(f["dict"] is FormField.Raw)
    }

    @Test fun `overridden paths are locked, star locks everything`() {
        assertTrue(fields(overridden = listOf("endpoint"))["endpoint"]!!.locked)
        assertFalse(fields(overridden = listOf("endpoint"))["apiKey"]!!.locked)
        assertTrue((fields(overridden = listOf("proxy"))["proxy"] as FormField.Group).fields.all { it.locked })
        assertTrue(fields(overridden = listOf("*")).values.all { it.locked })
    }

    @Test fun `a non-object root is one code field`() {
        val f = SchemaForm.fields(RpcJson.parseToJsonElement("""{"type":"string"}""").jsonObject)
        assertEquals(1, f.size)
        assertTrue(f[0] is FormField.Raw)
        assertEquals(emptyList<String>(), f[0].path)
    }

    @Test fun `values are set by path and unset keys fall back to defaults`() {
        var v: kotlinx.serialization.json.JsonElement = RpcJson.parseToJsonElement("""{"endpoint":"x","proxy":{"host":"h"}}""")
        v = SchemaForm.withValue(v, listOf("proxy", "port"), JsonPrimitive(8080))
        v = SchemaForm.withValue(v, listOf("endpoint"), null)
        v = SchemaForm.withValue(v, listOf("new", "deep"), JsonPrimitive(true))
        assertEquals("""{"proxy":{"host":"h","port":8080},"new":{"deep":true}}""", v.toString())
        assertEquals("h", SchemaForm.textOf(v, listOf("proxy", "host")))
        assertEquals("8080", SchemaForm.numberText(v, listOf("proxy", "port")))
        assertEquals("", SchemaForm.textOf(v, listOf("endpoint")))
    }

    @Test fun `number and list parsing`() {
        val limit = fields()["limit"] as FormField.Number
        assertEquals(JsonPrimitive(5L), SchemaForm.parseNumber(" 5 ", limit).getOrThrow())
        assertNull(SchemaForm.parseNumber("", limit).getOrThrow())
        assertTrue(SchemaForm.parseNumber("5.5", limit).isFailure)
        assertTrue(SchemaForm.parseNumber("0", limit).isFailure)
        assertTrue(SchemaForm.parseNumber("abc", limit).isFailure)
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), SchemaForm.parseList("a\n\n b \n"))
        assertEquals("a\nb", SchemaForm.listText(JsonObject(mapOf("l" to SchemaForm.parseList("a\nb"))), listOf("l")))
    }
}
