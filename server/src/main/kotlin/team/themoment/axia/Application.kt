package team.themoment.axia

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.authRoutes
import team.themoment.axia.config.AxiaConfig
import team.themoment.axia.plugins.configureErrorHandling
import team.themoment.axia.plugins.configureHttpPlugins
import team.themoment.axia.updates.PublishedUpdates
import team.themoment.axia.updates.updateRoutes

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
    val updates = config.updates?.let { PublishedUpdates(it.root) }
    val runtime = AxiaRuntime.create(config)
    monitor.subscribe(ApplicationStopped) { runtime.close() }
    monitor.subscribe(ApplicationStarted) { runtime.start() }
    configureApi(runtime.authService, runtime::ready) {
        runtime.routes(this)
        updates?.let { updateRoutes(it) }
    }
}

private fun Application.configureApi(
    authService: AuthService,
    readiness: suspend () -> Boolean,
    features: Route.() -> Unit,
) {
    configureHttpPlugins()
    configureErrorHandling()

    routing {
        get("/") {
            call.respondText("Axia API")
        }
        get("/health") {
            val ready = readiness()
            call.respondText(
                if (ready) "ready" else "not ready",
                status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
        get("/health/live") {
            call.respondText("alive")
        }
        get("/health/ready") {
            val ready = readiness()
            call.respondText(
                if (ready) "ready" else "not ready",
                status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
        authRoutes(authService)
        features()
    }
}
