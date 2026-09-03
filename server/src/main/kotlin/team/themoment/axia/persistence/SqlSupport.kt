package team.themoment.axia.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate

internal fun PreparedStatement.bind(values: List<Any?>) {
    values.forEachIndexed { index, value ->
        when (value) {
            null -> setNull(index + 1, Types.NULL)
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            is LocalDate -> setDate(index + 1, java.sql.Date.valueOf(value))
            is Enum<*> -> setString(index + 1, value.name)
            is ByteArray -> setBytes(index + 1, value)
            else -> setObject(index + 1, value)
        }
    }
}

internal fun Connection.execute(
    sql: String,
    vararg values: Any?,
): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(values.toList())
        statement.executeUpdate()
    }

internal fun <T> Connection.query(
    sql: String,
    vararg values: Any?,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(values.toList())
        statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
    }

internal fun Connection.insertId(
    sql: String,
    vararg values: Any?,
): Long =
    prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { statement ->
        statement.bind(values.toList())
        statement.executeUpdate()
        statement.generatedKeys.use { result ->
            check(result.next()) { "Database insert did not return an identity" }
            result.getLong(1)
        }
    }

internal fun ResultSet.instant(name: String): Instant? = getTimestamp(name)?.toInstant()

internal fun ResultSet.nullableLong(name: String): Long? = getLong(name).takeUnless { wasNull() }

internal fun ResultSet.nullableInt(name: String): Int? = getInt(name).takeUnless { wasNull() }
