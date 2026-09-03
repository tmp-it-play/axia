package team.themoment.axia.products

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.domain.ProductId
import team.themoment.axia.integration.github.GitHubProjectSnapshot
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.insertId
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

class IntegrationStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json

    suspend fun repositories(product: ProductId? = null): List<RepositoryIntegration> =
        database {
            query(
                "SELECT * FROM repository_integrations WHERE (? IS NULL OR product_id=?) ORDER BY id",
                product?.name,
                product?.name,
                map = ::repositoryRow,
            )
        }

    suspend fun projects(product: ProductId? = null): List<ProjectIntegration> =
        database {
            query(
                "SELECT * FROM project_integrations WHERE (? IS NULL OR product_id=?) ORDER BY project_number",
                product?.name,
                product?.name,
            ) { result ->
                projectRow(result)
            }.map { project -> project.copy(fields = fields(project.id)) }
        }

    suspend fun repository(id: Long): RepositoryIntegration =
        database {
            query("SELECT * FROM repository_integrations WHERE id=?", id, map = ::repositoryRow).singleOrNull()
                ?: throw missingIntegration()
        }

    suspend fun project(id: Long): ProjectIntegration =
        database {
            val project =
                query("SELECT * FROM project_integrations WHERE id=?", id, map = ::projectRow).singleOrNull() ?: throw missingIntegration()
            project.copy(fields = fields(id))
        }

    suspend fun addRepository(
        product: ProductId,
        owner: String,
        name: String,
        nodeId: String,
        guard: (Connection) -> Unit,
    ): Long =
        database(guard) {
            insertId(
                "INSERT INTO repository_integrations(product_id,owner,name,github_node_id,enabled,access_state,tracking_started_at) VALUES(?,?,?,?,TRUE,'AVAILABLE',?)",
                product,
                owner,
                name,
                nodeId,
                clock.instant(),
            )
        }

    suspend fun addProject(
        product: ProductId,
        number: Int,
        snapshot: GitHubProjectSnapshot,
        mappings: List<ProjectFieldMapping>,
        guard: (Connection) -> Unit,
    ): Long =
        database(guard) {
            val id =
                insertId(
                    "INSERT INTO project_integrations(product_id,project_number,github_node_id,title,enabled,access_state,repository_link_warning) VALUES(?,?,?,?,TRUE,'AVAILABLE',?)",
                    product,
                    number,
                    snapshot.nodeId,
                    snapshot.title,
                    snapshot.repositoryNodeIds.isEmpty(),
                )
            replaceFields(id, mappings)
            id
        }

    suspend fun activateRepository(
        id: Long,
        nodeId: String,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        val previous =
            query("SELECT * FROM repository_integrations WHERE id=? FOR UPDATE", id, map = ::repositoryRow).singleOrNull()
                ?: throw missingIntegration()
        if (previous.nodeId != null && previous.nodeId != nodeId) throw integrationConflict()
        execute(
            "UPDATE repository_integrations SET github_node_id=?,enabled=TRUE,access_state='AVAILABLE',tracking_started_at=COALESCE(tracking_started_at,?) WHERE id=?",
            nodeId,
            clock.instant(),
            id,
        )
    }

    suspend fun activateProject(
        id: Long,
        snapshot: GitHubProjectSnapshot,
        mappings: List<ProjectFieldMapping>,
        warning: Boolean,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        val previous =
            query("SELECT * FROM project_integrations WHERE id=? FOR UPDATE", id, map = ::projectRow).singleOrNull()
                ?: throw missingIntegration()
        if (previous.nodeId != null && previous.nodeId != snapshot.nodeId) throw integrationConflict()
        execute(
            "UPDATE project_integrations SET github_node_id=?,title=?,enabled=TRUE,access_state='AVAILABLE',repository_link_warning=?,updated_at=? WHERE id=?",
            snapshot.nodeId,
            snapshot.title,
            warning,
            clock.instant(),
            id,
        )
        replaceFields(id, mappings)
    }

    suspend fun saveMappings(
        id: Long,
        mappings: List<ProjectFieldMapping>,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        query("SELECT id FROM project_integrations WHERE id=? FOR UPDATE", id) { it.getLong(1) }.singleOrNull()
            ?: throw missingIntegration()
        replaceFields(id, mappings)
        execute("UPDATE project_integrations SET updated_at=? WHERE id=?", clock.instant(), id)
    }

    suspend fun setRepositoryUnavailable(
        id: Long,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        execute("UPDATE repository_integrations SET enabled=FALSE,access_state='UNAVAILABLE' WHERE id=?", id)
    }

    suspend fun setProjectUnavailable(
        id: Long,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        execute("UPDATE project_integrations SET enabled=FALSE,access_state='UNAVAILABLE' WHERE id=? AND access_state='PENDING'", id)
    }

    /** The caller has fenced its lease/receipt before applying the failed read's projection. */
    fun setProjectUnavailable(
        connection: Connection,
        expected: ProjectIntegration,
        requestedAt: Instant,
        now: Instant,
    ) = with(connection) {
        check(!autoCommit)
        val current =
            query("SELECT github_node_id,updated_at FROM project_integrations WHERE id=? AND enabled=TRUE FOR UPDATE", expected.id) {
                it.getString("github_node_id") to requireNotNull(it.instant("updated_at"))
            }.singleOrNull() ?: return@with
        // Do not overwrite a repair, new mapping, or administrator toggle made during the remote read.
        if (current.first != expected.nodeId || current.second.isAfter(requestedAt) ||
            fields(expected.id).toSet() != expected.fields.toSet()
        ) {
            return@with
        }
        execute("UPDATE project_integrations SET enabled=FALSE,access_state='UNAVAILABLE',updated_at=? WHERE id=?", now, expected.id)
    }

    suspend fun disableRepository(
        id: Long,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        execute("UPDATE repository_integrations SET enabled=FALSE WHERE id=?", id)
    }

    suspend fun disableProject(
        id: Long,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        execute("UPDATE project_integrations SET enabled=FALSE WHERE id=?", id)
    }

    suspend fun setDefaultProject(
        repositoryId: Long,
        projectId: Long?,
        guard: (Connection) -> Unit,
    ) = database(guard) {
        val repository =
            query("SELECT * FROM repository_integrations WHERE id=? FOR UPDATE", repositoryId, map = ::repositoryRow).singleOrNull()
                ?: throw missingIntegration()
        if (projectId != null) {
            val project =
                query("SELECT * FROM project_integrations WHERE id=? FOR UPDATE", projectId, map = ::projectRow).singleOrNull()
                    ?: throw missingIntegration()
            if (repository.productId != project.productId) throw ApiFailure.invalidRequest("기본 보드는 저장소와 같은 제품에 속해야 합니다.")
        }
        execute("UPDATE repository_integrations SET default_project_id=? WHERE id=?", projectId, repositoryId)
    }

    private fun Connection.fields(projectId: Long): List<ProjectFieldMapping> =
        query("SELECT * FROM project_field_mappings WHERE project_id=? ORDER BY semantic", projectId) { result ->
            ProjectFieldMapping(
                ProjectSemanticField.valueOf(result.getString("semantic")),
                result.getString("field_node_id"),
                json.decodeFromString<Map<String, String>>(result.getString("option_ids_json")),
            )
        }

    private fun Connection.replaceFields(
        projectId: Long,
        mappings: List<ProjectFieldMapping>,
    ) {
        execute("DELETE FROM project_field_mappings WHERE project_id=?", projectId)
        mappings.forEach { field ->
            execute(
                "INSERT INTO project_field_mappings(project_id,semantic,field_node_id,option_ids_json) VALUES(?,?,?,?)",
                projectId,
                field.semantic,
                field.fieldNodeId,
                json.encodeToString(field.options),
            )
        }
    }

    private suspend fun <T> database(
        guard: ((Connection) -> Unit)? = null,
        block: Connection.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                dataSource.inTransaction {
                    guard?.invoke(this)
                    block()
                }
            } catch (failure: SQLException) {
                if (failure.sqlState == "23000") throw integrationConflict()
                throw failure
            }
        }

    private fun repositoryRow(result: ResultSet): RepositoryIntegration =
        RepositoryIntegration(
            id = result.getLong("id"),
            productId = ProductId.valueOf(result.getString("product_id")),
            nodeId = result.getString("github_node_id"),
            owner = result.getString("owner"),
            name = result.getString("name"),
            enabled = result.getBoolean("enabled"),
            access = IntegrationAccess.valueOf(result.getString("access_state")),
            defaultProjectId = result.nullableLong("default_project_id"),
            trackingStartedAtEpochMillis = result.instant("tracking_started_at")?.toEpochMilli(),
        )

    private fun projectRow(result: ResultSet): ProjectIntegration =
        ProjectIntegration(
            id = result.getLong("id"),
            productId = ProductId.valueOf(result.getString("product_id")),
            nodeId = result.getString("github_node_id"),
            number = result.getInt("project_number"),
            title = result.getString("title"),
            enabled = result.getBoolean("enabled"),
            access = IntegrationAccess.valueOf(result.getString("access_state")),
            repositoryLinkWarning = result.getBoolean("repository_link_warning"),
        )

    private fun missingIntegration(): ApiFailure = ApiFailure(HttpStatusCode.NotFound, ApiErrorCode.ResourceNotFound, "연동 대상을 찾을 수 없습니다.")

    private fun integrationConflict(): ApiFailure =
        ApiFailure(HttpStatusCode.Conflict, ApiErrorCode.Conflict, "저장소와 보드는 한 제품에만 연결할 수 있습니다.")
}
