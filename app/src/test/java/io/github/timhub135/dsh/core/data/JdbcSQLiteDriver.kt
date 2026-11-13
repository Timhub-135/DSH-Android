package io.github.timhub135.dsh.core.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Properties

/**
 * Room's SQLiteDriver over sqlite-jdbc, for DAO tests on hosts where Robolectric has no SQLite of its
 * own (its native runtime and sqlite4java do not ship for Linux aarch64). Only what Room uses.
 */
class JdbcSQLiteDriver : SQLiteDriver {
    // The driver directly, not DriverManager: in Robolectric's sandbox class loader its registration is not visible.
    override fun open(fileName: String): SQLiteConnection = JdbcConnection(org.sqlite.JDBC().connect("jdbc:sqlite:$fileName", Properties()))
}

private class JdbcConnection(private val c: Connection) : SQLiteConnection {
    private var inTx = false
    override fun inTransaction() = inTx
    override fun prepare(sql: String): SQLiteStatement {
        val head = sql.trimStart().uppercase()
        when {
            head.startsWith("BEGIN") -> inTx = true
            head.startsWith("COMMIT") || head.startsWith("END") || head.startsWith("ROLLBACK") && !head.startsWith("ROLLBACK TO") -> inTx = false
        }
        return JdbcStatement(c.prepareStatement(sql))
    }
    override fun close() = c.close()
}

private class JdbcStatement(private val ps: PreparedStatement) : SQLiteStatement {
    private var rs: ResultSet? = null
    private var executed = false

    override fun bindBlob(index: Int, value: ByteArray) = ps.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = ps.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)
    override fun bindText(index: Int, value: String) = ps.setString(index, value)
    override fun bindNull(index: Int) = ps.setObject(index, null)

    private fun value(index: Int): Any? = rs!!.getObject(index + 1)
    override fun getBlob(index: Int): ByteArray = rs!!.getBytes(index + 1)
    override fun getDouble(index: Int) = rs!!.getDouble(index + 1)
    override fun getLong(index: Int) = rs!!.getLong(index + 1)
    override fun getText(index: Int): String = rs!!.getString(index + 1)
    override fun isNull(index: Int) = value(index) == null
    /** Read from the prepared statement: Room asks before the first step and after the last one. */
    private val columns: List<String> by lazy { ps.metaData?.let { m -> (1..m.columnCount).map(m::getColumnName) } ?: emptyList() }
    override fun getColumnCount() = columns.size
    override fun getColumnName(index: Int): String = columns[index]
    override fun getColumnType(index: Int) = when (value(index)) {
        null -> 5
        is Int, is Long -> 1
        is Float, is Double -> 2
        is ByteArray -> 4
        else -> 3
    }

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            if (ps.execute()) rs = ps.resultSet else return false
        }
        return rs?.next() ?: false
    }
    override fun reset() { rs?.close(); rs = null; executed = false }
    override fun clearBindings() = ps.clearParameters()
    override fun close() { rs?.close(); ps.close() }
}
