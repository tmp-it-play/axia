package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.people.Person
import java.time.LocalDate
import kotlin.math.roundToInt

/** 러시아·르완다에서는 불신임 대상인 내각과 그 후임을 지명할 대통령의 임기를 별도로 다룬다. */
internal class PresidentialCabinet(
    private val rule: ConfidenceRule,
) {
    private val duma get() = rule.procedure == ConfidenceProcedure.DUMA_CONFIDENCE
    var nextDecision: LocalDate? = null
        private set
    var caretaker = false
        private set
    var dissolved = false
        private set

    private var lastDumaElection = LocalDate.of(2021, 9, 19)
    private var lastOverride: LocalDate? = null
    private var firstRefusal: LocalDate? = null
    private var refusedPremier: Int? = null
    private var signatories = emptySet<String>()
    private var motionSession: LocalDate? = null
    private var barredSession: LocalDate? = null
    private val barredSignatories = mutableSetOf<String>()
    private var targetPremier: Int? = null
    private var approvalAtFiling = 50.0
    private var rejectedNominations = 0
    private var canReappoint = false

    fun reset() {
        nextDecision = null
        caretaker = false
        dissolved = false
        firstRefusal = null
        refusedPremier = null
        signatories = emptySet()
        targetPremier = null
        rejectedNominations = 0
        canReappoint = false
    }

    private fun dumaMayVote(
        date: LocalDate,
        nextPresidential: LocalDate?,
    ): Boolean =
        date >= lastDumaElection.plusYears(1) &&
            nextPresidential?.let { date < it.minusMonths(6) } == true &&
            lastOverride?.let { date >= it.plusYears(1) } != false

    /** 하원의 세 정기회. 닫히기 전에 발의한 안건은 표결 때까지 회기를 연장한다. */
    private fun rwandanSession(date: LocalDate): LocalDate? =
        listOf(2, 6, 10).map { LocalDate.of(date.year, it, 5) }.find { date >= it && date < it.plusMonths(2) }

    private fun votes(
        chamber: Legislature,
        approval: Double,
    ): Map<String, Int> =
        chamber.groups.associate { group ->
            val count =
                when {
                    group.role == PartyRole.OPPOSITION -> {
                        group.votingSeats
                    }

                    !duma && group.role == PartyRole.OTHER && group.reservedSeats > 0 -> {
                        // 특별대표는 당론이 없다. 불만이 큰 국면에서 일부 의원이 서명·비밀투표에 동참하는 것으로 추계한다.
                        val discontent = ((50.0 - approval) / 50.0).coerceIn(0.0, 1.0)
                        (group.votingSeats * discontent).roundToInt()
                    }

                    else -> {
                        0
                    }
                }
            group.modelName to count
        }

    fun file(
        chamber: Legislature,
        premier: Int,
        date: LocalDate,
        approval: Double,
        nextPresidential: LocalDate?,
    ): ConfidenceDecision? {
        if (nextDecision != null || dissolved || caretaker) return null
        if (duma && !dumaMayVote(date, nextPresidential)) return null
        val session = if (duma) null else rwandanSession(date) ?: return null
        val willing = votes(chamber, approval).filterValues { it > 0 }
        val signatures = if (!duma && barredSession == session) willing - barredSignatories else willing
        val needed = if (duma) 90 else 27
        if (signatures.values.sum() < needed) return null
        targetPremier = premier
        signatories = signatures.keys
        motionSession = session
        approvalAtFiling = approval
        nextDecision = date.plusDays(if (duma) 8 else 2)
        val scrutiny = if (duma) "" else "하원이 대정부 질의를 마쳤다. "
        val notice = "${scrutiny}${signatures.values.sum()}명 서명으로 내각 전체 불신임안이 발의됐다. ${nextDecision}에 표결한다."
        return ConfidenceDecision(chamber, listOf(notice))
    }

    fun afterElections(
        chamber: Legislature,
        legislativeDue: Boolean,
        presidentElected: Boolean,
        presidentChanged: Boolean,
        date: LocalDate,
    ): ConfidenceDecision? {
        if (legislativeDue) {
            lastDumaElection = date
            dissolved = false
            signatories = emptySet()
            barredSignatories.clear()
            firstRefusal = null
            targetPremier = null
            if (!caretaker) nextDecision = null
        }
        if (!presidentElected) return null
        val retain = !presidentChanged && !caretaker
        val notice = if (duma) "대통령 취임에 따라 내각이 총사임했다. 대통령 지명 총리의 국가두마 인준을 기다린다." else "대통령이 취임했다. 후임 내각을 구성할 총리를 임명한다."
        return startCabinet(chamber, date, notice, retain)
    }

    fun advance(
        chamber: Legislature,
        date: LocalDate,
        nextLegislative: LocalDate?,
        nextPresidential: LocalDate?,
        president: Person,
        premier: Int?,
    ): ConfidenceDecision? {
        if (nextDecision?.let { date >= it } != true) return null
        if (caretaker) return nominate(chamber, date, president)
        nextDecision = null
        if ((premier != null && targetPremier != premier) || (duma && !dumaMayVote(date, nextPresidential))) {
            return ConfidenceDecision(chamber, listOf("내각 또는 헌법상 표결 조건이 바뀌어 불신임안 심의가 종료됐다."))
        }
        val yes = votes(chamber, approvalAtFiling).values.sum()
        val required = if (duma) 226 else 54
        val ballot = "내각 불신임안 ${if (yes >= required) "가결" else "부결"} · 찬성 ${yes}표 / 가결 ${required}표"
        if (yes < required) {
            if (!duma) {
                if (motionSession != barredSession) barredSignatories.clear()
                barredSession = motionSession
                barredSignatories.addAll(signatories)
            }
            return ConfidenceDecision(chamber, listOf(ballot))
        }
        if (!duma) return startCabinet(chamber, date, "$ballot. 총리가 대통령에게 내각 사직서를 냈다. 후임 임명까지 통상 사무를 수행한다.")
        val repeated = targetPremier == refusedPremier && firstRefusal?.let { date <= it.plusMonths(3) } == true
        // 3분의 2가 한 번에 반대하면 대통령이 사임을 수용한다. 그 밖에는 처음에 현 내각을 유임한다.
        if (!repeated && yes < 300) {
            refusedPremier = targetPremier
            firstRefusal = date
            return ConfidenceDecision(chamber, listOf("$ballot. 대통령이 내각 해임을 거부했다. ${date.plusMonths(3)}까지 재가결하면 다시 거부할 수 없다."))
        }
        if (repeated && nextLegislative != null && nomination(chamber, president).second < 226) {
            val election = minOf(date.plusDays(requireNotNull(rule.electionDays).toLong()), nextLegislative)
            reset()
            dissolved = true
            return ConfidenceDecision(
                chamber,
                listOf("$ballot. 대통령이 재가결에 따라 현 내각을 유임하고 국가두마를 해산했다. 총선은 ${election}이다."),
                election = election,
            )
        }
        return startCabinet(chamber, date, "$ballot. 대통령이 내각을 해임하고 후임 총리 지명 절차를 시작했다.")
    }

    private fun startCabinet(
        chamber: Legislature,
        date: LocalDate,
        notice: String,
        retain: Boolean = false,
    ): ConfidenceDecision {
        reset()
        caretaker = true
        canReappoint = retain
        nextDecision = date.plusDays(rule.consultationDays.toLong())
        return ConfidenceDecision(chamber, listOf(notice))
    }

    private fun nomination(
        chamber: Legislature,
        president: Person,
    ): Pair<List<ParliamentaryGroup>, Int> {
        val sponsor = chamber.groups.find { it.name == president.party } ?: chamber.groups.find { it.role == PartyRole.LEAD }
        if (sponsor == null) return emptyList<ParliamentaryGroup>() to 0
        val backing = ElectionModel.formGovernment(chamber.groups, 226, chamber.groups.associate { it.name to it.role }, sponsor.name)
        return backing to
            backing
                .filter {
                    it.role == PartyRole.LEAD || it.role == PartyRole.COALITION || it.role == PartyRole.SUPPLY
                }.sumOf { it.votingSeats }
    }

    private fun nominate(
        chamber: Legislature,
        date: LocalDate,
        president: Person,
    ): ConfidenceDecision {
        if (!duma) {
            val retain = canReappoint
            reset()
            val text = if (retain) "대통령이 현 총리를 재임명했다." else "대통령이 새 총리를 임명하고 총리 제청으로 후임 내각을 구성한다."
            val government =
                president.party?.let {
                    ElectionModel.presidentialLegislature(chamber, PoliticalProfile.forCountry("RWA").elections, it)
                } ?: chamber
            return ConfidenceDecision(government, listOf(text), successor = !retain)
        }
        val (backers, yes) = nomination(chamber, president)
        if (yes < 226 && ++rejectedNominations < 3) {
            nextDecision = date.plusDays(7)
            return ConfidenceDecision(chamber, listOf("국가두마가 대통령의 총리 지명을 ${rejectedNominations}회 부결했다. ${nextDecision}에 다시 표결한다."))
        }
        val approved = yes >= 226
        val retain = canReappoint
        val members = if (backers.isEmpty()) chamber else chamber.withGroups(backers)
        val text = if (approved) "국가두마가 대통령의 총리 지명안을 찬성 ${yes}표로 인준했다." else "세 차례 지명 부결 후 대통령이 총리를 임명하고 현 의회를 유지했다. 1년 동안 내각 불신임이 제한된다."
        if (!approved) lastOverride = date
        reset()
        return ConfidenceDecision(members, listOf(text + if (retain) " 현 총리를 재임명했다." else ""), successor = !retain)
    }
}
