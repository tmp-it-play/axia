package team.themoment.axia.integration.github

/** GitHub identity and organization-membership queries used during login. */
interface GitHubIdentityClient {
    /** Resolves a mutable GitHub login to its durable numeric account ID. */
    suspend fun findUser(login: String): GitHubUserIdentity?

    /**
     * Checks private as well as public membership using the organization
     * installation. Pending invitations and outside collaborators return false.
     */
    suspend fun hasActiveOrganizationMembership(identity: GitHubUserIdentity): Boolean
}

data class GitHubUserIdentity(
    val numericId: Long,
    /** Canonical login returned by GitHub, which can differ in case or after a rename. */
    val login: String,
    val nodeId: String?,
    val accountType: String,
)

enum class GitHubIdentityFailure {
    AppAuthenticationFailed,
    InstallationNotFound,
    PermissionDenied,
    RateLimited,
    ServiceUnavailable,
    InvalidProviderResponse,
    NetworkFailure,
}

/** Sanitized failure that never embeds an App JWT, installation token, or response body. */
class GitHubIdentityClientException(
    val failure: GitHubIdentityFailure,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val rateLimitResetEpochSeconds: Long? = null,
) : RuntimeException("GitHub identity request failed: $failure")
