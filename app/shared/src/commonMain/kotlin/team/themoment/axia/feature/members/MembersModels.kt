package team.themoment.axia.feature.members

import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.ExplicitAdminUpdateRequest
import team.themoment.axia.members.MemberDetail
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.members.OrganizationRoleUpdateRequest
import team.themoment.axia.members.PocsUpdateRequest
import team.themoment.axia.members.PositionsUpdateRequest

enum class MemberDetailTab { Profile, Permissions }

data class MemberEditorDraft(
    val memberId: Long,
    val organizationRole: OrganizationRole?,
    val positions: Set<TechnicalPosition>,
    val pocs: Set<ProductId>,
    val explicitAdmin: Boolean,
) {
    companion object {
        fun from(detail: MemberDetail): MemberEditorDraft =
            MemberEditorDraft(
                memberId = detail.member.id,
                organizationRole = detail.member.organizationRole,
                positions = detail.member.positions,
                pocs = detail.member.pocs,
                explicitAdmin = detail.member.isExplicitSystemAdministrator,
            )
    }
}

data class MembersUiState(
    val currentUser: CurrentUserResponse? = null,
    val members: List<MemberSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedMemberId: Long? = null,
    val detail: MemberDetail? = null,
    val editor: MemberEditorDraft? = null,
    val detailTab: MemberDetailTab = MemberDetailTab.Profile,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
)

data class MemberCallbacks(
    val onRefresh: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onSelectMember: (Long) -> Unit,
    val onCloseDetail: () -> Unit,
    val onDetailTabChange: (MemberDetailTab) -> Unit,
    val onEditorChange: (MemberEditorDraft) -> Unit,
    val onSaveRole: (Long, OrganizationRoleUpdateRequest) -> Unit,
    val onSavePositions: (Long, PositionsUpdateRequest) -> Unit,
    val onSavePocs: (Long, PocsUpdateRequest) -> Unit,
    val onSaveExplicitAdmin: (Long, ExplicitAdminUpdateRequest) -> Unit,
    val onOpenUnavailability: (Long) -> Unit,
    val onBrowseMemberTasks: (Long) -> Unit,
    val onBrowseMemberHistory: (Long) -> Unit,
)
