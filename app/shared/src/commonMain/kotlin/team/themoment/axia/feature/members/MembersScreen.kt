package team.themoment.axia.feature.members

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaBadge
import team.themoment.axia.designsystem.component.AxiaBadgeStyle
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.component.AxiaSelect
import team.themoment.axia.designsystem.component.AxiaSelectOption
import team.themoment.axia.designsystem.component.AxiaTextField
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.domain.OrganizationRole
import team.themoment.axia.domain.ProductId
import team.themoment.axia.domain.TechnicalPosition
import team.themoment.axia.members.ExplicitAdminUpdateRequest
import team.themoment.axia.members.MemberDetail
import team.themoment.axia.members.MemberEnrollmentStatus
import team.themoment.axia.members.MemberGitHubStatus
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.members.OrganizationRoleUpdateRequest
import team.themoment.axia.members.PocsUpdateRequest
import team.themoment.axia.members.PositionsUpdateRequest
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.unavailability.UnavailabilityType

@Composable
fun MembersScreen(
    state: MembersUiState,
    callbacks: MemberCallbacks,
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val members =
        state.members.filter {
            query.isBlank() || it.displayName.contains(query, true) ||
                it.githubLogin?.contains(query, true) == true
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3), verticalAlignment = Alignment.CenterVertically) {
            AxiaTextField(
                value = state.searchQuery,
                onValueChange = callbacks.onSearchChange,
                label = "이름 또는 GitHub 계정 검색",
                modifier = Modifier.weight(1f),
                placeholder = "구성원 찾기",
            )
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !state.loading && !state.saving, style = AxiaButtonStyle.Secondary)
        }
        state.error?.let { AxiaBanner("변경 내용을 확인해 주세요", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        state.successMessage?.let { AxiaBanner("저장했어요", description = it, style = AxiaBannerStyle.Success, announceChanges = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
            AxiaPanel(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                    Text(
                        "구성원 ${members.size}명",
                        style = AxiaTheme.typography.sectionTitle,
                        color = AxiaTheme.semanticColors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (state.loading && members.isEmpty()) {
                        Text("구성원 정보를 불러오고 있어요", color = AxiaTheme.semanticColors.textSecondary)
                    } else if (members.isEmpty()) {
                        AxiaEmptyState("구성원을 찾지 못했어요", "다른 이름 또는 GitHub 계정으로 검색해 주세요.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 640.dp)) {
                            items(members, key = MemberSummary::id) { member ->
                                MemberDirectoryRow(
                                    member = member,
                                    selected = member.id == state.selectedMemberId,
                                    onSelect = { callbacks.onSelectMember(member.id) },
                                )
                                AxiaDivider()
                            }
                        }
                    }
                }
            }
            if (state.selectedMemberId != null) {
                val detail = state.detail?.takeIf { it.member.id == state.selectedMemberId }
                AxiaPanel(modifier = Modifier.width(AxiaTheme.dimensions.detailPanelWidth)) {
                    Column(
                        Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                detail?.member?.displayName ?: "구성원 상세",
                                style = AxiaTheme.typography.sectionTitle,
                                color = AxiaTheme.semanticColors.textPrimary,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            AxiaButton("닫기", callbacks.onCloseDetail, style = AxiaButtonStyle.Tertiary)
                        }
                        if (detail == null) {
                            Text("상세 정보를 불러오고 있어요", color = AxiaTheme.semanticColors.textSecondary)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                                MemberDetailTab.entries.forEach { tab ->
                                    AxiaButton(
                                        if (tab == MemberDetailTab.Profile) "프로필" else "역할과 권한",
                                        onClick = { callbacks.onDetailTabChange(tab) },
                                        style = if (state.detailTab == tab) AxiaButtonStyle.Primary else AxiaButtonStyle.Secondary,
                                    )
                                }
                            }
                            when (state.detailTab) {
                                MemberDetailTab.Profile -> MemberProfileContent(detail, callbacks)
                                MemberDetailTab.Permissions -> MemberPermissionsContent(state, detail, callbacks)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberDirectoryRow(
    member: MemberSummary,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) AxiaTheme.semanticColors.surfaceSubtle else AxiaTheme.semanticColors.surface)
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics { this.selected = selected }
            .defaultMinSize(minHeight = 64.dp)
            .padding(vertical = AxiaTheme.spacing.space3, horizontal = AxiaTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        ) {
            Text(member.displayName, style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
            AxiaBadge(member.enrollmentStatus.label, AxiaBadgeStyle.Metadata)
            member.organizationRole?.let { AxiaBadge(it.displayName, AxiaBadgeStyle.Info) }
            if (!member.isActive) AxiaBadge("비활성", AxiaBadgeStyle.Danger)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        ) {
            if (member.positions.isEmpty()) AxiaBadge("포지션 미지정", AxiaBadgeStyle.Warning)
            member.positions.sortedBy { it.ordinal }.forEach { AxiaBadge(it.displayName, it.badgeStyle) }
            member.pocs.sortedBy { it.ordinal }.forEach { AxiaBadge(it.displayName, AxiaBadgeStyle.Product) }
            member.availability.type?.let {
                AxiaBadge(if (it == UnavailabilityType.VACATION) "휴가 중" else "작업 불가", AxiaBadgeStyle.Warning)
            }
        }
    }
}

@Composable
private fun MemberProfileContent(
    detail: MemberDetail,
    callbacks: MemberCallbacks,
) {
    val member = detail.member
    val profile = detail.profile
    ProfileValue("학적", member.enrollmentStatus.label)
    ProfileValue("이메일", profile.email)
    ProfileValue("학년 · 반 · 번호", listOf(profile.grade, profile.classNumber, profile.number).joinToString(" / ") { it?.toString() ?: "—" })
    ProfileValue("학번", profile.studentNumber?.toString() ?: "미등록")
    ProfileValue("전공", profile.major ?: "미등록")
    ProfileValue("특기", profile.specialty ?: "미등록")
    ProfileValue("전공동아리", profile.majorClubName ?: "미등록")
    AxiaDivider()
    ProfileValue("GitHub", member.githubLogin ?: "연결되지 않음")
    ProfileValue("조직 상태", member.githubStatus.label)
    ProfileValue("현재 가용 상태", availabilityLabel(member))
    AxiaButton("휴가·작업 불가 일정 보기", onClick = { callbacks.onOpenUnavailability(member.id) }, style = AxiaButtonStyle.Secondary)
    AxiaDivider()
    Text("업무와 기록", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
    detail.incompleteAssignedTasks.forEach {
        ProfileValue(it.productId.displayName, "미완료 담당 태스크 ${it.count}건")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
        AxiaButton("현재 할당 태스크", onClick = { callbacks.onBrowseMemberTasks(member.id) }, style = AxiaButtonStyle.Secondary)
        AxiaButton("완료 이력", onClick = { callbacks.onBrowseMemberHistory(member.id) }, style = AxiaButtonStyle.Secondary)
    }
}

@Composable
private fun MemberPermissionsContent(
    state: MembersUiState,
    detail: MemberDetail,
    callbacks: MemberCallbacks,
) {
    val member = detail.member
    val capabilities = state.currentUser?.capabilities
    val editor = state.editor?.takeIf { it.memberId == member.id } ?: MemberEditorDraft.from(detail)
    val editable = !state.saving && member.isActive && state.currentUser?.member?.isActive == true
    ProfileValue("조직 역할", member.organizationRole?.displayName ?: "없음")
    if (capabilities?.canManageOrganizationRoles == true) {
        RoleSelector(editor.organizationRole, enabled = editable) { callbacks.onEditorChange(editor.copy(organizationRole = it)) }
        AxiaButton(
            "조직 역할 저장",
            onClick = { callbacks.onSaveRole(member.id, OrganizationRoleUpdateRequest(editor.organizationRole)) },
            enabled = editable && editor.organizationRole != member.organizationRole,
        )
    }
    AxiaDivider()
    Text("기술 포지션", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
    TechnicalPosition.entries.forEach { position ->
        val canManage = capabilities?.managedTechnicalPositions?.contains(position) == true
        MemberToggle(
            label = position.displayName,
            checked = position in editor.positions,
            enabled = editable && canManage,
            onChange = { selected ->
                callbacks.onEditorChange(
                    editor.copy(positions = if (selected) editor.positions + position else editor.positions - position),
                )
            },
        )
    }
    if (capabilities?.managedTechnicalPositions?.isNotEmpty() == true) {
        AxiaButton(
            "기술 포지션 저장",
            onClick = { callbacks.onSavePositions(member.id, PositionsUpdateRequest(editor.positions)) },
            enabled = editable && editor.positions != member.positions,
        )
    }
    AxiaDivider()
    Text("제품 POC", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
    ProductId.entries.forEach { product ->
        MemberToggle(
            label = product.displayName,
            checked = product in editor.pocs,
            enabled = editable && capabilities?.canManagePocs == true,
            onChange = { selected ->
                callbacks.onEditorChange(
                    editor.copy(
                        pocs =
                            if (selected) {
                                editor.pocs + product
                            } else {
                                editor.pocs -
                                    product
                            },
                    ),
                )
            },
        )
    }
    if (capabilities?.canManagePocs == true) {
        val removedWithTasks = detail.incompleteAssignedTasks.filter { it.count > 0 && it.productId in member.pocs - editor.pocs }
        if (removedWithTasks.isNotEmpty()) {
            AxiaBanner(
                "미완료 담당 태스크가 남아 있어요",
                description =
                    removedWithTasks.joinToString { "${it.productId.displayName} ${it.count}건" } +
                        ". POC 제거는 가능하며 현재 담당 중인 미완료 태스크의 접근은 완료 또는 담당 해제까지 유지됩니다.",
                style = AxiaBannerStyle.Warning,
            )
        }
        AxiaButton(
            "POC 저장",
            onClick = { callbacks.onSavePocs(member.id, PocsUpdateRequest(editor.pocs)) },
            enabled = editable && editor.pocs != member.pocs,
        )
    }
    AxiaDivider()
    Text("시스템 관리자", style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
    ProfileValue("Team Leader 파생 권한", if (member.organizationRole == OrganizationRole.TEAM_LEADER) "역할 유지 중 자동 적용" else "해당 없음")
    MemberToggle(
        label = "명시적 시스템 관리자",
        checked = editor.explicitAdmin,
        enabled = editable && capabilities?.canManageExplicitAdmins == true,
        onChange = { callbacks.onEditorChange(editor.copy(explicitAdmin = it)) },
    )
    Text(
        "명시적 관리자는 조직 역할과 별도입니다. 이 자격만으로 분석 그래프나 다른 구성원의 휴가를 관리할 수는 없습니다.",
        style = AxiaTheme.typography.secondary,
        color = AxiaTheme.semanticColors.textSecondary,
    )
    if (capabilities?.canManageExplicitAdmins == true) {
        AxiaButton(
            if (editor.explicitAdmin) "명시적 관리자 부여" else "명시적 관리자 회수",
            onClick = { callbacks.onSaveExplicitAdmin(member.id, ExplicitAdminUpdateRequest(editor.explicitAdmin)) },
            enabled = editable && editor.explicitAdmin != member.isExplicitSystemAdministrator,
            style = if (editor.explicitAdmin) AxiaButtonStyle.Primary else AxiaButtonStyle.Danger,
        )
    }
}

@Composable
private fun RoleSelector(
    role: OrganizationRole?,
    enabled: Boolean,
    onSelect: (OrganizationRole?) -> Unit,
) {
    AxiaSelect(
        label = "조직 역할 변경",
        value = role,
        options =
            listOf(AxiaSelectOption<OrganizationRole?>(null, "역할 없음")) +
                OrganizationRole.entries.map {
                    AxiaSelectOption<OrganizationRole?>(it, it.displayName)
                },
        onChange = onSelect,
        enabled = enabled,
    )
}

@Composable
private fun MemberToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AxiaTheme.dimensions.minimumInteractiveSize)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = AxiaTheme.semanticColors.action,
                    checkmarkColor = AxiaTheme.semanticColors.onAction,
                ),
        )
        Text(label, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary)
    }
}

@Composable
private fun ProfileValue(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
        Text(label, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
        Text(value, style = AxiaTheme.typography.body, color = AxiaTheme.semanticColors.textPrimary, overflow = TextOverflow.Ellipsis)
    }
}

private val MemberEnrollmentStatus.label: String
    get() =
        when (this) {
            MemberEnrollmentStatus.CURRENT_STUDENT -> "재학생"
            MemberEnrollmentStatus.GRADUATE -> "졸업생"
            MemberEnrollmentStatus.WITHDRAWN -> "자퇴"
            MemberEnrollmentStatus.UNKNOWN -> "학적 확인 필요"
        }

private val MemberGitHubStatus.label: String
    get() =
        when (this) {
            MemberGitHubStatus.MEMBER -> "themoment-team 정식 멤버"
            MemberGitHubStatus.NOT_MEMBER -> "조직 미가입"
            MemberGitHubStatus.UNLINKED -> "미연결"
            MemberGitHubStatus.UNKNOWN -> "확인 필요"
        }

private val TechnicalPosition.badgeStyle: AxiaBadgeStyle
    get() =
        when (this) {
            TechnicalPosition.SERVER -> AxiaBadgeStyle.Server
            TechnicalPosition.FRONTEND -> AxiaBadgeStyle.Frontend
            TechnicalPosition.CLOUD -> AxiaBadgeStyle.Cloud
            TechnicalPosition.DESIGN -> AxiaBadgeStyle.Design
        }

private fun availabilityLabel(member: MemberSummary): String {
    val type = member.availability.type ?: return "작업 가능"
    val label = if (type == UnavailabilityType.VACATION) "휴가" else "작업 불가"
    return member.availability.untilEpochMillis?.let { "$label · ${AxiaTime.label(it)}까지" } ?: label
}
