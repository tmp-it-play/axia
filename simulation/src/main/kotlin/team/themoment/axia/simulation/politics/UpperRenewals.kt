package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.ElectoralRegion
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.ln

data class UpperRenewalResult(
    val date: LocalDate,
    val body: String,
    val renewed: Int,
    val method: String,
    val seats: Map<String, Int>,
)

@Serializable
private enum class UpperMethod(
    val label: String,
) {
    SNTV("단기비이양식 직선"),
    LIST("비례 명부"),
    PLURALITY("지역 직접선거"),
    STV("선호투표"),
    COLLEGE("선거인단 간선"),
    COLLEGE_LIST("선거인단 명부비례"),
    COLLEGE_MAJORITY("선거인단 결선투표"),
    CANDIDATE_RUNOFF("지역구 개인후보 결선"),
    APPOINT("지명"),
    BLOCK_LIST("절대 명부"),
    PROFESSION("직군·대학 선거인단"),
    EXPIRY("임기 만료"),
    LAND("주 정부 위임"),
    CANADIAN("총리 제청·총독 임명"),
    PEER("종신 귀족 임명·퇴임"),
    HEREDITARY("세습 귀족 보선"),
    BISHOP("성직 귀족 교체"),
    LIFE("종신직"),
}

@Serializable
private data class UpperUnit(
    val name: String,
    val size: Int,
    val method: UpperMethod,
    val next: String? = null,
    val years: Int = 0,
    val region: String = "",
    val regions: List<String> = emptyList(),
    val lowerLinked: Boolean = false,
    val initial: Int = size,
    val vacant: Boolean = false,
    val expiry: String? = null,
    val regular: String? = null,
    val allies: List<String> = emptyList(),
    val holders: Map<String, Int>? = null,
    val byElection: String? = null,
)

@Serializable
private data class UpperRule(
    val source: String,
    val units: List<UpperUnit>,
)

internal class UpperRenewals private constructor(
    private val country: String,
    private val rule: UpperRule,
) {
    private class Cohort(
        val unit: UpperUnit,
    ) {
        var due = unit.next?.let(LocalDate::parse)
        var expiry = unit.expiry?.let(LocalDate::parse)
        var regular = unit.regular?.let(LocalDate::parse)
        var byElection = unit.byElection?.let(LocalDate::parse)
        var capacity = unit.initial
        var members = emptyMap<String, Int>()
        val scheduled: LocalDate get() = minOf(due ?: LocalDate.MAX, expiry ?: LocalDate.MAX, byElection ?: LocalDate.MAX)
    }

    private val cohorts = rule.units.map(::Cohort)
    private val names = mutableMapOf<String, UpperGroup>()
    private val lowerLinked = cohorts.filter { it.unit.lowerLinked }
    private var initialSource: String? = null
    private var initialized = false
    private var previousDate: LocalDate? = null
    var next: LocalDate? = nextScheduled()
        private set

    private fun nextScheduled(): LocalDate? = cohorts.minOfOrNull { it.scheduled }?.takeUnless { it == LocalDate.MAX }

    var last: UpperRenewalResult? = null
        private set

    fun advance(
        date: LocalDate,
        upper: UpperHouse,
        lower: Legislature?,
        lowerElected: Boolean,
        approval: Double,
        regions: List<ElectoralRegion> = emptyList(),
    ): UpperHouse? {
        if (previousDate == date) return null
        previousDate = date
        val started = !initialized
        if (started) initialize(upper)
        val due =
            if (next?.let { date >= it } == true) {
                cohorts.filter { c ->
                    date >= c.scheduled || (c.unit.lowerLinked && lowerElected)
                }
            } else if (lowerElected) {
                lowerLinked
            } else {
                emptyList()
            }
        if (due.isEmpty()) return if (started) assemble(upper, lower, date) else null
        val methods = mutableSetOf<String>()
        val results = mutableMapOf<String, Int>()
        val regional = regions.associateBy { it.id }
        for (c in due) {
            val u = c.unit
            if (c.expiry?.let { date >= it } == true) {
                c.members = emptyMap()
                c.expiry = null
                if (u.method == UpperMethod.HEREDITARY) {
                    c.due = null
                    c.capacity = 0
                    methods += "세습 귀족의 의석 자격 종료"
                    continue
                }
                methods += "임기 만료"
                if (c.due?.let { date >= it } != true) continue
            }
            methods += u.method.label
            if (u.method == UpperMethod.CANADIAN && c.members.isNotEmpty()) {
                c.members = emptyMap()
                c.due = date.plusDays(30 + (90 * draw(u, date, "공석")).toLong())
                methods += "75세 정년"
                continue
            }
            val local =
                if (u.regions.isEmpty()) {
                    regional[u.region]?.approval ?: approval
                } else {
                    val electorate = u.regions.mapNotNull(regional::get)
                    if (electorate.isEmpty()) approval else electorate.map { it.approval }.average()
                }
            if (c.byElection?.let { date >= it } == true) {
                c.byElection = null
                if (c.due?.let { date >= it } != true) {
                    val vacancies = c.capacity - c.members.values.sum()
                    val elected = runoff(c, weights(c, lower, local), date, vacancies)
                    c.members = (c.members.keys + elected.keys).associateWith { (c.members[it] ?: 0) + (elected[it] ?: 0) }
                    for ((p, n) in elected) results[p] = (results[p] ?: 0) + n
                    methods += "보궐"
                    continue
                }
            }
            val filled =
                when (u.method) {
                    UpperMethod.PEER, UpperMethod.HEREDITARY, UpperMethod.BISHOP -> {
                        peers(c, date, lower, approval)
                    }

                    UpperMethod.EXPIRY -> {
                        c.members = emptyMap()
                        c.capacity = 0
                        emptyMap()
                    }

                    UpperMethod.LAND -> {
                        val weights = weights(c, lower, local)
                        val parties = weights.entries.sortedByDescending { it.value }
                        val partners = parties.takeWhileInclusiveMajority(weights.values.sum()).map { names[it]?.alignWith ?: it }
                        val group = land(u, partners.ifEmpty { u.allies })
                        c.members = mapOf(group.name to u.size)
                        c.members
                    }

                    UpperMethod.PROFESSION -> {
                        val name = u.name
                        names[name] = UpperGroup(name, 0, PartyRole.OTHER)
                        c.capacity = u.size
                        c.members = mapOf(name to u.size)
                        c.members
                    }

                    else -> {
                        val weights = weights(c, lower, local)
                        val winners =
                            when (u.method) {
                                UpperMethod.SNTV, UpperMethod.STV -> {
                                    preferenceVote(c, weights, date, u.method == UpperMethod.STV)
                                }

                                UpperMethod.PLURALITY -> {
                                    List(u.size) { k ->
                                        weights
                                            .maxBy { (p, v) ->
                                                v * v /
                                                    -ln(
                                                        DistrictElectorate.localAffinity(
                                                            u.name + u.region,
                                                            k + date.year * 151,
                                                            names[p]?.alignWith ?: p,
                                                        ),
                                                    )
                                            }.key
                                    }.groupingBy { it }.eachCount()
                                }

                                UpperMethod.BLOCK_LIST -> {
                                    val united = weights.keys.filter { it in egyptianList }
                                    val opposing = weights.filterKeys { it !in united }
                                    if (united.isNotEmpty() && united.sumOf(weights::getValue) >= (opposing.values.maxOrNull() ?: 0.0)) {
                                        proportional(weights.filterKeys { it in united }, u.size)
                                    } else {
                                        mapOf(opposing.maxBy { it.value }.key to u.size)
                                    }
                                }

                                UpperMethod.APPOINT -> {
                                    val presidential = weights.mapValues { (p, v) -> v * if (names[p]?.role in cabinet) 2.7 else 0.4 }
                                    proportional(presidential, u.size)
                                }

                                UpperMethod.CANADIAN -> {
                                    val independent =
                                        names.values.filter { it.name in canadianCaucuses }.associate {
                                            it.name to
                                                (upper.groups.find { g -> g.name == it.name }?.seats ?: 1).toDouble()
                                        }
                                    val candidates = independent.ifEmpty { weights }
                                    mapOf(candidates.maxBy { (p, v) -> v / -ln(draw(u, date, p)) }.key to 1)
                                }

                                UpperMethod.COLLEGE_MAJORITY, UpperMethod.CANDIDATE_RUNOFF -> {
                                    runoff(c, weights, date, u.size)
                                }

                                UpperMethod.LIST, UpperMethod.COLLEGE_LIST -> {
                                    MixedMemberElections.divisors(weights, u.size)
                                }

                                else -> {
                                    proportional(weights, u.size)
                                }
                            }
                        c.members = winners.filterValues { it > 0 }
                        c.capacity = u.size
                        c.members
                    }
                }
            for ((p, n) in filled) results[p] = (results[p] ?: 0) + n
            c.due =
                when {
                    u.method == UpperMethod.CANADIAN -> date.plusYears(7 + (18 * draw(u, date, "취임 연령")).toLong())
                    c.regular != null -> c.regular.also { c.regular = null }
                    u.years > 0 -> date.plusYears(u.years.toLong())
                    else -> null
                }
        }
        next = nextScheduled()
        last = UpperRenewalResult(date, upper.name, results.values.sum(), methods.joinToString(" · "), results)
        return assemble(upper, lower, date)
    }

    private fun runoff(
        cohort: Cohort,
        weights: Map<String, Double>,
        date: LocalDate,
        seats: Int,
    ): Map<String, Int> {
        if (seats == 0) return emptyMap()
        val unit = cohort.unit
        val turnout = if (unit.method == UpperMethod.COLLEGE_MAJORITY) 0.85 + 0.15 * draw(unit, date, "선거인단 투표율") else null
        return MixedMemberElections
            .candidateRunoff(weights, seats, turnout) { party, i ->
                DistrictElectorate.localAffinity(unit.name + unit.region, date.year * 151 + i, names[party]?.alignWith ?: party)
            }.groupingBy { it }
            .eachCount()
    }

    fun renameParty(change: PartyRename) {
        val group = names.remove(change.old) ?: return
        names[change.name] = group.copy(name = change.name)
        for (cohort in cohorts) {
            if (change.old in cohort.members) cohort.members = cohort.members.mapKeys { change.label(it.key) }
        }
    }

    private fun initialize(upper: UpperHouse) {
        initialized = true
        initialSource = upper.sourceNote
        val opening =
            upper.groups.ifEmpty {
                listOf(
                    UpperGroup("정부 지지 의원", upper.governmentSeats, PartyRole.LEAD),
                    UpperGroup("야당계 의원", upper.oppositionSeats, PartyRole.OPPOSITION),
                    UpperGroup("원내 소속 미확인", upper.seats - upper.governmentSeats - upper.oppositionSeats, PartyRole.OTHER),
                )
            }
        opening.forEach { names[it.name] = it }
        val remaining = opening.associate { it.name to it.seats }.toMutableMap()
        for (c in cohorts) {
            val holders = c.unit.holders ?: continue
            require(holders.values.sum() <= c.capacity)
            for ((party, seats) in holders) {
                val remainingSeats = remaining.getValue(party) - seats
                require(remainingSeats >= 0)
                remaining[party] = remainingSeats
            }
            c.members = holders
        }
        val bishops = cohorts.firstOrNull { it.unit.method == UpperMethod.BISHOP }
        if (bishops != null && remaining.containsKey("주교")) {
            bishops.members = mapOf("주교" to remaining.getValue("주교"))
            remaining.remove("주교")
        }
        for ((index, c) in cohorts.withIndex()) {
            if (c.unit.vacant || c === bishops || c.unit.holders != null) continue
            if (c.unit.method == UpperMethod.LAND) {
                val group = land(c.unit, c.unit.allies)
                c.members = mapOf(group.name to c.capacity)
                continue
            }
            val held = mutableMapOf<String, Int>()
            repeat(c.capacity) { i ->
                val taken =
                    remaining.entries
                        .filter { it.value > 0 }
                        .maxByOrNull { (p, n) ->
                            n / -ln(DistrictElectorate.localAffinity(c.unit.name, i + index * 53, p))
                        }?.key ?: return@repeat
                held[taken] = (held[taken] ?: 0) + 1
                remaining[taken] = remaining.getValue(taken) - 1
            }
            c.members = held
        }
    }

    private fun land(
        u: UpperUnit,
        allies: List<String>,
    ): UpperGroup {
        val name = "${u.name} 주 대표단"
        return UpperGroup(name, u.size, PartyRole.OTHER, blocVotes = true, coalition = allies).also { names[name] = it }
    }

    private fun List<Map.Entry<String, Double>>.takeWhileInclusiveMajority(total: Double): List<String> {
        var sum = 0.0
        val result = mutableListOf<String>()
        for (entry in this) {
            result += entry.key
            sum += entry.value
            if (sum > total / 2) break
        }
        return result
    }

    private fun weights(
        c: Cohort,
        lower: Legislature?,
        local: Double,
    ): Map<String, Double> {
        val values = mutableMapOf<String, Double>()
        val chamber = lower?.groups.orEmpty().filter { it.votingSeats > 0 }
        val sum = chamber.sumOf { it.votingSeats }.coerceAtLeast(1)
        for (party in chamber) {
            if (c.unit.method == UpperMethod.LAND) {
                when (party.modelName) {
                    "기독민주연합" -> if (c.unit.name == "바이에른") continue
                    "기독사회연합" -> if (c.unit.name != "바이에른") continue
                    "남슐레스비히 유권자연합" -> if (c.unit.name != "슐레스비히홀슈타인") continue
                }
            }
            if (country == "GBR" && party.modelName in setOf("스코틀랜드 국민당", "신페인")) continue
            val existing =
                names.values.find {
                    !it.blocVotes &&
                        (it.alignWith == party.modelName || (it.alignWith == null && it.name == party.name))
                }
            val label = existing?.name ?: party.name
            names[label] = UpperGroup(label, 0, party.role, alignWith = party.modelName)
            val domestic = party.votingSeats.toDouble() / sum
            val swing = if (party.role in cabinet) (1.0 + (local - 50) / 110).coerceIn(0.55, 1.45) else 1.0
            val territory = 0.65 + 0.7 * DistrictElectorate.localAffinity(c.unit.name, 0, party.modelName)
            values[label] = domestic * territory * swing
        }
        if (c.unit.method == UpperMethod.LAND) {
            for (partner in c.members.keys.flatMap { names[it]?.coalition.orEmpty() }) {
                values[partner] = (values[partner] ?: 0.0) + 0.08
            }
        }
        val prior =
            c.members.values
                .sum()
                .coerceAtLeast(1)
        for ((p, n) in c.members) {
            if (c.unit.method == UpperMethod.LAND || p in unmeasured) continue
            values[p] = (values[p] ?: 0.0) + (if (c.unit.method in collegeMethods) 0.9 else 0.35) * n / prior
        }
        if (values.isEmpty()) {
            val pool = c.members.ifEmpty { cohorts.flatMap { it.members.keys }.groupingBy { it }.eachCount() }
            values.putAll(pool.mapValues { it.value.toDouble() })
        }
        if (values.isEmpty()) {
            names["원내 소속 미확인"] = UpperGroup("원내 소속 미확인", 0, PartyRole.OTHER)
            values["원내 소속 미확인"] = 1.0
        }
        return values
    }

    private fun peers(
        c: Cohort,
        date: LocalDate,
        lower: Legislature?,
        approval: Double,
    ): Map<String, Int> {
        val held = c.members.toMutableMap()
        val vacancy =
            when (c.unit.method) {
                UpperMethod.PEER -> (c.capacity * (0.018 + 0.024 * draw(c.unit, date, "퇴임"))).toInt()
                UpperMethod.HEREDITARY -> 1 + (3 * draw(c.unit, date, "보선")).toInt()
                else -> 1 + (3 * draw(c.unit, date, "주교")).toInt()
            }
        val retired = mutableMapOf<String, Int>()
        repeat(vacancy.coerceAtMost(held.values.sum())) { i ->
            val p = held.filterValues { it > 0 }.maxBy { (p, n) -> n / -ln(draw(c.unit, date, "$p/$i")) }.key
            held[p] = held.getValue(p) - 1
            retired[p] = (retired[p] ?: 0) + 1
        }
        val newcomers =
            if (c.unit.method == UpperMethod.PEER) {
                val count = (c.capacity * (0.014 + 0.045 * draw(c.unit, date, "신규 임명"))).toInt()
                proportional(weights(c, lower, approval), count)
            } else {
                retired
            }
        newcomers.forEach { (p, n) -> held[p] = (held[p] ?: 0) + n }
        c.members = held.filterValues { it > 0 }
        c.capacity = c.members.values.sum()
        return newcomers
    }

    /** 후보별 모형 선호표. STV의 쿼터 초과분 및 탈락자 표를 다음 선호 후보에게 이전한다. */
    private fun preferenceVote(
        c: Cohort,
        support: Map<String, Double>,
        date: LocalDate,
        transferable: Boolean,
    ): Map<String, Int> {
        val n = c.unit.size
        val total = support.values.sum()
        val candidates = mutableMapOf<Pair<String, Int>, Double>()
        for ((p, weight) in support) {
            val number = (ceil(weight / total * n).toInt() + 1).coerceIn(1, n)
            val local = List(number) { i -> 0.2 + DistrictElectorate.localAffinity(c.unit.name, date.year + i, names[p]?.alignWith ?: p) }
            local.forEachIndexed { i, preference -> candidates[p to i] = weight * preference / local.sum() }
        }
        if (!transferable) {
            return candidates.entries
                .sortedByDescending { it.value }
                .take(n)
                .groupingBy { it.key.first }
                .eachCount()
        }
        val quota = total / (n + 1.0)
        val winners = mutableListOf<String>()
        while (winners.size < n && candidates.isNotEmpty()) {
            if (candidates.size <= n - winners.size) {
                winners += candidates.keys.map { it.first }
                break
            }
            val top = candidates.maxBy { it.value }
            val elected = top.value > quota
            val removed = if (elected) top else candidates.minBy { it.value }
            val transferableVotes = if (elected) removed.value - quota else removed.value
            if (elected) winners += removed.key.first
            candidates.remove(removed.key)
            if (transferableVotes <= 0 || candidates.isEmpty()) continue
            val same = candidates.keys.filter { it.first == removed.key.first }
            val next =
                same.ifEmpty { candidates.keys.toList() }.associateWith { (p, i) ->
                    0.1 + DistrictElectorate.localAffinity(c.unit.name, i + date.year, removed.key.first + (names[p]?.alignWith ?: p))
                }
            val denominator = next.values.sum()
            for ((candidate, fraction) in next) {
                candidates[candidate] =
                    candidates.getValue(candidate) + transferableVotes * fraction / denominator
            }
        }
        return winners.groupingBy { it }.eachCount()
    }

    /** 인도 대통령 선거권이 있는 선출직 라지야 사바 의원. 부통령 선거에는 지명직도 참여한다. */
    fun electedDelegation(): Map<String, Int>? {
        if (country != "IND" || !initialized) return null
        val eligible = mutableMapOf<String, Int>()
        for (cohort in cohorts.filter { it.unit.method == UpperMethod.STV }) {
            for ((name, count) in cohort.members) eligible[name] = eligible.getOrDefault(name, 0) + count
        }
        return eligible
    }

    private fun assemble(
        upper: UpperHouse,
        lower: Legislature?,
        date: LocalDate,
    ): UpperHouse {
        val held = mutableMapOf<String, Int>()
        for (cohort in cohorts) for ((p, n) in cohort.members) held[p] = (held[p] ?: 0) + n
        val groups = held.map { (p, n) -> names.getValue(p).let { it.copy(seats = n, nonVoting = minOf(it.nonVoting, n)) } }
        return upper
            .copy(
                seats =
                    cohorts.sumOf {
                        it.capacity
                    },
                groups = groups,
                governmentSeats = 0,
                oppositionSeats = 0,
                sourceNote = listOfNotNull(initialSource, rule.source).joinToString(" "),
            ).session(lower, date)
    }

    companion object {
        private val rules by lazy {
            requireNotNull(UpperRenewals::class.java.getResourceAsStream("/world/upper_renewals.json")).bufferedReader().use {
                Json.decodeFromString<Map<String, UpperRule>>(it.readText())
            }
        }
        private val cabinet = setOf(PartyRole.LEAD, PartyRole.COALITION, PartyRole.SUPPLY)
        private val collegeMethods = setOf(UpperMethod.COLLEGE, UpperMethod.COLLEGE_LIST, UpperMethod.COLLEGE_MAJORITY)
        private val unmeasured = setOf("정부 지지 의원", "야당계 의원", "원내 소속 미확인")
        private val canadianCaucuses = setOf("독립상원의원단", "캐나다 상원의원단", "진보 상원의원단", "비소속")
        private val egyptianList =
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

        private fun proportional(
            weights: Map<String, Double>,
            seats: Int,
        ): Map<String, Int> = ElectionModel.allocateSeats(weights, ElectoralSystem.PROPORTIONAL, seats).filterValues { it > 0 }

        private fun draw(
            unit: UpperUnit,
            date: LocalDate,
            key: String,
        ): Double = DistrictElectorate.localAffinity(unit.region + unit.name, date.toEpochDay().toInt(), key)

        fun forCountry(countryId: String): UpperRenewals? = rules[countryId]?.let { UpperRenewals(countryId, it) }
    }
}
