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

/**
 * 공식 지역 인구는 그 자료가 포함하는 구역 사이의 상대 지분을 결정한다.
 * 통계 밖 영토는 기존 추정 몫을 유지한다. 관할 변경 뒤에는 양도 직전 장부의 지분을 쓴다.
 */
internal fun regionalDemographics(
    regions: List<Region>,
    nationalUrban: Double,
    records: Map<String, RegionRecord>,
    holdings: Map<String, TerritorialShare> = emptyMap(),
): List<RegionBaseline> {
    val estimates = estimatedDemographics(regions, nationalUrban, records)
    if (holdings.isNotEmpty()) {
        return reweightPopulation(estimates, estimates.map { holdings.getValue(it.region.id).population }, nationalUrban)
    }
    val country = regions.firstOrNull()?.countryId ?: return emptyList()
    val observed = RegionalPopulation.byCountry[country] ?: return estimates
    val covered = estimates.filter { it.region.id in observed.counts }
    if (covered.isEmpty()) return estimates
    val observedTotal = covered.sumOf { observed.counts.getValue(it.region.id) }
    require(observedTotal > 0)
    val coveredShare = covered.sumOf { it.share }
    val nationalShares = covered.map { coveredShare * observed.counts.getValue(it.region.id) / observedTotal }
    val urbanTarget = covered.sumOf { it.share * it.urban } / coveredShare
    val measured = reweightPopulation(covered, nationalShares, urbanTarget).associateBy { it.region.id }
    return estimates.map { measured[it.region.id] ?: it }
}

/** 도시화율은 여전히 추정치다. 새 인구 비중으로 재가중해도 같은 범위의 도시 인구 총량을 보존한다. */
private fun reweightPopulation(
    regions: List<RegionBaseline>,
    shares: List<Double>,
    urbanTarget: Double,
): List<RegionBaseline> {
    val total = shares.sum()
    val meanUrban = regions.indices.sumOf { shares[it] * regions[it].urban } / total
    val target = urbanTarget.coerceIn(0.0, 1.0)
    return regions.mapIndexed { index, baseline ->
        val urban =
            when {
                meanUrban < target -> baseline.urban + (1.0 - baseline.urban) * (target - meanUrban) / (1.0 - meanUrban)
                meanUrban > target -> baseline.urban * target / meanUrban
                else -> baseline.urban
            }
        baseline.copy(share = shares[index], urban = urban.coerceIn(0.0, 1.0))
    }
}

private fun estimatedDemographics(
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
