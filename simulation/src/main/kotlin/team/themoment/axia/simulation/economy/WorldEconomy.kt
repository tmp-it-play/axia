package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.history.MonthlySeries
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 모든 주권국의 거시경제. 매월 같은 모델로 갱신하고 지표를 기록한다.
 * [fiscalPolicies]에 없는 국가는 AI 재정 대응([AutomaticFiscalPolicy])을 쓴다.
 */
class WorldEconomy internal constructor(
    data: EconomyData,
    fiscalPolicies: Map<String, FiscalPolicy>,
    private val startMonth: YearMonth,
) {
    private val countries =
        data.countries.values
            .sortedBy(InitialEconomy::id)
            .map { CountryEconomy(it.id, it, fiscalPolicies[it.id] ?: AutomaticFiscalPolicy(it)) }
    private val countriesById = countries.associateBy(CountryEconomy::id)
    private val reserveCurrencyIssuer = countriesById.getValue(RESERVE_CURRENCY_ISSUER)
    private val histories = countries.associate { country -> country.id to EconomySeries.entries.associateWith { MonthlySeries() } }

    init {
        require(fiscalPolicies.keys.all(countriesById::containsKey)) { "Unknown country in fiscal policies: ${fiscalPolicies.keys}" }
        record()
    }

    /** 국가 순서와 난수 사용 순서가 고정돼 있어 같은 시드면 같은 결과가 나온다. */
    fun advanceMonth(random: Random) {
        val dollarInflation = reserveCurrencyIssuer.inflation
        countries.forEach { it.advanceMonth(random.nextGaussian(), dollarInflation) }
        record()
    }

    internal fun country(id: String): CountryEconomy = countriesById.getValue(id)

    fun snapshot(date: LocalDate): EconomySnapshot =
        EconomySnapshot(
            date = date,
            countries = countries.associate { it.id to it.indicators() },
            history =
                histories.mapValues { (_, series) ->
                    val dropped = series.getValue(EconomySeries.GDP).dropped
                    CountryHistory(startMonth.plusMonths(dropped.toLong()), series.mapValues { it.value.toList() })
                },
        )

    private fun record() {
        countries.forEach { country ->
            val indicators = country.indicators()
            val series = histories.getValue(country.id)
            series.getValue(EconomySeries.GDP).record(indicators.gdp)
            series.getValue(EconomySeries.GDP_PER_CAPITA).record(indicators.gdpPerCapita)
            series.getValue(EconomySeries.GROWTH).record(indicators.growth)
            series.getValue(EconomySeries.INFLATION).record(indicators.inflation)
            series.getValue(EconomySeries.UNEMPLOYMENT).record(indicators.unemployment)
            series.getValue(EconomySeries.BUDGET_BALANCE).record(indicators.budgetBalance)
            series.getValue(EconomySeries.DEBT).record(indicators.debtRatio)
        }
    }

    private companion object {
        /** 지표를 표시하는 기준 통화(달러)의 발행국. */
        const val RESERVE_CURRENCY_ISSUER = "USA"
    }
}

/** 표준정규분포 표본(Box-Muller). */
private fun Random.nextGaussian(): Double {
    val u1 = 1.0 - nextDouble()
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
