package team.themoment.axia.members

import kotlinx.serialization.Serializable
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.unavailability.AvailabilitySummary

@Serializable
enum class MemberEnrollmentStatus {
    CURRENT_STUDENT,
    GRADUATE,
    WITHDRAWN,
    UNKNOWN,
}

@Serializable
enum class MemberGitHubStatus {
    MEMBER,
    NOT_MEMBER,
    UNLINKED,
    UNKNOWN,
}

@Serializable
data class MemberSummary(
    val id: Long,
    val displayName: String,
    val isActive: Boolean,
    val enrollmentStatus: MemberEnrollmentStatus,
    val organizationRole: OrganizationRole?,
    val positions: Set<TechnicalPosition>,
    val pocs: Set<ProductId>,
    val isExplicitSystemAdministrator: Boolean,
    val githubLogin: String?,
    val githubStatus: MemberGitHubStatus,
    val availability: AvailabilitySummary,
)

@Serializable
data class MemberProfile(
    val email: String,
    val grade: Int?,
    val classNumber: Int?,
    val number: Int?,
    val studentNumber: Int?,
    val major: String?,
    val specialty: String?,
    val majorClubId: Long?,
    val majorClubName: String?,
    val dataGsmAccountStatus: String,
    val dataGsmStudentRole: String,
    val githubUserId: Long?,
)

@Serializable
data class ProductTaskCount(
    val productId: ProductId,
    val count: Int,
)

@Serializable
data class MemberDetail(
    val member: MemberSummary,
    val profile: MemberProfile,
    /** Lets the editor warn before removing a POC; removal itself remains allowed. */
    val incompleteAssignedTasks: List<ProductTaskCount>,
)

@Serializable
data class MemberListResponse(
    val members: List<MemberSummary>,
)

@Serializable
data class OrganizationRoleUpdateRequest(
    val role: OrganizationRole?,
)

@Serializable
data class PositionsUpdateRequest(
    val positions: Set<TechnicalPosition>,
)

@Serializable
data class PocsUpdateRequest(
    val products: Set<ProductId>,
)

@Serializable
data class ExplicitAdminUpdateRequest(
    val enabled: Boolean,
)

@Serializable
data class PocsUpdateResponse(
    val member: MemberDetail,
    val retainedTaskWarnings: List<ProductTaskCount>,
)

@Serializable
enum class AnalyticsScopeKind {
    NONE,
    ORGANIZATION,
    PRODUCT,
    TECHNICAL_POSITION,
}

@Serializable
data class AnalyticsScope(
    val kind: AnalyticsScopeKind,
    val productId: ProductId? = null,
    val position: TechnicalPosition? = null,
)

@Serializable
data class MemberCapabilities(
    val effectiveSystemAdministrator: Boolean,
    val canManageOrganizationRoles: Boolean,
    val canManageExplicitAdmins: Boolean,
    val managedTechnicalPositions: Set<TechnicalPosition>,
    val canManagePocs: Boolean,
    val writableProducts: Set<ProductId>,
    val managedIntegrationProducts: Set<ProductId>,
    val canAdjustAllUnavailability: Boolean,
    val adjustableUnavailabilityPositions: Set<TechnicalPosition>,
    val analyticsScope: AnalyticsScope,
)

@Serializable
data class CurrentUserResponse(
    val member: MemberSummary,
    val capabilities: MemberCapabilities,
)
