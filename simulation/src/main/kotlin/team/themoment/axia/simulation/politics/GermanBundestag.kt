package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 제1표 1위 후보는 같은 정당·주의 제2표 의석이 뒷받침하는 득표율 순서로 입성한다. */
internal object GermanBundestag {
    @Serializable
    private data class Ballots(
        val parties: List<String>,
        val districts: List<District>,
    )

    @Serializable
    private data class District(
        val number: Int,
        val name: String,
        val region: String,
        val first: List<Int>,
        val second: List<Int>,
    )

    private data class FirstPlace(
        val district: District,
        val party: String,
        val share: Double,
    )

    private val ballots by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/german_districts.json")).bufferedReader().use {
            Json { ignoreUnknownKeys = true }.decodeFromString<Ballots>(it.readText())
        }
    }
    private val prior by lazy {
        ballots.parties.mapIndexed { index, name -> name to ballots.districts.sumOf { it.second[index] }.toDouble() }.toMap()
    }
    val listBaseline by lazy { prior.mapValues { it.value / prior.values.sum() } }
    private val columns by lazy { ballots.parties.withIndex().associate { it.value to it.index } }

    fun elect(votes: MixedVotes): LegislativeSeats {
        val first = mutableListOf<FirstPlace>()
        val secondByState = mutableMapOf<String, MutableMap<String, Double>>()
        for (district in ballots.districts) {
            val firstWeights = mutableMapOf<String, Double>()
            val second = secondByState.getOrPut(district.region) { mutableMapOf() }
            for ((party, share) in votes.shares) {
                val name = votes.name(party)
                val column = columns[name]
                val swing = votes.regionalSwing(party, district.region)
                if (column == null) {
                    val local = votes.affinity(district.region, district.number, party)
                    firstWeights[party] = share * swing * district.first.sum() * (0.3 + 1.4 * local)
                    if (!votes.independent(party)) second[party] = (second[party] ?: 0.0) + share * swing * district.second.sum()
                } else {
                    val shift = share / listBaseline.getValue(name) * swing
                    firstWeights[party] = district.first[column] * shift
                    second[party] = (second[party] ?: 0.0) + district.second[column] * shift
                }
            }
            val winner = firstWeights.maxBy { it.value }
            first += FirstPlace(district, winner.key, winner.value / firstWeights.values.sum())
        }
        val firstCounts = first.groupingBy { it.party }.eachCount()
        val independent = first.filter { votes.independent(it.party) }
        val second = votes.shares.keys.associateWith { p -> secondByState.values.sumOf { it[p] ?: 0.0 } }
        val totalVotes = second.values.sum()
        val eligible =
            second.filter { (p, count) ->
                count > 0.0 && !votes.independent(p) &&
                    (count >= totalVotes * 0.05 || (firstCounts[p] ?: 0) >= 3 || votes.name(p) == "남슐레스비히 유권자연합")
            }
        val entitlements = MixedMemberElections.divisors(eligible, 630 - independent.size, sainteLague = true)
        val seated = independent.toMutableSet()
        val lists = mutableMapOf<String, MutableMap<String, Int>>()
        for ((p, n) in entitlements) {
            val states = secondByState.mapValues { it.value[p] ?: 0.0 }.filterValues { it > 0.0 }
            val guaranteed = MixedMemberElections.divisors(states, n, sainteLague = true)
            for ((state, seats) in guaranteed) {
                val covered = first.filter { it.party == p && it.district.region == state }.sortedByDescending { it.share }.take(seats)
                seated += covered
                lists.getOrPut(votes.regionName(state)) { mutableMapOf() }[p] = seats - covered.size
            }
        }
        val rows =
            first.groupBy { it.district.region }.map { (region, items) ->
                val elected = items.filter { it in seated }
                DistrictResult(
                    region,
                    votes.regionName(region),
                    elected.map { it.party },
                    elected.map { "${it.district.number} ${it.district.name}" },
                    items.filter { it !in seated }.associate { "${it.district.number} ${it.district.name}" to it.party },
                )
            }
        val seats = votes.shares.keys.associateWith { p -> (entitlements[p] ?: 0) + independent.count { it.party == p } }
        return LegislativeSeats(seats, districts = rows, districtMandates = seated.groupingBy { it.party }.eachCount(), listSeats = lists)
    }
}
