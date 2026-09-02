package team.themoment.axia

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.authRoutes
import team.themoment.axia.auth.store.JdbcAuthStore
import team.themoment.axia.config.AxiaConfig
import team.themoment.axia.integration.datagsm.KtorDataGsmOAuthClient
import team.themoment.axia.integration.github.KtorGitHubIdentityClient
import team.themoment.axia.persistence.DatabaseFactory
import team.themoment.axia.plugins.configureErrorHandling
import team.themoment.axia.plugins.configureHttpPlugins
import team.themoment.axia.security.AccessTokenService
import team.themoment.axia.security.RefreshCredentialService
import java.time.Clock

fun main() {
    val config = AxiaConfig.fromEnvironment()
    embeddedServer(
        factory = Netty,
        port = config.server.port,
        host = config.server.host,
        module = { module(config) },
    ).start(wait = true)
}

fun Application.module() {
    module(AxiaConfig.fromEnvironment())
}

fun Application.module(config: AxiaConfig) {
    val runtime = AxiaRuntime.create(config)
    monitor.subscribe(ApplicationStopped) { runtime.close() }
    module(runtime.authService)
}

fun Application.module(authService: AuthService) {
    configureHttpPlugins()
    configureErrorHandling()

    routing {
        get("/") {
            call.respondText("Axia API")
        }
        get("/health") {
            call.respondText("ok")
        }
        authRoutes(authService)
    }
}

private class AxiaRuntime private constructor(
    val authService: AuthService,
    private val dataSource: HikariDataSource,
    private val httpClient: HttpClient,
) : AutoCloseable {
    override fun close() {
        httpClient.close()
        dataSource.close()
    }

    companion object {
        fun create(config: AxiaConfig): AxiaRuntime {
            val clock = Clock.systemUTC()
            val httpClient =
                HttpClient(CIO) {
                    expectSuccess = false
                    followRedirects = false
                    install(HttpTimeout) {
                        connectTimeoutMillis = PROVIDER_CONNECT_TIMEOUT_MILLIS
                        requestTimeoutMillis = PROVIDER_REQUEST_TIMEOUT_MILLIS
                        socketTimeoutMillis = PROVIDER_SOCKET_TIMEOUT_MILLIS
                    }
                }
            var dataSource: HikariDataSource? = null

            try {
                val dataGsmClient =
                    KtorDataGsmOAuthClient(
                        httpClient = httpClient,
                        clientId = config.dataGsm.clientId,
                        authorizationServerBaseUrl = config.dataGsm.authorizationBaseUrl.toString(),
                        resourceServerBaseUrl = config.dataGsm.resourceBaseUrl.toString(),
                        requiredScope = config.dataGsm.scope,
                    )
                val gitHubClient =
                    KtorGitHubIdentityClient(
                        httpClient = httpClient,
                        appIssuer = config.gitHub.appId.toString(),
                        installationId = config.gitHub.installationId,
                        privateKeyPem = config.gitHub.privateKeyPem,
                        organization = config.gitHub.organization,
                        userAgent = config.gitHub.userAgent,
                        apiBaseUrl = config.gitHub.apiBaseUrl.toString(),
                        apiVersion = config.gitHub.apiVersion,
                        clock = clock,
                    )
                val accessTokens =
                    AccessTokenService(
                        issuer = config.security.issuer,
                        audience = config.security.audience,
                        secret = config.security.accessTokenSecret,
                        ttl = config.security.accessTokenTtl,
                        clock = clock,
                    )
                val refreshCredentials = RefreshCredentialService(config.security.refreshCredentialSecret)

                dataSource = DatabaseFactory.createAndInitialize(config.database)
                val store = JdbcAuthStore(dataSource)
                val authService =
                    AuthService(
                        dataGsmConfig = config.dataGsm,
                        transactionSecret = config.security.transactionSecret,
                        dataGsmClient = dataGsmClient,
                        gitHubClient = gitHubClient,
                        store = store,
                        refreshCredentials = refreshCredentials,
                        accessTokens = accessTokens,
                        clock = clock,
                    )

                return AxiaRuntime(authService, dataSource, httpClient)
            } catch (failure: Throwable) {
                dataSource?.close()
                httpClient.close()
                throw failure
            }
        }

        private const val PROVIDER_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val PROVIDER_REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val PROVIDER_SOCKET_TIMEOUT_MILLIS = 15_000L
    }
}
