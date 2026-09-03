package team.themoment.axia.scheduling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

class MaintenanceService(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun cleanup(lease: JobLease): Unit =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                val now = clock.instant()
                lease.requireOwned(this, now)
                execute("DELETE FROM oauth_transactions WHERE expires_at<?", now.minus(Duration.ofDays(1)))
                // Consumed refresh digests remain for the entire session lifetime so replay detection survives rotation.
                execute(
                    "DELETE r FROM refresh_credentials r JOIN desktop_sessions s ON s.id=r.session_id WHERE s.absolute_expires_at<=?",
                    now,
                )
                execute("DELETE FROM desktop_sessions WHERE absolute_expires_at<=?", now)
                // Mutation requests carry a fixed issuance time and are rejected after their 24-hour window.
                execute("DELETE FROM external_write_operations WHERE expires_at<=?", now)
            }
        }
}
