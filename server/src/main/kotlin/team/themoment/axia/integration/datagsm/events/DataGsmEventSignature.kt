package team.themoment.axia.integration.datagsm.events

import team.themoment.axia.security.CryptoSupport

class DataGsmEventSignature(
    secret: String,
) {
    // DataGSM signs with the UTF-8 bytes of the displayed hex secret, not its hex-decoded bytes.
    private val key = secret.toByteArray(Charsets.UTF_8)

    init {
        require(SECRET_PATTERN.matches(secret)) { "DataGSM event secret must be the provider-issued 64-character lowercase hex string" }
    }

    fun verify(
        rawBody: ByteArray,
        signature: String?,
    ): Boolean {
        if (signature == null || !SIGNATURE_PATTERN.matches(signature)) return false
        val received =
            signature
                .removePrefix(PREFIX)
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        return CryptoSupport.constantTimeEquals(CryptoSupport.hmacSha256(key, rawBody), received)
    }

    override fun toString(): String = "DataGsmEventSignature([REDACTED])"

    private companion object {
        const val PREFIX = "sha256="
        val SECRET_PATTERN = Regex("[0-9a-f]{64}")
        val SIGNATURE_PATTERN = Regex("sha256=[0-9a-f]{64}")
    }
}
