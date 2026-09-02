package team.themoment.axia.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Header used to prove possession of a one-time authentication transaction. */
const val AUTH_TRANSACTION_SECRET_HEADER: String = "X-Axia-Auth-Transaction-Secret"

/**
 * Starts a DataGSM browser authentication transaction for one Desktop device.
 * The server uses this device information when it creates the refresh session.
 */
@Serializable
data class AuthTransactionCreateRequest(
    val deviceName: String,
    val devicePlatform: DesktopPlatform,
)

/**
 * One-time transaction details returned before the system browser is opened.
 * [transactionSecret] must only be sent in [AUTH_TRANSACTION_SECRET_HEADER].
 */
@Serializable
data class AuthTransactionCreateResponse(
    val transactionId: String,
    val transactionSecret: String,
    val authorizationUrl: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString(): String =
        "AuthTransactionCreateResponse(transactionId=$transactionId, transactionSecret=[REDACTED], " +
            "authorizationUrl=[REDACTED], expiresAtEpochMillis=$expiresAtEpochMillis)"
}

/**
 * Result of polling an authentication transaction.
 *
 * - [AuthTransactionStatus.Pending]: [tokens] and [block] are null.
 * - [AuthTransactionStatus.Succeeded]: [tokens] is present and [block] is null.
 * - [AuthTransactionStatus.Blocked]: [block] is present and [tokens] is null.
 *
 * A terminal result is returned at most once because the transaction is
 * consumed when it succeeds or fails.
 */
@Serializable
data class AuthTransactionPollResponse(
    val status: AuthTransactionStatus,
    val tokens: AuthTokens? = null,
    val block: AuthBlock? = null,
)

@Serializable
enum class AuthTransactionStatus {
    @SerialName("pending")
    Pending,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("blocked")
    Blocked,
}

/** Information needed to render the dedicated login-blocked screen. */
@Serializable
data class AuthBlock(
    val reason: AuthBlockReason,
    /** Present only for [AuthBlockReason.GitHubIdConflict]. It is always the current user's name. */
    val userDisplayName: String? = null,
)

/** The six login-blocking reasons defined by the implementation specification. */
@Serializable
enum class AuthBlockReason {
    @SerialName("not_the_moment_member")
    NotTheMomentMember,

    @SerialName("github_id_missing")
    GitHubIdMissing,

    @SerialName("github_account_not_found")
    GitHubAccountNotFound,

    @SerialName("github_id_conflict")
    GitHubIdConflict,

    @SerialName("github_organization_membership_required")
    GitHubOrganizationMembershipRequired,

    @SerialName("external_authentication_failed")
    ExternalAuthenticationFailed,
}

/** Credentials issued after login and after each refresh rotation. */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAtEpochMillis: Long,
    val refreshCredential: String,
    val sessionId: String,
    /** Fixed absolute expiry; refreshing must not extend this value. */
    val sessionExpiresAtEpochMillis: Long,
) {
    override fun toString(): String =
        "AuthTokens(accessToken=[REDACTED], accessTokenExpiresAtEpochMillis=$accessTokenExpiresAtEpochMillis, " +
            "refreshCredential=[REDACTED], sessionId=$sessionId, " +
            "sessionExpiresAtEpochMillis=$sessionExpiresAtEpochMillis)"
}

@Serializable
data class TokenRefreshRequest(
    val refreshCredential: String,
) {
    override fun toString(): String = "TokenRefreshRequest(refreshCredential=[REDACTED])"
}

/**
 * Result of rotating a valid refresh credential.
 *
 * - [TokenRefreshStatus.Succeeded]: [tokens] is present and [block] is null.
 * - [TokenRefreshStatus.Blocked]: [block] is present and [tokens] is null. The
 *   refresh credential remains stored so a suspended, unexpired session can be
 *   restored when the account becomes eligible again.
 *
 * A server may return this body with HTTP 200 for success or HTTP 403 for an
 * account-state block. Invalid, reused, or revoked credentials instead return
 * an [team.themoment.axia.api.ApiError] with HTTP 401 and must be deleted by the
 * client.
 */
@Serializable
data class TokenRefreshResponse(
    val status: TokenRefreshStatus,
    val tokens: AuthTokens? = null,
    val block: AuthBlock? = null,
)

@Serializable
enum class TokenRefreshStatus {
    @SerialName("succeeded")
    Succeeded,

    @SerialName("blocked")
    Blocked,
}

@Serializable
enum class DesktopPlatform {
    @SerialName("macos")
    MacOS,

    @SerialName("windows")
    Windows,
}

/** A revocable Desktop session shown in the user's device list. */
@Serializable
data class DesktopSession(
    val id: String,
    val deviceName: String,
    val devicePlatform: DesktopPlatform,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val isCurrent: Boolean,
)

@Serializable
data class SessionListResponse(
    val sessions: List<DesktopSession>,
)
