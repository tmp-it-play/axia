package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.resource

enum class InvestmentIncomeSource(
    val label: String,
) {
    IMF_BOP("IMF 국제수지"),
    EUROSTAT_BOP("Eurostat 국제수지"),
    US_BEA("미국 경제분석국 국제거래표"),
    IMF_ARTICLE_IV("당국·IMF 연례협의 국제수지"),
    IMF_COUNTRY_REPORT("당국·IMF 국가보고서 국제수지"),
    NATIONAL_BOP("공표 국제수지"),
    NATIONAL_BOP_FY_MARCH("3월 종료 회계연도 국제수지"),
    NATIONAL_BOP_FY_SEPTEMBER("9월 말 회계연도 국제수지"),
    NATIONAL_ACCOUNTS("공표 국민계정"),
}

/** 같은 보고연도·방향의 연간 달러 금액. 손실은 음수이며 미보고 세부항목은 null이다. */
@Serializable
data class InvestmentIncomeBenchmark(
    val year: Int,
    val source: InvestmentIncomeSource,
    val total: Long,
    val dividends: Long?,
    val interest: Long?,
    val reinvested: Long?,
) {
    init {
        require(year in 1994..2023)
    }

    internal fun accrue(
        monthlyScale: Double,
        interestIndex: Double,
    ): InvestmentIncomeAmounts {
        val baseInterest = interest?.times(monthlyScale)
        val repricedInterest = baseInterest?.times(interestIndex)
        return InvestmentIncomeAmounts(
            total = total * monthlyScale + ((repricedInterest ?: 0.0) - (baseInterest ?: 0.0)),
            dividends = dividends?.times(monthlyScale),
            interest = repricedInterest,
            reinvested = reinvested?.times(monthlyScale),
        )
    }
}

@Serializable
data class NetInvestmentIncomeBenchmark(
    val year: Int,
    val source: InvestmentIncomeSource,
    val total: Long,
    val reinvested: Long?,
    val interest: Long?,
) {
    init {
        require(year in 1994..2023)
    }
}

@Serializable
data class InvestmentIncomeBenchmarks(
    val received: InvestmentIncomeBenchmark?,
    val paid: InvestmentIncomeBenchmark?,
    val netOnly: NetInvestmentIncomeBenchmark? = null,
) {
    init {
        require(netOnly == null || (received == null && paid == null))
    }
}

data class NetInvestmentIncomeAmounts(
    val total: Double,
    val reinvested: Double?,
    val interest: Double?,
) {
    val other: Double get() = total - (interest ?: 0.0) - (reinvested ?: 0.0)
}

data class InvestmentIncomeAmounts(
    val total: Double,
    val dividends: Double?,
    val interest: Double?,
    val reinvested: Double?,
) {
    val other: Double get() = total - (dividends ?: 0.0) - (interest ?: 0.0) - (reinvested ?: 0.0)
}

data class InvestmentIncomeSnapshot(
    val opening: InvestmentIncomeBenchmarks,
    val received: InvestmentIncomeAmounts? = null,
    val paid: InvestmentIncomeAmounts? = null,
    val netOnly: NetInvestmentIncomeAmounts? = null,
) {
    val net: Double get() = netOnly?.total ?: ((received?.total ?: 0.0) - (paid?.total ?: 0.0))

    /** 금융계정 대응액. 자금 유입을 양수로 표시해 재투자 순수취와 상쇄한다. */
    val reinvestmentFinancing: Double
        get() = if (netOnly != null) -(netOnly.reinvested ?: 0.0) else (paid?.reinvested ?: 0.0) - (received?.reinvested ?: 0.0)
}

@Serializable
private data class InvestmentIncomeFile(
    val unit: String,
    val countries: Map<String, InvestmentIncomeBenchmarks>,
)

internal object InvestmentIncomeData {
    val countries by lazy {
        val file = Json.decodeFromString<InvestmentIncomeFile>(resource("investment_income"))
        require(file.unit == "annual_USD")
        file.countries
    }
}

/** 양자 포지션이 없어 수취는 자국을 제외한 세계, 지급은 자국의 월초 소득·금리 여건에 연동한다. */
internal class WorldInvestmentIncome(
    private val countries: List<CountryEconomy>,
) {
    private val opening = countries.map { InvestmentIncomeData.countries.getValue(it.id) }
    private val openingGdp = DoubleArray(countries.size) { countries[it].dollarGdp }
    private val worldOpeningGdp = openingGdp.sum()
    private val initialRate = DoubleArray(countries.size) { countries[it].policyRate }
    private val repricedRate = initialRate.copyOf()
    private val openingWeightedRates = countries.indices.sumOf { openingGdp[it] * initialRate[it] }

    init {
        require(InvestmentIncomeData.countries.keys == countries.map { it.id }.toSet())
    }

    fun settle(): List<InvestmentIncomeSnapshot> {
        val worldGdp = countries.sumOf(CountryEconomy::dollarGdp)
        // 수취·지급이 분리되지 않는 순액에는 양쪽에 공통인 세계 명목달러 GDP 지수만 적용한다.
        val netMonthlyScale = worldGdp / worldOpeningGdp / 12
        var weightedRates = 0.0
        for (i in countries.indices) {
            repricedRate[i] += (countries[i].policyRate - repricedRate[i]) / REPRICING_MONTHS
            weightedRates += openingGdp[i] * repricedRate[i]
        }
        return countries.indices.map { i ->
            val domesticGdp = countries[i].dollarGdp
            val foreignOpeningGdp = worldOpeningGdp - openingGdp[i]
            val foreignInitialRate = (openingWeightedRates - openingGdp[i] * initialRate[i]) / foreignOpeningGdp
            val foreignRate = (weightedRates - openingGdp[i] * repricedRate[i]) / foreignOpeningGdp
            InvestmentIncomeSnapshot(
                opening = opening[i],
                received =
                    opening[i].received?.accrue(
                        (worldGdp - domesticGdp) / foreignOpeningGdp / 12,
                        yieldIndex(foreignRate, foreignInitialRate),
                    ),
                paid = opening[i].paid?.accrue(domesticGdp / openingGdp[i] / 12, yieldIndex(repricedRate[i], initialRate[i])),
                netOnly =
                    opening[i].netOnly?.let {
                        NetInvestmentIncomeAmounts(
                            total = it.total * netMonthlyScale,
                            reinvested = it.reinvested?.times(netMonthlyScale),
                            interest = it.interest?.times(netMonthlyScale),
                        )
                    },
            )
        }
    }

    private companion object {
        // 유효수익률 수준은 원표 금액에 이미 들어 있다. 아래 게임 계수는 기준금리 변동에 따른 비율 변화만 정한다.
        const val REPRICING_MONTHS = 12.0
        const val FIXED_YIELD = 0.03
        const val POLICY_RATE_SHARE = 0.25

        fun yieldIndex(
            rate: Double,
            opening: Double,
        ) = (FIXED_YIELD + POLICY_RATE_SHARE * rate) / (FIXED_YIELD + POLICY_RATE_SHARE * opening)
    }
}
