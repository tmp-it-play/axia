package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatCompactDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatRate
import team.themoment.axia.game.EconomyPage
import team.themoment.axia.simulation.economy.BankPressure
import team.themoment.axia.simulation.economy.Company
import team.themoment.axia.simulation.economy.CountryHistory
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.economy.EconomySeries
import team.themoment.axia.simulation.economy.ExternalAccountSnapshot
import team.themoment.axia.simulation.economy.GovernorMandate
import team.themoment.axia.simulation.economy.InvestmentIncomeSnapshot
import team.themoment.axia.simulation.economy.MonetaryFramework
import team.themoment.axia.simulation.economy.MonetaryPolicy
import team.themoment.axia.simulation.economy.MonetaryStance
import team.themoment.axia.simulation.economy.SectorLine
import team.themoment.axia.simulation.economy.TradeSnapshot
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.politics.PoliticsSnapshot
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierSlider
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.metricTooltip
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun EconomyPanel(
    page: EconomyPage,
    countryId: String,
    countryName: (String) -> String,
    indicators: EconomyIndicators,
    history: CountryHistory,
    sectors: List<SectorLine>,
    externalAccount: ExternalAccountSnapshot,
    companies: List<Company>,
    closures: Int,
    onRenameCompany: (Int, String) -> Unit,
    onRequestImf: () -> Unit,
    trade: TradeSnapshot,
    date: LocalDate,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    politicalCapital: Double,
    onAppointGovernor: (GovernorMandate) -> Unit,
    onPressureBank: (BankPressure) -> Unit,
    onDirectRate: (Double) -> Unit,
    onOpenIndependence: () -> Unit,
    onViewBill: () -> Unit,
) {
    when (page) {
        EconomyPage.INDICATORS -> {
            PanelColumns(
                left = {
                    LedgerHeading("주요 경제 지표")
                    KeyIndicatorRows(indicators)
                },
                right = { EconomicTrends(history) },
            )
        }

        EconomyPage.MONETARY -> {
            PanelColumns(
                left = {
                    MonetarySection(
                        countryName(countryId),
                        history,
                        monetary,
                        politics,
                        onDirectRate,
                        onOpenIndependence,
                        onViewBill,
                    )
                },
                right = { GovernorSection(monetary, politicalCapital, onAppointGovernor, onPressureBank) },
            )
        }

        EconomyPage.EXTERNAL -> {
            PanelColumns(
                left = { ExternalAccounts(countryId, externalAccount) },
                right = { ForeignReserves(externalAccount, onRequestImf) },
            )
        }

        EconomyPage.INDUSTRY -> {
            PanelColumns(
                left = { IndustrySection(sectors, companies, closures, onRenameCompany) },
                right = {
                    CommoditySection(countryId, trade.commodities, date, indicators.supportedGrainTonnes, pageHeading = true, countryName)
                },
            )
        }
    }
}

@Composable
private fun ExternalAccounts(
    countryId: String,
    externalAccount: ExternalAccountSnapshot,
) {
    LedgerHeading("경상수지")
    if (externalAccount.settled) {
        StatRow("월 수출 · 수입", "${formatCompactDollars(externalAccount.exports)} · ${formatCompactDollars(externalAccount.imports)}")
        val transfers = externalAccount.remittances
        StatRow(
            "월 개인송금 수취 · 지급",
            dollarPair(externalAccount.openingReceived?.let { transfers.received }, externalAccount.openingPaid?.let { transfers.paid }),
        )
        val investment = externalAccount.investmentIncome
        if (investment.netOnly != null) {
            StatRow("월 투자소득 순수취", formatCompactDollars(investment.net))
        } else {
            StatRow("월 투자소득 수취 · 지급", dollarPair(investment.received?.total, investment.paid?.total))
        }
        if (externalAccount.imfInterest > 0.0) StatRow("월 IMF 차입 이자", formatCompactDollars(externalAccount.imfInterest))
        if (externalAccount.netGrants != 0.0) {
            val label = if (externalAccount.netGrants > 0.0) "월 경제 원조 수취" else "월 경제 원조 지급"
            StatRow(label, formatCompactDollars(kotlin.math.abs(externalAccount.netGrants)))
        }
        StatRow("월 경상수지", formatCompactDollars(externalAccount.currentAccount))
    } else {
        Hint("첫 월 결산 후 수출입과 경상수지가 표시됩니다.")
    }
    var showRemittances by remember(countryId) { mutableStateOf(false) }
    PaperTag(if (showRemittances) "송금 내역 접기" else "송금 내역", onClick = { showRemittances = !showRemittances })
    if (showRemittances) {
        for ((label, benchmark) in listOf("연간 수취 기준액" to externalAccount.openingReceived, "연간 지급 기준액" to externalAccount.openingPaid)) {
            StatRow(label, benchmark?.let { "${formatCompactDollars(it.usd.toDouble())} · ${it.year}년" } ?: "—")
        }
        if (externalAccount.settled) {
            val transfers = externalAccount.remittances
            val received = externalAccount.openingReceived?.let { transfers.unallocatedReceived }
            val paid = externalAccount.openingPaid?.let { transfers.unallocatedPaid }
            StatRow("상대 미배정 수취 · 지급", dollarPair(received, paid))
        }
        Hint("국가 간 송금액은 지급국의 경제 규모에 따라 달라집니다. 거래 상대가 정해지지 않은 송금도 경상수지에 포함됩니다.")
    }
    InvestmentIncomeDetails(countryId, externalAccount.investmentIncome, externalAccount.settled)
}

@Composable
private fun ForeignReserves(
    externalAccount: ExternalAccountSnapshot,
    onRequestImf: () -> Unit,
) {
    LedgerHeading("외환보유액과 차입")
    StatRow("준비자산 (금 제외)", formatCompactDollars(externalAccount.reserves))
    externalAccount.importCover?.let { StatRow("수입 대금 충당 기간", "%.1f개월".format(java.util.Locale.KOREA, it)) }
    if (externalAccount.pegFailed) Hint("외환보유액 부족으로 고정환율 방어가 종료됐습니다.")
    if (externalAccount.tradeArrears > 0.0) StatRow("미결제 수입 대금", formatCompactDollars(externalAccount.tradeArrears))
    if (externalAccount.shortTermCredit > 0.0) StatRow("단기 대외신용 잔액", formatCompactDollars(externalAccount.shortTermCredit))
    if (externalAccount.imfCredit > 0.0) {
        StatRow("IMF 대출 잔액", formatCompactDollars(externalAccount.imfCredit))
        StatRow("상환 상태", if (externalAccount.imfMonthsLeft > 0) "만기까지 ${externalAccount.imfMonthsLeft}개월" else "만기 경과 · 미상환 원금")
    }
    if (externalAccount.canRequestAssistance) {
        Hint("외화를 즉시 차입합니다. 다음 달부터 연 3% 이자와 36개월 분할 원금을 보유액에서 지급합니다.")
        PaperTag("IMF 긴급 대출 신청", onClick = onRequestImf)
    }
}

@Composable
private fun IndustrySection(
    sectors: List<SectorLine>,
    companies: List<Company>,
    closures: Int,
    onRenameCompany: (Int, String) -> Unit,
) {
    LedgerHeading("산업 구조")
    SectorRow("산업 부문", "부가가치 비중", "성장률", "고용 비중", header = true)
    sectors.sortedByDescending { it.share }.forEach { line ->
        SectorRow(
            line.sector.label,
            formatPercent(line.share),
            formatPercent(line.growth, signed = true),
            formatPercent(line.employment),
            growthColor = if (line.growth < 0.0) DossierColors.negative else DossierColors.ink,
        )
    }
    Hint("성장률은 실질 부가가치의 전년 대비 증가율입니다.")
    SectionTitle("기업")
    StatRow("생성 기업 · 누적 폐업", "${companies.size}곳 · ${closures}곳")
    var showCompanies by remember { mutableStateOf(false) }
    if (companies.isNotEmpty()) PaperTag(if (showCompanies) "목록 접기" else "기업 목록", onClick = { showCompanies = !showCompanies })
    if (showCompanies) {
        companies.forEach { company ->
            StatRow(company.name, company.sector.label)
            Hint("${company.founded} 설립 · 연간 부가가치 ${formatCompactDollars(company.annualValueAdded)}")
            RenameControl(company.id, company.name) { onRenameCompany(company.id, it) }
        }
    }
}

@Composable
private fun SectorRow(
    sector: String,
    share: String,
    growth: String,
    jobs: String,
    header: Boolean = false,
    growthColor: Color = DossierColors.ink,
) {
    val style = if (header) DossierType.title(12.sp) else DossierType.number(14.sp)
    val ink = if (header) DossierColors.blueInk else DossierColors.ink

    fun Modifier.figure(
        label: String,
        detail: String,
    ): Modifier = if (header) this else metricTooltip("$sector · $label", detail)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(sector, Modifier.weight(1.35f), style = style, color = ink)
        Text(
            share,
            Modifier.weight(1.1f).figure("부가가치 비중", "국내 산업 전체의 부가가치에서 이 부문이 차지하는 비율입니다."),
            textAlign = TextAlign.End,
            style = style,
            color = ink,
        )
        Text(
            growth,
            Modifier.weight(1f).figure("성장률", "지난해 같은 달에 비해 이 부문의 실질 부가가치가 변한 비율입니다."),
            textAlign = TextAlign.End,
            style = style,
            color = if (header) ink else growthColor,
        )
        Text(
            jobs,
            Modifier.weight(1f).figure("고용 비중", "전체 취업자 중 이 부문에서 일하는 사람의 비율입니다."),
            textAlign = TextAlign.End,
            style = style,
            color = ink,
        )
    }
}

private fun dollarPair(
    received: Double?,
    paid: Double?,
): String = "${received?.let(::formatCompactDollars) ?: "—"} · ${paid?.let(::formatCompactDollars) ?: "—"}"

@Composable
private fun InvestmentIncomeDetails(
    countryId: String,
    income: InvestmentIncomeSnapshot,
    settled: Boolean,
) {
    var expanded by remember(countryId) { mutableStateOf(false) }
    PaperTag(if (expanded) "투자소득 내역 접기" else "투자소득 내역", onClick = { expanded = !expanded })
    if (!expanded) return
    income.opening.netOnly?.let { base ->
        StatRow("투자소득 연간 순수취 기준액", "${formatCompactDollars(base.total.toDouble())} · ${base.year}년")
        Hint("순수취액은 세계 달러 GDP 변화에 연동됩니다.")
        base.interest?.let { StatRow("연간 투자이자 순수취 기준액", formatCompactDollars(it.toDouble())) }
        income.netOnly?.let { monthly ->
            monthly.interest?.let { StatRow("월 투자이자 순수취", formatCompactDollars(it)) }
            if (monthly.interest != null || monthly.reinvested != null) {
                StatRow("월 기타·미분류 순수취", formatCompactDollars(monthly.other))
            }
        }
        income.netOnly?.reinvested?.let {
            StatRow("월 재투자수익 순수취", formatCompactDollars(it))
            StatRow("재투자 금융계정 대응액 (순유입)", formatCompactDollars(income.reinvestmentFinancing))
        }
        return
    }
    for ((label, base) in listOf("투자소득 연간 수취 기준액" to income.opening.received, "투자소득 연간 지급 기준액" to income.opening.paid)) {
        StatRow(label, base?.let { "${formatCompactDollars(it.total.toDouble())} · ${it.year}년" } ?: "—")
    }
    if (settled) {
        if (income.received != null && income.paid != null) {
            StatRow("월 투자소득 순수취", formatCompactDollars(income.net))
        }
        StatRow("월 배당 수취 · 지급", dollarPair(income.received?.dividends, income.paid?.dividends))
        StatRow("월 투자이자 수취 · 지급", dollarPair(income.received?.interest, income.paid?.interest))
        StatRow("월 재투자수익 수취 · 지급", dollarPair(income.received?.reinvested, income.paid?.reinvested))
        StatRow("월 기타·미분류 수취 · 지급", dollarPair(income.received?.other, income.paid?.other))
        if (income.received?.reinvested != null || income.paid?.reinvested != null) {
            StatRow("재투자 금융계정 대응액 (순유입)", formatCompactDollars(income.reinvestmentFinancing))
        }
        Hint(
            "배당에는 펀드 분배금과 준법인 소득인출이 포함됩니다. 기타·미분류는 배당·이자·재투자수익을 뺀 잔액입니다. 재투자수익은 경상수지와 금융계정에 함께 기록되어 외환보유액을 바꾸지 않습니다.",
        )
    }
}

@Composable
private fun MonetarySection(
    countryName: String,
    history: CountryHistory,
    monetary: MonetaryPolicy,
    politics: PoliticsSnapshot,
    onDirectRate: (Double) -> Unit,
    onOpenIndependence: () -> Unit,
    onViewBill: () -> Unit,
) {
    LedgerHeading("통화정책")
    StatRow("중앙은행", monetary.bankName ?: "$countryName 중앙은행")
    StatRow("통화 체제", monetary.framework.label + monetary.anchorCurrency?.let { " · $it" }.orEmpty())
    StatRow("기준금리", formatRate(monetary.policyRate))
    OpeningPolicyRate(monetary)
    if (monetary.quantitativeEasing) StatRow("자산 매입", "금리 하한 · 양적완화 중")
    StatRow("물가 목표", formatPercent(monetary.inflationTarget))
    StatRow("실질금리 (기준금리 − 기대 물가)", formatRate(monetary.realRate, signed = true))
    StatRow("통화 기조", monetary.stance.label, valueColor = stanceColor(monetary.stance))
    StatRow("통화 신뢰도", "${(monetary.credibility * 100).roundToInt()}점")
    LineChart(history[EconomySeries.POLICY_RATE], history.start, rateFormat, title = "기준금리")

    val pending = politics.pending
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
                    secondary =
                        if (monetary.directedRate != null) {
                            "지시 금리 · ${monetary.directiveMonths}개월 더 유지 · 지금 ${formatRate(monetary.policyRate)}"
                        } else {
                            "자동 결정 중(정부 지시 준칙) · 지금 ${formatRate(monetary.policyRate)}"
                        },
                    onValueChange = { onDirectRate((it / RATE_STEP).roundToInt() * RATE_STEP) },
                )
                Hint("지시한 금리는 다음 달 1일부터 12개월 동안 유지되고, 그 뒤에는 정부 지시 준칙으로 자동 결정됩니다. 금리를 물가보다 낮게 묶어 두면 경기는 살지만 물가가 스스로 오르기 시작합니다.")
            }
        }
    }
    if (proposedInBill) {
        PaperTag("중앙은행법 심의 보기", onClick = onViewBill)
    } else if (pending == null && monetary.framework.adjustableIndependence) {
        PaperTag("중앙은행 독립성 입안", onClick = onOpenIndependence)
    }
}

private fun decisionMaker(monetary: MonetaryPolicy): String =
    when (monetary.framework) {
        MonetaryFramework.INFLATION_TARGETING -> "독립 중앙은행"
        MonetaryFramework.MONETARY_UNION -> monetary.unionBank.orEmpty()
        MonetaryFramework.CURRENCY_PEG -> "기준 통화를 따름"
        MonetaryFramework.GOVERNMENT_DIRECTED -> "정부"
    }

@Composable
internal fun OpeningPolicyRate(monetary: MonetaryPolicy) {
    StatRow("시작 기준금리 (2024년 1월 1일)", formatRate(monetary.initialRate))
    StatRow("금리 결정", decisionMaker(monetary))
    monetary.unionExchangeAnchor?.let { StatRow("공동 통화의 환율 고정 대상", it) }
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

@Composable
private fun GovernorSection(
    monetary: MonetaryPolicy,
    capital: Double,
    onAppoint: (GovernorMandate) -> Unit,
    onPressure: (BankPressure) -> Unit,
) {
    val governor = monetary.governor
    LedgerHeading(if (monetary.unionBank == null) "중앙은행 총재" else "공동 중앙은행 총재")
    StatRow(governor.name, "${governor.mandate.label} · 임기 ${governor.termEnds}까지")
    governor.pressure?.let { StatRow("정부 의견", "${it.label} · ${governor.pressureMonths}개월 남음") }
    if (monetary.unionBank != null) {
        Hint("통화동맹의 공동 총재가 회원국 전체의 물가와 경기에 대응합니다.")
        return
    }
    Hint("새 총재의 정책 우선순위를 선택하세요. 임기는 6년이며 독립 총재를 임기 종료 6개월보다 일찍 교체하면 신뢰도가 하락합니다.")
    GovernorMandate.entries.forEach { mandate ->
        PaperTag("${mandate.label} 총재 임명 · ${governor.replacementCost.toInt()}", enabled = capital >= governor.replacementCost, onClick = {
            onAppoint(mandate)
        })
    }
    if (monetary.framework != MonetaryFramework.CURRENCY_PEG) {
        Hint("정부 의견은 세 차례의 월간 금리 결정에 영향을 주며 중앙은행 신뢰도가 낮아집니다. 독립 총재는 요구를 일부만 반영합니다.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BankPressure.entries.forEach { direction ->
                PaperTag(
                    "${direction.label} · ${governor.pressureCost.toInt()}",
                    enabled =
                        governor.pressure == null && monetary.directiveMonths == 0 && capital >= governor.pressureCost,
                    onClick = { onPressure(direction) },
                )
            }
        }
    }
}
