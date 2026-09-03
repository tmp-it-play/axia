package team.themoment.axia.integration.datagsm.events

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import team.themoment.axia.integration.github.GitHubIdentityClientException
import team.themoment.axia.security.CryptoSupport
import team.themoment.axia.webhooks.WebhookClaimResult
import team.themoment.axia.webhooks.WebhookReceiptClaim
import team.themoment.axia.webhooks.WebhookReceiptStore
import team.themoment.axia.webhooks.WebhookSource
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

class DataGsmEventService(
    private val signature: DataGsmEventSignature,
    private val decoder: DataGsmEventDecoder,
    private val receipts: WebhookReceiptStore,
    private val processor: JdbcDataGsmEventProcessor,
    private val clock: Clock,
) {
    suspend fun receive(
        rawBody: ByteArray,
        signatureHeader: String?,
    ): DataGsmEventResult {
        if (!signature.verify(rawBody, signatureHeader)) return DataGsmEventResult.InvalidSignature
        val envelope =
            try {
                decoder.envelope(rawBody)
            } catch (_: DataGsmEventException) {
                return DataGsmEventResult.InvalidEnvelope
            }
        val claim =
            when (
                val result =
                    receipts.claim(
                        source = WebhookSource.DATAGSM,
                        deliveryId = envelope.id,
                        eventType = envelope.type,
                        payloadHash = CryptoSupport.sha256(rawBody),
                        occurredAt = envelope.dispatchedAt,
                        now = clock.instant(),
                    )
            ) {
                is WebhookClaimResult.Acquired -> result.claim
                WebhookClaimResult.AlreadyCompleted -> return DataGsmEventResult.Accepted
                WebhookClaimResult.Busy -> return DataGsmEventResult.RetryableFailure
                WebhookClaimResult.PayloadMismatch -> return DataGsmEventResult.PayloadMismatch
            }

        return try {
            val event = decoder.decode(rawBody, envelope)
            processor.process(claim, event)
            DataGsmEventResult.Accepted
        } catch (exception: CancellationException) {
            withContext(NonCancellable) { failSafely(claim, DataGsmEventFailure.ProcessingFailed.name) }
            throw exception
        } catch (exception: DataGsmEventException) {
            logger.warn("DataGSM event {} failed: {}", claim.deliveryId, exception.safeFailureCode)
            failSafely(claim, exception.safeFailureCode)
            if (exception.failure == DataGsmEventFailure.InvalidProviderContract) {
                DataGsmEventResult.UnsupportedProviderContract
            } else {
                DataGsmEventResult.RetryableFailure
            }
        } catch (_: GitHubIdentityClientException) {
            logger.warn("DataGSM event {} requires GitHub verification retry", claim.deliveryId)
            failSafely(claim, DataGsmEventFailure.ExternalVerificationUnavailable.name)
            DataGsmEventResult.RetryableFailure
        } catch (exception: Exception) {
            logger.error("DataGSM event {} processing failed ({})", claim.deliveryId, exception.javaClass.simpleName)
            failSafely(claim, DataGsmEventFailure.ProcessingFailed.name)
            DataGsmEventResult.RetryableFailure
        }
    }

    private suspend fun failSafely(
        claim: WebhookReceiptClaim,
        failureCode: String,
    ) {
        try {
            receipts.fail(claim, failureCode, clock.instant())
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // A database outage leaves an expiring PROCESSING lease, never a false success.
            logger.error("DataGSM event {} failure receipt could not be persisted", claim.deliveryId)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DataGsmEventService::class.java)
    }
}

enum class DataGsmEventResult {
    Accepted,
    InvalidSignature,
    InvalidEnvelope,
    PayloadMismatch,
    UnsupportedProviderContract,
    RetryableFailure,
}
