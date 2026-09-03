package team.themoment.axia

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.analytics.AnalyticsService
import team.themoment.axia.analytics.analyticsRoutes
import team.themoment.axia.auth.AuthService
import team.themoment.axia.auth.store.JdbcAuthStore
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.config.AxiaConfig
import team.themoment.axia.integration.datagsm.KtorDataGsmOAuthClient
import team.themoment.axia.integration.datagsm.events.DataGsmEventDecoder
import team.themoment.axia.integration.datagsm.events.DataGsmEventService
import team.themoment.axia.integration.datagsm.events.DataGsmEventSignature
import team.themoment.axia.integration.datagsm.events.JdbcDataGsmEventProcessor
import team.themoment.axia.integration.github.GitHubAppTransport
import team.themoment.axia.integration.github.GitHubMutationClient
import team.themoment.axia.integration.github.GitHubSyncService
import team.themoment.axia.integration.github.GitHubWebhookDeliveryClient
import team.themoment.axia.integration.github.GitHubWorkClient
import team.themoment.axia.integration.github.KtorGitHubIdentityClient
import team.themoment.axia.integration.github.ProjectBoardAdapter
import team.themoment.axia.members.MemberBootstrap
import team.themoment.axia.members.MemberService
import team.themoment.axia.members.memberRoutes
import team.themoment.axia.notifications.NotificationService
import team.themoment.axia.notifications.notificationRoutes
import team.themoment.axia.notifications.reminderRoutes
import team.themoment.axia.persistence.DatabaseFactory
import team.themoment.axia.products.IntegrationService
import team.themoment.axia.products.IntegrationStore
import team.themoment.axia.products.integrationRoutes
import team.themoment.axia.reminders.ReminderService
import team.themoment.axia.scheduling.MaintenanceService
import team.themoment.axia.scheduling.MySqlJobScheduler
import team.themoment.axia.scheduling.ProviderHealthStore
import team.themoment.axia.security.AccessTokenService
import team.themoment.axia.security.RefreshCredentialService
import team.themoment.axia.tasks.TaskMutationService
import team.themoment.axia.tasks.TaskService
import team.themoment.axia.tasks.TaskStore
import team.themoment.axia.tasks.taskMutationRoutes
import team.themoment.axia.tasks.taskRoutes
import team.themoment.axia.unavailability.UnavailabilityService
import team.themoment.axia.unavailability.unavailabilityRoutes
import team.themoment.axia.webhooks.GitHubWebhookRedeliveryService
import team.themoment.axia.webhooks.GitHubWebhookService
import team.themoment.axia.webhooks.GitHubWebhookSignature
import team.themoment.axia.webhooks.WebhookReceiptStore
import team.themoment.axia.webhooks.dataGsmWebhookRoutes
import team.themoment.axia.webhooks.gitHubWebhookRoutes
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Owns every production service and its lifetime; feature routes never instantiate separate stores. */
internal class AxiaRuntime private constructor(
    val authService: AuthService,
    private val dataSource: HikariDataSource,
    private val httpClient: HttpClient,
    private val scheduler: MySqlJobScheduler,
    private val config: AxiaConfig,
    private val clock: Clock,
    private val health: ProviderHealthStore,
    private val work: GitHubWorkClient,
    private val sync: GitHubSyncService,
    private val members: MemberService,
    private val unavailability: UnavailabilityService,
    private val integrations: IntegrationService,
    private val tasks: TaskService,
    private val mutations: TaskMutationService,
    private val notifications: NotificationService,
    private val reminders: ReminderService,
    private val analytics: AnalyticsService,
    private val dataGsmEvents: DataGsmEventService,
    private val gitHubEvents: GitHubWebhookService,
    private val redelivery: GitHubWebhookRedeliveryService,
    private val maintenance: MaintenanceService,
) : AutoCloseable {
    private val started = AtomicBoolean(false)

    fun routes(route: Route) =
        with(route) {
            memberRoutes(authService, members)
            unavailabilityRoutes(authService, unavailability)
            integrationRoutes(authService, integrations)
            taskRoutes(authService, tasks)
            taskMutationRoutes(authService, mutations)
            notificationRoutes(authService, notifications)
            reminderRoutes(authService, reminders)
            analyticsRoutes(authService, analytics)
            dataGsmWebhookRoutes(dataGsmEvents)
            gitHubWebhookRoutes(gitHubEvents)
        }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scheduler.register(RECONCILIATION_JOB, config.jobs.incremental, jitter = true) { lease ->
            integrations.verifyPendingSeeds(lease)
            sync.reconcile(sync.fullReconciliationDue(config.jobs.full), lease)
        }
        scheduler.register("reminders", config.jobs.reminders) { reminders.process(it) }
        scheduler.register("notification-retention", Duration.ofDays(1)) { notifications.purgeRead(it) }
        scheduler.register("github-webhook-redelivery", config.jobs.redelivery, jitter = true) { redelivery.run(it) }
        scheduler.register("ephemeral-retention", Duration.ofHours(1)) { maintenance.cleanup(it) }
        scheduler.register("github-recovery", config.jobs.health, jitter = true) {
            val state = health.current()
            if (state.readOnly && state.retryAt?.isAfter(clock.instant()) != true) {
                // A probe can request reconciliation, but cannot itself re-enable writes.
                work.installationRepositories()
                scheduler.requestRun(RECONCILIATION_JOB, recovered = true)
            }
        }
    }

    suspend fun ready(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { it.isValid(2) }
            } catch (_: Exception) {
                false
            }
        }

    override fun close() {
        scheduler.close()
        httpClient.close()
        dataSource.close()
    }

    companion object {
        private const val RECONCILIATION_JOB = "github-reconciliation"

        fun create(config: AxiaConfig): AxiaRuntime {
            val clock = Clock.systemUTC()
            val client =
                HttpClient(CIO) {
                    expectSuccess = false
                    followRedirects = false
                    install(HttpTimeout) {
                        connectTimeoutMillis = 5_000
                        requestTimeoutMillis = 15_000
                        socketTimeoutMillis = 15_000
                    }
                }
            var dataSource: HikariDataSource? = null
            var scheduler: MySqlJobScheduler? = null
            try {
                val dataGsm =
                    KtorDataGsmOAuthClient(
                        httpClient = client,
                        clientId = config.dataGsm.clientId,
                        authorizationServerBaseUrl = config.dataGsm.authorizationBaseUrl.toString(),
                        resourceServerBaseUrl = config.dataGsm.resourceBaseUrl.toString(),
                        requiredScope = config.dataGsm.scope,
                    )
                val identity =
                    KtorGitHubIdentityClient(
                        httpClient = client,
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
                val database = DatabaseFactory.createAndInitialize(config.database)
                dataSource = database
                MemberBootstrap.initializeForEmptyDatabase(database, clock.instant())
                val authStore = JdbcAuthStore(database)
                val auth =
                    AuthService(
                        dataGsmConfig = config.dataGsm,
                        transactionSecret = config.security.transactionSecret,
                        dataGsmClient = dataGsm,
                        gitHubClient = identity,
                        store = authStore,
                        refreshCredentials = RefreshCredentialService(config.security.refreshCredentialSecret),
                        accessTokens = accessTokens,
                        clock = clock,
                    )
                val authorization = AuthorizationService(database)
                val health = ProviderHealthStore(database, clock)
                val transport = GitHubAppTransport(client, config.gitHub, clock, health::markReadFailure)
                val work = GitHubWorkClient(transport, config.gitHub.organization, clock)
                val adapter = ProjectBoardAdapter(transport)
                val integrationStore = IntegrationStore(database, clock)
                val tasks = TaskStore(database, clock)
                val sync = GitHubSyncService(database, integrationStore, work, adapter, tasks, authStore, identity, health, clock)
                val receipts = WebhookReceiptStore(database)
                val eventProcessor =
                    JdbcDataGsmEventProcessor(database, authStore, receipts, identity, config.dataGsm.requiredMajorClubId, clock)
                val jobs = MySqlJobScheduler(database, clock)
                scheduler = jobs
                return AxiaRuntime(
                    auth,
                    database,
                    client,
                    jobs,
                    config,
                    clock,
                    health,
                    work,
                    sync,
                    MemberService(database, authorization, clock),
                    UnavailabilityService(database, authorization, clock),
                    IntegrationService(
                        integrationStore,
                        work,
                        adapter,
                        authorization,
                        database,
                        config.gitHub.organization,
                        config.gitHub.installationId,
                    ),
                    TaskService(authorization, tasks),
                    TaskMutationService(
                        database,
                        authorization,
                        integrationStore,
                        tasks,
                        work,
                        GitHubMutationClient(transport),
                        adapter,
                        sync,
                        health,
                        clock,
                    ),
                    NotificationService(database, authorization, clock),
                    ReminderService(database, authorization, clock),
                    AnalyticsService(database, authorization, clock),
                    DataGsmEventService(
                        DataGsmEventSignature(config.webhooks.dataGsmEventSecret),
                        DataGsmEventDecoder(),
                        receipts,
                        eventProcessor,
                        clock,
                    ),
                    GitHubWebhookService(
                        database,
                        GitHubWebhookSignature(config.webhooks.gitHubWebhookSecret.toString(Charsets.UTF_8)),
                        receipts,
                        sync,
                        config.gitHub.installationId,
                        clock,
                    ),
                    GitHubWebhookRedeliveryService(
                        database,
                        GitHubWebhookDeliveryClient(transport),
                        config.gitHub.installationId,
                        clock,
                        config.jobs.redelivery,
                    ),
                    MaintenanceService(database, clock),
                )
            } catch (failure: Throwable) {
                scheduler?.close()
                dataSource?.close()
                client.close()
                throw failure
            }
        }
    }
}
