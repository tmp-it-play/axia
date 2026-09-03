package team.themoment.axia.scheduling

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.query
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

data class ProviderHealth(
    val state: String,
    val generation: Long,
    val retryAt: Instant?,
) {
    val readOnly: Boolean get() = state != "HEALTHY"
}

/** A successful individual read never unlocks writes; a complete reconciliation must finish first. */
class ProviderHealthStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun current(): ProviderHealth =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction(readOnly = true) {
                query("SELECT * FROM provider_health WHERE provider='GITHUB'") {
                    ProviderHealth(it.getString("state"), it.getLong("failure_generation"), it.instant("retry_at"))
                }.single()
            }
        }

    suspend fun markReadFailure(failure: GitHubApiException): Unit =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                execute(
                    "UPDATE provider_health SET state='DEGRADED',failure_generation=failure_generation+1,retry_at=?,changed_at=? WHERE provider='GITHUB'",
                    failure.retryAt,
                    clock.instant(),
                )
            }
        }

    suspend fun beginRecovery(): Long =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                val generation =
                    query(
                        "SELECT failure_generation FROM provider_health WHERE provider='GITHUB' FOR UPDATE",
                    ) { it.getLong(1) }.single()
                execute("UPDATE provider_health SET state='RECOVERING',changed_at=? WHERE provider='GITHUB'", clock.instant())
                generation
            }
        }

    suspend fun completeRecovery(expectedGeneration: Long): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                execute(
                    "UPDATE provider_health SET state='HEALTHY',retry_at=NULL,changed_at=? WHERE provider='GITHUB' AND failure_generation=?",
                    clock.instant(),
                    expectedGeneration,
                ) == 1
            }
        }

    suspend fun requireWritable() {
        if (current().readOnly) {
            throw ApiFailure(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorCode.GitHubReadOnly,
                "GitHub 연결이 복구될 때까지 읽기 전용입니다. 캐시된 태스크는 계속 볼 수 있습니다.",
            )
        }
    }
}
