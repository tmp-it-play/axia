package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.SectorLine
import team.themoment.axia.simulation.world.Region
import team.themoment.axia.simulation.world.StructureData
import team.themoment.axia.simulation.world.World

/** 양도 직전 해당 국가의 총량 중 지역 몫. 두 값은 각각 국가별 합계가 1이다. */
data class TerritorialShare(
    val population: Double,
    val gdp: Double,
)

data class TerritorialTransfer(
    val population: Double,
    val gdp: Double,
)

internal data class RegionBaseline(
    val region: Region,
    val share: Double,
    val urban: Double,
)

/** 면적·도시 자료가 아예 없는 국가에서는 행정구역 수로 균분한다. */
internal fun regionalDemographics(
    regions: List<Region>,
    nationalUrban: Double,
    records: Map<String, RegionRecord>,
): List<RegionBaseline> {
    val totalCity = regions.sumOf { records[it.id]?.cityPopulation ?: 0.0 }
    val totalArea = regions.sumOf { records[it.id]?.areaKm2 ?: 0.0 }
    if (totalArea == 0.0 && totalCity == 0.0) return regions.map { RegionBaseline(it, 1.0 / regions.size, nationalUrban) }
    // 관측값이 0인 작은 섬도 협정 뒤 한 나라의 마지막 지역으로 남을 수 있다.
    val area = regions.associate { it.id to (records[it.id]?.areaKm2 ?: 0.0).coerceAtLeast(0.001) }
    val allocatedArea = area.values.sum()
    val urbanWeight = if (totalCity > 0.0) nationalUrban.coerceAtMost(0.999999) else 0.0
    return regions.map { region ->
        val record = records[region.id]
        val urban = if (totalCity > 0.0 && record != null) urbanWeight * record.cityPopulation / totalCity else 0.0
        val rural = (1.0 - urbanWeight) * area.getValue(region.id) / allocatedArea
        val share = urban + rural
        RegionBaseline(region, share, if (share > 0.0) urban / share else nationalUrban)
    }
}

/** 같은 날짜에 영토가 왕복할 때 이동한 사람과 실질 생산이 그대로 돌아오도록 양국의 분모까지 갱신한다. */
internal class TerritorialAccounts(
    private val world: World,
    private val data: EconomyData,
    private val sectorLines: (String) -> List<SectorLine> = { emptyList() },
) {
    private val holdings = mutableMapOf<String, Map<String, TerritorialShare>>()

    fun shares(country: String): Map<String, TerritorialShare> = holdings[country].orEmpty()

    private fun initial(country: String): Map<String, TerritorialShare> {
        require(country in data.countries)
        val regions =
            world.regions.values
                .filter { it.countryId == country }
                .sortedBy { it.id }
        val weights = regionalDemographics(regions, StructureData.default.of(country).urbanShare, RegionFile.default.regions)
        val urban = weights.sumOf { it.share * it.urban }
        val sectors = sectorLines(country)
        return weights.associate { (region, share, localUrban) ->
            val density = RegionalEconomy.density(localUrban, urban)
            val output = if (sectors.isEmpty()) 1.0 else sectors.sumOf { it.share * density.getValue(it.sector) }
            region.id to TerritorialShare(share, share * output)
        }
    }

    fun transfer(
        regionIds: Set<String>,
        from: String,
        to: String,
        sourcePopulation: Double,
        sourceGdp: Double,
        destinationPopulation: Double,
        destinationGdp: Double,
    ): TerritorialTransfer {
        require(from != to && regionIds.isNotEmpty())
        require(listOf(sourcePopulation, sourceGdp, destinationPopulation, destinationGdp).all { it.isFinite() && it > 0.0 })
        val source = holdings[from] ?: initial(from)
        val destination = holdings[to] ?: initial(to)
        require(regionIds.all { it in source && it !in destination }) { "Territory must belong exclusively to the source country" }
        val moved = source.filterKeys { it in regionIds }
        val remaining = source.filterKeys { it !in regionIds }
        val population = moved.values.sumOf { it.population } * sourcePopulation
        val gdp = moved.values.sumOf { it.gdp } * sourceGdp
        // 남은 몫을 직접 더하면 큰 지역을 넘긴 뒤에도 작은 잔여 지역의 정밀도를 보존한다.
        val remainingPopulation = remaining.values.sumOf { it.population }
        val remainingGdp = remaining.values.sumOf { it.gdp }
        require(remainingPopulation > 0.0 && remainingGdp > 0.0) { "Treaty would extinguish the source country" }
        val afterPopulation = destinationPopulation + population
        val afterGdp = destinationGdp + gdp
        holdings[from] =
            remaining.mapValues { (_, share) -> TerritorialShare(share.population / remainingPopulation, share.gdp / remainingGdp) }
        holdings[to] =
            destination.mapValues { (_, share) ->
                TerritorialShare(
                    share.population * destinationPopulation / afterPopulation,
                    share.gdp * destinationGdp / afterGdp,
                )
            } +
            moved.mapValues { (_, share) ->
                TerritorialShare(
                    share.population * sourcePopulation / afterPopulation,
                    share.gdp * sourceGdp / afterGdp,
                )
            }
        return TerritorialTransfer(population, gdp)
    }
}
