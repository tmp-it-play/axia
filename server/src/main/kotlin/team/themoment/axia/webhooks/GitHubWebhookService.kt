package team.themoment.axia.webhooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.integration.github.GitHubSyncService
import team.themoment.axia.integration.github.long
import team.themoment.axia.integration.github.objectOrNull
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.security.CryptoSupport
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException

enum class GitHubWebhookResult { Accepted, InvalidSignature, InvalidEnvelope, PayloadMismatch, RetryableFailure }

class GitHubWebhookSignature(
    secret: String,
) {
    private val key = secret.toByteArray(Charsets.UTF_8)

    init {
        require(key.size >= 32) { "GitHub webhook secret must contain at least 32 UTF-8 bytes" }
    }

    fun verify(
        rawBody: ByteArray,
        signature: String?,
    ): Boolean {
        if (signature == null || !Regex("sha256=[0-9a-f]{64}").matches(signature)) return false
        val supplied =
            signature
                .removePrefix("sha256=")
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        return CryptoSupport.constantTimeEquals(CryptoSupport.hmacSha256(key, rawBody), supplied)
    }

    override fun toString(): String = "GitHubWebhookSignature([REDACTED])"
}

class GitHubWebhookService(
    private val dataSource: DataSource,
    private val signature: GitHubWebhookSignature,
    private val receipts: WebhookReceiptStore,
    private val sync: GitHubSyncService,
    private val installationId: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(installationId > 0)
    }

    suspend fun receive(
        rawBody: ByteArray,
        signatureHeader: String?,
        deliveryId: String?,
        eventType: String?,
    ): GitHubWebhookResult {
        if (!signature.verify(rawBody, signatureHeader)) return GitHubWebhookResult.InvalidSignature
        if (deliveryId == null || !DELIVERY_ID.matches(deliveryId) || eventType == null ||
            !EVENT_TYPE.matches(eventType)
        ) {
            return GitHubWebhookResult.InvalidEnvelope
        }
        val payload =
            try {
                val decoded =
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(
                            CodingErrorAction.REPORT,
                        ).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(rawBody))
                        .toString()
                Json.parseToJsonElement(decoded) as? JsonObject ?: return GitHubWebhookResult.InvalidEnvelope
            } catch (_: Exception) {
                return GitHubWebhookResult.InvalidEnvelope
            }
        // Never accept a different installation as a source of Axia state.
        if (eventType != "ping" &&
            payload.objectOrNull("installation")?.long("id") != installationId
        ) {
            return GitHubWebhookResult.InvalidEnvelope
        }
        val claim =
            when (
                val result =
                    receipts.claim(
                        WebhookSource.GITHUB,
                        deliveryId,
                        eventType,
                        CryptoSupport.sha256(rawBody),
                        clock.instant(),
                        clock.instant(),
                    )
            ) {
                is WebhookClaimResult.Acquired -> result.claim
                WebhookClaimResult.AlreadyCompleted -> return GitHubWebhookResult.Accepted
                WebhookClaimResult.PayloadMismatch -> return GitHubWebhookResult.PayloadMismatch
                WebhookClaimResult.Busy -> return GitHubWebhookResult.RetryableFailure
            }
        return try {
            val batch = sync.prepareWebhook(eventType, payload)
            withContext(Dispatchers.IO) {
                dataSource.inTransaction {
                    val now = clock.instant()
                    check(receipts.lockAndRenew(this, claim, now)) { "Webhook claim lost" }
                    sync.apply(this, batch, now)
                    check(receipts.complete(this, claim, now)) { "Webhook claim lost" }
                }
            }
            GitHubWebhookResult.Accepted
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recordFailure(claim, "ProcessingCancelled") }
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(
                claim,
                if (failure is GitHubApiException &&
                    failure.invalidContract
                ) {
                    "InvalidProviderContract"
                } else {
                    "ProcessingFailed"
                },
            )
            GitHubWebhookResult.RetryableFailure
        }
    }

    private suspend fun recordFailure(
        claim: WebhookReceiptClaim,
        code: String,
    ) {
        try {
            receipts.fail(claim, code, clock.instant())
        } catch (_: Exception) {
            logger.error("GitHub webhook failure receipt unavailable delivery={}", claim.deliveryId)
        }
        logger.warn("GitHub webhook processing failed delivery={} code={}", claim.deliveryId, code)
    }

    private companion object {
        val DELIVERY_ID = Regex("[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")
        val EVENT_TYPE = Regex("[a-z][a-z0-9_]{0,63}")
        val logger = LoggerFactory.getLogger(GitHubWebhookService::class.java)
    }
}
