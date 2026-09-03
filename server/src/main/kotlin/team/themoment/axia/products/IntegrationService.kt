package team.themoment.axia.products

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.domain.ProductId
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.integration.github.GitHubWorkClient
import team.themoment.axia.integration.github.ProjectBoardAdapter
import team.themoment.axia.integration.github.objects
import team.themoment.axia.integration.github.requiredText
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.query
import team.themoment.axia.reminders.taskOperations
import team.themoment.axia.scheduling.JobLease
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource

class IntegrationService(
    private val store: IntegrationStore,
    private val github: GitHubWorkClient,
    private val adapter: ProjectBoardAdapter,
    private val authorization: AuthorizationService,
    private val dataSource: DataSource,
    private val organization: String,
    private val installationId: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun products(userId: Long): ProductListResponse {
        val actor = authorization.requireCurrent(userId)
        val counts =
            withContext(Dispatchers.IO) {
                dataSource.inTransaction(readOnly = true) {
                    val ids = query("SELECT id FROM task_projections WHERE is_open=TRUE AND is_completed=FALSE") { it.getLong(1) }
                    taskOperations(this, ids, clock.instant())
                }
            }.associateBy { it.productId }
        return ProductListResponse(
            ProductId.entries.map { product ->
                val count = counts.getValue(product)
                ProductSummary(
                    product,
                    product.displayName,
                    count.openTasks.toInt(),
                    count.unassignedTasks.toInt(),
                    count.overdueTasks.toInt(),
                    AuthorizationPolicy.canManageIntegrations(actor, product),
                )
            },
        )
    }

    suspend fun integrations(
        userId: Long,
        product: ProductId,
    ): IntegrationListResponse {
        authorization.requireCurrent(userId)
        return IntegrationListResponse(
            store.repositories(product),
            store.projects(product),
            "https://github.com/organizations/$organization/settings/installations/$installationId",
        )
    }

    suspend fun addRepository(
        userId: Long,
        product: ProductId,
        request: AddRepositoryRequest,
    ): RepositoryIntegration {
        authorization.requireIntegrationManagement(userId, product)
        if (!request.name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) throw ApiFailure.invalidRequest("저장소 이름을 확인해 주세요.")
        val remote = verifyProvider { github.repository(request.name) }
        authorization.requireIntegrationManagement(userId, product)
        val id =
            store.addRepository(
                product,
                organization,
                remote.requiredText("name"),
                remote.requiredText("node_id"),
                guard(userId, product),
            )
        return store.repository(id)
    }

    suspend fun addProject(
        userId: Long,
        product: ProductId,
        request: AddProjectRequest,
    ): ProjectIntegration {
        authorization.requireIntegrationManagement(userId, product)
        if (request.number <= 0 || request.number == 19) throw ApiFailure.invalidRequest("템플릿을 제외한 운영 보드 번호를 입력해 주세요.")
        val remote = verifyProvider { github.project(request.number) }
        val mappings = verifyProvider { adapter.initialMappings(remote) }
        authorization.requireIntegrationManagement(userId, product)
        val id = store.addProject(product, request.number, remote, mappings, guard(userId, product))
        return store.project(id)
    }

    suspend fun updateRepository(
        userId: Long,
        product: ProductId,
        id: Long,
        request: UpdateIntegrationRequest,
    ): RepositoryIntegration {
        val repository = store.repository(id)
        if (repository.productId != product) throw missing()
        authorization.requireIntegrationManagement(userId, product)
        if (request.enabled) {
            val remote = verifyProvider { github.repository(repository.name) }
            authorization.requireIntegrationManagement(userId, product)
            if (repository.nodeId != null &&
                repository.nodeId != remote.requiredText("node_id")
            ) {
                throw ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "저장소의 원본 ID가 변경되었습니다. 연결 정보를 확인해 주세요.")
            }
            store.activateRepository(id, remote.requiredText("node_id"), guard(userId, product))
        } else {
            store.disableRepository(id, guard(userId, product))
        }
        return store.repository(id)
    }

    suspend fun updateProject(
        userId: Long,
        product: ProductId,
        id: Long,
        request: UpdateIntegrationRequest,
    ): ProjectIntegration {
        val project = store.project(id)
        if (project.productId != product) throw missing()
        authorization.requireIntegrationManagement(userId, product)
        if (request.enabled) {
            val remote = verifyProvider { github.project(project.number) }
            val fields = if (project.fields.isEmpty()) verifyProvider { adapter.initialMappings(remote) } else project.fields
            verifyProvider { adapter.validateMappings(remote, fields) }
            authorization.requireIntegrationManagement(userId, product)
            store.activateProject(id, remote, fields, remote.repositoryNodeIds.isEmpty(), guard(userId, product))
        } else {
            store.disableProject(id, guard(userId, product))
        }
        return store.project(id)
    }

    suspend fun setDefaultProject(
        userId: Long,
        repositoryId: Long,
        request: DefaultProjectRequest,
    ): RepositoryIntegration {
        val repository = store.repository(repositoryId)
        authorization.requireIntegrationManagement(userId, repository.productId)
        store.setDefaultProject(repositoryId, request.projectId, guard(userId, repository.productId))
        return store.repository(repositoryId)
    }

    suspend fun setMappings(
        userId: Long,
        projectId: Long,
        request: ProjectFieldMappingRequest,
    ): ProjectIntegration {
        val project = store.project(projectId)
        authorization.requireIntegrationManagement(userId, project.productId)
        val remote = verifyProvider { github.project(project.number) }
        verifyProvider { adapter.validateMappings(remote, request.fields) }
        authorization.requireIntegrationManagement(userId, project.productId)
        store.saveMappings(projectId, request.fields, guard(userId, project.productId))
        return store.project(projectId)
    }

    suspend fun fieldCatalog(
        userId: Long,
        projectId: Long,
    ): ProjectFieldCatalog {
        val project = store.project(projectId)
        authorization.requireIntegrationManagement(userId, project.productId)
        val remote = verifyProvider { github.project(project.number) }
        return ProjectFieldCatalog(
            projectId,
            remote.fields.map { field ->
                ProjectFieldDescriptor(
                    field.requiredText(
                        "id",
                    ),
                    field.requiredText("name"),
                    field.requiredText("dataType"),
                    field.objects("options").map {
                        ProjectFieldOption(it.requiredText("id"), it.requiredText("name"))
                    },
                )
            },
        )
    }

    /** Only unverified seed rows are auto-enabled; an administrator's disabled setting is preserved. */
    suspend fun verifyPendingSeeds(lease: JobLease) {
        val guard: (Connection) -> Unit = { lease.requireOwned(it, clock.instant()) }
        store.projects().filter { it.access == IntegrationAccess.PENDING }.forEach { project ->
            try {
                val remote = github.project(project.number)
                val mappings = adapter.initialMappings(remote)
                store.activateProject(project.id, remote, mappings, remote.repositoryNodeIds.isEmpty(), guard)
            } catch (failure: GitHubApiException) {
                if (failure.resourceUnavailable ||
                    (failure.invalidContract && !failure.authenticationFailure)
                ) {
                    store.setProjectUnavailable(project.id, guard)
                } else {
                    throw failure
                }
            }
        }
        store.repositories().filter { it.access == IntegrationAccess.PENDING }.forEach { repository ->
            try {
                val remote = github.repository(repository.name)
                store.activateRepository(repository.id, remote.requiredText("node_id"), guard)
                val number = initialDefaultProjects[repository.name]
                if (number !=
                    null
                ) {
                    store
                        .projects(
                            repository.productId,
                        ).singleOrNull { it.number == number }
                        ?.let { store.setDefaultProject(repository.id, it.id, guard) }
                }
            } catch (failure: GitHubApiException) {
                if (failure.status in setOf(404, 422)) store.setRepositoryUnavailable(repository.id, guard) else throw failure
            }
        }
    }

    private fun guard(
        userId: Long,
        product: ProductId,
    ): (Connection) -> Unit =
        { connection ->
            if (!AuthorizationPolicy.canManageIntegrations(authorization.requireCurrent(connection, userId), product)) throw accessDenied()
        }

    private suspend fun <T> verifyProvider(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: GitHubApiException) {
            throw ApiFailure(
                if (failure.status in setOf(403, 404, 422) ||
                    failure.invalidContract
                ) {
                    HttpStatusCode.Conflict
                } else {
                    HttpStatusCode.BadGateway
                },
                if (failure.invalidContract) ApiErrorCode.InvalidProviderContract else ApiErrorCode.ExternalServiceUnavailable,
                "GitHub App의 접근 권한과 보드 필드 구성을 확인해 주세요. Issue·PR 원본은 변경하지 않았습니다.",
            )
        }

    private fun missing(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "연동 대상을 찾을 수 없습니다.")

    private companion object {
        val initialDefaultProjects =
            mapOf(
                "datagsm-server" to 23,
                "datagsm-client" to 24,
                "hellogsm-server-26" to 25,
                "hellogsm-client-26" to 26,
                "readygsm-server" to 27,
                "readygsm-client" to 28,
                "everygsm-server-v2" to 29,
                "everygsm-client-v2" to 30,
            )
    }
}
