package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.ElectoralRegion
import team.themoment.axia.simulation.world.WorldLoader
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

enum class MixedSystem { DEU, EGY, ITA, JPN, KOR, LTU, RUS, THA, TWN }

@Serializable
internal data class MixedArea(
    val id: String,
    val name: String,
    val seats: Int,
    val block: String = "",
    val members: List<String> = emptyList(),
    val sizes: List<Int> = emptyList(),
    val labels: List<String> = emptyList(),
    val priorVotes: Map<String, Int> = emptyMap(),
    val candidates: Map<String, List<Int>> = emptyMap(),
    val valid: Int = 0,
)

internal class MixedVotes(
    val shares: Map<String, Double>,
    regions: List<ElectoralRegion> = emptyList(),
    private val roles: Map<String, PartyRole> = emptyMap(),
    val approval: Double = 50.0,
    private val modelNames: Map<String, String> = emptyMap(),
) {
    private val regional = regions.associateBy { it.id }

    fun name(party: String): String = modelNames[party] ?: party

    fun independent(party: String): Boolean = name(party) == "무소속"

    fun regionName(id: String): String = regional[id]?.name ?: worldRegions.getValue(id).name

    fun affinity(
        area: String,
        district: Int,
        party: String,
    ): Double = DistrictElectorate.localAffinity(area, district, name(party))

    fun regionalSwing(
        party: String,
        region: String,
    ): Double {
        val area = regional[region] ?: return 1.0
        val position =
            when (roles[party]) {
                PartyRole.LEAD, PartyRole.COALITION -> 1.0
                PartyRole.SUPPLY -> 0.5
                PartyRole.OPPOSITION -> -0.5
                else -> 0.0
            }
        return exp(position * ((area.approval - approval) / 35.0).coerceIn(-1.0, 1.0))
    }

    fun weights(area: MixedArea): Map<String, Double> {
        val members = area.members.mapNotNull(regional::get)
        val weight = members.sumOf { it.populationShare }
        return shares.mapValues { (party, share) ->
            val swing =
                if (weight >
                    0
                ) {
                    members.sumOf { it.populationShare * regionalSwing(party, it.id) } / weight
                } else {
                    regionalSwing(party, area.id)
                }
            share * swing * (0.65 + 0.70 * affinity(area.id, -1, party))
        }
    }

    companion object {
        private val worldRegions by lazy { WorldLoader.loadDefault().regions }
    }
}

internal object MixedMemberElections {
    private val areas by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/mixed_constituencies.json"))
            .bufferedReader()
            .use {
                Json.decodeFromString<Map<MixedSystem, List<MixedArea>>>(it.readText())
            }.also { rules ->
                val totals =
                    mapOf(
                        MixedSystem.KOR to 254,
                        MixedSystem.JPN to 289,
                        MixedSystem.RUS to 225,
                        MixedSystem.LTU to 71,
                        MixedSystem.ITA to 147,
                        MixedSystem.EGY to 284,
                        MixedSystem.THA to 400,
                        MixedSystem.TWN to 73,
                    )
                require(rules.mapValues { it.value.sumOf(MixedArea::seats) } == totals)
                require(
                    rules.values.flatten().all {
                        (it.sizes.isEmpty() || it.sizes.sum() == it.seats) &&
                            (it.labels.isEmpty() || it.labels.size == it.seats)
                    },
                )
            }
    }
    val lithuanianAreas: List<MixedArea> get() = areas.getValue(MixedSystem.LTU)

    private val japaneseBlocks =
        mapOf(
            "홋카이도" to 8,
            "도호쿠" to 12,
            "기타칸토" to 19,
            "미나미칸토" to 23,
            "도쿄" to 19,
            "호쿠리쿠신에쓰" to 10,
            "도카이" to 21,
            "긴키" to 28,
            "주고쿠" to 10,
            "시코쿠" to 6,
            "규슈" to 20,
        )
    private val egyptianBlocks = mapOf("카이로·델타 중남부" to 100, "상이집트" to 100, "서부 델타" to 42, "동부 델타" to 42)
    val italianDistricts: List<Pair<String, Int>> by lazy {
        areas.getValue(MixedSystem.ITA).flatMap { area -> List(area.seats) { index -> area.id to index } }
    }
    private val italianDistrictIndex by lazy { italianDistricts.withIndex().associate { (index, district) -> district to index } }
    private val egyptianUnity =
        setOf(
            "국가미래당",
            "신와프드당",
            "조국수호당",
            "현대이집트당",
            "이집트사회민주당",
            "공화인민당",
            "개혁개발당",
            "타감무",
            "세대의지당",
            "이집트자유당",
            "정의당",
            "회의당",
        )

    /** 동트식(1, 2, 3…)과 생트라귀식(1, 3, 5…) 최고평균법. */
    fun divisors(
        weights: Map<String, Double>,
        seats: Int,
        sainteLague: Boolean = false,
    ): Map<String, Int> {
        val assigned = weights.keys.associateWith { 0 }.toMutableMap()
        val entries = weights.filterValues { it > 0 }.entries
        if (entries.isEmpty()) return assigned
        repeat(seats) {
            val winner = entries.maxBy { (party, weight) -> weight / ((if (sainteLague) 2 else 1) * assigned.getValue(party) + 1) }
            assigned[winner.key] = assigned.getValue(winner.key) + 1
        }
        return assigned
    }

    private fun remainder(
        weights: Map<String, Double>,
        seats: Int,
    ): Map<String, Int> {
        val valid = weights.filterValues { it > 0.0 }
        return if (seats == 0 || valid.isEmpty()) emptyMap() else ElectionModel.allocateSeats(valid, ElectoralSystem.PROPORTIONAL, seats)
    }

    private fun normalized(weights: Map<String, Double>): Map<String, Double> {
        val total = weights.values.sum()
        return if (total > 0.0) weights.mapValues { it.value / total } else emptyMap()
    }

    fun elect(
        system: MixedSystem,
        votes: MixedVotes,
        registration: ListRegistration? = null,
    ): LegislativeSeats {
        if (system == MixedSystem.DEU) return GermanBundestag.elect(votes)
        if (system == MixedSystem.LTU) return LithuanianSeimas.count(votes, registration).result()
        val areas = areas.getValue(system)
        val italianSlates =
            if (system ==
                MixedSystem.ITA
            ) {
                registration?.lists ?: JointListNominations.defaults(system, votes)
            } else {
                emptyList()
            }
        val local =
            areas.associateWith { area ->
                val support = votes.weights(area)
                when (system) {
                    MixedSystem.KOR -> support.filterKeys { KoreanListNominations.districtEligible(votes.name(it), registration?.election) }
                    MixedSystem.ITA -> JointListNominations.italianDistrictSupport(area, support, votes)
                    else -> support
                }
            }
        val districts =
            local
                .map { (area, support) ->
                    val winners =
                        if (system == MixedSystem.EGY) {
                            egyptianDistricts(area, votes, support)
                        } else {
                            List(area.seats) { index ->
                                val weights =
                                    support.mapValues { (party, value) ->
                                        value.pow(1.9) / -ln(votes.affinity(area.id, index, party))
                                    }
                                when (system) {
                                    MixedSystem.ITA -> {
                                        JointListNominations.italianWinner(
                                            italianSlates,
                                            support,
                                            votes,
                                            area,
                                            index,
                                            italianDistrictIndex.getValue(area.id to index),
                                        )
                                    }

                                    MixedSystem.TWN -> {
                                        TaiwanBallots.local(votes, area.id, index)
                                    }

                                    else -> {
                                        weights.maxBy { it.value }.key
                                    }
                                }
                            }
                        }
                    val labels =
                        area.labels.ifEmpty {
                            if (area.sizes.isEmpty()) {
                                List(area.seats) { "${area.name} ${it + 1}" }
                            } else {
                                area.sizes.flatMapIndexed { index, seats -> List(seats) { "${area.name} ${index + 1}선거구" } }
                            }
                        }
                    DistrictResult(area.id, area.name, winners, labels)
                }.toMutableList()
        if (system == MixedSystem.TWN) districts += TaiwanBallots.indigenous(votes)
        val districtSeats = districts.flatMap { it.winners }.groupingBy { it }.eachCount()
        val partyBallot = normalized(votes.shares.filterKeys { !votes.independent(it) })
        val lists = linkedMapOf<String, Map<String, Int>>()
        val appointments = if (system == MixedSystem.EGY) presidentialAppointments(votes, districtSeats) else emptyMap()
        when (system) {
            MixedSystem.KOR -> {
                return KoreanListNominations.count(votes, districts, districtSeats, registration)
            }

            MixedSystem.TWN -> {
                lists["전국 비례대표"] = remainder(partyBallot.filterValues { it >= 0.05 }, 34)
            }

            MixedSystem.THA -> {
                lists["전국 비례대표"] = remainder(partyBallot, 100)
            }

            MixedSystem.RUS -> {
                lists["연방 명부"] = remainder(russianLists(partyBallot), 225)
            }

            MixedSystem.ITA -> {
                val filedParties = italianSlates.flatMap { it.members }.toSet()
                val ballot = partyBallot.filterKeys { it in filedParties }
                val tyrol =
                    local.entries
                        .single { it.key.name == "트렌티노알토아디제" }
                        .value
                        .filterKeys { it in filedParties }
                lists["국내 비례대표"] = JointListNominations.italianSeats(ballot, italianSlates, votes, districts, tyrol)
                for ((area, seats) in mapOf("유럽" to 3, "남아메리카" to 2, "북·중앙아메리카" to 2, "아프리카·아시아·오세아니아·남극" to 1)) {
                    val overseas =
                        ballot.filterKeys { votes.name(it) != "남티롤인민당 연합" }.mapValues { (p, n) ->
                            n * (0.5 + votes.affinity(area, -1, p))
                        }
                    lists["재외 · $area"] = remainder(overseas, seats)
                }
            }

            MixedSystem.EGY, MixedSystem.JPN -> {
                for ((block, members) in areas.groupBy(MixedArea::block)) {
                    val party = partyBallot.keys.associateWith { p -> members.sumOf { local.getValue(it).getValue(p) * it.seats } }
                    lists[block] =
                        if (system == MixedSystem.JPN) {
                            divisors(party, japaneseBlocks.getValue(block))
                        } else {
                            egyptianList(party, votes, egyptianBlocks.getValue(block))
                        }
                }
            }

            MixedSystem.DEU, MixedSystem.LTU -> {
                error("별도 선거구별 개표를 사용하는 제도다")
            }
        }
        val total = votes.shares.keys.associateWith { p -> (districtSeats[p] ?: 0) + lists.values.sumOf { it[p] ?: 0 } }
        if (appointments.isNotEmpty()) lists["대통령 임명"] = appointments
        return LegislativeSeats(total, appointments, districts, districtSeats, lists)
    }

    /** 러시아의 5% 봉쇄조항과 과반 초과·최소 두 명부 조건. */
    private fun russianLists(votes: Map<String, Double>): Map<String, Double> {
        val eligible = votes.filterValues { it >= 0.05 }.toMutableMap()
        for ((p, v) in votes.entries.sortedByDescending { it.value }) {
            val support = eligible.values.sum()
            if (eligible.size >= 2 && support > 0.5) break
            eligible[p] = v
        }
        return eligible
    }

    /** 각 유권자는 출마자 중에서 공석 수만큼 선택할 수 있다. 과반 당선 뒤 남은 자리에 두 배수 결선을 치른다. */
    private fun egyptianDistricts(
        area: MixedArea,
        votes: MixedVotes,
        support: Map<String, Double>,
    ): List<String> =
        area.sizes.flatMapIndexed { i, n ->
            candidateRunoff(support, n) { party, j -> votes.affinity(area.id, i * 32 + j, party) }
        }

    /** 유권자 1명이 정원만큼 기표하는 개인후보제. 이집트는 미충원 의석 두 배수만 결선에 진출한다. */
    internal fun candidateRunoff(
        support: Map<String, Double>,
        seats: Int,
        collegeTurnout: Double? = null,
        affinity: (String, Int) -> Double,
    ): List<String> {
        val field =
            support
                .flatMap { (party, share) ->
                    List(seats) { index -> Triple(party, index, share * (0.4 + 1.2 * affinity(party, index))) }
                }.sortedByDescending { it.third }
        val validBallots = field.sumOf { it.third } / seats
        val first =
            field
                .filter {
                    it.third > validBallots / 2 && (collegeTurnout == null || it.third * collegeTurnout >= validBallots / 4)
                }.take(seats)
        val remaining = seats - first.size
        if (remaining == 0) return first.map { it.first }
        val candidates = field.filter { it !in first }
        val finalists = if (collegeTurnout == null) candidates.take(2 * remaining) else candidates
        val second = finalists.sortedByDescending { (party, index, votes) -> votes * (0.65 + 0.7 * affinity(party, seats + index)) }
        return (first + second.take(remaining)).map { it.first }
    }

    private fun egyptianList(
        weights: Map<String, Double>,
        votes: MixedVotes,
        seats: Int,
    ): Map<String, Int> {
        val entries = weights.keys.groupBy { if (votes.name(it) in egyptianUnity) "이집트 통합명부" else it }
        val winner = entries.values.maxBy { group -> group.sumOf { weights.getValue(it) } }
        return remainder(weights.filterKeys { it in winner }, seats)
    }

    private fun presidentialAppointments(
        votes: MixedVotes,
        elected: Map<String, Int>,
    ): Map<String, Int> {
        val independent = votes.shares.keys.firstOrNull(votes::independent)
        return if (independent != null) mapOf(independent to 28) else remainder(elected.mapValues { it.value.toDouble() }, 28)
    }
}
