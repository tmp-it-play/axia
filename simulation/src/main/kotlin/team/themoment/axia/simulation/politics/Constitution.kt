package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.country.BillCheck
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.RegulationItem
import java.time.LocalDate
import kotlin.math.roundToInt

/** 상원(또는 그에 준하는 2원)의 권한. */
enum class ChamberPower(
    val label: String,
) {
    /** 부결하면 법안이 좌초한다. */
    VETO("거부권"),

    /** 부결해도 하원이 재의결하므로 시행만 늦어진다. */
    DELAY("지연"),
}

data class UpperVote(
    val group: String,
    val yes: Int,
    val no: Int,
)

@Serializable
data class UpperGroup(
    val name: String,
    val seats: Int,
    val role: PartyRole,
    /** 같은 정당 또는 공식 원내제휴의 하원 교섭단체. */
    val alignWith: String? = null,
    val nonVoting: Int = 0,
    @Serializable(with = LocalDateSerializer::class)
    val swearingDate: LocalDate? = null,
    val blocVotes: Boolean = false,
    val coalition: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank() && seats >= 0 && nonVoting in 0..seats)
    }

    val votingSeats: Int get() = seats - nonVoting

    internal fun votes(
        score: Double,
        oppositionBill: Boolean,
    ): Int {
        val alignment =
            if (!oppositionBill) {
                role.backing
            } else {
                when (role) {
                    PartyRole.LEAD, PartyRole.COALITION -> PartyRole.OPPOSITION.backing
                    PartyRole.OPPOSITION -> PartyRole.LEAD.backing
                    else -> -role.backing
                }
            }
        if (blocVotes) return if (score + alignment > 50.0) votingSeats else 0
        return (votingSeats * (score + alignment).coerceIn(0.0, 100.0) / 100.0).roundToInt()
    }
}

@Serializable
data class UpperHouse(
    val name: String,
    val power: ChamberPower,
    val seats: Int,
    /** 정부 쪽 의석. 나머지는 [oppositionSeats]와 무소속·임명직 등 기타다. */
    val governmentSeats: Int,
    val oppositionSeats: Int,
    val groups: List<UpperGroup> = emptyList(),
    val sourceNote: String? = null,
) {
    init {
        require(
            name.isNotBlank() && seats > 0 && governmentSeats >= 0 && oppositionSeats >= 0 && governmentSeats + oppositionSeats <= seats,
        )
        require(groups.distinctBy { it.name }.size == groups.size && groups.sumOf { it.seats } <= seats)
    }

    val filledSeats: Int get() = if (groups.isEmpty()) seats else groups.sumOf { it.seats }
    val votingSeats: Int get() = if (groups.isEmpty()) seats else groups.sumOf { it.votingSeats }
    val otherSeats: Int get() = filledSeats - governmentSeats - oppositionSeats

    internal fun session(
        chamber: Legislature?,
        date: LocalDate,
    ): UpperHouse {
        if (groups.isEmpty()) return this
        val sitting =
            groups.map { group ->
                val ally = chamber?.groups?.find { it.modelName == group.alignWith || it.name == group.alignWith }
                val sworn = group.swearingDate?.let { date >= it } == true
                val blocRoles = group.coalition.map { partner -> chamber?.groups?.find { it.modelName == partner }?.role }
                val role =
                    when {
                        blocRoles.isEmpty() -> ally?.role ?: group.role
                        blocRoles.all { it in setOf(PartyRole.LEAD, PartyRole.COALITION, PartyRole.SUPPLY) } -> PartyRole.COALITION
                        blocRoles.all { it == PartyRole.OPPOSITION } -> PartyRole.OPPOSITION
                        else -> PartyRole.OTHER
                    }
                group.copy(
                    name =
                        if (ally?.foundation != null &&
                            !group.blocVotes
                        ) {
                            ally.name
                        } else {
                            group.name
                        },
                    role = role,
                    nonVoting = if (sworn) 0 else group.nonVoting,
                )
            }
        return copy(
            groups = sitting,
            governmentSeats =
                sitting
                    .filter {
                        it.role == PartyRole.LEAD || it.role == PartyRole.COALITION || it.role == PartyRole.SUPPLY
                    }.sumOf { it.seats },
            oppositionSeats = sitting.filter { it.role == PartyRole.OPPOSITION }.sumOf { it.seats },
        )
    }

    internal fun rollCall(
        score: Double,
        oppositionBill: Boolean = false,
    ): List<UpperVote> =
        groups.map { group ->
            val yes = group.votes(score, oppositionBill)
            UpperVote(group.name, yes, group.votingSeats - yes)
        }

    /** 공통 지지 기반 [score]에서 예상한 찬성표. 하원과 같은 정부·야당 가점을 쓴다. */
    fun forecast(
        score: Double,
        oppositionBill: Boolean = false,
    ): Int {
        if (groups.isNotEmpty()) return groups.sumOf { it.votes(score, oppositionBill) }
        val governmentBacking = if (oppositionBill) PartyRole.OPPOSITION.backing else PartyRole.LEAD.backing
        val oppositionBacking = if (oppositionBill) PartyRole.LEAD.backing else PartyRole.OPPOSITION.backing

        fun share(points: Double) = points.coerceIn(0.0, 100.0) / 100.0
        return (
            governmentSeats * share(score + governmentBacking) +
                oppositionSeats * share(score + oppositionBacking) +
                otherSeats * share(score)
        ).roundToInt()
    }
}

/** 동거 정부에서 정부와 다른 진영의 대통령이 가진 법률 거부권. 하원 찬성이 [override] 이상이면 재의결된다. */
@Serializable
data class PresidentialVeto(
    val holder: String,
    val party: String,
    val override: Double,
) {
    init {
        require(holder.isNotBlank() && override in 0.5..1.0)
    }
}

/**
 * 헌법기관의 권한(constitutions.json).
 * @property judicialIndependence V-Dem 사법 견제 지수. 위헌 심사에서 법률을 무효로 할 가능성에 비례한다.
 * @property reviewsLaws 법원이 의회 제정법을 무효로 할 수 있는지(영국 대법원은 못 한다).
 * @property executiveTermLimit 행정부 수반의 연속 임기 제한. 없으면 null.
 * @property confidence 내각·정부 수반 불신임의 표결과 후속 절차.
 */
@Serializable
data class ConstitutionProfile(
    val court: String?,
    val reviewsLaws: Boolean,
    val judicialIndependence: Double,
    val upperHouse: UpperHouse? = null,
    val executiveTermLimit: Int? = null,
    val confidence: ConfidenceRule? = null,
    val appointments: AppointmentRule? = null,
    val indirectPresident: IndirectPresidentRule? = null,
    val presidentialVeto: PresidentialVeto? = null,
    /** 근래 쿠데타(시도) 이력이 있어 군부 개입 위험이 있는 나라인지. */
    val coupProne: Boolean = false,
    val sourceNote: String,
) {
    init {
        require(judicialIndependence in 0.0..1.0 && sourceNote.isNotBlank())
        require(!reviewsLaws || court != null)
        require(executiveTermLimit == null || executiveTermLimit >= 1)
    }

    companion object {
        private val profiles by lazy {
            val text =
                requireNotNull(
                    ConstitutionProfile::class.java.getResourceAsStream("/world/constitutions.json"),
                ) { "Missing constitutions.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<Map<String, ConstitutionProfile>>(text)
        }

        fun forCountry(id: String): ConstitutionProfile = requireNotNull(profiles[id]) { "Missing constitution for $id" }
    }
}

/** 위헌 심사 중이거나 끝난 사건. */
data class CourtCase(
    val court: String,
    val change: PolicyChange,
    val filed: LocalDate,
    val rulingDate: LocalDate,
    /** 판결 전에는 null. true면 위헌 결정으로 변경이 취소됐다. */
    val struckDown: Boolean?,
)

/** 헌법기관 화면 요약. */
data class ConstitutionSnapshot(
    val profile: ConstitutionProfile,
    val cases: List<CourtCase>,
    val appointments: AppointmentSnapshot? = null,
    val indirectPresident: IndirectPresidentSnapshot? = null,
    val nextUpperRenewal: LocalDate? = null,
    val lastUpperRenewal: UpperRenewalResult? = null,
)

/** 헌법기관의 심사 규칙. 상태가 없는 계산만 모은다. */
internal object ConstitutionalReview {
    /** 상원이 있는 나라의 상원 판정. 거부권이 없는 상원은 부결해도 [UPPER_HOUSE_DELAY_DAYS]만 늦춘다. */
    fun upperHouseCheck(
        house: UpperHouse,
        score: Double,
        oppositionBill: Boolean = false,
    ): BillCheck {
        val yes = house.forecast(score, oppositionBill)
        val required = house.votingSeats / 2 + 1
        val delay = if (house.power == ChamberPower.DELAY) UPPER_HOUSE_DELAY_DAYS else 0
        return BillCheck(house.name, yes >= required, "예상 찬성 $yes / 가결 ${required}표 · ${house.power.label}", delay)
    }

    /**
     * 동거 대통령의 거부권. 대통령 진영 정당의 지지 기반이 법안으로 손해를 보면 거부하고,
     * 하원 찬성이 재의결선에 닿으면 무력화된다.
     */
    fun vetoCheck(
        veto: PresidentialVeto,
        legislature: Legislature?,
        support: BillSupport,
    ): BillCheck? {
        val party = legislature?.groups?.firstOrNull { it.name == veto.party } ?: return null
        // 대통령 정당이 정부에 들어오면 동거가 끝난 것이다.
        if (party.role == PartyRole.LEAD || party.role == PartyRole.COALITION) return null
        val interest = party.constituency?.let { support.groupScores.getValue(it) - support.constituency } ?: 0.0
        val vetoed = interest < VETO_INTEREST || support.score < VETO_SCORE
        if (!vetoed) return BillCheck("대통령 ${veto.holder}", true, "거부권 행사 예상 없음")
        val rollCall = support.rollCall
        val needed = legislature.votingSeats * veto.override
        val overridden = rollCall != null && rollCall.yes >= needed
        val detail = "거부권 행사 예상 · 재의결 ${needed.roundToInt()}표 필요(예상 ${rollCall?.yes ?: 0}표)"
        return BillCheck("대통령 ${veto.holder}", overridden, detail)
    }

    /**
     * 통과한 변경 중 위헌 소지가 있는 것의 심각도(0~1). 시민 자유 축소, 큰 폭의 치안·감시 강화,
     * 독립 기관의 정부 통제가 대상이다.
     */
    fun severity(change: PolicyChange): Double =
        when (change.item) {
            RegulationItem.CIVIL_LIBERTIES -> (-change.delta * 3.0).coerceIn(0.0, 1.0)
            RegulationItem.POLICING -> ((change.delta - 0.15) * 2.0).coerceIn(0.0, 1.0)
            InstitutionItem.CENTRAL_BANK_INDEPENDENCE -> if (change.delta < 0) 0.3 else 0.0
            else -> 0.0
        }

    const val UPPER_HOUSE_DELAY_DAYS = 60
    private const val VETO_INTEREST = -3.0
    private const val VETO_SCORE = 35.0
}
