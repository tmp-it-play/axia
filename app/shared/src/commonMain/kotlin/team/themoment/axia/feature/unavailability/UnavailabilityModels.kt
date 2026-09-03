package team.themoment.axia.feature.unavailability

import team.themoment.axia.members.CurrentUserResponse
import team.themoment.axia.members.MemberSummary
import team.themoment.axia.platform.AxiaTime
import team.themoment.axia.unavailability.UnavailabilityCreateRequest
import team.themoment.axia.unavailability.UnavailabilityPeriod
import team.themoment.axia.unavailability.UnavailabilityType
import team.themoment.axia.unavailability.UnavailabilityUpdateRequest
import kotlin.time.Instant

data class UnavailabilityEditorDraft(
    val periodId: Long? = null,
    val userId: Long,
    val type: UnavailabilityType = UnavailabilityType.VACATION,
    val startsAt: String,
    val endsAt: String,
    val reason: String = "",
    val expectedVersion: Long? = null,
    val originalStartsAtEpochMillis: Long? = null,
    val originalEndsAtEpochMillis: Long? = null,
) {
    val startsAtEpochMillis: Long?
        get() = originalStartsAtEpochMillis?.takeIf { AxiaTime.label(it) == startsAt } ?: AxiaTime.parseInput(startsAt)
    val endsAtEpochMillis: Long?
        get() = originalEndsAtEpochMillis?.takeIf { AxiaTime.label(it) == endsAt } ?: AxiaTime.parseInput(endsAt)
    val startError: String?
        get() =
            when {
                startsAtEpochMillis == null -> "시작 시각을 YYYY-MM-DD HH:mm 형식으로 입력해 주세요."
                startsAtEpochMillis!! < MIN_PERIOD_EPOCH_MILLIS -> "지원하는 날짜 범위를 벗어났어요."
                else -> null
            }
    val endError: String?
        get() =
            when {
                endsAtEpochMillis == null -> "종료 시각을 YYYY-MM-DD HH:mm 형식으로 입력해 주세요."
                endsAtEpochMillis!! > MAX_PERIOD_EPOCH_MILLIS -> "지원하는 날짜 범위를 벗어났어요."
                startsAtEpochMillis != null && startsAtEpochMillis!! >= endsAtEpochMillis!! -> "종료 시각은 시작 시각보다 늦어야 해요."
                else -> null
            }
    val reasonError: String?
        get() =
            when {
                reason.isBlank() -> "공개할 사유를 입력해 주세요."
                reason.trim().length > 2000 -> "사유는 2,000자까지 입력할 수 있어요."
                reason.contains('\u0000') -> "사유에 지원하지 않는 문자가 포함되어 있어요."
                else -> null
            }
    val isValid: Boolean
        get() =
            userId > 0 && startError == null && endError == null && reasonError == null &&
                (periodId == null || (periodId > 0 && expectedVersion != null && expectedVersion > 0))

    fun createRequest(currentUserId: Long): UnavailabilityCreateRequest {
        check(isValid && periodId == null) { "A valid new period is required" }
        return UnavailabilityCreateRequest(
            type = type,
            startsAtEpochMillis = requireNotNull(startsAtEpochMillis),
            endsAtEpochMillis = requireNotNull(endsAtEpochMillis),
            reason = reason.trim(),
            userId = userId.takeUnless { it == currentUserId },
        )
    }

    fun updateRequest(): UnavailabilityUpdateRequest {
        check(isValid && periodId != null) { "A valid existing period is required" }
        return UnavailabilityUpdateRequest(
            type = type,
            startsAtEpochMillis = requireNotNull(startsAtEpochMillis),
            endsAtEpochMillis = requireNotNull(endsAtEpochMillis),
            reason = reason.trim(),
            expectedVersion = requireNotNull(expectedVersion),
        )
    }

    override fun toString(): String =
        "UnavailabilityEditorDraft(periodId=$periodId, userId=$userId, type=$type, startsAt=$startsAt, endsAt=$endsAt, reason=[REDACTED])"

    companion object {
        fun new(
            userId: Long,
            startsAtEpochMillis: Long = AxiaTime.nowEpochMillis(),
        ): UnavailabilityEditorDraft =
            UnavailabilityEditorDraft(
                userId = userId,
                startsAt = AxiaTime.label(startsAtEpochMillis),
                endsAt = AxiaTime.label(startsAtEpochMillis + 3_600_000),
            )

        fun from(period: UnavailabilityPeriod): UnavailabilityEditorDraft =
            UnavailabilityEditorDraft(
                periodId = period.id,
                userId = period.userId,
                type = period.type,
                startsAt = AxiaTime.label(period.startsAtEpochMillis),
                endsAt = AxiaTime.label(period.endsAtEpochMillis),
                reason = period.reason,
                expectedVersion = period.version,
                originalStartsAtEpochMillis = period.startsAtEpochMillis,
                originalEndsAtEpochMillis = period.endsAtEpochMillis,
            )
    }
}

data class UnavailabilityUiState(
    val currentUser: CurrentUserResponse? = null,
    val members: List<MemberSummary> = emptyList(),
    val periods: List<UnavailabilityPeriod> = emptyList(),
    val selectedUserId: Long? = null,
    val editor: UnavailabilityEditorDraft? = null,
    val deleteConfirmationId: Long? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
)

data class UnavailabilityCallbacks(
    val onRefresh: () -> Unit,
    val onFilterUser: (Long?) -> Unit,
    val onCreate: (Long) -> Unit,
    val onEdit: (UnavailabilityPeriod) -> Unit,
    val onEditorChange: (UnavailabilityEditorDraft) -> Unit,
    val onCancelEdit: () -> Unit,
    val onCreatePeriod: (UnavailabilityCreateRequest) -> Unit,
    val onUpdatePeriod: (Long, UnavailabilityUpdateRequest) -> Unit,
    val onRequestDelete: (Long?) -> Unit,
    val onDeletePeriod: (Long, Long) -> Unit,
)

/** Presentation affordance only; every mutation is independently authorized by the server. */
fun canEditUnavailability(
    currentUser: CurrentUserResponse?,
    target: MemberSummary,
): Boolean {
    if (currentUser == null || !currentUser.member.isActive || !target.isActive) return false
    return target.id == currentUser.member.id || currentUser.capabilities.canAdjustAllUnavailability ||
        target.positions.any { it in currentUser.capabilities.adjustableUnavailabilityPositions }
}

private val MIN_PERIOD_EPOCH_MILLIS = Instant.parse("1000-01-01T00:00:00Z").toEpochMilliseconds()
private val MAX_PERIOD_EPOCH_MILLIS = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilliseconds()
