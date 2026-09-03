package team.themoment.axia.integration.datagsm.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.themoment.axia.auth.store.GitHubLinkApproval
import team.themoment.axia.auth.store.JdbcAuthStore
import team.themoment.axia.auth.store.ProjectedGitHubConflictResult
import team.themoment.axia.auth.store.ProjectedGitHubOutcome
import team.themoment.axia.auth.store.ProjectedGitHubResult
import team.themoment.axia.auth.store.UserProfileSnapshot
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.integration.github.GitHubIdentityClient
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.webhooks.WebhookReceiptClaim
import team.themoment.axia.webhooks.WebhookReceiptStore
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * Atomic receipt-backed lifecycle projection followed by durable, CAS-guarded
 * GitHub verification. No external HTTP request runs inside a JDBC transaction.
 *
 * The upstream dispatch timestamp cannot establish source order. Continuity checks
 * reject snapshots that conflict with the local projection, but cannot establish
 * a total order for cyclic provider changes without an upstream revision contract.
 */
class JdbcDataGsmEventProcessor(
    private val dataSource: DataSource,
    private val authStore: JdbcAuthStore,
    private val receipts: WebhookReceiptStore,
    private val github: GitHubIdentityClient,
    private val requiredMajorClubId: Long,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(requiredMajorClubId > 0) { "The required DataGSM club ID must be positive" }
    }

    suspend fun process(
        claim: WebhookReceiptClaim,
        event: DataGsmEvent,
    ) {
        val targets = prepare(claim, event)
        targets.forEach { target -> verify(claim, target) }
        withContext(ioDispatcher) {
            dataSource.inTransaction {
                requireLease(claim)
                val pending =
                    prepareStatement(COUNT_PENDING).use { statement ->
                        statement.setString(1, claim.deliveryId)
                        statement.executeQuery().use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
                check(pending == 0) { "DataGSM event verification is incomplete" }
                check(receipts.complete(this, claim, clock.instant())) { "DataGSM receipt lease was lost" }
            }
        }
    }

    private suspend fun prepare(
        claim: WebhookReceiptClaim,
        event: DataGsmEvent,
    ): List<VerificationTarget> =
        withContext(ioDispatcher) {
            dataSource.inTransaction {
                requireLease(claim)
                val alreadyProjected =
                    prepareStatement(FIND_PROJECTION).use { statement ->
                        statement.setString(1, claim.deliveryId)
                        statement.executeQuery().use(ResultSet::next)
                    }
                if (!alreadyProjected) {
                    // Match the authorization/authentication lock order before locking individual users.
                    lockMemberManagement()
                    val targets =
                        when (event) {
                            is DataGsmEvent.StudentsUpdated -> projectStudents(event)
                            is DataGsmEvent.ClubsUpdated -> projectClubs(event)
                            is DataGsmEvent.Unsupported -> emptyList()
                        }
                    targets.forEach { target -> insertVerification(claim, target) }
                    prepareStatement(INSERT_PROJECTION).use { statement ->
                        statement.setString(1, claim.deliveryId)
                        statement.setInstant(2, clock.instant())
                        statement.executeUpdate()
                    }
                }
                pendingVerifications(claim.deliveryId)
            }
        }

    private fun Connection.projectStudents(event: DataGsmEvent.StudentsUpdated): List<VerificationTarget> =
        event.changes.sortedBy { it.studentId }.mapNotNull { change ->
            val current = findProfile(change.studentId) ?: return@mapNotNull null
            val currentSnapshot = current.eventSnapshot()
            if (currentSnapshot != change.before && currentSnapshot != change.after) {
                throw DataGsmEventException(DataGsmEventFailure.AmbiguousSourceOrder)
            }
            val newProfile =
                change.after?.toProfile(current.dataGsmAccountStatus)
                    ?: current.copy(majorClubId = null, majorClubName = null, dataGsmGitHubLogin = null)
            projectUser(newProfile, newProfile.majorClubId == requiredMajorClubId)
        }

    private fun Connection.projectClubs(event: DataGsmEvent.ClubsUpdated): List<VerificationTarget> {
        val change = event.changes.singleOrNull { it.clubId == requiredMajorClubId } ?: return emptyList()
        listOfNotNull(change.before, change.after).forEach { snapshot ->
            if (snapshot.type != MAJOR_CLUB_TYPE || snapshot.status !in SUPPORTED_CLUB_STATUSES) {
                throw DataGsmEventException(DataGsmEventFailure.InvalidProviderContract)
            }
        }
        val previousMembers = change.before.activeMembers()
        val currentMembers = change.after.activeMembers()
        val identities = previousMembers + currentMembers
        val profiles = affectedClubProfiles(identities)
        return profiles.mapNotNull { current ->
            val wasMember = current.dataGsmUserId in previousMembers
            val isMember = current.dataGsmUserId in currentMembers
            val locallyMember = current.majorClubId == requiredMajorClubId
            if (wasMember == isMember && locallyMember != isMember) {
                throw DataGsmEventException(DataGsmEventFailure.AmbiguousSourceOrder)
            }
            val next =
                if (isMember) {
                    current.copy(majorClubId = requiredMajorClubId, majorClubName = requireNotNull(change.after).name)
                } else if (locallyMember || wasMember) {
                    current.copy(majorClubId = null, majorClubName = null)
                } else {
                    return@mapNotNull null
                }
            projectUser(next, isMember)
        }
    }

    private fun Connection.projectUser(
        profile: UserProfileSnapshot,
        isTheMomentMember: Boolean,
    ): VerificationTarget? {
        val projected =
            authStore.projectDataGsmProfile(this, profile, isTheMomentMember, clock.instant())
                ?: return null
        if (!isTheMomentMember) return null
        return VerificationTarget(
            userId = projected.userId,
            studentId = profile.dataGsmUserId,
            profileVersion = projected.profileVersion,
            githubLogin = profile.dataGsmGitHubLogin?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private suspend fun verify(
        claim: WebhookReceiptClaim,
        target: VerificationTarget,
    ) {
        val login = target.githubLogin
        if (login == null) {
            finishVerification(claim, target, ProjectedGitHubOutcome.IdMissing)
            return
        }
        val identity = receipts.withLeaseRenewal(claim, clock) { github.findUser(login) }
        if (identity == null) {
            finishVerification(claim, target, ProjectedGitHubOutcome.AccountNotFound)
            return
        }
        val link = GitHubLinkApproval(identity.numericId, identity.login)
        val conflict =
            withContext(ioDispatcher) {
                dataSource.inTransaction {
                    requireLease(claim)
                    val result =
                        authStore.checkProjectedGitHubConflict(this, target.userId, target.profileVersion, link, clock.instant())
                    when (result) {
                        ProjectedGitHubConflictResult.NoConflict -> Unit
                        ProjectedGitHubConflictResult.Conflict -> finishJob(claim, target, stale = false)
                        ProjectedGitHubConflictResult.StaleProfile -> finishJob(claim, target, stale = true)
                    }
                    result
                }
            }
        if (conflict != ProjectedGitHubConflictResult.NoConflict) return
        val outcome =
            if (receipts.withLeaseRenewal(claim, clock) { github.hasActiveOrganizationMembership(identity) }) {
                ProjectedGitHubOutcome.Verified(link)
            } else {
                ProjectedGitHubOutcome.NotOrganizationMember(link)
            }
        finishVerification(claim, target, outcome)
    }

    private suspend fun finishVerification(
        claim: WebhookReceiptClaim,
        target: VerificationTarget,
        outcome: ProjectedGitHubOutcome,
    ): Unit =
        withContext(ioDispatcher) {
            dataSource.inTransaction {
                requireLease(claim)
                val result =
                    authStore.completeProjectedGitHubVerification(this, target.userId, target.profileVersion, outcome, clock.instant())
                finishJob(claim, target, stale = result == ProjectedGitHubResult.StaleProfile)
            }
        }

    private fun Connection.requireLease(claim: WebhookReceiptClaim) {
        if (!receipts.lockAndRenew(this, claim, clock.instant())) throw DataGsmEventException(DataGsmEventFailure.ProcessingFailed)
    }

    private fun Connection.finishJob(
        claim: WebhookReceiptClaim,
        target: VerificationTarget,
        stale: Boolean,
    ) {
        prepareStatement(FINISH_VERIFICATION).use { statement ->
            statement.setString(1, if (stale) "STALE" else "SUCCEEDED")
            statement.setInstant(2, clock.instant())
            statement.setString(3, claim.deliveryId)
            statement.setLong(4, target.userId)
            check(statement.executeUpdate() == 1) { "DataGSM verification job disappeared" }
        }
    }

    private fun Connection.insertVerification(
        claim: WebhookReceiptClaim,
        target: VerificationTarget,
    ) {
        prepareStatement(INSERT_VERIFICATION).use { statement ->
            statement.setString(1, claim.deliveryId)
            statement.setLong(2, target.userId)
            statement.setLong(3, target.studentId)
            statement.setLong(4, target.profileVersion)
            statement.setString(5, target.githubLogin)
            statement.setInstant(6, clock.instant())
            statement.executeUpdate()
        }
    }

    private fun Connection.pendingVerifications(eventId: String): List<VerificationTarget> =
        prepareStatement(PENDING_VERIFICATIONS).use { statement ->
            statement.setString(1, eventId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            VerificationTarget(
                                userId = result.getLong("user_id"),
                                studentId = result.getLong("datagsm_student_id"),
                                profileVersion = result.getLong("profile_version"),
                                githubLogin = result.getString("github_login"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.findProfile(studentId: Long): UserProfileSnapshot? =
        prepareStatement("$PROFILE_SELECT WHERE datagsm_user_id = ? FOR UPDATE").use { statement ->
            statement.setLong(1, studentId)
            statement.executeQuery().use { result -> if (result.next()) result.profile() else null }
        }

    private fun Connection.affectedClubProfiles(studentIds: Set<Long>): List<UserProfileSnapshot> {
        val sortedIds = studentIds.sorted()
        val filter = if (sortedIds.isEmpty()) "" else " OR datagsm_user_id IN (${sortedIds.joinToString(",") { "?" }})"
        return prepareStatement("$PROFILE_SELECT WHERE major_club_id = ?$filter ORDER BY datagsm_user_id FOR UPDATE").use { statement ->
            statement.setLong(1, requiredMajorClubId)
            sortedIds.forEachIndexed { index, id -> statement.setLong(index + 2, id) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.profile()) } }
        }
    }

    private fun ResultSet.profile(): UserProfileSnapshot =
        UserProfileSnapshot(
            dataGsmUserId = getLong("datagsm_user_id"),
            displayName = getString("display_name"),
            email = getString("email"),
            dataGsmAccountStatus = getString("datagsm_account_status"),
            dataGsmStudentRole = getString("datagsm_student_role"),
            majorClubId = getLong("major_club_id").takeUnless { wasNull() },
            majorClubName = getString("major_club_name"),
            dataGsmGitHubLogin = getString("datagsm_github_login"),
            sex = getString("sex"),
            grade = getInt("school_grade").takeUnless { wasNull() },
            classNumber = getInt("school_class_number").takeUnless { wasNull() },
            number = getInt("school_number").takeUnless { wasNull() },
            studentNumber = getInt("student_number").takeUnless { wasNull() },
            major = getString("major"),
            specialty = getString("specialty"),
        )

    private fun UserProfileSnapshot.eventSnapshot(): DataGsmStudentEventSnapshot =
        DataGsmStudentEventSnapshot(
            studentId = dataGsmUserId,
            name = displayName,
            email = email,
            sex = sex.orEmpty(),
            grade = grade,
            classNumber = classNumber,
            number = number,
            studentNumber = studentNumber,
            major = major,
            specialty = specialty,
            role = dataGsmStudentRole,
            majorClubId = majorClubId,
            majorClubName = majorClubName,
            githubId = dataGsmGitHubLogin,
        )

    private fun DataGsmStudentEventSnapshot.toProfile(accountStatus: String): UserProfileSnapshot =
        UserProfileSnapshot(
            dataGsmUserId = studentId,
            displayName = name,
            email = email,
            dataGsmAccountStatus = accountStatus,
            dataGsmStudentRole = role,
            majorClubId = majorClubId,
            majorClubName = majorClubName,
            dataGsmGitHubLogin = githubId?.trim()?.takeIf(String::isNotEmpty),
            sex = sex,
            grade = grade,
            classNumber = classNumber,
            number = number,
            studentNumber = studentNumber,
            major = major,
            specialty = specialty,
        )

    private fun DataGsmClubEventSnapshot?.activeMembers(): Set<Long> = if (this?.status == "ACTIVE") memberStudentIds else emptySet()

    private fun PreparedStatement.setInstant(
        index: Int,
        instant: Instant,
    ) = setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

    private data class VerificationTarget(
        val userId: Long,
        val studentId: Long,
        val profileVersion: Long,
        val githubLogin: String?,
    )

    private companion object {
        const val MAJOR_CLUB_TYPE = "MAJOR_CLUB"
        val SUPPORTED_CLUB_STATUSES = setOf("ACTIVE", "ABOLISHED")
        const val PROFILE_SELECT = """
            SELECT datagsm_user_id, display_name, email, datagsm_account_status, datagsm_student_role,
                major_club_id, major_club_name, datagsm_github_login, sex, school_grade,
                school_class_number, school_number, student_number, major, specialty FROM users
        """
        const val FIND_PROJECTION = "SELECT event_id FROM datagsm_event_projections WHERE event_id = ?"
        const val INSERT_PROJECTION = "INSERT INTO datagsm_event_projections (event_id, projected_at) VALUES (?, ?)"
        const val INSERT_VERIFICATION = """
            INSERT INTO datagsm_event_verifications (
                event_id, user_id, datagsm_student_id, profile_version, github_login, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
        """
        const val PENDING_VERIFICATIONS = """
            SELECT user_id, datagsm_student_id, profile_version, github_login
            FROM datagsm_event_verifications WHERE event_id = ? AND status = 'PENDING' ORDER BY user_id
        """
        const val FINISH_VERIFICATION = """
            UPDATE datagsm_event_verifications SET status = ?, completed_at = ?
            WHERE event_id = ? AND user_id = ? AND status = 'PENDING'
        """
        const val COUNT_PENDING = "SELECT COUNT(*) FROM datagsm_event_verifications WHERE event_id = ? AND status = 'PENDING'"
    }
}
