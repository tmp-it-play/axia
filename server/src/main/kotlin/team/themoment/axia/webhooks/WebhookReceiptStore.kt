package team.themoment.axia.webhooks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.security.CryptoSupport
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException

enum class WebhookSource {
    DATAGSM,
    GITHUB,
}

data class WebhookReceiptClaim(
    val source: WebhookSource,
    val deliveryId: String,
    val leaseOwner: String,
)

sealed interface WebhookClaimResult {
    data class Acquired(
        val claim: WebhookReceiptClaim,
    ) : WebhookClaimResult

    data object AlreadyCompleted : WebhookClaimResult

    data object Busy : WebhookClaimResult

    data object PayloadMismatch : WebhookClaimResult
}

/**
 * Receipt claims commit before work starts. Projection writes and [complete] must
 * use the same caller-owned JDBC transaction; only a successful projection is final.
 */
class WebhookReceiptStore(
    private val dataSource: DataSource,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Webhook receipt lease must be positive" }
    }

    suspend fun claim(
        source: WebhookSource,
        deliveryId: String,
        eventType: String,
        payloadHash: ByteArray,
        occurredAt: Instant,
        now: Instant,
    ): WebhookClaimResult =
        withContext(ioDispatcher) {
            require(SAFE_IDENTIFIER.matches(deliveryId)) { "Invalid webhook delivery identifier" }
            require(SAFE_IDENTIFIER.matches(eventType)) { "Invalid webhook event type" }
            require(payloadHash.size == 32) { "Webhook payload digest must be SHA-256" }
            dataSource.inTransaction {
                prepareStatement(INSERT_RECEIPT).use { statement ->
                    statement.setString(1, source.name)
                    statement.setString(2, deliveryId)
                    statement.setBytes(3, payloadHash)
                    statement.setString(4, eventType)
                    statement.setInstant(5, occurredAt)
                    statement.setInstant(6, now)
                    statement.setInstant(7, now)
                    statement.executeUpdate()
                }
                val receipt =
                    prepareStatement(LOCK_RECEIPT).use { statement ->
                        statement.setString(1, source.name)
                        statement.setString(2, deliveryId)
                        statement.executeQuery().use { result ->
                            check(result.next()) { "Webhook receipt disappeared" }
                            ReceiptRow(
                                payloadHash = result.getBytes("payload_sha256"),
                                status = result.getString("status"),
                                leaseUntil = result.instantOrNull("lease_until"),
                            )
                        }
                    }
                if (!CryptoSupport.constantTimeEquals(receipt.payloadHash, payloadHash)) {
                    return@inTransaction WebhookClaimResult.PayloadMismatch
                }
                if (receipt.status == "SUCCEEDED") return@inTransaction WebhookClaimResult.AlreadyCompleted
                if (receipt.status == "PROCESSING" && receipt.leaseUntil?.isAfter(now) == true) {
                    return@inTransaction WebhookClaimResult.Busy
                }
                val owner = UUID.randomUUID().toString()
                prepareStatement(CLAIM_RECEIPT).use { statement ->
                    statement.setString(1, owner)
                    statement.setInstant(2, now.plus(leaseDuration))
                    statement.setInstant(3, now)
                    statement.setString(4, source.name)
                    statement.setString(5, deliveryId)
                    check(statement.executeUpdate() == 1) { "Webhook receipt could not be claimed" }
                }
                WebhookClaimResult.Acquired(WebhookReceiptClaim(source, deliveryId, owner))
            }
        }

    /**
     * Keeps a claim alive only while preparing external reads. Both children finish
     * before returning, so no renewal can contend with the caller's projection lock.
     * Projection writes must still acquire their own [lockAndRenew] fence afterwards.
     */
    internal suspend fun <T> withLeaseRenewal(
        claim: WebhookReceiptClaim,
        clock: Clock,
        prepare: suspend () -> T,
    ): T =
        supervisorScope {
            val leaseLost = AtomicBoolean(false)
            val work = async { prepare() }
            val heartbeat =
                launch {
                    while (work.isActive && !leaseLost.get()) {
                        delay((leaseDuration.toMillis() / 4).coerceAtLeast(1))
                        withContext(ioDispatcher) {
                            try {
                                val owned = dataSource.inTransaction { lockAndRenew(this, claim, clock::instant) }
                                check(owned) { "Webhook receipt lease was lost" }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Latch inside the IO block: cancellation on return must not hide
                                // a failed renewal racing with an already completed preparation.
                                leaseLost.set(true)
                                work.cancel(CancellationException("Webhook receipt lease was lost"))
                            }
                        }
                    }
                }
            val prepared =
                try {
                    val result =
                        try {
                            work.await()
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            check(!leaseLost.get()) { "Webhook receipt lease was lost" }
                            throw cancelled
                        }
                    heartbeat.cancelAndJoin()
                    check(!leaseLost.get()) { "Webhook receipt lease was lost" }
                    result
                } finally {
                    withContext(NonCancellable) {
                        work.cancel()
                        heartbeat.cancel()
                        work.join()
                        heartbeat.join()
                    }
                }
            currentCoroutineContext().ensureActive()
            prepared
        }

    /** Locks and renews an owned lease. Call before making projection changes. */
    fun lockAndRenew(
        connection: Connection,
        claim: WebhookReceiptClaim,
        now: Instant,
    ): Boolean = lockAndRenew(connection, claim) { now }

    private fun lockAndRenew(
        connection: Connection,
        claim: WebhookReceiptClaim,
        now: () -> Instant,
    ): Boolean {
        check(!connection.autoCommit) { "Webhook projection requires an explicit transaction" }
        val renewedAt =
            connection.prepareStatement(LOCK_RECEIPT).use { statement ->
                statement.setString(1, claim.source.name)
                statement.setString(2, claim.deliveryId)
                statement.executeQuery().use { result ->
                    if (!result.next() || result.getString("status") != "PROCESSING" ||
                        result.getString("lease_owner") != claim.leaseOwner
                    ) {
                        return false
                    }
                    // Heartbeat time is sampled after the row lock; waiting cannot revive an expired lease.
                    val instant = now()
                    if (result.instantOrNull("lease_until")?.isAfter(instant) != true) return false
                    instant
                }
            }
        connection.prepareStatement(RENEW_RECEIPT).use { statement ->
            statement.setInstant(1, renewedAt.plus(leaseDuration))
            statement.setInstant(2, renewedAt)
            statement.bindClaim(3, claim)
            return statement.executeUpdate() == 1
        }
    }

    /** Must run after projection writes, in that exact transaction. */
    fun complete(
        connection: Connection,
        claim: WebhookReceiptClaim,
        now: Instant,
    ): Boolean {
        check(!connection.autoCommit) { "Webhook completion requires an explicit transaction" }
        connection.prepareStatement(COMPLETE_RECEIPT).use { statement ->
            statement.setInstant(1, now)
            statement.setInstant(2, now)
            statement.bindClaim(3, claim)
            return statement.executeUpdate() == 1
        }
    }

    suspend fun fail(
        claim: WebhookReceiptClaim,
        safeFailureCode: String,
        now: Instant,
    ): Unit =
        withContext(ioDispatcher) {
            require(SAFE_FAILURE_CODE.matches(safeFailureCode)) { "Webhook failure must be a safe code, not exception text" }
            dataSource.inTransaction {
                prepareStatement(FAIL_RECEIPT).use { statement ->
                    statement.setString(1, safeFailureCode)
                    statement.setInstant(2, now)
                    statement.bindClaim(3, claim)
                    statement.executeUpdate()
                }
            }
        }

    private fun PreparedStatement.bindClaim(
        firstIndex: Int,
        claim: WebhookReceiptClaim,
    ) {
        setString(firstIndex, claim.source.name)
        setString(firstIndex + 1, claim.deliveryId)
        setString(firstIndex + 2, claim.leaseOwner)
    }

    private fun PreparedStatement.setInstant(
        index: Int,
        value: Instant,
    ) = setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC))

    private fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

    private class ReceiptRow(
        val payloadHash: ByteArray,
        val status: String,
        val leaseUntil: Instant?,
    )

    private companion object {
        val SAFE_IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val SAFE_FAILURE_CODE = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        const val INSERT_RECEIPT = """
            INSERT INTO webhook_receipts (
                source, delivery_id, payload_sha256, event_type, occurred_at, received_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE delivery_id = VALUES(delivery_id)
        """
        const val LOCK_RECEIPT = """
            SELECT payload_sha256, status, lease_owner, lease_until
            FROM webhook_receipts WHERE source = ? AND delivery_id = ? FOR UPDATE
        """
        const val CLAIM_RECEIPT = """
            UPDATE webhook_receipts
            SET status = 'PROCESSING', lease_owner = ?, lease_until = ?, attempts = attempts + 1,
                last_failure_code = NULL, updated_at = ?
            WHERE source = ? AND delivery_id = ?
        """
        const val RENEW_RECEIPT = """
            UPDATE webhook_receipts SET lease_until = ?, updated_at = ?
            WHERE source = ? AND delivery_id = ? AND lease_owner = ? AND status = 'PROCESSING'
        """
        const val COMPLETE_RECEIPT = """
            UPDATE webhook_receipts
            SET status = 'SUCCEEDED', completed_at = ?, updated_at = ?, lease_owner = NULL,
                lease_until = NULL, last_failure_code = NULL
            WHERE source = ? AND delivery_id = ? AND lease_owner = ? AND status = 'PROCESSING'
        """
        const val FAIL_RECEIPT = """
            UPDATE webhook_receipts
            SET status = 'FAILED', last_failure_code = ?, updated_at = ?, lease_owner = NULL, lease_until = NULL
            WHERE source = ? AND delivery_id = ? AND lease_owner = ? AND status = 'PROCESSING'
        """
    }
}
