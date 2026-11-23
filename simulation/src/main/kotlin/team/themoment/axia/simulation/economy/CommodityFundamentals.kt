package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

/** 곡물은 연간 톤, 원유는 연간 배럴이다. 관측된 0은 결측과 구분한다. */
@Serializable
private data class CommodityRecord(
    val id: String,
    val cerealTonnes: Double?,
    val crudeBarrels: Double?,
    val oilDemandWeight: Double,
)

@Serializable
private data class CommodityFile(
    val baseYear: Int,
    val sourceNote: String,
    val countries: List<CommodityRecord>,
)

/** 실측 연간 생산을 월 물량으로 바꾸고, 같은 세계 총량을 국가별 기준 수요에 배분한다. */
internal class CommodityFundamentals(
    private val initial: List<InitialEconomy>,
) {
    private val basePopulation = initial.sumOf { it.population }
    val baseline: Map<Commodity, List<CommodityBaseline>>

    init {
        val observations = records
        require(initial.map { it.id }.toSet() == observations.keys) { "Commodity and economy country IDs must match" }
        // 자료가 없는 나라에는 별도 국내 생산을 배정하지 않고 수요·재고·수입은 계산한다.
        baseline =
            Commodity.entries.associateWith { commodity ->
                val production =
                    initial.map {
                        val record = observations.getValue(it.id)
                        when (commodity) {
                            Commodity.GRAIN -> record.cerealTonnes
                            Commodity.CRUDE_OIL -> record.crudeBarrels
                        }?.div(MONTHS_PER_YEAR)
                    }
                val demandWeights =
                    initial.map {
                        when (commodity) {
                            Commodity.GRAIN -> it.population
                            Commodity.CRUDE_OIL -> observations.getValue(it.id).oilDemandWeight
                        }
                    }
                val totalSupply = production.sumOf { it ?: 0.0 }
                val totalWeight = demandWeights.sum()
                require(totalWeight > 0.0 && totalSupply > 0.0)
                initial.indices.map { i ->
                    CommodityBaseline(
                        production[i] ?: 0.0,
                        totalSupply * (demandWeights[i] / totalWeight),
                        productionReported = production[i] != null,
                        harvest = if (commodity == Commodity.GRAIN) GrainHarvest.forCountry(initial[i].id) else null,
                    )
                }
            }
    }

    /** 생산능력의 장기 경로는 세계 인구·잠재소득을 따르고, 실제 생산은 각국 부문 경기와 전쟁으로 달라진다. */
    fun monthlyInputs(
        countries: List<CountryEconomy>,
        state: InternationalState,
    ): Map<Commodity, List<CommodityInput>> {
        val globalPopulation = countries.sumOf { it.populationCount } / basePopulation
        val oil = baseline.getValue(Commodity.CRUDE_OIL)
        val oilDemand = DoubleArray(countries.size)
        var oilCapacity = 0.0
        for (i in countries.indices) {
            val country = countries[i]
            val realOutput = country.realGdpPerCapita * country.populationCount / initial[i].gdp
            oilDemand[i] = realOutput.pow(OIL_INCOME_ELASTICITY)
            oilCapacity += oil[i].demand * (realOutput / (1.0 + country.outputGap)).pow(OIL_INCOME_ELASTICITY)
        }
        oilCapacity /= oil.sumOf { it.demand }
        return Commodity.entries.associateWith { commodity ->
            countries.mapIndexed { i, country ->
                val war = state.warIntensity(country.id)
                when (commodity) {
                    Commodity.GRAIN -> {
                        CommodityInput(
                            globalPopulation * (1.0 + country.sectorGap(Sector.AGRICULTURE)) * (1.0 - GRAIN_WAR_LOSS * war),
                            country.populationCount / initial[i].population,
                        )
                    }

                    Commodity.CRUDE_OIL -> {
                        CommodityInput(
                            oilCapacity * (1.0 + country.sectorGap(Sector.ENERGY)) * (1.0 - OIL_WAR_LOSS * war),
                            oilDemand[i],
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val MONTHS_PER_YEAR = 12.0
        const val OIL_INCOME_ELASTICITY = 0.45
        const val GRAIN_WAR_LOSS = 0.15
        const val OIL_WAR_LOSS = 0.30
        val records: Map<String, CommodityRecord> by lazy {
            val file =
                requireNotNull(CommodityFundamentals::class.java.getResourceAsStream("/world/commodities.json")) {
                    "Missing commodities.json"
                }.bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<CommodityFile>(it.readText()) }
            require(file.baseYear == 2023)
            require(
                file.countries.all { row ->
                    (row.cerealTonnes == null || (row.cerealTonnes.isFinite() && row.cerealTonnes >= 0.0)) &&
                        (row.crudeBarrels == null || (row.crudeBarrels.isFinite() && row.crudeBarrels >= 0.0)) &&
                        row.oilDemandWeight.isFinite() && row.oilDemandWeight >= 0.0
                },
            )
            file.countries.associateBy { it.id }.also { require(it.size == file.countries.size) { "Duplicate commodity country ID" } }
        }
    }
}
