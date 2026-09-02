package team.themoment.axia.integration.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * GitHub App-backed identity client.
 *
 * [privateKeyPem] is parsed immediately and is not retained as a String. The
 * injected [httpClient] must not have BODY-level request logging enabled because
 * the DataGSM/GitHub integration layer sends credentials in headers and bodies.
 */
class KtorGitHubIdentityClient(
    private val httpClient: HttpClient,
    private val appIssuer: String,
    private val installationId: Long,
    privateKeyPem: String,
    private val organization: String,
    private val userAgent: String = DEFAULT_USER_AGENT,
    apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val apiVersion: String = DEFAULT_API_VERSION,
    private val clock: Clock = Clock.systemUTC(),
) : GitHubIdentityClient {
    private val apiBaseUrl = apiBaseUrl.trimEnd('/')
    private val signingKey = parseRsaPrivateKey(privateKeyPem)
    private val signingAlgorithm = Algorithm.RSA256(null, signingKey)
    private val tokenRefreshMutex = Mutex()
    private val tokenCacheMonitor = Any()
    private var cachedInstallationToken: CachedInstallationToken? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    init {
        require(appIssuer.isNotBlank()) { "GitHub App issuer must not be blank" }
        require(installationId > 0) { "GitHub installationId must be positive" }
        require(organization.matches(GITHUB_OWNER_PATTERN)) { "Invalid GitHub organization" }
        require(userAgent.isNotBlank() && '\r' !in userAgent && '\n' !in userAgent) { "Invalid GitHub User-Agent" }
        require(apiVersion.matches(API_VERSION_PATTERN)) { "Invalid GitHub API version" }
    }

    override suspend fun findUser(login: String): GitHubUserIdentity? {
        if (!login.matches(GITHUB_LOGIN_PATTERN)) {
            return null
        }
        val token = installationToken()
        val response =
            executeRequest {
                httpClient.get("$apiBaseUrl/users/$login") {
                    expectSuccess = false
                    githubHeaders(token)
                }
            }

        return when (response.status) {
            HttpStatusCode.OK -> {
                decodeUser(response)
            }

            HttpStatusCode.NotFound -> {
                response.cancel()
                null
            }

            else -> {
                throw githubApiFailure(response, token)
            }
        }
    }

    override suspend fun hasActiveOrganizationMembership(identity: GitHubUserIdentity): Boolean {
        require(identity.numericId > 0) { "GitHub numeric ID must be positive" }
        require(identity.login.matches(GITHUB_LOGIN_PATTERN)) { "Invalid canonical GitHub login" }

        val token = installationToken()
        val response =
            executeRequest {
                httpClient.get("$apiBaseUrl/orgs/$organization/memberships/${identity.login}") {
                    expectSuccess = false
                    githubHeaders(token)
                }
            }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val membership = decodeResponse<GitHubMembershipResponse>(response)
                if (
                    membership.user.id != identity.numericId ||
                    !membership.user.login.equals(identity.login, ignoreCase = true)
                ) {
                    throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
                }
                membership.state == ACTIVE_MEMBERSHIP_STATE
            }

            HttpStatusCode.NotFound -> {
                response.cancel()
                false
            }

            else -> {
                throw githubApiFailure(response, token)
            }
        }
    }

    private suspend fun installationToken(): String {
        validCachedToken()?.let { return it }

        return tokenRefreshMutex.withLock {
            validCachedToken()?.let { return@withLock it }
            val issued = createInstallationToken()
            synchronized(tokenCacheMonitor) {
                cachedInstallationToken = issued
            }
            issued.value
        }
    }

    private fun validCachedToken(): String? {
        val now = clock.instant()
        return synchronized(tokenCacheMonitor) {
            cachedInstallationToken
                ?.takeIf { now.isBefore(it.expiresAt.minusSeconds(TOKEN_EXPIRY_SKEW_SECONDS)) }
                ?.value
        }
    }

    private suspend fun createInstallationToken(): CachedInstallationToken {
        val appJwt = createAppJwt()
        val requestBody = GitHubInstallationTokenRequest(permissions = mapOf("members" to "read"))
        val response =
            executeRequest {
                httpClient.post("$apiBaseUrl/app/installations/$installationId/access_tokens") {
                    expectSuccess = false
                    retry { noRetry() }
                    githubHeaders(appJwt)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(requestBody))
                }
            }

        if (response.status != HttpStatusCode.Created) {
            throw installationTokenFailure(response)
        }

        val tokenResponse = decodeResponse<GitHubInstallationTokenResponse>(response)
        val expiresAt =
            try {
                Instant.parse(tokenResponse.expiresAt)
            } catch (_: Exception) {
                throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
            }
        val membersPermission = tokenResponse.permissions["members"]
        if (
            tokenResponse.token.isBlank() ||
            tokenResponse.token.hasHeaderDelimiter() ||
            !clock.instant().isBefore(expiresAt) ||
            membersPermission !in ALLOWED_MEMBERS_PERMISSIONS
        ) {
            throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
        }

        return CachedInstallationToken(value = tokenResponse.token, expiresAt = expiresAt)
    }

    private fun createAppJwt(): String {
        val now = clock.instant()
        return try {
            JWT
                .create()
                .withIssuer(appIssuer)
                .withIssuedAt(now.minusSeconds(APP_JWT_CLOCK_SKEW_SECONDS))
                .withExpiresAt(now.plusSeconds(APP_JWT_LIFETIME_SECONDS))
                .sign(signingAlgorithm)
        } catch (_: Exception) {
            throw GitHubIdentityClientException(GitHubIdentityFailure.AppAuthenticationFailed)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders(bearerToken: String) {
        header(HttpHeaders.Accept, GITHUB_ACCEPT)
        header(HttpHeaders.Authorization, "Bearer $bearerToken")
        header(HttpHeaders.UserAgent, userAgent)
        header(GITHUB_API_VERSION_HEADER, apiVersion)
    }

    private suspend fun decodeUser(response: HttpResponse): GitHubUserIdentity {
        val user = decodeResponse<GitHubUserResponse>(response)
        if (user.id <= 0 || !user.login.matches(GITHUB_LOGIN_PATTERN) || user.type.isBlank()) {
            throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
        }
        return GitHubUserIdentity(
            numericId = user.id,
            login = user.login,
            nodeId = user.nodeId,
            accountType = user.type,
        )
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): T {
        val body =
            try {
                response.bodyAsText()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                throw GitHubIdentityClientException(GitHubIdentityFailure.NetworkFailure)
            }

        return try {
            json.decodeFromString<T>(body)
        } catch (_: SerializationException) {
            throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
        } catch (_: IllegalArgumentException) {
            throw GitHubIdentityClientException(GitHubIdentityFailure.InvalidProviderResponse)
        }
    }

    private suspend fun executeRequest(request: suspend () -> HttpResponse): HttpResponse =
        try {
            request()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: GitHubIdentityClientException) {
            throw exception
        } catch (_: Exception) {
            // Do not retain the transport exception: it can contain sensitive request context.
            throw GitHubIdentityClientException(GitHubIdentityFailure.NetworkFailure)
        }

    private fun installationTokenFailure(response: HttpResponse): GitHubIdentityClientException =
        when (response.status) {
            HttpStatusCode.Unauthorized -> {
                response.failure(GitHubIdentityFailure.AppAuthenticationFailed)
            }

            HttpStatusCode.NotFound -> {
                response.failure(GitHubIdentityFailure.InstallationNotFound)
            }

            HttpStatusCode.Forbidden -> {
                response.failure(rateLimitAwareFailure(response, GitHubIdentityFailure.PermissionDenied))
            }

            HttpStatusCode.UnprocessableEntity -> {
                response.failure(rateLimitAwareFailure(response, GitHubIdentityFailure.PermissionDenied))
            }

            HttpStatusCode.TooManyRequests -> {
                response.failure(GitHubIdentityFailure.RateLimited)
            }

            else -> {
                if (response.status.value >= SERVER_ERROR_START) {
                    response.failure(GitHubIdentityFailure.ServiceUnavailable)
                } else {
                    response.failure(GitHubIdentityFailure.InvalidProviderResponse)
                }
            }
        }

    private fun githubApiFailure(
        response: HttpResponse,
        token: String,
    ): GitHubIdentityClientException {
        if (response.status == HttpStatusCode.Unauthorized) {
            invalidateCachedToken(token)
        }
        return when (response.status) {
            HttpStatusCode.Unauthorized -> {
                response.failure(GitHubIdentityFailure.AppAuthenticationFailed)
            }

            HttpStatusCode.Forbidden -> {
                response.failure(rateLimitAwareFailure(response, GitHubIdentityFailure.PermissionDenied))
            }

            HttpStatusCode.TooManyRequests -> {
                response.failure(GitHubIdentityFailure.RateLimited)
            }

            else -> {
                if (response.status.value >= SERVER_ERROR_START) {
                    response.failure(GitHubIdentityFailure.ServiceUnavailable)
                } else {
                    response.failure(GitHubIdentityFailure.InvalidProviderResponse)
                }
            }
        }
    }

    private fun invalidateCachedToken(token: String) {
        synchronized(tokenCacheMonitor) {
            if (cachedInstallationToken?.value == token) {
                cachedInstallationToken = null
            }
        }
    }

    private fun String.hasHeaderDelimiter(): Boolean = '\r' in this || '\n' in this

    private fun rateLimitAwareFailure(
        response: HttpResponse,
        fallback: GitHubIdentityFailure,
    ): GitHubIdentityFailure =
        if (
            response.headers[HttpHeaders.RetryAfter] != null ||
            response.headers[GITHUB_RATE_LIMIT_REMAINING]?.toLongOrNull() == 0L
        ) {
            GitHubIdentityFailure.RateLimited
        } else {
            fallback
        }

    private fun HttpResponse.failure(failure: GitHubIdentityFailure): GitHubIdentityClientException {
        val exception =
            GitHubIdentityClientException(
                failure = failure,
                httpStatus = status.value,
                retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                rateLimitResetEpochSeconds = headers[GITHUB_RATE_LIMIT_RESET]?.toLongOrNull(),
            )
        cancel()
        return exception
    }

    private companion object {
        const val DEFAULT_API_BASE_URL = "https://api.github.com"
        const val DEFAULT_API_VERSION = "2026-03-10"
        const val DEFAULT_USER_AGENT = "axia-server"
        const val GITHUB_ACCEPT = "application/vnd.github+json"
        const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val GITHUB_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
        const val GITHUB_RATE_LIMIT_RESET = "X-RateLimit-Reset"
        const val ACTIVE_MEMBERSHIP_STATE = "active"
        const val APP_JWT_CLOCK_SKEW_SECONDS = 60L
        const val APP_JWT_LIFETIME_SECONDS = 9L * 60L
        const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
        const val SERVER_ERROR_START = 500

        val ALLOWED_MEMBERS_PERMISSIONS = setOf("read", "write")
        val GITHUB_LOGIN_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
        val GITHUB_OWNER_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?$")
        val API_VERSION_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        fun parseRsaPrivateKey(privateKeyPem: String): RSAPrivateKey {
            require(privateKeyPem.isNotBlank()) { "GitHub private key PEM must not be blank" }
            val parsed =
                try {
                    PEMParser(StringReader(privateKeyPem)).use(PEMParser::readObject)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Invalid GitHub private key PEM")
                }
            val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider())
            val privateKey =
                try {
                    when (parsed) {
                        is PEMKeyPair -> converter.getKeyPair(parsed).private
                        is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                        else -> throw IllegalArgumentException("Unsupported GitHub private key PEM")
                    }
                } catch (exception: IllegalArgumentException) {
                    throw exception
                } catch (_: Exception) {
                    throw IllegalArgumentException("Invalid GitHub private key PEM")
                }
            return privateKey as? RSAPrivateKey
                ?: throw IllegalArgumentException("GitHub private key must be an RSA private key")
        }
    }
}

private class CachedInstallationToken(
    val value: String,
    val expiresAt: Instant,
)

@Serializable
private class GitHubInstallationTokenRequest(
    val permissions: Map<String, String>,
)

@Serializable
private class GitHubInstallationTokenResponse(
    val token: String,
    @SerialName("expires_at")
    val expiresAt: String,
    val permissions: Map<String, String> = emptyMap(),
)

@Serializable
private class GitHubUserResponse(
    val login: String,
    val id: Long,
    @SerialName("node_id")
    val nodeId: String? = null,
    val type: String,
)

@Serializable
private class GitHubMembershipResponse(
    val state: String,
    val user: GitHubMembershipUserResponse,
)

@Serializable
private class GitHubMembershipUserResponse(
    val login: String,
    val id: Long,
)
