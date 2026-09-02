package team.themoment.axia.security

import java.security.SecureRandom

class RefreshCredentialService(
    digestKey: ByteArray,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val digestKey = digestKey.copyOf()

    init {
        require(digestKey.size >= MINIMUM_DIGEST_KEY_BYTES) {
            "The refresh credential digest key must contain at least $MINIMUM_DIGEST_KEY_BYTES bytes"
        }
    }

    /** Issues a credential in the `sessionId.secret` format and its storage digest. */
    fun issue(sessionId: String): IssuedRefreshCredential {
        require(sessionIdPattern.matches(sessionId)) { "Invalid session id" }

        val secret =
            CryptoSupport.generateUrlSafeSecret(
                entropyBytes = REFRESH_SECRET_ENTROPY_BYTES,
                random = secureRandom,
            )
        val value = "$sessionId.$secret"
        return IssuedRefreshCredential(value, digestUnchecked(value))
    }

    /** Parses only the non-secret lookup portion of a well-formed credential. */
    fun parse(credential: String): ParsedRefreshCredential? {
        if (credential.length !in MINIMUM_CREDENTIAL_LENGTH..MAXIMUM_CREDENTIAL_LENGTH) return null

        val separatorIndex = credential.indexOf(CREDENTIAL_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex != credential.lastIndexOf(CREDENTIAL_SEPARATOR)) return null

        val sessionId = credential.substring(0, separatorIndex)
        val secret = credential.substring(separatorIndex + 1)
        if (!sessionIdPattern.matches(sessionId) || !refreshSecretPattern.matches(secret)) return null

        return ParsedRefreshCredential(sessionId)
    }

    /** Returns the keyed storage digest, or null when the credential is malformed. */
    fun keyedDigest(credential: String): ByteArray? =
        if (parse(credential) == null) {
            null
        } else {
            digestUnchecked(credential)
        }

    /** Compares a candidate with a stored digest without exposing the secret. */
    fun matches(
        credential: String,
        expectedDigest: ByteArray,
    ): Boolean {
        val actualDigest = keyedDigest(credential) ?: return false
        return CryptoSupport.constantTimeEquals(expectedDigest, actualDigest)
    }

    /** Returns the parsed session id only when the credential digest is valid. */
    fun verify(
        credential: String,
        expectedDigest: ByteArray,
    ): ParsedRefreshCredential? {
        val parsed = parse(credential) ?: return null
        val actualDigest = digestUnchecked(credential)
        return parsed.takeIf { CryptoSupport.constantTimeEquals(expectedDigest, actualDigest) }
    }

    private fun digestUnchecked(credential: String): ByteArray = CryptoSupport.hmacSha256(digestKey, DIGEST_CONTEXT + credential)

    private companion object {
        const val CREDENTIAL_SEPARATOR = '.'
        const val REFRESH_SECRET_ENTROPY_BYTES = 32
        const val REFRESH_SECRET_LENGTH = 43
        const val MINIMUM_DIGEST_KEY_BYTES = 32
        const val MAXIMUM_SESSION_ID_LENGTH = 128
        const val MINIMUM_CREDENTIAL_LENGTH = 1 + 1 + REFRESH_SECRET_LENGTH
        const val MAXIMUM_CREDENTIAL_LENGTH = MAXIMUM_SESSION_ID_LENGTH + 1 + REFRESH_SECRET_LENGTH
        const val DIGEST_CONTEXT = "axia:refresh:v1:"

        val sessionIdPattern = Regex("[A-Za-z0-9_-]{1,$MAXIMUM_SESSION_ID_LENGTH}")
        val refreshSecretPattern = Regex("[A-Za-z0-9_-]{$REFRESH_SECRET_LENGTH}")
    }
}

/**
 * A newly issued raw credential and the digest to persist. Its string form is
 * deliberately redacted so incidental structured logging cannot expose it.
 */
class IssuedRefreshCredential internal constructor(
    val value: String,
    digest: ByteArray,
) {
    private val digestBytes = digest.copyOf()

    val digest: ByteArray
        get() = digestBytes.copyOf()

    override fun toString(): String = "IssuedRefreshCredential([REDACTED])"
}

/** Parsing deliberately returns no secret-bearing field. */
data class ParsedRefreshCredential(
    val sessionId: String,
)
