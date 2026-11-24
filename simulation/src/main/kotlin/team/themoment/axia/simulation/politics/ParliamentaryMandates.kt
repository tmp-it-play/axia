package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ln
import kotlin.random.Random

@Serializable
enum class Replacement(
    val label: String,
) {
    DISTRICT("지역구 보궐"),
    LIST("명부 차순위 승계"),
    SUBSTITUTE("동반 선출 대리인 승계"),
    DELEGATION("특별대표 보충"),
    NATIONAL_POLL("전국선거 동시 보궐"),
    COMMUNITY_POLL("종교 공동체 보궐"),
    DEFERRED("보충 유보"),
}

@Serializable
data class SeatVacancy(
    /** 당명이 바뀌어도 당선 당시 명부에 연결한다. */
    val partyKey: String?,
    val method: Replacement,
    @Serializable(with = LocalDateSerializer::class)
    val due: LocalDate? = null,
    val districtSeat: Boolean = false,
    val reservedSeat: Boolean = false,
    val region: String? = null,
    val regionId: String? = null,
    val districtIndex: Int? = null,
    val listName: String? = null,
)

internal enum class MandateRoute { DISTRICT, LIST, MIXED, SUBSTITUTE, DEFERRED }

internal enum class PartySwitch { KEEP, VACATE, VACATE_LIST, UNMODELLED }

@Serializable
internal data class MandateRule(
    val route: MandateRoute,
    val switch: PartySwitch = PartySwitch.UNMODELLED,
    val days: Long = 42,
    val lastDays: Long = 0,
    val sourceNote: String,
)

internal data class MandateChange(
    val chamber: Legislature,
    val news: List<String>,
    val districts: List<DistrictResult>? = null,
)

/** 선거일 당시 결과와 현직 지역구 의원 명단을 분리한다. 빈 문자열은 표결권자가 없는 선거구다. */
private fun List<DistrictResult>.replaceHolder(
    region: String?,
    index: Int?,
    party: String,
): List<DistrictResult> {
    if (region == null || index == null) return this
    return map { row ->
        if (row.regionId == region) row.copy(winners = row.winners.mapIndexed { i, name -> if (i == index) party else name }) else row
    }
}

/** 한 달에 한 의원의 퇴임 또는 당적 이동을 표본화한다. 보충일의 당선·승계는 일 단위로 실행한다. */
internal class ParliamentaryMandates private constructor(
    private val id: String,
    private val rule: MandateRule,
) {
    private var turkishPollTerm: LocalDate? = null

    fun review(
        chamber: Legislature,
        date: LocalDate,
        general: LocalDate?,
        approval: Double,
        legislatureRule: LegislativeElectionRule?,
        random: Random,
        districts: List<DistrictResult> = emptyList(),
    ): MandateChange? {
        if (id == "TUR" && general != null && general > date.plusYears(1)) {
            val gaps = chamber.vacancies.count { it.method == Replacement.DEFERRED }
            val exceptional = gaps >= 30
            if (exceptional || (gaps > 0 && general != turkishPollTerm && date >= general.minusMonths(30))) {
                turkishPollTerm = general
                val poll = date.plusDays(60)
                val scheduled =
                    chamber.vacancies.map {
                        if (it.method == Replacement.DEFERRED) it.copy(method = Replacement.DISTRICT, due = poll) else it
                    }
                return MandateChange(chamber.copy(vacancies = scheduled), listOf("공석 ${gaps}석을 채우는 의회 보궐선거가 ${poll}로 결정됐다."))
            }
        }
        if (chamber.filledSeats <= 1 || general == date) return null
        val incidence = (chamber.filledSeats * 0.00024).coerceAtMost(0.18)
        val draw = random.nextDouble()
        val changeParty = draw >= incidence
        if (changeParty && (rule.switch == PartySwitch.UNMODELLED || approval >= 40 || draw >= incidence * 1.6)) return null
        val pool =
            chamber.groups.filter {
                it.votingSeats > 0 &&
                    (!changeParty || it.role == PartyRole.LEAD || it.role == PartyRole.COALITION)
            }
        if (pool.isEmpty()) return null
        var ticket = random.nextInt(pool.sumOf { it.votingSeats })
        val old =
            pool.first {
                ticket -= it.votingSeats
                ticket < 0
            }
        val seat = random.nextInt(old.seats - old.nonVoting)
        val districtCount = old.districtMandates ?: initialDistricts(old, legislatureRule, chamber)
        val reserved = seat < old.reservedSeats
        val district = !reserved && seat - old.reservedSeats < districtCount
        val known =
            if (district || (reserved && id == "IRN")) {
                districts
                    .flatMap { row ->
                        row.winners.indices
                            .filter { row.winners[it] == old.name }
                            .map { row to it }
                    }.randomOrNull(random)
            } else {
                null
            }
        val region = known?.let { (row, i) -> row.constituencies.getOrNull(i) ?: "${row.regionName} ${i + 1}구" }
        val list =
            if (!reserved && !district && chamber.rankedLists.isNotEmpty()) {
                val position = seat - old.reservedSeats - districtCount
                chamber.rankedLists.flatMap { row -> List(row.seated.count { it == old.name }) { row } }[position]
            } else {
                null
            }
        val move = changeParty && rule.switch != PartySwitch.VACATE && (rule.switch != PartySwitch.VACATE_LIST || district)
        if (move) {
            val party =
                chamber.groups.filter { it.name != old.name && it.role == PartyRole.OPPOSITION && it.votingSeats > 0 }.minByOrNull {
                    ideologicalDistance(it.identity, old.identity)
                } ?: return null
            val next = chamber.moveListSeat(list, old.name, party.name)
            val change = transfer(next, old, party, district, reserved)
            return change.copy(districts = districts.replaceHolder(known?.first?.regionId, known?.second, party.name))
        }
        val method =
            when {
                id == "IRN" && (general == null || date.plusYears(1) >= general) -> Replacement.DEFERRED
                id == "IRN" && reserved -> Replacement.COMMUNITY_POLL
                id == "IRN" -> Replacement.NATIONAL_POLL
                reserved && id == "RWA" -> Replacement.DELEGATION
                reserved -> Replacement.LIST
                rule.route == MandateRoute.SUBSTITUTE -> Replacement.SUBSTITUTE
                rule.route == MandateRoute.DEFERRED -> Replacement.DEFERRED
                district && id == "UKR" -> Replacement.DEFERRED
                district -> Replacement.DISTRICT
                else -> Replacement.LIST
            }
        val due = replacementDate(method, date, general)
        val vacancy = SeatVacancy(old.modelName, method, due, district, reserved, region, known?.first?.regionId, known?.second, list?.name)
        val remaining = chamber.groups.map { if (it == old) it.minusMember(district, reserved) else it }
        val next =
            chamber.moveListSeat(list, old.name, null).copy(
                groups = remaining,
                filledSeats = chamber.filledSeats - 1,
                vacancies = chamber.vacancies + vacancy,
            )
        val cause =
            if (changeParty) {
                "당적 이탈에 따른 의원직 상실"
            } else if (method == Replacement.SUBSTITUTE) {
                "의원 사망"
            } else {
                "의원 퇴임"
            }
        return MandateChange(
            next,
            listOf("${old.name} ${cause}로 한 석이 비었다. ${due?.let { "${method.label} 예정일 $it." } ?: "보충 일정을 유보한다."}"),
            districts.replaceHolder(known?.first?.regionId, known?.second, ""),
        )
    }

    private fun Legislature.moveListSeat(
        list: RankedMandateList?,
        former: String,
        successor: String?,
    ): Legislature {
        if (list == null) return this
        val members = list.seated - former
        return copy(rankedLists = rankedLists.map { if (it == list) it.copy(seated = members + listOfNotNull(successor)) else it })
    }

    private fun initialDistricts(
        group: ParliamentaryGroup,
        election: LegislativeElectionRule?,
        chamber: Legislature,
    ): Int =
        when (rule.route) {
            MandateRoute.DISTRICT -> {
                group.directSeats
            }

            MandateRoute.MIXED -> {
                val total = if (id == "UKR") (chamber.filledSeats * 199 / 424) else election?.constituencySeats ?: 0
                val allocations =
                    ElectionModel.allocateSeats(
                        chamber.groups.associate {
                            it.name to it.directSeats.toDouble()
                        },
                        ElectoralSystem.PROPORTIONAL,
                        total,
                    )
                allocations.getValue(group.name).coerceAtMost(group.directSeats)
            }

            else -> {
                0
            }
        }

    private fun ParliamentaryGroup.minusMember(
        district: Boolean,
        reserved: Boolean,
    ) = copy(
        seats = seats - 1,
        reservedSeats = reservedSeats - if (reserved) 1 else 0,
        districtMandates = districtMandates?.minus(if (district) 1 else 0),
    )

    private fun ParliamentaryGroup.plusMember(
        district: Boolean,
        reserved: Boolean,
    ) = copy(
        seats = seats + 1,
        reservedSeats = reservedSeats + if (reserved) 1 else 0,
        districtMandates = districtMandates?.plus(if (district) 1 else 0),
    )

    private fun transfer(
        chamber: Legislature,
        old: ParliamentaryGroup,
        party: ParliamentaryGroup,
        district: Boolean,
        reserved: Boolean,
    ): MandateChange {
        val members =
            chamber.groups.map { p ->
                when (p) {
                    old -> p.minusMember(district, reserved)
                    party -> p.plusMember(district, reserved)
                    else -> p
                }
            }
        return MandateChange(chamber.withGroups(members), listOf("${old.name} 의원 1명이 ${party.name}에 입당했다. 의석은 새 소속 정당으로 이동했다."))
    }

    private fun replacementDate(
        method: Replacement,
        date: LocalDate,
        general: LocalDate?,
    ): LocalDate? {
        if (method == Replacement.DEFERRED || method == Replacement.NATIONAL_POLL || method == Replacement.COMMUNITY_POLL) return null
        if (id == "KOR" && method == Replacement.LIST) {
            val year = date.year + Math.floorMod(2024 - date.year, 4)
            val may = LocalDate.of(year, 5, 29)
            val termEnd = if (date > may) may.plusYears(4) else may
            if (date >= termEnd.minusDays(120)) return null
        }
        if (method !=
            Replacement.DISTRICT
        ) {
            return date
                .plusDays(
                    if (method ==
                        Replacement.DELEGATION
                    ) {
                        21
                    } else {
                        3
                    },
                ).takeIf { general == null || it < general }
        }
        if (general == null || date.plusDays(rule.lastDays) >= general) return null
        val poll =
            when (id) {
                "KOR" -> {
                    // 2024년 국회의원 보궐은 매년 4월 첫 수요일. 3월 1일 이후 확정분은 다음 차수로 넘긴다.
                    val year = if (date < LocalDate.of(date.year, 3, 1)) date.year else date.year + 1
                    LocalDate.of(year, 4, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.WEDNESDAY))
                }

                "JPN" -> {
                    val next =
                        listOf(LocalDate.of(date.year, 3, 15), LocalDate.of(date.year, 9, 15), LocalDate.of(date.year + 1, 3, 15)).first {
                            date <=
                                it
                        }
                    next.plusMonths(1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.SUNDAY))
                }

                else -> {
                    date.plusDays(rule.days)
                }
            }
        return poll.takeIf { it < general }
    }

    fun fill(
        chamber: Legislature,
        date: LocalDate,
        shares: () -> Map<String, Double>,
        random: Random,
        districts: List<DistrictResult> = emptyList(),
        nationalElection: Boolean = false,
        regionalSwing: (String) -> Double = { 0.0 },
    ): MandateChange? {
        val due =
            chamber.vacancies.filter {
                it.due?.let { day -> day <= date } == true ||
                    (nationalElection && (it.method == Replacement.NATIONAL_POLL || it.method == Replacement.COMMUNITY_POLL))
            }
        if (due.isEmpty()) return null
        val votes = if (due.any { it.method == Replacement.DISTRICT || it.method == Replacement.NATIONAL_POLL }) shares() else emptyMap()
        var roster = districts
        var groups = chamber.groups
        var lists = chamber.rankedLists
        var exhausted = 0
        val news = mutableListOf<String>()
        for (vacancy in due) {
            val former = groups.find { it.modelName == vacancy.partyKey }
            val preferential =
                if (id == "AUS" &&
                    vacancy.method == Replacement.DISTRICT
                ) {
                    AustralianHouse.byElection(vacancy.region, votes)
                } else {
                    null
                }
            val incoming =
                if (vacancy.listName != null) {
                    val original = lists.single { it.name == vacancy.listName }
                    val successorList =
                        if (id == "LTU" && original.remaining.isEmpty()) lists.firstOrNull { it.remaining.isNotEmpty() } else original
                    val next = successorList?.remaining?.firstOrNull()
                    if (successorList == null || next == null) {
                        exhausted++
                        news += "${original.name} 명부의 승계 가능 후보가 없어 한 석을 공석으로 유지한다."
                        continue
                    }
                    val updated = successorList.copy(seated = successorList.seated + next, remaining = successorList.remaining.drop(1))
                    lists = lists.map { if (it == successorList) updated else it }
                    if (successorList != original) {
                        val index = lists.indexOf(updated)
                        lists = lists.drop(index + 1) + lists.take(index + 1)
                    }
                    groups.find { it.name == next } ?: successorList.nominees.single { it.name == next }.also { groups = groups + it }
                } else if (preferential != null) {
                    groups.find { it.name == preferential }
                        ?: ParliamentaryGroup(preferential, 0, PartyRole.OPPOSITION, districtMandates = 0).also { groups = groups + it }
                } else if (vacancy.method == Replacement.DISTRICT || vacancy.method == Replacement.NATIONAL_POLL) {
                    val contenders = groups.filter { (votes[it.name] ?: 0.0) > 0 }
                    contenders.maxBy { p ->
                        // 전국 지지율에 기존 지역 기반을 가중한다. 실제 선거구 자료가 없는 보궐의 지역 차이는 표본 추계다.
                        val incumbent = if (p == former) 1.5 else 1.0
                        val district =
                            vacancy.regionId?.let { id -> DistrictElectorate.localAffinity(id, vacancy.districtIndex ?: 0, p.modelName) }
                                ?: 0.5
                        val swing =
                            vacancy.regionId
                                ?.let(regionalSwing)
                                ?.div(50.0)
                                ?.coerceIn(-0.6, 0.6) ?: 0.0
                        val alignment =
                            when (p.role) {
                                PartyRole.LEAD, PartyRole.COALITION -> 1.0
                                PartyRole.OPPOSITION -> -0.5
                                else -> 0.0
                            }
                        val local = incumbent * (0.5 + district) * (1.0 + swing * alignment)
                        (votes.getValue(p.name) * local) / -ln(random.nextDouble().coerceIn(1e-9, 1.0 - 1e-9))
                    }
                } else {
                    requireNotNull(former) { "Missing original mandate for ${vacancy.partyKey}" }
                }
            groups = groups.map { if (it == incoming) it.plusMember(vacancy.districtSeat, vacancy.reservedSeat) else it }
            roster = roster.replaceHolder(vacancy.regionId, vacancy.districtIndex, incoming.name)
            news += "${vacancy.region?.let { "$it " } ?: ""}${vacancy.method.label} · ${incoming.name} 1석"
        }
        return MandateChange(
            chamber.copy(
                groups = groups,
                filledSeats = chamber.filledSeats + due.size - exhausted,
                unassignedVacancies = chamber.unassignedVacancies + exhausted,
                rankedLists = lists,
                vacancies =
                    chamber.vacancies - due.toSet(),
            ),
            news,
            roster,
        )
    }

    companion object {
        private val rules by lazy {
            val resource = requireNotNull(ParliamentaryMandates::class.java.getResourceAsStream("/world/parliamentary_mandates.json"))
            resource.bufferedReader().use { Json.decodeFromString<Map<String, MandateRule>>(it.readText()) }
        }

        fun forCountry(id: String): ParliamentaryMandates? = rules[id]?.let { ParliamentaryMandates(id, it) }
    }
}
