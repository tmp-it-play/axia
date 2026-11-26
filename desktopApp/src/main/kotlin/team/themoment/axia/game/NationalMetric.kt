package team.themoment.axia.game

import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.nation.NationHistory
import team.themoment.axia.ui.dossier.AlignmentTrend
import team.themoment.axia.ui.dossier.MetricTrend
import team.themoment.axia.ui.dossier.TooltipTrend

internal const val GDP_DESCRIPTION = "국내에서 한 해 동안 생산한 재화와 서비스의 부가가치를 현재 가격과 달러 기준으로 합한 금액입니다."

internal const val GDP_PER_CAPITA_DESCRIPTION = "연간 명목 GDP를 전체 인구로 나눈 1인당 생산액입니다."

internal const val ALIGNMENT_TITLE = "충성파·반대파"
internal const val ALIGNMENT_DESCRIPTION = "15세 이상 주민 중 정부를 강하게 지지하거나 반대하는 사람의 수입니다."

internal enum class NationalMetric(
    val symbol: ConsoleSymbol,
    val shortName: String,
    val title: String,
    val description: String,
) {
    APPROVAL(
        ConsoleSymbol.BALLOT_BOX,
        "지지율",
        "정부 지지율",
        "직업·성향 집단의 만족도에 각 집단의 정치적 영향력을 반영합니다. 사회 불안이 커지면 지지율이 내려갑니다.",
    ),
    ALIGNMENT(
        ConsoleSymbol.COCKADE,
        ALIGNMENT_TITLE,
        ALIGNMENT_TITLE,
        ALIGNMENT_DESCRIPTION,
    ),
    POLITICAL_CAPITAL(
        ConsoleSymbol.LECTERN,
        "정치 자본",
        "정치 자본",
        "외교 협상이나 국내 조직과의 대화에 쓸 수 있습니다. 매달 정부 지지율과 행정부의 재량에 따라 최대 100점까지 쌓입니다.",
    ),
    GROWTH(
        ConsoleSymbol.GROWTH,
        "성장",
        "경제성장률",
        "물가 변동을 제외한 국내 생산이 지난해 같은 달보다 얼마나 늘거나 줄었는지 보여 줍니다.",
    ),
    INFLATION(
        ConsoleSymbol.PRICE_TAG,
        "물가",
        "물가상승률",
        "국내 물가가 오르거나 내리는 속도를 연율로 표시합니다. 소비세, 환율, 원유·곡물 가격의 영향을 받습니다.",
    ),
    UNEMPLOYMENT(
        ConsoleSymbol.BRIEFCASE,
        "실업",
        "실업률",
        "경제활동인구 중 일자리를 찾고 있는 실업자의 비율입니다. 경기가 위축되면 실업률이 오릅니다.",
    ),
    BALANCE(
        ConsoleSymbol.SCALES,
        "재정",
        "재정수지 (GDP 대비)",
        "한 달 세입에서 이자를 포함한 세출을 뺀 뒤 연간으로 환산해 GDP로 나눕니다. 양수면 흑자, 음수면 적자입니다.",
    ),
    DEBT(
        ConsoleSymbol.BOND,
        "부채",
        "국가부채 (GDP 대비)",
        "정부의 부채 잔액을 연간 명목 GDP로 나눈 비율입니다.",
    ),
    RATE(
        ConsoleSymbol.INTEREST,
        "금리",
        "기준금리",
        "중앙은행의 정책금리입니다. 금리 인상은 시차를 두고 경기를 둔화시키고 국채 이자 부담을 높입니다.",
    ),
    ;

    fun trend(
        economy: CountryHistory,
        nation: NationHistory,
    ): TooltipTrend? {
        val series =
            when (this) {
                APPROVAL -> return MetricTrend(nation.start, nation.approval, "%")
                ALIGNMENT -> return AlignmentTrend(nation.start, nation.loyalists, nation.opponents)
                POLITICAL_CAPITAL -> return null
                GROWTH -> EconomySeries.GROWTH
                INFLATION -> EconomySeries.INFLATION
                UNEMPLOYMENT -> EconomySeries.UNEMPLOYMENT
                BALANCE -> EconomySeries.BUDGET_BALANCE
                DEBT -> EconomySeries.DEBT
                RATE -> EconomySeries.POLICY_RATE
            }
        return MetricTrend(economy.start, economy[series], "%", scale = 100.0, decimals = if (this == RATE) 2 else 1)
    }
}
