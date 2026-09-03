package team.themoment.axia.members

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.authorization.AuthorizationPolicy
import team.themoment.axia.authorization.AuthorizationService
import team.themoment.axia.authorization.AuthorizationSnapshot
import team.themoment.axia.authorization.accessDenied
import team.themoment.axia.authorization.findAuthorizationSnapshot
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.unavailability.AvailabilitySummary
import team.themoment.axia.unavailability.UnavailabilityType
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

class MemberService(
    private val dataSource: DataSource,
    private val authorization: AuthorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun currentUser(actorId: Long): CurrentUserResponse =
        read { connection ->
            val actor = authorization.requireCurrent(connection, actorId, forUpdate = false)
            CurrentUserResponse(readMemberDetail(connection, actorId, clock.instant()).member, AuthorizationPolicy.capabilities(actor))
        }

    suspend fun listMembers(
        actorId: Long,
        activeOnly: Boolean = true,
        unassignedPositionsOnly: Boolean = false,
    ): MemberListResponse =
        read { connection ->
            authorization.requireCurrent(connection, actorId, forUpdate = false)
            val now = clock.instant()
            val ids =
                connection
                    .prepareStatement(
                        "SELECT u.id FROM users u WHERE (? = FALSE OR u.is_active = TRUE) " +
                            "AND (? = FALSE OR NOT EXISTS (SELECT 1 FROM user_positions p WHERE p.user_id = u.id)) ORDER BY u.display_name, u.id",
                    ).use { statement ->
                        statement.setBoolean(1, activeOnly)
                        statement.setBoolean(2, unassignedPositionsOnly)
                        statement.executeQuery().use { results -> buildList { while (results.next()) add(results.getLong("id")) } }
                    }
            MemberListResponse(ids.map { readMemberDetail(connection, it, now).member })
        }

    suspend fun memberDetail(
        actorId: Long,
        targetId: Long,
    ): MemberDetail =
        read { connection ->
            authorization.requireCurrent(connection, actorId, forUpdate = false)
            readMemberDetail(connection, targetId, clock.instant())
        }

    suspend fun setOrganizationRole(
        actorId: Long,
        targetId: Long,
        role: OrganizationRole?,
    ): MemberDetail =
        mutate(actorId, targetId) { connection, actor, _ ->
            if (!AuthorizationPolicy.canManageOrganizationRoles(actor)) throw accessDenied()
            if (role != null && role != OrganizationRole.TEAM_LEADER) {
                val occupied =
                    connection
                        .prepareStatement(
                            "SELECT user_id FROM organization_role_assignments WHERE role = ? AND user_id <> ? FOR UPDATE",
                        ).use { statement ->
                            statement.setString(1, role.name)
                            statement.setLong(2, targetId)
                            statement.executeQuery().use { it.next() }
                        }
                if (occupied) throw memberConflict("이 조직 역할은 이미 다른 구성원에게 부여되어 있습니다.")
            }
            connection.prepareStatement("DELETE FROM organization_role_assignments WHERE user_id = ?").use { statement ->
                statement.setLong(1, targetId)
                statement.executeUpdate()
            }
            if (role !=
                null
            ) {
                connection.prepareStatement("INSERT INTO organization_role_assignments (user_id, role) VALUES (?, ?)").use { statement ->
                    statement.setLong(1, targetId)
                    statement.setString(2, role.name)
                    statement.executeUpdate()
                }
            }
        }

    suspend fun setPositions(
        actorId: Long,
        targetId: Long,
        positions: Set<TechnicalPosition>,
    ): MemberDetail =
        mutate(actorId, targetId) { connection, actor, target ->
            val changed = (target.positions - positions) + (positions - target.positions)
            val managed = AuthorizationPolicy.managedTechnicalPositions(actor)
            if (managed.isEmpty() || !managed.containsAll(changed)) throw accessDenied()
            connection.prepareStatement("DELETE FROM user_positions WHERE user_id = ?").use { statement ->
                statement.setLong(1, targetId)
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO user_positions (user_id, position) VALUES (?, ?)").use { statement ->
                positions.forEach { position ->
                    statement.setLong(1, targetId)
                    statement.setString(2, position.name)
                    statement.addBatch()
                }
                if (positions.isNotEmpty()) statement.executeBatch()
            }
        }

    suspend fun setPocs(
        actorId: Long,
        targetId: Long,
        products: Set<ProductId>,
    ): PocsUpdateResponse =
        withContext(ioDispatcher) {
            dataSource.memberTransaction {
                lockMemberManagement()
                val actor = authorization.requireCurrent(this, actorId)
                val target = requireActiveTarget(this, targetId)
                if (!AuthorizationPolicy.canManagePocs(actor)) throw accessDenied()
                val removed = target.pocs - products
                val now = clock.instant()
                val warnings = incompleteTaskCounts(this, targetId).filter { it.productId in removed }
                removed.forEach { product ->
                    prepareStatement(
                        """
                        INSERT INTO task_write_grants
                          (user_id, task_id, product_id, github_user_id, closed_occurrence_ids, unassignment_occurrence_ids, lifecycle_version, granted_at)
                        SELECT ?, t.id, t.product_id, a.github_user_id,
                          COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY()),
                          COALESCE((SELECT JSON_ARRAYAGG(e.occurrence_id) FROM task_assignment_events e
                            WHERE e.task_id = t.id AND e.github_user_id = a.github_user_id AND e.assigned = FALSE), JSON_ARRAY()),
                          t.lifecycle_version, ? FROM task_projections t
                        JOIN task_assignees a ON a.task_id = t.id AND a.user_id = ?
                        JOIN user_github_links l ON l.user_id = a.user_id AND l.github_user_id = a.github_user_id AND l.organization_membership = 'MEMBER'
                        WHERE t.product_id = ? AND t.is_open = TRUE AND t.is_completed = FALSE
                        ON DUPLICATE KEY UPDATE granted_at = granted_at
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, targetId)
                        statement.setMemberInstant(2, now)
                        statement.setLong(3, targetId)
                        statement.setString(4, product.name)
                        statement.executeUpdate()
                    }
                    prepareStatement(
                        """
                        INSERT INTO review_task_write_grants
                          (user_id, task_id, product_id, github_user_id, request_occurrence_id, closed_occurrence_ids, lifecycle_version, granted_at)
                        SELECT ?, t.id, t.product_id, a.github_user_id, a.request_occurrence_id,
                          COALESCE(JSON_EXTRACT(t.snapshot_json, '$.closedOccurrenceIds'), JSON_ARRAY()), t.lifecycle_version, ?
                        FROM task_projections t
                        JOIN review_assignments a ON a.task_id = t.id AND a.user_id = ? AND a.active = TRUE
                        JOIN user_github_links l ON l.user_id = a.user_id AND l.github_user_id = a.github_user_id AND l.organization_membership = 'MEMBER'
                        WHERE t.product_id = ? AND t.kind = 'PULL_REQUEST' AND t.is_open = TRUE AND t.is_completed = FALSE
                        ON DUPLICATE KEY UPDATE granted_at = granted_at
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, targetId)
                        statement.setMemberInstant(2, now)
                        statement.setLong(3, targetId)
                        statement.setString(4, product.name)
                        statement.executeUpdate()
                    }
                }
                prepareStatement("DELETE FROM user_pocs WHERE user_id = ?").use { statement ->
                    statement.setLong(1, targetId)
                    statement.executeUpdate()
                }
                products.forEach { product ->
                    prepareStatement("INSERT INTO user_pocs (user_id, product_id) VALUES (?, ?)").use { statement ->
                        statement.setLong(1, targetId)
                        statement.setString(2, product.name)
                        statement.executeUpdate()
                    }
                    prepareStatement("DELETE FROM task_write_grants WHERE user_id = ? AND product_id = ?").use { statement ->
                        statement.setLong(1, targetId)
                        statement.setString(2, product.name)
                        statement.executeUpdate()
                    }
                    prepareStatement("DELETE FROM review_task_write_grants WHERE user_id = ? AND product_id = ?").use { statement ->
                        statement.setLong(1, targetId)
                        statement.setString(2, product.name)
                        statement.executeUpdate()
                    }
                }
                MemberContributionHistory.capture(this, targetId, now)
                PocsUpdateResponse(readMemberDetail(this, targetId, now), warnings)
            }
        }

    suspend fun setExplicitAdmin(
        actorId: Long,
        targetId: Long,
        enabled: Boolean,
    ): MemberDetail =
        mutate(actorId, targetId) { connection, actor, _ ->
            if (!AuthorizationPolicy.canManageExplicitAdmins(actor)) throw accessDenied()
            val sql =
                if (enabled) {
                    "INSERT INTO explicit_admins (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id = user_id"
                } else {
                    "DELETE FROM explicit_admins WHERE user_id = ?"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, targetId)
                statement.executeUpdate()
            }
        }

    private suspend fun mutate(
        actorId: Long,
        targetId: Long,
        change: (Connection, AuthorizationSnapshot, AuthorizationSnapshot) -> Unit,
    ): MemberDetail =
        withContext(ioDispatcher) {
            dataSource.memberTransaction {
                lockMemberManagement()
                val actor = authorization.requireCurrent(this, actorId)
                val target = requireActiveTarget(this, targetId)
                change(this, actor, target)
                MemberContributionHistory.capture(this, targetId, clock.instant())
                readMemberDetail(this, targetId, clock.instant())
            }
        }

    private suspend fun <T> read(block: (Connection) -> T): T =
        withContext(ioDispatcher) {
            dataSource.inTransaction(isolationLevel = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) { block(this) }
        }
}

internal fun requireActiveTarget(
    connection: Connection,
    targetId: Long,
): AuthorizationSnapshot {
    val target = findAuthorizationSnapshot(connection, targetId, forUpdate = true) ?: throw memberNotFound()
    if (!target.isActive) throw memberConflict("비활성 구성원의 현재 권한이나 휴가를 변경할 수 없습니다.")
    return target
}

internal fun readMemberDetail(
    connection: Connection,
    userId: Long,
    now: Instant,
): MemberDetail {
    val current = findAuthorizationSnapshot(connection, userId) ?: throw memberNotFound()
    val profile =
        connection
            .prepareStatement(
                "SELECT u.*, g.github_user_id, g.github_login, g.organization_membership FROM users u LEFT JOIN user_github_links g ON g.user_id = u.id WHERE u.id = ?",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { results ->
                    if (!results.next()) throw memberNotFound()
                    val githubId = results.memberNullableLong("github_user_id")
                    val status =
                        when {
                            githubId == null -> MemberGitHubStatus.UNLINKED
                            results.getString("organization_membership") == "MEMBER" -> MemberGitHubStatus.MEMBER
                            results.getString("organization_membership") == "NOT_MEMBER" -> MemberGitHubStatus.NOT_MEMBER
                            else -> MemberGitHubStatus.UNKNOWN
                        }
                    MemberSummary(
                        id = userId,
                        displayName = results.getString("display_name"),
                        isActive = current.isActive,
                        enrollmentStatus = current.enrollmentStatus,
                        organizationRole = current.organizationRole,
                        positions = current.positions,
                        pocs = current.pocs,
                        isExplicitSystemAdministrator = current.isExplicitSystemAdministrator,
                        githubLogin = results.getString("github_login"),
                        githubStatus = status,
                        availability = currentAvailability(connection, userId, now),
                    ) to
                        MemberProfile(
                            email = results.getString("email"),
                            grade = results.memberNullableInt("school_grade"),
                            classNumber = results.memberNullableInt("school_class_number"),
                            number = results.memberNullableInt("school_number"),
                            studentNumber = results.memberNullableInt("student_number"),
                            major = results.getString("major"),
                            specialty = results.getString("specialty"),
                            majorClubId = results.memberNullableLong("major_club_id"),
                            majorClubName = results.getString("major_club_name"),
                            dataGsmAccountStatus = results.getString("datagsm_account_status"),
                            dataGsmStudentRole = results.getString("datagsm_student_role"),
                            githubUserId = githubId,
                        )
                }
            }
    return MemberDetail(profile.first, profile.second, incompleteTaskCounts(connection, userId))
}

private fun currentAvailability(
    connection: Connection,
    userId: Long,
    now: Instant,
): AvailabilitySummary =
    connection
        .prepareStatement(
            "SELECT type, ends_at FROM unavailability_periods WHERE user_id = ? AND starts_at <= ? AND ends_at > ? ORDER BY (type = 'WORK_UNAVAILABLE') DESC, ends_at DESC LIMIT 1",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setMemberInstant(2, now)
            statement.setMemberInstant(3, now)
            statement.executeQuery().use { results ->
                if (results.next()) {
                    AvailabilitySummary(
                        UnavailabilityType.valueOf(results.getString("type")),
                        results.memberInstant("ends_at").toEpochMilli(),
                    )
                } else {
                    AvailabilitySummary()
                }
            }
        }

internal fun incompleteTaskCounts(
    connection: Connection,
    userId: Long,
): List<ProductTaskCount> =
    connection
        .prepareStatement(
            """
            SELECT product_id, COUNT(*) AS task_count FROM (
              SELECT DISTINCT t.product_id, t.id, 'ASSIGNEE' AS assignment_kind
              FROM task_projections t JOIN task_assignees a ON a.task_id = t.id
              WHERE a.user_id = ? AND t.is_open = TRUE AND t.is_completed = FALSE
              UNION ALL
              SELECT t.product_id, t.id, 'REVIEW' AS assignment_kind
              FROM task_projections t JOIN review_assignments a ON a.task_id = t.id
              WHERE a.user_id = ? AND a.active = TRUE AND t.kind = 'PULL_REQUEST' AND t.is_open = TRUE AND t.is_completed = FALSE
            ) assignments GROUP BY product_id ORDER BY product_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, userId)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            ProductTaskCount(ProductId.valueOf(results.getString("product_id")), results.getInt("task_count")),
                        )
                    }
                }
            }
        }
