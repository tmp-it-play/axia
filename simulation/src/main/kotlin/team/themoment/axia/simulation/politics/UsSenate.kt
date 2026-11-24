package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.ElectoralRegion
import java.time.LocalDate
import kotlin.math.exp

data class SenateReturn(
    val state: String,
    val cohort: Int,
    val winner: String,
    val seating: LocalDate,
    val special: Boolean,
)

/** 상원 100석은 주와 임기군에 귀속된다. 선거가 없는 의석은 기존 당적을 유지한다. */
internal class UsSenate {
    private val alignedIndependent = "무소속 · 민주당 원내제휴"

    private data class Seat(
        val state: String,
        val cohort: Int,
    )

    private data class Return(
        val date: LocalDate,
        val seat: Seat,
        val party: String,
    )

    private val seats =
        ElectoralAreas.default.states
            .flatMap { state ->
                state.senateClasses.map { (cohort, party) ->
                    Seat(state.code, cohort) to
                        when (party) {
                            "D" -> "민주당"
                            "R" -> "공화당"
                            "I" -> alignedIndependent
                            else -> error("알 수 없는 상원 당적 $party")
                        }
                }
            }.toMap()
            .toMutableMap()
    private var pending = emptyList<Return>()
    var lastElection: List<SenateReturn> = emptyList()
        private set

    fun elect(
        date: LocalDate,
        shares: Map<String, Double>,
        parties: List<ParliamentaryGroup>,
        government: String?,
        regions: List<ElectoralRegion>,
        approval: Double,
    ) {
        val cohort = Math.floorMod((date.year - 2024) / 2, 3) + 1
        val regional = regions.associateBy { it.id }
        val modelNames = parties.associate { it.name to it.modelName }
        val returns = mutableListOf<Return>()
        val report = mutableListOf<SenateReturn>()
        for (state in ElectoralAreas.default.states) {
            val odds = state.demVotes.toDouble() / state.repVotes
            val swing = ((regional[state.region]?.approval ?: approval) - approval) / 35.0
            for (group in state.senateClasses.keys) {
                val special = date.year == 2024 && ((state.code == "NE" && group == 2) || (state.code == "CA" && group == 1))
                if (group != cohort && !special) continue
                val seat = Seat(state.code, group)
                val incumbent = seats.getValue(seat)
                val weights =
                    shares
                        .filterValues { it > 0 }
                        .mapValues { (party, share) ->
                            val local =
                                if (party == "민주당" || party == "공화당") {
                                    RegionalElections.partyFactor(party, odds)
                                } else {
                                    exp(2.4 * (DistrictElectorate.localAffinity(state.code, group, modelNames[party] ?: party) - 0.5))
                                }
                            val defending = party == incumbent || (party == "무소속" && incumbent == alignedIndependent)
                            share * local * (if (defending) 1.12 else 1.0) *
                                (if (party == government) exp(swing) else 1.0)
                        }.toMutableMap()
                if (incumbent == alignedIndependent) {
                    // 시작 무소속 세 의원은 상원 조직 구성에서 민주당과 제휴한다. 재출마·주내 지지 이전은 게임 추정이다.
                    val caucus = weights["민주당"] ?: 0.0
                    val retained = if (state.code == "AZ") 0.35 else 0.80
                    weights["무소속"] = (weights["무소속"] ?: 0.0) + retained * caucus
                    weights["민주당"] = (1 - retained) * caucus
                }

                fun affiliation(winner: String) = if (winner == "무소속" && incumbent == alignedIndependent) alignedIndependent else winner
                val elected = affiliation(weights.maxBy { it.value }.key)
                if (special) {
                    val certified = LocalDate.of(2024, 12, if (state.code == "CA") 19 else 9)
                    val shortTerm =
                        affiliation(
                            weights
                                .maxBy { (party, support) ->
                                    support *
                                        (0.9 + 0.2 * DistrictElectorate.localAffinity(state.code, date.year, modelNames[party] ?: party))
                                }.key,
                        )
                    returns += Return(certified, seat, shortTerm)
                    report += SenateReturn(state.name, group, shortTerm, certified, true)
                }
                if (group == cohort) {
                    val newTerm = LocalDate.of(date.year + 1, 1, 3)
                    returns += Return(newTerm, seat, elected)
                    report += SenateReturn(state.name, group, elected, newTerm, false)
                }
            }
        }
        lastElection = report
        pending = returns.sortedBy { it.date }
    }

    fun seat(
        date: LocalDate,
        previous: UpperHouse?,
    ): UpperHouse? {
        if (pending.isEmpty() || date < pending.first().date) return null
        val (due, later) = pending.partition { it.date <= date }
        pending = later
        due.forEach { seats[it.seat] = it.party }
        val chamber = requireNotNull(previous)
        val roles = chamber.groups.associateBy { it.name }
        val groups =
            seats.values.groupingBy { it }.eachCount().map { (party, seats) ->
                roles[party]?.copy(seats = seats) ?: when (party) {
                    alignedIndependent -> UpperGroup(party, seats, roles["민주당"]?.role ?: PartyRole.OTHER, "민주당")
                    "무소속" -> UpperGroup(party, seats, PartyRole.OTHER)
                    else -> UpperGroup(party, seats, PartyRole.OPPOSITION, party)
                }
            }
        val gov = groups.filter { it.role in setOf(PartyRole.LEAD, PartyRole.COALITION, PartyRole.SUPPLY) }.sumOf { it.seats }
        return chamber.copy(
            groups = groups,
            governmentSeats = gov,
            oppositionSeats =
                groups
                    .filter {
                        it.role == PartyRole.OPPOSITION
                    }.sumOf { it.seats },
        )
    }

    fun renameParty(change: PartyRename) {
        seats.replaceAll { _, party -> change.label(party) }
        pending = pending.map { it.copy(party = change.label(it.party)) }
    }
}
