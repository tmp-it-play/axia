package team.themoment.axia.authorization

import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.AnalyticsScope
import team.themoment.axia.members.AnalyticsScopeKind
import team.themoment.axia.members.MemberCapabilities
import team.themoment.axia.members.MemberEnrollmentStatus

data class AuthorizationSnapshot(
    val userId: Long,
    val isActive: Boolean,
    val isGitHubOrganizationMember: Boolean,
    val enrollmentStatus: MemberEnrollmentStatus,
    val organizationRole: OrganizationRole?,
    val positions: Set<TechnicalPosition>,
    val pocs: Set<ProductId>,
    val isExplicitSystemAdministrator: Boolean,
) {
    val isEligible: Boolean
        get() = isActive && isGitHubOrganizationMember

    val isCurrentStudent: Boolean
        get() = enrollmentStatus == MemberEnrollmentStatus.CURRENT_STUDENT

    val effectiveSystemAdministrator: Boolean
        get() = isEligible && (isExplicitSystemAdministrator || organizationRole == OrganizationRole.TEAM_LEADER)
}

/** Pure policy only. Callers must load a fresh snapshot from the database for every request. */
object AuthorizationPolicy {
    fun canWriteProduct(
        actor: AuthorizationSnapshot,
        productId: ProductId,
    ): Boolean =
        actor.isEligible &&
            (hasOrganizationWideProductAuthority(actor) || actor.organizationRole?.product == productId || productId in actor.pocs)

    fun canManageIntegrations(
        actor: AuthorizationSnapshot,
        productId: ProductId,
    ): Boolean =
        actor.isEligible &&
            (hasOrganizationWideProductAuthority(actor) || actor.organizationRole?.product == productId)

    fun canManageOrganizationRoles(actor: AuthorizationSnapshot): Boolean =
        actor.isEligible &&
            (actor.effectiveSystemAdministrator || actor.organizationRole == OrganizationRole.C_LEADER)

    fun canManageExplicitAdmins(actor: AuthorizationSnapshot): Boolean = actor.effectiveSystemAdministrator

    fun managedTechnicalPositions(actor: AuthorizationSnapshot): Set<TechnicalPosition> {
        if (!actor.isEligible) return emptySet()
        if (actor.effectiveSystemAdministrator || actor.organizationRole == OrganizationRole.C_LEADER) {
            return TechnicalPosition.entries.toSet()
        }
        return actor.organizationRole?.headPosition?.let(::setOf) ?: emptySet()
    }

    fun canManagePocs(actor: AuthorizationSnapshot): Boolean =
        actor.isEligible &&
            (
                hasOrganizationWideProductAuthority(actor) ||
                    actor.organizationRole?.product != null
            )

    fun canAdjustUnavailability(
        actor: AuthorizationSnapshot,
        target: AuthorizationSnapshot,
    ): Boolean {
        if (!actor.isEligible || !target.isActive) return false
        if (actor.userId == target.userId) return true
        return when (actor.organizationRole) {
            OrganizationRole.TEAM_LEADER, OrganizationRole.C_LEADER -> true
            else -> actor.organizationRole?.headPosition?.let { it in target.positions } ?: false
        }
    }

    fun analyticsScope(actor: AuthorizationSnapshot): AnalyticsScope {
        if (!actor.isEligible) return AnalyticsScope(AnalyticsScopeKind.NONE)
        val role = actor.organizationRole
        return when {
            role == OrganizationRole.TEAM_LEADER || role == OrganizationRole.C_LEADER -> {
                AnalyticsScope(AnalyticsScopeKind.ORGANIZATION)
            }

            role?.headPosition != null -> {
                AnalyticsScope(AnalyticsScopeKind.TECHNICAL_POSITION, position = role.headPosition)
            }

            role?.product != null -> {
                AnalyticsScope(AnalyticsScopeKind.PRODUCT, productId = role.product)
            }

            else -> {
                AnalyticsScope(AnalyticsScopeKind.NONE)
            }
        }
    }

    fun capabilities(actor: AuthorizationSnapshot): MemberCapabilities =
        MemberCapabilities(
            effectiveSystemAdministrator = actor.effectiveSystemAdministrator,
            canManageOrganizationRoles = canManageOrganizationRoles(actor),
            canManageExplicitAdmins = canManageExplicitAdmins(actor),
            managedTechnicalPositions = managedTechnicalPositions(actor),
            canManagePocs = canManagePocs(actor),
            writableProducts = ProductId.entries.filterTo(linkedSetOf()) { canWriteProduct(actor, it) },
            managedIntegrationProducts = ProductId.entries.filterTo(linkedSetOf()) { canManageIntegrations(actor, it) },
            canAdjustAllUnavailability =
                actor.isEligible && actor.organizationRole in setOf(OrganizationRole.TEAM_LEADER, OrganizationRole.C_LEADER),
            adjustableUnavailabilityPositions =
                if (actor.isEligible) actor.organizationRole?.headPosition?.let(::setOf) ?: emptySet() else emptySet(),
            analyticsScope = analyticsScope(actor),
        )

    private fun hasOrganizationWideProductAuthority(actor: AuthorizationSnapshot): Boolean =
        actor.effectiveSystemAdministrator ||
            actor.organizationRole == OrganizationRole.C_LEADER ||
            actor.organizationRole?.headPosition != null
}

fun enrollmentStatusForDataGsmRole(role: String): MemberEnrollmentStatus =
    when (role) {
        "GENERAL_STUDENT", "STUDENT_COUNCIL", "DORMITORY_MANAGER" -> MemberEnrollmentStatus.CURRENT_STUDENT
        "GRADUATE" -> MemberEnrollmentStatus.GRADUATE
        "WITHDRAWN" -> MemberEnrollmentStatus.WITHDRAWN
        else -> MemberEnrollmentStatus.UNKNOWN
    }
