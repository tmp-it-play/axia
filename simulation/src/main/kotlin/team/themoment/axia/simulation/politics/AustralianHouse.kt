package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 연방 하원의 1인 선거구 완전 선호투표. AEC의 실제 후보별 1순위 득표와 탈락표 이전을 기초로 삼는다. */
internal object AustralianHouse {
    @Serializable
    private data class Returns(
        val source: String,
        val redistribution: String,
        val baseline: Map<String, Int>,
        val divisions: List<Division>,
    )

    @Serializable
    private data class Division(
        val name: String,
        val region: String,
        val parties: List<String>,
        val first: List<Int>,
        /** 2022년 해당 후보가 탈락했을 때 남은 후보들에 실제로 배분된 표. */
        val transfers: List<List<Int>>,
    )

    private val returns by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/australian_divisions.json")).bufferedReader().use {
            Json.decodeFromString<Returns>(it.readText())
        }
    }

    val baseline by lazy {
        val total =
            returns.baseline.values
                .sum()
                .toDouble()
        returns.baseline.mapValues { it.value / total }
    }

    /** 원외 실존 정당도 자신의 후보가 출마한 선거구에서 당선될 수 있다. */
    fun contenders(groups: List<ParliamentaryGroup>): List<ParliamentaryGroup> {
        val present = groups.map { it.modelName }.toSet()
        return groups + baseline.keys.filterNot { it in present }.map { ParliamentaryGroup(it, 0, PartyRole.OPPOSITION) }
    }

    fun elect(votes: MixedVotes): LegislativeSeats {
        val display = votes.shares.keys.associateBy(votes::name)
        val newcomers = votes.shares.keys.filter { votes.name(it) !in baseline }
        val districts =
            returns.divisions.groupBy { it.region }.map { (region, divisions) ->
                val winners = divisions.map { count(it, votes, display, newcomers) }
                DistrictResult(region, votes.regionName(region), winners, divisions.map { it.name })
            }
        val winners = districts.flatMap { it.winners }.groupingBy { it }.eachCount()
        val seats = votes.shares.mapValues { winners[it.key] ?: 0 }
        return LegislativeSeats(seats, districts = districts, districtMandates = seats)
    }

    fun byElection(
        name: String?,
        shares: Map<String, Double>,
    ): String? {
        val division = returns.divisions.find { it.name == name } ?: return null
        val votes = MixedVotes(shares)
        val display = shares.keys.associateBy(votes::name)
        return count(division, votes, display, shares.keys.filter { votes.name(it) !in baseline })
    }

    private fun count(
        division: Division,
        votes: MixedVotes,
        display: Map<String, String>,
        newcomers: List<String>,
    ): String {
        val entrants = if (division.parties.none { it in display } && newcomers.isEmpty()) votes.shares.keys.toList() else newcomers
        val owners = division.parties.map { display[it] } + entrants
        val formal = division.first.sum()
        val totals =
            division.first
                .mapIndexed { index, first ->
                    val key = division.parties[index]
                    val party = display[key]
                    when {
                        party == null -> 0.0
                        else -> first * votes.shares.getValue(party) / baseline.getValue(key) * votes.regionalSwing(party, division.region)
                    }
                }.toMutableList()
        entrants.forEach { party ->
            val presence = 0.25 + 1.5 * votes.affinity(division.name, 0, party)
            totals += formal * votes.shares.getValue(party) * votes.regionalSwing(party, division.region) * presence
        }
        val active = totals.indices.filter { owners[it] != null }.toMutableList()
        val majority = totals.sum() / 2.0
        while (active.size > 1) {
            val top = active.maxBy { totals[it] }
            if (totals[top] > majority) return requireNotNull(owners[top])
            val lowest = active.minBy { totals[it] }
            active.remove(lowest)
            val prior = division.transfers.getOrNull(lowest)
            val observed = active.sumOf { prior?.getOrNull(it) ?: 0 }
            val weights =
                active.map { candidate ->
                    if (observed > 0) {
                        (prior?.getOrNull(candidate) ?: 0).toDouble()
                    } else {
                        val party = requireNotNull(owners[candidate])
                        // 가상 탈락 순서에서 관측되지 않은 선호는 잔여 후보의 현 득표와 지역 기반으로 추계한다.
                        (1.0 + totals[candidate]) * (0.5 + votes.affinity(division.name, lowest, party))
                    }
                }
            val sum = weights.sum()
            active.forEachIndexed { i, candidate -> totals[candidate] += totals[lowest] * weights[i] / sum }
        }
        return requireNotNull(owners[active.single()])
    }
}
