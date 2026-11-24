package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.ElectoralRegion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.sqrt

data class RegionalBallot(
    val name: String,
    val shares: Map<Int, Double>,
    val electors: Map<Int, Int> = emptyMap(),
)

data class RegionalCount(
    val areas: List<RegionalBallot>,
    val popular: List<CandidateVote>,
    val electors: Map<Int, Int> = emptyMap(),
    val qualifyingProvinces: Int? = null,
    val stateDelegations: Map<Int, Int> = emptyMap(),
)

@Serializable
internal data class HouseDistrict(
    val winner: String,
    val demVotes: Int,
    val repVotes: Int,
)

@Serializable
internal data class UsElectoralState(
    val code: String,
    val name: String,
    val region: String,
    val electors: Int,
    val demVotes: Int,
    val repVotes: Int,
    val total: Int,
    val presidentialDistricts: List<List<Int>> = emptyList(),
    val houseDistricts: List<HouseDistrict> = emptyList(),
    val senateClasses: Map<Int, String> = emptyMap(),
)

@Serializable
internal data class PrimaryAllocation(
    val code: String,
    val dem: Int,
    val rep: Int,
    val demDate: Int,
    val repDate: Int,
)

@Serializable
internal data class IndonesianProvince(
    val name: String,
    val region: String,
    val registered: Int,
)

@Serializable
internal data class IndonesianTicket(
    val name: String,
    val born: Int,
    val party: String? = null,
    val runningMate: String,
    val mateBorn: Int,
    val mateParty: String? = null,
    val endorsers: List<String>,
)

@Serializable
internal data class ElectoralAreas(
    val sourceNote: String,
    val sources: List<String>,
    val states: List<UsElectoralState>,
    val primaries: List<PrimaryAllocation>,
    val indonesia: List<IndonesianProvince>,
    val indonesianTickets: List<IndonesianTicket>,
) {
    init {
        require(states.size == 51 && states.distinctBy { it.code }.size == 51 && states.sumOf { it.electors } == 538)
        require(states.all { it.houseDistricts.size == (if (it.code == "DC") 0 else it.electors - 2) })
        require(states.flatMap { it.senateClasses.keys }.groupingBy { it }.eachCount() == mapOf(1 to 33, 2 to 33, 3 to 34))
        require(primaries.size == 57 && primaries.distinctBy { it.code }.size == 57)
        require(indonesia.size == 39 && indonesia.count { it.region.isNotEmpty() } == 38 && indonesia.all { it.registered > 0 })
    }

    companion object {
        val default: ElectoralAreas by lazy {
            val stream = requireNotNull(ElectoralAreas::class.java.getResourceAsStream("/world/electoral_areas.json"))
            stream.bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<ElectoralAreas>(it.readText()) }
        }
    }
}

internal fun usFederalElection(year: Int): LocalDate =
    LocalDate.of(year, 11, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)).plusDays(1)

internal object RegionalElections {
    private val data get() = ElectoralAreas.default
    private val nationalOdds by lazy { data.states.sumOf { it.demVotes }.toDouble() / data.states.sumOf { it.repVotes } }

    internal fun partyFactor(
        party: String?,
        odds: Double,
    ): Double =
        when (party) {
            "민주당" -> sqrt(odds / nationalOdds)
            "공화당" -> sqrt(nationalOdds / odds)
            else -> 1.0
        }

    /** 주의 2020년 대선·2022년 지역구의 양당 득표차에 게임의 전국·지역 민심 변화를 적용한다. */
    fun house(
        shares: Map<String, Double>,
        regions: List<ElectoralRegion> = emptyList(),
        approval: Double = 50.0,
        governmentParty: String? = null,
        modelNames: Map<String, String> = emptyMap(),
    ): List<DistrictResult> {
        val local = regions.associateBy { it.id }
        return data.states.filter { it.houseDistricts.isNotEmpty() }.map { state ->
            val swing = ((local[state.region]?.approval ?: approval) - approval) / 35.0
            val winners =
                state.houseDistricts.mapIndexed { index, district ->
                    val stateOdds = state.demVotes.toDouble() / state.repVotes
                    val odds =
                        if (district.demVotes > 0 && district.repVotes > 0) {
                            district.demVotes.toDouble() / district.repVotes
                        } else {
                            // 한 주요 정당이 출마하지 않은 지역구는 해당 주 득표와 당시 승자를 기준으로 한다.
                            if (district.winner == "D") maxOf(1.35, stateOdds) else minOf(1 / 1.35, stateOdds)
                        }
                    shares.keys.maxBy { party ->
                        val affinity =
                            if (party == "민주당" || party == "공화당") {
                                partyFactor(party, odds)
                            } else {
                                exp(2.4 * (DistrictElectorate.localAffinity(state.code, index, modelNames[party] ?: party) - 0.5))
                            }
                        shares.getValue(party) * affinity * if (party == governmentParty) exp(swing) else 1.0
                    }
                }
            DistrictResult(
                state.region,
                state.name,
                winners,
                winners.indices.map {
                    if (winners.size ==
                        1
                    ) {
                        "${state.code}-AL"
                    } else {
                        "${state.code}-${it + 1}"
                    }
                },
            )
        }
    }

    fun count(
        country: String,
        ballot: List<CandidateVote>,
        regions: List<ElectoralRegion>,
        approval: Double,
        incumbent: String?,
    ): RegionalCount {
        val local = regions.associateBy { it.id }

        fun localBallot(
            key: String,
            odds: Double?,
            region: String,
        ): Map<Int, Double> {
            val swing = ((local[region]?.approval ?: approval) - approval) / 35.0
            val weights =
                ballot.associate { (p, share) ->
                    val factor =
                        if (odds !=
                            null
                        ) {
                            partyFactor(p.party, odds)
                        } else {
                            exp(3.2 * (DistrictElectorate.localAffinity(key, 0, p.id.toString()) - 0.5))
                        }
                    p.id to share * factor * if (p.party != null && p.party == incumbent) exp(swing) else 1.0
                }
            val total = weights.values.sum()
            return weights.mapValues { it.value / total }
        }
        val counts = mutableListOf<Pair<RegionalBallot, Int>>()
        if (country == "USA") {
            data.states.forEach { state ->
                val shares = localBallot(state.code, state.demVotes.toDouble() / state.repVotes, state.region)
                val electors = mutableMapOf(shares.maxBy { it.value }.key to (state.electors - state.presidentialDistricts.size))
                state.presidentialDistricts.forEach { (dem, rep) ->
                    val winner = localBallot(state.code, dem.toDouble() / rep, state.region).maxBy { it.value }.key
                    electors[winner] = electors.getOrDefault(winner, 0) + 1
                }
                counts += RegionalBallot(state.name, shares, electors) to state.total
            }
        } else {
            require(country == "IDN")
            data.indonesia.forEach { p ->
                counts += RegionalBallot(p.name, localBallot(p.name, null, p.region)) to p.registered
            }
        }
        val total = counts.sumOf { it.second }.toDouble()
        val popular =
            ballot
                .map { v ->
                    CandidateVote(
                        v.person,
                        counts.sumOf { (r, n) ->
                            r.shares.getValue(v.person.id) * n
                        } / total,
                    )
                }.sortedByDescending { it.share }
        val rows = counts.map { it.first }
        val winner = popular.first().person.id
        val qualifying =
            if (country ==
                "IDN"
            ) {
                rows.zip(data.indonesia).count { (r, p) -> p.region.isNotEmpty() && r.shares.getValue(winner) >= 0.20 }
            } else {
                null
            }
        val electoral =
            if (country ==
                "USA"
            ) {
                ballot.associate { it.person.id to rows.sumOf { r -> r.electors.getOrDefault(it.person.id, 0) } }
            } else {
                emptyMap()
            }
        return RegionalCount(rows, popular, electoral, qualifying)
    }

    /** 각 주 하원 대표단은 한 표다. 대표단 내 과반을 얻지 못한 주와 D.C.는 집계에서 제외한다. */
    fun houseBallot(
        finalists: List<CandidateVote>,
        districts: List<DistrictResult>,
        round: Int,
    ): Map<Int, Int> {
        val states = data.states.associateBy { it.region }
        val votes = finalists.associate { it.person.id to 0 }.toMutableMap()
        for (district in districts) {
            val state = states.getValue(district.regionId)
            if (state.code == "DC" || district.winners.isEmpty()) continue
            val choices =
                district.winners
                    .mapIndexedNotNull { index, party ->
                        if (party.isBlank()) return@mapIndexedNotNull null
                        val own = finalists.find { it.person.party == party }
                        // 반복 표결에서는 일부 의원이 교착을 풀기 위해 주내 유력 후보 쪽으로 이동할 수 있다.
                        val partyVote = DistrictElectorate.localAffinity(state.code, index + round * 435, party)
                        if (own != null && (round == 1 || partyVote > minOf(0.45, 0.07 * (round - 1)))) {
                            own
                        } else {
                            finalists.maxBy {
                                it.share *
                                    DistrictElectorate.localAffinity(state.code, index + round, it.person.id.toString())
                            }
                        }
                    }.groupingBy { it.person.id }
                    .eachCount()
            val top = choices.maxByOrNull { it.value } ?: continue
            if (top.value * 2 > choices.values.sum()) votes[top.key] = votes.getValue(top.key) + 1
        }
        return votes
    }
}
