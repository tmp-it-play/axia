package team.themoment.axia.simulation.nation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.economy.SectorLine
import team.themoment.axia.simulation.world.World

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
    world: World,
    countryId: String,
    nationalUrbanShare: Double,
    private val groupShares: Map<PopulationGroup, Double>,
    file: RegionFile = RegionFile.default,
) {
    private class RegionState(
        val id: String,
        val name: String,
        val share: Double,
        val urban: Double,
        val mix: Map<PopulationGroup, Double>,
        val income: Double,
        val sectorDensity: Map<Sector, Double>,
    )

    private val regions: List<RegionState>

    init {
        val own =
            world.regions.values
                .filter { it.countryId == countryId && it.territoryId == null }
                .sortedBy { it.id }
        val records = own.associate { it.id to (file.regions[it.id] ?: RegionRecord(0.0, 0.0)) }
        val totalCity = records.values.sumOf { it.cityPopulation }
        val totalArea = records.values.sumOf { it.areaKm2 }.coerceAtLeast(1.0)
        val urbanWeight = if (totalCity > 0.0) nationalUrbanShare else 0.0
        val raw =
            own.map { region ->
                val record = records.getValue(region.id)
                val city = if (totalCity > 0.0) urbanWeight * record.cityPopulation / totalCity else 0.0
                val rural = (1.0 - urbanWeight) * record.areaKm2 / totalArea
                Triple(region, city + rural, if (city + rural > 0.0) city / (city + rural) else 0.0)
            }
        val incomeRaw = raw.associate { (region, _, urban) -> region.id to (INCOME_BASE + INCOME_URBAN * urban) }
        val average = raw.sumOf { (region, share, _) -> share * incomeRaw.getValue(region.id) }.coerceAtLeast(1e-9)
        regions =
            raw.map { (region, share, urban) ->
                RegionState(
                    region.id,
                    region.name,
                    share,
                    urban,
                    mix(urban, nationalUrbanShare),
                    incomeRaw.getValue(region.id) / average,
                    RegionalEconomy.density(urban, urbanWeight),
                )
            }
    }

    /** 도시화율 차이로 전국 집단 구성을 조정한다. */
    private fun mix(
        urban: Double,
        national: Double,
    ): Map<PopulationGroup, Double> {
        val tilt = urban - national
        val raw =
            groupShares.mapValues { (group, share) ->
                share * (1.0 + (URBAN_TILT[group] ?: 0.0) * tilt).coerceAtLeast(0.1)
            }
        val total = raw.values.sum()
        return raw.mapValues { it.value / total }
    }

    fun snapshot(
        population: Double,
        unemployment: Double,
        sectors: List<SectorLine>,
        approval: Double,
        approvalWeights: Map<PopulationGroup, Double>,
        satisfaction: Map<PopulationGroup, Double>,
    ): List<RegionSnapshot> {
        val average = groupShares.entries.sumOf { (group, share) -> share * satisfaction.getValue(group) }
        val economy = RegionalEconomy(sectors, unemployment)
        return regions.map { region ->
            val shift =
                PopulationGroup.entries.sumOf { group ->
                    val share = groupShares.getValue(group)
                    if (share <= 0.0) {
                        0.0
                    } else {
                        approvalWeights.getValue(group) * (region.mix.getValue(group) / share - 1.0) *
                            (satisfaction.getValue(group) - average)
                    }
                }
            RegionSnapshot(
                regionId = region.id,
                name = region.name,
                populationShare = region.share,
                population = population * region.share,
                urbanShare = region.urban,
                relativeIncome = region.income,
                approval = (approval + shift).coerceIn(0.0, 100.0),
                economy = economy.estimate(region.share, region.sectorDensity),
            )
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
