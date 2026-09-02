package team.themoment.axia.integration.datagsm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import kotlinx.coroutines.cancel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ktor-backed DataGSM OAuth client.
 *
 * The injected [httpClient] must not have BODY-level request logging enabled:
 * the token exchange body contains the authorization code and PKCE verifier.
 */
class KtorDataGsmOAuthClient(
    private val httpClient: HttpClient,
    private val clientId: String,
    authorizationServerBaseUrl: String = DEFAULT_AUTHORIZATION_SERVER_BASE_URL,
    resourceServerBaseUrl: String = DEFAULT_RESOURCE_SERVER_BASE_URL,
    private val requiredScope: String = DEFAULT_USERINFO_SCOPE,
) : DataGsmOAuthClient {
    private val authorizationEndpoint = "${authorizationServerBaseUrl.trimEnd('/')}/v1/oauth/authorize"
    private val tokenEndpoint = "${authorizationServerBaseUrl.trimEnd('/')}/v1/oauth/token"
    private val userInfoEndpoint = "${resourceServerBaseUrl.trimEnd('/')}/userinfo"

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }

    init {
        require(clientId.isNotBlank()) { "DataGSM clientId must not be blank" }
        require(requiredScope.isNotBlank()) { "DataGSM requiredScope must not be blank" }
    }

    override fun buildAuthorizationUrl(
        redirectUri: String,
        state: String,
        codeChallenge: String,
    ): String {
        require(redirectUri.isNotBlank()) { "redirectUri must not be blank" }
        require(state.isNotBlank()) { "OAuth state must not be blank" }
        require(codeChallenge.matches(PKCE_CODE_CHALLENGE_PATTERN)) { "Invalid PKCE code challenge" }

        return URLBuilder(authorizationEndpoint)
            .apply {
                parameters.append("client_id", clientId)
                parameters.append("redirect_uri", redirectUri)
                parameters.append("response_type", "code")
                parameters.append("state", state)
                parameters.append("code_challenge", codeChallenge)
                parameters.append("code_challenge_method", "S256")
                parameters.append("scope", requiredScope)
            }.buildString()
    }

    override suspend fun exchangeAuthorizationCodeAndFetchUserInfo(
        authorizationCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): DataGsmAccountSnapshot {
        require(authorizationCode.isNotBlank()) { "authorizationCode must not be blank" }
        require(codeVerifier.matches(PKCE_CODE_VERIFIER_PATTERN)) { "Invalid PKCE code verifier" }
        require(redirectUri.isNotBlank()) { "redirectUri must not be blank" }

        val token = exchangeAuthorizationCode(authorizationCode, codeVerifier, redirectUri)
        return fetchUserInfo(token)
    }

    private suspend fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): String {
        val requestBody =
            DataGsmTokenExchangeRequest(
                grantType = "authorization_code",
                code = authorizationCode,
                clientId = clientId,
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            )
        val response =
            executeRequest {
                httpClient.post(tokenEndpoint) {
                    expectSuccess = false
                    retry { noRetry() }
                    accept(ContentType.Application.Json)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(requestBody))
                }
            }

        if (response.status != HttpStatusCode.OK) {
            throw tokenExchangeFailure(response)
        }

        val tokenResponse = decodeResponse<DataGsmTokenResponse>(response)
        if (
            tokenResponse.accessToken.isBlank() ||
            tokenResponse.accessToken.hasHeaderDelimiter() ||
            !tokenResponse.tokenType.equals("Bearer", ignoreCase = true) ||
            requiredScope !in tokenResponse.scope.split(SCOPE_SEPARATOR).filter(String::isNotBlank)
        ) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }
        return tokenResponse.accessToken
    }

    private suspend fun fetchUserInfo(accessToken: String): DataGsmAccountSnapshot {
        val response =
            executeRequest {
                httpClient.get(userInfoEndpoint) {
                    expectSuccess = false
                    retry { noRetry() }
                    accept(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            }

        if (response.status != HttpStatusCode.OK) {
            throw userInfoFailure(response)
        }

        val account = decodeResponse<DataGsmAccountInfoResponse>(response)
        if (account.id <= 0 || account.email.isBlank() || account.role.isBlank() || account.status.isBlank()) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }
        if ((account.objectType == STUDENT_OBJECT_TYPE) != (account.student != null)) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }

        return DataGsmAccountSnapshot(
            accountId = account.id,
            email = account.email,
            accountRole = account.role,
            status = account.status,
            objectType = account.objectType,
            student = account.student?.toSnapshot(),
        )
    }

    private suspend inline fun <reified T> decodeResponse(response: HttpResponse): T {
        val body =
            try {
                response.bodyAsText()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                throw DataGsmOAuthClientException(DataGsmOAuthFailure.NetworkFailure)
            }

        return try {
            json.decodeFromString<T>(body)
        } catch (_: SerializationException) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        } catch (_: IllegalArgumentException) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }
    }

    private suspend fun executeRequest(request: suspend () -> HttpResponse): HttpResponse =
        try {
            request()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: DataGsmOAuthClientException) {
            throw exception
        } catch (_: Exception) {
            // Do not retain the transport exception: it can contain sensitive request context.
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.NetworkFailure)
        }

    private fun tokenExchangeFailure(response: HttpResponse): DataGsmOAuthClientException =
        when (response.status) {
            HttpStatusCode.BadRequest -> {
                response.failure(DataGsmOAuthFailure.InvalidAuthorizationGrant)
            }

            HttpStatusCode.Unauthorized -> {
                response.failure(DataGsmOAuthFailure.InvalidClient)
            }

            HttpStatusCode.TooManyRequests -> {
                response.failure(DataGsmOAuthFailure.RateLimited)
            }

            else -> {
                if (response.status.value >= SERVER_ERROR_START) {
                    response.failure(DataGsmOAuthFailure.ServiceUnavailable)
                } else {
                    response.failure(DataGsmOAuthFailure.InvalidProviderResponse)
                }
            }
        }

    private fun userInfoFailure(response: HttpResponse): DataGsmOAuthClientException =
        when (response.status) {
            HttpStatusCode.Unauthorized -> {
                response.failure(DataGsmOAuthFailure.InvalidAuthorizationGrant)
            }

            HttpStatusCode.Forbidden -> {
                response.failure(DataGsmOAuthFailure.AccessDenied)
            }

            HttpStatusCode.NotFound -> {
                response.failure(DataGsmOAuthFailure.UserNotFound)
            }

            HttpStatusCode.TooManyRequests -> {
                response.failure(DataGsmOAuthFailure.RateLimited)
            }

            else -> {
                if (response.status.value >= SERVER_ERROR_START) {
                    response.failure(DataGsmOAuthFailure.ServiceUnavailable)
                } else {
                    response.failure(DataGsmOAuthFailure.InvalidProviderResponse)
                }
            }
        }

    private fun HttpResponse.failure(failure: DataGsmOAuthFailure): DataGsmOAuthClientException {
        val exception =
            DataGsmOAuthClientException(
                failure = failure,
                httpStatus = status.value,
                retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
            )
        cancel()
        return exception
    }

    private fun DataGsmStudentResponse.toSnapshot(): DataGsmStudentSnapshot {
        if (id <= 0 || name.isBlank() || email.isBlank() || role.isBlank()) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }
        return DataGsmStudentSnapshot(
            id = id,
            name = name,
            email = email,
            sex = sex,
            grade = grade,
            classNumber = classNumber,
            number = number,
            studentNumber = studentNumber,
            major = major,
            specialty = specialty,
            role = role,
            majorClub = majorClub?.toSnapshot(),
            githubId = githubId,
        )
    }

    private fun DataGsmClubResponse.toSnapshot(): DataGsmClubSnapshot {
        if (id <= 0 || name.isBlank() || type.isBlank()) {
            throw DataGsmOAuthClientException(DataGsmOAuthFailure.InvalidProviderResponse)
        }
        return DataGsmClubSnapshot(id = id, name = name, type = type)
    }

    private fun String.hasHeaderDelimiter(): Boolean = '\r' in this || '\n' in this

    private companion object {
        const val DEFAULT_AUTHORIZATION_SERVER_BASE_URL = "https://oauth.authorization.datagsm.kr"
        const val DEFAULT_RESOURCE_SERVER_BASE_URL = "https://oauth.resource.datagsm.kr"
        const val DEFAULT_USERINFO_SCOPE = "datagsm:self_read"
        const val STUDENT_OBJECT_TYPE = "STUDENT"
        const val SERVER_ERROR_START = 500
        val PKCE_CODE_CHALLENGE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
        val PKCE_CODE_VERIFIER_PATTERN = Regex("^[A-Za-z0-9._~-]{43,128}$")
        val SCOPE_SEPARATOR = Regex("\\s+")
    }
}

@Serializable
private class DataGsmTokenExchangeRequest(
    @SerialName("grant_type")
    val grantType: String,
    val code: String,
    @SerialName("client_id")
    val clientId: String,
    @SerialName("redirect_uri")
    val redirectUri: String,
    @SerialName("code_verifier")
    val codeVerifier: String,
)

@Serializable
private class DataGsmTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    val scope: String,
)

@Serializable
private class DataGsmAccountInfoResponse(
    val id: Long,
    val email: String,
    val role: String,
    val status: String,
    @SerialName("objectType")
    val objectType: String?,
    val student: DataGsmStudentResponse? = null,
)

@Serializable
private class DataGsmStudentResponse(
    val id: Long,
    val name: String,
    val email: String,
    val sex: String? = null,
    val grade: Int? = null,
    @SerialName("classNum")
    val classNumber: Int? = null,
    val number: Int? = null,
    val studentNumber: Int? = null,
    val major: String? = null,
    val specialty: String? = null,
    val role: String,
    // Missing identity fields indicate an incompatible provider deployment, not a departure or an empty ID.
    val majorClub: DataGsmClubResponse?,
    val githubId: String?,
)

@Serializable
private class DataGsmClubResponse(
    val id: Long,
    val name: String,
    val type: String,
)
