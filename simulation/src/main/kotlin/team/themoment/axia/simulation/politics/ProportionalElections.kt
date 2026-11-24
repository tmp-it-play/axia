package team.themoment.axia.simulation.politics

import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

enum class ProportionalSystem { BRA, EST, FIN, IDN, ISR, LVA, POL, RWA, TUR, ZAF }

internal object ProportionalElections {
    private val regions by lazy {
        requireNotNull(javaClass.getResourceAsStream("/world/proportional_constituencies.json")).bufferedReader().use {
            Json.decodeFromString<Map<ProportionalSystem, List<MixedArea>>>(it.readText())
        }
    }
    private val prior by lazy {
        regions.mapValues { (_, areas) ->
            val totals =
                areas.flatMap { it.priorVotes.keys }.distinct().associateWith { p ->
                    areas.sumOf { it.priorVotes[p] ?: 0 }.toDouble()
                }
            val sum = totals.values.sum()
            totals.mapValues { it.value / sum }
        }
    }

    fun historicalVote(system: ProportionalSystem?): Map<String, Double> = prior[system].orEmpty()

    private fun eligibility(
        votes: Map<String, Double>,
        minimum: Double,
    ): Map<String, Double> {
        val total = votes.values.sum()
        return votes.filterValues { it > 0.0 && it >= minimum * total }
    }

    fun elect(
        rule: LegislativeElectionRule,
        votes: MixedVotes,
    ): LegislativeSeats {
        val system = requireNotNull(rule.proportionalSystem)
        val party = votes.shares.filter { (p, s) -> p != rule.reservedDelegation && s > 0 }
        if (system == ProportionalSystem.ISR || system == ProportionalSystem.RWA) {
            val eligible = eligibility(party, if (system == ProportionalSystem.RWA) 0.05 else 0.0325)
            val count =
                if (system == ProportionalSystem.ISR) {
                    israeli(eligible, votes)
                } else {
                    val independent = eligible.keys.firstOrNull(votes::independent)
                    val places = 53 - if (independent == null) 0 else 1
                    val allocated = remainder(eligible.filterKeys { it != independent }, places)
                    if (independent == null) allocated else allocated + (independent to 1)
                }
            val reserved = rule.reservedDelegation?.let { mapOf(it to 27) }.orEmpty()
            return LegislativeSeats(votes.shares.keys.associateWith { count[it] ?: 0 }, reserved, listSeats = mapOf("전국 명부" to count))
        }
        val areas = regions.getValue(system)
        val local = areas.associateWith { area -> regionalVotes(system, area, votes, party) }
        val national = party.keys.associateWith { p -> local.values.sumOf { it[p] ?: 0.0 } }
        val lists =
            when (system) {
                ProportionalSystem.EST -> {
                    estonia(areas, local, national, votes)
                }

                ProportionalSystem.ZAF -> {
                    southAfrica(areas, local, votes)
                }

                else -> {
                    val eligible =
                        when (system) {
                            ProportionalSystem.POL -> {
                                national.filter { (p, v) ->
                                    val threshold = if (votes.name(p) in setOf("시민연합", "제3의 길")) 0.08 else 0.05
                                    v >= threshold * (national.values.sum() + areas.sumOf { it.valid - it.priorVotes.values.sum() })
                                }
                            }

                            ProportionalSystem.TUR -> {
                                val alliances = party.keys.groupBy { turkishAlliance(votes.name(it)) ?: it }
                                val qualifying =
                                    alliances.values.filter { group ->
                                        group.sumOf { party.getValue(it) } >= party.values.sum() * 0.07
                                    }
                                party.filterKeys { p -> votes.independent(p) || qualifying.any { p in it } }
                            }

                            ProportionalSystem.LVA -> {
                                eligibility(party, 0.05)
                            }

                            ProportionalSystem.IDN -> {
                                eligibility(party, 0.04)
                            }

                            else -> {
                                party
                            }
                        }
                    areas.associate { area ->
                        val weights = local.getValue(area).filterKeys { it in eligible }
                        area.name to
                            when (system) {
                                ProportionalSystem.BRA -> {
                                    brazil(area, weights, votes)
                                }

                                ProportionalSystem.FIN -> {
                                    val island = weights.keys.firstOrNull { votes.name(it) == "올란드 대표" }
                                    when {
                                        area.name == "올란드" && island != null -> mapOf(island to 1)
                                        else -> MixedMemberElections.divisors(weights.filterKeys { it != island }, area.seats)
                                    }
                                }

                                else -> {
                                    MixedMemberElections.divisors(
                                        weights,
                                        area.seats,
                                        system in setOf(ProportionalSystem.LVA, ProportionalSystem.IDN),
                                    )
                                }
                            }
                    }
                }
            }
        return LegislativeSeats(votes.shares.keys.associateWith { p -> lists.values.sumOf { it[p] ?: 0 } }, listSeats = lists)
    }

    private fun regionalVotes(
        system: ProportionalSystem,
        area: MixedArea,
        votes: MixedVotes,
        national: Map<String, Double>,
    ): Map<String, Double> {
        val baseline = prior.getValue(system)
        val local = votes.weights(area)
        if (area.priorVotes.isEmpty()) {
            val total = local.values.sum()
            return local.filterKeys { it in national }.mapValues { it.value / total * area.seats * 100000.0 }
        }
        val valid = area.priorVotes.values.sum()
        return national.mapValues { (party, support) ->
            val original = votes.name(party)
            val prior = area.priorVotes[original]
            if (prior != null) {
                val swing =
                    area.members
                        .ifEmpty { listOf(area.id) }
                        .map { votes.regionalSwing(party, it) }
                        .average()
                prior * (support / baseline.getValue(original)) * swing
            } else {
                local.getValue(party) * valid
            }
        }
    }

    private fun remainder(
        weights: Map<String, Double>,
        seats: Int,
    ): Map<String, Int> = if (weights.isEmpty()) emptyMap() else ElectionModel.allocateSeats(weights, ElectoralSystem.PROPORTIONAL, seats)

    /** 바데르·오페르 방식. 전국 기초배분 뒤 양당 잉여표 약정끼리 경쟁하고 해당 한 석을 두 당 안에서 다시 결정한다. */
    private fun israeli(
        votes: Map<String, Double>,
        electorate: MixedVotes,
    ): Map<String, Int> {
        if (votes.isEmpty()) return emptyMap()
        val quota = votes.values.sum() / 120
        val seats = votes.mapValues { floor(it.value / quota).toInt() }.toMutableMap()
        val pairs = listOf(setOf("리쿠드", "종교시온주의 연합"), setOf("예시 아티드", "국가통합당"), setOf("샤스", "통합토라유대주의"), setOf("노동당", "메레츠"))
        val partners = votes.keys.groupBy { p -> pairs.firstOrNull { electorate.name(p) in it }?.first() ?: p }
        repeat(120 - seats.values.sum()) {
            val group = partners.values.maxBy { g -> g.sumOf(votes::getValue) / (1 + g.sumOf(seats::getValue)) }
            val winner = group.maxBy { p -> votes.getValue(p) / (1 + seats.getValue(p)) }
            seats[winner] = seats.getValue(winner) + 1
        }
        return seats
    }

    private fun turkishAlliance(party: String): String? =
        when (party) {
            "정의개발당", "민족주의행동당", "신복지당", "민주좌파당" -> "인민연합"
            "공화인민당", "좋은당", "민주진보당", "행복당", "미래당", "민주당" -> "국민연합"
            "인민평등민주당 계열", "튀르키예 노동자당", "노동당", "인민민주당" -> "노동자유연합"
            else -> null
        }

    /** 2023년 개표의 개인별 득표 분포에 변동을 적용한다. 개인 쿼터·지역 10%·보정 5% 후보 요건을 각각 검사한다. */
    private fun estonia(
        areas: List<MixedArea>,
        local: Map<MixedArea, Map<String, Double>>,
        national: Map<String, Double>,
        electorate: MixedVotes,
    ): Map<String, Map<String, Int>> {
        val valid = national.values.sum() + areas.sumOf { it.valid - it.priorVotes.values.sum() }
        val eligible = national.filterValues { it >= valid * 0.05 }
        val total = national.keys.associateWith { 0 }.toMutableMap()
        val nationalCandidates = national.keys.associateWith { 0 }.toMutableMap()
        val areasWon = mutableMapOf<String, Map<String, Int>>()
        for (area in areas) {
            val weights = local.getValue(area)
            val quota = (weights.values.sum() + area.valid - area.priorVotes.values.sum()) / area.seats
            val seats =
                weights.mapValues { (p, support) ->
                    val original = electorate.name(p)
                    val prior = area.candidates[original]
                    val preferences =
                        if (prior != null) {
                            prior.map { support * it / area.priorVotes.getValue(original) }
                        } else {
                            candidateWeights(area, electorate, p, support, area.seats)
                        }
                    val personal = preferences.count { it >= quota }
                    val ratio = support / quota
                    val localSeats = if (p in eligible) (ratio + 0.25).toInt().coerceAtMost(preferences.count { it >= 0.10 * quota }) else 0
                    val filled = maxOf(personal, localSeats)
                    nationalCandidates[p] = nationalCandidates.getValue(p) + preferences.count { it >= 0.05 * quota }
                    total[p] = total.getValue(p) + filled
                    filled
                }
            areasWon[area.name] = seats
        }
        val compensation = mutableMapOf<String, Int>()
        repeat(101 - total.values.sum()) {
            val eligibleWithCandidates = eligible.keys.filter { total.getValue(it) < nationalCandidates.getValue(it) }
            val p = eligibleWithCandidates.maxByOrNull { national.getValue(it) / (total.getValue(it) + 1.0).pow(0.9) } ?: return@repeat
            total[p] = total.getValue(p) + 1
            compensation[p] = (compensation[p] ?: 0) + 1
        }
        return areasWon + ("전국 보정" to compensation)
    }

    private fun candidateWeights(
        area: MixedArea,
        votes: MixedVotes,
        p: String,
        support: Double,
        seats: Int,
    ): List<Double> {
        val candidates = List(seats + 1) { i -> (0.01 + votes.affinity(area.id, i, p)).pow(3.0) }
        val sum = candidates.sum()
        return candidates.map { it / sum * support }.sortedDescending()
    }

    /** 연방을 하나의 명부로 계산하고 개인 득표 순서로 당선 정당을 확정한다. */
    private fun brazil(
        area: MixedArea,
        weights: Map<String, Double>,
        votes: MixedVotes,
    ): Map<String, Int> {
        val quota = floor(weights.values.sum() / area.seats + 0.5 - 1e-9)
        val federations = listOf(setOf("노동자당", "브라질공산당", "녹색당"), setOf("브라질사회민주당", "시민당"), setOf("사회주의자유당", "지속가능네트워크"))
        val grouped = weights.keys.groupBy { p -> federations.firstOrNull { votes.name(p) in it }?.first() ?: p }
        val support = grouped.mapValues { (_, members) -> members.sumOf(weights::getValue) }
        val candidates =
            grouped.mapValues { (_, members) ->
                members
                    .flatMap { p ->
                        candidateWeights(area, votes, p, weights.getValue(p), area.seats).map { p to it }
                    }.sortedByDescending { it.second }
            }
        val seats =
            support
                .mapValues { (p, n) ->
                    minOf((n / quota).toInt(), candidates.getValue(p).count { it.second >= quota * 0.1 })
                }.toMutableMap()
        repeat(area.seats - seats.values.sum()) {
            val next = grouped.keys.filter { g -> candidates.getValue(g).size > seats.getValue(g) }
            val qualified =
                next.filter { g ->
                    support.getValue(g) >= 0.8 * quota &&
                        candidates.getValue(g)[seats.getValue(g)].second >= 0.2 * quota
                }
            val winner = qualified.ifEmpty { next }.maxBy { support.getValue(it) / (seats.getValue(it) + 1) }
            seats[winner] = seats.getValue(winner) + 1
        }
        return seats.flatMap { (group, n) -> candidates.getValue(group).take(n).map { it.first } }.groupingBy { it }.eachCount()
    }

    /** 드룹 쿼터. 전국 잔여 중 처음 다섯 석 뒤로는 득표/기배정 의석 평균을 쓴다(선거법 별표 1A). */
    private fun droop(
        weights: Map<String, Double>,
        seats: Int,
        maximumRemainders: Int,
    ): Map<String, Int> {
        if (seats <= 0 || weights.isEmpty()) return emptyMap()
        val quota = floor(weights.values.sum() / (seats + 1.0)) + 1
        val counts = weights.mapValues { (it.value / quota).toInt() }.toMutableMap()
        val order = weights.keys.sortedByDescending { weights.getValue(it) - counts.getValue(it) * quota }
        for (p in order.take(minOf(maximumRemainders, seats - counts.values.sum()))) counts[p] = counts.getValue(p) + 1
        repeat(seats - counts.values.sum()) {
            val p = weights.keys.maxBy { weights.getValue(it) / counts.getValue(it).coerceAtLeast(1) }
            counts[p] = counts.getValue(p) + 1
        }
        return counts
    }

    private fun southAfrica(
        areas: List<MixedArea>,
        local: Map<MixedArea, Map<String, Double>>,
        votes: MixedVotes,
    ): Map<String, Map<String, Int>> {
        val districts = areas.associate { area -> area.name to droop(local.getValue(area), area.seats, Int.MAX_VALUE) }
        val regional = votes.shares.keys.associateWith { p -> districts.values.sumOf { it[p] ?: 0 } }
        val combined =
            votes.shares.filterKeys { !votes.independent(it) }.mapValues { (p, n) ->
                n * 20000000 +
                    local.values.sumOf { it[p] ?: 0.0 }
            }
        val fixed = regional.filterKeys(votes::independent).toMutableMap()
        var entitlement: Map<String, Int>
        while (true) {
            entitlement = droop(combined.filterKeys { it !in fixed }, 400 - fixed.values.sum(), 5)
            val overhangs = entitlement.keys.filter { entitlement.getValue(it) < regional.getValue(it) }
            if (overhangs.isEmpty()) break
            for (p in overhangs) fixed[p] = regional.getValue(p)
        }
        return districts + ("전국 보정" to entitlement.mapValues { (p, n) -> n - regional.getValue(p) })
    }
}
