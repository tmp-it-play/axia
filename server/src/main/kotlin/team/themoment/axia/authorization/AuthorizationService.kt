package team.themoment.axia.authorization

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.api.ApiErrorCode
import team.themoment.axia.api.ApiFailure
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.persistence.inTransaction
import java.sql.Connection
import javax.sql.DataSource

class AuthorizationService(
    private val dataSource: DataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun requireCurrent(userId: Long): AuthorizationSnapshot =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                requireCurrent(this, userId, forUpdate = false)
            }
        }

    /** Blocking transaction-aware overload for mutations that must authorize and write atomically. */
    fun requireCurrent(
        connection: Connection,
        userId: Long,
        forUpdate: Boolean = true,
    ): AuthorizationSnapshot {
        if (forUpdate) connection.lockMemberManagement()
        val actor = findAuthorizationSnapshot(connection, userId, forUpdate) ?: throw accessDenied()
        if (!actor.isEligible) throw accessDenied()
        return actor
    }

    suspend fun requireProductWrite(
        userId: Long,
        productId: ProductId,
    ): AuthorizationSnapshot = requireCurrent(userId).also { if (!AuthorizationPolicy.canWriteProduct(it, productId)) throw accessDenied() }

    suspend fun requireIntegrationManagement(
        userId: Long,
        productId: ProductId,
    ): AuthorizationSnapshot =
        requireCurrent(userId).also { if (!AuthorizationPolicy.canManageIntegrations(it, productId)) throw accessDenied() }

    suspend fun requireTaskWrite(
        userId: Long,
        productId: ProductId,
        taskId: Long,
    ): AuthorizationSnapshot =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                requireTaskWrite(this, userId, productId, taskId, forUpdate = false)
            }
        }

    fun requireTaskWrite(
        connection: Connection,
        userId: Long,
        productId: ProductId,
        taskId: Long,
        forUpdate: Boolean = true,
    ): AuthorizationSnapshot {
        val actor = requireCurrent(connection, userId, forUpdate)
        if (AuthorizationPolicy.canWriteProduct(actor, productId)) return actor
        if (!hasRetainedTaskWrite(connection, userId, productId, taskId, forUpdate)) throw accessDenied()
        return actor
    }
}

/** Task-scoped exceptions never confer product authority; callers must first check current eligibility. */
fun hasRetainedTaskWrite(
    connection: Connection,
    userId: Long,
    productId: ProductId,
    taskId: Long,
    forUpdate: Boolean = false,
): Boolean =
    listOf(SELECT_RETAINED_TASK_ACCESS, SELECT_RETAINED_REVIEW_ACCESS).any { sql ->
        connection.prepareStatement(sql + if (forUpdate) " FOR UPDATE" else "").use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, taskId)
            statement.setString(3, productId.name)
            statement.executeQuery().use { it.next() }
        }
    }

/** Reads only current state. Unknown organization-role data is rejected instead of granting defaults. */
fun findAuthorizationSnapshot(
    connection: Connection,
    userId: Long,
    forUpdate: Boolean = false,
): AuthorizationSnapshot? {
    if (userId <= 0) return null
    val base =
        connection.prepareStatement(SELECT_ACTOR + if (forUpdate) " FOR UPDATE" else "").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                if (!results.next()) return null
                ActorBase(
                    isActive = results.getBoolean("is_active"),
                    isGitHubMember = results.getBoolean("github_member"),
                    dataGsmStudentRole = results.getString("datagsm_student_role"),
                    role = results.getString("role")?.let(OrganizationRole::valueOf),
                    explicitAdmin = results.getBoolean("explicit_admin"),
                )
            }
        }
    val positions =
        connection.prepareStatement("SELECT position FROM user_positions WHERE user_id = ? ORDER BY position").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                buildSet { while (results.next()) add(TechnicalPosition.valueOf(results.getString("position"))) }
            }
        }
    val pocs =
        connection.prepareStatement("SELECT product_id FROM user_pocs WHERE user_id = ? ORDER BY product_id").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { results ->
                buildSet { while (results.next()) add(ProductId.valueOf(results.getString("product_id"))) }
            }
        }
    return AuthorizationSnapshot(
        userId = userId,
        isActive = base.isActive,
        isGitHubOrganizationMember = base.isGitHubMember,
        enrollmentStatus = enrollmentStatusForDataGsmRole(base.dataGsmStudentRole),
        organizationRole = base.role,
        positions = positions,
        pocs = pocs,
        isExplicitSystemAdministrator = base.explicitAdmin,
    )
}

fun Connection.lockMemberManagement() {
    prepareStatement("SELECT id FROM member_management_lock WHERE id = 1 FOR UPDATE").use { statement ->
        statement.executeQuery().use { results ->
            check(results.next()) { "Member management lock has not been initialized" }
        }
    }
}

fun accessDenied(): ApiFailure = ApiFailure(HttpStatusCode.Forbidden, ApiErrorCode.AccessDenied, "이 작업을 수행할 권한이 없습니다.")

private data class ActorBase(
    val isActive: Boolean,
    val isGitHubMember: Boolean,
    val dataGsmStudentRole: String,
    val role: OrganizationRole?,
    val explicitAdmin: Boolean,
)

private val SELECT_ACTOR =
    """
    SELECT u.is_active, u.datagsm_student_role, r.role,
           (g.github_user_id IS NOT NULL AND g.organization_membership = 'MEMBER') AS github_member,
           (a.user_id IS NOT NULL) AS explicit_admin
    FROM users u
    LEFT JOIN user_github_links g ON g.user_id = u.id
    LEFT JOIN organization_role_assignments r ON r.user_id = u.id
    LEFT JOIN explicit_admins a ON a.user_id = u.id
    WHERE u.id = ?
    """.trimIndent()

private val SELECT_RETAINED_TASK_ACCESS =
    """
    SELECT 1
    FROM task_write_grants g
    JOIN task_projections t ON t.id = g.task_id AND t.product_id = g.product_id
    JOIN task_assignees a ON a.task_id = t.id AND a.user_id = g.user_id AND a.github_user_id = g.github_user_id
    JOIN user_github_links l ON l.user_id = g.user_id AND l.github_user_id = g.github_user_id AND l.organization_membership = 'MEMBER'
    WHERE g.user_id = ? AND g.task_id = ? AND g.product_id = ?
      AND t.is_open = TRUE AND t.is_completed = FALSE
      AND t.lifecycle_version = g.lifecycle_version
      AND g.closed_occurrence_ids = COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY())
      AND NOT EXISTS (SELECT 1 FROM task_assignment_events e
        WHERE e.task_id = g.task_id AND e.github_user_id = g.github_user_id AND e.assigned = FALSE
          AND NOT JSON_CONTAINS(g.unassignment_occurrence_ids, JSON_QUOTE(e.occurrence_id)))
    LIMIT 1
    """.trimIndent()

private val SELECT_RETAINED_REVIEW_ACCESS =
    """
    SELECT 1
    FROM review_task_write_grants g
    JOIN task_projections t ON t.id = g.task_id AND t.product_id = g.product_id
    JOIN review_assignments a ON a.task_id = t.id AND a.user_id = g.user_id
      AND a.github_user_id = g.github_user_id AND a.request_occurrence_id = g.request_occurrence_id AND a.active = TRUE
    JOIN user_github_links l ON l.user_id = g.user_id AND l.github_user_id = g.github_user_id AND l.organization_membership = 'MEMBER'
    WHERE g.user_id = ? AND g.task_id = ? AND g.product_id = ?
      AND t.kind = 'PULL_REQUEST' AND t.is_open = TRUE AND t.is_completed = FALSE
      AND t.lifecycle_version = g.lifecycle_version
      AND g.closed_occurrence_ids = COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY())
    LIMIT 1
    """.trimIndent()
