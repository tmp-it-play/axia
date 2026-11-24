package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** 제12대 마즐레스. 유권자는 선거구 정원만큼 기표하며 20% 기준의 분모는 유권자의 투표용지 수다. */
internal object IranianAssembly {
    const val MINORITIES = "종교 소수자 대표"

    @Serializable
    internal data class District(
        val region: String,
        val name: String,
        val seats: Int,
    )

    internal data class Candidate(
        val party: String,
        val first: Double,
    )

    internal data class Ballot(
        val district: District,
        val elected: List<String>,
        val finalists: List<Candidate>,
    )

    internal data class Count(
        val vote: Map<String, Double>,
        val ballots: List<Ballot>,
        val firstApproval: Double,
    ) {
        val settled: Int get() = ballots.sumOf { it.elected.size }

        fun result(
            approval: Double,
            lead: String?,
            salt: Int,
        ): LegislativeSeats {
            val rows =
                ballots.groupBy { it.district.region }.map { (region, ballots) ->
                    val winners = mutableListOf<String>()
                    val labels = mutableListOf<String>()
                    for (ballot in ballots) {
                        val needed = ballot.district.seats - ballot.elected.size
                        val runoff =
                            if (needed == 0) {
                                emptyList()
                            } else {
                                ballot.finalists
                                    .withIndex()
                                    .sortedByDescending { (index, candidate) ->
                                        val swing = if (candidate.party == lead) 1.0 + (approval - firstApproval) / 100.0 else 1.0
                                        val field = DistrictElectorate.localAffinity(ballot.district.name, index + salt, candidate.party)
                                        candidate.first * swing * (0.85 + 0.3 * field)
                                    }.take(needed)
                                    .map { it.value.party }
                            }
                        winners.addAll(ballot.elected + runoff)
                        repeat(ballot.district.seats) { labels += ballot.district.name }
                    }
                    DistrictResult(
                        region,
                        if (region ==
                            "minorities"
                        ) {
                            "종교 공동체"
                        } else {
                            MixedVotes(emptyMap()).regionName(region)
                        },
                        winners,
                        labels,
                    )
                }
            val won =
                rows
                    .filterNot { it.regionId == "minorities" }
                    .flatMap { it.winners }
                    .groupingBy { it }
                    .eachCount()
            return LegislativeSeats(vote.keys.associateWith { won[it] ?: 0 }, mapOf(MINORITIES to 5), rows, won)
        }

        fun renamed(rename: PartyRename): Count =
            copy(
                vote = vote.mapKeys { rename.label(it.key) },
                ballots =
                    ballots.map { ballot ->
                        ballot.copy(
                            elected = ballot.elected.map(rename::label),
                            finalists = ballot.finalists.map { it.copy(party = rename.label(it.party)) },
                        )
                    },
            )
    }

    @Serializable
    private data class Boundaries(
        val source: String,
        val districts: List<District>,
    )

    private val boundaries by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/iranian_constituencies.json")).bufferedReader().use {
            Json.decodeFromString<Boundaries>(it.readText()).districts
        }
    }

    fun contenders(parties: List<ParliamentaryGroup>): List<ParliamentaryGroup> =
        if (parties.any { it.name == MINORITIES }) parties else parties + ParliamentaryGroup(MINORITIES, 0, PartyRole.OTHER)

    fun count(
        votes: MixedVotes,
        approval: Double,
    ): Count {
        val shares = votes.shares + (MINORITIES to 0.0)
        val general = shares.filter { (name, share) -> name != MINORITIES && share > 0.0 }
        val ballots =
            boundaries.map { district ->
                if (district.region == "minorities") {
                    Ballot(district, listOf(MINORITIES), emptyList())
                } else {
                    val field = mutableListOf<Candidate>()
                    for ((party, share) in general) {
                        val local = votes.regionalSwing(party, district.region) * (0.2 + 1.6 * votes.affinity(district.name, -1, party))
                        // 승인된 후보의 수와 같은 계열 안의 개인 득표는 원자료가 없어 선거구별로 추계한다.
                        val count =
                            (district.seats + general.size - 1) / general.size +
                                ((district.seats + 3) * votes.affinity(district.name, -2, party)).toInt()
                        val weights = List(count) { i -> 0.12 + votes.affinity(district.name, i, party) }
                        val sum = weights.sum()
                        weights.forEach { field += Candidate(party, share * local * it / sum) }
                    }
                    val total = field.sumOf { it.first }
                    val marks = 1.0 + (district.seats - 1) * 0.7
                    val ranking =
                        field
                            .map {
                                it.copy(
                                    first = (it.first / total * marks).coerceAtMost(1.0),
                                )
                            }.sortedByDescending { it.first }
                    val elected = ranking.take(district.seats).takeWhile { it.first >= 0.2 }
                    val remaining = district.seats - elected.size
                    Ballot(district, elected.map { it.party }, ranking.drop(elected.size).take(remaining * 2))
                }
            }
        return Count(shares, ballots, approval)
    }

    fun secondRound(first: LocalDate): LocalDate = if (first == LocalDate.of(2024, 3, 1)) LocalDate.of(2024, 5, 10) else first.plusWeeks(8)
}
