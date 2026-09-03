package team.themoment.axia.feature.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.themoment.axia.analytics.AnalyticsBucket
import team.themoment.axia.analytics.AnalyticsRange
import team.themoment.axia.analytics.CompletionAnalyticsResponse
import team.themoment.axia.analytics.CompletionBucket
import team.themoment.axia.analytics.MemberAnalyticsResponse
import team.themoment.axia.analytics.OperationsAnalyticsResponse
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
import team.themoment.axia.members.AnalyticsScopeKind
import team.themoment.axia.platform.AxiaTime

@Composable
fun AnalyticsScreen(
    state: AnalyticsUiState,
    callbacks: AnalyticsCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = state.currentUser?.capabilities?.analyticsScope
    if (scope == null || scope.kind == AnalyticsScopeKind.NONE ||
        state.currentUser.member.isActive
            .not()
    ) {
        AxiaEmptyState(
            "분석 범위가 없는 계정입니다",
            "분석은 현재 조직 역할의 범위로 제공됩니다. 명시적 시스템 관리자 자격만으로 분석 권한이 생기지는 않습니다. 완료 이력은 구성원 화면에서 볼 수 있습니다.",
            modifier,
        )
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaBadge(scope.displayLabel(), AxiaBadgeStyle.Info)
            AxiaSelect(
                label = "조회 기간",
                value = state.filter.range,
                options = AnalyticsRange.entries.map { AxiaSelectOption(it, it.label) },
                onChange = { callbacks.onFilterChange(state.filter.copy(range = it)) },
                enabled = !state.loading,
            )
            AxiaButton("기간 적용", { callbacks.onApplyFilter(state.filter) }, enabled = state.filter.isValid && !state.loading)
            AxiaButton("새로고침", callbacks.onRefresh, enabled = !state.loading, style = AxiaButtonStyle.Secondary)
        }
        if (state.filter.range == AnalyticsRange.CUSTOM) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
            ) {
                AxiaTextField(
                    state.filter.customFrom,
                    { callbacks.onFilterChange(state.filter.copy(customFrom = it)) },
                    "시작 시각 (한국 시간)",
                    placeholder = "YYYY-MM-DD HH:mm",
                    enabled = !state.loading,
                )
                AxiaTextField(
                    state.filter.customUntil,
                    { callbacks.onFilterChange(state.filter.copy(customUntil = it)) },
                    "종료 시각 (미포함)",
                    placeholder = "YYYY-MM-DD HH:mm",
                    enabled = !state.loading,
                )
            }
            state.filter.validationError()?.let { AxiaBanner("기간을 확인해 주세요", description = it, style = AxiaBannerStyle.Warning) }
        }
        state.error?.let { AxiaBanner("분석을 불러오지 못했어요", description = it, style = AxiaBannerStyle.Danger, announceChanges = true) }
        if (state.loading) {
            Text(
                "현재 권한 범위의 분석을 불러오고 있어요",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
        }
        val completions = state.completions?.takeIf { it.scope == scope }
        val members = state.members?.takeIf { it.scope == scope }
        val operations = state.operations?.takeIf { it.scope == scope }
        completions?.let { CompletionTimeSeries(it) }
        members?.let { MemberCredits(it, callbacks.onOpenMember) }
        operations?.let { OperationsSummary(it) }
        if (!state.loading && completions == null && members == null && operations == null) {
            AxiaEmptyState("분석을 조회해 주세요", "기간을 적용하면 현재 역할 범위의 완료·구성원·운영 현황을 볼 수 있습니다.")
        }
    }
}

@Composable
private fun CompletionTimeSeries(response: CompletionAnalyticsResponse) {
    AxiaPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
            SectionHeading("완료 흐름")
            Text(
                "${response.fromEpochMillis?.let(AxiaTime::label) ?: "전체 기록"}부터 ${AxiaTime.label(response.untilEpochMillis)} 전까지",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
                MetricValue("전체 완료 이벤트", response.totalEvents.toString())
                MetricValue("이슈 닫힘", response.issueCloses.toString())
                MetricValue("PR 병합", response.pullRequestMerges.toString())
            }
            AxiaDivider()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ChartLegend("이슈 닫힘", AxiaTheme.semanticColors.action)
                ChartLegend("PR 병합", AxiaTheme.semanticColors.success.content)
                Text(
                    "${response.bucket.label} 단위 · 좌우 스크롤",
                    style = AxiaTheme.typography.metadata,
                    color = AxiaTheme.semanticColors.textSecondary,
                )
            }
            if (response.series.isEmpty()) {
                AxiaEmptyState("완료 기록이 없어요", "이 기간에 닫힌 이슈 또는 병합된 PR이 기록되면 표시됩니다.")
            } else {
                val maximum = response.series.maxOf { it.totalEvents }.coerceAtLeast(1)
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
                ) {
                    items(response.series, key = CompletionBucket::startsOn) { BucketBar(it, maximum) }
                }
            }
            Text(
                "팀 합계는 완료 이벤트를 한 번씩 집계합니다. 담당자가 여러 명이어도 이 그래프의 완료 건수는 늘어나지 않습니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
        }
    }
}

@Composable
private fun BucketBar(
    bucket: CompletionBucket,
    maximum: Long,
) {
    val issueColor = AxiaTheme.semanticColors.action
    val prColor = AxiaTheme.semanticColors.success.content
    val baselineColor = AxiaTheme.semanticColors.border
    Column(
        modifier =
            Modifier.width(82.dp).clearAndSetSemantics {
                contentDescription =
                    "${bucket.startsOn}, 이슈 닫힘 ${bucket.issueCloses}건, PR 병합 ${bucket.pullRequestMerges}건, 전체 ${bucket.totalEvents}건"
            },
        verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(bucket.totalEvents.toString(), style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val issueHeight = size.height * bucket.issueCloses.coerceAtLeast(0).toFloat() / maximum
            val prHeight = size.height * bucket.pullRequestMerges.coerceAtLeast(0).toFloat() / maximum
            val left = size.width * 0.24f
            val width = size.width * 0.52f
            drawRect(issueColor, Offset(left, size.height - issueHeight), Size(width, issueHeight))
            drawRect(prColor, Offset(left, size.height - issueHeight - prHeight), Size(width, prHeight))
            drawLine(baselineColor, Offset(0f, size.height), Offset(size.width, size.height))
        }
        Text(bucket.startsOn, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
        Text("이슈 ${bucket.issueCloses}", style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textPrimary)
        Text("PR ${bucket.pullRequestMerges}", style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textPrimary)
    }
}

@Composable
private fun MemberCredits(
    response: MemberAnalyticsResponse,
    onOpenMember: (Long) -> Unit,
) {
    AxiaPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
            SectionHeading("구성원별 완료 기여")
            Text(
                "공동 담당자는 각각 완료 기여를 받습니다. 구성원 기여 합계는 팀 완료 이벤트 합계와 다를 수 있습니다.",
                style = AxiaTheme.typography.secondary,
                color = AxiaTheme.semanticColors.textSecondary,
            )
            if (response.members.isEmpty()) AxiaEmptyState("범위에 해당하는 구성원이 없어요", "현재 역할과 구성원 포지션·제품 POC를 확인해 주세요.")
            response.members.forEach { member ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
                    verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    AxiaButton(member.displayName, { onOpenMember(member.userId) }, style = AxiaButtonStyle.Tertiary)
                    if (!member.isActive) AxiaBadge("비활성", AxiaBadgeStyle.Metadata)
                    MetricValue("완료 기여", "${member.totalCredits}건")
                    MetricValue("이슈 닫힘", "${member.issueCloses}건")
                    MetricValue("PR 병합", "${member.pullRequestMerges}건")
                    MetricValue("평균 처리시간", processingDurationLabel(member.averageProcessingSeconds))
                }
                AxiaDivider()
            }
        }
    }
}

@Composable
private fun OperationsSummary(response: OperationsAnalyticsResponse) {
    AxiaPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4)) {
            SectionHeading("현재 운영 현황")
            Text("조회 기간과 무관한 현재 미완료 태스크입니다.", style = AxiaTheme.typography.secondary, color = AxiaTheme.semanticColors.textSecondary)
            if (response.products.isEmpty()) AxiaEmptyState("표시할 운영 현황이 없어요", "현재 역할 범위의 제품을 확인해 주세요.")
            response.products.forEach { product ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5),
                    verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    AxiaBadge(product.productId.displayName, AxiaBadgeStyle.Product)
                    MetricValue("미완료", "${product.openTasks}건")
                    MetricValue("지연", "${product.overdueTasks}건")
                    MetricValue("담당자 미지정", "${product.unassignedTasks}건")
                }
                AxiaDivider()
            }
        }
    }
}

@Composable
private fun SectionHeading(label: String) {
    Text(
        label,
        style = AxiaTheme.typography.sectionTitle,
        color = AxiaTheme.semanticColors.textPrimary,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
}

@Composable
private fun MetricValue(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
        Text(label, style = AxiaTheme.typography.metadata, color = AxiaTheme.semanticColors.textSecondary)
        Text(value, style = AxiaTheme.typography.bodyEmphasis, color = AxiaTheme.semanticColors.textPrimary)
    }
}

@Composable
private fun ChartLegend(
    label: String,
    color: Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color))
        Text(label, style = AxiaTheme.typography.secondary, color = AxiaTheme.semanticColors.textPrimary)
    }
}

private val AnalyticsRange.label: String get() =
    when (this) {
        AnalyticsRange.LAST_7_DAYS -> "최근 7일"
        AnalyticsRange.LAST_30_DAYS -> "최근 30일"
        AnalyticsRange.LAST_90_DAYS -> "최근 90일"
        AnalyticsRange.CUSTOM -> "사용자 지정"
        AnalyticsRange.ALL -> "전체"
    }

private val AnalyticsBucket.label: String get() =
    when (this) {
        AnalyticsBucket.DAY -> "일"
        AnalyticsBucket.WEEK -> "주"
        AnalyticsBucket.MONTH -> "월"
    }
