package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World

/** 사회 구조 초기값. 비율은 모두 소수다. */
@Serializable
data class InitialSociety(
    val incomeGroup: String,
    val agricultureEmployment: Double,
    val industryEmployment: Double,
    val servicesEmployment: Double,
    val selfEmployment: Double,
    val elderlyShare: Double,
    val childShare: Double,
    val gini: Double,
)

/** 예산 구성 초기값. 지출은 GDP 대비, 세입 구성은 세입 대비 비율이다. */
@Serializable
data class InitialBudget(
    val defense: Double,
    val education: Double,
    val health: Double,
    val incomeTaxShare: Double,
    val corporateTaxShare: Double,
    val consumptionTaxShare: Double,
    val socialContributionShare: Double,
    val tradeTaxShare: Double,
    val otherRevenueShare: Double,
)

/** 2023년 기준 국가별 경제 초기값. 비율은 모두 소수다. */
@Serializable
data class InitialEconomy(
    val id: String,
    val population: Double,
    val populationGrowth: Double,
    val gdp: Double,
    val growth: Double,
    val potentialGrowth: Double,
    val growthVolatility: Double,
    val inflation: Double,
    val unemployment: Double,
    val revenue: Double,
    val primarySpending: Double,
    val debt: Double,
    val interestRate: Double,
    val society: InitialSociety,
    val budget: InitialBudget,
    val estimated: Set<String>,
)

/** 모든 주권국의 경제 초기값. 세계 정의의 주권국과 정확히 일치해야 한다. */
class EconomyData private constructor(
    val countries: Map<String, InitialEconomy>,
) {
    fun country(id: String): InitialEconomy = countries.getValue(id)

    internal fun mapInitial(transform: (InitialEconomy) -> InitialEconomy): EconomyData =
        EconomyData(
            countries.mapValues {
                transform(it.value)
            },
        )

    companion object {
        private const val DEFAULT_DATA = "/world/economy.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun loadDefault(world: World): EconomyData {
            val text =
                requireNotNull(EconomyData::class.java.getResourceAsStream(DEFAULT_DATA)) { "Missing $DEFAULT_DATA" }
                    .use { it.readBytes().decodeToString() }
            return parse(world, text)
        }

        fun parse(
            world: World,
            text: String,
        ): EconomyData {
            val file = json.decodeFromString<EconomyFile>(text)
            val entries = file.countries.associateBy(InitialEconomy::id)
            require(entries.size == file.countries.size) { "Duplicate economy entry" }
            val states =
                world.countries.values
                    .filter { it.kind == CountryKind.STATE }
                    .map { it.id }
                    .toSet()
            val missing = states - entries.keys
            val unknown = entries.keys - states
            require(missing.isEmpty() && unknown.isEmpty()) {
                "Economy entries must match states. Missing: ${missing.take(5)}, unknown: ${unknown.take(5)}"
            }
            return EconomyData(entries)
        }
    }

    @Serializable
    private data class EconomyFile(
        val baseYear: Int,
        val countries: List<InitialEconomy>,
    )
}
