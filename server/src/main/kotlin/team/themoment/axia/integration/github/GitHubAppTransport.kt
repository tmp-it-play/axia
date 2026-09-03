package team.themoment.axia.integration.github

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import team.themoment.axia.config.GitHubConfig
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.interfaces.RSAPrivateKey
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.random.Random

/** A transport failure deliberately contains no request, response body, token, or upstream cause. */
class GitHubApiException(
    val status: Int? = null,
    val retryAt: Instant? = null,
    val outcomeUncertain: Boolean = false,
    val invalidContract: Boolean = false,
    val rateLimited: Boolean = false,
    val authenticationFailure: Boolean = false,
) : RuntimeException("GitHub operation could not be confirmed") {
    val resourceUnavailable: Boolean
        get() =
            !authenticationFailure && !rateLimited &&
                (status == 404 || status == 410 || (status == 403 && retryAt == null))
}

data class GitHubCursorPage(
    val items: List<JsonObject>,
    val nextCursor: String?,
)

/**
 * Server-only GitHub App transport. Reads get four attempts; mutations never get a blind retry.
 * Pagination follows our own same-origin URLs, not untrusted Link header URLs.
 */
class GitHubAppTransport(
    private val client: HttpClient,
    private val config: GitHubConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val onReadExhausted: suspend (GitHubApiException) -> Unit = {},
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = config.apiBaseUrl.toString().trimEnd('/')
    private val algorithm = Algorithm.RSA256(null, parseKey(config.privateKeyPem))
    private val tokenMutex = Mutex()
    private var token: InstallationToken? = null

    suspend fun read(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        appAuthentication: Boolean = false,
    ): JsonElement =
        withReadRetry {
            request(HttpMethod.Get, path, null, parameters, appAuthentication, mutation = false)
        }

    suspend fun readObject(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): JsonObject =
        withReadRetry {
            request(HttpMethod.Get, path, null, parameters, appAuthentication = false, mutation = false) as? JsonObject
                ?: throw GitHubApiException(invalidContract = true)
        }

    suspend fun list(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        collectionKey: String? = null,
        appAuthentication: Boolean = false,
    ): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val items =
                withReadRetry {
                    val response =
                        request(
                            HttpMethod.Get,
                            path,
                            null,
                            parameters + mapOf("per_page" to "100", "page" to page.toString()),
                            appAuthentication,
                            mutation = false,
                        )
                    val array =
                        (if (collectionKey == null) response else (response as? JsonObject)?.get(collectionKey))
                            as? JsonArray ?: throw GitHubApiException(invalidContract = true)
                    array.map { it as? JsonObject ?: throw GitHubApiException(invalidContract = true) }
                }
            result += items
            if (items.size < 100) return result
            page++
        }
    }

    suspend fun mutate(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        appAuthentication: Boolean = false,
    ): JsonElement = request(method, path, body, emptyMap(), appAuthentication, mutation = true)

    /** Some App endpoints use opaque cursors, not the page-number pagination used by repositories. */
    suspend fun readCursorPage(
        path: String,
        cursor: String? = null,
        appAuthentication: Boolean = false,
    ): GitHubCursorPage =
        withReadRetry {
            val parameters =
                buildMap {
                    put("per_page", "100")
                    if (cursor != null) put("cursor", cursor)
                }
            val response = requestResponse(HttpMethod.Get, path, null, parameters, appAuthentication, mutation = false)
            val items =
                (response.body as? JsonArray)?.map {
                    it as? JsonObject ?: throw GitHubApiException(invalidContract = true)
                } ?: throw GitHubApiException(invalidContract = true)
            GitHubCursorPage(items, nextCursor(response.link, path))
        }

    /** Resource-scoped reads isolate one board's contract/access failure, never credentials or provider outages. */
    suspend fun graphQL(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        mutation: Boolean = false,
        resourceScoped: Boolean = false,
    ): JsonObject {
        val body =
            buildJsonObject {
                put("query", query)
                put("variables", variables)
            }

        suspend fun execute(): JsonObject {
            val response =
                request(HttpMethod.Post, "/graphql", body, emptyMap(), false, mutation) as? JsonObject
                    ?: throw GitHubApiException(invalidContract = true, outcomeUncertain = mutation)
            val errors = response["errors"] as? JsonArray
            if (!errors.isNullOrEmpty()) {
                // GraphQL can return partial data and errors together; never commit a partial snapshot.
                val types = errors.map { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }
                val unavailable = resourceScoped && types.all { it == "NOT_FOUND" || it == "FORBIDDEN" }
                throw GitHubApiException(
                    status =
                        if (unavailable) {
                            if ("FORBIDDEN" in types) {
                                403
                            } else {
                                404
                            }
                        } else {
                            null
                        },
                    outcomeUncertain = mutation,
                )
            }
            return response["data"] as? JsonObject
                ?: throw GitHubApiException(invalidContract = true, outcomeUncertain = mutation)
        }
        return if (mutation) execute() else withReadRetry(resourceScoped) { execute() }
    }

    private suspend fun <T> withReadRetry(
        resourceScoped: Boolean = false,
        operation: suspend () -> T,
    ): T {
        repeat(4) { attempt ->
            try {
                return operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GitHubApiException) {
                // A missing resource is an integration-level result, not a provider-wide outage.
                if (failure.status == 404 ||
                    (
                        resourceScoped && !failure.authenticationFailure &&
                            (failure.resourceUnavailable || failure.invalidContract)
                    )
                ) {
                    throw failure
                }
                if (attempt == 3) {
                    onReadExhausted(failure)
                    throw failure
                }
                val exponentialDelay = (500L shl attempt) + Random.nextLong(0, 251)
                val providerDelay = failure.retryAt?.toEpochMilli()?.minus(clock.millis()) ?: 0L
                wait(max(exponentialDelay, providerDelay))
            }
        }
        error("Unreachable read retry state")
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        parameters: Map<String, String>,
        appAuthentication: Boolean,
        mutation: Boolean,
    ): JsonElement = requestResponse(method, path, body, parameters, appAuthentication, mutation).body

    private suspend fun requestResponse(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        parameters: Map<String, String>,
        appAuthentication: Boolean,
        mutation: Boolean,
    ): TransportResponse {
        require(path.startsWith('/') && !path.startsWith("//") && '\r' !in path && '\n' !in path)
        val bearer = if (appAuthentication) appJwt() else installationToken()
        return try {
            val response =
                client.request("$baseUrl$path") {
                    this.method = method
                    expectSuccess = false
                    retry { noRetry() }
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header(HttpHeaders.UserAgent, config.userAgent)
                    header("X-GitHub-Api-Version", config.apiVersion)
                    url { parameters.forEach { (key, value) -> this.parameters.append(key, value) } }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            if (response.status.value == 401 && !appAuthentication) {
                tokenMutex.withLock { if (token?.value == bearer) token = null }
            }
            val responseBody = response.bodyAsText()
            val decoded = runCatching { json.parseToJsonElement(responseBody) }.getOrNull()
            val errorObject = decoded as? JsonObject
            val graphErrors = errorObject?.get("errors") as? JsonArray
            val hasError = response.status.value !in 200..299 || !graphErrors.isNullOrEmpty()
            val errorTexts =
                buildList {
                    (errorObject?.get("message") as? JsonPrimitive)?.contentOrNull?.let(::add)
                    graphErrors.orEmpty().forEach { error ->
                        val fields = error as? JsonObject ?: return@forEach
                        (fields["message"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                        (fields["type"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                    }
                }
            // GitHub also sends primary/secondary GraphQL rate limits as HTTP 200.
            // Inspect only classification signals; never retain the upstream body in an exception.
            val rateLimited =
                response.status.value == 429 || (
                    hasError && (
                        response.headers["X-RateLimit-Remaining"] == "0" ||
                            response.headers[HttpHeaders.RetryAfter] != null ||
                            errorTexts.any {
                                it.contains("rate limit", ignoreCase = true) || it == "RATE_LIMITED" ||
                                    it.contains("abuse detection", ignoreCase = true)
                            }
                    )
                )
            val retry =
                retryAt(
                    response.headers[HttpHeaders.RetryAfter],
                    response.headers["X-RateLimit-Reset"].takeIf { response.headers["X-RateLimit-Remaining"] == "0" },
                ) ?: clock.instant().plusSeconds(60).takeIf { rateLimited }
            if (rateLimited) {
                throw GitHubApiException(
                    status = response.status.value,
                    retryAt = retry,
                    outcomeUncertain = mutation && (response.status.value in 200..299 || response.status.value >= 500),
                    rateLimited = true,
                    authenticationFailure = appAuthentication,
                )
            }
            if (response.status.value !in 200..299) {
                throw GitHubApiException(
                    status = response.status.value,
                    retryAt = retry,
                    outcomeUncertain = mutation && response.status.value >= 500,
                    authenticationFailure = appAuthentication,
                )
            }
            TransportResponse(
                if (responseBody.isBlank()) JsonNull else decoded ?: throw GitHubApiException(authenticationFailure = appAuthentication),
                response.headers[HttpHeaders.Link],
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GitHubApiException) {
            throw failure
        } catch (_: Exception) {
            throw GitHubApiException(outcomeUncertain = mutation)
        }
    }

    /** Extract only an opaque cursor after validating origin and path; never follow a provider URL directly. */
    private fun nextCursor(
        link: String?,
        path: String,
    ): String? {
        if (link.isNullOrBlank()) return null
        return try {
            val links =
                link.split(Regex(",(?=\\s*<)")).map { entry ->
                    val match =
                        Regex("\\s*<([^>]+)>\\s*;(.*)").matchEntire(entry)
                            ?: throw GitHubApiException(invalidContract = true)
                    val relation =
                        Regex("(?:^|;)\\s*rel=(?:\"([^\"]+)\"|([^\\s;]+))\\s*(?:;|$)").find(match.groupValues[2])
                            ?: throw GitHubApiException(invalidContract = true)
                    match.groupValues[1] to
                        relation.groupValues
                            .drop(1)
                            .first { it.isNotEmpty() }
                            .split(' ')
                }
            val next = links.filter { "next" in it.second }
            if (next.isEmpty()) return null
            if (next.size != 1) throw GitHubApiException(invalidContract = true)
            val target = URI(next.single().first)
            val expected = URI("$baseUrl$path")
            if (target.scheme != expected.scheme || target.host != expected.host || target.port != expected.port ||
                target.rawPath != expected.rawPath || target.rawUserInfo != null || target.rawFragment != null
            ) {
                throw GitHubApiException(invalidContract = true)
            }
            val parameters =
                target.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { part ->
                    val pair = part.split('=', limit = 2)
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8) to
                        URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                }
            if (parameters.any { it.first !in setOf("cursor", "per_page") }) throw GitHubApiException(invalidContract = true)
            parameters
                .filter { it.first == "cursor" }
                .singleOrNull()
                ?.second
                ?.takeIf { it.isNotBlank() && it.length <= 4096 && '\r' !in it && '\n' !in it }
                ?: throw GitHubApiException(invalidContract = true)
        } catch (failure: GitHubApiException) {
            throw failure
        } catch (_: Exception) {
            throw GitHubApiException(invalidContract = true)
        }
    }

    private data class TransportResponse(
        val body: JsonElement,
        val link: String?,
    )

    private suspend fun installationToken(): String =
        tokenMutex.withLock {
            token?.takeIf { it.expiresAt.isAfter(clock.instant().plusSeconds(60)) }?.let { return@withLock it.value }
            val response =
                request(
                    HttpMethod.Post,
                    "/app/installations/${config.installationId}/access_tokens",
                    buildJsonObject { put("permissions", JsonObject(requiredPermissions.mapValues { JsonPrimitive(it.value) })) },
                    emptyMap(),
                    appAuthentication = true,
                    mutation = false,
                ) as? JsonObject ?: throw GitHubApiException(invalidContract = true, authenticationFailure = true)
            val value = response["token"]?.jsonPrimitive?.contentOrNull
            val expiresAt = response["expires_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val permissions = response["permissions"] as? JsonObject
            if (
                value.isNullOrBlank() || '\r' in value || '\n' in value ||
                expiresAt == null || !expiresAt.isAfter(clock.instant()) ||
                requiredPermissions.any { (permission, level) -> permissions?.get(permission)?.jsonPrimitive?.contentOrNull != level }
            ) {
                throw GitHubApiException(invalidContract = true, authenticationFailure = true)
            }
            token = InstallationToken(value, expiresAt)
            value
        }

    private fun appJwt(): String =
        JWT
            .create()
            .withIssuer(config.appId.toString())
            .withIssuedAt(clock.instant().minusSeconds(60))
            .withExpiresAt(clock.instant().plusSeconds(9 * 60))
            .sign(algorithm)

    private fun retryAt(
        retryAfter: String?,
        rateLimitReset: String?,
    ): Instant? {
        val fromRetryAfter =
            retryAfter?.toLongOrNull()?.coerceAtLeast(0)?.let { clock.instant().plusSeconds(it) }
                ?: retryAfter?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
        val fromReset = rateLimitReset?.toLongOrNull()?.let(Instant::ofEpochSecond)
        return listOfNotNull(fromRetryAfter, fromReset).maxOrNull()
    }

    private class InstallationToken(
        val value: String,
        val expiresAt: Instant,
    )

    private companion object {
        val requiredPermissions =
            mapOf(
                "issues" to "write",
                "pull_requests" to "write",
                "organization_projects" to "write",
                "members" to "read",
                "metadata" to "read",
            )

        fun parseKey(pem: String): RSAPrivateKey {
            try {
                val parsed = PEMParser(StringReader(pem)).use(PEMParser::readObject)
                val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider())
                val key =
                    when (parsed) {
                        is PEMKeyPair -> converter.getKeyPair(parsed).private
                        is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                        else -> null
                    }
                return key as? RSAPrivateKey ?: throw IllegalArgumentException("GitHub App requires an RSA private key")
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid GitHub App RSA private key")
            }
        }
    }
}
