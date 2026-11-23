package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.history.MonthlySeries
import team.themoment.axia.simulation.math.nextGaussian
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
    centralBanks: CentralBankData,
    fiscalPolicies: Map<String, FiscalPolicy>,
    playerCountryId: String?,
    private val startMonth: YearMonth,
) {
    internal val monetary = MonetarySystem(centralBanks, data.countries.values, playerCountryId)
    private val countries =
        data.countries.values
            .sortedBy(InitialEconomy::id)
            .map { initial ->
                val bank = monetary.banks.getValue(initial.id)
                CountryEconomy(
                    initial.id,
                    initial,
                    fiscalPolicies[initial.id] ?: AutomaticFiscalPolicy(initial),
                    bank.initialRate,
                    bank.estimated,
                )
            }
    private val countriesById = countries.associateBy(CountryEconomy::id)
    private val reserveCurrencyIssuer = countriesById.getValue(RESERVE_CURRENCY_ISSUER)
    private val trade = TradeNetwork(countries.associate { it.id to it.dollarGdp })
    private val histories = countries.associate { country -> country.id to EconomySeries.entries.associateWith { MonthlySeries() } }

    init {
        require(fiscalPolicies.keys.all(countriesById::containsKey)) { "Unknown country in fiscal policies: ${fiscalPolicies.keys}" }
        record()
    }

    /** 국가 순서와 난수 사용 순서가 고정돼 있어 같은 시드면 같은 결과가 나온다. */
    internal fun advanceMonth(
        random: Random,
        international: InternationalState = InternationalState.PEACEFUL,
    ): Map<String, DebtNotice> {
        val dollarInflation = reserveCurrencyIssuer.inflation
        val external =
            trade.advanceMonth(
                international,
                gaps = countries.associate { it.id to it.outputGap },
                gdp = countries.associate { it.id to it.dollarGdp },
            )
        monetary.decide(countriesById)
        val us = monetary.conditions(RESERVE_CURRENCY_ISSUER)
        val usRealRate = us.effectiveRate - reserveCurrencyIssuer.expectedInflation
        val euroExchange = countriesById.getValue(EURO_REFERENCE).realExchange
        val debtNotices = mutableMapOf<String, DebtNotice>()
        countries.forEach { country ->
            val conditions = monetary.conditions(country.id)
            val anchor =
                when {
                    country.id == RESERVE_CURRENCY_ISSUER -> 0.0
                    conditions.pegAnchor == RESERVE_CURRENCY_ISSUER -> 0.0
                    conditions.pegAnchor != null -> euroExchange
                    else -> null
                }
            country
                .advanceMonth(
                    random.nextGaussian(),
                    dollarInflation,
                    conditions,
                    external.getValue(country.id),
                    ExchangeInputs(usRealRate, anchor, random.nextGaussian()),
                )?.let { debtNotices[country.id] = it }
        }
        record()
        return debtNotices
    }

    fun tradeSnapshot(): TradeSnapshot = trade.snapshot()

    /** [a]의 시작 시점 수출 중 [b]로 가는 비중. */
    internal fun tradeShare(
        a: String,
        b: String,
    ): Double = trade.baseShare(a, b)

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
            monetary = countries.associate { it.id to monetary.policy(it.id, it.expectedInflation) },
            sectors = countries.associate { it.id to it.sectorLines() },
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
            series.getValue(EconomySeries.POLICY_RATE).record(indicators.policyRate)
        }
    }

    private companion object {
        /** 지표를 표시하는 기준 통화(달러)의 발행국. */
        const val RESERVE_CURRENCY_ISSUER = "USA"

        /** 유로에 고정한 나라가 따라가는 유로권 실질환율의 대표국. */
        const val EURO_REFERENCE = "DEU"
    }
}
