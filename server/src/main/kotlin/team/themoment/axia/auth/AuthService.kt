package team.themoment.axia.auth

import team.themoment.axia.api.ApiFailure
import team.themoment.axia.auth.store.AccessCheck
import team.themoment.axia.auth.store.AuthStore
import team.themoment.axia.auth.store.ExistingUserProfile
import team.themoment.axia.auth.store.GitHubConflictBlockResult
import team.themoment.axia.auth.store.GitHubLinkApproval
import team.themoment.axia.auth.store.LoginApprovalResult
import team.themoment.axia.auth.store.NewOAuthTransaction
import team.themoment.axia.auth.store.NewSession
import team.themoment.axia.auth.store.PollConsumptionResult
import team.themoment.axia.auth.store.RefreshRotationResult
import team.themoment.axia.auth.store.SessionRevocationReason
import team.themoment.axia.auth.store.StaleProfileSnapshotException
import team.themoment.axia.auth.store.UserProfileSnapshot
import team.themoment.axia.config.DataGsmConfig
import team.themoment.axia.integration.datagsm.DataGsmAccountSnapshot
import team.themoment.axia.integration.datagsm.DataGsmOAuthClient
import team.themoment.axia.integration.datagsm.DataGsmOAuthClientException
import team.themoment.axia.integration.github.GitHubIdentityClient
import team.themoment.axia.integration.github.GitHubIdentityClientException
import team.themoment.axia.integration.github.GitHubUserIdentity
import team.themoment.axia.security.AccessTokenService
import team.themoment.axia.security.CryptoSupport
import team.themoment.axia.security.RefreshCredentialService
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class AuthService(
    private val dataGsmConfig: DataGsmConfig,
    transactionSecret: ByteArray,
    private val dataGsmClient: DataGsmOAuthClient,
    private val gitHubClient: GitHubIdentityClient,
    private val store: AuthStore,
    private val refreshCredentials: RefreshCredentialService,
    private val accessTokens: AccessTokenService,
    private val clock: Clock,
) {
    private val transactionSecret = transactionSecret.copyOf()

    init {
        require(transactionSecret.size >= MINIMUM_SECRET_BYTES) {
            "The authentication transaction key must contain at least $MINIMUM_SECRET_BYTES bytes"
        }
        require(!dataGsmConfig.transactionTtl.isNegative && !dataGsmConfig.transactionTtl.isZero) {
            "OAuth transaction TTL must be positive"
        }
    }

    suspend fun createTransaction(request: AuthTransactionCreateRequest): AuthTransactionCreateResponse {
        val deviceName = normalizeDeviceName(request.deviceName)
        val transactionId = UUID.randomUUID().toString()
        val state = CryptoSupport.generateUrlSafeSecret()
        val pollingSecret = CryptoSupport.generateUrlSafeSecret()
        val codeVerifier = CryptoSupport.derivePkceVerifier(transactionSecret, transactionId)
        val challengeDigest = CryptoSupport.sha256(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        val authorizationUrl =
            dataGsmClient.buildAuthorizationUrl(
                redirectUri = dataGsmConfig.redirectUri.toString(),
                state = state,
                codeChallenge = CryptoSupport.pkceS256Challenge(codeVerifier),
            )
        val createdAt = clock.instant()
        val expiresAt = createdAt.plus(dataGsmConfig.transactionTtl)

        store.createOAuthTransaction(
            NewOAuthTransaction(
                id = transactionId,
                stateHash = stateDigest(state),
                pollSecretHash = pollSecretDigest(pollingSecret),
                pkceChallengeDigest = challengeDigest,
                nonceHash = pollNonceDigest(transactionId, pollingSecret),
                deviceName = deviceName,
                devicePlatform = request.devicePlatform,
                createdAt = createdAt,
                expiresAt = expiresAt,
            ),
        )

        return AuthTransactionCreateResponse(
            transactionId = transactionId,
            transactionSecret = pollingSecret,
            authorizationUrl = authorizationUrl,
            expiresAtEpochMillis = expiresAt.toEpochMilli(),
        )
    }

    suspend fun completeDataGsmCallback(
        state: String?,
        authorizationCode: String?,
        providerError: String?,
    ): CallbackHandlingResult {
        if (state == null || !OAUTH_STATE_PATTERN.matches(state)) return CallbackHandlingResult.Unavailable

        val claim = store.claimCallback(stateDigest(state), clock.instant()) ?: return CallbackHandlingResult.Unavailable
        if (!providerError.isNullOrBlank() || !isValidAuthorizationCode(authorizationCode)) {
            completeBlocked(claim.id, AuthBlockReason.ExternalAuthenticationFailed)
            return CallbackHandlingResult.Completed
        }

        val codeVerifier = CryptoSupport.derivePkceVerifier(transactionSecret, claim.id)
        val expectedChallengeDigest = CryptoSupport.sha256(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        if (!CryptoSupport.constantTimeEquals(claim.pkceChallengeDigest, expectedChallengeDigest)) {
            completeBlocked(claim.id, AuthBlockReason.ExternalAuthenticationFailed)
            return CallbackHandlingResult.Completed
        }

        return try {
            val snapshotRequestedAt = clock.instant()
            val account =
                dataGsmClient.exchangeAuthorizationCodeAndFetchUserInfo(
                    authorizationCode = requireNotNull(authorizationCode),
                    codeVerifier = codeVerifier,
                    redirectUri = dataGsmConfig.redirectUri.toString(),
                )
            applyLoginGates(claim.id, account, snapshotRequestedAt)
            CallbackHandlingResult.Completed
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: DataGsmOAuthClientException) {
            completeBlocked(claim.id, AuthBlockReason.ExternalAuthenticationFailed)
            CallbackHandlingResult.Completed
        } catch (_: GitHubIdentityClientException) {
            completeBlocked(claim.id, AuthBlockReason.ExternalAuthenticationFailed)
            CallbackHandlingResult.Completed
        } catch (_: StaleProfileSnapshotException) {
            completeBlocked(claim.id, AuthBlockReason.ExternalAuthenticationFailed)
            CallbackHandlingResult.Completed
        }
    }

    suspend fun pollTransaction(
        transactionId: String,
        pollingSecret: String?,
    ): AuthTransactionPollResponse {
        if (!isUuid(transactionId) || pollingSecret == null || !POLL_SECRET_PATTERN.matches(pollingSecret)) {
            throw ApiFailure.transactionUnavailable()
        }

        val sessionId = UUID.randomUUID().toString()
        val refreshCredential = refreshCredentials.issue(sessionId)
        val result =
            store.consumePollAndCreateSession(
                transactionId = transactionId,
                pollSecretHash = pollSecretDigest(pollingSecret),
                nonceHash = pollNonceDigest(transactionId, pollingSecret),
                newSession =
                    NewSession(
                        id = sessionId,
                        refreshCredentialHash = refreshCredential.digest,
                        hashVersion = REFRESH_HASH_VERSION,
                    ),
                now = clock.instant(),
            )

        return when (result) {
            PollConsumptionResult.Pending -> {
                AuthTransactionPollResponse(status = AuthTransactionStatus.Pending)
            }

            is PollConsumptionResult.Blocked -> {
                AuthTransactionPollResponse(
                    status = AuthTransactionStatus.Blocked,
                    block = AuthBlock(result.reason, result.userDisplayName),
                )
            }

            is PollConsumptionResult.SessionCreated -> {
                val accessToken =
                    accessTokens.issue(
                        userId = result.userId.toString(),
                        sessionId = result.session.id,
                        sessionAbsoluteExpiresAt = result.session.absoluteExpiresAt,
                    )
                AuthTransactionPollResponse(
                    status = AuthTransactionStatus.Succeeded,
                    tokens =
                        AuthTokens(
                            accessToken = accessToken.value,
                            accessTokenExpiresAtEpochMillis = accessToken.expiresAt.toEpochMilli(),
                            refreshCredential = refreshCredential.value,
                            sessionId = result.session.id,
                            sessionExpiresAtEpochMillis = result.session.absoluteExpiresAt.toEpochMilli(),
                        ),
                )
            }

            PollConsumptionResult.Unavailable -> {
                throw ApiFailure.transactionUnavailable()
            }
        }
    }

    suspend fun refresh(request: TokenRefreshRequest): TokenRefreshResponse {
        val parsed = refreshCredentials.parse(request.refreshCredential) ?: throw ApiFailure.refreshCredentialInvalid()
        if (!isUuid(parsed.sessionId)) throw ApiFailure.refreshCredentialInvalid()

        val presentedDigest =
            refreshCredentials.keyedDigest(request.refreshCredential)
                ?: throw ApiFailure.refreshCredentialInvalid()
        val nextCredential = refreshCredentials.issue(parsed.sessionId)

        return when (
            val result =
                store.rotateRefresh(
                    sessionId = parsed.sessionId,
                    presentedCredentialHash = presentedDigest,
                    nextCredentialHash = nextCredential.digest,
                    hashVersion = REFRESH_HASH_VERSION,
                    now = clock.instant(),
                )
        ) {
            is RefreshRotationResult.Rotated -> {
                val accessToken =
                    accessTokens.issue(
                        userId = result.userId.toString(),
                        sessionId = result.sessionId,
                        sessionAbsoluteExpiresAt = result.absoluteExpiresAt,
                    )
                TokenRefreshResponse(
                    status = TokenRefreshStatus.Succeeded,
                    tokens =
                        AuthTokens(
                            accessToken = accessToken.value,
                            accessTokenExpiresAtEpochMillis = accessToken.expiresAt.toEpochMilli(),
                            refreshCredential = nextCredential.value,
                            sessionId = result.sessionId,
                            sessionExpiresAtEpochMillis = result.absoluteExpiresAt.toEpochMilli(),
                        ),
                )
            }

            is RefreshRotationResult.Ineligible -> {
                TokenRefreshResponse(
                    status = TokenRefreshStatus.Blocked,
                    block =
                        AuthBlock(
                            reason = result.reason,
                            userDisplayName =
                                result.userDisplayName.takeIf {
                                    result.reason == AuthBlockReason.GitHubIdConflict
                                },
                        ),
                )
            }

            is RefreshRotationResult.Reused -> {
                throw ApiFailure.refreshCredentialReused()
            }

            RefreshRotationResult.Invalid -> {
                throw ApiFailure.refreshCredentialInvalid()
            }
        }
    }

    suspend fun authenticate(accessToken: String?): AuthenticatedSession? {
        val claims = accessToken?.let(accessTokens::verify) ?: return null
        val userId = claims.userId.toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (!isUuid(claims.sessionId)) return null

        return when (store.checkAccess(userId, claims.sessionId, clock.instant())) {
            is AccessCheck.Eligible -> AuthenticatedSession(userId = userId, sessionId = claims.sessionId)
            is AccessCheck.Ineligible -> null
        }
    }

    suspend fun listSessions(principal: AuthenticatedSession): SessionListResponse =
        SessionListResponse(
            sessions =
                store.listSessions(principal.userId, principal.sessionId, clock.instant()).map { session ->
                    DesktopSession(
                        id = session.id,
                        deviceName = session.deviceName,
                        devicePlatform = session.devicePlatform,
                        createdAtEpochMillis = session.issuedAt.toEpochMilli(),
                        expiresAtEpochMillis = session.absoluteExpiresAt.toEpochMilli(),
                        isCurrent = session.isCurrent,
                    )
                },
        )

    suspend fun logout(principal: AuthenticatedSession) {
        store.revokeSession(
            userId = principal.userId,
            sessionId = principal.sessionId,
            reason = SessionRevocationReason.Logout,
            now = clock.instant(),
        )
    }

    suspend fun revokeSession(
        principal: AuthenticatedSession,
        sessionId: String,
    ) {
        if (!isUuid(sessionId)) throw ApiFailure.sessionNotFound()
        val revoked =
            store.revokeSession(
                userId = principal.userId,
                sessionId = sessionId,
                reason = SessionRevocationReason.UserRequested,
                now = clock.instant(),
            )
        if (!revoked) throw ApiFailure.sessionNotFound()
    }

    suspend fun revokeAllSessions(principal: AuthenticatedSession) {
        store.revokeAllSessions(
            userId = principal.userId,
            reason = SessionRevocationReason.AllSessionsRequested,
            now = clock.instant(),
        )
    }

    private suspend fun applyLoginGates(
        transactionId: String,
        account: DataGsmAccountSnapshot,
        snapshotRequestedAt: Instant,
    ) {
        val student = account.student
        if (account.objectType != STUDENT_OBJECT_TYPE || student == null) {
            completeBlocked(transactionId, AuthBlockReason.NotTheMomentMember)
            return
        }

        val registeredGitHubLogin = student.githubId?.trim()?.takeIf(String::isNotEmpty)
        val profile =
            UserProfileSnapshot(
                dataGsmUserId = student.id,
                displayName = student.name,
                email = student.email,
                dataGsmAccountStatus = account.status,
                dataGsmStudentRole = student.role,
                majorClubId = student.majorClub?.id,
                majorClubName = student.majorClub?.name,
                dataGsmGitHubLogin = registeredGitHubLogin,
                sex = student.sex,
                grade = student.grade,
                classNumber = student.classNumber,
                number = student.number,
                studentNumber = student.studentNumber,
                major = student.major,
                specialty = student.specialty,
                snapshotRequestedAt = snapshotRequestedAt,
            )

        if (
            student.majorClub?.id != dataGsmConfig.requiredMajorClubId ||
            student.majorClub.type != MAJOR_CLUB_TYPE
        ) {
            store.deactivateAndRelease(profile, clock.instant())
            completeBlocked(transactionId, AuthBlockReason.NotTheMomentMember)
            return
        }

        val existing = store.syncExistingProfile(profile, clock.instant())
        var linkReleased = existing?.githubLinkReleased == true

        if (registeredGitHubLogin == null) {
            val released =
                existing?.let {
                    store.releaseGitHubLinkAndSuspend(
                        userId = it.userId,
                        reason = AuthBlockReason.GitHubIdMissing,
                        now = clock.instant(),
                        expectedProfileVersion = it.profileVersion,
                    )
                } ?: true
            completeBlocked(
                transactionId,
                if (released) AuthBlockReason.GitHubIdMissing else AuthBlockReason.ExternalAuthenticationFailed,
            )
            return
        }

        if (!GITHUB_LOGIN_PATTERN.matches(registeredGitHubLogin)) {
            blockInvalidGitHubAccount(transactionId, profile.dataGsmUserId, existing)
            return
        }

        val identity = gitHubClient.findUser(registeredGitHubLogin)
        if (identity == null) {
            blockInvalidGitHubAccount(transactionId, profile.dataGsmUserId, existing)
            return
        }

        if (existing?.githubUserId != null && existing.githubUserId != identity.numericId) {
            val released =
                store.releaseGitHubLinkAndSuspend(
                    userId = existing.userId,
                    reason = AuthBlockReason.ExternalAuthenticationFailed,
                    now = clock.instant(),
                    expectedProfileVersion = existing.profileVersion,
                )
            if (!released) {
                completeBlocked(transactionId, AuthBlockReason.ExternalAuthenticationFailed)
                return
            }
            linkReleased = true
        }

        when (
            store.blockForGitHubConflict(
                transactionId = transactionId,
                githubUserId = identity.numericId,
                conflictingProfile = profile,
                expectedProfileVersion = existing?.profileVersion,
                now = clock.instant(),
            )
        ) {
            GitHubConflictBlockResult.NoConflict -> Unit

            is GitHubConflictBlockResult.Blocked,
            GitHubConflictBlockResult.StaleProfile,
            GitHubConflictBlockResult.TransactionUnavailable,
                -> return
        }

        if (!gitHubClient.hasActiveOrganizationMembership(identity)) {
            val suspended = suspendForOrganizationMembership(existing, identity, linkReleased)
            completeBlocked(
                transactionId,
                if (suspended) {
                    AuthBlockReason.GitHubOrganizationMembershipRequired
                } else {
                    AuthBlockReason.ExternalAuthenticationFailed
                },
            )
            return
        }

        when (
            store.approveLogin(
                transactionId = transactionId,
                profile = profile,
                githubLink = GitHubLinkApproval(identity.numericId, identity.login),
                expectedProfileVersion = existing?.profileVersion,
                now = clock.instant(),
            )
        ) {
            is LoginApprovalResult.Approved,
            is LoginApprovalResult.Conflict,
            LoginApprovalResult.StaleProfile,
            LoginApprovalResult.TransactionUnavailable,
                -> Unit
        }
    }

    private suspend fun blockInvalidGitHubAccount(
        transactionId: String,
        dataGsmUserId: Long,
        existing: ExistingUserProfile?,
    ) {
        val released =
            existing?.let {
                store.releaseGitHubLinkAndSuspend(
                    userId = it.userId,
                    reason = AuthBlockReason.GitHubAccountNotFound,
                    now = clock.instant(),
                    expectedProfileVersion = it.profileVersion,
                )
            } ?: true
        if (released) {
            store.resolveGitHubConflicts(dataGsmUserId, clock.instant())
        }
        completeBlocked(
            transactionId,
            if (released) AuthBlockReason.GitHubAccountNotFound else AuthBlockReason.ExternalAuthenticationFailed,
        )
    }

    private suspend fun suspendForOrganizationMembership(
        existing: ExistingUserProfile?,
        identity: GitHubUserIdentity,
        linkReleased: Boolean,
    ): Boolean {
        if (existing == null) return true
        return if (!linkReleased && existing.githubUserId == identity.numericId) {
            store.suspendForGitHubMembershipLoss(
                userId = existing.userId,
                now = clock.instant(),
                expectedProfileVersion = existing.profileVersion,
            )
        } else {
            store.releaseGitHubLinkAndSuspend(
                userId = existing.userId,
                reason = AuthBlockReason.GitHubOrganizationMembershipRequired,
                now = clock.instant(),
                expectedProfileVersion = existing.profileVersion,
            )
        }
    }

    private suspend fun completeBlocked(
        transactionId: String,
        reason: AuthBlockReason,
        failureDisplayName: String? = null,
    ) {
        store.completeBlocked(
            transactionId = transactionId,
            reason = reason,
            failureDisplayName = failureDisplayName,
            now = clock.instant(),
        )
    }

    private fun stateDigest(state: String): ByteArray = CryptoSupport.hmacSha256(transactionSecret, STATE_DIGEST_CONTEXT + state)

    private fun pollSecretDigest(secret: String): ByteArray =
        CryptoSupport.hmacSha256(transactionSecret, POLL_SECRET_DIGEST_CONTEXT + secret)

    private fun pollNonceDigest(
        transactionId: String,
        pollingSecret: String,
    ): ByteArray =
        CryptoSupport.hmacSha256(
            transactionSecret,
            "$POLL_NONCE_DIGEST_CONTEXT$transactionId:$pollingSecret",
        )

    private fun normalizeDeviceName(value: String): String {
        val normalized = value.trim()
        if (
            normalized.isEmpty() ||
            normalized.length > MAXIMUM_DEVICE_NAME_LENGTH ||
            normalized.any(Char::isISOControl)
        ) {
            throw ApiFailure.invalidRequest("기기 이름을 확인해 주세요.")
        }
        return normalized
    }

    private fun isValidAuthorizationCode(code: String?): Boolean =
        code != null &&
            code.isNotBlank() &&
            code.length <= MAXIMUM_AUTHORIZATION_CODE_LENGTH &&
            code.none { it == '\r' || it == '\n' }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private companion object {
        const val STUDENT_OBJECT_TYPE = "STUDENT"
        const val MAJOR_CLUB_TYPE = "MAJOR_CLUB"
        const val MINIMUM_SECRET_BYTES = 32
        const val MAXIMUM_DEVICE_NAME_LENGTH = 128
        const val MAXIMUM_AUTHORIZATION_CODE_LENGTH = 4096
        const val REFRESH_HASH_VERSION = 1
        const val STATE_DIGEST_CONTEXT = "axia:oauth-state:v1:"
        const val POLL_SECRET_DIGEST_CONTEXT = "axia:oauth-poll-secret:v1:"
        const val POLL_NONCE_DIGEST_CONTEXT = "axia:oauth-poll-nonce:v1:"

        val OAUTH_STATE_PATTERN = Regex("[A-Za-z0-9_-]{43}")
        val POLL_SECRET_PATTERN = Regex("[A-Za-z0-9_-]{43}")
        val GITHUB_LOGIN_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    }
}

enum class CallbackHandlingResult {
    Completed,
    Unavailable,
}

data class AuthenticatedSession(
    val userId: Long,
    val sessionId: String,
)
