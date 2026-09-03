package team.themoment.axia.integration.datagsm.events

import java.time.Instant

/** The provider timestamp is a dispatch timestamp, not a database revision. */
data class DataGsmEventEnvelope(
    val id: String,
    val type: String,
    val dispatchedAt: Instant,
)

sealed interface DataGsmEvent {
    val envelope: DataGsmEventEnvelope

    data class StudentsUpdated(
        override val envelope: DataGsmEventEnvelope,
        val changes: List<DataGsmStudentChange>,
    ) : DataGsmEvent

    data class ClubsUpdated(
        override val envelope: DataGsmEventEnvelope,
        val changes: List<DataGsmClubChange>,
    ) : DataGsmEvent

    data class Unsupported(
        override val envelope: DataGsmEventEnvelope,
    ) : DataGsmEvent
}

data class DataGsmStudentChange(
    val before: DataGsmStudentEventSnapshot?,
    val after: DataGsmStudentEventSnapshot?,
) {
    val studentId: Long
        get() = requireNotNull(after?.studentId ?: before?.studentId)
}

data class DataGsmStudentEventSnapshot(
    val studentId: Long,
    val name: String,
    val email: String,
    val sex: String,
    val grade: Int?,
    val classNumber: Int?,
    val number: Int?,
    val studentNumber: Int?,
    val major: String?,
    val specialty: String?,
    val role: String,
    /** Required stable-ID extension. Current upstream only supplies major_club_name. */
    val majorClubId: Long?,
    val majorClubName: String?,
    val githubId: String?,
)

data class DataGsmClubChange(
    val before: DataGsmClubEventSnapshot?,
    val after: DataGsmClubEventSnapshot?,
) {
    val clubId: Long
        get() = requireNotNull(after?.clubId ?: before?.clubId)
}

data class DataGsmClubEventSnapshot(
    val clubId: Long,
    val name: String,
    val type: String,
    val status: String,
    val leader: DataGsmEventStudentReference?,
    val participants: List<DataGsmEventStudentReference>,
) {
    val memberStudentIds: Set<Long>
        get() = participants.mapTo(mutableSetOf()) { it.studentId }.apply { leader?.let { add(it.studentId) } }
}

data class DataGsmEventStudentReference(
    /** Never substitute a name, email or mutable school number for this immutable ID. */
    val studentId: Long,
)

enum class DataGsmEventFailure {
    InvalidEnvelope,
    InvalidProviderContract,
    AmbiguousSourceOrder,
    PayloadIdentityCollision,
    ExternalVerificationUnavailable,
    ProcessingFailed,
}

enum class DataGsmContractIssue {
    MissingMajorClubId,
    MissingMemberStudentId,
}

/** A safe code only; never retain JSON text, profile fields or a transport exception. */
class DataGsmEventException(
    val failure: DataGsmEventFailure,
    val contractIssue: DataGsmContractIssue? = null,
) : RuntimeException("DataGSM event processing failed: $failure${contractIssue?.let { "/$it" }.orEmpty()}") {
    val safeFailureCode: String
        get() = failure.name + contractIssue?.let { "_${it.name}" }.orEmpty()
}
