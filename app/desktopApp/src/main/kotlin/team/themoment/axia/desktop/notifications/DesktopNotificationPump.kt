package team.themoment.axia.desktop.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import team.themoment.axia.desktop.auth.DesktopApiException
import team.themoment.axia.desktop.auth.DesktopApiResponse
import team.themoment.axia.desktop.auth.DesktopAuthApi
import team.themoment.axia.desktop.auth.DesktopAuthController
import team.themoment.axia.desktop.auth.DesktopAuthState
import team.themoment.axia.desktop.auth.DesktopSessionUnavailableException
import team.themoment.axia.notifications.NativeNotificationAckRequest
import team.themoment.axia.notifications.NativeNotificationClaimResponse
import team.themoment.axia.notifications.NotificationSummary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class DesktopNotificationStatus {
    Stopped,
    WaitingForSession,
    Active,
    DeliveryUnavailable,
    Retrying,
}

/**
 * Delivers while this process is alive. Native receipt is independent of the in-app unread state.
 * The callback must return true only after the OS accepted dispatch; it owns UI-thread marshalling.
 * Native dispatch and server acknowledgement cannot form one transaction: a process crash between
 * them may cause redelivery. Within a running pump, an uncertain acknowledgement is retried without
 * dispatching the same notification again.
 */
class DesktopNotificationPump internal constructor(
    private val authState: StateFlow<DesktopAuthState>,
    private val request: suspend (String, String, String?) -> DesktopApiResponse,
    private val notify: suspend (NotificationSummary) -> Boolean,
    scope: CoroutineScope,
    private val pollIntervalMillis: Long,
) : AutoCloseable {
    constructor(
        authController: DesktopAuthController,
        notify: suspend (NotificationSummary) -> Boolean,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        pollIntervalMillis: Long = 30_000,
    ) : this(authController.state, authController::request, notify, scope, pollIntervalMillis)

    private val ownedScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val deliveryMutex = Mutex()
    private val mutableStatus = MutableStateFlow(DesktopNotificationStatus.Stopped)
    val status: StateFlow<DesktopNotificationStatus> = mutableStatus.asStateFlow()
    private var deliverySessionId: String? = null
    private var pendingAcknowledgement: NativeNotificationAckRequest? = null
    private var nativeDeliveryUnavailable = false
    private val recentlyDispatched = linkedSetOf<Long>()

    init {
        require(pollIntervalMillis > 0) { "The notification poll interval must be positive" }
    }

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        ownedScope.launch {
            authState.map { (it as? DesktopAuthState.SignedIn)?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) {
                    deliveryMutex.withLock { resetSession(null) }
                    mutableStatus.value = DesktopNotificationStatus.WaitingForSession
                    return@collectLatest
                }
                while (currentCoroutineContext().isActive) {
                    pollOnce(sessionId)
                    delay(pollIntervalMillis)
                }
            }
        }
    }

    internal suspend fun pollOnce(sessionId: String) {
        deliveryMutex.withLock {
            if (closed.get()) return
            if (deliverySessionId != sessionId) resetSession(sessionId)
            try {
                requireSession(sessionId)
                mutableStatus.value = activeStatus()
                pendingAcknowledgement?.let { acknowledge(sessionId, it) }
                requireSession(sessionId)
                val claim = DesktopAuthApi.decode<NativeNotificationClaimResponse>(request("POST", "/v1/notifications/native/claim", null))
                requireSession(sessionId)
                validateClaim(claim)
                val claimId = claim.claimId ?: return
                var deliveryUnavailable = false
                for (notification in claim.notifications) {
                    requireSession(sessionId)
                    val dispatched =
                        notification.id in recentlyDispatched ||
                            try {
                                notify(notification)
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Exception) {
                                false
                            }
                    if (!dispatched) {
                        deliveryUnavailable = true
                        nativeDeliveryUnavailable = true
                        continue
                    }
                    recentlyDispatched.add(notification.id)
                    // Bounded per-process duplicate suppression after a stale claim. Confirmed IDs
                    // are removed immediately; the server owns durable delivery deduplication.
                    while (recentlyDispatched.size > MAXIMUM_RECENT_DISPATCHES) {
                        recentlyDispatched.remove(recentlyDispatched.first())
                    }
                    val acknowledgement = NativeNotificationAckRequest(claimId, setOf(notification.id))
                    pendingAcknowledgement = acknowledgement
                    requireSession(sessionId)
                    acknowledge(sessionId, acknowledgement)
                }
                nativeDeliveryUnavailable = deliveryUnavailable
                mutableStatus.value = activeStatus()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: DesktopSessionUnavailableException) {
                mutableStatus.value = if (closed.get()) DesktopNotificationStatus.Stopped else DesktopNotificationStatus.WaitingForSession
            } catch (_: Exception) {
                // No exception message, response payload, notification body, or private reason is logged.
                mutableStatus.value = if (closed.get()) DesktopNotificationStatus.Stopped else DesktopNotificationStatus.Retrying
            }
        }
    }

    private suspend fun acknowledge(
        sessionId: String,
        acknowledgement: NativeNotificationAckRequest,
    ) {
        requireSession(sessionId)
        val response = request("POST", "/v1/notifications/native/ack", DesktopAuthApi.json.encodeToString(acknowledgement))
        when (response.status) {
            204 -> {
                recentlyDispatched.removeAll(acknowledgement.notificationIds)
                pendingAcknowledgement = null
            }

            404 -> {
                // The lease may have been reclaimed. If the server returns this ID under a fresh
                // claim, acknowledge the dispatch already made in this session without showing it again.
                pendingAcknowledgement = null
            }

            else -> {
                throw DesktopApiException(response.status)
            }
        }
    }

    private fun requireSession(sessionId: String) {
        if (closed.get() || (authState.value as? DesktopAuthState.SignedIn)?.sessionId != sessionId) {
            throw DesktopSessionUnavailableException()
        }
    }

    private fun resetSession(sessionId: String?) {
        deliverySessionId = sessionId
        pendingAcknowledgement = null
        nativeDeliveryUnavailable = false
        recentlyDispatched.clear()
    }

    private fun activeStatus(): DesktopNotificationStatus =
        if (nativeDeliveryUnavailable) DesktopNotificationStatus.DeliveryUnavailable else DesktopNotificationStatus.Active

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownedScope.cancel()
        mutableStatus.value = DesktopNotificationStatus.Stopped
    }

    private fun validateClaim(claim: NativeNotificationClaimResponse) {
        if (claim.notifications.isEmpty()) {
            if (claim.claimId != null) throw DesktopApiException(uncertain = true)
            return
        }
        val claimId = claim.claimId
        val validId = claimId != null && runCatching { UUID.fromString(claimId).toString() == claimId }.getOrDefault(false)
        if (!validId || claim.notifications.size > 100 || claim.notifications.any { it.id <= 0 || it.readAtEpochMillis != null } ||
            claim.notifications
                .map { it.id }
                .distinct()
                .size != claim.notifications.size
        ) {
            throw DesktopApiException(uncertain = true)
        }
    }

    private companion object {
        const val MAXIMUM_RECENT_DISPATCHES = 4096
    }
}
