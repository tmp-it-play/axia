package team.themoment.axia.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class AccessTokenService(
    private val issuer: String,
    private val audience: String,
    secret: ByteArray,
    private val ttl: Duration,
    private val clock: Clock,
) {
    init {
        require(issuer.isNotBlank()) { "Access token issuer must not be blank" }
        require(audience.isNotBlank()) { "Access token audience must not be blank" }
        require(secret.size >= MINIMUM_HMAC_KEY_BYTES) {
            "The access token signing key must contain at least $MINIMUM_HMAC_KEY_BYTES bytes"
        }
        require(ttl >= MINIMUM_ACCESS_TOKEN_TTL && ttl <= MAXIMUM_ACCESS_TOKEN_TTL) {
            "Access token TTL must be between $MINIMUM_ACCESS_TOKEN_TTL and $MAXIMUM_ACCESS_TOKEN_TTL"
        }
    }

    private val algorithm = Algorithm.HMAC256(secret.copyOf())
    private val verifier =
        (JWT.require(algorithm) as JWTVerifier.BaseVerification)
            .apply {
                withIssuer(issuer)
                withAudience(audience)
                withClaimPresence(ISSUED_AT_CLAIM)
                withClaimPresence(EXPIRES_AT_CLAIM)
                withClaimPresence(USER_ID_CLAIM)
                withClaimPresence(SESSION_ID_CLAIM)
                withClaim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
            }.build(clock)

    /**
     * Issues an HS256 access token whose expiry never exceeds the session's
     * fixed absolute expiry.
     */
    fun issue(
        userId: String,
        sessionId: String,
        sessionAbsoluteExpiresAt: Instant,
    ): IssuedAccessToken {
        require(userId.isNotBlank()) { "User id must not be blank" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }

        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val sessionExpiresAt = sessionAbsoluteExpiresAt.truncatedTo(ChronoUnit.SECONDS)
        require(sessionExpiresAt.isAfter(issuedAt)) { "Session has expired" }

        val configuredExpiresAt = issuedAt.plus(ttl).truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = minOf(configuredExpiresAt, sessionExpiresAt)

        val token =
            JWT
                .create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withIssuedAt(issuedAt)
                .withExpiresAt(expiresAt)
                .withClaim(USER_ID_CLAIM, userId)
                .withClaim(SESSION_ID_CLAIM, sessionId)
                .withClaim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .sign(algorithm)

        return IssuedAccessToken(token, expiresAt)
    }

    /** Returns verified claims, or null for every malformed or invalid token. */
    fun verify(token: String): VerifiedAccessTokenClaims? {
        if (token.isBlank()) return null

        return try {
            val decoded = verifier.verify(token)
            val userId = decoded.getClaim(USER_ID_CLAIM).asString()?.takeIf(String::isNotBlank) ?: return null
            val sessionId = decoded.getClaim(SESSION_ID_CLAIM).asString()?.takeIf(String::isNotBlank) ?: return null
            val issuedAt = decoded.issuedAtAsInstant ?: return null
            val expiresAt = decoded.expiresAtAsInstant ?: return null

            if (!expiresAt.isAfter(issuedAt)) return null
            if (Duration.between(issuedAt, expiresAt) > ttl) return null

            VerifiedAccessTokenClaims(
                userId = userId,
                sessionId = sessionId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            )
        } catch (_: JWTVerificationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val ISSUED_AT_CLAIM = "iat"
        const val EXPIRES_AT_CLAIM = "exp"
        const val USER_ID_CLAIM = "userId"
        const val SESSION_ID_CLAIM = "sessionId"
        const val TOKEN_TYPE_CLAIM = "tokenType"
        const val ACCESS_TOKEN_TYPE = "access"
        const val MINIMUM_HMAC_KEY_BYTES = 32

        val MINIMUM_ACCESS_TOKEN_TTL: Duration = Duration.ofSeconds(1)
        val MAXIMUM_ACCESS_TOKEN_TTL: Duration = Duration.ofHours(1)
    }
}

/**
 * A signed token with its effective expiry. Its string form omits the token so
 * accidental logging cannot disclose the bearer credential.
 */
class IssuedAccessToken internal constructor(
    val value: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedAccessToken(expiresAt=$expiresAt)"
}

data class VerifiedAccessTokenClaims(
    val userId: String,
    val sessionId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
