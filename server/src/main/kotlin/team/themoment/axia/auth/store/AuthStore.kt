package team.themoment.axia.auth.store

import team.themoment.axia.auth.AuthBlockReason
import team.themoment.axia.auth.DesktopPlatform
import java.time.Instant

interface AuthStore {
    suspend fun createOAuthTransaction(transaction: NewOAuthTransaction)

    suspend fun claimCallback(
        stateHash: ByteArray,
        now: Instant,
    ): CallbackClaim?

    suspend fun completeBlocked(
        transactionId: String,
        reason: AuthBlockReason,
        failureDisplayName: String?,
        now: Instant,
    ): Boolean

    suspend fun syncExistingProfile(
        profile: UserProfileSnapshot,
        now: Instant,
    ): ExistingUserProfile?

    suspend fun deactivateAndRelease(
        profile: UserProfileSnapshot,
        now: Instant,
    ): Boolean

    suspend fun suspendForGitHubMembershipLoss(
        userId: Long,
        now: Instant,
        expectedProfileVersion: Long? = null,
    ): Boolean

    suspend fun releaseGitHubLinkAndSuspend(
        userId: Long,
        reason: AuthBlockReason,
        now: Instant,
        expectedProfileVersion: Long? = null,
    ): Boolean

    suspend fun resolveGitHubConflicts(
        dataGsmUserId: Long,
        now: Instant,
    ): Int

    suspend fun blockForGitHubConflict(
        transactionId: String,
        githubUserId: Long,
        conflictingProfile: UserProfileSnapshot,
        expectedProfileVersion: Long?,
        now: Instant,
    ): GitHubConflictBlockResult

    suspend fun approveLogin(
        transactionId: String,
        profile: UserProfileSnapshot,
        githubLink: GitHubLinkApproval,
        expectedProfileVersion: Long?,
        now: Instant,
    ): LoginApprovalResult

    suspend fun consumePollAndCreateSession(
        transactionId: String,
        pollSecretHash: ByteArray,
        nonceHash: ByteArray,
        newSession: NewSession,
        now: Instant,
    ): PollConsumptionResult

    suspend fun listSessions(
        userId: Long,
        currentSessionId: String,
        now: Instant,
    ): List<StoredDesktopSession>

    suspend fun revokeSession(
        userId: Long,
        sessionId: String,
        reason: SessionRevocationReason,
        now: Instant,
    ): Boolean

    suspend fun revokeAllSessions(
        userId: Long,
        reason: SessionRevocationReason,
        now: Instant,
    ): Int

    suspend fun checkAccess(
        userId: Long,
        sessionId: String,
        now: Instant,
    ): AccessCheck

    suspend fun rotateRefresh(
        sessionId: String,
        presentedCredentialHash: ByteArray,
        nextCredentialHash: ByteArray,
        hashVersion: Int,
        now: Instant,
    ): RefreshRotationResult
}

data class NewOAuthTransaction(
    val id: String,
    val stateHash: ByteArray,
    val pollSecretHash: ByteArray,
    val pkceChallengeDigest: ByteArray,
    val nonceHash: ByteArray,
    val deviceName: String,
    val devicePlatform: DesktopPlatform,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class CallbackClaim(
    val id: String,
    val pkceChallengeDigest: ByteArray,
    val nonceHash: ByteArray,
    val expiresAt: Instant,
)

data class UserProfileSnapshot(
    val dataGsmUserId: Long,
    val displayName: String,
    val email: String,
    val dataGsmAccountStatus: String,
    val dataGsmStudentRole: String,
    val majorClubId: Long?,
    val majorClubName: String?,
    val dataGsmGitHubLogin: String?,
    val sex: String? = null,
    val grade: Int? = null,
    val classNumber: Int? = null,
    val number: Int? = null,
    val studentNumber: Int? = null,
    val major: String? = null,
    val specialty: String? = null,
    /** Local instant immediately before fetching the authoritative snapshot; never a provider timestamp. */
    val snapshotRequestedAt: Instant? = null,
)

class StaleProfileSnapshotException : IllegalStateException("A newer authoritative profile has already been applied")

sealed interface ProjectedGitHubConflictResult {
    data object NoConflict : ProjectedGitHubConflictResult

    data object Conflict : ProjectedGitHubConflictResult

    data object StaleProfile : ProjectedGitHubConflictResult
}

sealed interface ProjectedGitHubOutcome {
    data class Verified(
        val link: GitHubLinkApproval,
    ) : ProjectedGitHubOutcome

    data class NotOrganizationMember(
        val link: GitHubLinkApproval,
    ) : ProjectedGitHubOutcome

    data object IdMissing : ProjectedGitHubOutcome

    data object AccountNotFound : ProjectedGitHubOutcome
}

sealed interface ProjectedGitHubResult {
    data object Applied : ProjectedGitHubResult

    data object Conflict : ProjectedGitHubResult

    data object StaleProfile : ProjectedGitHubResult
}

data class ExistingUserProfile(
    val userId: Long,
    val isActive: Boolean,
    val previousDataGsmGitHubLogin: String?,
    /** Monotonic CAS token for rejecting an approval based on a stale DataGSM snapshot. */
    val profileVersion: Long,
    /** True when this sync already released a stale link and suspended live sessions atomically. */
    val githubLinkReleased: Boolean,
    val githubUserId: Long?,
    val githubLogin: String?,
    val githubMembership: GitHubMembership,
)

enum class GitHubMembership {
    Unknown,
    Member,
    NotMember,
}

data class GitHubLinkApproval(
    val numericId: Long,
    val login: String,
)

data class GitHubConflict(
    val ownerUserId: Long,
    val episodeCreated: Boolean,
    val notificationCreated: Boolean,
)

sealed interface GitHubConflictBlockResult {
    data class Blocked(
        val conflict: GitHubConflict,
    ) : GitHubConflictBlockResult

    data object NoConflict : GitHubConflictBlockResult

    data object TransactionUnavailable : GitHubConflictBlockResult

    data object StaleProfile : GitHubConflictBlockResult
}

sealed interface LoginApprovalResult {
    data class Approved(
        val userId: Long,
        val displayName: String,
    ) : LoginApprovalResult

    data class Conflict(
        val conflict: GitHubConflict,
    ) : LoginApprovalResult

    data object TransactionUnavailable : LoginApprovalResult

    /** A newer profile/event won the race; this OAuth transaction was failed without changing the user. */
    data object StaleProfile : LoginApprovalResult
}

data class NewSession(
    val id: String,
    val refreshCredentialHash: ByteArray,
    val hashVersion: Int = 1,
)

data class StoredDesktopSession(
    val id: String,
    val userId: Long,
    val deviceName: String,
    val devicePlatform: DesktopPlatform,
    val issuedAt: Instant,
    val absoluteExpiresAt: Instant,
    val isCurrent: Boolean,
)

sealed interface PollConsumptionResult {
    data object Pending : PollConsumptionResult

    data class Blocked(
        val reason: AuthBlockReason,
        val userDisplayName: String?,
    ) : PollConsumptionResult

    data class SessionCreated(
        val userId: Long,
        val session: StoredDesktopSession,
    ) : PollConsumptionResult

    data object Unavailable : PollConsumptionResult
}

sealed interface AccessCheck {
    data class Eligible(
        val userId: Long,
        val sessionId: String,
        val absoluteExpiresAt: Instant,
    ) : AccessCheck

    data class Ineligible(
        val reason: AccessIneligibilityReason,
    ) : AccessCheck
}

sealed interface AccessIneligibilityReason {
    data object SessionNotFound : AccessIneligibilityReason

    data object SessionExpired : AccessIneligibilityReason

    data object SessionRevoked : AccessIneligibilityReason

    data object UserInactive : AccessIneligibilityReason

    data class AuthenticationBlocked(
        val reason: AuthBlockReason,
    ) : AccessIneligibilityReason
}

enum class SessionRevocationReason(
    internal val databaseValue: String,
) {
    Logout("LOGOUT"),
    UserRequested("USER_REQUESTED"),
    AllSessionsRequested("ALL_SESSIONS_REQUESTED"),
}

sealed interface RefreshRotationResult {
    data class Rotated(
        val userId: Long,
        val sessionId: String,
        val absoluteExpiresAt: Instant,
        val generation: Long,
    ) : RefreshRotationResult

    data class Reused(
        val sessionId: String,
    ) : RefreshRotationResult

    data object Invalid : RefreshRotationResult

    data class Ineligible(
        val reason: AuthBlockReason,
        /** Present only for a GitHub ID conflict; always the current session user's name. */
        val userDisplayName: String? = null,
    ) : RefreshRotationResult
}
