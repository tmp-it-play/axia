package team.themoment.axia.unavailability

import kotlinx.serialization.Serializable

const val UNAVAILABILITY_REASON_WARNING: String =
    "모든 팀원이 볼 수 있으므로 민감한 건강·개인정보를 입력하지 마세요."

@Serializable
enum class UnavailabilityType {
    VACATION,
    WORK_UNAVAILABLE,
}

@Serializable
data class AvailabilitySummary(
    val type: UnavailabilityType? = null,
    val untilEpochMillis: Long? = null,
)

@Serializable
data class UnavailabilityPeriod(
    val id: Long,
    val userId: Long,
    val type: UnavailabilityType,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val reason: String,
    val version: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class UnavailabilityCreateRequest(
    val type: UnavailabilityType,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val reason: String,
    /** Omit for the caller's own schedule. Other users require the Head/TL/C-Leader policy. */
    val userId: Long? = null,
)

@Serializable
data class UnavailabilityUpdateRequest(
    val type: UnavailabilityType,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long,
    val reason: String,
    val expectedVersion: Long,
)

@Serializable
data class UnavailabilityListResponse(
    val periods: List<UnavailabilityPeriod>,
)
