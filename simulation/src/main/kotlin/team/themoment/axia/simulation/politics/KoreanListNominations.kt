package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.roundToInt

internal object KoreanListNominations {
    private val firstElection = LocalDate.of(2024, 4, 10)
    private const val DEMOCRATIC = "더불어민주당"
    private const val PEOPLE_POWER = "국민의힘"
    private const val ALLIANCE = "더불어민주연합"
    private const val PEOPLE_FUTURE = "국민의미래"
    private val allies = listOf("진보당", "기본소득당", "사회민주당")
    private val unaffiliatedDistrictParties = setOf("국민주권당", "민중민주당", "한국국민당")

    @Serializable
    private data class FirstSlates(
        val sources: List<String>,
        val capacities: Map<String, Int>,
        val listOnly: Set<String>,
        val districtOnly: Set<String>,
        val allianceRanks: Map<Int, String>,
        val supportPoll: Map<String, Int>,
        val listIntention: Map<String, Map<String, Int>>,
    )

    private val first by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/korean_lists.json"))
            .bufferedReader()
            .use {
                Json.decodeFromString<FirstSlates>(it.readText())
            }.also {
                require(it.capacities.size == 38 && it.capacities.values.sum() == 253)
            }
    }

    private val firstParties by lazy {
        (first.capacities.keys - setOf(ALLIANCE, PEOPLE_FUTURE, "녹색정의당")) + first.districtOnly + allies + "정의당"
    }
    val protectedNames: Set<String> by lazy { firstParties + first.capacities.keys }

    fun contenders(
        parties: List<ParliamentaryGroup>,
        poll: LocalDate?,
    ): List<ParliamentaryGroup> {
        if (poll != firstElection) return parties
        val names = parties.map { it.modelName }.toSet()
        return parties + (firstParties - names).map { ParliamentaryGroup(it, 0, PartyRole.OPPOSITION) }
    }

    /** 공표일 이후 지지 정당 조사로 시나리오의 선거운동 출발점을 바꾼다. 미분류 1%를 나눠 쓴다. */
    fun campaignVote(
        poll: LocalDate?,
        today: LocalDate,
        previous: Map<String, Double>,
    ): Map<String, Double> {
        if (poll != firstElection || today < firstElection.minusDays(19)) return previous
        return firstCampaignVote
    }

    private val firstCampaignVote by lazy {
        val others = (firstParties + "무소속") - first.supportPoll.keys
        val weights = others.associateWith { 1.0 / others.size } + first.supportPoll.mapValues { it.value.toDouble() }
        val total = weights.values.sum()
        weights.mapValues { it.value / total } + ("한국의희망" to 0.0)
    }

    fun districtEligible(
        party: String,
        poll: LocalDate?,
    ): Boolean = poll != firstElection || (party !in first.listOnly && party !in setOf("사회민주당", "한국의희망"))

    fun register(
        election: LocalDate,
        groups: List<ParliamentaryGroup>,
        leadChoice: String?,
    ): List<RegisteredList> {
        val field = groups.filter { it.modelName != "무소속" }
        val historical = election == firstElection
        val byModel = field.associateBy { it.modelName }
        val lead = field.firstOrNull { it.role == PartyRole.LEAD }
        val total = groups.sumOf { it.seats }
        val result = mutableListOf<RegisteredList>()
        val claimed = mutableSetOf<String>()
        val taken = groups.mapTo(mutableSetOf()) { it.name }
        // 출마 당시 법적으로 다른 정당이다. 모당이 지역구에서 얻은 의석을 이 명부의 당선인으로 계산하지 않는다.
        for (party in field) {
            val satellite =
                if (leadChoice != null && party == lead) {
                    leadChoice == "위성정당"
                } else if (historical) {
                    party.modelName == DEMOCRATIC || party.modelName == PEOPLE_POWER
                } else {
                    total > 0 && party.seats * 4 >= total
                }
            if (!satellite) continue
            val partners =
                if (historical && party.modelName == DEMOCRATIC) {
                    allies.mapNotNull(byModel::get).filter { it != lead || leadChoice == null }
                } else {
                    emptyList()
                }
            val base =
                when {
                    historical && party.modelName == DEMOCRATIC -> ALLIANCE
                    historical && party.modelName == PEOPLE_POWER -> PEOPLE_FUTURE
                    else -> "${party.name} 비례연합"
                }
            val name = generateSequence(base) { "$it·비례" }.first { it !in taken }
            taken += name
            val members = listOf(party.name) + partners.map { it.name }
            val order =
                List(if (historical) first.capacities[base] ?: 46 else 46) { rank ->
                    val partner = if (base == ALLIANCE) first.allianceRanks[rank + 1]?.let(byModel::get)?.name else null
                    partner?.takeIf { it in members } ?: party.name
                }
            result += RegisteredList(name, members, order)
            claimed += members
        }
        for (party in field.filterNot { it.name in claimed }) {
            val model = party.modelName
            if (historical && (model == "한국의희망" || (model in unaffiliatedDistrictParties && party != lead))) continue
            val name = if (historical && model == "정의당") "녹색정의당" else party.name
            val capacity = if (historical) first.capacities[name] ?: 46 else 46
            result += RegisteredList(name, listOf(party.name), List(capacity) { party.name })
        }
        return result
    }

    /** 의석할당정당 판정에는 후보 명부 없이 지역구에서 5석 이상 얻은 모당도 포함한다. */
    fun allocation(
        listVotes: Map<String, Double>,
        direct: Map<String, Int>,
        independent: Set<String>,
    ): Map<String, Int> {
        val total = listVotes.values.sum()
        if (total <= 0.0) return emptyMap()
        val eligible = listVotes.filter { (party, vote) -> vote >= 0.03 * total || direct.getOrDefault(party, 0) >= 5 }
        val valid = eligible.values.sum()
        if (valid <= 0.0) return emptyMap()
        val seats = 300 - direct.filter { (p, n) -> p in independent || (p !in eligible && n < 5) }.values.sum()
        val fractions = eligible.mapValues { it.value / valid }
        val linked =
            fractions.mapValues { (party, fraction) ->
                val quota = (seats * fraction - direct.getOrDefault(party, 0)) / 2
                if (quota < 1.0) 0 else quota.roundToInt()
            }
        val count = linked.values.sum()
        if (count > 46) return ElectionModel.allocateSeats(linked.mapValues { it.value.toDouble() }, ElectoralSystem.PROPORTIONAL, 46)
        if (count == 46) return linked
        val remaining = ElectionModel.allocateSeats(fractions, ElectoralSystem.PROPORTIONAL, 46 - count)
        return linked.mapValues { (p, n) -> n + remaining.getValue(p) }
    }

    fun count(
        votes: MixedVotes,
        districts: List<DistrictResult>,
        direct: Map<String, Int>,
        registration: ListRegistration?,
    ): LegislativeSeats {
        val slates =
            registration?.lists ?: votes.shares.keys.filterNot(votes::independent).map { party ->
                RegisteredList(party, listOf(party), List(46) { party })
            }
        val byParty = slates.flatMap { list -> list.members.map { member -> member to list.name } }.toMap()
        val labels = votes.shares.keys.associateBy(votes::name)
        val ballot = slates.associate { it.name to 0.0 }.toMutableMap()
        for ((party, share) in votes.shares) {
            val ownList = byParty[party] ?: continue
            val choices = if (registration?.election == firstElection) first.listIntention[votes.name(party)] else null
            val expressed = choices.orEmpty().entries.mapNotNull { (p, value) -> labels[p]?.let(byParty::get)?.let { it to value } }
            val total = expressed.sumOf { it.second }
            if (total == 0) {
                ballot[ownList] = ballot.getValue(ownList) + share
            } else {
                for ((list, value) in expressed) ballot[list] = ballot.getValue(list) + share * value / total
            }
        }
        // 명칭만 바꾼 정당은 같은 법인으로 지역구 의석을 공제한다.
        val legalDirect =
            direct.mapKeys { (party, _) ->
                if (registration?.election == firstElection && votes.name(party) == "정의당" && byParty[party] == "녹색정의당") {
                    "녹색정의당"
                } else {
                    party
                }
            }
        val allocated = allocation(ballot, legalDirect, votes.shares.keys.filterTo(mutableSetOf(), votes::independent))
        val lists = linkedMapOf<String, Int>()
        val seats =
            votes.shares.keys
                .associateWith { direct.getOrDefault(it, 0) }
                .toMutableMap()
        for (list in slates) {
            val nominees = list.candidateOrder.take(allocated.getOrDefault(list.name, 0))
            lists[list.name] = nominees.size
            for (party in nominees) seats[party] = seats.getValue(party) + 1
        }
        val validVotes = ballot.values.sum()
        return LegislativeSeats(
            seats,
            districts = districts,
            districtMandates = direct,
            listSeats = mapOf("전국 준연동 비례대표" to lists),
            rankedLists =
                slates.filter { lists.getValue(it.name) > 0 }.map {
                    RankedMandateList.elected(it.name, it.candidateOrder, lists.getValue(it.name))
                },
            listVoteShares = if (validVotes > 0.0) ballot.mapValues { it.value / validVotes } else emptyMap(),
        )
    }
}
