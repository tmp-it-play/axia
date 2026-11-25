package team.themoment.axia.simulation.country.bra

import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.economy.GrainProducerSupport
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import java.util.Locale

/** Conab의 곡물 가격차 보조(PEPRO). 2023/24년 밀 경매를 바탕으로 추가 사업만 모형화한다. */
internal class BrazilModule :
    CountryModule,
    GrainProducerSupport {
    override val countryId = ID
    private val pepro =
        CountryPolicyItem(
            key = "bra.pepro",
            label = "Conab 곡물 농가 가격차 보조",
            lowLabel = "기존 경매 유지",
            highLabel = "추가 경매 확대",
            description =
                "국제 곡물 가격 지수가 105 아래로 떨어지면 농가와 협동조합에 가격 차액을 지급한다. 법안으로 추가 경매 범위를 정하며, 지급액은 월별 곡물 가격과 국내 생산량에 따라 달라진다. " +
                    "첫 결산 전에는 지출 한도를 기준으로 법안의 예상 지출을 계산한다.",
            identityReactions = mapOf(Identity.FREE_MARKET to -3.0),
            groupReactions = mapOf(PopulationGroup.FARMERS to 6.0),
        )
    override val policies = listOf(pepro)
    private var coverage = 0.0
    private var latestPrice: Double? = null
    override var paidTonnes = 0.0
        private set
    private var fullCoverageCostShare = MAX_ANNUAL_GDP_SHARE

    override fun policyValue(item: CountryPolicyItem): Double {
        require(item == pepro)
        return coverage
    }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == pepro && target in 0.0..1.0)
        coverage = target
    }

    override fun estimatedSpendingPerUnit(item: CountryPolicyItem): Double {
        require(item == pepro)
        return fullCoverageCostShare
    }

    override fun settle(
        priceIndex: Double,
        productionTonnes: Double,
        annualRealGdp: Double,
    ): Double {
        latestPrice = priceIndex
        val premiumPerTonne = (SUPPORT_PRICE - priceIndex).coerceAtLeast(0.0) * BASE_DOLLARS_PER_TONNE / BASE_INDEX
        val monthlyLimit = annualRealGdp * MAX_ANNUAL_GDP_SHARE / MONTHS_PER_YEAR
        val availableTonnes =
            if (premiumPerTonne > 0.0) minOf(productionTonnes * MAX_PRODUCTION_SHARE, monthlyLimit / premiumPerTonne) else 0.0
        fullCoverageCostShare = availableTonnes * premiumPerTonne * MONTHS_PER_YEAR / annualRealGdp
        paidTonnes = availableTonnes * coverage
        return paidTonnes * premiumPerTonne
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                "추가 경매 지출 한도 (GDP 대비 연율)",
                String.format(Locale.KOREA, "%.4f%%", coverage * MAX_ANNUAL_GDP_SHARE * 100.0),
                "월초 실질 GDP로 정한 연간 한도의 12분의 1을 매월 배정한다. 남은 한도는 다음 달로 이월하지 않는다.",
            ),
            CountryIndicator(
                "국제 곡물 가격 지수",
                latestPrice?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "첫 정산 전",
                "국제 곡물 지수가 지원 기준 105 아래로 떨어지면 추가 경매 대상에 가격차 보조금을 지급한다.",
            ),
            CountryIndicator(
                "이번 달 추가 보조 물량",
                String.format(Locale.KOREA, "%,.0f톤", paidTonnes),
                "브라질의 실제 월 곡물 생산량 중 추가 지원을 받은 물량이다. 해당 농가에 지급한 금액은 재정 탭에서 확인한다.",
            ),
        )

    companion object {
        const val ID = "BRA"
        private const val MONTHS_PER_YEAR = 12.0

        // 2024년 정책 대비 추가 경매의 모형 가정. 곡물은 전국 밀 산지가격·적격 물량의 대용치다.
        private const val BASE_INDEX = 100.0
        private const val SUPPORT_PRICE = 105.0
        private const val BASE_DOLLARS_PER_TONNE = 250.0
        private const val MAX_PRODUCTION_SHARE = 0.04
        private const val MAX_ANNUAL_GDP_SHARE = 0.00005
    }
}
