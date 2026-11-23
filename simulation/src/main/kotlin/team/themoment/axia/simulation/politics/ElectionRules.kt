package team.themoment.axia.simulation.politics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** 정부가 어디서 나오는지. 선거 후 정부 재구성 방식을 정한다. */
enum class GovernmentSystem(
    val label: String,
) {
    /** 의회 다수에서 정부가 나온다. */
    PARLIAMENTARY("의원내각제"),

    /** 대통령 선거가 정부를 정한다. 의회 선거는 의석만 바꾼다. */
    PRESIDENTIAL("대통령제"),

    /** 정부는 의회에서 나오고 대통령 선거는 국가원수만 바꾼다. */
    SEMI_PRESIDENTIAL("이원집정제"),

    /** 권력 구성을 바꾸는 선거가 없다. */
    NON_COMPETITIVE("비경쟁 체제"),
}

/** 득표가 의석으로 바뀔 때 큰 정당에 얼마나 유리한지. 의석 비중 ∝ 득표율^[seatExponent]. */
enum class ElectoralSystem(
    val label: String,
    val seatExponent: Double,
) {
    PROPORTIONAL("비례대표제", 1.0),
    MIXED("혼합형", 1.4),
    MAJORITARIAN("다수대표제", 1.9),
}

/** 시작 시점에 하원이 해산된 나라의 첫 선거에 쓸 직전 총선 득표 자료. */
@Serializable
data class OpeningContest(
    val chamberName: String,
    val headOfGovernmentTitle: String,
    val parties: List<OpeningParty>,
) {
    init {
        require(chamberName.isNotBlank() && headOfGovernmentTitle.isNotBlank())
        require(parties.any { !it.other } && parties.distinctBy { it.name }.size == parties.size)
    }
}

@Serializable
data class OpeningParty(
    val name: String,
    val votes: Int,
    /** 무소속 등 하나의 정당으로 연정을 이끌거나 유보석을 배분받을 수 없는 후보 묶음. */
    val other: Boolean = false,
) {
    init {
        require(name.isNotBlank() && votes > 0)
    }
}

@Serializable
data class LegislativeElectionRule(
    @Serializable(with = LocalDateSerializer::class)
    val next: LocalDate,
    val termYears: Int,
    /** 선거 후 법정 정원. 공석도 선거로 채워진다. */
    val seats: Int,
    val electoralSystem: ElectoralSystem,
    /** 정부가 임기 중 의회를 해산하고 조기 총선을 치를 수 있는지. */
    val earlyDissolution: Boolean,
    /** 정원 중 지역구 당선 정당에 비례해 따로 배분할 의석. */
    val reservedSeats: Int = 0,
    /** 혼합제에서 지역 단위로 선출하는 정원. */
    val constituencySeats: Int? = null,
    val openingContest: OpeningContest? = null,
) {
    init {
        require(termYears in 1..7 && seats > 0 && reservedSeats in 0 until seats)
        require((electoralSystem == ElectoralSystem.MIXED) == (constituencySeats != null))
        require(constituencySeats == null || constituencySeats in 1..(seats - reservedSeats))
    }
}

@Serializable
data class ExecutiveElectionRule(
    @Serializable(with = LocalDateSerializer::class)
    val next: LocalDate,
    val termYears: Int,
    val title: String,
    /** 0은 단순 다수제. 그 밖에는 전국 과반 미달 시 양자 결선을 치르는 모형 일정이다. */
    val runoffDays: Int = 0,
    val lastSundayRunoff: Boolean = false,
    val partyPrimary: Boolean = false,
    val nonpartisan: Boolean = false,
) {
    init {
        require(runoffDays >= 0 && (!lastSundayRunoff || runoffDays > 0))
        require(termYears in 1..7 && title.isNotBlank())
    }
}

/**
 * 2024년 1월 1일 기준 선거 일정. 법정 일정이 정해져 있지 않은 의원내각제 국가는 법정 최종 기한을 쓴다.
 * [suspended]가 있으면 그 사유로 선거를 치르지 않는다.
 */
@Serializable
data class ElectionRules(
    val system: GovernmentSystem,
    val legislature: LegislativeElectionRule? = null,
    val executive: ExecutiveElectionRule? = null,
    val suspended: String? = null,
    /** 날짜의 근거와 기준. 데이터 전용 주석이다. */
    val sourceNote: String,
) {
    init {
        require(sourceNote.isNotBlank())
        require(system != GovernmentSystem.NON_COMPETITIVE || (legislature == null && executive == null))
        require(suspended == null || (legislature == null && executive == null))
    }

    val coalitionNegotiations: Boolean get() =
        system == GovernmentSystem.PARLIAMENTARY || system == GovernmentSystem.SEMI_PRESIDENTIAL ||
            (system == GovernmentSystem.PRESIDENTIAL && legislature?.electoralSystem == ElectoralSystem.PROPORTIONAL)
}

internal object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: LocalDate,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
