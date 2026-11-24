package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import team.themoment.axia.simulation.country.CountryNews
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import kotlin.random.Random

enum class AppointmentProcedure(
    val label: String,
    val body: String,
) {
    CHINESE_CONGRESS("국가주석 선출 · 총리 인준", "전국인민대표대회"),
    VIETNAMESE_ASSEMBLY("국가주석 · 총리 선출", "국회"),
    KOREAN_PEOPLES_ASSEMBLY("국무위원장 · 내각총리 선출", "최고인민회의"),
    SAUDI_ROYAL_COUNCIL("왕령에 의한 각료회의 구성", "왕실"),
    IRANIAN_MAJLES("각료별 신임 표결", "이슬람 의회"),
}

@Serializable
data class AppointmentRule(
    val procedure: AppointmentProcedure,
    val firstSession: String? = null,
    val sourceNote: String,
) {
    init {
        require((procedure == AppointmentProcedure.IRANIAN_MAJLES) == (firstSession == null))
        firstSession?.let(LocalDate::parse)
        require(sourceNote.isNotBlank())
    }
}

data class MinisterVote(
    val portfolio: String,
    val nominee: String,
    val yes: Int,
    val required: Int,
) {
    val confirmed: Boolean get() = yes >= required
}

data class AppointmentSnapshot(
    val procedure: AppointmentProcedure,
    val nextSession: LocalDate?,
    val unfilledPortfolios: List<String>,
    val lastSession: LocalDate?,
    val decision: String?,
    val ministerVotes: List<MinisterVote>,
    val confirmedMinisters: Map<String, String>,
)

/** 헌법기관별 임명 회기와 이란 대통령이 제출한 미인준 각료 명단. */
internal class InstitutionAppointments(
    private val rule: AppointmentRule,
    private val leadership: LeadershipState,
    private val random: Random,
) {
    private val procedure = rule.procedure
    private var nextSession = rule.firstSession?.let(LocalDate::parse)
    private var vietnamReview: LocalDate? = null
    private var lastSession: LocalDate? = null
    private var decision: String? = null
    private var ministerVotes = emptyList<MinisterVote>()
    private var pending = emptyMap<String, Person>()
    private val confirmed = mutableMapOf<String, Person>()
    private var rejectedRounds = 0
    private var iranSpecialElection: LocalDate? = null
    private var lastChallenge: LocalDate? = null
    private val replacedMinistries = mutableSetOf<String>()
    private var challenged: String? = null

    fun snapshot() =
        AppointmentSnapshot(
            procedure,
            listOfNotNull(
                nextSession,
                vietnamReview,
            ).minOrNull(),
            pending.keys.toList(),
            lastSession,
            decision,
            ministerVotes,
            confirmed.mapValues {
                it.value.name
            },
        )

    /** 1차와 1주 뒤 결선 모두 헌법 제131조의 50일 시한 이내에 실시한다. */
    fun callIranianElection(date: LocalDate): LocalDate? {
        if (procedure != AppointmentProcedure.IRANIAN_MAJLES || !leadership.iranActingPresident || iranSpecialElection != null) return null
        challenged = null
        pending = emptyMap()
        nextSession = null
        val poll = date.plusDays(43).with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
        iranSpecialElection = poll
        return poll
    }

    fun presidentSeated(date: LocalDate): CountryNews? {
        if (procedure != AppointmentProcedure.IRANIAN_MAJLES) return null
        iranSpecialElection = null
        replacedMinistries.clear()
        challenged = null
        lastChallenge = date
        ministerVotes = emptyList()
        confirmed.clear()
        return submitCabinet(date, MINISTRIES, "대통령이 새 내각의 각료 후보 19명을 지명했다.")
    }

    private fun submitCabinet(
        date: LocalDate,
        portfolios: Collection<String>,
        message: String,
    ): CountryNews {
        pending = portfolios.associateWith { confirmed[it] ?: leadership.nominate(null, date) }
        rejectedRounds = 0
        nextSession = date.plusWeeks(2)
        return news("$message ${nextSession}에 신임 표결을 연다.")
    }

    fun advance(
        date: LocalDate,
        legislature: Legislature?,
        approval: Double,
    ): CountryNews? {
        if (procedure == AppointmentProcedure.IRANIAN_MAJLES) {
            if (leadership.iranActingPresident) return null
            return advanceMajles(date, requireNotNull(legislature), approval)
        }
        if (vietnamReview?.let { date >= it } == true) {
            vietnamReview = null
            return reviewVietnameseOfficers(date, requireNotNull(legislature), approval)
        }
        val session = requireNotNull(nextSession)
        if (date < session) return null
        val heads = leadership.current()
        val outcome =
            when (procedure) {
                AppointmentProcedure.SAUDI_ROYAL_COUNCIL -> {
                    val premier = heads.headOfGovernment?.let { leadership.appointPremier(date, retain = true) }
                    val chair = premier?.let { "${it.name} ${it.title}" } ?: "${heads.headOfState.name} 국왕"
                    "국왕이 왕령으로 각료회의를 재구성했다. $chair 주재로 새 임기를 시작했다."
                }

                AppointmentProcedure.CHINESE_CONGRESS -> {
                    val president = leadership.appointStateHead(date, retain = true)
                    val premier = leadership.appointPremier(date, retain = requireNotNull(heads.headOfGovernment).termsServed < 2)
                    "${president.name} 국가주석을 선출하고 주석이 지명한 ${premier.name} 국무원 총리를 인준했다."
                }

                AppointmentProcedure.VIETNAMESE_ASSEMBLY -> {
                    vietnamReview = LocalDate.of(date.year + 2, 10, 25)
                    val president = leadership.appointStateHead(date, retain = date.year - heads.headOfState.born < 70)
                    val premier = leadership.appointPremier(date, retain = date.year - requireNotNull(heads.headOfGovernment).born < 70)
                    "${president.name} 국가주석을 선출하고 주석의 제청에 따라 ${premier.name} 총리를 선출했다."
                }

                AppointmentProcedure.KOREAN_PEOPLES_ASSEMBLY -> {
                    val chairman = leadership.appointStateHead(date, retain = true)
                    val premier = leadership.appointPremier(date, retain = date.year - requireNotNull(heads.headOfGovernment).born < 70)
                    "${chairman.name} 국무위원장과 ${premier.name} 내각총리를 각각 선출했다."
                }

                AppointmentProcedure.IRANIAN_MAJLES -> {
                    error("이란은 각료별 신임 절차를 사용한다")
                }
            }
        nextSession =
            if (procedure == AppointmentProcedure.SAUDI_ROYAL_COUNCIL) {
                // 움알쿠라 공표 역표 이후의 먼 미래는 평균 삭망월 48개월로 추산한다.
                if (session.year < 2170) LocalDate.from(HijrahDate.from(session).plus(4, ChronoUnit.YEARS)) else session.plusDays(1417)
            } else {
                session.plusYears(5)
            }
        return record(date, outcome)
    }

    /** 결의 96/2023/QH15. 제3년 말 국회는 자신이 선출한 공직자의 신임을 각각 묻는다. */
    private fun reviewVietnameseOfficers(
        date: LocalDate,
        house: Legislature,
        approval: Double,
    ): CountryNews {
        val heads = leadership.current()
        val outcomes =
            listOfNotNull(heads.headOfState, heads.headOfGovernment).filter { it.since.year != date.year }.map { holder ->
                val trust = (approval + random.nextDouble(-10.0, 10.0)).coerceIn(0.0, 100.0)
                val low = (house.votingSeats * (1 - trust / 100)).roundToInt()
                val high = ((house.votingSeats - low) * trust / 100).roundToInt()
                val middle = house.votingSeats - low - high
                val rating = "${holder.name} ${holder.title} 높음 $high · 신임 $middle · 낮음 ${low}표"
                if (low * 2 <= house.votingSeats) return@map "$rating, 유임"
                val direct = low * 3 >= house.votingSeats * 2
                val second = if (direct) null else vote(house, trust)
                if (second != null && second.no * 2 <= house.votingSeats) return@map "$rating, 후속 표결에서 유임"
                val next =
                    if (holder.id ==
                        heads.headOfState.id
                    ) {
                        leadership.appointStateHead(date, false)
                    } else {
                        leadership.appointPremier(date, false)
                    }
                "$rating, ${if (direct) "해임 의결" else "후속 불신임"} 후 ${next.name} 선출"
            }
        return record(date, if (outcomes.isEmpty()) "올해 취임한 국가주석과 총리는 신임 평가에서 제외됐다." else outcomes.joinToString(". ") + ".")
    }

    private fun advanceMajles(
        date: LocalDate,
        house: Legislature,
        approval: Double,
    ): CountryNews? {
        val hearing = nextSession
        if (hearing == null) {
            if (date.dayOfMonth != 1 || approval >= 36 || lastChallenge?.plusMonths(3)?.let { date < it } == true) return null
            if (house.groups.filter { it.role == PartyRole.OPPOSITION }.sumOf { it.votingSeats } < 10) return null
            challenged = MINISTRIES.random(random)
            lastChallenge = date
            nextSession = date.plusDays(7)
            return news("의원 10명 이상이 $challenged 장관에 대한 문책을 요구했다. ${nextSession}에 신임을 표결한다.")
        }
        if (date < hearing) return null
        val minister = challenged
        if (minister != null) {
            challenged = null
            nextSession = null
            val votes = vote(house, approval + random.nextDouble(-8.0, 8.0))
            val result = "$minister 장관 신임 찬성 ${votes.yes}표 · 가결선 ${votes.required}표."
            if (votes.no < votes.required) return record(date, "$result 장관이 유임됐다.")
            confirmed.remove(minister)
            replacedMinistries += minister
            val renewAll = replacedMinistries.size * 2 >= MINISTRIES.size
            if (renewAll) replacedMinistries.clear()
            val offices = if (renewAll) MINISTRIES else listOf(minister)
            val message = if (renewAll) "내각의 절반 이상이 교체돼 전체 내각이 다시 신임을 구한다." else "대통령이 후임 장관을 지명했다."
            val submission = submitCabinet(date, offices, "$result $message")
            return record(date, submission.text)
        }
        ministerVotes =
            pending.map { (portfolio, nominee) ->
                // 재지명할 때 의회 협의를 거친 후보에게 더 많은 지지가 모이도록 한다.
                val candidateSupport = 56 + (approval - 50) * 0.35 + rejectedRounds * 7 + random.nextDouble(-15.0, 15.0)
                val votes = vote(house, candidateSupport)
                MinisterVote(portfolio, nominee.name, votes.yes, votes.required)
            }
        val rejected = ministerVotes.filterNot { it.confirmed }.map { it.portfolio }
        ministerVotes.forEach {
            if (it.confirmed) confirmed[it.portfolio] = pending.getValue(it.portfolio) else confirmed.remove(it.portfolio)
        }
        pending = rejected.associateWith { leadership.nominate(null, date) }
        rejectedRounds++
        nextSession = if (rejected.isEmpty()) null else date.plusWeeks(2)
        val outcome = if (rejected.isEmpty()) "내각 인준이 완료됐다." else "${rejected.joinToString("·")} 후보가 부결돼 후임 후보를 다시 심사한다."
        return record(date, "각료 후보 ${ministerVotes.size}명 중 ${ministerVotes.count { it.confirmed }}명을 인준했다. $outcome")
    }

    private fun vote(
        house: Legislature,
        score: Double,
    ) = house.forecast(score, score, emptyMap(), null)

    private fun record(
        date: LocalDate,
        text: String,
    ): CountryNews {
        lastSession = date
        decision = text
        return news(text)
    }

    private fun news(text: String) = CountryNews(procedure.body, text, major = true)

    private companion object {
        val MINISTRIES =
            listOf(
                "외교",
                "내무",
                "정보",
                "국방",
                "법무",
                "경제재정",
                "산업광업통상",
                "농업",
                "석유",
                "에너지",
                "도로도시개발",
                "정보통신",
                "보건의학교육",
                "과학연구기술",
                "교육",
                "문화이슬람지도",
                "협동노동사회복지",
                "체육청소년",
                "문화유산관광수공예",
            )
    }
}
