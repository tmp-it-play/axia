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

internal enum class CurrencyNotice { PEG_ENDED, IMF_AVAILABLE, IMF_DISBURSED }

/**
 * 모든 주권국의 거시경제. 매월 같은 모델로 갱신하고 지표를 기록한다.
 * [fiscalPolicies]에 없는 국가는 AI 재정 대응([AutomaticFiscalPolicy])을 쓴다.
 */
class WorldEconomy internal constructor(
    data: EconomyData,
    centralBanks: CentralBankData,
    fiscalPolicies: Map<String, FiscalPolicy>,
    private val playerCountryId: String?,
    private val startMonth: YearMonth,
    seed: Long,
) {
    internal val monetary = MonetarySystem(centralBanks, data.countries.values, playerCountryId, startMonth, seed)
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
    private val companies = countries.associate { it.id to Companies(it.id, Random(seed xor it.id.hashCode().toLong())) }

    fun renameCompany(
        id: Int,
        name: String,
    ) {
        companies[playerCountryId]?.rename(id, name)
    }

    internal fun setLegislatedFiscalTargets(
        countryId: String,
        revenue: Double,
        spending: Double,
    ) = country(countryId).setLegislatedFiscalTargets(revenue, spending)

    private val reserveCurrencyIssuer = countriesById.getValue(RESERVE_CURRENCY_ISSUER)
    private val trade = TradeNetwork(countries, data, startMonth)
    private val remittances = RemittanceNetwork(countries)
    private val investmentIncome = WorldInvestmentIncome(countries)
    internal val currencyNotices = mutableListOf<Pair<String, CurrencyNotice>>()
    private val histories =
        countries.associate { country ->
            val limit = if (country.id == playerCountryId) null else OTHER_COUNTRY_HISTORY_MONTHS
            country.id to EconomySeries.entries.associateWith { MonthlySeries(limit) }
        }

    init {
        require(fiscalPolicies.keys.all(countriesById::containsKey)) { "Unknown country in fiscal policies: ${fiscalPolicies.keys}" }
        record()
    }

    /** 국가 순서와 난수 사용 순서가 고정돼 있어 같은 시드면 같은 결과가 나온다. */
    internal fun advanceMonth(
        random: Random,
        international: InternationalState,
        period: YearMonth,
        grants: Map<String, Double>,
        imfMember: (String) -> Boolean,
    ): Map<String, DebtNotice> {
        currencyNotices.clear()
        val dollarInflation = reserveCurrencyIssuer.inflation
        val external = trade.advanceMonth(international, period)
        val transfers = remittances.settle()
        val investment = investmentIncome.settle()
        monetary.decide(countriesById, period.plusMonths(1))
        val us = monetary.conditions(RESERVE_CURRENCY_ISSUER)
        val usRealRate = us.effectiveRate - reserveCurrencyIssuer.expectedInflation
        val euroExchange = countriesById.getValue(EURO_REFERENCE).realExchange
        val debtNotices = mutableMapOf<String, DebtNotice>()
        val totalGrants = grants.values.sum()
        countries.forEachIndexed { i, country ->
            val grant = if (country.id == playerCountryId) -totalGrants else grants[country.id] ?: 0.0
            val bank = monetary.banks.getValue(country.id)
            val defendsPeg = bank.framework == MonetaryFramework.CURRENCY_PEG && country.id in ADJUSTABLE_PEGS
            val payments = country.externalAccount
            val hasFailed = payments.pegFailed
            val wasEligible = payments.canRequest
            val flows = external.getValue(country.id)
            val pressure =
                payments.closeMonth(
                    flows,
                    transfers[i],
                    investment[i],
                    grant,
                    country.dollarGdp,
                    country.debtRatio,
                    country.inflation,
                    imfMember(country.id),
                    defendsPeg,
                )
            if (!hasFailed && payments.pegFailed) {
                currencyNotices += country.id to CurrencyNotice.PEG_ENDED
                bank.abandonPeg()
                country.devalueAfterPeg()
            }
            val conditions = monetary.conditions(country.id)
            if (payments.canRequest) {
                if (country.id != playerCountryId) {
                    payments.request()
                    currencyNotices += country.id to CurrencyNotice.IMF_DISBURSED
                } else if (!wasEligible) {
                    currencyNotices += country.id to CurrencyNotice.IMF_AVAILABLE
                }
            }
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
                    ExchangeInputs(usRealRate, anchor, random.nextGaussian(), pressure),
                    grant,
                )?.let { debtNotices[country.id] = it }
            companies.getValue(country.id).advance(
                period,
                country.sectorLines(),
                country.dollarGdp,
                country.policyRate - country.expectedInflation,
                country::addSectorShock,
            )
        }
        record()
        return debtNotices
    }

    internal fun setStartingTradePolicy(state: InternationalState) = trade.setStartingPolicy(state)

    internal fun commodityQuotes(): Map<Commodity, CommodityQuote> = trade.commodityQuotes()

    fun tradeSnapshot(): TradeSnapshot = trade.snapshot()

    /** [a]의 시작 시점 수출 중 [b]로 가는 비중. */
    internal fun tradeShare(
        a: String,
        b: String,
    ): Double = trade.baseShare(a, b)

    internal fun country(id: String): CountryEconomy = countriesById.getValue(id)

    fun indicators(): Map<String, EconomyIndicators> = countries.associate { it.id to it.indicators() }

    fun snapshot(date: LocalDate): EconomySnapshot =
        EconomySnapshot(
            date = date,
            countries = indicators(),
            history =
                histories.mapValues { (_, series) ->
                    val dropped = series.getValue(EconomySeries.GDP).dropped
                    CountryHistory(startMonth.plusMonths(dropped.toLong()), series.mapValues { it.value.toList() })
                },
            monetary = countries.associate { it.id to monetary.policy(it.id, it.expectedInflation) },
            sectors = countries.associate { it.id to it.sectorLines() },
            externalAccounts = countries.associate { it.id to it.externalAccount.snapshot() },
            companies = companies.mapValues { it.value.current },
            companyClosures = companies.mapValues { it.value.closures },
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
        const val OTHER_COUNTRY_HISTORY_MONTHS = 50 * 12

        /** 지표를 표시하는 기준 통화(달러)의 발행국. */
        const val RESERVE_CURRENCY_ISSUER = "USA"

        /** 유로에 고정한 나라가 따라가는 유로권 실질환율의 대표국. */
        const val EURO_REFERENCE = "DEU"

        // 자국 통화를 방어하는 단일국 페그. 공동·타국 통화 사용국은 단독으로 페그를 포기할 수 없다.
        val ADJUSTABLE_PEGS = setOf("SAU", "ARE", "QAT", "BHR", "OMN", "JOR", "DNK", "BGR", "BIH")
    }
}
