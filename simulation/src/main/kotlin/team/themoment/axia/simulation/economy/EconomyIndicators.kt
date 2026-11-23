package team.themoment.axia.simulation.economy

import java.time.LocalDate
import java.time.YearMonth

/** [EconomyIndicators.estimated]에서 기준금리를 가리키는 이름. */
const val POLICY_RATE_FIELD = "policyRate"

/** 한 국가의 경제 지표 스냅숏. 비율은 모두 소수(0.031 = 3.1%)다. */
data class EconomyIndicators(
    val population: Double,
    /** 명목 GDP, 연간 달러. */
    val gdp: Double,
    /** 실질 GDP의 전년 동월 대비 성장률. */
    val growth: Double,
    val inflation: Double,
    val unemployment: Double,
    /** 최근 한 달 재정수지를 연간으로 환산한 GDP 대비 비율. 흑자면 양수. */
    val budgetBalance: Double,
    val debtRatio: Double,
    /** 중앙은행 기준금리. */
    val policyRate: Double,
    /** 달러 대비 실질환율의 시작 대비 로그 편차. 양수면 통화 가치가 올랐다. */
    val realExchange: Double,
    /** 산출 갭(잠재 GDP 대비). */
    val outputGap: Double,
    /** 실측값이 없어 추정한 초기 항목. */
    val estimated: Set<String>,
) {
    val gdpPerCapita: Double get() = if (population > 0.0) gdp / population else 0.0
}

enum class EconomySeries {
    GDP,
    GDP_PER_CAPITA,
    GROWTH,
    INFLATION,
    UNEMPLOYMENT,
    BUDGET_BALANCE,
    DEBT,
    POLICY_RATE,
}

/** 국가별 월간 기록. [start]는 첫 값의 연월이다. */
data class CountryHistory(
    val start: YearMonth,
    val series: Map<EconomySeries, List<Double>>,
) {
    operator fun get(series: EconomySeries): List<Double> = this.series.getValue(series)
}

data class EconomySnapshot(
    val date: LocalDate,
    val countries: Map<String, EconomyIndicators>,
    val history: Map<String, CountryHistory>,
    val monetary: Map<String, MonetaryPolicy>,
)
