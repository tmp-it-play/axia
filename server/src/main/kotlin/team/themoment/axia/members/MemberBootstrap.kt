package team.themoment.axia.members

import team.themoment.axia.authorization.lockMemberManagement
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

enum class MemberBootstrapStatus {
    DISABLED,
    PENDING,
    APPLIED,
    BLOCKED,
}

/** Temporary installation bootstrap. No normal authorization path identifies a user by email/name. */
object MemberBootstrap {
    // TODO(REMOVE_BOOTSTRAP): Remove this one-time installation bootstrap after initial member provisioning.
    fun initializeForEmptyDatabase(
        dataSource: DataSource,
        now: Instant,
    ): MemberBootstrapStatus =
        dataSource.memberTransaction {
            lockMemberManagement()
            existingStatus(this)?.let { return@memberTransaction it }
            val empty =
                prepareStatement("SELECT COUNT(*) FROM users").use { statement ->
                    statement.executeQuery().use { results ->
                        results.next()
                        results.getLong(1) == 0L
                    }
                }
            val status = if (empty) MemberBootstrapStatus.PENDING else MemberBootstrapStatus.DISABLED
            prepareStatement(
                "INSERT INTO member_bootstrap_state (id, status, initialized_at, completed_at) VALUES (1, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setMemberInstant(2, now)
                if (empty) statement.setNull(3, java.sql.Types.TIMESTAMP) else statement.setMemberInstant(3, now)
                statement.executeUpdate()
            }
            status
        }

    internal fun tryApply(
        connection: Connection,
        now: Instant,
    ) {
        if (existingStatus(connection) != MemberBootstrapStatus.PENDING) return
        val administrator = matchingUsers(connection, "email", BOOTSTRAP_ADMIN_EMAIL)
        val firstLeader = matchingUsers(connection, "display_name", BOOTSTRAP_FIRST_LEADER)
        val secondLeader = matchingUsers(connection, "display_name", BOOTSTRAP_SECOND_LEADER)
        val matches = listOf(administrator, firstLeader, secondLeader)
        if (matches.any { it.size > 1 }) {
            finish(connection, MemberBootstrapStatus.BLOCKED, now)
            return
        }
        if (matches.any { it.isEmpty() }) return
        val users = matches.map { it.single() }
        if (users.any { !it.eligible }) return
        val existingPermissions =
            connection
                .prepareStatement(
                    """
                    SELECT (SELECT COUNT(*) FROM organization_role_assignments)
                         + (SELECT COUNT(*) FROM explicit_admins)
                         + (SELECT COUNT(*) FROM user_positions)
                         + (SELECT COUNT(*) FROM user_pocs)
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { results ->
                        results.next()
                        results.getLong(1)
                    }
                }
        if (existingPermissions != 0L) {
            finish(connection, MemberBootstrapStatus.BLOCKED, now)
            return
        }
        connection.prepareStatement("INSERT INTO explicit_admins (user_id) VALUES (?)").use { statement ->
            statement.setLong(1, administrator.single().id)
            statement.executeUpdate()
        }
        connection
            .prepareStatement(
                "INSERT INTO organization_role_assignments (user_id, role) VALUES (?, 'TEAM_LEADER')",
            ).use { statement ->
                listOf(firstLeader.single().id, secondLeader.single().id).forEach { userId ->
                    statement.setLong(1, userId)
                    statement.executeUpdate()
                }
            }
        users.map { it.id }.distinct().forEach { MemberContributionHistory.capture(connection, it, now) }
        finish(connection, MemberBootstrapStatus.APPLIED, now)
    }

    private fun existingStatus(connection: Connection): MemberBootstrapStatus? =
        connection.prepareStatement("SELECT status FROM member_bootstrap_state WHERE id = 1 FOR UPDATE").use { statement ->
            statement.executeQuery().use { results ->
                if (results.next()) MemberBootstrapStatus.valueOf(results.getString("status")) else null
            }
        }

    private fun matchingUsers(
        connection: Connection,
        column: String,
        value: String,
    ): List<BootstrapUser> {
        check(column == "email" || column == "display_name")
        return connection
            .prepareStatement(
                """
                SELECT u.id, (u.is_active AND g.github_user_id IS NOT NULL AND g.organization_membership = 'MEMBER') AS eligible
                FROM users u LEFT JOIN user_github_links g ON g.user_id = u.id
                WHERE BINARY u.$column = BINARY ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) add(BootstrapUser(results.getLong("id"), results.getBoolean("eligible")))
                    }
                }
            }
    }

    private fun finish(
        connection: Connection,
        status: MemberBootstrapStatus,
        now: Instant,
    ) {
        connection
            .prepareStatement(
                "UPDATE member_bootstrap_state SET status = ?, completed_at = GREATEST(?, initialized_at) WHERE id = 1 AND status = 'PENDING'",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setMemberInstant(2, now)
                check(statement.executeUpdate() == 1) { "Locked bootstrap state could not be completed" }
            }
    }

    private data class BootstrapUser(
        val id: Long,
        val eligible: Boolean,
    )

    private const val BOOTSTRAP_ADMIN_EMAIL = "s24058@gsm.hs.kr"
    private const val BOOTSTRAP_FIRST_LEADER = "정연돈"
    private const val BOOTSTRAP_SECOND_LEADER = "정효주"
}
