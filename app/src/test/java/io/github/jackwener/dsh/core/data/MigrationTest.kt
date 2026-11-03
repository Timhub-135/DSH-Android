package io.github.jackwener.dsh.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room migrations against real SQLite (sqlite-jdbc): builds a released database from its frozen
 * schema export (test resources `room-schemas/<version>.json`), fills it, runs the migration SQL and
 * checks the result against the schema Room exports for the current version (`app/schemas/…`),
 * column by column the way Room validates on open, and that every row survived.
 */
class MigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Released versions are frozen test resources; the current one is Room's own export. */
    private fun schema(version: Int): JsonObject {
        val text = if (version < DB_VERSION) javaClass.classLoader!!.getResource("room-schemas/$version.json")!!.readText()
        else File("schemas/io.github.jackwener.dsh.core.data.DshDatabase/$version.json").readText()
        return json.parseToJsonElement(text).jsonObject["database"]!!.jsonObject
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun create(version: Int): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (e in schema(version)["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e["tableName"]!!.jsonPrimitive.content
            c.exec(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { i -> c.exec(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        return c
    }

    /** (name, declared type, notNull, pk position) per column, as Room's TableInfo compares them. */
    private fun Connection.columns(table: String): Set<List<Any>> = createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildSet { while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk"))) }
        }
    }

    private fun expectedColumns(version: Int, table: String): Set<List<Any>> {
        val e = schema(version)["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == table }
        val pk = e["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        return e["fields"]!!.jsonArray.map { it.jsonObject }.map { f ->
            val name = f["columnName"]!!.jsonPrimitive.content
            listOf(name, f["affinity"]!!.jsonPrimitive.content.uppercase(), f["notNull"]?.jsonPrimitive?.boolean ?: false, pk.indexOf(name) + 1)
        }.toSet()
    }

    @Test fun `the current version is migrated from every released version, not recreated`() {
        assertEquals(DB_VERSION, schema(DB_VERSION)["version"]!!.jsonPrimitive.int)
        var v = 2
        while (v < DB_VERSION) {
            val step = Migrations.ALL.firstOrNull { it.startVersion == v } ?: error("no migration from $v")
            v = step.endVersion
        }
        assertFalse("v2 databases must be migrated", 2 in Migrations.DESTRUCTIVE_FROM)
    }

    @Test fun `2 to 3 adds modeId and keeps every conversation`() {
        create(2).use { c ->
            c.exec("INSERT INTO sessions (id, title, createdAt, updatedAt, ruleId, running, parentId, pinned, archived, unread, preset) " +
                "VALUES ('s1', '整理下载', 1, 2, NULL, 0, NULL, 1, 0, 0, 'read-only'), ('s2', 'child', 3, 4, NULL, 0, 's1', 0, 0, 1, NULL)")
            c.exec("INSERT INTO events (sessionId, seq, type, json, time) VALUES ('s1', 0, 'user/message', '{}', 5), ('s1', -7, 'app/error', '{}', 6)")
            c.exec("INSERT INTO rules (specJson, enabled, createdAt, lastFiredAt) VALUES ('{}', 1, 7, NULL)")
            c.exec("INSERT INTO usage (day, inputTokens, outputTokens, cachedTokens) VALUES ('2026-10-30', 10, 20, 30)")

            Migrations.SQL_2_3.forEach { c.exec(it) }

            for (table in listOf("sessions", "events", "rules", "rule_runs", "usage"))
                assertEquals("table $table matches the v3 schema", expectedColumns(3, table), c.columns(table))
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, title, pinned, preset, modeId FROM sessions ORDER BY id").use { rs ->
                    assertTrue(rs.next()); assertEquals("s1", rs.getString("id")); assertEquals("整理下载", rs.getString("title"))
                    assertEquals(1, rs.getInt("pinned")); assertEquals("read-only", rs.getString("preset")); assertNull(rs.getString("modeId"))
                    assertTrue(rs.next()); assertEquals("s2", rs.getString("id")); assertNull(rs.getString("modeId"))
                    assertFalse(rs.next())
                }
                st.executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM rules").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT inputTokens FROM usage").use { rs -> rs.next(); assertEquals(10, rs.getInt(1)) }
            }
            // The new column is writable through the DAO's query shape.
            c.exec("UPDATE sessions SET modeId = 'chat' WHERE id = 's1'")
        }
    }

    @Test fun `3 to 4 adds muted, off for every existing conversation`() {
        create(3).use { c ->
            c.exec("INSERT INTO sessions (id, title, createdAt, updatedAt, ruleId, running, parentId, pinned, archived, unread, preset, modeId) " +
                "VALUES ('s1', '整理下载', 1, 2, NULL, 0, NULL, 1, 0, 0, NULL, 'chat')")
            Migrations.SQL_3_4.forEach { c.exec(it) }
            for (table in listOf("sessions", "events", "rules", "rule_runs", "usage"))
                assertEquals("table $table matches the v4 schema", expectedColumns(4, table), c.columns(table))
            c.createStatement().use { st ->
                st.executeQuery("SELECT title, modeId, muted FROM sessions").use { rs ->
                    assertTrue(rs.next()); assertEquals("整理下载", rs.getString("title")); assertEquals("chat", rs.getString("modeId")); assertEquals(0, rs.getInt("muted"))
                }
            }
            c.exec("UPDATE sessions SET muted = 1 WHERE id = 's1'")
        }
    }

    @Test fun `4 to 5 adds the conversation's model, unset (the settings default) for every existing conversation`() {
        create(4).use { c ->
            c.exec("INSERT INTO sessions (id, title, createdAt, updatedAt, ruleId, running, parentId, pinned, archived, unread, preset, modeId, muted) " +
                "VALUES ('s1', '整理下载', 1, 2, NULL, 0, NULL, 1, 0, 0, 'read-only', 'chat', 1)")
            c.exec("INSERT INTO events (sessionId, seq, type, json, time) VALUES ('s1', 0, 'user/message', '{}', 5)")
            Migrations.SQL_4_5.forEach { c.exec(it) }
            for (table in listOf("sessions", "events", "rules", "rule_runs", "usage"))
                assertEquals("table $table matches the v$DB_VERSION schema", expectedColumns(DB_VERSION, table), c.columns(table))
            c.createStatement().use { st ->
                st.executeQuery("SELECT title, preset, modeId, muted, modelProvider, modelId, modelEffort FROM sessions").use { rs ->
                    assertTrue(rs.next()); assertEquals("整理下载", rs.getString("title")); assertEquals("read-only", rs.getString("preset"))
                    assertEquals("chat", rs.getString("modeId")); assertEquals(1, rs.getInt("muted"))
                    assertNull(rs.getString("modelProvider")); assertNull(rs.getString("modelId")); assertNull(rs.getString("modelEffort"))
                }
                st.executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            }
            c.exec("UPDATE sessions SET modelProvider = 'kimi', modelId = 'kimi-k3', modelEffort = NULL WHERE id = 's1'")
        }
    }

    @Test fun `a fresh database has the same tables as a migrated one`() {
        create(DB_VERSION).use { fresh ->
            create(2).use { migrated ->
                (Migrations.SQL_2_3 + Migrations.SQL_3_4 + Migrations.SQL_4_5).forEach { migrated.exec(it) }
                for (table in listOf("sessions", "events", "rules", "rule_runs", "usage")) assertEquals(table, fresh.columns(table), migrated.columns(table))
            }
        }
    }
}
