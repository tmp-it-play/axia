package team.themoment.axia.navigation

import androidx.compose.runtime.Immutable

@Immutable
enum class AxiaDestination(
    val label: String,
    val description: String,
) {
    Dashboard(
        label = "내 대시보드",
        description = "내 작업과 지금 확인할 상태를 한곳에서 봅니다.",
    ),
    Tasks(
        label = "태스크",
        description = "전체 제품의 Issue, PR, 리뷰 요청과 Draft를 봅니다.",
    ),
    Products(
        label = "제품",
        description = "제품별 작업과 GitHub 연결 상태를 봅니다.",
    ),
    Members(
        label = "멤버",
        description = "구성원의 역할, 포지션과 현재 가용 상태를 봅니다.",
    ),
    Calendar(
        label = "캘린더",
        description = "마감과 휴가·작업 불가 일정을 봅니다.",
    ),
    Analytics(
        label = "분석",
        description = "권한 범위 안의 완료와 운영 지표를 봅니다.",
    ),
    Notifications(
        label = "알림",
        description = "배정, 마감과 동기화 알림을 확인합니다.",
    ),
    Management(
        label = "관리",
        description = "권한이 있는 조직과 연동 설정을 관리합니다.",
    ),
    Settings(
        label = "설정",
        description = "이 기기에서 사용할 화면 설정을 고릅니다.",
    ),
}

@Immutable
data class AxiaAccessFlags(
    val canViewAnalytics: Boolean = false,
    val canManageOrganization: Boolean = false,
    val canManageProducts: Boolean = false,
    val canManageIntegrations: Boolean = false,
) {
    val hasManagementAccess: Boolean
        get() = canManageOrganization || canManageProducts || canManageIntegrations
}

fun destinationsFor(accessFlags: AxiaAccessFlags): List<AxiaDestination> =
    buildList {
        add(AxiaDestination.Dashboard)
        add(AxiaDestination.Tasks)
        add(AxiaDestination.Products)
        add(AxiaDestination.Members)
        add(AxiaDestination.Calendar)
        if (accessFlags.canViewAnalytics) {
            add(AxiaDestination.Analytics)
        }
        add(AxiaDestination.Notifications)
        if (accessFlags.hasManagementAccess) {
            add(AxiaDestination.Management)
        }
        add(AxiaDestination.Settings)
    }
