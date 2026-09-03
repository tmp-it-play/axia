package team.themoment.axia.analytics

import kotlinx.serialization.Serializable
import team.themoment.axia.domain.ProductId
import team.themoment.axia.members.AnalyticsScope

@Serializable
enum class AnalyticsRange { LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, CUSTOM, ALL }

@Serializable
enum class AnalyticsBucket { DAY, WEEK, MONTH }

@Serializable
enum class CompletionKind { ISSUE_CLOSE, PR_MERGE }

@Serializable
data class CompletionBucket(
    val startsOn: String,
    val issueCloses: Long,
    val pullRequestMerges: Long,
    val totalEvents: Long,
)

@Serializable
data class CompletionAnalyticsResponse(
    val scope: AnalyticsScope,
    val fromEpochMillis: Long?,
    val untilEpochMillis: Long,
    val bucket: AnalyticsBucket,
    val totalEvents: Long,
    val issueCloses: Long,
    val pullRequestMerges: Long,
    val series: List<CompletionBucket>,
)

@Serializable
data class MemberCompletionMetrics(
    val userId: Long,
    val displayName: String,
    val isActive: Boolean,
    val issueCloses: Long,
    val pullRequestMerges: Long,
    val totalCredits: Long,
    val averageProcessingSeconds: Double?,
)

@Serializable
data class MemberAnalyticsResponse(
    val scope: AnalyticsScope,
    val fromEpochMillis: Long?,
    val untilEpochMillis: Long,
    val members: List<MemberCompletionMetrics>,
)

@Serializable
data class ProductOperationsMetrics(
    val productId: ProductId,
    val openTasks: Long,
    val overdueTasks: Long,
    val unassignedTasks: Long,
)

@Serializable
data class OperationsAnalyticsResponse(
    val scope: AnalyticsScope,
    val products: List<ProductOperationsMetrics>,
)

@Serializable
data class CompletionHistoryEntry(
    val id: Long,
    val taskId: Long,
    val title: String,
    val productId: ProductId,
    val kind: CompletionKind,
    val occurredAtEpochMillis: Long,
    val processingSeconds: Long,
    val creditedUserIds: List<Long>,
)

@Serializable
data class CompletionHistoryResponse(
    val completions: List<CompletionHistoryEntry>,
    val nextCursor: Long?,
)
