package team.themoment.axia.feature.analytics

import team.themoment.axia.analytics.AnalyticsRange
import team.themoment.axia.analytics.CompletionAnalyticsResponse
import team.themoment.axia.analytics.CompletionHistoryEntry
import team.themoment.axia.analytics.MemberAnalyticsResponse
import team.themoment.axia.analytics.OperationsAnalyticsResponse
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.AnalyticsScope
import team.themoment.axia.members.AnalyticsScopeKind
import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import kotlin.time.Instant

data class AnalyticsFilterDraft(
    val range: AnalyticsRange = AnalyticsRange.LAST_30_DAYS,
    val customFrom: String = AxiaTime.label(AxiaTime.nowEpochMillis() - 30L * 24 * 60 * 60 * 1000),
    val customUntil: String = AxiaTime.label(AxiaTime.nowEpochMillis()),
) {
    val isValid: Boolean get() = validationError() == null

    fun validationError(nowEpochMillis: Long = AxiaTime.nowEpochMillis()): String? {
        if (range != AnalyticsRange.CUSTOM) return null
        val start = AxiaTime.parseInput(customFrom) ?: return "시작 시각을 YYYY-MM-DD HH:mm 형식으로 입력해 주세요."
        val end = AxiaTime.parseInput(customUntil) ?: return "종료 시각을 YYYY-MM-DD HH:mm 형식으로 입력해 주세요."
        return when {
            start < MIN_ANALYTICS_EPOCH_MILLIS || end > MAX_ANALYTICS_EPOCH_MILLIS -> "지원하는 날짜 범위를 벗어났어요."
            start >= end -> "종료 시각은 시작 시각보다 늦어야 해요."
            end > nowEpochMillis -> "종료 시각은 현재 시각을 넘을 수 없어요."
            else -> null
        }
    }

    /** API query parameters without the leading question mark. */
    fun query(): String {
        check(isValid) { "A valid analytics range is required" }
        return buildString {
            append("range=${range.name}")
            if (range == AnalyticsRange.CUSTOM) {
                append("&fromEpochMillis=${requireNotNull(AxiaTime.parseInput(customFrom))}")
                append("&untilEpochMillis=${requireNotNull(AxiaTime.parseInput(customUntil))}")
            }
        }
    }
}

data class AnalyticsUiState(
    val currentUser: CurrentUserResponse? = null,
    val filter: AnalyticsFilterDraft = AnalyticsFilterDraft(),
    val completions: CompletionAnalyticsResponse? = null,
    val members: MemberAnalyticsResponse? = null,
    val operations: OperationsAnalyticsResponse? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class AnalyticsCallbacks(
    val onFilterChange: (AnalyticsFilterDraft) -> Unit,
    val onApplyFilter: (AnalyticsFilterDraft) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenMember: (Long) -> Unit,
)

data class CompletionHistoryUiState(
    val completions: List<CompletionHistoryEntry> = emptyList(),
    val members: List<MemberSummary> = emptyList(),
    val selectedUserId: Long? = null,
    val selectedProductId: ProductId? = null,
    val nextCursor: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class CompletionHistoryCallbacks(
    val onFilterUser: (Long?) -> Unit,
    val onFilterProduct: (ProductId?) -> Unit,
    val onOpenTask: (Long) -> Unit,
    val onOpenMember: (Long) -> Unit,
    val onLoadMore: () -> Unit,
    val onRefresh: () -> Unit,
)

fun AnalyticsScope.displayLabel(): String =
    when (kind) {
        AnalyticsScopeKind.ORGANIZATION -> "조직 전체"
        AnalyticsScopeKind.PRODUCT -> productId?.displayName ?: "제품 범위"
        AnalyticsScopeKind.TECHNICAL_POSITION -> position?.displayName?.let { "$it 포지션" } ?: "기술 포지션 범위"
        AnalyticsScopeKind.NONE -> "분석 권한 없음"
    }

fun processingDurationLabel(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0) return "기록 없음"
    val whole = seconds.toLong()
    val days = whole / 86_400
    val hours = whole % 86_400 / 3_600
    val minutes = whole % 3_600 / 60
    return when {
        days > 0 -> "${days}일 ${hours}시간"
        hours > 0 -> "${hours}시간 ${minutes}분"
        minutes > 0 -> "${minutes}분"
        else -> "${whole}초"
    }
}

private val MIN_ANALYTICS_EPOCH_MILLIS = Instant.parse("1000-01-01T00:00:00Z").toEpochMilliseconds()
private val MAX_ANALYTICS_EPOCH_MILLIS = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilliseconds()
