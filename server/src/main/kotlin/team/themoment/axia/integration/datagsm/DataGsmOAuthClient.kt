package team.themoment.axia.integration.datagsm

/**
 * Port for the DataGSM browser OAuth flow.
 *
 * Implementations exchange an authorization code with PKCE and use the
 * resulting access token exactly once to load the current account snapshot.
 * Provider tokens must not escape this boundary.
 */
interface DataGsmOAuthClient {
    /** Builds the URL that must be opened in the user's system browser. */
    fun buildAuthorizationUrl(
        redirectUri: String,
        state: String,
        codeChallenge: String,
    ): String

    /** Exchanges [authorizationCode] and returns one authoritative `/userinfo` snapshot. */
    suspend fun exchangeAuthorizationCodeAndFetchUserInfo(
        authorizationCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): DataGsmAccountSnapshot
}

/** Provider-owned account fields needed by the Axia authentication policy. */
data class DataGsmAccountSnapshot(
    val accountId: Long,
    val email: String,
    val accountRole: String,
    val status: String,
    val objectType: String?,
    val student: DataGsmStudentSnapshot?,
)

/** Latest DataGSM student snapshot fields consumed by Axia. */
data class DataGsmStudentSnapshot(
    val id: Long,
    val name: String,
    val email: String,
    val sex: String?,
    val grade: Int?,
    val classNumber: Int?,
    val number: Int?,
    val studentNumber: Int?,
    val major: String?,
    val specialty: String?,
    val role: String,
    val majorClub: DataGsmClubSnapshot?,
    val githubId: String?,
)

data class DataGsmClubSnapshot(
    val id: Long,
    val name: String,
    val type: String,
)

enum class DataGsmOAuthFailure {
    InvalidAuthorizationGrant,
    InvalidClient,
    AccessDenied,
    UserNotFound,
    RateLimited,
    ServiceUnavailable,
    InvalidProviderResponse,
    NetworkFailure,
}

/**
 * A deliberately sanitized provider failure.
 *
 * The exception never contains an authorization code, PKCE verifier, token,
 * or upstream response body.
 */
class DataGsmOAuthClientException(
    val failure: DataGsmOAuthFailure,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException("DataGSM OAuth request failed: $failure")
