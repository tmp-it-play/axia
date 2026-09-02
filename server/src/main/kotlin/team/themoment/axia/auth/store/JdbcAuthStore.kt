package team.themoment.axia.auth.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.auth.AuthBlockReason
import team.themoment.axia.auth.DesktopPlatform
import team.themoment.axia.persistence.inTransaction
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthStore(
    private val dataSource: DataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthStore {
    override suspend fun createOAuthTransaction(transaction: NewOAuthTransaction): Unit =
        withContext(ioDispatcher) {
            validateNewOAuthTransaction(transaction)
            databaseTransaction {
                prepareStatement(INSERT_OAUTH_TRANSACTION).use { statement ->
                    statement.setString(1, transaction.id)
                    statement.setBytes(2, transaction.stateHash)
                    statement.setBytes(3, transaction.pollSecretHash)
                    statement.setBytes(4, transaction.pkceChallengeDigest)
                    statement.setBytes(5, transaction.nonceHash)
                    statement.setString(6, transaction.deviceName.trim())
                    statement.setString(7, transaction.devicePlatform.databaseValue())
                    statement.setUtcInstant(8, transaction.createdAt)
                    statement.setUtcInstant(9, transaction.expiresAt)
                    check(statement.executeUpdate() == 1) { "OAuth transaction was not inserted" }
                }
            }
        }

    override suspend fun claimCallback(
        stateHash: ByteArray,
        now: Instant,
    ): CallbackClaim? =
        withContext(ioDispatcher) {
            requireDigest(stateHash, "stateHash")
            databaseTransaction {
                val transaction = findOAuthByStateForUpdate(stateHash) ?: return@databaseTransaction null
                if (transaction.status != OAuthDatabaseStatus.Pending) return@databaseTransaction null

                if (!now.isBefore(transaction.expiresAt)) {
                    expireOAuthTransaction(transaction.id, now)
                    return@databaseTransaction null
                }

                prepareStatement(CLAIM_OAUTH_TRANSACTION).use { statement ->
                    statement.setUtcInstant(1, now)
                    statement.setString(2, transaction.id)
                    check(statement.executeUpdate() == 1) { "Locked OAuth transaction could not be claimed" }
                }

                CallbackClaim(
                    id = transaction.id,
                    pkceChallengeDigest = transaction.pkceChallengeDigest.copyOf(),
                    nonceHash = transaction.nonceHash.copyOf(),
                    expiresAt = transaction.expiresAt,
                )
            }
        }

    override suspend fun completeBlocked(
        transactionId: String,
        reason: AuthBlockReason,
        failureDisplayName: String?,
        now: Instant,
    ): Boolean =
        withContext(ioDispatcher) {
            requireUuid(transactionId, "transactionId")
            validateFailureDisplayName(reason, failureDisplayName)

            databaseTransaction {
                val transaction = findOAuthByIdForUpdate(transactionId)
                if (transaction?.status != OAuthDatabaseStatus.Processing) {
                    return@databaseTransaction false
                }
                if (!now.isBefore(transaction.expiresAt)) {
                    expireOAuthTransaction(transaction.id, now)
                    return@databaseTransaction false
                }
                prepareStatement(COMPLETE_BLOCKED_TRANSACTION).use { statement ->
                    statement.setString(1, reason.databaseValue())
                    statement.setNullableString(2, failureDisplayName?.trim())
                    statement.setUtcInstant(3, now)
                    statement.setString(4, transactionId)
                    statement.executeUpdate() == 1
                }
            }
        }

    override suspend fun syncExistingProfile(
        profile: UserProfileSnapshot,
        now: Instant,
    ): ExistingUserProfile? =
        withContext(ioDispatcher) {
            val validatedProfile = profile.validated()
            databaseTransaction {
                val existing = findUserByDataGsmIdForUpdate(validatedProfile.dataGsmUserId)
                if (existing == null) {
                    if (validatedProfile.dataGsmGitHubLogin == null) {
                        resolveCandidateConflictEpisodes(validatedProfile.dataGsmUserId, now)
                    }
                    return@databaseTransaction null
                }
                val sourceLoginChanged = existing.dataGsmGitHubLogin != validatedProfile.dataGsmGitHubLogin
                if (sourceLoginChanged) {
                    val reason =
                        if (validatedProfile.dataGsmGitHubLogin == null) {
                            AuthBlockReason.GitHubIdMissing
                        } else {
                            AuthBlockReason.ExternalAuthenticationFailed
                        }
                    releaseGitHubLink(existing.userId, now)
                    suspendUserSessions(existing.userId, reason, now)
                    resolveConflictEpisodes(existing.userId, validatedProfile.dataGsmUserId, now)
                }
                val profileVersion = updateUserProfile(existing, validatedProfile, now)
                ExistingUserProfile(
                    userId = existing.userId,
                    isActive = existing.isActive,
                    previousDataGsmGitHubLogin = existing.dataGsmGitHubLogin,
                    profileVersion = profileVersion,
                    githubLinkReleased = sourceLoginChanged,
                    githubUserId = existing.githubUserId.takeUnless { sourceLoginChanged },
                    githubLogin = existing.githubLogin.takeUnless { sourceLoginChanged },
                    githubMembership =
                        existing.githubMembership.takeUnless { sourceLoginChanged }
                            ?: GitHubMembership.Unknown,
                )
            }
        }

    override suspend fun deactivateAndRelease(
        profile: UserProfileSnapshot,
        now: Instant,
    ): Boolean =
        withContext(ioDispatcher) {
            val validatedProfile = profile.validated()
            databaseTransaction {
                val existing = findUserByDataGsmIdForUpdate(validatedProfile.dataGsmUserId)
                if (existing == null) {
                    resolveCandidateConflictEpisodes(validatedProfile.dataGsmUserId, now)
                    return@databaseTransaction false
                }
                updateUserProfile(existing, validatedProfile, now)
                prepareStatement(DEACTIVATE_USER).use { statement ->
                    statement.setUtcInstant(1, now)
                    statement.setLong(2, existing.userId)
                    check(statement.executeUpdate() == 1) { "Locked user could not be deactivated" }
                }

                revokeUserSessions(existing.userId, DATAGSM_INACTIVE_REASON, now)
                revokeActiveRefreshCredentials(existing.userId, now)
                releaseGitHubLink(existing.userId, now)
                resolveConflictEpisodes(existing.userId, validatedProfile.dataGsmUserId, now)
                true
            }
        }

    override suspend fun suspendForGitHubMembershipLoss(
        userId: Long,
        now: Instant,
        expectedProfileVersion: Long?,
    ): Boolean =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            expectedProfileVersion?.let { requirePositive(it, "expectedProfileVersion") }
            databaseTransaction {
                val user = findUserByIdForUpdate(userId) ?: return@databaseTransaction false
                if (expectedProfileVersion != null && user.profileVersion != expectedProfileVersion) {
                    return@databaseTransaction false
                }
                if (expectedProfileVersion == null) advanceProfileVersion(user)

                prepareStatement(MARK_GITHUB_MEMBERSHIP_LOST).use { statement ->
                    statement.setUtcInstant(1, now)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
                suspendUserSessions(
                    userId = userId,
                    reason = AuthBlockReason.GitHubOrganizationMembershipRequired,
                    now = now,
                )
                true
            }
        }

    override suspend fun releaseGitHubLinkAndSuspend(
        userId: Long,
        reason: AuthBlockReason,
        now: Instant,
        expectedProfileVersion: Long?,
    ): Boolean =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            expectedProfileVersion?.let { requirePositive(it, "expectedProfileVersion") }
            require(reason in LINK_RELEASE_BLOCK_REASONS) {
                "Only a GitHub identity failure can release a GitHub link"
            }
            databaseTransaction {
                val user = findUserByIdForUpdate(userId) ?: return@databaseTransaction false
                if (expectedProfileVersion != null && user.profileVersion != expectedProfileVersion) {
                    return@databaseTransaction false
                }
                if (expectedProfileVersion == null) advanceProfileVersion(user)
                releaseGitHubLink(userId, now)
                suspendUserSessions(userId, reason, now)
                if (reason == AuthBlockReason.GitHubIdConflict) {
                    resolveOwnedConflictEpisodes(userId, now)
                } else {
                    resolveConflictEpisodes(userId, user.dataGsmUserId, now)
                }
                true
            }
        }

    override suspend fun resolveGitHubConflicts(
        dataGsmUserId: Long,
        now: Instant,
    ): Int =
        withContext(ioDispatcher) {
            requirePositive(dataGsmUserId, "dataGsmUserId")
            databaseTransaction {
                prepareStatement(RESOLVE_CONFLICTS_FOR_DATAGSM_USER).use { statement ->
                    statement.setUtcInstant(1, now)
                    statement.setLong(2, dataGsmUserId)
                    statement.executeUpdate()
                }
            }
        }

    override suspend fun blockForGitHubConflict(
        transactionId: String,
        githubUserId: Long,
        conflictingProfile: UserProfileSnapshot,
        expectedProfileVersion: Long?,
        now: Instant,
    ): GitHubConflictBlockResult =
        withContext(ioDispatcher) {
            requireUuid(transactionId, "transactionId")
            requirePositive(githubUserId, "githubUserId")
            expectedProfileVersion?.let { requirePositive(it, "expectedProfileVersion") }
            val profile = conflictingProfile.validated()
            databaseTransaction {
                val transaction = findOAuthByIdForUpdate(transactionId)
                if (transaction?.status != OAuthDatabaseStatus.Processing) {
                    return@databaseTransaction GitHubConflictBlockResult.TransactionUnavailable
                }
                if (!now.isBefore(transaction.expiresAt)) {
                    expireOAuthTransaction(transaction.id, now)
                    return@databaseTransaction GitHubConflictBlockResult.TransactionUnavailable
                }

                val existingUser = findUserByDataGsmIdForUpdate(profile.dataGsmUserId)
                if (existingUser.hasStaleProfileVersion(expectedProfileVersion)) {
                    completeStaleProfileTransaction(transactionId, existingUser, now)
                    return@databaseTransaction GitHubConflictBlockResult.StaleProfile
                }

                val owner = findGitHubOwnerForUpdate(githubUserId)
                if (owner == null || owner.dataGsmUserId == profile.dataGsmUserId) {
                    resolveCandidateConflictEpisodes(profile.dataGsmUserId, now)
                    return@databaseTransaction GitHubConflictBlockResult.NoConflict
                }
                check(owner.isActive) { "Inactive user ${owner.userId} retains a GitHub identity" }
                releaseExistingUserLinkForFailedApproval(profile.dataGsmUserId, now)
                val conflict = recordGitHubConflict(owner.userId, githubUserId, profile, now)
                completeConflictTransaction(transactionId, profile.displayName, now)
                GitHubConflictBlockResult.Blocked(conflict)
            }
        }

    override suspend fun approveLogin(
        transactionId: String,
        profile: UserProfileSnapshot,
        githubLink: GitHubLinkApproval,
        expectedProfileVersion: Long?,
        now: Instant,
    ): LoginApprovalResult =
        withContext(ioDispatcher) {
            requireUuid(transactionId, "transactionId")
            expectedProfileVersion?.let { requirePositive(it, "expectedProfileVersion") }
            val validatedProfile = profile.validated()
            val validatedLink = githubLink.validated()

            databaseTransaction {
                val transaction = findOAuthByIdForUpdate(transactionId)
                if (transaction?.status != OAuthDatabaseStatus.Processing) {
                    return@databaseTransaction LoginApprovalResult.TransactionUnavailable
                }
                if (!now.isBefore(transaction.expiresAt)) {
                    expireOAuthTransaction(transaction.id, now)
                    return@databaseTransaction LoginApprovalResult.TransactionUnavailable
                }

                val existingUser = findUserByDataGsmIdForUpdate(validatedProfile.dataGsmUserId)
                if (existingUser.hasStaleProfileVersion(expectedProfileVersion)) {
                    completeStaleProfileTransaction(transactionId, existingUser, now)
                    return@databaseTransaction LoginApprovalResult.StaleProfile
                }

                val existingOwner = findGitHubOwnerForUpdate(validatedLink.numericId)
                if (existingOwner != null && existingOwner.dataGsmUserId != validatedProfile.dataGsmUserId) {
                    check(existingOwner.isActive) {
                        "Inactive user ${existingOwner.userId} retains a GitHub identity"
                    }
                    releaseExistingUserLinkForFailedApproval(validatedProfile.dataGsmUserId, now)
                    val conflict = recordGitHubConflict(existingOwner.userId, validatedLink.numericId, validatedProfile, now)
                    completeConflictTransaction(transactionId, validatedProfile.displayName, now)
                    return@databaseTransaction LoginApprovalResult.Conflict(conflict)
                }

                val approvalSavepoint = setSavepoint()
                val linkResult =
                    try {
                        val user = upsertApprovedUser(validatedProfile, existingUser, now)
                        approveGitHubLink(user.userId, validatedLink, now)
                        user
                    } catch (failure: SQLException) {
                        if (!failure.isDuplicateKey()) throw failure
                        rollback(approvalSavepoint)
                        val racedOwner = findGitHubOwnerForUpdate(validatedLink.numericId)
                        if (racedOwner != null && racedOwner.dataGsmUserId != validatedProfile.dataGsmUserId) {
                            check(racedOwner.isActive) {
                                "Inactive user ${racedOwner.userId} retains a GitHub identity"
                            }
                            releaseExistingUserLinkForFailedApproval(validatedProfile.dataGsmUserId, now)
                            val conflict =
                                recordGitHubConflict(
                                    racedOwner.userId,
                                    validatedLink.numericId,
                                    validatedProfile,
                                    now,
                                )
                            completeConflictTransaction(transactionId, validatedProfile.displayName, now)
                            return@databaseTransaction LoginApprovalResult.Conflict(conflict)
                        }
                        val racedUser = findUserByDataGsmIdForUpdate(validatedProfile.dataGsmUserId)
                        if (expectedProfileVersion == null && racedUser != null) {
                            completeStaleProfileTransaction(transactionId, racedUser, now)
                            return@databaseTransaction LoginApprovalResult.StaleProfile
                        }
                        throw failure
                    }
                releaseSavepoint(approvalSavepoint)

                activateUser(linkResult.userId)
                resumeUserSessions(linkResult.userId, now)
                resolveCandidateConflictEpisodes(validatedProfile.dataGsmUserId, now)
                completeApprovedTransaction(transactionId, linkResult.userId, now)
                LoginApprovalResult.Approved(linkResult.userId, validatedProfile.displayName)
            }
        }

    override suspend fun consumePollAndCreateSession(
        transactionId: String,
        pollSecretHash: ByteArray,
        nonceHash: ByteArray,
        newSession: NewSession,
        now: Instant,
    ): PollConsumptionResult =
        withContext(ioDispatcher) {
            requireUuid(transactionId, "transactionId")
            requireDigest(pollSecretHash, "pollSecretHash")
            requireDigest(nonceHash, "nonceHash")
            validateNewSession(newSession)

            databaseTransaction {
                val transaction =
                    findOAuthForPollForUpdate(transactionId, pollSecretHash, nonceHash)
                        ?: return@databaseTransaction PollConsumptionResult.Unavailable
                if (transaction.terminalDeliveredAt != null) return@databaseTransaction PollConsumptionResult.Unavailable

                if (!now.isBefore(transaction.expiresAt)) {
                    if (
                        transaction.status == OAuthDatabaseStatus.Pending ||
                        transaction.status == OAuthDatabaseStatus.Processing
                    ) {
                        expireOAuthTransaction(transaction.id, now)
                    }
                    markTerminalDeliveredIfTerminal(transaction.id, now)
                    return@databaseTransaction PollConsumptionResult.Unavailable
                }

                when (transaction.status) {
                    OAuthDatabaseStatus.Pending,
                    OAuthDatabaseStatus.Processing,
                        -> {
                            PollConsumptionResult.Pending
                        }

                    OAuthDatabaseStatus.Expired -> {
                        markTerminalDeliveredIfTerminal(transaction.id, now)
                        PollConsumptionResult.Unavailable
                    }

                    OAuthDatabaseStatus.Failed -> {
                        val reason =
                            transaction.failureCode?.toAuthBlockReason()
                                ?: error("Failed OAuth transaction has no failure code")
                        markTerminalDelivered(transaction.id, now)
                        PollConsumptionResult.Blocked(reason, transaction.failureDisplayName)
                    }

                    OAuthDatabaseStatus.Succeeded -> {
                        val userId =
                            transaction.approvedUserId
                                ?: error("Succeeded OAuth transaction has no approved user")
                        val eligibility = checkUserEligibilityForUpdate(userId)
                        if (eligibility != null) {
                            markTerminalDelivered(transaction.id, now)
                            return@databaseTransaction PollConsumptionResult.Unavailable
                        }

                        val session =
                            insertSession(
                                transaction = transaction,
                                userId = userId,
                                newSession = newSession,
                                issuedAt = now,
                            )
                        markTerminalDelivered(transaction.id, now)
                        PollConsumptionResult.SessionCreated(userId, session)
                    }
                }
            }
        }

    override suspend fun listSessions(
        userId: Long,
        currentSessionId: String,
        now: Instant,
    ): List<StoredDesktopSession> =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            requireUuid(currentSessionId, "currentSessionId")
            dataSource.connection.use { connection ->
                connection.prepareStatement(LIST_ACTIVE_SESSIONS).use { statement ->
                    statement.setLong(1, userId)
                    statement.setUtcInstant(2, now)
                    statement.executeQuery().use { results ->
                        buildList {
                            while (results.next()) {
                                add(results.toStoredSession(currentSessionId))
                            }
                        }
                    }
                }
            }
        }

    override suspend fun revokeSession(
        userId: Long,
        sessionId: String,
        reason: SessionRevocationReason,
        now: Instant,
    ): Boolean =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            requireUuid(sessionId, "sessionId")
            databaseTransaction {
                if (!lockUser(userId)) return@databaseTransaction false
                val session = findSessionForUpdate(sessionId, userId) ?: return@databaseTransaction false
                if (session.revokedAt != null) return@databaseTransaction false
                revokeActiveRefreshCredential(sessionId, now)
                revokeSessionRow(sessionId, reason.databaseValue, now)
                true
            }
        }

    override suspend fun revokeAllSessions(
        userId: Long,
        reason: SessionRevocationReason,
        now: Instant,
    ): Int =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            databaseTransaction {
                if (!lockUser(userId)) return@databaseTransaction 0
                val revokedSessions = revokeUserSessions(userId, reason.databaseValue, now)
                revokeActiveRefreshCredentials(userId, now)
                revokedSessions
            }
        }

    override suspend fun checkAccess(
        userId: Long,
        sessionId: String,
        now: Instant,
    ): AccessCheck =
        withContext(ioDispatcher) {
            requirePositive(userId, "userId")
            requireUuid(sessionId, "sessionId")
            dataSource.connection.use { connection ->
                val session =
                    connection.findSessionAccess(userId, sessionId)
                        ?: return@use AccessCheck.Ineligible(AccessIneligibilityReason.SessionNotFound)
                val ineligibility = session.ineligibility(now)
                if (ineligibility == null) {
                    AccessCheck.Eligible(userId, sessionId, session.absoluteExpiresAt)
                } else {
                    AccessCheck.Ineligible(ineligibility)
                }
            }
        }

    override suspend fun rotateRefresh(
        sessionId: String,
        presentedCredentialHash: ByteArray,
        nextCredentialHash: ByteArray,
        hashVersion: Int,
        now: Instant,
    ): RefreshRotationResult =
        withContext(ioDispatcher) {
            requireUuid(sessionId, "sessionId")
            requireDigest(presentedCredentialHash, "presentedCredentialHash")
            requireDigest(nextCredentialHash, "nextCredentialHash")
            require(hashVersion in 1..MAX_HASH_VERSION) { "hashVersion is out of range" }
            require(!MessageDigest.isEqual(presentedCredentialHash, nextCredentialHash)) {
                "The rotated refresh credential must have a new digest"
            }

            databaseTransaction {
                val sessionUserId =
                    findSessionUserId(sessionId)
                        ?: return@databaseTransaction RefreshRotationResult.Invalid
                if (!lockUser(sessionUserId)) return@databaseTransaction RefreshRotationResult.Invalid
                val session =
                    findSessionAccessForUpdate(sessionId)
                        ?: return@databaseTransaction RefreshRotationResult.Invalid
                val ineligibility = session.ineligibility(now)
                when (ineligibility) {
                    AccessIneligibilityReason.SessionExpired -> {
                        revokeActiveRefreshCredential(sessionId, now)
                        revokeSessionRow(sessionId, SESSION_EXPIRED_REASON, now)
                        return@databaseTransaction RefreshRotationResult.Invalid
                    }

                    AccessIneligibilityReason.UserInactive -> {
                        revokeActiveRefreshCredential(sessionId, now)
                        revokeSessionRow(sessionId, DATAGSM_INACTIVE_REASON, now)
                        return@databaseTransaction RefreshRotationResult.Invalid
                    }

                    AccessIneligibilityReason.SessionNotFound,
                    AccessIneligibilityReason.SessionRevoked,
                        -> {
                            return@databaseTransaction RefreshRotationResult.Invalid
                        }

                    is AccessIneligibilityReason.AuthenticationBlocked,
                    null,
                        -> {}
                }

                val credential =
                    findRefreshCredentialForUpdate(sessionId, presentedCredentialHash)
                        ?: return@databaseTransaction RefreshRotationResult.Invalid

                if (credential.consumedAt != null) {
                    markRefreshReuse(sessionId, credential.id, maxOf(now, credential.consumedAt))
                    return@databaseTransaction RefreshRotationResult.Reused(sessionId)
                }
                if (credential.revokedAt != null) return@databaseTransaction RefreshRotationResult.Invalid

                when (ineligibility) {
                    is AccessIneligibilityReason.AuthenticationBlocked -> {
                        return@databaseTransaction RefreshRotationResult.Ineligible(
                            reason = ineligibility.reason,
                            userDisplayName =
                                session.userDisplayName.takeIf {
                                    ineligibility.reason == AuthBlockReason.GitHubIdConflict
                                },
                        )
                    }

                    AccessIneligibilityReason.SessionNotFound,
                    AccessIneligibilityReason.SessionExpired,
                    AccessIneligibilityReason.SessionRevoked,
                    AccessIneligibilityReason.UserInactive,
                        -> {
                            return@databaseTransaction RefreshRotationResult.Invalid
                        }

                    null -> {}
                }

                val nextGeneration = Math.addExact(credential.generation, 1L)
                val rotationTime = maxOf(now, credential.issuedAt)
                consumeRefreshCredential(credential.id, rotationTime)
                insertRefreshCredential(
                    sessionId = sessionId,
                    generation = nextGeneration,
                    credentialHash = nextCredentialHash,
                    hashVersion = hashVersion,
                    issuedAt = rotationTime,
                )
                touchSessionRefresh(sessionId, rotationTime)
                RefreshRotationResult.Rotated(
                    userId = session.userId,
                    sessionId = sessionId,
                    absoluteExpiresAt = session.absoluteExpiresAt,
                    generation = nextGeneration,
                )
            }
        }

    private fun Connection.findOAuthByStateForUpdate(stateHash: ByteArray): OAuthTransactionRow? =
        prepareStatement(SELECT_OAUTH_BY_STATE_FOR_UPDATE).use { statement ->
            statement.setBytes(1, stateHash)
            statement.executeQuery().use { results ->
                results.singleOrNull { toOAuthTransactionRow() }
            }
        }

    private fun Connection.findOAuthByIdForUpdate(transactionId: String): OAuthTransactionRow? =
        prepareStatement(SELECT_OAUTH_BY_ID_FOR_UPDATE).use { statement ->
            statement.setString(1, transactionId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toOAuthTransactionRow() }
            }
        }

    private fun Connection.findOAuthForPollForUpdate(
        transactionId: String,
        pollSecretHash: ByteArray,
        nonceHash: ByteArray,
    ): OAuthTransactionRow? =
        prepareStatement(SELECT_OAUTH_FOR_POLL_FOR_UPDATE).use { statement ->
            statement.setString(1, transactionId)
            statement.setBytes(2, pollSecretHash)
            statement.setBytes(3, nonceHash)
            statement.executeQuery().use { results ->
                results.singleOrNull { toOAuthTransactionRow() }
            }
        }

    private fun Connection.expireOAuthTransaction(
        transactionId: String,
        now: Instant,
    ) {
        prepareStatement(EXPIRE_OAUTH_TRANSACTION).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, transactionId)
            check(statement.executeUpdate() == 1) { "Locked OAuth transaction could not be expired" }
        }
    }

    private fun Connection.markTerminalDelivered(
        transactionId: String,
        now: Instant,
    ) {
        prepareStatement(MARK_TERMINAL_DELIVERED).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, transactionId)
            check(statement.executeUpdate() == 1) { "OAuth terminal result was already delivered" }
        }
    }

    private fun Connection.markTerminalDeliveredIfTerminal(
        transactionId: String,
        now: Instant,
    ) {
        prepareStatement(MARK_TERMINAL_DELIVERED_IF_TERMINAL).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, transactionId)
            statement.executeUpdate()
        }
    }

    private fun Connection.completeApprovedTransaction(
        transactionId: String,
        userId: Long,
        now: Instant,
    ) {
        prepareStatement(COMPLETE_APPROVED_TRANSACTION).use { statement ->
            statement.setLong(1, userId)
            statement.setUtcInstant(2, now)
            statement.setString(3, transactionId)
            check(statement.executeUpdate() == 1) { "Locked OAuth transaction could not be approved" }
        }
    }

    private fun Connection.completeConflictTransaction(
        transactionId: String,
        displayName: String,
        now: Instant,
    ) {
        prepareStatement(COMPLETE_BLOCKED_TRANSACTION).use { statement ->
            statement.setString(1, AuthBlockReason.GitHubIdConflict.databaseValue())
            statement.setString(2, displayName)
            statement.setUtcInstant(3, now)
            statement.setString(4, transactionId)
            check(statement.executeUpdate() == 1) { "Locked OAuth transaction could not be blocked" }
        }
    }

    private fun Connection.completeStaleProfileTransaction(
        transactionId: String,
        currentUser: UserRow?,
        now: Instant,
    ) {
        val reason =
            if (currentUser != null && !currentUser.isActive) {
                AuthBlockReason.NotTheMomentMember
            } else {
                AuthBlockReason.ExternalAuthenticationFailed
            }
        prepareStatement(COMPLETE_BLOCKED_TRANSACTION).use { statement ->
            statement.setString(1, reason.databaseValue())
            statement.setNullableString(2, null)
            statement.setUtcInstant(3, now)
            statement.setString(4, transactionId)
            check(statement.executeUpdate() == 1) { "Stale OAuth transaction could not be blocked" }
        }
    }

    private fun Connection.findUserByDataGsmIdForUpdate(dataGsmUserId: Long): UserRow? =
        prepareStatement(SELECT_USER_BY_DATAGSM_ID_FOR_UPDATE).use { statement ->
            statement.setLong(1, dataGsmUserId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toUserRow() }
            }
        }

    private fun Connection.findUserByIdForUpdate(userId: Long): UserRow? =
        prepareStatement(SELECT_USER_BY_ID_FOR_UPDATE).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toUserRow() }
            }
        }

    private fun Connection.lockUser(userId: Long): Boolean =
        prepareStatement(LOCK_USER_BY_ID).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun Connection.updateUserProfile(
        user: UserRow,
        profile: UserProfileSnapshot,
        now: Instant,
    ): Long {
        val nextVersion = Math.addExact(user.profileVersion, 1L)
        prepareStatement(UPDATE_USER_PROFILE).use { statement ->
            var index = statement.bindProfile(profile, startIndex = 1)
            statement.setLong(index++, nextVersion)
            statement.setUtcInstant(index++, now)
            statement.setLong(index, user.userId)
            check(statement.executeUpdate() == 1) { "Locked user profile could not be updated" }
        }
        return nextVersion
    }

    private fun Connection.advanceProfileVersion(user: UserRow) {
        prepareStatement(ADVANCE_PROFILE_VERSION).use { statement ->
            statement.setLong(1, Math.addExact(user.profileVersion, 1L))
            statement.setLong(2, user.userId)
            check(statement.executeUpdate() == 1) { "Locked user profile version could not be advanced" }
        }
    }

    private fun Connection.upsertApprovedUser(
        profile: UserProfileSnapshot,
        existing: UserRow?,
        now: Instant,
    ): UserRow {
        if (existing != null) {
            val profileVersion = updateUserProfile(existing, profile, now)
            return existing.copy(
                dataGsmGitHubLogin = profile.dataGsmGitHubLogin,
                profileVersion = profileVersion,
            )
        }

        val userId = insertUser(profile, now)
        return UserRow(
            userId = userId,
            dataGsmUserId = profile.dataGsmUserId,
            isActive = true,
            dataGsmGitHubLogin = profile.dataGsmGitHubLogin,
            profileVersion = INITIAL_PROFILE_VERSION,
            githubUserId = null,
            githubLogin = null,
            githubMembership = GitHubMembership.Unknown,
        )
    }

    private fun Connection.insertUser(
        profile: UserProfileSnapshot,
        now: Instant,
    ): Long =
        prepareStatement(INSERT_USER, Statement.RETURN_GENERATED_KEYS).use { statement ->
            val nextIndex = statement.bindProfile(profile, startIndex = 1)
            statement.setUtcInstant(nextIndex, now)
            check(statement.executeUpdate() == 1) { "User was not inserted" }
            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Inserted user id was not returned" }
                keys.getLong(1)
            }
        }

    private fun Connection.activateUser(userId: Long) {
        prepareStatement(ACTIVATE_USER).use { statement ->
            statement.setLong(1, userId)
            check(statement.executeUpdate() == 1) { "Locked user could not be activated" }
        }
    }

    private fun Connection.findGitHubOwnerForUpdate(githubUserId: Long): GitHubOwnerRow? =
        prepareStatement(SELECT_GITHUB_OWNER_FOR_UPDATE).use { statement ->
            statement.setLong(1, githubUserId)
            statement.executeQuery().use { results ->
                results.singleOrNull {
                    GitHubOwnerRow(
                        userId = getLong("user_id"),
                        dataGsmUserId = getLong("datagsm_user_id"),
                        isActive = getBoolean("is_active"),
                    )
                }
            }
        }

    private fun Connection.approveGitHubLink(
        userId: Long,
        link: GitHubLinkApproval,
        now: Instant,
    ) {
        val currentLink = findGitHubLinkForUpdate(userId)
        if (currentLink == null) {
            prepareStatement(INSERT_GITHUB_LINK).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, link.numericId)
                statement.setString(3, link.login)
                statement.setUtcInstant(4, now)
                statement.setUtcInstant(5, now)
                check(statement.executeUpdate() == 1) { "GitHub link was not inserted" }
            }
        } else {
            val linkedAt = if (currentLink.githubUserId == link.numericId) currentLink.linkedAt ?: now else now
            if (currentLink.githubUserId != null && currentLink.githubUserId != link.numericId) {
                resolveOwnedConflictEpisodes(userId, now)
            }
            prepareStatement(UPDATE_GITHUB_LINK).use { statement ->
                statement.setLong(1, link.numericId)
                statement.setString(2, link.login)
                statement.setUtcInstant(3, now)
                statement.setUtcInstant(4, linkedAt)
                statement.setLong(5, userId)
                check(statement.executeUpdate() == 1) { "Locked GitHub link could not be updated" }
            }
        }
    }

    private fun Connection.findGitHubLinkForUpdate(userId: Long): GitHubLinkRow? =
        prepareStatement(SELECT_GITHUB_LINK_FOR_UPDATE).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                results.singleOrNull {
                    GitHubLinkRow(
                        githubUserId = nullableLong("github_user_id"),
                        linkedAt = nullableUtcInstant("linked_at"),
                    )
                }
            }
        }

    private fun Connection.releaseExistingUserLinkForFailedApproval(
        dataGsmUserId: Long,
        now: Instant,
    ) {
        val existing = findUserByDataGsmIdForUpdate(dataGsmUserId) ?: return
        releaseGitHubLink(existing.userId, now)
        suspendUserSessions(existing.userId, AuthBlockReason.GitHubIdConflict, now)
        resolveOwnedConflictEpisodes(existing.userId, now)
    }

    private fun Connection.releaseGitHubLink(
        userId: Long,
        now: Instant,
    ) {
        prepareStatement(RELEASE_GITHUB_LINK).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.recordGitHubConflict(
        ownerUserId: Long,
        githubUserId: Long,
        profile: UserProfileSnapshot,
        now: Instant,
    ): GitHubConflict {
        resolveOtherCandidateConflictEpisodes(ownerUserId, profile.dataGsmUserId, githubUserId, now)
        val dedupeKey = conflictDedupeKey(ownerUserId, profile.dataGsmUserId, githubUserId)
        val existingId = findConflictEpisodeForUpdate(dedupeKey)
        if (existingId != null) {
            return GitHubConflict(
                ownerUserId = ownerUserId,
                episodeCreated = false,
                notificationCreated = false,
            )
        }

        val episode = insertConflictEpisode(ownerUserId, githubUserId, profile, dedupeKey, now)
        if (!episode.created) {
            return GitHubConflict(
                ownerUserId = ownerUserId,
                episodeCreated = false,
                notificationCreated = false,
            )
        }
        insertConflictNotification(
            conflictId = episode.id,
            recipientUserId = ownerUserId,
            githubUserId = githubUserId,
            conflictingDisplayName = profile.displayName,
            now = now,
        )
        markConflictNotified(episode.id, now)
        return GitHubConflict(
            ownerUserId = ownerUserId,
            episodeCreated = true,
            notificationCreated = true,
        )
    }

    private fun Connection.findConflictEpisodeForUpdate(dedupeKey: String): Long? =
        prepareStatement(SELECT_CONFLICT_EPISODE_FOR_UPDATE).use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { results ->
                results.singleOrNull { getLong("id") }
            }
        }

    private fun Connection.insertConflictEpisode(
        ownerUserId: Long,
        githubUserId: Long,
        profile: UserProfileSnapshot,
        dedupeKey: String,
        now: Instant,
    ): ConflictEpisodeInsert =
        try {
            prepareStatement(INSERT_CONFLICT_EPISODE, Statement.RETURN_GENERATED_KEYS).use { statement ->
                statement.setLong(1, ownerUserId)
                statement.setLong(2, profile.dataGsmUserId)
                statement.setLong(3, githubUserId)
                statement.setString(4, profile.displayName)
                statement.setString(5, dedupeKey)
                statement.setUtcInstant(6, now)
                check(statement.executeUpdate() == 1) { "GitHub conflict episode was not inserted" }
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "Inserted conflict episode id was not returned" }
                    ConflictEpisodeInsert(
                        id = keys.getLong(1),
                        created = true,
                    )
                }
            }
        } catch (failure: SQLException) {
            if (!failure.isDuplicateKey()) throw failure
            ConflictEpisodeInsert(
                id = findConflictEpisodeForUpdate(dedupeKey) ?: throw failure,
                created = false,
            )
        }

    private fun Connection.insertConflictNotification(
        conflictId: Long,
        recipientUserId: Long,
        githubUserId: Long,
        conflictingDisplayName: String,
        now: Instant,
    ) {
        val payload =
            buildJsonObject {
                put("conflictId", conflictId)
                put("githubUserId", githubUserId)
                put("conflictingDisplayName", conflictingDisplayName)
            }.toString()
        prepareStatement(INSERT_CONFLICT_NOTIFICATION).use { statement ->
            statement.setLong(1, recipientUserId)
            statement.setString(2, payload)
            statement.setString(3, conflictNotificationDedupeKey(conflictId))
            statement.setUtcInstant(4, now)
            check(statement.executeUpdate() == 1) { "GitHub conflict notification was not inserted" }
        }
    }

    private fun Connection.markConflictNotified(
        conflictId: Long,
        now: Instant,
    ) {
        prepareStatement(MARK_CONFLICT_NOTIFIED).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, conflictId)
            check(statement.executeUpdate() == 1) { "GitHub conflict episode was not marked notified" }
        }
    }

    private fun Connection.resolveConflictEpisodes(
        ownerUserId: Long,
        dataGsmUserId: Long,
        now: Instant,
    ) {
        prepareStatement(RESOLVE_CONFLICT_EPISODES).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, ownerUserId)
            statement.setLong(3, dataGsmUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.resolveOwnedConflictEpisodes(
        ownerUserId: Long,
        now: Instant,
    ) {
        prepareStatement(RESOLVE_OWNED_CONFLICT_EPISODES).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, ownerUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.resolveCandidateConflictEpisodes(
        dataGsmUserId: Long,
        now: Instant,
    ) {
        prepareStatement(RESOLVE_CONFLICTS_FOR_DATAGSM_USER).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, dataGsmUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.resolveOtherCandidateConflictEpisodes(
        ownerUserId: Long,
        dataGsmUserId: Long,
        githubUserId: Long,
        now: Instant,
    ) {
        prepareStatement(RESOLVE_OTHER_CANDIDATE_CONFLICT_EPISODES).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, dataGsmUserId)
            statement.setLong(3, ownerUserId)
            statement.setLong(4, githubUserId)
            statement.executeUpdate()
        }
    }

    private fun Connection.checkUserEligibilityForUpdate(userId: Long): AccessIneligibilityReason? =
        prepareStatement(SELECT_USER_ELIGIBILITY_FOR_UPDATE).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                val eligibility =
                    results.singleOrNull {
                        UserEligibilityRow(
                            isActive = getBoolean("is_active"),
                            githubUserId = nullableLong("github_user_id"),
                            githubMembership = getString("organization_membership").toGitHubMembership(),
                        )
                    } ?: return@use AccessIneligibilityReason.UserInactive
                eligibility.ineligibility()
            }
        }

    private fun Connection.insertSession(
        transaction: OAuthTransactionRow,
        userId: Long,
        newSession: NewSession,
        issuedAt: Instant,
    ): StoredDesktopSession {
        prepareStatement(INSERT_DESKTOP_SESSION).use { statement ->
            statement.setString(1, newSession.id)
            statement.setLong(2, userId)
            statement.setString(3, transaction.deviceName)
            statement.setString(4, transaction.devicePlatform.databaseValue())
            statement.setUtcInstant(5, issuedAt)
            check(statement.executeUpdate() == 1) { "Desktop session was not inserted" }
        }
        insertRefreshCredential(
            sessionId = newSession.id,
            generation = INITIAL_REFRESH_GENERATION,
            credentialHash = newSession.refreshCredentialHash,
            hashVersion = newSession.hashVersion,
            issuedAt = issuedAt,
        )
        val absoluteExpiresAt = findSessionAbsoluteExpiry(newSession.id)
        return StoredDesktopSession(
            id = newSession.id,
            userId = userId,
            deviceName = transaction.deviceName,
            devicePlatform = transaction.devicePlatform,
            issuedAt = issuedAt,
            absoluteExpiresAt = absoluteExpiresAt,
            isCurrent = true,
        )
    }

    private fun Connection.findSessionAbsoluteExpiry(sessionId: String): Instant =
        prepareStatement(SELECT_SESSION_ABSOLUTE_EXPIRY).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { results ->
                results.singleOrNull { utcInstant("absolute_expires_at") }
                    ?: error("Inserted desktop session was not found")
            }
        }

    private fun Connection.findSessionUserId(sessionId: String): Long? =
        prepareStatement(SELECT_SESSION_USER_ID).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { results ->
                results.singleOrNull { getLong("user_id") }
            }
        }

    private fun Connection.findSessionForUpdate(
        sessionId: String,
        userId: Long,
    ): SessionAccessRow? =
        prepareStatement(SELECT_SESSION_FOR_USER_FOR_UPDATE).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, userId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toSessionAccessRow() }
            }
        }

    private fun Connection.findSessionAccessForUpdate(sessionId: String): SessionAccessRow? =
        prepareStatement(SELECT_SESSION_ACCESS_FOR_UPDATE).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toSessionAccessRow() }
            }
        }

    private fun Connection.findSessionAccess(
        userId: Long,
        sessionId: String,
    ): SessionAccessRow? =
        prepareStatement(SELECT_SESSION_ACCESS).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, userId)
            statement.executeQuery().use { results ->
                results.singleOrNull { toSessionAccessRow() }
            }
        }

    private fun Connection.suspendUserSessions(
        userId: Long,
        reason: AuthBlockReason,
        now: Instant,
    ): Int =
        prepareStatement(SUSPEND_USER_SESSIONS).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, reason.databaseValue())
            statement.setLong(3, userId)
            statement.setUtcInstant(4, now)
            statement.executeUpdate()
        }

    private fun Connection.resumeUserSessions(
        userId: Long,
        now: Instant,
    ): Int =
        prepareStatement(RESUME_USER_SESSIONS).use { statement ->
            statement.setLong(1, userId)
            statement.setUtcInstant(2, now)
            statement.executeUpdate()
        }

    private fun Connection.revokeUserSessions(
        userId: Long,
        reason: String,
        now: Instant,
    ): Int =
        prepareStatement(REVOKE_USER_SESSIONS).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, reason)
            statement.setLong(3, userId)
            statement.executeUpdate()
        }

    private fun Connection.revokeSessionRow(
        sessionId: String,
        reason: String,
        now: Instant,
    ) {
        prepareStatement(REVOKE_SESSION).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, reason)
            statement.setString(3, sessionId)
            check(statement.executeUpdate() == 1) { "Locked desktop session could not be revoked" }
        }
    }

    private fun Connection.findRefreshCredentialForUpdate(
        sessionId: String,
        credentialHash: ByteArray,
    ): RefreshCredentialRow? =
        prepareStatement(SELECT_REFRESH_CREDENTIAL_FOR_UPDATE).use { statement ->
            statement.setString(1, sessionId)
            statement.setBytes(2, credentialHash)
            statement.executeQuery().use { results ->
                results.singleOrNull {
                    RefreshCredentialRow(
                        id = getLong("id"),
                        generation = getLong("generation"),
                        issuedAt = utcInstant("issued_at"),
                        consumedAt = nullableUtcInstant("consumed_at"),
                        revokedAt = nullableUtcInstant("revoked_at"),
                    )
                }
            }
        }

    private fun Connection.insertRefreshCredential(
        sessionId: String,
        generation: Long,
        credentialHash: ByteArray,
        hashVersion: Int,
        issuedAt: Instant,
    ) {
        prepareStatement(INSERT_REFRESH_CREDENTIAL).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, generation)
            statement.setBytes(3, credentialHash)
            statement.setInt(4, hashVersion)
            statement.setUtcInstant(5, issuedAt)
            check(statement.executeUpdate() == 1) { "Refresh credential was not inserted" }
        }
    }

    private fun Connection.consumeRefreshCredential(
        credentialId: Long,
        now: Instant,
    ) {
        prepareStatement(CONSUME_REFRESH_CREDENTIAL).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, credentialId)
            check(statement.executeUpdate() == 1) { "Locked refresh credential could not be consumed" }
        }
    }

    private fun Connection.revokeActiveRefreshCredential(
        sessionId: String,
        now: Instant,
    ): Int =
        prepareStatement(REVOKE_ACTIVE_REFRESH_CREDENTIAL).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, sessionId)
            statement.executeUpdate()
        }

    private fun Connection.revokeActiveRefreshCredentials(
        userId: Long,
        now: Instant,
    ): Int =
        prepareStatement(REVOKE_USER_REFRESH_CREDENTIALS).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }

    private fun Connection.markRefreshReuse(
        sessionId: String,
        credentialId: Long,
        now: Instant,
    ) {
        prepareStatement(MARK_REFRESH_CREDENTIAL_REUSED).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setLong(2, credentialId)
            check(statement.executeUpdate() == 1) { "Consumed refresh credential could not be marked reused" }
        }
        revokeActiveRefreshCredential(sessionId, now)
        prepareStatement(MARK_SESSION_REFRESH_REUSE).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setUtcInstant(2, now)
            statement.setString(3, sessionId)
            check(statement.executeUpdate() == 1) { "Refresh credential family session was not revoked" }
        }
    }

    private fun Connection.touchSessionRefresh(
        sessionId: String,
        now: Instant,
    ) {
        prepareStatement(TOUCH_SESSION_REFRESH).use { statement ->
            statement.setUtcInstant(1, now)
            statement.setString(2, sessionId)
            check(statement.executeUpdate() == 1) { "Rotated desktop session was not updated" }
        }
    }

    private fun <T> databaseTransaction(block: Connection.() -> T): T {
        var attempt = 1
        while (true) {
            try {
                return dataSource.inTransaction(block = block)
            } catch (failure: SQLException) {
                if (attempt >= MAX_TRANSACTION_ATTEMPTS || !failure.isRetryableTransactionFailure()) {
                    throw failure
                }
                attempt += 1
            }
        }
    }

    private fun validateNewOAuthTransaction(transaction: NewOAuthTransaction) {
        requireUuid(transaction.id, "transaction.id")
        requireDigest(transaction.stateHash, "transaction.stateHash")
        requireDigest(transaction.pollSecretHash, "transaction.pollSecretHash")
        requireDigest(transaction.pkceChallengeDigest, "transaction.pkceChallengeDigest")
        requireDigest(transaction.nonceHash, "transaction.nonceHash")
        requireSizedText(transaction.deviceName, MAX_DEVICE_NAME_LENGTH, "transaction.deviceName")
        require(transaction.createdAt.isBefore(transaction.expiresAt)) {
            "OAuth transaction expiry must be after creation"
        }
    }

    private fun validateNewSession(session: NewSession) {
        requireUuid(session.id, "newSession.id")
        requireDigest(session.refreshCredentialHash, "newSession.refreshCredentialHash")
        require(session.hashVersion in 1..MAX_HASH_VERSION) { "newSession.hashVersion is out of range" }
    }

    private fun validateFailureDisplayName(
        reason: AuthBlockReason,
        failureDisplayName: String?,
    ) {
        if (reason == AuthBlockReason.GitHubIdConflict) {
            requireSizedText(
                failureDisplayName ?: throw IllegalArgumentException(
                    "failureDisplayName is required for a GitHub ID conflict",
                ),
                MAX_DISPLAY_NAME_LENGTH,
                "failureDisplayName",
            )
        } else {
            require(failureDisplayName == null) {
                "failureDisplayName is only permitted for a GitHub ID conflict"
            }
        }
    }

    private fun UserProfileSnapshot.validated(): UserProfileSnapshot {
        requirePositive(dataGsmUserId, "profile.dataGsmUserId")
        val validatedDisplayName = requireSizedText(displayName, MAX_DISPLAY_NAME_LENGTH, "profile.displayName")
        val validatedEmail = requireSizedText(email, MAX_EMAIL_LENGTH, "profile.email")
        val validatedAccountStatus =
            requireSizedText(
                dataGsmAccountStatus,
                MAX_DATAGSM_STATUS_LENGTH,
                "profile.dataGsmAccountStatus",
            )
        val validatedStudentRole =
            requireSizedText(
                dataGsmStudentRole,
                MAX_DATAGSM_ROLE_LENGTH,
                "profile.dataGsmStudentRole",
            )
        require((majorClubId == null) == (majorClubName == null)) {
            "profile.majorClubId and profile.majorClubName must both be present or absent"
        }
        majorClubId?.let { requirePositive(it, "profile.majorClubId") }
        val validatedMajorClubName =
            majorClubName?.let {
                requireSizedText(it, MAX_MAJOR_CLUB_NAME_LENGTH, "profile.majorClubName")
            }
        val validatedGitHubLogin =
            dataGsmGitHubLogin?.let {
                requireSizedText(it, MAX_GITHUB_LOGIN_LENGTH, "profile.dataGsmGitHubLogin")
            }
        return copy(
            displayName = validatedDisplayName,
            email = validatedEmail,
            dataGsmAccountStatus = validatedAccountStatus,
            dataGsmStudentRole = validatedStudentRole,
            majorClubName = validatedMajorClubName,
            dataGsmGitHubLogin = validatedGitHubLogin,
        )
    }

    private fun GitHubLinkApproval.validated(): GitHubLinkApproval {
        requirePositive(numericId, "githubLink.numericId")
        return copy(login = requireSizedText(login, MAX_GITHUB_LOGIN_LENGTH, "githubLink.login"))
    }

    private fun PreparedStatement.bindProfile(
        profile: UserProfileSnapshot,
        startIndex: Int,
    ): Int {
        var index = startIndex
        setLong(index++, profile.dataGsmUserId)
        setString(index++, profile.displayName)
        setString(index++, profile.email)
        setString(index++, profile.dataGsmAccountStatus)
        setString(index++, profile.dataGsmStudentRole)
        setNullableLong(index++, profile.majorClubId)
        setNullableString(index++, profile.majorClubName)
        setNullableString(index++, profile.dataGsmGitHubLogin)
        return index
    }
}

private data class OAuthTransactionRow(
    val id: String,
    val pkceChallengeDigest: ByteArray,
    val nonceHash: ByteArray,
    val status: OAuthDatabaseStatus,
    val approvedUserId: Long?,
    val failureCode: String?,
    val failureDisplayName: String?,
    val deviceName: String,
    val devicePlatform: DesktopPlatform,
    val expiresAt: Instant,
    val terminalDeliveredAt: Instant?,
)

private enum class OAuthDatabaseStatus {
    Pending,
    Processing,
    Succeeded,
    Failed,
    Expired,
}

private data class UserRow(
    val userId: Long,
    val dataGsmUserId: Long,
    val isActive: Boolean,
    val dataGsmGitHubLogin: String?,
    val profileVersion: Long,
    val githubUserId: Long?,
    val githubLogin: String?,
    val githubMembership: GitHubMembership,
)

private fun UserRow?.hasStaleProfileVersion(expectedProfileVersion: Long?): Boolean =
    if (expectedProfileVersion == null) {
        this != null
    } else {
        this?.profileVersion != expectedProfileVersion
    }

private data class GitHubOwnerRow(
    val userId: Long,
    val dataGsmUserId: Long,
    val isActive: Boolean,
)

private data class GitHubLinkRow(
    val githubUserId: Long?,
    val linkedAt: Instant?,
)

private data class ConflictEpisodeInsert(
    val id: Long,
    val created: Boolean,
)

private data class UserEligibilityRow(
    val isActive: Boolean,
    val githubUserId: Long?,
    val githubMembership: GitHubMembership,
) {
    fun ineligibility(): AccessIneligibilityReason? {
        if (!isActive) return AccessIneligibilityReason.UserInactive
        if (githubUserId == null) {
            return AccessIneligibilityReason.AuthenticationBlocked(AuthBlockReason.GitHubIdMissing)
        }
        if (githubMembership != GitHubMembership.Member) {
            return AccessIneligibilityReason.AuthenticationBlocked(
                AuthBlockReason.GitHubOrganizationMembershipRequired,
            )
        }
        return null
    }
}

private data class SessionAccessRow(
    val userId: Long,
    val userDisplayName: String,
    val absoluteExpiresAt: Instant,
    val suspendedAt: Instant?,
    val suspensionReason: String?,
    val revokedAt: Instant?,
    val isUserActive: Boolean,
    val githubUserId: Long?,
    val githubMembership: GitHubMembership,
) {
    fun ineligibility(now: Instant): AccessIneligibilityReason? {
        if (revokedAt != null) return AccessIneligibilityReason.SessionRevoked
        if (!now.isBefore(absoluteExpiresAt)) return AccessIneligibilityReason.SessionExpired
        if (!isUserActive) return AccessIneligibilityReason.UserInactive
        if (suspendedAt != null) {
            val reason =
                suspensionReason?.toAuthBlockReason()
                    ?: error("Suspended desktop session has no suspension reason")
            return AccessIneligibilityReason.AuthenticationBlocked(reason)
        }
        if (githubUserId == null) {
            return AccessIneligibilityReason.AuthenticationBlocked(AuthBlockReason.GitHubIdMissing)
        }
        if (githubMembership != GitHubMembership.Member) {
            return AccessIneligibilityReason.AuthenticationBlocked(
                AuthBlockReason.GitHubOrganizationMembershipRequired,
            )
        }
        return null
    }
}

private data class RefreshCredentialRow(
    val id: Long,
    val generation: Long,
    val issuedAt: Instant,
    val consumedAt: Instant?,
    val revokedAt: Instant?,
)

private fun ResultSet.toOAuthTransactionRow(): OAuthTransactionRow =
    OAuthTransactionRow(
        id = getString("id"),
        pkceChallengeDigest = getBytes("pkce_challenge_digest"),
        nonceHash = getBytes("nonce_hash"),
        status = getString("status").toOAuthDatabaseStatus(),
        approvedUserId = nullableLong("approved_user_id"),
        failureCode = getString("failure_code"),
        failureDisplayName = getString("failure_display_name"),
        deviceName = getString("device_name"),
        devicePlatform = getString("device_platform").toDesktopPlatform(),
        expiresAt = utcInstant("expires_at"),
        terminalDeliveredAt = nullableUtcInstant("terminal_delivered_at"),
    )

private fun ResultSet.toUserRow(): UserRow =
    UserRow(
        userId = getLong("user_id"),
        dataGsmUserId = getLong("datagsm_user_id"),
        isActive = getBoolean("is_active"),
        dataGsmGitHubLogin = getString("datagsm_github_login"),
        profileVersion = getLong("profile_version"),
        githubUserId = nullableLong("github_user_id"),
        githubLogin = getString("github_login"),
        githubMembership = getString("organization_membership").toGitHubMembership(),
    )

private fun ResultSet.toStoredSession(currentSessionId: String): StoredDesktopSession =
    StoredDesktopSession(
        id = getString("id"),
        userId = getLong("user_id"),
        deviceName = getString("device_name"),
        devicePlatform = getString("device_platform").toDesktopPlatform(),
        issuedAt = utcInstant("issued_at"),
        absoluteExpiresAt = utcInstant("absolute_expires_at"),
        isCurrent = getString("id") == currentSessionId,
    )

private fun ResultSet.toSessionAccessRow(): SessionAccessRow =
    SessionAccessRow(
        userId = getLong("user_id"),
        userDisplayName = getString("user_display_name"),
        absoluteExpiresAt = utcInstant("absolute_expires_at"),
        suspendedAt = nullableUtcInstant("suspended_at"),
        suspensionReason = getString("suspension_reason"),
        revokedAt = nullableUtcInstant("revoked_at"),
        isUserActive = getBoolean("is_active"),
        githubUserId = nullableLong("github_user_id"),
        githubMembership = getString("organization_membership").toGitHubMembership(),
    )

private inline fun <T> ResultSet.singleOrNull(transform: ResultSet.() -> T): T? {
    if (!next()) return null
    val value = transform()
    check(!next()) { "Expected at most one database row" }
    return value
}

private fun ResultSet.nullableLong(columnLabel: String): Long? {
    val value = getLong(columnLabel)
    return if (wasNull()) null else value
}

private fun ResultSet.utcInstant(columnLabel: String): Instant = getObject(columnLabel, LocalDateTime::class.java).toInstant(ZoneOffset.UTC)

private fun ResultSet.nullableUtcInstant(columnLabel: String): Instant? =
    getObject(columnLabel, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

private fun PreparedStatement.setUtcInstant(
    parameterIndex: Int,
    value: Instant,
) {
    setObject(parameterIndex, LocalDateTime.ofInstant(value, ZoneOffset.UTC))
}

private fun PreparedStatement.setNullableString(
    parameterIndex: Int,
    value: String?,
) {
    if (value == null) {
        setNull(parameterIndex, Types.VARCHAR)
    } else {
        setString(parameterIndex, value)
    }
}

private fun PreparedStatement.setNullableLong(
    parameterIndex: Int,
    value: Long?,
) {
    if (value == null) {
        setNull(parameterIndex, Types.BIGINT)
    } else {
        setLong(parameterIndex, value)
    }
}

private fun requireDigest(
    digest: ByteArray,
    name: String,
) {
    require(digest.size == SHA_256_DIGEST_BYTES) { "$name must be a SHA-256 digest" }
}

private fun requireUuid(
    value: String,
    name: String,
) {
    val parsed =
        try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("$name must be a canonical UUID", failure)
        }
    require(parsed.toString() == value) { "$name must be a lowercase canonical UUID" }
}

private fun requirePositive(
    value: Long,
    name: String,
) {
    require(value > 0) { "$name must be positive" }
}

private fun requireSizedText(
    value: String,
    maximumLength: Int,
    name: String,
): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$name must not be blank" }
    require(normalized.length <= maximumLength) { "$name must contain at most $maximumLength characters" }
    return normalized
}

private fun AuthBlockReason.databaseValue(): String =
    when (this) {
        AuthBlockReason.NotTheMomentMember -> "not_the_moment_member"
        AuthBlockReason.GitHubIdMissing -> "github_id_missing"
        AuthBlockReason.GitHubAccountNotFound -> "github_account_not_found"
        AuthBlockReason.GitHubIdConflict -> "github_id_conflict"
        AuthBlockReason.GitHubOrganizationMembershipRequired -> "github_organization_membership_required"
        AuthBlockReason.ExternalAuthenticationFailed -> "external_authentication_failed"
    }

private fun String.toAuthBlockReason(): AuthBlockReason =
    when (this) {
        "not_the_moment_member" -> AuthBlockReason.NotTheMomentMember
        "github_id_missing" -> AuthBlockReason.GitHubIdMissing
        "github_account_not_found" -> AuthBlockReason.GitHubAccountNotFound
        "github_id_conflict" -> AuthBlockReason.GitHubIdConflict
        "github_organization_membership_required" -> AuthBlockReason.GitHubOrganizationMembershipRequired
        "external_authentication_failed" -> AuthBlockReason.ExternalAuthenticationFailed
        else -> error("Unknown authentication block reason in database: $this")
    }

private fun DesktopPlatform.databaseValue(): String =
    when (this) {
        DesktopPlatform.MacOS -> "MACOS"
        DesktopPlatform.Windows -> "WINDOWS"
    }

private fun String.toDesktopPlatform(): DesktopPlatform =
    when (this) {
        "MACOS" -> DesktopPlatform.MacOS
        "WINDOWS" -> DesktopPlatform.Windows
        else -> error("Unknown desktop platform in database: $this")
    }

private fun String?.toGitHubMembership(): GitHubMembership =
    when (this) {
        null, "UNKNOWN" -> GitHubMembership.Unknown
        "MEMBER" -> GitHubMembership.Member
        "NOT_MEMBER" -> GitHubMembership.NotMember
        else -> error("Unknown GitHub membership in database: $this")
    }

private fun String.toOAuthDatabaseStatus(): OAuthDatabaseStatus =
    when (this) {
        "PENDING" -> OAuthDatabaseStatus.Pending
        "PROCESSING" -> OAuthDatabaseStatus.Processing
        "SUCCEEDED" -> OAuthDatabaseStatus.Succeeded
        "FAILED" -> OAuthDatabaseStatus.Failed
        "EXPIRED" -> OAuthDatabaseStatus.Expired
        else -> error("Unknown OAuth transaction status in database: $this")
    }

private fun SQLException.isDuplicateKey(): Boolean {
    var current: SQLException? = this
    while (current != null) {
        if (current.errorCode == MYSQL_DUPLICATE_KEY) {
            return true
        }
        current = current.nextException
    }
    return false
}

private fun SQLException.isRetryableTransactionFailure(): Boolean {
    var current: SQLException? = this
    while (current != null) {
        if (
            current.sqlState == SQL_STATE_TRANSACTION_ROLLBACK ||
            current.errorCode == MYSQL_LOCK_WAIT_TIMEOUT ||
            current.errorCode == MYSQL_DEADLOCK
        ) {
            return true
        }
        current = current.nextException
    }
    return false
}

private fun conflictDedupeKey(
    ownerUserId: Long,
    conflictingDataGsmUserId: Long,
    githubUserId: Long,
): String = "github-conflict:$ownerUserId:$conflictingDataGsmUserId:$githubUserId"

private fun conflictNotificationDedupeKey(conflictId: Long): String = "github-conflict-notification:$conflictId"

private const val SHA_256_DIGEST_BYTES = 32
private const val MAX_HASH_VERSION = 65_535
private const val MAX_DEVICE_NAME_LENGTH = 128
private const val MAX_DISPLAY_NAME_LENGTH = 128
private const val MAX_EMAIL_LENGTH = 254
private const val MAX_DATAGSM_STATUS_LENGTH = 64
private const val MAX_DATAGSM_ROLE_LENGTH = 64
private const val MAX_MAJOR_CLUB_NAME_LENGTH = 128
private const val MAX_GITHUB_LOGIN_LENGTH = 255
private const val INITIAL_PROFILE_VERSION = 1L
private const val INITIAL_REFRESH_GENERATION = 1L
private const val MAX_TRANSACTION_ATTEMPTS = 3
private const val MYSQL_DUPLICATE_KEY = 1062
private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205
private const val MYSQL_DEADLOCK = 1213
private const val SQL_STATE_TRANSACTION_ROLLBACK = "40001"
private const val DATAGSM_INACTIVE_REASON = "DATAGSM_INACTIVE"
private const val SESSION_EXPIRED_REASON = "ABSOLUTE_SESSION_EXPIRY"

private val LINK_RELEASE_BLOCK_REASONS =
    setOf(
        AuthBlockReason.GitHubIdMissing,
        AuthBlockReason.GitHubAccountNotFound,
        AuthBlockReason.GitHubIdConflict,
        AuthBlockReason.GitHubOrganizationMembershipRequired,
        AuthBlockReason.ExternalAuthenticationFailed,
    )

private val INSERT_OAUTH_TRANSACTION =
    """
    INSERT INTO oauth_transactions (
        id, state_hash, poll_secret_hash, pkce_challenge_digest, nonce_hash,
        device_name, device_platform, created_at, expires_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

private val OAUTH_ROW_COLUMNS =
    """
    id, pkce_challenge_digest, nonce_hash, status, approved_user_id,
    failure_code, failure_display_name, device_name, device_platform,
    expires_at, terminal_delivered_at
    """.trimIndent()

private val SELECT_OAUTH_BY_STATE_FOR_UPDATE =
    "SELECT $OAUTH_ROW_COLUMNS FROM oauth_transactions WHERE state_hash = ? FOR UPDATE"

private val SELECT_OAUTH_BY_ID_FOR_UPDATE =
    "SELECT $OAUTH_ROW_COLUMNS FROM oauth_transactions WHERE id = ? FOR UPDATE"

private val SELECT_OAUTH_FOR_POLL_FOR_UPDATE =
    """
    SELECT $OAUTH_ROW_COLUMNS
    FROM oauth_transactions
    WHERE id = ? AND poll_secret_hash = ? AND nonce_hash = ?
    FOR UPDATE
    """.trimIndent()

private val CLAIM_OAUTH_TRANSACTION =
    """
    UPDATE oauth_transactions
    SET status = 'PROCESSING', processing_started_at = GREATEST(?, created_at)
    WHERE id = ? AND status = 'PENDING'
    """.trimIndent()

private val EXPIRE_OAUTH_TRANSACTION =
    """
    UPDATE oauth_transactions
    SET status = 'EXPIRED', failure_code = 'transaction_expired', failure_display_name = NULL,
        completed_at = GREATEST(?, created_at, COALESCE(processing_started_at, created_at))
    WHERE id = ? AND status IN ('PENDING', 'PROCESSING')
    """.trimIndent()

private val COMPLETE_APPROVED_TRANSACTION =
    """
    UPDATE oauth_transactions
    SET status = 'SUCCEEDED', approved_user_id = ?, failure_code = NULL, failure_display_name = NULL,
        completed_at = GREATEST(?, created_at, processing_started_at)
    WHERE id = ? AND status = 'PROCESSING'
    """.trimIndent()

private val COMPLETE_BLOCKED_TRANSACTION =
    """
    UPDATE oauth_transactions
    SET status = 'FAILED', approved_user_id = NULL, failure_code = ?, failure_display_name = ?,
        completed_at = GREATEST(?, created_at, processing_started_at)
    WHERE id = ? AND status = 'PROCESSING'
    """.trimIndent()

private val MARK_TERMINAL_DELIVERED =
    """
    UPDATE oauth_transactions
    SET terminal_delivered_at = GREATEST(?, completed_at)
    WHERE id = ? AND terminal_delivered_at IS NULL AND status IN ('SUCCEEDED', 'FAILED', 'EXPIRED')
    """.trimIndent()

private val MARK_TERMINAL_DELIVERED_IF_TERMINAL = MARK_TERMINAL_DELIVERED

private val USER_ROW_SELECT =
    """
    SELECT u.id AS user_id, u.datagsm_user_id, u.is_active, u.datagsm_github_login, u.profile_version,
           l.github_user_id, l.github_login, l.organization_membership
    FROM users u
    LEFT JOIN user_github_links l ON l.user_id = u.id
    """.trimIndent()

private val SELECT_USER_BY_DATAGSM_ID_FOR_UPDATE =
    "$USER_ROW_SELECT WHERE u.datagsm_user_id = ? FOR UPDATE"

private val SELECT_USER_BY_ID_FOR_UPDATE =
    "$USER_ROW_SELECT WHERE u.id = ? FOR UPDATE"

private const val LOCK_USER_BY_ID = "SELECT id FROM users WHERE id = ? FOR UPDATE"

private val UPDATE_USER_PROFILE =
    """
    UPDATE users
    SET datagsm_user_id = ?, display_name = ?, email = ?, datagsm_account_status = ?,
        datagsm_student_role = ?, major_club_id = ?, major_club_name = ?, datagsm_github_login = ?,
        profile_version = ?, profile_synced_at = ?
    WHERE id = ?
    """.trimIndent()

private val INSERT_USER =
    """
    INSERT INTO users (
        datagsm_user_id, display_name, email, datagsm_account_status, datagsm_student_role,
        major_club_id, major_club_name, datagsm_github_login, profile_synced_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

private val DEACTIVATE_USER =
    """
    UPDATE users
    SET is_active = FALSE, deactivated_at = ?
    WHERE id = ?
    """.trimIndent()

private const val ACTIVATE_USER = "UPDATE users SET is_active = TRUE, deactivated_at = NULL WHERE id = ?"

private const val ADVANCE_PROFILE_VERSION = "UPDATE users SET profile_version = ? WHERE id = ?"

private val SELECT_GITHUB_OWNER_FOR_UPDATE =
    """
    SELECT l.user_id, u.datagsm_user_id, u.is_active
    FROM user_github_links l
    JOIN users u ON u.id = l.user_id
    WHERE l.github_user_id = ?
    FOR UPDATE
    """.trimIndent()

private val SELECT_GITHUB_LINK_FOR_UPDATE =
    "SELECT github_user_id, linked_at FROM user_github_links WHERE user_id = ? FOR UPDATE"

private val INSERT_GITHUB_LINK =
    """
    INSERT INTO user_github_links (
        user_id, github_user_id, github_login, organization_membership, membership_checked_at, linked_at
    ) VALUES (?, ?, ?, 'MEMBER', ?, ?)
    """.trimIndent()

private val UPDATE_GITHUB_LINK =
    """
    UPDATE user_github_links
    SET github_user_id = ?, github_login = ?, organization_membership = 'MEMBER',
        membership_checked_at = ?, linked_at = ?, unlinked_at = NULL
    WHERE user_id = ?
    """.trimIndent()

private val MARK_GITHUB_MEMBERSHIP_LOST =
    """
    UPDATE user_github_links
    SET organization_membership = 'NOT_MEMBER', membership_checked_at = ?
    WHERE user_id = ? AND github_user_id IS NOT NULL
    """.trimIndent()

private val RELEASE_GITHUB_LINK =
    """
    UPDATE user_github_links
    SET unlinked_at = CASE WHEN github_user_id IS NOT NULL THEN ? ELSE unlinked_at END,
        github_user_id = NULL, github_login = NULL, organization_membership = 'UNKNOWN',
        membership_checked_at = NULL
    WHERE user_id = ?
    """.trimIndent()

private val SELECT_CONFLICT_EPISODE_FOR_UPDATE =
    "SELECT id FROM github_link_conflicts WHERE dedupe_key = ? AND resolved_at IS NULL FOR UPDATE"

private val INSERT_CONFLICT_EPISODE =
    """
    INSERT INTO github_link_conflicts (
        owner_user_id, conflicting_datagsm_user_id, github_user_id,
        conflicting_display_name, dedupe_key, created_at
    ) VALUES (?, ?, ?, ?, ?, ?)
    """.trimIndent()

private val INSERT_CONFLICT_NOTIFICATION =
    """
    INSERT INTO notifications (recipient_user_id, type, payload_json, dedupe_key, created_at)
    VALUES (?, 'GITHUB_ID_CONFLICT', ?, ?, ?)
    """.trimIndent()

private val MARK_CONFLICT_NOTIFIED =
    """
    UPDATE github_link_conflicts
    SET notified_at = GREATEST(?, created_at)
    WHERE id = ? AND notified_at IS NULL
    """.trimIndent()

private val RESOLVE_CONFLICT_EPISODES =
    """
    UPDATE github_link_conflicts
    SET resolved_at = GREATEST(?, created_at), dedupe_key = NULL
    WHERE resolved_at IS NULL AND (owner_user_id = ? OR conflicting_datagsm_user_id = ?)
    """.trimIndent()

private val RESOLVE_CONFLICTS_FOR_DATAGSM_USER =
    """
    UPDATE github_link_conflicts
    SET resolved_at = GREATEST(?, created_at), dedupe_key = NULL
    WHERE resolved_at IS NULL AND conflicting_datagsm_user_id = ?
    """.trimIndent()

private val RESOLVE_OWNED_CONFLICT_EPISODES =
    """
    UPDATE github_link_conflicts
    SET resolved_at = GREATEST(?, created_at), dedupe_key = NULL
    WHERE resolved_at IS NULL AND owner_user_id = ?
    """.trimIndent()

private val RESOLVE_OTHER_CANDIDATE_CONFLICT_EPISODES =
    """
    UPDATE github_link_conflicts
    SET resolved_at = GREATEST(?, created_at), dedupe_key = NULL
    WHERE resolved_at IS NULL AND conflicting_datagsm_user_id = ?
      AND (owner_user_id <> ? OR github_user_id <> ?)
    """.trimIndent()

private val SELECT_USER_ELIGIBILITY_FOR_UPDATE =
    """
    SELECT u.is_active, l.github_user_id, l.organization_membership
    FROM users u
    LEFT JOIN user_github_links l ON l.user_id = u.id
    WHERE u.id = ?
    FOR UPDATE
    """.trimIndent()

private val INSERT_DESKTOP_SESSION =
    """
    INSERT INTO desktop_sessions (id, user_id, device_name, device_platform, issued_at)
    VALUES (?, ?, ?, ?, ?)
    """.trimIndent()

private const val SELECT_SESSION_ABSOLUTE_EXPIRY =
    "SELECT absolute_expires_at FROM desktop_sessions WHERE id = ?"

private const val SELECT_SESSION_USER_ID = "SELECT user_id FROM desktop_sessions WHERE id = ?"

private val LIST_ACTIVE_SESSIONS =
    """
    SELECT id, user_id, device_name, device_platform, issued_at, absolute_expires_at
    FROM desktop_sessions
    WHERE user_id = ? AND revoked_at IS NULL AND absolute_expires_at > ?
    ORDER BY issued_at DESC, id
    """.trimIndent()

private val SESSION_ACCESS_SELECT =
    """
    SELECT s.user_id, s.absolute_expires_at, s.suspended_at, s.suspension_reason, s.revoked_at,
           u.display_name AS user_display_name, u.is_active, l.github_user_id, l.organization_membership
    FROM desktop_sessions s
    JOIN users u ON u.id = s.user_id
    LEFT JOIN user_github_links l ON l.user_id = s.user_id
    """.trimIndent()

private val SELECT_SESSION_FOR_USER_FOR_UPDATE =
    "$SESSION_ACCESS_SELECT WHERE s.id = ? AND s.user_id = ? FOR UPDATE"

private val SELECT_SESSION_ACCESS_FOR_UPDATE =
    "$SESSION_ACCESS_SELECT WHERE s.id = ? FOR UPDATE"

private val SELECT_SESSION_ACCESS =
    "$SESSION_ACCESS_SELECT WHERE s.id = ? AND s.user_id = ?"

private val SUSPEND_USER_SESSIONS =
    """
    UPDATE desktop_sessions
    SET suspended_at = COALESCE(suspended_at, GREATEST(?, issued_at)), suspension_reason = ?
    WHERE user_id = ? AND revoked_at IS NULL AND absolute_expires_at > ?
    ORDER BY id
    """.trimIndent()

private val RESUME_USER_SESSIONS =
    """
    UPDATE desktop_sessions
    SET suspended_at = NULL, suspension_reason = NULL
    WHERE user_id = ? AND revoked_at IS NULL AND absolute_expires_at > ?
    ORDER BY id
    """.trimIndent()

private val REVOKE_USER_SESSIONS =
    """
    UPDATE desktop_sessions
    SET revoked_at = GREATEST(?, issued_at), revocation_reason = ?
    WHERE user_id = ? AND revoked_at IS NULL
    ORDER BY id
    """.trimIndent()

private val REVOKE_SESSION =
    """
    UPDATE desktop_sessions
    SET revoked_at = GREATEST(?, issued_at), revocation_reason = ?
    WHERE id = ? AND revoked_at IS NULL
    """.trimIndent()

private val SELECT_REFRESH_CREDENTIAL_FOR_UPDATE =
    """
    SELECT id, generation, issued_at, consumed_at, revoked_at
    FROM refresh_credentials
    WHERE session_id = ? AND credential_hash = ?
    FOR UPDATE
    """.trimIndent()

private val INSERT_REFRESH_CREDENTIAL =
    """
    INSERT INTO refresh_credentials (session_id, generation, credential_hash, hash_version, issued_at)
    VALUES (?, ?, ?, ?, ?)
    """.trimIndent()

private val CONSUME_REFRESH_CREDENTIAL =
    """
    UPDATE refresh_credentials
    SET consumed_at = GREATEST(?, issued_at)
    WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
    """.trimIndent()

private val REVOKE_ACTIVE_REFRESH_CREDENTIAL =
    """
    UPDATE refresh_credentials
    SET revoked_at = GREATEST(?, issued_at)
    WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
    """.trimIndent()

private val REVOKE_USER_REFRESH_CREDENTIALS =
    """
    UPDATE refresh_credentials r
    JOIN desktop_sessions s ON s.id = r.session_id
    SET r.revoked_at = GREATEST(?, r.issued_at)
    WHERE s.user_id = ? AND r.consumed_at IS NULL AND r.revoked_at IS NULL
    """.trimIndent()

private val MARK_REFRESH_CREDENTIAL_REUSED =
    """
    UPDATE refresh_credentials
    SET reuse_detected_at = GREATEST(?, consumed_at)
    WHERE id = ? AND consumed_at IS NOT NULL
    """.trimIndent()

private val MARK_SESSION_REFRESH_REUSE =
    """
    UPDATE desktop_sessions
    SET refresh_reuse_detected_at = GREATEST(?, issued_at),
        revoked_at = COALESCE(revoked_at, GREATEST(?, issued_at)),
        revocation_reason = 'REFRESH_CREDENTIAL_REUSE'
    WHERE id = ?
    """.trimIndent()

private val TOUCH_SESSION_REFRESH =
    """
    UPDATE desktop_sessions
    SET last_refreshed_at = GREATEST(?, issued_at)
    WHERE id = ? AND revoked_at IS NULL
    """.trimIndent()
