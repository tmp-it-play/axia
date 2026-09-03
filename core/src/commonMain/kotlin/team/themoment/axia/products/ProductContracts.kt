package team.themoment.axia.products

import kotlinx.serialization.Serializable
import team.themoment.axia.domain.ProductId

@Serializable
data class ProductSummary(
    val id: ProductId,
    val displayName: String,
    val openTasks: Int,
    val unassignedTasks: Int,
    val overdueTasks: Int,
    val canManageIntegrations: Boolean,
)

@Serializable
data class ProductListResponse(
    val products: List<ProductSummary>,
)

@Serializable
enum class IntegrationAccess { PENDING, AVAILABLE, UNAVAILABLE }

@Serializable
data class RepositoryIntegration(
    val id: Long,
    val productId: ProductId,
    val nodeId: String?,
    val owner: String,
    val name: String,
    val enabled: Boolean,
    val access: IntegrationAccess,
    val defaultProjectId: Long?,
    val trackingStartedAtEpochMillis: Long?,
)

@Serializable
data class ProjectIntegration(
    val id: Long,
    val productId: ProductId,
    val nodeId: String?,
    val number: Int,
    val title: String?,
    val enabled: Boolean,
    val access: IntegrationAccess,
    val fields: List<ProjectFieldMapping> = emptyList(),
    val repositoryLinkWarning: Boolean = false,
)

@Serializable
enum class ProjectSemanticField { STATUS, PRIORITY, START_DATE, TARGET_DATE }

/** Option keys are stable Axia semantics; option values are GitHub's immutable IDs. */
@Serializable
data class ProjectFieldMapping(
    val semantic: ProjectSemanticField,
    val fieldNodeId: String,
    val options: Map<String, String> = emptyMap(),
)

@Serializable
data class IntegrationListResponse(
    val repositories: List<RepositoryIntegration>,
    val projects: List<ProjectIntegration>,
    val appSettingsUrl: String,
)

@Serializable
data class AddRepositoryRequest(
    val name: String,
)

@Serializable
data class AddProjectRequest(
    val number: Int,
)

@Serializable
data class UpdateIntegrationRequest(
    val enabled: Boolean,
)

@Serializable
data class DefaultProjectRequest(
    val projectId: Long?,
)

@Serializable
data class ProjectFieldMappingRequest(
    val fields: List<ProjectFieldMapping>,
)

@Serializable
data class ProjectFieldOption(
    val id: String,
    val name: String,
)

@Serializable
data class ProjectFieldDescriptor(
    val id: String,
    val name: String,
    val dataType: String,
    val options: List<ProjectFieldOption>,
)

@Serializable
data class ProjectFieldCatalog(
    val projectId: Long,
    val fields: List<ProjectFieldDescriptor>,
)
