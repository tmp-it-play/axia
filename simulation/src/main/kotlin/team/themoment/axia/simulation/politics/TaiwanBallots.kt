package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object TaiwanBallots {
    @Serializable
    private data class Candidate(
        val party: String,
        val votes: Int,
    )

    @Serializable
    private data class Ballots(
        val lists: Map<String, Int>,
        val districts: Map<String, List<Candidate>>,
    )

    private val ballots by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/taiwan_ballots.json")).bufferedReader().use {
            Json { ignoreUnknownKeys = true }.decodeFromString<Ballots>(it.readText())
        }
    }

    val baseline: Map<String, Double> by lazy {
        val independent =
            ballots.districts
                .filterKeys { "/" in it }
                .values
                .flatten()
                .filter { it.party == "무소속" }
                .sumOf { it.votes }
        val weights = ballots.lists + ("무소속" to independent)
        val sum = weights.values.sum().toDouble()
        weights.mapValues { it.value / sum }
    }

    fun local(
        votes: MixedVotes,
        region: String,
        index: Int,
    ): String = count(votes, "$region/${index + 1}", region, 1).single()

    fun indigenous(votes: MixedVotes): List<DistrictResult> =
        listOf("평지 원주민", "산지 원주민").map { area ->
            DistrictResult("TWN-$area", area, count(votes, area, area, 3))
        }

    /** 각 후보에게 한 표를 주는 단기비이양식 선거. 같은 정당 후보와 무소속 후보도 각각 경쟁한다. */
    private fun count(
        votes: MixedVotes,
        district: String,
        region: String,
        seats: Int,
    ): List<String> {
        val field = ballots.districts.getValue(district)
        val valid = field.sumOf { it.votes }
        val candidates =
            votes.shares.flatMap { (party, share) ->
                val original = votes.name(party)
                val actual = field.filter { it.party == original }
                val base = baseline[original]
                val swing = votes.regionalSwing(party, region)
                if (actual.isNotEmpty() && base != null) {
                    actual.map { party to (it.votes * share / base * swing) }
                } else {
                    val local = votes.affinity(district, 0, party)
                    listOf(party to (share * swing * valid * (0.35 + local) * if (base == null) 1.0 else 0.02))
                }
            }
        // 신규 단일 정당에 후보가 모인 경우에도 복수 정원 선거를 구성한다.
        val complete =
            if (candidates.size >= seats) {
                candidates
            } else {
                candidates.flatMap { (p, weight) ->
                    List(seats) { i -> p to weight / (i + 1) }
                }
            }
        return complete.sortedByDescending { it.second }.take(seats).map { it.first }
    }
}
