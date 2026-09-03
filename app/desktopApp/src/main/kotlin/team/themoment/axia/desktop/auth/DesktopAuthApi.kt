package team.themoment.axia.desktop.auth

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.auth.AUTH_TRANSACTION_SECRET_HEADER
import team.themoment.axia.auth.AuthTransactionCreateRequest
import team.themoment.axia.auth.AuthTransactionCreateResponse
import team.themoment.axia.auth.AuthTransactionPollResponse
import team.themoment.axia.auth.TokenRefreshRequest
import team.themoment.axia.auth.TokenRefreshResponse
import team.themoment.axia.auth.TokenRefreshStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A sanitized failure: request URLs, response bodies, and credentials are never exception messages. */
class DesktopApiException(
    val status: Int? = null,
    val uncertain: Boolean = false,
) : IllegalStateException("Axia API request failed (status=${status ?: "unavailable"}, uncertain=$uncertain)")

class DesktopApiResponse(
    val status: Int,
    val body: String,
) {
    override fun toString(): String = "DesktopApiResponse(status=$status, body=[REDACTED])"
}

interface DesktopApiTransport {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        accessToken: String? = null,
        transactionSecret: String? = null,
    ): DesktopApiResponse
}

/** No automatic redirect or request retry; a refresh response may be lost after its credential was consumed. */
class JdkDesktopApiTransport(
    val baseUrl: URI,
) : DesktopApiTransport,
    AutoCloseable {
    private val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    init {
        require(baseUrl.scheme == "https" && !baseUrl.host.isNullOrBlank()) { "The Axia API URL must use HTTPS" }
        require(baseUrl.userInfo == null && baseUrl.query == null && baseUrl.fragment == null) { "The Axia API URL is invalid" }
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        transactionSecret: String?,
    ): DesktopApiResponse {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains("..")) { "An API-relative path is required" }
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported HTTP method" }
        val request =
            HttpRequest
                .newBuilder(URI(baseUrl.toASCIIString().trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .apply {
                    accessToken?.let { header("Authorization", "Bearer $it") }
                    transactionSecret?.let { header(AUTH_TRANSACTION_SECRET_HEADER, it) }
                    body?.let { header("Content-Type", "application/json") }
                }.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build()

        return suspendCancellableCoroutine { continuation ->
            val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { response, failure ->
                if (failure != null) {
                    if (continuation.isActive) continuation.resumeWithException(DesktopApiException(uncertain = true))
                } else if (continuation.isActive) {
                    if (response.body().length > MAXIMUM_RESPONSE_CHARACTERS) {
                        continuation.resumeWithException(DesktopApiException(response.statusCode(), uncertain = true))
                    } else {
                        continuation.resume(DesktopApiResponse(response.statusCode(), response.body()))
                    }
                }
            }
        }
    }

    override fun close() {
        httpClient.shutdownNow()
    }

    companion object {
        private const val MAXIMUM_RESPONSE_CHARACTERS = 2_000_000

        fun configuredBaseUrl(environment: Map<String, String> = System.getenv()): URI =
            URI(environment["AXIA_DESKTOP_API_BASE_URL"]?.trim()?.takeIf(String::isNotEmpty) ?: "https://kimtaeeun.site/axia/api")
    }
}

class DesktopAuthApi(
    val transport: DesktopApiTransport,
) {
    suspend fun createTransaction(request: AuthTransactionCreateRequest): AuthTransactionCreateResponse =
        decode(transport.request("POST", "/v1/auth/datagsm/transactions", json.encodeToString(request)), 201)

    suspend fun pollTransaction(transaction: AuthTransactionCreateResponse): AuthTransactionPollResponse =
        decode(
            transport.request(
                "GET",
                "/v1/auth/datagsm/transactions/${transaction.transactionId}",
                transactionSecret = transaction.transactionSecret,
            ),
            200,
        )

    suspend fun refresh(credential: String): TokenRefreshResponse {
        val response = transport.request("POST", "/v1/auth/refresh", json.encodeToString(TokenRefreshRequest(credential)))
        if (response.status !in setOf(200, 403)) throw DesktopApiException(response.status, uncertain = response.status != 401)
        val value = decode<TokenRefreshResponse>(response, response.status)
        val valid =
            when (response.status) {
                200 -> value.status == TokenRefreshStatus.Succeeded && value.tokens != null && value.block == null
                else -> value.status == TokenRefreshStatus.Blocked && value.block != null && value.tokens == null
            }
        if (!valid) throw DesktopApiException(response.status, uncertain = true)
        return value
    }

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true }

        inline fun <reified T> decode(
            response: DesktopApiResponse,
            expectedStatus: Int = 200,
        ): T {
            if (response.status != expectedStatus) throw DesktopApiException(response.status)
            return try {
                json.decodeFromString<T>(response.body)
            } catch (_: SerializationException) {
                throw DesktopApiException(response.status, uncertain = true)
            } catch (_: IllegalArgumentException) {
                throw DesktopApiException(response.status, uncertain = true)
            }
        }
    }
}
