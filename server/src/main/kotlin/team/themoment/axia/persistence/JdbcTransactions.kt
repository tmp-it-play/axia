package team.themoment.axia.persistence

import java.sql.Connection
import javax.sql.DataSource

/**
 * Runs [block] on one JDBC connection and commits it atomically.
 *
 * This function is blocking. Callers running on an application event loop must dispatch it to a
 * blocking/IO executor.
 */
fun <T> DataSource.inTransaction(
    isolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED,
    readOnly: Boolean = false,
    block: Connection.() -> T,
): T {
    require(isolationLevel in supportedIsolationLevels) {
        "Unsupported JDBC transaction isolation level: $isolationLevel"
    }

    return connection.use { connection ->
        connection.transactionIsolation = isolationLevel
        connection.isReadOnly = readOnly
        connection.autoCommit = false

        try {
            val result = connection.block()
            connection.commit()
            result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }
}

private val supportedIsolationLevels =
    setOf(
        Connection.TRANSACTION_READ_UNCOMMITTED,
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE,
    )
