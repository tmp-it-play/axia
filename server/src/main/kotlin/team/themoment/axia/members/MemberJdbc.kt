package team.themoment.axia.members

import io.ktor.http.HttpStatusCode
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.persistence.inTransaction
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

internal fun <T> DataSource.memberTransaction(block: Connection.() -> T): T {
    var attempt = 0
    while (true) {
        try {
            return inTransaction(block = block)
        } catch (failure: SQLException) {
            attempt += 1
            if (attempt >= 3 || (failure.errorCode !in setOf(1205, 1213) && failure.sqlState != "40001")) {
                throw failure
            }
        }
    }
}

internal fun PreparedStatement.setMemberInstant(
    index: Int,
    instant: Instant,
) {
    setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))
}

internal fun ResultSet.memberInstant(column: String): Instant = getObject(column, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)

internal fun ResultSet.memberNullableInstant(column: String): Instant? =
    getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

internal fun ResultSet.memberNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

internal fun ResultSet.memberNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

internal fun memberNotFound(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "구성원을 찾을 수 없습니다.")

internal fun memberConflict(message: String): ApiFailure = ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, message)
