package team.themoment.axia.members

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.insertId
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.nullableLong
import team.themoment.axia.persistence.query
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ContributionIdentity(
    val userId: Long,
    val githubId: Long,
    val organizationRole: String?,
    val positions: List<String>,
    val pocs: List<String>,
)

/** Minimal functional history required to credit the identity and permissions at completion time. */
object MemberContributionHistory {
    fun capture(
        connection: Connection,
        userId: Long,
        now: Instant,
    ) = with(connection) {
        check(!autoCommit)
        lockMemberManagement()
        val current =
            query("SELECT * FROM member_contribution_history WHERE user_id=? AND valid_until IS NULL FOR UPDATE", userId) {
                HistoryRow(
                    it.getLong("id"),
                    requireNotNull(it.instant("valid_from")),
                    ContributionIdentity(
                        userId,
                        it.getLong("github_user_id"),
                        it.getString("organization_role"),
                        Json.decodeFromString(it.getString("positions_json")),
                        Json.decodeFromString(it.getString("pocs_json")),
                    ),
                )
            }.singleOrNull()
        val githubId =
            query(
                "SELECT u.is_active,g.github_user_id FROM users u LEFT JOIN user_github_links g ON g.user_id=u.id WHERE u.id=? FOR UPDATE",
                userId,
            ) {
                it.nullableLong("github_user_id").takeIf { _ -> it.getBoolean("is_active") }
            }.singleOrNull()
        // Organization suspension preserves the identity and its contribution interval.
        val next =
            githubId?.let { github ->
                ContributionIdentity(
                    userId,
                    github,
                    query("SELECT role FROM organization_role_assignments WHERE user_id=?", userId) { it.getString(1) }.singleOrNull(),
                    query("SELECT position FROM user_positions WHERE user_id=? ORDER BY position", userId) { it.getString(1) },
                    query("SELECT product_id FROM user_pocs WHERE user_id=? ORDER BY product_id", userId) { it.getString(1) },
                )
            }
        if (current?.identity == next) return@with
        val at = now.truncatedTo(ChronoUnit.MICROS)
        if (current != null) {
            check(!at.isBefore(current.from)) { "Contribution state time moved backwards" }
            if (at == current.from) {
                // Several same-transaction provisioning hooks share one instant; retain only final state.
                if (next == null) {
                    execute("DELETE FROM member_contribution_history WHERE id=?", current.id)
                } else {
                    execute(
                        "UPDATE member_contribution_history SET github_user_id=?,organization_role=?,positions_json=?,pocs_json=? WHERE id=?",
                        next.githubId,
                        next.organizationRole,
                        Json.encodeToString(next.positions),
                        Json.encodeToString(next.pocs),
                        current.id,
                    )
                }
                return@with
            }
            execute("UPDATE member_contribution_history SET valid_until=? WHERE id=?", at, current.id)
        }
        if (next != null) {
            insertId(
                "INSERT INTO member_contribution_history(user_id,github_user_id,organization_role,positions_json,pocs_json,valid_from) VALUES(?,?,?,?,?,?)",
                next.userId,
                next.githubId,
                next.organizationRole,
                Json.encodeToString(next.positions),
                Json.encodeToString(next.pocs),
                at,
            )
        }
    }

    /** No current-state fallback: unknown-at-completion accounts must never receive retroactive credit. */
    fun at(
        connection: Connection,
        githubId: Long,
        occurredAt: Instant,
    ): ContributionIdentity? =
        connection
            .query(
                "SELECT * FROM member_contribution_history WHERE github_user_id=? AND valid_from<=? AND (valid_until IS NULL OR valid_until>?)",
                githubId,
                occurredAt,
                occurredAt,
            ) {
                ContributionIdentity(
                    it.getLong("user_id"),
                    githubId,
                    it.getString("organization_role"),
                    Json.decodeFromString(it.getString("positions_json")),
                    Json.decodeFromString(it.getString("pocs_json")),
                )
            }.also { check(it.size <= 1) { "Overlapping contribution identity intervals" } }
            .singleOrNull()

    private data class HistoryRow(
        val id: Long,
        val from: Instant,
        val identity: ContributionIdentity,
    )
}
