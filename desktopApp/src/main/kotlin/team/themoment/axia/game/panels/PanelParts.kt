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
import team.themoment.axia.game.GDP_DESCRIPTION
import team.themoment.axia.game.GDP_PER_CAPITA_DESCRIPTION
import team.themoment.axia.game.NationalMetric
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
import team.themoment.axia.ui.dossier.metricTooltip
import kotlin.math.roundToInt

/** 0~100 만족도를 막대로 보여 주는 한 줄. */
@Composable
internal fun SatisfactionRow(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    billSupport: Boolean = false,
) {
    val metric = if (billSupport) "법안 지지 점수" else "만족도"
    val title = if (label == metric) metric else "$label · $metric"
    val detail =
        if (billSupport) {
            "집단의 현재 만족도에 법안으로 받을 영향을 더해 100점 만점으로 표시합니다. 높을수록 이 법안을 지지합니다."
        } else {
            "이 집단이 정부에 얼마나 만족하는지 100점 만점으로 표시합니다. 정부 지지율에는 각 집단의 영향력에 따라 반영됩니다."
        }
    Row(modifier.metricTooltip(title, detail), verticalAlignment = Alignment.CenterVertically) {
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
        metric = "만족도",
        unit = "점",
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
        metric = "정부 지지율",
        unit = "%p",
        scale = maxOf(5.0, contributions.maxOf { kotlin.math.abs(it.value) }),
    )
}

private const val MIN_SHOWN_CONTRIBUTION = 0.05

@Composable
internal fun KeyIndicatorRows(indicators: EconomyIndicators) {
    StatRow("인구", formatPopulation(indicators.population), tooltip = "이 나라에 거주하는 전체 인구입니다. 인구의 자연 증감과 국제 이동을 반영합니다.")
    StatRow("GDP", formatDollars(indicators.gdp), tooltip = GDP_DESCRIPTION)
    StatRow("1인당 GDP", formatWholeDollars(indicators.gdpPerCapita), tooltip = GDP_PER_CAPITA_DESCRIPTION)
    IndicatorRow(NationalMetric.GROWTH, formatPercent(indicators.growth, signed = true))
    IndicatorRow(NationalMetric.INFLATION, formatPercent(indicators.inflation))
    IndicatorRow(NationalMetric.UNEMPLOYMENT, formatPercent(indicators.unemployment))
    RateContributions(indicators)
    IndicatorRow(NationalMetric.BALANCE, formatPercent(indicators.budgetBalance, signed = true))
    IndicatorRow(NationalMetric.DEBT, formatPercent(indicators.debtRatio))
    IndicatorRow(NationalMetric.RATE, formatRate(indicators.policyRate))
    StatRow(
        "통화 가치 (달러 대비, 시작 대비)",
        formatPercent(kotlin.math.exp(indicators.realExchange) - 1.0, signed = true),
        tooltip = "시작일과 비교한 달러 대비 자국 통화의 실질 가치입니다. 양수면 절상, 음수면 절하를 뜻합니다.",
    )
}

@Composable
private fun IndicatorRow(
    metric: NationalMetric,
    value: String,
) {
    StatRow(metric.title, value, tooltip = metric.description)
}

@Composable
private fun RateContributions(indicators: EconomyIndicators) {
    var expanded by remember { mutableStateOf(false) }
    PaperTag("물가·실업률 변동 요인", selected = expanded, onClick = { expanded = !expanded })
    if (!expanded) return
    val inflation = indicators.inflationContributions
    val unemployment = indicators.unemploymentContributions
    if (inflation == null || unemployment == null) {
        StatRow("시작 물가상승률", formatRate(indicators.inflation), tooltip = NationalMetric.INFLATION.description)
        StatRow("시작 실업률", formatRate(indicators.unemployment), tooltip = NationalMetric.UNEMPLOYMENT.description)
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
    StatRow("물가상승률 (연율)", formatRate(inflation.rate), tooltip = NationalMetric.INFLATION.description)
    SectionTitle("직전 월 결산 · 실업률")
    RateContribution("자연실업률 (시작값)", unemployment.naturalRate)
    RateContribution("노동 규제·고용 정책", unemployment.laborPolicy)
    RateContribution("전쟁에 따른 실업", unemployment.war)
    RateContribution("경기 (산출 갭)", unemployment.outputGap)
    if (unemployment.limitAdjustment != 0.0) RateContribution("상·하한 조정", unemployment.limitAdjustment)
    StatRow("실업률", formatRate(unemployment.rate), tooltip = NationalMetric.UNEMPLOYMENT.description)
}

@Composable
private fun RateContribution(
    label: String,
    share: Double,
) {
    StatRow(label, formatRate(share, signed = true) + "p", tooltip = "이 항목이 전체 비율에 더한 퍼센트포인트입니다. 음수이면 전체 비율을 낮춥니다.")
}

internal val percentFormat: (Double) -> String = { formatPercent(it) }
internal val rateFormat: (Double) -> String = { formatRate(it) }
internal val signedPercentFormat: (Double) -> String = { formatPercent(it, signed = true) }
internal val dollarFormat: (Double) -> String = ::formatCompactDollars
internal val wholeDollarFormat: (Double) -> String = { formatCompactDollars(it) }
internal val approvalFormat: (Double) -> String = { "${it.roundToInt()}" }
