package team.themoment.axia.simulation.politics

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** 이탈리아의 명부 간 상호 연결과 리투아니아의 단일 공동명부를 선거 전에 확정한다. */
internal object JointListNominations {
    private val italianRight = setOf("이탈리아의 형제들", "동맹", "전진 이탈리아", "우리 온건파")
    private val italianLeft = setOf("민주당", "녹색좌파연합", "더 많은 유럽", "시민참여")
    private val peaceCoalition = setOf("노동당", "리투아니아 기독교민주당", "사모기티아당")
    private val lithuanianPoll = LocalDate.of(2024, 10, 13)
    private const val SOUTH_TYROL = "남티롤인민당 연합"
    private const val REGIONAL_LISTS = "기타 지역·해외 명부"

    private const val ITALIAN_VALID = 28_102_222
    private const val TYROL_VALID = 505_551
    val italianBaseline: Map<String, Double> =
        run {
            // 하원 선거위원회의 2022 국내 개표 검증표. 나머지 소규모 명부는 독립 배분 대상에서 제외한다.
            val totals =
                mapOf(
                    "이탈리아의 형제들" to 7_325_018,
                    "동맹" to 2_456_909,
                    "전진 이탈리아" to 2_278_587,
                    "우리 온건파" to 245_586,
                    "민주당" to 5_370_857,
                    "녹색좌파연합" to 1_014_427,
                    "더 많은 유럽" to 791_905,
                    "시민참여" to 163_800,
                    "오성운동" to 4_337_526,
                    "행동당·생동하는 이탈리아" to 2_187_752,
                    SOUTH_TYROL to 117_025,
                )
            (totals + (REGIONAL_LISTS to ITALIAN_VALID - totals.values.sum())).mapValues { it.value.toDouble() / ITALIAN_VALID }
        }

    fun eligible(group: ParliamentaryGroup): Boolean = group.modelName !in setOf("무소속", SOUTH_TYROL, REGIONAL_LISTS)

    fun register(
        system: MixedSystem,
        election: LocalDate?,
        groups: List<ParliamentaryGroup>,
        leadChoice: String?,
    ): List<RegisteredList> {
        require(system == MixedSystem.ITA || system == MixedSystem.LTU)
        val parties = groups.filter { it.modelName != "무소속" }
        val available = parties.filter(::eligible).associateBy { it.name }.toMutableMap()
        val slates = mutableListOf<RegisteredList>()
        val occupiedLabels = parties.mapTo(mutableSetOf()) { it.name }

        fun file(
            applicants: List<ParliamentaryGroup>,
            label: String? = null,
        ) {
            val members = applicants.filter { it.name in available }
            if (members.size < 2) return
            val names = members.map { it.name }
            val order = if (system == MixedSystem.ITA) italianNominees(members) else lithuanianOrder(members)
            val memberLabel = names.joinToString("·") + " 선거연합"
            val base = label?.takeUnless { it in occupiedLabels } ?: memberLabel
            val unique =
                if (base !in
                    occupiedLabels
                ) {
                    base
                } else {
                    generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in occupiedLabels }
                }
            occupiedLabels += unique
            slates += RegisteredList(unique, names, candidateOrder = order)
            names.forEach(available::remove)
        }

        val lead = available.values.firstOrNull { it.role == PartyRole.LEAD }
        if (lead != null && leadChoice != null) {
            available[leadChoice]?.takeIf { it != lead }?.let { file(listOf(lead, it)) }
            available.remove(lead.name)
        }
        if (system == MixedSystem.ITA) {
            val cabinet = available.values.filter { it.role == PartyRole.LEAD || it.role == PartyRole.COALITION }

            fun label(members: List<ParliamentaryGroup>) =
                when {
                    members.all { it.modelName in italianRight } -> "중도우파 선거연합"
                    members.all { it.modelName in italianLeft } -> "중도좌파 선거연합"
                    else -> null
                }

            file(cabinet, label(cabinet))
            file(available.values.filter { it.modelName in italianRight }, "중도우파 선거연합")
            file(available.values.filter { it.modelName in italianLeft }, "중도좌파 선거연합")
        } else if (election == lithuanianPoll) {
            file(available.values.filter { it.modelName in peaceCoalition }, "평화연합")
        } else {
            // 독자 봉쇄선이 부담되는 정당들이 같은 진영의 가까운 정당과 먼저 접촉한다.
            val total = parties.sumOf { it.seats }
            for (small in available.values.sortedBy { it.seats }) {
                if (small.name !in available || small.seats >= 0.05 * total) continue
                val partner =
                    available.values
                        .filter {
                            it != small && it.seats + small.seats >= 0.07 * total && it.seats < 0.12 * total &&
                                ((it.role in governing) == (small.role in governing)) &&
                                ideologicalDistance(it.identity, small.identity) <= 0.3
                        }.minByOrNull { ideologicalDistance(small.identity, it.identity) }
                if (partner != null) file(listOf(partner, small))
            }
        }
        val joined = slates.flatMap { it.members }.toSet()
        return slates +
            parties.filterNot { it.name in joined }.map {
                RegisteredList(
                    it.name,
                    listOf(it.name),
                    joint =
                        system == MixedSystem.LTU && election == lithuanianPoll && it.modelName == "농민녹색연합" &&
                            (it != lead || leadChoice == null),
                )
            }
    }

    // 합산 순서가 달라도 법정 봉쇄선에 정확히 걸친 표는 포함한다.
    private fun threshold(
        total: Double,
        fraction: Double,
    ) = total * fraction - 4 * Math.ulp(total)

    private val governing = setOf(PartyRole.LEAD, PartyRole.COALITION, PartyRole.SUPPLY)

    /** 일정 없는 의석 계산에서도 같은 명부 규칙을 사용한다. */
    fun defaults(
        system: MixedSystem,
        votes: MixedVotes,
    ): List<RegisteredList> {
        val parties = votes.shares.map { (p, v) -> ParliamentaryGroup(p, (v * 1000).roundToInt(), PartyRole.OPPOSITION) }
        return register(system, null, parties, null)
    }

    private fun italianNominees(members: List<ParliamentaryGroup>): List<String> {
        val districts = MixedMemberElections.italianDistricts
        val weights = members.associate { it.name to it.seats.coerceAtLeast(1).toDouble() }
        val remaining = ElectionModel.allocateSeats(weights, ElectoralSystem.PROPORTIONAL, districts.size).toMutableMap()
        return districts.map { (area, index) ->
            val nominee =
                members.filter { remaining.getValue(it.name) > 0 }.maxBy {
                    remaining.getValue(it.name) / -ln(DistrictElectorate.localAffinity(area, index, it.modelName))
                }
            remaining[nominee.name] = remaining.getValue(nominee.name) - 1
            nominee.name
        }
    }

    /** 공동명부의 후보를 각 당의 교섭력에 따라 순차 배치한다. ID 단위 우선표가 없는 모형의 공천 순번이다. */
    private fun lithuanianOrder(members: List<ParliamentaryGroup>): List<String> {
        val used = members.associate { it.name to 0 }.toMutableMap()
        return List(70) {
            val next = members.maxBy { it.seats.coerceAtLeast(1).toDouble() / (2 * used.getValue(it.name) + 1) }
            used[next.name] = used.getValue(next.name) + 1
            next.name
        }
    }

    fun italianDistrictSupport(
        area: MixedArea,
        support: Map<String, Double>,
        votes: MixedVotes,
    ): Map<String, Double> {
        val national = support.filterKeys { votes.name(it) != SOUTH_TYROL && votes.name(it) != REGIONAL_LISTS }
        if (area.name == "발레다오스타") {
            val regional = support.keys.firstOrNull { votes.name(it) == REGIONAL_LISTS } ?: return national
            val swing = votes.shares.getValue(regional) / italianBaseline.getValue(REGIONAL_LISTS)
            // 발레다오스타는 국내 비례표 없이 1명을 선출했다. 2022년 최대 지역명부 20,763표, 경쟁 후보 32,983표.
            return national + (regional to national.values.sum() * 20_763 / 32_983 * swing)
        }
        val minority = votes.shares.keys.firstOrNull { votes.name(it) == SOUTH_TYROL }
        if (minority == null || area.name != "트렌티노알토아디제") return national
        val share = (votes.shares.getValue(minority) * ITALIAN_VALID / TYROL_VALID).coerceIn(0.0, 1.0)
        val sum = national.values.sum()
        val others = if (sum > 0.0) national.mapValues { (_, value) -> value / sum * (1 - share) } else emptyMap()
        return others + (minority to share)
    }

    fun italianWinner(
        ballots: List<RegisteredList>,
        support: Map<String, Double>,
        votes: MixedVotes,
        area: MixedArea,
        district: Int,
        nationwideIndex: Int,
    ): String {
        // 보호 언어 명부는 해당 특별자치지역에서만 후보를 낸다. 기타 지역·재외 명부는 발레다오스타에서만 국내 후보를 낸다.
        val field = ballots.filter { list -> list.members.any { it in support } }
        val winner =
            field.maxBy { list ->
                val candidate = list.candidateOrder.getOrNull(nationwideIndex) ?: list.members.first()
                list.members.sumOf { support[it] ?: 0.0 }.pow(1.9) / -ln(votes.affinity(area.id, district, candidate))
            }
        return winner.candidateOrder.getOrNull(nationwideIndex) ?: winner.members.first()
    }

    fun italianSeats(
        shares: Map<String, Double>,
        lists: List<RegisteredList>,
        votes: MixedVotes,
        districts: List<DistrictResult>,
        tyrol: Map<String, Double>,
    ): Map<String, Int> {
        val domestic = shares.filterKeys { votes.name(it) != REGIONAL_LISTS }
        val total = shares.values.sum()
        val tyroleanWinners = districts.single { it.regionName == "트렌티노알토아디제" }.winners
        val minority =
            domestic.keys.firstOrNull {
                votes.name(it) == SOUTH_TYROL &&
                    (
                        tyroleanWinners.count { winner -> winner == it } * 4 >= tyroleanWinners.size ||
                            (tyrol[it] ?: 0.0) >= tyrol.values.sum() * 0.20
                    )
            }
        val eligible = domestic.filter { (p, v) -> v >= threshold(total, 0.03) || p == minority }
        val ballots = linkedMapOf<String, Double>()
        val recipients = mutableMapOf<String, Map<String, Double>>()
        for (list in lists) {
            val members = domestic.filterKeys { it in list.members }
            val qualified = members.filterKeys { it in eligible }
            val counted = members.filter { (p, v) -> v >= threshold(total, 0.01) || p == minority }.values.sum()
            if (list.members.size > 1 && counted >= threshold(total, 0.10) && qualified.isNotEmpty()) {
                val key = list.members.first()
                ballots[key] = counted
                recipients[key] = qualified
            } else {
                for ((p, v) in qualified) {
                    ballots[p] = v
                    recipients[p] = mapOf(p to v)
                }
            }
        }
        val national = ElectionModel.allocateSeats(ballots, ElectoralSystem.PROPORTIONAL, 245)
        return buildMap {
            for ((key, seats) in national) {
                putAll(
                    ElectionModel.allocateSeats(recipients.getValue(key), ElectoralSystem.PROPORTIONAL, seats),
                )
            }
        }
    }

    internal data class LithuanianListCount(
        val seats: Map<String, Int>,
        val mandates: List<RankedMandateList>,
    )

    private fun lithuanianRanking(
        list: RegisteredList,
        votes: Map<String, Double>,
    ): List<String> {
        val order =
            list.candidateOrder.ifEmpty {
                if (list.members.size == 1) return List(70) { list.members.first() }
                lithuanianOrder(list.members.map { ParliamentaryGroup(it, 1, PartyRole.OPPOSITION) })
            }
        val nominations = order.groupingBy { it }.eachCount()
        val priorCandidates = mutableMapOf<String, Int>()
        // 같은 당 후보 중 앞 순번의 인지도가 높다. 우선표 동률은 신고된 순번대로 둔다.
        return order
            .map { party ->
                val ordinal = priorCandidates.getOrDefault(party, 0) + 1
                priorCandidates[party] = ordinal
                party to (votes[party] ?: 0.0) / (nominations.getValue(party) * ordinal)
            }.sortedByDescending { it.second }
            .map { it.first }
    }

    /** 봉쇄조항은 무효표 포함 투표자, 의석 배분 쿼터는 참가 자격을 얻은 명부의 유효표가 분모다. */
    fun lithuanianCount(
        votes: Map<String, Double>,
        lists: List<RegisteredList>,
        valid: Int = 1_133_561,
        turnout: Int = 1_175_026,
    ): LithuanianListCount {
        val weights = lists.associate { list -> list.name to list.members.sumOf { votes[it] ?: 0.0 } }.filterValues { it > 0 }
        if (weights.isEmpty()) return LithuanianListCount(emptyMap(), emptyList())
        val ballots = ElectionModel.allocateSeats(weights, ElectoralSystem.PROPORTIONAL, valid)
        val eligible = lists.filter { list -> (ballots[list.name] ?: 0) * 100L >= turnout * (if (list.joint) 7L else 5L) }.toMutableList()
        // 봉쇄선을 넘은 명부가 60% 미만을 대표하면 차순위 동률 명부까지 함께 참여한다.
        for ((_, tied) in (lists - eligible.toSet()).groupBy { ballots[it.name] ?: 0 }.toSortedMap(reverseOrder())) {
            if (eligible.sumOf { ballots.getValue(it.name) } * 100L >= turnout * 60L) break
            eligible += tied.filter { it.name in ballots }
        }
        val quota = ceil(eligible.sumOf { ballots.getValue(it.name) } / 70.0).toInt()
        val quotientSeats = eligible.associateWith { ballots.getValue(it.name) / quota }
        // 잉여와 득표가 같으면 개표 전 신고 순서를 선거번호의 대용으로 쓴다.
        val ordered =
            eligible.sortedWith(
                compareByDescending<RegisteredList> {
                    ballots.getValue(it.name) % quota
                }.thenByDescending { ballots.getValue(it.name) },
            )
        val bonus = 70 - quotientSeats.values.sum()
        val byParty = mutableMapOf<String, Int>()
        val mandates =
            ordered.mapIndexed { index, list ->
                val seats = quotientSeats.getValue(list) + (if (index < bonus) 1 else 0)
                val ranking = lithuanianRanking(list, votes)
                byParty.putAll(ranking.take(seats).groupingBy { it }.eachCount())
                RankedMandateList.elected(list.name, ranking, seats)
            }
        return LithuanianListCount(byParty, mandates.drop(bonus) + mandates.take(bonus))
    }
}
