package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** 2023년 기준 국가별 경제 초기값. 비율은 모두 소수다. */
@Serializable
internal data class InitialEconomy(
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
    val estimated: Set<String>,
)

class WorldEconomy internal constructor(
    countries: List<CountryEconomy>,
) {
    private val countries = countries.sortedBy(CountryEconomy::id)

    private val reserveCurrencyIssuer = this.countries.single { it.id == RESERVE_CURRENCY_ISSUER }

    /** 국가 순서와 난수 사용 순서가 고정돼 있어 같은 시드면 같은 결과가 나온다. */
    fun advanceMonth(random: Random) {
        val dollarInflation = reserveCurrencyIssuer.inflationRate
        countries.forEach { it.advanceMonth(random.nextGaussian(), dollarInflation) }
    }

    fun snapshot(date: LocalDate): EconomySnapshot = EconomySnapshot(date, countries.associate { it.id to it.indicators() })

    companion object {
        private const val DEFAULT_DATA = "/world/economy.json"

        /** 지표를 표시하는 기준 통화(달러)의 발행국. */
        private const val RESERVE_CURRENCY_ISSUER = "USA"
        private val json = Json { ignoreUnknownKeys = false }

        fun loadDefault(world: World): WorldEconomy {
            val text =
                requireNotNull(WorldEconomy::class.java.getResourceAsStream(DEFAULT_DATA)) { "Missing $DEFAULT_DATA" }
                    .use { it.readBytes().decodeToString() }
            return parse(world, text)
        }

        fun parse(
            world: World,
            text: String,
        ): WorldEconomy {
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
            return WorldEconomy(entries.values.map { CountryEconomy(it.id, it) })
        }
    }

    @Serializable
    private data class EconomyFile(
        val baseYear: Int,
        val countries: List<InitialEconomy>,
    )
}

/** 표준정규분포 표본(Box-Muller). */
private fun Random.nextGaussian(): Double {
    val u1 = 1.0 - nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
