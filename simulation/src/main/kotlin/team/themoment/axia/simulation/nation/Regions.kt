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
}

data class ElectoralRegion(
    val id: String,
    val name: String,
    val populationShare: Double,
    val approval: Double,
)

/** 지역 하나의 현황. */
data class RegionSnapshot(
    val regionId: String,
    val name: String,
    /** 나라 인구 중 이 지역 비중. */
    val populationShare: Double,
    val population: Double,
    /** 지역 인구 중 도시 인구 비율. */
    val urbanShare: Double,
    /** 1인당 소득의 전국 대비 배율. */
    val relativeIncome: Double,
    /** 지역 지지율. 지역의 인구 집단 구성 차이로 전국 지지율에서 벗어난다. */
    val approval: Double,
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

/**
 * 플레이어 국가의 지역 분포(1차 행정구역).
 *
 * - 지역 인구 = 도시 인구(도시화율 × Natural Earth 도시 인구 비중) + 농촌 인구(나머지 × 면적 비중). 추정이다.
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
        val groupDensity: Map<PopulationGroup, Double>,
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

    fun assign(
        id: String,
        program: RegionalProgram,
    ) {
        if (regions.none { it.id == id && it.share > 0.0 }) return
        if (program == RegionalProgram.NONE) programs.remove(id) else programs[id] = program
    }

    val spending: Double get() = regions.sumOf { it.share * (programs[it.id]?.annualCost ?: 0.0) }
    val employmentEffect: Double get() = regions.sumOf { it.share * (jobs[it.id] ?: 0.0) } * 0.01

    fun advanceMonth() {
        regions.forEach { region ->
            val id = region.id
            val program = programs[id]
            services[id] = (services[id] ?: 0.0) * 0.85 + if (program == RegionalProgram.SERVICES) 0.15 else 0.0
            jobs[id] = (jobs[id] ?: 0.0) * 0.85 + if (program == RegionalProgram.JOBS) 0.15 else 0.0
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
            regionalDemographics(own, nationalUrbanShare, file.regions).map {
                it.copy(share = holdings[it.region.id]?.population ?: it.share)
            }
        val urbanWeight = raw.sumOf { it.share * it.urban }
        val incomeRaw = raw.associate { (region, _, urban) -> region.id to (INCOME_BASE + INCOME_URBAN * urban) }
        val average = raw.sumOf { (region, share, _) -> share * incomeRaw.getValue(region.id) }.coerceAtLeast(1e-9)
        return raw.map { (region, share, urban) ->
            RegionState(
                region.id,
                region.name,
                share,
                urban,
                groupDensity(urban - urbanWeight),
                incomeRaw.getValue(region.id) / average,
                RegionalEconomy.density(urban, urbanWeight),
                participatesInDistricts = region.territoryId == null || region.countryId != countryId,
            )
        }
    }

    /** 도시화율 차이로 생기는 직업별 입지 배율. 매월 현재 전국 인구 비중과 결합한다. */
    private fun groupDensity(tilt: Double): Map<PopulationGroup, Double> =
        URBAN_TILT.mapValues { (_, sensitivity) -> (1.0 + sensitivity * tilt).coerceAtLeast(0.1) }

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
        val weightedUnemployment =
            regions.indices.sumOf { i ->
                estimates[i].laborForceShare * estimates[i].unemployment * placementWeight(regions[i].id)
            }
        return regions.mapIndexed { i, region ->
            val estimate = estimates[i]
            val localUnemployment =
                if (weightedUnemployment > 0) {
                    estimate.unemployment * placementWeight(region.id) * unemployment / weightedUnemployment
                } else {
                    estimate.unemployment
                }
            val program = programs[region.id] ?: RegionalProgram.NONE
            RegionSnapshot(
                regionId = region.id,
                name = region.name,
                populationShare = region.share,
                population = population * region.share,
                urbanShare = region.urban,
                relativeIncome = region.income,
                approval = sentiments.getValue(region.id).approval,
                economy =
                    estimate.copy(
                        unemployment = localUnemployment,
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
        return regions.map { region ->
            val density = groupShares.entries.sumOf { (group, share) -> share * region.groupDensity.getValue(group) }
            val shift =
                PopulationGroup.entries.sumOf { group ->
                    approvalWeights.getValue(group) * (region.groupDensity.getValue(group) / density - 1.0) *
                        (satisfaction.getValue(group) - average)
                }
            val benefit = 7.0 * (services[region.id] ?: 0.0) + 4.0 * (jobs[region.id] ?: 0.0)
            ElectoralRegion(region.id, region.name, region.share, (approval + shift + benefit).coerceIn(0.0, 100.0))
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
