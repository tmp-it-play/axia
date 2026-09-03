package team.themoment.axia.members

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import team.themoment.axia.authorization.enrollmentStatusForDataGsmRole
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import java.sql.Connection
import java.time.Instant

/** Blocking hooks used inside the same transaction as auth or DataGSM lifecycle state changes. */
class JdbcMemberLifecycle {
    fun afterApprovedLogin(
        connection: Connection,
        userId: Long,
        isNewUser: Boolean,
        now: Instant,
    ) {
        connection.lockMemberManagement()
        MemberBootstrap.tryApply(connection, now)
        if (!isNewUser) return
        val processed =
            connection.prepareStatement("SELECT user_id FROM member_onboarding_flags WHERE user_id = ? FOR UPDATE").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { it.next() }
            }
        if (processed) return
        connection
            .prepareStatement(
                "INSERT INTO member_onboarding_flags (user_id, positionless_signup_processed_at) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setMemberInstant(2, now)
                statement.executeUpdate()
            }
        val recipients = currentNotificationActors(connection).filter { it.role == OrganizationRole.TEAM_LEADER || it.explicitAdmin }
        val payload = buildJsonObject { put("userId", userId) }.toString()
        recipients.forEach { recipient ->
            insertFunctionalNotification(
                connection,
                recipient.id,
                "MEMBER_POSITION_UNASSIGNED",
                payload,
                "positionless-signup:$userId",
                now,
            )
        }
    }

    fun removeCurrentAuthorizationForDeparture(
        connection: Connection,
        userId: Long,
        wasActive: Boolean,
        now: Instant,
    ) {
        connection.lockMemberManagement()
        if (wasActive) notifyDepartureAssignments(connection, userId, now)
        listOf(
            "organization_role_assignments",
            "user_positions",
            "user_pocs",
            "explicit_admins",
            "task_write_grants",
            "review_task_write_grants",
        ).forEach { table ->
            connection.prepareStatement("DELETE FROM $table WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun notifyDepartureAssignments(
        connection: Connection,
        userId: Long,
        now: Instant,
    ) {
        val profile =
            connection.prepareStatement("SELECT display_name, profile_version FROM users WHERE id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { results ->
                    check(results.next()) { "Departing user was not found" }
                    results.getString("display_name") to results.getLong("profile_version")
                }
            }
        val positions =
            connection.prepareStatement("SELECT position FROM user_positions WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { results ->
                    buildSet { while (results.next()) add(TechnicalPosition.valueOf(results.getString("position"))) }
                }
            }
        val tasks =
            connection
                .prepareStatement(
                    """
                    SELECT DISTINCT t.id, t.product_id
                    FROM task_projections t
                    WHERE t.is_open = TRUE AND t.is_completed = FALSE
                      AND (EXISTS (SELECT 1 FROM task_assignees a WHERE a.task_id = t.id AND a.user_id = ?)
                        OR (t.kind = 'PULL_REQUEST' AND EXISTS (
                          SELECT 1 FROM review_assignments a WHERE a.task_id = t.id AND a.user_id = ? AND a.active = TRUE
                        )))
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { results ->
                        buildList {
                            while (results.next()) add(results.getLong("id") to ProductId.valueOf(results.getString("product_id")))
                        }
                    }
                }
        val actors = currentNotificationActors(connection).filter { it.id != userId }
        tasks.forEach { (taskId, productId) ->
            val recipients =
                actors.filter { actor ->
                    actor.role?.product == productId ||
                        (
                            actor.currentStudent &&
                                (actor.role == OrganizationRole.TEAM_LEADER || actor.role?.headPosition in positions)
                        )
                }
            val payload =
                buildJsonObject {
                    put("taskId", taskId)
                    put("productId", productId.name)
                    put("departedUserId", userId)
                    put("departedDisplayName", profile.first)
                }.toString()
            recipients.forEach { recipient ->
                insertFunctionalNotification(
                    connection,
                    recipient.id,
                    "TASK_INACTIVE_ASSIGNEE",
                    payload,
                    "member-departure:$userId:${profile.second}:$taskId",
                    now,
                )
            }
        }
    }
}

internal data class NotificationActor(
    val id: Long,
    val role: OrganizationRole?,
    val currentStudent: Boolean,
    val explicitAdmin: Boolean,
)

internal fun currentNotificationActors(connection: Connection): List<NotificationActor> =
    connection
        .prepareStatement(
            """
            SELECT u.id, u.datagsm_student_role, r.role, (a.user_id IS NOT NULL) AS explicit_admin
            FROM users u
            JOIN user_github_links g ON g.user_id = u.id
            LEFT JOIN organization_role_assignments r ON r.user_id = u.id
            LEFT JOIN explicit_admins a ON a.user_id = u.id
            WHERE u.is_active = TRUE AND g.github_user_id IS NOT NULL AND g.organization_membership = 'MEMBER'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            NotificationActor(
                                id = results.getLong("id"),
                                role = results.getString("role")?.let(OrganizationRole::valueOf),
                                currentStudent =
                                    enrollmentStatusForDataGsmRole(results.getString("datagsm_student_role")) ==
                                        MemberEnrollmentStatus.CURRENT_STUDENT,
                                explicitAdmin = results.getBoolean("explicit_admin"),
                            ),
                        )
                    }
                }
            }
        }

internal fun insertFunctionalNotification(
    connection: Connection,
    recipientUserId: Long,
    type: String,
    payload: String,
    dedupeKey: String,
    now: Instant,
) {
    connection
        .prepareStatement(
            """
            INSERT INTO notifications (recipient_user_id, type, payload_json, dedupe_key, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, recipientUserId)
            statement.setString(2, type)
            statement.setString(3, payload)
            statement.setString(4, dedupeKey)
            statement.setMemberInstant(5, now)
            statement.executeUpdate()
        }
}
