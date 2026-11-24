package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.ElectoralRegion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.ln

@Serializable
private data class FrenchDistrict(
    val code: String,
    val region: String,
    val area: String,
    val registered: Int,
    val valid: Int,
    val votes: Map<String, Int>,
)

data class LegislativeRunoff(
    val firstRound: LocalDate,
    val nextRound: LocalDate,
    val settled: Int,
    val contested: Int,
)

/** 2022년 선거구별 등록자·유효표·정치 계열별 득표. 같은 계열의 정당은 지역별 공동후보를 추정한다. */
internal object FrenchAssembly {
    private val data: List<FrenchDistrict> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/france_districts.json"))
            .bufferedReader()
            .use {
                Json.decodeFromString<List<FrenchDistrict>>(it.readText())
            }.also { rows -> require(rows.size == 577 && rows.distinctBy { it.code }.size == 577) }
    }
    private val alliances =
        mapOf(
            "르네상스" to "ENS",
            "민주운동·무소속" to "ENS",
            "오리종·동맹" to "ENS",
            "굴복하지 않는 프랑스" to "NUP",
            "사회당·동맹" to "NUP",
            "생태주의" to "NUP",
            "민주공화좌파" to "NUP",
            "공화당" to "LR",
            "국민연합" to "RN",
            "LIOT" to "REG",
            "무소속" to "DIV",
        )
    private val baseline by lazy {
        val valid = data.sumOf { it.valid }.toDouble()
        data.flatMap { it.votes.entries }.groupBy { it.key }.mapValues { (_, rows) -> rows.sumOf { it.value } / valid }
    }

    fun earlyElection(dissolved: LocalDate): LocalDate = dissolved.plusDays(20).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    data class Count(
        val vote: Map<String, Double>,
        val seats: LegislativeSeats,
        val settled: Int,
        private val runoffVotes: Map<String, Map<String, Double>>,
        private val firstApproval: Map<String, Double>,
        private val nationalApproval: Double,
        private val government: Set<String>,
        private val modelNames: Map<String, String>,
    ) {
        fun renamed(change: PartyRename): Count =
            copy(
                vote = vote.mapKeys { change.label(it.key) },
                seats =
                    seats.copy(
                        direct = seats.direct.mapKeys { change.label(it.key) },
                        districtMandates = seats.districtMandates.mapKeys { change.label(it.key) },
                        districts = seats.districts.map { it.copy(winners = it.winners.map(change::label)) },
                    ),
                runoffVotes = runoffVotes.mapValues { (_, votes) -> votes.mapKeys { change.label(it.key) } },
                government = government.mapTo(mutableSetOf(), change::label),
                modelNames = modelNames.mapKeys { change.label(it.key) },
            )

        /** 1차 당선자는 유지하고 결선 진출자 사이에서 당일 여론·참여 변동을 반영한다. */
        fun secondBallot(
            approval: Double,
            regions: List<ElectoralRegion>,
            ballotSeed: Int,
        ): LegislativeSeats {
            val current = regions.associate { it.id to it.approval }
            val districts =
                seats.districts.map { row ->
                    val shift = ((current[row.regionId] ?: approval) - (firstApproval[row.regionId] ?: nationalApproval)) / 35.0
                    row.copy(
                        winners =
                            row.constituencies.mapIndexed { i, code ->
                                runoffVotes[code]
                                    ?.maxBy { (party, support) ->
                                        val participation =
                                            0.96 + 0.08 * DistrictElectorate.localAffinity(code, ballotSeed, modelNames[party] ?: party)
                                        support * participation * (if (party in government) exp(shift) else 1.0)
                                    }?.key ?: row.winners[i]
                            },
                    )
                }
            val won = districts.flatMap { it.winners }.groupingBy { it }.eachCount()
            return seats.copy(direct = vote.keys.associateWith { won[it] ?: 0 }, districts = districts, districtMandates = won)
        }
    }

    fun count(
        shares: Map<String, Double>,
        regions: List<ElectoralRegion> = emptyList(),
        roles: Map<String, PartyRole> = emptyMap(),
        approval: Double = 50.0,
        modelNames: Map<String, String> = emptyMap(),
    ): Count {
        val blocs = shares.filterValues { it > 0.0 }.keys.groupBy { party -> alliances[modelNames[party] ?: party] ?: party }
        val local = regions.associateBy { it.id }
        val byRegion = mutableListOf<DistrictResult>()
        val national = mutableMapOf<String, Double>()
        val runoffVotes = mutableMapOf<String, Map<String, Double>>()
        var settled = 0
        for ((region, districts) in data.groupBy { it.region }) {
            val swing = ((local[region]?.approval ?: approval) - approval) / 35.0
            val winners =
                districts.map { district ->
                    val weights =
                        blocs.entries.associate { (bloc, parties) ->
                            fun localSupport(party: String): Double {
                                val incumbent = if (roles[party] in setOf(PartyRole.LEAD, PartyRole.COALITION)) exp(swing) else 1.0
                                return shares.getValue(party) * incumbent
                            }
                            val candidate =
                                parties.maxBy {
                                    localSupport(it) /
                                        -ln(DistrictElectorate.localAffinity(district.code, 0, modelNames[it] ?: it))
                                }
                            val baselineShare = baseline[bloc]
                            val lean =
                                if (baselineShare ==
                                    null
                                ) {
                                    0.15 - ln(DistrictElectorate.localAffinity(district.code, 1, modelNames[candidate] ?: candidate))
                                } else {
                                    ((district.votes[bloc] ?: 0) + 0.008 * district.valid) / (district.valid * (baselineShare + 0.008))
                                }
                            candidate to parties.sumOf(::localSupport) * lean
                        }
                    val total = weights.values.sum()
                    val ranking = weights.entries.sortedByDescending { it.value }
                    val first = ranking.first()
                    val validPerRegistered = district.valid.toDouble() / district.registered
                    weights.forEach { (party, weight) ->
                        national[party] =
                            national.getOrDefault(party, 0.0) + district.valid * weight / total
                    }
                    if (first.value > total / 2 && first.value / total * validPerRegistered >= 0.25) {
                        settled++
                        first.key
                    } else {
                        val qualified = ranking.filter { it.value / total * validPerRegistered >= 0.125 }
                        val finalists = if (qualified.size >= 2) qualified else ranking.take(2)
                        val final = finalists.associate { it.key to it.value }.toMutableMap()
                        ranking.filter { it.key !in final }.forEach { voter ->
                            val preferences =
                                finalists.associate { candidate ->
                                    val affinity =
                                        exp(
                                            -3.0 *
                                                ideologicalDistance(
                                                    partyIdentity(modelNames[voter.key] ?: voter.key, null),
                                                    partyIdentity(modelNames[candidate.key] ?: candidate.key, null),
                                                ),
                                        )
                                    candidate.key to affinity * candidate.value
                                }
                            val weight = preferences.values.sum()
                            preferences.forEach { (party, points) -> final[party] = final.getValue(party) + voter.value * points / weight }
                        }
                        runoffVotes[district.code] = final
                        final.maxBy { it.value }.key
                    }
                }
            byRegion += DistrictResult(region, districts.first().area, winners, districts.map { it.code })
        }
        val won = byRegion.flatMap { it.winners }.groupingBy { it }.eachCount()
        val valid = data.sumOf { it.valid }.toDouble()
        return Count(
            shares.mapValues { national.getOrDefault(it.key, 0.0) / valid },
            LegislativeSeats(
                shares.keys.associateWith {
                    won[it]
                        ?: 0
                },
                districts = byRegion,
                districtMandates = won,
            ),
            settled,
            runoffVotes,
            regions.associate { it.id to it.approval },
            approval,
            roles
                .filterValues {
                    it ==
                        PartyRole.LEAD ||
                        it == PartyRole.COALITION
                }.keys,
            modelNames,
        )
    }
}
