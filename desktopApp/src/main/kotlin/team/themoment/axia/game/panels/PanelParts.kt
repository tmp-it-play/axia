package team.themoment.axia.game.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatRate
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.game.formatPoints
import team.themoment.axia.game.satisfactionColor
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.SatisfactionFactor
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.Contribution
import team.themoment.axia.ui.dossier.ContributionBars
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 0~100 만족도를 막대로 보여 주는 한 줄. */
@Composable
internal fun SatisfactionRow(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = DossierType.number(12.sp, weight = FontWeight.Normal),
            color = DossierColors.inkMuted,
            modifier = Modifier.width(104.dp),
        )
        Canvas(Modifier.weight(1f).height(9.dp)) {
            drawRect(DossierColors.paperShade, size = size)
            drawRect(satisfactionColor(value).copy(alpha = 0.75f), size = Size(size.width * (value / 100.0).toFloat(), size.height))
            drawLine(DossierColors.inkMuted, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
        }
        Text(
            text = value.roundToInt().toString(),
            style = DossierType.number(12.sp),
            color = satisfactionColor(value),
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
internal fun FactorBars(factors: Map<SatisfactionFactor, Double>) {
    ContributionBars(
        contributions = factors.map { (factor, value) -> Contribution(factor.label, value) },
        format = ::formatPoints,
        scale = maxOf(5.0, factors.values.maxOf { kotlin.math.abs(it) }),
    )
}

/** 지지율 분해: 경제 요인, 성향, 사회 불안. 같은 축척으로 그린다. */
@Composable
internal fun ApprovalBreakdown(nation: NationSnapshot) {
    val contributions =
        nation.approvalFactors.map { (factor, value) -> Contribution(factor.label, value) } +
            nation.identityContributions
                .filter { kotlin.math.abs(it.value) >= MIN_SHOWN_CONTRIBUTION }
                .map { (identity, value) -> Contribution("성향 · ${identity.label}", value) } +
            listOfNotNull(Contribution("사회 불안", -nation.unrest).takeIf { nation.unrest >= MIN_SHOWN_CONTRIBUTION })
    ContributionBars(
        contributions = contributions,
        format = ::formatPoints,
        scale = maxOf(5.0, contributions.maxOf { kotlin.math.abs(it.value) }),
    )
}

private const val MIN_SHOWN_CONTRIBUTION = 0.05

@Composable
internal fun KeyIndicatorRows(indicators: EconomyIndicators) {
    StatRow("인구", formatPopulation(indicators.population))
    StatRow("GDP", formatDollars(indicators.gdp))
    StatRow("1인당 GDP", formatWholeDollars(indicators.gdpPerCapita))
    StatRow("경제성장률", formatPercent(indicators.growth, signed = true))
    StatRow("물가상승률", formatPercent(indicators.inflation))
    StatRow("실업률", formatPercent(indicators.unemployment))
    RateContributions(indicators)
    StatRow("재정수지 (GDP 대비)", formatPercent(indicators.budgetBalance, signed = true))
    StatRow("국가부채 (GDP 대비)", formatPercent(indicators.debtRatio))
    StatRow("기준금리", formatRate(indicators.policyRate))
    StatRow("통화 가치 (달러 대비, 시작 대비)", formatPercent(kotlin.math.exp(indicators.realExchange) - 1.0, signed = true))
}

@Composable
private fun RateContributions(indicators: EconomyIndicators) {
    var expanded by remember { mutableStateOf(false) }
    PaperTag("물가·실업률 변동 요인", selected = expanded, onClick = { expanded = !expanded })
    if (!expanded) return
    val inflation = indicators.inflationContributions
    val unemployment = indicators.unemploymentContributions
    if (inflation == null || unemployment == null) {
        StatRow("시작 물가상승률", formatRate(indicators.inflation))
        StatRow("시작 실업률", formatRate(indicators.unemployment))
        Hint("첫 월 결산부터 원인별 기여도를 볼 수 있습니다.")
        return
    }
    SectionTitle("직전 월 결산 · 물가상승률")
    RateContribution("기대 물가상승률", inflation.expectations)
    RateContribution("경기 (산출 갭)", inflation.outputGap)
    RateContribution("소비세 전가", inflation.consumptionTax)
    RateContribution("국제 원유·곡물 가격", inflation.commodityPrices)
    RateContribution("환율 전가", inflation.exchangeRate)
    RateContribution("사건·국가 정책 충격", inflation.shocks)
    if (inflation.limitAdjustment != 0.0) RateContribution("상·하한 조정", inflation.limitAdjustment)
    StatRow("물가상승률 (연율)", formatRate(inflation.rate))
    SectionTitle("직전 월 결산 · 실업률")
    RateContribution("자연실업률 (시작값)", unemployment.naturalRate)
    RateContribution("노동 규제·고용 정책", unemployment.laborPolicy)
    RateContribution("전쟁에 따른 실업", unemployment.war)
    RateContribution("경기 (산출 갭)", unemployment.outputGap)
    if (unemployment.limitAdjustment != 0.0) RateContribution("상·하한 조정", unemployment.limitAdjustment)
    StatRow("실업률", formatRate(unemployment.rate))
}

@Composable
private fun RateContribution(
    label: String,
    share: Double,
) {
    StatRow(label, formatRate(share, signed = true) + "p")
}

internal val percentFormat: (Double) -> String = { formatPercent(it) }
internal val rateFormat: (Double) -> String = { formatRate(it) }
internal val signedPercentFormat: (Double) -> String = { formatPercent(it, signed = true) }
internal val dollarFormat: (Double) -> String = ::formatCompactDollars
internal val wholeDollarFormat: (Double) -> String = { formatCompactDollars(it) }
internal val approvalFormat: (Double) -> String = { "${it.roundToInt()}" }
