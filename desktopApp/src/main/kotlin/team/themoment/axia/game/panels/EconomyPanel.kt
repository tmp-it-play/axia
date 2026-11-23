package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatRate
import team.themoment.axia.game.note
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.economy.MonetaryFramework
import team.themoment.axia.simulation.economy.MonetaryPolicy
import team.themoment.axia.simulation.economy.MonetaryStance
import team.themoment.axia.simulation.economy.POLICY_RATE_FIELD
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun EconomyPanel(
    countryName: String,
    indicators: EconomyIndicators,
    history: CountryHistory,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    onDirectRate: (Double) -> Unit,
    onDraftIndependence: (Double) -> Unit,
    onViewBill: () -> Unit,
) {
    SectionTitle("현재 지표")
    KeyIndicatorRows(indicators)
    MonetarySection(countryName, indicators, history, monetary, politics, onDirectRate, onDraftIndependence, onViewBill)
    SectionTitle("경제성장률")
    LineChart(history[EconomySeries.GROWTH], history.start, signedPercentFormat, zeroLine = true)
    SectionTitle("물가상승률")
    LineChart(history[EconomySeries.INFLATION], history.start, percentFormat)
    SectionTitle("실업률")
    LineChart(history[EconomySeries.UNEMPLOYMENT], history.start, percentFormat)
    SectionTitle("GDP")
    LineChart(history[EconomySeries.GDP], history.start, dollarFormat)
}

@Composable
private fun MonetarySection(
    countryName: String,
    indicators: EconomyIndicators,
    history: CountryHistory,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    onDirectRate: (Double) -> Unit,
    onDraftIndependence: (Double) -> Unit,
    onViewBill: () -> Unit,
) {
    SectionTitle("통화정책")
    StatRow("중앙은행", monetary.bankName ?: "$countryName 중앙은행")
    StatRow("통화 체제", monetary.framework.label + monetary.anchorCurrency?.let { " · $it" }.orEmpty())
    StatRow("금리 결정", decisionMaker(monetary))
    StatRow("기준금리", formatRate(monetary.policyRate), note = indicators.note(POLICY_RATE_FIELD))
    if (monetary.quantitativeEasing) StatRow("자산 매입", "금리 하한 · 양적완화 중")
    StatRow("물가 목표", formatPercent(monetary.inflationTarget))
    StatRow("실질금리 (기준금리 − 기대 물가)", formatRate(monetary.realRate, signed = true))
    StatRow("통화 기조", monetary.stance.label, valueColor = stanceColor(monetary.stance))
    StatRow("통화 신뢰도", "${(monetary.credibility * 100).roundToInt()}점")
    LineChart(history[EconomySeries.POLICY_RATE], history.start, rateFormat)

    val pending = politics.pending
    val drafted = politics.draft[InstitutionItem.CENTRAL_BANK_INDEPENDENCE]
    val proposedInBill = pending?.changes?.any { it.item == InstitutionItem.CENTRAL_BANK_INDEPENDENCE } == true
    when (monetary.framework) {
        MonetaryFramework.MONETARY_UNION -> {
            Hint("통화동맹 회원국이라 기준금리는 ${monetary.unionBank}이 회원국 전체의 물가와 경기를 보고 정합니다.")
        }

        MonetaryFramework.CURRENCY_PEG -> {
            Hint("환율을 ${monetary.anchorCurrency}에 고정해 기준금리가 기준 통화의 금리를 따라갑니다.")
        }

        MonetaryFramework.INFLATION_TARGETING -> {
            Hint("독립 중앙은행이 물가와 경기를 보고 금리를 정합니다. 정부가 금리를 정하려면 독립성을 없애는 법안을 통과시켜야 하며, 통과되면 통화 신뢰가 무너집니다.")
            IndependenceDraft(
                label = "독립성 박탈",
                target = 0.0,
                drafted = drafted != null,
                proposedInBill = proposedInBill,
                editable = pending == null,
                onDraft = onDraftIndependence,
                onViewBill = onViewBill,
            )
        }

        MonetaryFramework.GOVERNMENT_DIRECTED -> {
            if (monetary.directable) {
                val limit = remember { ceil(maxOf(MIN_SLIDER_LIMIT, monetary.policyRate * 2) / SLIDER_ROUNDING) * SLIDER_ROUNDING }
                val proposed = monetary.directedRate ?: monetary.policyRate
                DossierSlider(
                    label = "기준금리 결정",
                    value = proposed.toFloat(),
                    range = MIN_RATE.toFloat()..limit.toFloat(),
                    valueText = formatRate(proposed),
                    secondary = "다음 달 1일부터 적용 · 지금 ${formatRate(monetary.policyRate)}",
                    onValueChange = { onDirectRate((it / RATE_STEP).roundToInt() * RATE_STEP) },
                )
                Hint("금리를 물가보다 낮게 묶어 두면 경기는 살지만 물가가 스스로 오르기 시작합니다. 독립성을 주면 신뢰도가 5년에 걸쳐 오릅니다.")
            }
            IndependenceDraft(
                label = "중앙은행 독립",
                target = 1.0,
                drafted = drafted != null,
                proposedInBill = proposedInBill,
                editable = pending == null,
                onDraft = onDraftIndependence,
                onViewBill = onViewBill,
            )
        }
    }
}

@Composable
private fun IndependenceDraft(
    label: String,
    target: Double,
    drafted: Boolean,
    proposedInBill: Boolean,
    editable: Boolean,
    onDraft: (Double) -> Unit,
    onViewBill: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            proposedInBill -> {
                PaperTag("$label 법안 심의 중", selected = true, onClick = onViewBill)
            }

            drafted -> {
                PaperTag("$label · 초안에 포함됨", selected = true, onClick = onViewBill)
                PaperTag("초안에서 빼기", onClick = { onDraft(1.0 - target) })
            }

            else -> {
                PaperTag("$label 법안 초안에 추가", enabled = editable, onClick = { onDraft(target) })
            }
        }
    }
}

private fun decisionMaker(monetary: MonetaryPolicy): String =
    when (monetary.framework) {
        MonetaryFramework.INFLATION_TARGETING -> "독립 중앙은행"
        MonetaryFramework.MONETARY_UNION -> monetary.unionBank.orEmpty()
        MonetaryFramework.CURRENCY_PEG -> "기준 통화를 따름"
        MonetaryFramework.GOVERNMENT_DIRECTED -> "정부"
    }

private fun stanceColor(stance: MonetaryStance) =
    when (stance) {
        MonetaryStance.TIGHT -> DossierColors.negative
        MonetaryStance.NEUTRAL -> DossierColors.ink
        MonetaryStance.LOOSE -> DossierColors.positive
    }

private const val MIN_RATE = -0.0075
private const val RATE_STEP = 0.0025
private const val MIN_SLIDER_LIMIT = 0.2
private const val SLIDER_ROUNDING = 0.05
