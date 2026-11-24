package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.nation.ElectoralRegion
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** ECP Form 7(2023-11-30)의 지역구와 헌법 제51조의 여성·비무슬림 유보석. */
internal object PakistanAssembly {
    @Serializable
    internal data class District(
        val number: Int,
        val name: String,
        val province: String,
        val region: String,
        val votes: Map<String, Int>,
        val valid: Int,
    )

    internal val districts: List<District> by lazy {
        requireNotNull(PakistanAssembly::class.java.getResourceAsStream("/world/pakistan_districts.json"))
            .bufferedReader()
            .use { Json.decodeFromString<List<District>>(it.readText()) }
            .also { seats ->
                require(seats.map { it.number } == (1..266).toList())
                require(seats.groupingBy { it.province }.eachCount() == mapOf("KP" to 45, "ICT" to 3, "PB" to 141, "SD" to 61, "BA" to 16))
                require(seats.all { it.valid > 0 && it.votes.values.all { n -> n in 1..it.valid } })
            }
    }
    internal val women = mapOf("KP" to 10, "PB" to 32, "SD" to 14, "BA" to 4)
    private val regionNames =
        mapOf(
            "PAK-1123" to "카이베르파크툰크와",
            "PAK-1112" to "연방 직할 부족 지역",
            "PAK-1110" to "이슬라마바드 수도권",
            "PAK-1113" to "펀자브",
            "PAK-1114" to "신드",
            "PAK-1108" to "발루치스탄",
        )

    fun elect(
        shares: Map<String, Double>,
        opening: OpeningContest,
        regions: List<ElectoralRegion> = emptyList(),
        nationalApproval: Double = 50.0,
        roles: Map<String, PartyRole> = emptyMap(),
        modelNames: Map<String, String> = emptyMap(),
    ): LegislativeSeats {
        val total = opening.parties.sumOf { it.votes }.toDouble()
        val nationalBase = opening.parties.associate { it.name to it.votes / total }
        val localApproval = regions.associate { it.id to ((it.approval - nationalApproval) / 35.0).coerceIn(-1.0, 1.0) }
        val winners =
            districts.associateWith { district ->
                shares.keys.maxBy { party ->
                    val name = modelNames[party] ?: party
                    val prior = nationalBase[name]
                    val local = district.votes[name]
                    val ratio =
                        if (prior != null) {
                            // 새로 출마한 지역에서도 작은 지지 기반은 허용한다. 무소속은 최다 득표한 한 후보만 비교한다.
                            if (local == null) 0.02 else local.toDouble() / district.valid / prior
                        } else {
                            -ln(DistrictElectorate.localAffinity(district.region, district.number, name))
                        }
                    val position =
                        when (roles[party]) {
                            PartyRole.LEAD, PartyRole.COALITION -> 1.0
                            PartyRole.SUPPLY -> 0.5
                            PartyRole.OPPOSITION -> -0.5
                            else -> 0.0
                        }
                    shares.getValue(party) * ratio * exp(position * (localApproval[district.region] ?: 0.0))
                }
            }
        val counts = winners.values.groupingBy { it }.eachCount()
        val results =
            districts.groupBy { it.region }.map { (region, seats) ->
                DistrictResult(
                    region,
                    regionNames.getValue(region),
                    seats.map(winners::getValue),
                    seats.map { "NA-${it.number} ${it.name}" },
                )
            }
        return LegislativeSeats(shares.keys.associateWith { counts[it] ?: 0 }, districts = results, districtMandates = counts)
    }
}

data class PakistanPartyAllocation(
    val name: String,
    val won: Int,
    val direct: Int,
    val women: Int,
    val nonMuslim: Int,
) {
    val total: Int get() = direct + women + nonMuslim
}

data class PakistanAffiliation(
    val district: String,
    val party: String,
    val filed: LocalDate,
)

data class PakistanReturn(
    val gazette: LocalDate,
    val deadline: LocalDate,
    val published: Boolean = false,
    val complete: Boolean = false,
    val affiliations: List<PakistanAffiliation> = emptyList(),
    val allocation: List<PakistanPartyAllocation> = emptyList(),
    val unfilledReserved: Int = 0,
)

internal data class PakistanFiling(
    val chamber: Legislature,
    val report: PakistanReturn,
    val news: String,
)

/** 선거 때 사용한 당명은 선거 기록에 보존하고, 입당과 의석 배분에는 고정 정당 키를 쓴다. */
internal class PakistanReturns(
    private val independent: Set<String>,
) {
    private var report: PakistanReturn? = null
    private var original = emptyMap<PakistanAssembly.District, String>()
    private var membership = mutableMapOf<PakistanAssembly.District, String>()
    private val remaining = mutableListOf<PakistanAssembly.District>()
    private var processed: LocalDate? = null

    fun begin(
        date: LocalDate,
        results: List<DistrictResult>,
        chamber: Legislature,
    ): PakistanReturn {
        val keys = chamber.groups.associate { it.name to it.modelName }
        val districts = PakistanAssembly.districts.groupBy { it.region }
        original =
            buildMap {
                for (region in results) {
                    val seats = districts.getValue(region.regionId)
                    require(seats.size == region.winners.size)
                    seats.zip(region.winners).forEach { (seat, party) -> put(seat, keys.getValue(party)) }
                }
            }
        require(original.size == 266)
        membership = original.toMutableMap()
        remaining.clear()
        remaining += original.filterValues { it in independent }.keys
        processed = date
        // 선거법 제98조의 14일 이내에 전국 공고를 한 번 하는 시나리오. 입당 시한은 공고 이후 3일이다.
        val gazette = date.plusDays(12)
        return PakistanReturn(gazette, gazette.plusDays(3)).also { report = it }
    }

    fun advance(
        date: LocalDate,
        chamber: Legislature,
        random: Random,
    ): PakistanFiling? {
        var status = report ?: return null
        if (status.complete || date < status.gazette || processed?.let { date <= it } == true) return null
        processed = date
        if (!status.published) {
            status = status.copy(published = true)
            report = status
            if (date ==
                status.gazette
            ) {
                return PakistanFiling(chamber, status, "지역구 당선인 명단을 관보에 공고했다. 무소속 당선인의 입당 신고는 ${status.deadline}까지다.")
            }
        }
        val byKey = chamber.groups.associateBy { it.modelName }
        val eligible = original.values.toSet() - independent
        val daysLeft = (status.deadline.toEpochDay() - date.toEpochDay() + 1).coerceIn(1, 3).toInt()
        val filings = mutableListOf<PakistanAffiliation>()
        val iterator = remaining.iterator()
        while (iterator.hasNext()) {
            val district = iterator.next()
            if (random.nextInt(daysLeft) != 0) continue
            iterator.remove()
            if (eligible.isEmpty() || random.nextDouble() >= 0.65) continue
            // 지역구 이전 득표를 입당 성향으로 쓰며, 표본에 없던 정당에는 전국 당선 의석을 사전 가중치로 준다.
            val weights =
                eligible.associateWith { key ->
                    (district.votes[key] ?: 0) / district.valid.toDouble() + 0.12 * original.values.count { it == key } / original.size
                }
            var draw = random.nextDouble() * weights.values.sum()
            val party =
                weights.keys.firstOrNull {
                    draw -= weights.getValue(it)
                    draw < 0
                } ?: weights.keys.last()
            membership[district] = party
            filings +=
                PakistanAffiliation(
                    "NA-${district.number} ${district.name}",
                    byKey.getValue(party).name,
                    date.coerceAtMost(status.deadline),
                )
        }
        val complete = date >= status.deadline
        if (filings.isEmpty() && !complete) return null
        val direct = membership.values.groupingBy { it }.eachCount()
        val women = mutableMapOf<String, Int>()
        val minorities = mutableMapOf<String, Int>()

        fun award(
            province: String?,
            seats: Int,
            tally: MutableMap<String, Int>,
        ) {
            val inArea = original.filterKeys { province == null || it.province == province }
            val eligibleInArea = inArea.values.toSet() - independent
            val votes =
                inArea.keys
                    .map(membership::getValue)
                    .filter { it in eligibleInArea }
                    .groupingBy { it }
                    .eachCount()
            if (votes.isEmpty()) return
            val allocated = ElectionModel.allocateSeats(votes.mapValues { it.value.toDouble() }, ElectoralSystem.PROPORTIONAL, seats)
            allocated.forEach { (party, count) ->
                tally[party] = (tally[party] ?: 0) + count
            }
        }
        if (complete) {
            PakistanAssembly.women.forEach { (province, seats) -> award(province, seats, women) }
            award(null, 10, minorities)
        }
        val won = original.values.groupingBy { it }.eachCount()
        val allocation =
            chamber.groups.map { party ->
                val key = party.modelName
                PakistanPartyAllocation(party.name, won[key] ?: 0, direct[key] ?: 0, women[key] ?: 0, minorities[key] ?: 0)
            }
        val groups =
            chamber.groups.zip(allocation).map { (party, seats) ->
                party.copy(seats = seats.total, districtMandates = seats.direct, reservedSeats = seats.women + seats.nonMuslim)
            }
        val unfilled = if (complete) 70 - women.values.sum() - minorities.values.sum() else 0
        status =
            status.copy(
                complete = complete,
                affiliations = status.affiliations + filings,
                allocation = allocation,
                unfilledReserved = unfilled,
            )
        report = status
        val notice =
            if (complete) {
                "입당 신고가 마감됐다. 무소속 당선인 ${status.affiliations.size}명이 입당했고 여성 ${women.values.sum()}석·비무슬림 ${minorities.values.sum()}석을 배분했다."
            } else {
                "무소속 지역구 당선인 ${filings.size}명의 입당 신고를 접수했다."
            }
        return PakistanFiling(chamber.withGroups(groups), status, notice)
    }
}
