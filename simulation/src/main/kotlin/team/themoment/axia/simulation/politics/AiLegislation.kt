package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.PopulationGroup
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.tanh

/** 세입과 기초지출 목표는 기존 자동 재정 경로에서 더하거나 빼는 GDP 비율이다. */
data class AiFiscalBill(
    val title: String,
    val sponsor: String,
    val introduced: LocalDate,
    val revenueAdjustment: Double,
    val spendingAdjustment: Double,
    val decision: LocalDate? = null,
    val vote: RollCall? = null,
    val approved: Boolean? = null,
    val conference: Boolean = false,
    val upperVote: Int? = null,
    val delayed: Boolean = false,
)

internal class AiLegislation {
    var pending: AiFiscalBill? = null
        private set
    var lastDecision: AiFiscalBill? = null
        private set
    private var revenue = 0.0
    private var spending = 0.0

    fun dissolve(): Boolean {
        val lapsed = pending != null
        pending = null
        return lapsed
    }

    fun advance(
        date: LocalDate,
        id: String,
        government: String,
        chamber: Legislature?,
        upper: UpperHouse?,
        indicators: EconomyIndicators,
        approval: Double,
        enact: (String, Double, Double) -> Unit,
    ): WorldNews? {
        val bill = pending
        if (bill != null) {
            val deficit = bill.spendingAdjustment - spending - (bill.revenueAdjustment - revenue)
            val score = (48.0 + (approval - 50.0) * 0.4 - deficit * 200.0 + if (bill.conference) 5.0 else 0.0).coerceIn(20.0, 80.0)
            val vote = chamber?.forecast(score, score, PopulationGroup.entries.associateWith { score }, null)
            val lowerPassed = vote?.passes ?: (score >= 50.0)
            val upperYes = upper?.takeIf { chamber != null }?.forecast(score)
            val upperPassed = upperYes == null || upperYes > requireNotNull(upper).seats / 2
            if (lowerPassed && !upperPassed && !bill.conference) {
                pending =
                    bill.copy(
                        conference = true,
                        vote = vote,
                        upperVote = upperYes,
                        revenueAdjustment = (bill.revenueAdjustment + revenue) / 2,
                        spendingAdjustment = (bill.spendingAdjustment + spending) / 2,
                    )
                return WorldNews(date, id, requireNotNull(upper).name, "${bill.title} 양원 협의회 회부 · 다음 달 절충안 재표결", false)
            }
            if (lowerPassed && !upperPassed && upper.power == ChamberPower.DELAY && !bill.delayed) {
                pending = bill.copy(delayed = true, vote = vote, upperVote = upperYes)
                return WorldNews(date, id, upper.name, "${bill.title} 절충안 시행을 다음 달까지 유예했다.", false)
            }
            val passed = lowerPassed && (upperPassed || bill.delayed)
            lastDecision = bill.copy(decision = date, vote = vote, approved = passed, upperVote = upperYes)
            pending = null
            if (passed) {
                revenue = bill.revenueAdjustment
                spending = bill.spendingAdjustment
                enact(id, revenue, spending)
            }
            val verdict = if (passed) "가결" else "부결"
            val detail = vote?.let { "찬성 ${it.yes}표 / 가결선 ${it.required}표" } ?: "기관 심의"
            return WorldNews(date, id, chamber?.name ?: government, "${bill.title} $verdict · $detail", major = false)
        }
        if (Math.floorMod(date.monthValue + id.hashCode(), 6) != 0) return null
        val sponsor = chamber?.groups?.firstOrNull { it.role == PartyRole.LEAD }?.name ?: government
        // 정당별 경기 부양 선호는 실측 강령이 없는 경우 이름에서 고정해 둔 게임 계수다.
        val preference = Math.floorMod(sponsor.hashCode(), 101) / 100.0 - 0.5
        val stress = tanh((indicators.debtRatio - 0.7) * 1.5)
        val jobGap = tanh((indicators.unemployment - 0.06) * 12.0)
        val nextRevenue = (0.011 * stress + 0.006 * preference).coerceIn(-0.02, 0.025)
        val nextSpending = (0.015 * jobGap - 0.006 * stress + 0.009 * preference).coerceIn(-0.025, 0.03)
        if (abs(nextRevenue - revenue) + abs(nextSpending - spending) < 0.0005) return null
        val title = if (nextSpending - spending > nextRevenue - revenue) "고용·경기 예산안" else "재정 수지 조정안"
        pending = AiFiscalBill(title, sponsor, date, nextRevenue, nextSpending)
        return WorldNews(date, id, sponsor, "$title 발의 · 다음 달 ${chamber?.name ?: "정부 기관"} 심의", major = false)
    }
}
