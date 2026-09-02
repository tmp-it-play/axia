package team.themoment.axia.config

import team.themoment.axia.persistence.DatabaseConfig
import java.net.URI
import java.time.Duration

data class AxiaConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val dataGsm: DataGsmConfig,
    val gitHub: GitHubConfig,
    val security: SecurityConfig,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): AxiaConfig {
            val values = EnvironmentValues(environment)

            return AxiaConfig(
                server =
                    ServerConfig(
                        host = values.optional("AXIA_SERVER_HOST", "0.0.0.0"),
                        port = values.positiveInt("AXIA_SERVER_PORT", 8080),
                    ),
                database =
                    DatabaseConfig(
                        jdbcUrl = values.required("AXIA_DATABASE_URL"),
                        username = values.required("AXIA_DATABASE_USER"),
                        password = values.required("AXIA_DATABASE_PASSWORD"),
                        maximumPoolSize = values.positiveInt("AXIA_DATABASE_POOL_SIZE", 10),
                    ),
                dataGsm =
                    DataGsmConfig(
                        clientId = values.required("AXIA_DATAGSM_CLIENT_ID"),
                        authorizationBaseUrl =
                            values.httpsUrl(
                                "AXIA_DATAGSM_AUTHORIZATION_BASE_URL",
                                "https://oauth.authorization.datagsm.kr",
                            ),
                        resourceBaseUrl =
                            values.httpsUrl(
                                "AXIA_DATAGSM_RESOURCE_BASE_URL",
                                "https://oauth.resource.datagsm.kr",
                            ),
                        redirectUri =
                            values.httpsUrl(
                                "AXIA_DATAGSM_REDIRECT_URI",
                                "https://kimtaeeun.site/axia/api/auth/datagsm/callback",
                            ),
                        requiredMajorClubId = values.positiveLong("AXIA_DATAGSM_THEMOMENT_CLUB_ID"),
                    ),
                gitHub =
                    GitHubConfig(
                        appId = values.positiveLong("AXIA_GITHUB_APP_ID"),
                        installationId = values.positiveLong("AXIA_GITHUB_INSTALLATION_ID"),
                        organization = values.optional("AXIA_GITHUB_ORGANIZATION", "themoment-team"),
                        privateKeyPem = values.required("AXIA_GITHUB_PRIVATE_KEY").replace("\\n", "\n"),
                        apiBaseUrl = values.httpsUrl("AXIA_GITHUB_API_BASE_URL", "https://api.github.com"),
                        apiVersion = values.optional("AXIA_GITHUB_API_VERSION", "2026-03-10"),
                        userAgent = values.optional("AXIA_GITHUB_USER_AGENT", "Axia/1.0.0"),
                    ),
                security =
                    SecurityConfig(
                        accessTokenSecret = values.secret("AXIA_ACCESS_TOKEN_SECRET"),
                        transactionSecret = values.secret("AXIA_AUTH_TRANSACTION_SECRET"),
                        refreshCredentialSecret = values.secret("AXIA_REFRESH_CREDENTIAL_SECRET"),
                        issuer = values.optional("AXIA_ACCESS_TOKEN_ISSUER", "axia-api"),
                        audience = values.optional("AXIA_ACCESS_TOKEN_AUDIENCE", "axia-desktop"),
                    ),
            )
        }
    }
}

data class ServerConfig(
    val host: String,
    val port: Int,
)

data class DataGsmConfig(
    val clientId: String,
    val authorizationBaseUrl: URI,
    val resourceBaseUrl: URI,
    val redirectUri: URI,
    val requiredMajorClubId: Long,
    val scope: String = "datagsm:self_read",
    val transactionTtl: Duration = Duration.ofMinutes(5),
)

class GitHubConfig(
    val appId: Long,
    val installationId: Long,
    val organization: String,
    privateKeyPem: String,
    val apiBaseUrl: URI,
    val apiVersion: String,
    val userAgent: String,
) {
    val privateKeyPem: String = privateKeyPem

    override fun toString(): String =
        "GitHubConfig(appId=$appId, installationId=$installationId, organization=$organization, " +
            "privateKeyPem=[REDACTED], apiBaseUrl=$apiBaseUrl, apiVersion=$apiVersion, userAgent=$userAgent)"
}

class SecurityConfig(
    accessTokenSecret: ByteArray,
    transactionSecret: ByteArray,
    refreshCredentialSecret: ByteArray,
    val issuer: String,
    val audience: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
) {
    val accessTokenSecret: ByteArray = accessTokenSecret.copyOf()
        get() = field.copyOf()
    val transactionSecret: ByteArray = transactionSecret.copyOf()
        get() = field.copyOf()
    val refreshCredentialSecret: ByteArray = refreshCredentialSecret.copyOf()
        get() = field.copyOf()

    override fun toString(): String =
        "SecurityConfig(accessTokenSecret=[REDACTED], transactionSecret=[REDACTED], " +
            "refreshCredentialSecret=[REDACTED], issuer=$issuer, audience=$audience, " +
            "accessTokenTtl=$accessTokenTtl)"
}

class ConfigurationException(
    message: String,
) : IllegalStateException(message)

private class EnvironmentValues(
    private val values: Map<String, String>,
) {
    fun required(name: String): String =
        values[name]?.trim()?.takeIf(String::isNotEmpty)
            ?: throw ConfigurationException("Required environment variable is missing: $name")

    fun optional(
        name: String,
        defaultValue: String,
    ): String = values[name]?.trim()?.takeIf(String::isNotEmpty) ?: defaultValue

    fun secret(name: String): ByteArray =
        required(name).encodeToByteArray().also {
            if (it.size < MINIMUM_SECRET_BYTES) {
                throw ConfigurationException("$name must contain at least $MINIMUM_SECRET_BYTES UTF-8 bytes")
            }
        }

    fun positiveInt(
        name: String,
        defaultValue: Int,
    ): Int =
        optional(name, defaultValue.toString()).toIntOrNull()?.takeIf { it > 0 }
            ?: throw ConfigurationException("$name must be a positive integer")

    fun positiveLong(name: String): Long =
        required(name).toLongOrNull()?.takeIf { it > 0 }
            ?: throw ConfigurationException("$name must be a positive integer")

    fun httpsUrl(
        name: String,
        defaultValue: String,
    ): URI {
        val value = optional(name, defaultValue)
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            throw ConfigurationException("$name must be an absolute HTTPS URL")
        }
        return uri
    }

    private companion object {
        const val MINIMUM_SECRET_BYTES = 32
    }
}
