package team.themoment.axia.webhooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.integration.github.GitHubWebhookDelivery
import team.themoment.axia.integration.github.GitHubWebhookDeliveryClient
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.query
import team.themoment.axia.scheduling.JobLease
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

data class GitHubRedeliverySummary(
    val inspected: Int,
    val requested: Int,
    val awaitingConfirmation: Int,
)

/** A failed POST is not blindly repeated; a new failed provider attempt is required after an uncertain outcome. */
class GitHubWebhookRedeliveryService(
    private val dataSource: DataSource,
    private val github: GitHubWebhookDeliveryClient,
    private val installationId: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelay: Duration = Duration.ofHours(6),
) {
    init {
        require(installationId > 0 && !retryDelay.isNegative && !retryDelay.isZero)
    }

    suspend fun run(lease: JobLease): GitHubRedeliverySummary {
        val deliveries = github.deliveries().filter { it.installationId == installationId }.groupBy { it.guid }
        var requested = 0
        var awaiting = 0
        for ((guid, attempts) in deliveries) {
            val latest = attempts.maxWith(compareBy<GitHubWebhookDelivery> { it.deliveredAt }.thenBy { it.id })
            val decision =
                withContext(Dispatchers.IO) {
                    dataSource.inTransaction {
                        val now = clock.instant()
                        lease.requireOwned(this, now)
                        val receiptSucceeded =
                            query(
                                "SELECT 1 FROM webhook_receipts WHERE source='GITHUB' AND delivery_id=? AND status='SUCCEEDED'",
                                guid,
                            ) { it.getInt(1) }.isNotEmpty()
                        val succeeded = receiptSucceeded || attempts.any { it.succeeded }
                        val state =
                            query("SELECT * FROM github_webhook_redeliveries WHERE delivery_guid=? FOR UPDATE", guid) {
                                RedeliveryState(
                                    it.getLong("source_delivery_id"),
                                    requireNotNull(it.instant("source_delivered_at")),
                                    it.getString("status"),
                                    it.instant("retry_at"),
                                )
                            }.singleOrNull()
                        if (succeeded || state?.status == "DELIVERED") {
                            execute(
                                """INSERT INTO github_webhook_redeliveries(delivery_guid,source_delivery_id,source_delivered_at,status,last_seen_at)
                               VALUES(?,?,?,'DELIVERED',?) ON DUPLICATE KEY UPDATE status='DELIVERED',
                               last_seen_at=VALUES(last_seen_at),retry_at=NULL,last_failure_code=NULL""",
                                guid,
                                latest.id,
                                latest.deliveredAt,
                                now,
                            )
                            return@inTransaction Decision.Done
                        }
                        if (state != null) execute("UPDATE github_webhook_redeliveries SET last_seen_at=? WHERE delivery_guid=?", now, guid)
                        if (latest.throttled || latest.deliveredAt.isAfter(now.minusSeconds(30))) return@inTransaction Decision.Wait
                        if (state?.retryAt?.isAfter(now) == true) return@inTransaction Decision.Wait
                        val newAttempt =
                            state == null || (latest.id != state.sourceId && !latest.deliveredAt.isBefore(state.sourceDeliveredAt))
                        if (!newAttempt && state.status != "RETRYABLE") return@inTransaction Decision.Wait
                        execute(
                            """INSERT INTO github_webhook_redeliveries(delivery_guid,source_delivery_id,source_delivered_at,status,requested_at,last_seen_at)
                           VALUES(?,?,?,'REQUESTING',?,?) ON DUPLICATE KEY UPDATE source_delivery_id=VALUES(source_delivery_id),
                           source_delivered_at=VALUES(source_delivered_at),status='REQUESTING',requested_at=VALUES(requested_at),
                           last_seen_at=VALUES(last_seen_at),retry_at=NULL,last_failure_code=NULL""",
                            guid,
                            latest.id,
                            latest.deliveredAt,
                            now,
                            now,
                        )
                        Decision.Request
                    }
                }
            if (decision != Decision.Request) {
                if (decision == Decision.Wait) awaiting++
                continue
            }
            try {
                // The durable intent is already committed. Other lease owners will not repeat this source attempt.
                github.redeliver(latest.id)
                finish(lease, guid, latest.id, "ACCEPTED", null, null)
                requested++
            } catch (failure: GitHubApiException) {
                val uncertain = failure.outcomeUncertain
                finish(
                    lease,
                    guid,
                    latest.id,
                    if (uncertain) "UNCERTAIN" else "RETRYABLE",
                    failure.retryAt ?: clock.instant().plus(retryDelay),
                    if (uncertain) "OutcomeUncertain" else "RequestRejected",
                )
                // Rate limiting and provider outages stop the batch; the next scheduled run reads metadata again.
                throw failure
            }
        }
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                val now = clock.instant()
                lease.requireOwned(this, now)
                // GitHub exposes the last three days; retain a wider safety margin, not an indefinite write audit.
                execute("DELETE FROM github_webhook_redeliveries WHERE last_seen_at<?", now.minus(Duration.ofDays(7)))
            }
        }
        return GitHubRedeliverySummary(deliveries.size, requested, awaiting)
    }

    private suspend fun finish(
        lease: JobLease,
        guid: String,
        sourceId: Long,
        status: String,
        retryAt: Instant?,
        failureCode: String?,
    ) = withContext(Dispatchers.IO) {
        dataSource.inTransaction {
            lease.requireOwned(this, clock.instant())
            execute(
                "UPDATE github_webhook_redeliveries SET status=?,retry_at=?,last_failure_code=? WHERE delivery_guid=? AND source_delivery_id=? AND status='REQUESTING'",
                status,
                retryAt,
                failureCode,
                guid,
                sourceId,
            )
        }
    }

    private enum class Decision { Done, Wait, Request }

    private data class RedeliveryState(
        val sourceId: Long,
        val sourceDeliveredAt: Instant,
        val status: String,
        val retryAt: Instant?,
    )
}
