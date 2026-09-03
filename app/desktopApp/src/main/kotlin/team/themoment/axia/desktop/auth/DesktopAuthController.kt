package team.themoment.axia.desktop.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import team.themoment.axia.auth.AuthTokens
import team.themoment.axia.auth.AuthTransactionCreateRequest
import team.themoment.axia.auth.AuthTransactionCreateResponse
import team.themoment.axia.auth.AuthTransactionStatus
import team.themoment.axia.auth.DesktopPlatform
import team.themoment.axia.auth.SessionListResponse
import team.themoment.axia.auth.TokenRefreshStatus
import team.themoment.axia.desktop.security.CredentialStoreException
import team.themoment.axia.desktop.security.SecureCredentialStore
import team.themoment.axia.feature.auth.AuthUiState
import java.net.URI
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface DesktopAuthState {
    data class Gated(
        val screen: AuthUiState,
    ) : DesktopAuthState

    data class SignedIn(
        val sessionId: String,
        val absoluteExpiresAtEpochMillis: Long,
    ) : DesktopAuthState
}

class DesktopSessionUnavailableException : IllegalStateException("An eligible Axia session is required")

/**
 * Owns credentials outside Compose state. All refreshes and secure-store mutations share one mutex.
 * A persisted rotating marker survives process termination, so an uncertain refresh is never replayed.
 */
class DesktopAuthController(
    private val api: DesktopAuthApi,
    private val secureStore: SecureCredentialStore,
    private val platform: DesktopPlatform,
    private val browser: SystemBrowser,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sessionCheckIntervalMillis: Long = SESSION_CHECK_INTERVAL_MILLIS,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<DesktopAuthState>(DesktopAuthState.Gated(AuthUiState.RestoringSession))
    val state: StateFlow<DesktopAuthState> = mutableState.asStateFlow()
    private val sessionMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val loginGeneration = AtomicLong(0)

    @Volatile private var loginJob: Job? = null

    @Volatile private var currentTransaction: AuthTransactionCreateResponse? = null

    @Volatile private var memoryAccess: MemoryAccess? = null

    @Volatile private var retryAction = RetryAction.Restore

    @Volatile private var beforeLogin: DesktopAuthState = DesktopAuthState.Gated(AuthUiState.SignedOut)

    init {
        require(sessionCheckIntervalMillis > 0) { "The session check interval must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { restoreSession() }
        scope.launch {
            while (isActive) {
                delay(sessionCheckIntervalMillis)
                if (loginJob?.isActive == true) continue
                try {
                    when (val current = state.value) {
                        is DesktopAuthState.SignedIn -> {
                            listSessions()
                        }

                        is DesktopAuthState.Gated -> {
                            if (current.screen is AuthUiState.Blocked) {
                                sessionMutex.withLock {
                                    // A failed first login has no session to restore; keep its dedicated blocked screen.
                                    if (readSavedCredential() != null) refreshLocked()
                                }
                            }
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: DesktopApiException) {
                    // A read failure does not invalidate a known session or replay a refresh.
                } catch (_: DesktopSessionUnavailableException) {
                    // refreshLocked already publishes the precise authentication state.
                } catch (_: CredentialStoreException) {
                    storageUnavailable()
                }
            }
        }
    }

    @Synchronized
    fun login() {
        val generation = loginGeneration.incrementAndGet()
        loginJob?.cancel()
        beforeLogin = state.value.takeUnless { it is DesktopAuthState.SignedIn } ?: DesktopAuthState.Gated(AuthUiState.SignedOut)
        currentTransaction = null
        mutableState.value = DesktopAuthState.Gated(AuthUiState.CreatingTransaction)
        retryAction = RetryAction.Login
        loginJob =
            scope.launch {
                try {
                    val transaction =
                        api.createTransaction(
                            AuthTransactionCreateRequest(
                                deviceName = if (platform == DesktopPlatform.MacOS) "macOS Desktop" else "Windows Desktop",
                                devicePlatform = platform,
                            ),
                        )
                    val duration = validateTransaction(transaction)
                    if (generation != loginGeneration.get()) return@launch
                    currentTransaction = transaction
                    mutableState.value = DesktopAuthState.Gated(AuthUiState.AwaitingBrowser())
                    // Browser integrations may wait for an OS prompt. They cannot extend the OAuth deadline.
                    scope.launch { openCurrentBrowser(generation) }
                    withTimeout(duration) {
                        while (isActive && generation == loginGeneration.get()) {
                            delay(POLL_INTERVAL_MILLIS)
                            if (clock.millis() >= transaction.expiresAtEpochMillis) break
                            val result = api.pollTransaction(transaction)
                            when (result.status) {
                                AuthTransactionStatus.Pending -> {
                                    if (result.tokens != null || result.block != null) throw DesktopApiException(uncertain = true)
                                }

                                AuthTransactionStatus.Blocked -> {
                                    val block = result.block ?: throw DesktopApiException(uncertain = true)
                                    if (result.tokens != null) throw DesktopApiException(uncertain = true)
                                    if (generation == loginGeneration.get()) {
                                        mutableState.value =
                                            DesktopAuthState.Gated(AuthUiState.Blocked(block.reason, block.userDisplayName))
                                    }
                                    return@withTimeout
                                }

                                AuthTransactionStatus.Succeeded -> {
                                    val tokens = result.tokens ?: throw DesktopApiException(uncertain = true)
                                    if (result.block != null) throw DesktopApiException(uncertain = true)
                                    withContext(NonCancellable) {
                                        sessionMutex.withLock {
                                            if (generation == loginGeneration.get()) {
                                                installTokens(tokens, publish = false)
                                                val accepted =
                                                    synchronized(this@DesktopAuthController) {
                                                        if (generation == loginGeneration.get()) {
                                                            mutableState.value =
                                                                DesktopAuthState.SignedIn(
                                                                    tokens.sessionId,
                                                                    tokens.sessionExpiresAtEpochMillis,
                                                                )
                                                            true
                                                        } else {
                                                            false
                                                        }
                                                    }
                                                if (!accepted) {
                                                    revokeUnacceptedSession(tokens)
                                                    clearLocalSession()
                                                }
                                            } else {
                                                revokeUnacceptedSession(tokens)
                                            }
                                        }
                                    }
                                    return@withTimeout
                                }
                            }
                        }
                        throw LoginDeadlineException()
                    }
                } catch (exception: kotlinx.coroutines.TimeoutCancellationException) {
                    if (generation == loginGeneration.get()) loginExpired()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: LoginDeadlineException) {
                    if (generation == loginGeneration.get()) loginExpired()
                } catch (_: CredentialStoreException) {
                    if (generation == loginGeneration.get()) storageUnavailable()
                } catch (_: Exception) {
                    if (generation == loginGeneration.get()) {
                        fail(
                            "로그인 결과를 받지 못했어요",
                            "네트워크와 외부 인증 상태를 확인한 뒤 새 로그인 요청으로 다시 시도해 주세요.",
                            RetryAction.Login,
                        )
                    }
                } finally {
                    if (generation == loginGeneration.get()) currentTransaction = null
                }
            }
    }

    @Synchronized
    fun cancelLogin() {
        if (loginJob?.isActive != true || state.value is DesktopAuthState.SignedIn) return
        loginGeneration.incrementAndGet()
        loginJob?.cancel()
        currentTransaction = null
        mutableState.value = beforeLogin
    }

    fun reopenBrowser() {
        scope.launch { openCurrentBrowser(loginGeneration.get()) }
    }

    fun openDataGsmMyInfo() {
        scope.launch {
            try {
                browser.open(URI("https://www.datagsm.kr/myinfo"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                fail("기본 브라우저를 열지 못했어요", "운영체제의 기본 브라우저 설정을 확인해 주세요.", RetryAction.Login)
            }
        }
    }

    fun retry() {
        when (retryAction) {
            RetryAction.Login -> login()
            RetryAction.Restore -> scope.launch { restoreSession() }
        }
    }

    /** API boundary for feature coordinators. A token never enters UI models or persistence. */
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
    ): DesktopApiResponse {
        if (state.value !is DesktopAuthState.SignedIn) throw DesktopSessionUnavailableException()
        val token = sessionMutex.withLock { accessLocked() }
        var response = api.transport.request(method, path, body, accessToken = token)
        if (response.status == 401) {
            val nextToken =
                sessionMutex.withLock {
                    if (memoryAccess?.value == token) refreshLocked()
                    accessLocked()
                }
            response = api.transport.request(method, path, body, accessToken = nextToken)
            if (response.status == 401) {
                sessionMutex.withLock { memoryAccess = null }
                fail("세션을 확인하지 못했어요", "로그인 상태를 다시 확인해 주세요.", RetryAction.Restore)
                throw DesktopSessionUnavailableException()
            }
        }
        return response
    }

    suspend fun listSessions(): SessionListResponse = DesktopAuthApi.decode(request("GET", "/v1/auth/sessions"))

    suspend fun revokeSession(sessionId: String) {
        require(validUuid(sessionId)) { "A valid session ID is required" }
        val response = request("DELETE", "/v1/auth/sessions/$sessionId")
        if (response.status != 204) throw DesktopApiException(response.status)
        sessionMutex.withLock {
            if (memoryAccess?.sessionId == sessionId) {
                clearLocalSession()
                mutableState.value = DesktopAuthState.Gated(AuthUiState.SignedOut)
            }
        }
    }

    suspend fun revokeAllSessions() {
        val response = request("DELETE", "/v1/auth/sessions")
        if (response.status != 204) throw DesktopApiException(response.status)
        sessionMutex.withLock {
            clearLocalSession()
            mutableState.value = DesktopAuthState.Gated(AuthUiState.SignedOut)
        }
    }

    /** Returns false if the local sign-out succeeded but remote revocation could not be confirmed. */
    suspend fun logout(): Boolean {
        cancelLogin()
        return sessionMutex.withLock {
            var revoked = false
            try {
                val token = accessLocked()
                revoked = api.transport.request("POST", "/v1/auth/logout", accessToken = token).status == 204
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: DesktopApiException) {
                // Always erase this device's credential; never claim unconfirmed server revocation.
            } catch (_: DesktopSessionUnavailableException) {
                // Suspended or invalid credentials cannot authorize revocation, but local sign-out still works.
            } finally {
                withContext(NonCancellable) { clearLocalSession() }
                mutableState.value = DesktopAuthState.Gated(AuthUiState.SignedOut)
            }
            revoked
        }
    }

    private suspend fun restoreSession() {
        mutableState.value = DesktopAuthState.Gated(AuthUiState.RestoringSession)
        try {
            sessionMutex.withLock { refreshLocked() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: DesktopSessionUnavailableException) {
            // The exact reason is published by refreshLocked.
        } catch (_: CredentialStoreException) {
            storageUnavailable()
        }
    }

    private suspend fun accessLocked(): String {
        val access = memoryAccess
        if (access != null && access.expiresAtEpochMillis > clock.millis() + ACCESS_EXPIRY_MARGIN_MILLIS) return access.value
        refreshLocked()
        return memoryAccess?.value ?: throw DesktopSessionUnavailableException()
    }

    private suspend fun refreshLocked() {
        val saved = readSavedCredential()
        if (saved == null) {
            memoryAccess = null
            mutableState.value = DesktopAuthState.Gated(AuthUiState.SignedOut)
            throw DesktopSessionUnavailableException()
        }
        if (saved.absoluteExpiresAtEpochMillis <= clock.millis()) {
            clearLocalSession()
            fail("세션이 만료되었어요", "240일의 로그인 유지 기간이 끝났습니다. 다시 로그인해 주세요.", RetryAction.Login)
            throw DesktopSessionUnavailableException()
        }
        if (saved.rotationPending) {
            clearLocalSession()
            uncertainRefresh()
            throw DesktopSessionUnavailableException()
        }
        // Durable intent precedes the request. Process death cannot cause reuse on the next launch.
        writeSavedCredential(saved.copy(rotationPending = true))
        val response =
            try {
                api.refresh(saved.refreshCredential)
            } catch (exception: CancellationException) {
                memoryAccess = null
                throw exception
            } catch (exception: DesktopApiException) {
                clearLocalSession()
                if (exception.status == 401) {
                    fail("로그인이 만료되었어요", "종료되었거나 유효하지 않은 세션입니다. 다시 로그인해 주세요.", RetryAction.Login)
                } else {
                    uncertainRefresh()
                }
                throw DesktopSessionUnavailableException()
            }
        when (response.status) {
            TokenRefreshStatus.Blocked -> {
                writeSavedCredential(saved)
                memoryAccess = null
                val block = requireNotNull(response.block)
                retryAction = RetryAction.Login
                mutableState.value = DesktopAuthState.Gated(AuthUiState.Blocked(block.reason, block.userDisplayName))
                throw DesktopSessionUnavailableException()
            }

            TokenRefreshStatus.Succeeded -> {
                val tokens = requireNotNull(response.tokens)
                if (tokens.sessionId != saved.sessionId || tokens.sessionExpiresAtEpochMillis != saved.absoluteExpiresAtEpochMillis) {
                    clearLocalSession()
                    uncertainRefresh()
                    throw DesktopSessionUnavailableException()
                }
                installTokens(tokens)
            }
        }
    }

    private suspend fun installTokens(
        tokens: AuthTokens,
        publish: Boolean = true,
    ) {
        if (
            !validUuid(tokens.sessionId) || !tokens.refreshCredential.startsWith("${tokens.sessionId}.") ||
            tokens.accessToken.isBlank() || tokens.accessToken.length > 8192 ||
            tokens.refreshCredential.length !in 60..256 || tokens.sessionExpiresAtEpochMillis <= clock.millis() ||
            tokens.accessTokenExpiresAtEpochMillis <= clock.millis() ||
            tokens.accessTokenExpiresAtEpochMillis > tokens.sessionExpiresAtEpochMillis
        ) {
            clearLocalSession()
            uncertainRefresh()
            throw DesktopSessionUnavailableException()
        }
        try {
            writeSavedCredential(StoredCredential(tokens.refreshCredential, tokens.sessionId, tokens.sessionExpiresAtEpochMillis))
        } catch (exception: CredentialStoreException) {
            memoryAccess = null
            revokeUnacceptedSession(tokens)
            throw exception
        }
        memoryAccess = MemoryAccess(tokens.accessToken, tokens.accessTokenExpiresAtEpochMillis, tokens.sessionId)
        if (publish) mutableState.value = DesktopAuthState.SignedIn(tokens.sessionId, tokens.sessionExpiresAtEpochMillis)
    }

    private suspend fun revokeUnacceptedSession(tokens: AuthTokens) {
        try {
            api.transport.request("POST", "/v1/auth/logout", accessToken = tokens.accessToken)
        } catch (_: DesktopApiException) {
            // No retry of an uncertain write. The unused credential remains inaccessible to the UI.
        }
    }

    private suspend fun readSavedCredential(): StoredCredential? {
        val raw = storage { secureStore.read() } ?: return null
        return try {
            DesktopAuthApi.json.decodeFromString<StoredCredential>(raw).also {
                if (!validUuid(it.sessionId) || !it.refreshCredential.startsWith("${it.sessionId}.")) throw IllegalArgumentException()
            }
        } catch (_: IllegalArgumentException) {
            clearLocalSession()
            null
        }
    }

    private suspend fun writeSavedCredential(credential: StoredCredential) {
        storage { secureStore.write(DesktopAuthApi.json.encodeToString(credential)) }
    }

    private suspend fun clearLocalSession() {
        memoryAccess = null
        storage { secureStore.delete() }
    }

    private suspend fun <T> storage(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (exception: CredentialStoreException) {
                throw exception
            } catch (_: Exception) {
                throw CredentialStoreException("access")
            } catch (_: LinkageError) {
                throw CredentialStoreException("native bridge")
            }
        }

    private suspend fun openCurrentBrowser(generation: Long) {
        val transaction = currentTransaction ?: return
        if (generation != loginGeneration.get() || clock.millis() >= transaction.expiresAtEpochMillis) return
        try {
            browser.open(URI(transaction.authorizationUrl))
            publishBrowserLaunchResult(generation, transaction, failed = false)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            publishBrowserLaunchResult(generation, transaction, failed = true)
        }
    }

    private fun publishBrowserLaunchResult(
        generation: Long,
        transaction: AuthTransactionCreateResponse,
        failed: Boolean,
    ) {
        mutableState.update { current ->
            if (
                generation == loginGeneration.get() && currentTransaction === transaction &&
                current is DesktopAuthState.Gated && current.screen is AuthUiState.AwaitingBrowser
            ) {
                DesktopAuthState.Gated(AuthUiState.AwaitingBrowser(failed))
            } else {
                current
            }
        }
    }

    private fun validateTransaction(transaction: AuthTransactionCreateResponse): Long {
        val uri = URI(transaction.authorizationUrl)
        val duration = transaction.expiresAtEpochMillis - clock.millis()
        require(validUuid(transaction.transactionId) && Regex("[A-Za-z0-9_-]{43}").matches(transaction.transactionSecret))
        require(uri.scheme == "https" && uri.host == "oauth.authorization.datagsm.kr" && uri.userInfo == null && uri.fragment == null)
        require(uri.path == "/v1/oauth/authorize" && (uri.port == -1 || uri.port == 443))
        require(duration in 1..MAXIMUM_TRANSACTION_MILLIS)
        return duration
    }

    private fun storageUnavailable() {
        memoryAccess = null
        fail("보안 저장소를 사용할 수 없어요", "Keychain 또는 자격 증명 관리자의 접근을 허용한 뒤 다시 시도해 주세요. 로그인 정보는 평문으로 저장하지 않습니다.", RetryAction.Restore)
    }

    private fun uncertainRefresh() {
        fail("세션 갱신 결과를 확인하지 못했어요", "이미 사용되었을 수 있는 인증 정보는 다시 보내지 않습니다. 안전하게 새로 로그인해 주세요.", RetryAction.Login)
    }

    private fun loginExpired() {
        fail("로그인 요청이 만료되었어요", "브라우저의 이전 요청을 닫고 로그인을 다시 시작해 주세요.", RetryAction.Login)
    }

    private fun fail(
        title: String,
        description: String,
        action: RetryAction,
    ) {
        retryAction = action
        mutableState.value =
            DesktopAuthState.Gated(
                AuthUiState.Failure(
                    title,
                    description,
                    retryLabel =
                        if (action ==
                            RetryAction.Login
                        ) {
                            "다시 로그인"
                        } else {
                            "다시 확인"
                        },
                ),
            )
    }

    @Synchronized
    override fun close() {
        loginGeneration.incrementAndGet()
        currentTransaction = null
        memoryAccess = null
        scope.cancel()
    }

    private class LoginDeadlineException : IllegalStateException()

    private class MemoryAccess(
        val value: String,
        val expiresAtEpochMillis: Long,
        val sessionId: String,
    ) {
        override fun toString(): String = "MemoryAccess(value=[REDACTED], sessionId=$sessionId)"
    }

    private enum class RetryAction { Login, Restore }

    @Serializable
    private data class StoredCredential(
        val refreshCredential: String,
        val sessionId: String,
        val absoluteExpiresAtEpochMillis: Long,
        val rotationPending: Boolean = false,
    ) {
        override fun toString(): String =
            "StoredCredential(refreshCredential=[REDACTED], sessionId=$sessionId, rotationPending=$rotationPending)"
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val SESSION_CHECK_INTERVAL_MILLIS = 60_000L
        const val ACCESS_EXPIRY_MARGIN_MILLIS = 30_000L
        const val MAXIMUM_TRANSACTION_MILLIS = 10 * 60_000L

        fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
