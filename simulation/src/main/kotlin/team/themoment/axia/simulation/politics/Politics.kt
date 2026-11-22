package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.GroupSnapshot
import team.themoment.axia.simulation.nation.PlayerBudget
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class BudgetChange(
    val item: BudgetItem,
    val from: Double,
    val to: Double,
) {
    val delta: Double get() = to - from
}

data class BillSupport(
    val groupScores: Map<PopulationGroup, Double>,
    val constituency: Double,
    val executiveBacking: Double,
    val fundingPenalty: Double,
    val rollCall: RollCall?,
) {
    val score: Double
        get() {
            val total = (constituency + executiveBacking - fundingPenalty).coerceIn(0.0, 100.0)
            return (total * 10).roundToInt() / 10.0
        }
}

enum class BillStage(
    val label: String,
) {
    REVIEW("심의 중"),
    STALLED("정체 · 재심의"),
    PASSED("통과"),
    REJECTED("좌초"),
}

data class FiscalBill(
    val changes: List<BudgetChange>,
    val introduced: LocalDate,
    val stage: BillStage,
    /** 진행 중에는 다음 심의일, 종결 후에는 결정일. */
    val decisionDate: LocalDate,
    val support: BillSupport,
    val firstReview: BillSupport? = null,
)

data class PoliticsSnapshot(
    val profile: PoliticalProfile,
    val draft: Map<BudgetItem, BudgetChange>,
    val draftSupport: BillSupport?,
    val pending: FiscalBill?,
    val lastDecision: FiscalBill?,
)

/** 한 번에 하나의 재정안을 심의한다. 정체된 안은 4주 후 최종 심의하며 통과된 목표만 예산에 전달한다. */
internal class Politics(
    private val profile: PoliticalProfile,
    private val budget: PlayerBudget,
) {
    private var groups: List<GroupSnapshot> = emptyList()
    private val draft = linkedMapOf<BudgetItem, BudgetChange>()
    private var negotiatingWith: String? = null
    private var pending: FiscalBill? = null
    private var lastDecision: FiscalBill? = null

    fun updateConstituencies(latest: List<GroupSnapshot>) {
        groups = latest
        pending = pending?.let { it.copy(support = assess(it.changes, it.support.rollCall?.negotiatingWith)) }
    }

    fun edit(
        item: BudgetItem,
        target: Double,
    ) {
        if (pending != null || !target.isFinite()) return
        val from = budget.target(item)
        val to = target.coerceIn(0.0, item.maxTarget)
        // Slider는 Float로 왕복한다. 원래 목표로 돌린 항목을 변경안에 남기지 않는다.
        if (abs(to - from) < 1e-7) draft.remove(item) else draft[item] = BudgetChange(item, from, to)
        if (draft.isEmpty()) negotiatingWith = null
    }

    fun clearDraft() {
        draft.clear()
        negotiatingWith = null
    }

    fun negotiate(party: String?) {
        if (pending != null || draft.isEmpty()) return
        if (party == null || profile.legislature?.groups?.any { it.name == party && it.canNegotiate } == true) {
            negotiatingWith = party
        }
    }

    fun reviseStalledBill() {
        val bill = pending ?: return
        if (bill.stage != BillStage.STALLED) return
        bill.changes.associateByTo(draft) { it.item }
        negotiatingWith = bill.support.rollCall?.negotiatingWith
        pending = null
    }

    fun introduce(date: LocalDate) {
        if (pending != null || draft.isEmpty()) return
        val changes = draft.values.toList()
        val negotiationDays = if (negotiatingWith == null) 0 else 14
        val days = 21 + negotiationDays + (35 * profile.legislativeConstraints).roundToLong()
        pending = FiscalBill(changes, date, BillStage.REVIEW, date.plusDays(days), assess(changes, negotiatingWith))
        clearDraft()
    }

    /** 월초 집행이 끝난 뒤 호출한다. 같은 날 통과해도 실제 집행은 다음 달부터 시작한다. */
    fun advanceDay(date: LocalDate): Boolean {
        val bill = pending ?: return false
        if (date < bill.decisionDate) return false
        val support = bill.support
        val passes = support.rollCall?.passes ?: (support.score >= 50.0)
        val close = support.rollCall?.close ?: (support.score >= 40.0)
        val stage =
            when {
                passes -> BillStage.PASSED
                close && bill.stage == BillStage.REVIEW -> BillStage.STALLED
                else -> BillStage.REJECTED
            }
        if (stage == BillStage.STALLED) {
            pending = bill.copy(stage = stage, decisionDate = date.plusWeeks(4), firstReview = support)
        } else {
            if (stage == BillStage.PASSED) bill.changes.forEach { budget.enact(it.item, it.to) }
            lastDecision = bill.copy(stage = stage, decisionDate = date)
            pending = null
        }
        return stage == BillStage.PASSED
    }

    fun snapshot(): PoliticsSnapshot =
        PoliticsSnapshot(
            profile = profile,
            draft = draft.toMap(),
            draftSupport = if (draft.isEmpty()) null else assess(draft.values, negotiatingWith),
            pending = pending,
            lastDecision = lastDecision,
        )

    private fun assess(
        changes: Collection<BudgetChange>,
        partner: String?,
    ): BillSupport {
        // 시민사회 참여도가 높을수록 인구 비중, 낮을수록 기존 집단별 정치 영향력에 가까워진다.
        val weights =
            groups.associate { state ->
                state.group to state.share * (profile.civilParticipation + (1.0 - profile.civilParticipation) * state.group.influence)
            }
        val scores =
            groups.associate { state ->
                val group = state.group
                val policyEffect =
                    changes.sumOf { change ->
                        when (val item = change.item) {
                            is TaxKind -> -change.delta * group.taxIncidence.getValue(item) * 160.0
                            is SpendingKind -> change.delta * group.serviceWeights.getValue(item) * 240.0
                        }
                    }
                group to (state.satisfaction + policyEffect).coerceIn(0.0, 100.0)
            }
        val constituency = scores.entries.sumOf { (group, score) -> score * weights.getValue(group) } / weights.values.sum()
        val addedDeficit =
            changes.sumOf { change ->
                when (val item = change.item) {
                    is TaxKind -> -change.delta * item.baseShare
                    is SpendingKind -> change.delta
                }
            }
        val executiveBacking = 4.0 + 12.0 * (1.0 - profile.legislativeConstraints)
        val fundingPenalty = addedDeficit.coerceAtLeast(0.0) * 180.0
        return BillSupport(
            groupScores = scores,
            constituency = constituency,
            executiveBacking = executiveBacking,
            fundingPenalty = fundingPenalty,
            rollCall = profile.legislature?.forecast(constituency + executiveBacking - fundingPenalty, constituency, scores, partner),
        )
    }
}
