package team.themoment.axia.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoSupport {
    private const val SHA_256_ALGORITHM = "SHA-256"
    private const val HMAC_SHA_256_ALGORITHM = "HmacSHA256"
    private const val DEFAULT_SECRET_ENTROPY_BYTES = 32
    private const val MINIMUM_SECRET_ENTROPY_BYTES = 16
    private const val MINIMUM_HMAC_KEY_BYTES = 32
    private const val PKCE_CONTEXT = "axia:pkce:v1:"

    private val secureRandom = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val pkceVerifierPattern = Regex("[A-Za-z0-9\\-._~]{43,128}")

    /** Returns an unpadded URL-safe Base64 secret backed by a CSPRNG. */
    fun generateUrlSafeSecret(entropyBytes: Int = DEFAULT_SECRET_ENTROPY_BYTES): String = generateUrlSafeSecret(entropyBytes, secureRandom)

    internal fun generateUrlSafeSecret(
        entropyBytes: Int,
        random: SecureRandom,
    ): String {
        require(entropyBytes >= MINIMUM_SECRET_ENTROPY_BYTES) {
            "A generated secret must contain at least $MINIMUM_SECRET_ENTROPY_BYTES bytes of entropy"
        }

        return ByteArray(entropyBytes)
            .also(random::nextBytes)
            .let(::encodeUrlSafe)
    }

    fun sha256(value: String): ByteArray = sha256(value.toByteArray(StandardCharsets.UTF_8))

    fun sha256(value: ByteArray): ByteArray =
        MessageDigest
            .getInstance(SHA_256_ALGORITHM)
            .digest(value)

    fun hmacSha256(
        key: ByteArray,
        value: String,
    ): ByteArray = hmacSha256(key, value.toByteArray(StandardCharsets.UTF_8))

    fun hmacSha256(
        key: ByteArray,
        value: ByteArray,
    ): ByteArray {
        require(key.isNotEmpty()) { "An HMAC key must not be empty" }

        return Mac
            .getInstance(HMAC_SHA_256_ALGORITHM)
            .apply { init(SecretKeySpec(key.copyOf(), HMAC_SHA_256_ALGORITHM)) }
            .doFinal(value)
    }

    fun constantTimeEquals(
        expected: ByteArray,
        actual: ByteArray,
    ): Boolean = MessageDigest.isEqual(expected, actual)

    /** Builds the RFC 7636 S256 challenge for a valid code verifier. */
    fun pkceS256Challenge(verifier: String): String {
        require(pkceVerifierPattern.matches(verifier)) { "Invalid RFC 7636 code verifier" }
        return encodeUrlSafe(sha256(verifier.toByteArray(StandardCharsets.US_ASCII)))
    }

    /**
     * Deterministically derives a 43-character RFC 7636 verifier without storing
     * the verifier itself. The context prefix separates this use of the key from
     * any other HMAC performed by the application.
     */
    fun derivePkceVerifier(
        serverSecret: ByteArray,
        transactionId: String,
    ): String {
        require(serverSecret.size >= MINIMUM_HMAC_KEY_BYTES) {
            "The PKCE derivation key must contain at least $MINIMUM_HMAC_KEY_BYTES bytes"
        }
        require(transactionId.isNotBlank()) { "Transaction id must not be blank" }

        return encodeUrlSafe(hmacSha256(serverSecret, PKCE_CONTEXT + transactionId))
    }

    private fun encodeUrlSafe(value: ByteArray): String = urlEncoder.encodeToString(value)
}
