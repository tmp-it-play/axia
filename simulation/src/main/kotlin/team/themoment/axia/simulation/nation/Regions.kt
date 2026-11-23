package team.themoment.axia.simulation.nation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.economy.SectorLine
import team.themoment.axia.simulation.world.World

/** 전국에 같은 사업을 시행할 때의 연 GDP 대비 비용. 한 지역의 비용은 인구 몫만큼이다. */
enum class RegionalProgram(
    val label: String,
    val annualCost: Double,
) {
    NONE("사업 없음", 0.0),
    JOBS("고용 지원", 0.006),
    SERVICES("생활 서비스", 0.004),
    PUBLIC_SAFETY("지역 치안", 0.003),
}

data class ElectoralRegion(
    val id: String,
    val name: String,
    val populationShare: Double,
    val approval: Double,
    val groupShares: Map<PopulationGroup, Double>,
)

/** 지역 하나의 현황. */
data class RegionSnapshot(
    val regionId: String,
    val name: String,
    /** 나라 인구 중 이 지역 비중. */
    val populationShare: Double,
    val population: Double,
    /** 초기 인구 지분의 통계 출처·기준일. 도시·면적 추정 지역이면 null. */
    val populationSource: String?,
    /** 지역 인구 중 도시 인구 비율. */
    val urbanShare: Double,
    /** 1인당 소득의 전국 대비 배율. */
    val relativeIncome: Double,
    /** 지역 지지율. 지역의 인구 집단 구성 차이로 전국 지지율에서 벗어난다. */
    val approval: Double,
    val groupShares: Map<PopulationGroup, Double>,
    /** 전국 시작 지수 100을 기준으로 한 지역 범죄 발생 압력. */
    val crime: Double,
    val economy: RegionEconomySnapshot,
    val program: RegionalProgram,
    val programSpending: Double,
    val participatesInDistricts: Boolean,
)

@Serializable
internal data class RegionRecord(
    val areaKm2: Double,
    val cityPopulation: Double,
)

@Serializable
internal data class RegionFile(
    val sourceNote: String,
    val regions: Map<String, RegionRecord>,
) {
    companion object {
        val default: RegionFile by lazy {
            val text =
                requireNotNull(RegionFile::class.java.getResourceAsStream("/world/regions.json")) { "Missing regions.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<RegionFile>(text)
        }
    }
}

/** 공식 통계 구역을 겹치지 않게 지도 구역에 대응한 초기 인구. */
@Serializable
internal data class RegionalPopulation(
    val date: String,
    val source: String,
    val url: String,
    val total: Long,
    val counts: Map<String, Long>,
) {
    init {
        require(total > 0 && counts.values.all { it >= 0 } && counts.values.sum() == total)
    }

    companion object {
        val byCountry: Map<String, RegionalPopulation> by lazy {
            val text =
                requireNotNull(RegionalPopulation::class.java.getResourceAsStream("/world/region_population.json"))
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<Map<String, RegionalPopulation>>(text)
        }
    }
}

/**
 * 플레이어 국가의 지역 분포(1차 행정구역).
 *
 * - 인구는 공식 통계 구역별 비중을 사용하고, 자료 밖 구역은 도시 표본과 면적으로 추정한다.
 * - 지역 집단 구성: 도시화율이 높을수록 전문·관리직·사무직·자본가가 많고, 낮을수록 농림어업·은퇴층이 많다.
 * - 지역 지지율 = 전국 지지율 + 지역 구성 차이 × (집단 만족도 − 평균). 모든 집단이 같은 만족도면 전국과 같다.
 * - 1인당 소득은 도시화율이 높을수록 높다(전국 평균 = 1).
 */
internal class Regions(
    private val world: World,
    private val countryId: String,
    private val nationalUrbanShare: Double,
    private val file: RegionFile = RegionFile.default,
) {
    private class RegionState(
        val id: String,
        val name: String,
        val share: Double,
        val urban: Double,
        val income: Double,
        val sectorDensity: Map<Sector, Double>,
        val participatesInDistricts: Boolean,
    )

    private var ownership = emptyMap<String, String>()
    private var holdings = emptyMap<String, TerritorialShare>()
    private var regions: List<RegionState> = buildRegions(ownership)
    private val programs = mutableMapOf<String, RegionalProgram>()
    private val services = mutableMapOf<String, Double>()
    private val jobs = mutableMapOf<String, Double>()

    private val crimeAdjustment = mutableMapOf<String, Double>()
    private var baseCrime = 100.0

    val nationalCrime: Double get() = baseCrime * (1.0 + regions.sumOf { it.share * (crimeAdjustment[it.id] ?: 0.0) })

    private fun localCrime(id: String): Double = baseCrime * (1.0 + (crimeAdjustment[id] ?: 0.0))

    fun assign(
        id: String,
        program: RegionalProgram,
    ) {
        if (regions.none { it.id == id && it.share > 0.0 }) return
        if (program == RegionalProgram.NONE) programs.remove(id) else programs[id] = program
    }

    val spending: Double get() = regions.sumOf { it.share * (programs[it.id]?.annualCost ?: 0.0) }
    val employmentEffect: Double get() = regions.sumOf { it.share * (jobs[it.id] ?: 0.0) } * 0.01

    fun advanceMonth(
        unemployment: Double,
        sectors: List<SectorLine>,
        nationalBaseCrime: Double,
    ) {
        baseCrime = nationalBaseCrime
        regions.forEach { region ->
            val id = region.id
            val program = programs[id]
            services[id] = (services[id] ?: 0.0) * 0.85 + if (program == RegionalProgram.SERVICES) 0.15 else 0.0
            jobs[id] = (jobs[id] ?: 0.0) * 0.85 + if (program == RegionalProgram.JOBS) 0.15 else 0.0
        }
        val economy = RegionalEconomy(sectors, unemployment)
        val estimates = regions.map { economy.estimate(it.share, it.sectorDensity) }
        val localRates = placedUnemployment(estimates, unemployment)
        val meanRate = regions.indices.sumOf { i -> regions[i].share * localRates[i] }
        val meanUrban = regions.sumOf { it.urban * it.share }
        regions.forEachIndexed { i, region ->
            val current = crimeAdjustment[region.id] ?: 0.0
            val prevention = if (programs[region.id] == RegionalProgram.PUBLIC_SAFETY) 0.18 else 0.0
            val target =
                3.0 * (localRates[i] - meanRate) + 0.08 * (region.urban - meanUrban) -
                    0.06 * (services[region.id] ?: 0.0) - prevention
            crimeAdjustment[region.id] = current + (target.coerceIn(-0.6, 0.8) - current) / 6.0
        }
    }

    fun updateOwners(
        changes: Map<String, String>,
        shares: Map<String, TerritorialShare>,
    ) {
        val relevant = changes.filter { (id, owner) -> owner == countryId || world.regions[id]?.countryId == countryId }
        if (ownership == relevant && shares == holdings) return
        holdings = shares
        ownership = relevant
        regions = buildRegions(ownership)
        val remaining = regions.mapTo(mutableSetOf()) { it.id }
        programs.keys.retainAll(remaining)
        jobs.keys.retainAll(remaining)
        services.keys.retainAll(remaining)
        crimeAdjustment.keys.retainAll(remaining)
    }

    private fun buildRegions(owners: Map<String, String>): List<RegionState> {
        val own =
            world.regions.values
                .filter { region ->
                    if (holdings.isNotEmpty()) {
                        region.id in holdings
                    } else {
                        (owners[region.id] ?: region.countryId) == countryId
                    }
                }.sortedBy { it.id }
        val raw =
            regionalDemographics(own, nationalUrbanShare, file.regions, holdings)
        val urbanWeight = raw.sumOf { it.share * it.urban }
        val incomeRaw = raw.associate { (region, _, urban) -> region.id to (INCOME_BASE + INCOME_URBAN * urban) }
        val average = raw.sumOf { (region, share, _) -> share * incomeRaw.getValue(region.id) }.coerceAtLeast(1e-9)
        return raw.map { (region, share, urban) ->
            RegionState(
                region.id,
                region.name,
                share,
                urban,
                incomeRaw.getValue(region.id) / average,
                RegionalEconomy.density(urban, urbanWeight),
                participatesInDistricts = region.territoryId == null || region.countryId != countryId,
            )
        }
    }

    fun snapshot(
        population: Double,
        groupShares: Map<PopulationGroup, Double>,
        unemployment: Double,
        sectors: List<SectorLine>,
        approval: Double,
        approvalWeights: Map<PopulationGroup, Double>,
        satisfaction: Map<PopulationGroup, Double>,
    ): List<RegionSnapshot> {
        val sentiments = regionalSentiment(groupShares, approval, approvalWeights, satisfaction).associateBy { it.id }
        val economy = RegionalEconomy(sectors, unemployment)
        val estimates = regions.map { economy.estimate(it.share, it.sectorDensity) }
        // 전국 실업자 수는 유지하고, 고용 지원이 집중된 지역의 실업자 몫을 낮춘다.
        val sectorAllocations = if (holdings.isEmpty()) emptyList() else reconcileOutput(estimates, sectors)
        val localRates = placedUnemployment(estimates, unemployment)
        return regions.mapIndexed { i, region ->
            val estimate = estimates[i]
            val program = programs[region.id] ?: RegionalProgram.NONE
            RegionSnapshot(
                regionId = region.id,
                name = region.name,
                populationShare = region.share,
                population = population * region.share,
                populationSource =
                    RegionalPopulation.byCountry[world.regions.getValue(region.id).countryId]
                        ?.takeIf { region.id in it.counts }
                        ?.let { "${it.source} · ${it.date}" },
                urbanShare = region.urban,
                relativeIncome = region.income,
                approval = sentiments.getValue(region.id).approval,
                groupShares = sentiments.getValue(region.id).groupShares,
                crime = localCrime(region.id),
                economy =
                    estimate.copy(
                        unemployment = localRates[i],
                        gdpShare = holdings[region.id]?.gdp ?: estimate.gdpShare,
                        sectors = sectorAllocations.getOrNull(i) ?: estimate.sectors,
                    ),
                program = program,
                programSpending = region.share * program.annualCost,
                participatesInDistricts = region.participatesInDistricts,
            )
        }
    }

    /** 양도 후 지역 GDP 총량과 전국 산업별 부가가치를 동시에 맞춘다(행·열 반복 비례 배분). */
    private fun reconcileOutput(
        estimates: List<RegionEconomySnapshot>,
        sectors: List<SectorLine>,
    ): List<List<RegionSector>> {
        val target = regions.map { holdings.getValue(it.id).gdp }
        val matrix = estimates.mapIndexed { i, region -> DoubleArray(sectors.size) { j -> region.sectors[j].share * target[i] } }
        for (iteration in 0 until 32) {
            for (j in sectors.indices) {
                val sum = matrix.sumOf { it[j] }
                if (sum > 0.0) for (row in matrix) row[j] *= sectors[j].share / sum
            }
            var error = 0.0
            matrix.forEachIndexed { i, row ->
                val sum = row.sum()
                error = maxOf(error, kotlin.math.abs(sum - target[i]))
                if (sum > 0.0) for (j in row.indices) row[j] *= target[i] / sum
            }
            if (error < 1e-12) break
        }
        return estimates.mapIndexed { i, region ->
            if (target[i] == 0.0) region.sectors else region.sectors.mapIndexed { j, line -> line.copy(share = matrix[i][j] / target[i]) }
        }
    }

    private fun placedUnemployment(
        estimates: List<RegionEconomySnapshot>,
        national: Double,
    ): List<Double> {
        val adjusted = regions.indices.map { i -> estimates[i].unemployment * placementWeight(regions[i].id) }
        val total = adjusted.indices.sumOf { i -> estimates[i].laborForceShare * adjusted[i] }
        return if (total > 0.0) adjusted.map { it * national / total } else adjusted
    }

    private fun placementWeight(id: String): Double = 1.0 - (jobs[id] ?: 0.0) * 0.15

    fun electoralRegions(
        groupShares: Map<PopulationGroup, Double>,
        approval: Double,
        approvalWeights: Map<PopulationGroup, Double>,
        satisfaction: Map<PopulationGroup, Double>,
    ): List<ElectoralRegion> =
        regionalSentiment(groupShares, approval, approvalWeights, satisfaction).filterIndexed { i, _ -> regions[i].participatesInDistricts }

    private fun regionalSentiment(
        groupShares: Map<PopulationGroup, Double>,
        approval: Double,
        approvalWeights: Map<PopulationGroup, Double>,
        satisfaction: Map<PopulationGroup, Double>,
    ): List<ElectoralRegion> {
        val average = groupShares.entries.sumOf { (group, share) -> share * satisfaction.getValue(group) }
        val urbanMean = regions.sumOf { it.share * it.urban }
        val tiltMean = groupShares.entries.sumOf { (group, share) -> share * URBAN_TILT.getValue(group) }
        val tilt = URBAN_TILT.mapValues { it.value - tiltMean }
        // 두 축의 평균을 빼면 지역별 합은 1, 전국 인구 가중 합은 각 직업의 실제 비중이다.
        val urbanSpread = regions.maxOfOrNull { kotlin.math.abs(it.urban - urbanMean) } ?: 0.0
        val extent = urbanSpread * tilt.values.maxOf { kotlin.math.abs(it) }
        val scale = if (extent > 0.95) 0.95 / extent else 1.0
        val nationalCrimeIndex = nationalCrime
        return regions.map { region ->
            val urban = (region.urban - urbanMean) * scale
            val localGroups = groupShares.mapValues { (group, share) -> share * (1.0 + tilt.getValue(group) * urban) }
            val shift =
                PopulationGroup.entries.sumOf { group ->
                    approvalWeights.getValue(group) * tilt.getValue(group) * urban * (satisfaction.getValue(group) - average)
                }
            val benefit = 7.0 * (services[region.id] ?: 0.0) + 4.0 * (jobs[region.id] ?: 0.0)
            val security = 0.12 * (nationalCrimeIndex - localCrime(region.id))
            ElectoralRegion(region.id, region.name, region.share, (approval + shift + benefit + security).coerceIn(0.0, 100.0), localGroups)
        }
    }

    private companion object {
        const val INCOME_BASE = 0.7
        const val INCOME_URBAN = 0.6

        /** 도시화율이 전국보다 1(100%p) 높을 때 집단 비중이 바뀌는 비율. */
        val URBAN_TILT =
            mapOf(
                PopulationGroup.CAPITALISTS to 1.0,
                PopulationGroup.PROFESSIONALS to 1.2,
                PopulationGroup.OFFICE to 0.8,
                PopulationGroup.SELF_EMPLOYED to 0.0,
                PopulationGroup.WORKERS to 0.2,
                PopulationGroup.FARMERS to -2.5,
                PopulationGroup.PUBLIC_SECTOR to 0.2,
                PopulationGroup.RETIREES to -0.6,
            )
    }
}
