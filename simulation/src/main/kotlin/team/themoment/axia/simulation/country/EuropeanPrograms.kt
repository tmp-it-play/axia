package team.themoment.axia.simulation.country

import team.themoment.axia.simulation.economy.Commodity
import java.time.LocalDate
import kotlin.math.pow

/** 정책 0.5가 시작 급여 PLN 800이다. 명목 지급액의 구매력과 소비 반응 시차를 구분한다. */
internal class FamilyAllowance : ProgramOperation {
    private var purchasingPower = 1.0
    private var consumption = 0.5
    override val costFactor get() = purchasingPower

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        // 극단적 디플레이션에서도 인정하는 실질 급여는 2024년 단가의 세 배까지로 모형화한다.
        purchasingPower = (purchasingPower / (1.0 + context.inflation).pow(1.0 / 12.0)).coerceIn(0.0, 3.0)
        val realBenefit = level * purchasingPower
        consumption += (realBenefit - consumption) / 3.0
        return ProgramMonth(realBenefit, consumption, realBenefit)
    }
}

/** 유럽회복기금 사업의 고정 사업량. 시공 후 반기별 검수에서 보조금 몫을 정산한다. */
internal class RecoveryMilestones : ProgramOperation {
    private var completed = 0.0
    private var certified = 0.0
    private var closed = false
    override val costFactor get() = if (closed || completed >= 1.0) 0.0 else 0.5

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val finalMonth = LocalDate.of(2026, 8, 1)
        closed = context.date >= finalMonth
        val work = if (context.date <= finalMonth) minOf(1.0 - completed, level / 20.0) else 0.0
        completed += work
        val review = context.date.monthValue in listOf(6, 12) || context.date == finalMonth
        val acceptance = if (review && context.date <= finalMonth) completed - certified else 0.0
        certified += acceptance
        // 20은 표준 월 사업량 대비 환산 배수, 0.5는 보조금으로 상환되는 사업비의 가정 비율이다.
        return ProgramMonth((work - acceptance * 0.5) * 20.0, certified, work * 20.0)
    }
}

/** 입주지원 주택을 직원과 함께 확보하고, 장기 입주자가 퇴거한 자리에 대기 가구를 배정한다. */
internal class HousingFirst : ProgramOperation {
    private var units = 0.45
    private var occupied = 0.40
    private var waiting = 0.15

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        units += (level - units) / 12.0
        occupied *= 0.98
        waiting += 0.014 + context.unemployment.coerceIn(0.0, 0.4) * 0.05
        val placements = minOf(waiting, (units - occupied).coerceAtLeast(0.0), 0.035)
        occupied += placements
        waiting = (waiting - placements) * 0.985
        return ProgramMonth(units * 0.6 + occupied * 0.4, occupied, waiting)
    }
}

/** 디지털 신원 심사 대기열과 실제 창업은 별개다. 승인 뒤 일부가 법인을 설립하고, 기존 법인은 매달 일부 퇴출된다. */
internal class ElectronicResidency : ProgramOperation {
    private var pending = 0.08
    private var companies = 0.45
    private val approved = ProgramCohorts(3)

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        pending = pending * 0.92 + (0.075 + context.outputGap * 0.3).coerceIn(0.03, 0.12)
        val processed = minOf(pending, 0.02 + 0.10 * level)
        pending -= processed
        companies = companies * 0.985 + approved.advance(processed) * 0.12
        return ProgramMonth(level, companies, pending)
    }
}

/** 아파트 단열 사업은 준공 뒤에만 보조금을 지급한다. 개보수한 건물은 겨울 난방 원가 충격을 줄인다. */
internal class ApartmentRenovation : ProgramOperation {
    private val sites = ProgramCohorts(9)
    private var uncommitted = 1.0
    private var renovated = 0.0
    override val costFactor get() = if (uncommitted > 0.0) 1.0 else 0.0

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val starts = minOf(uncommitted, level * 0.012)
        uncommitted -= starts
        val completed = sites.advance(starts)
        renovated += completed
        val heating =
            when (context.date.monthValue) {
                11, 12, 1, 2, 3 -> 1.0
                4, 10 -> 0.55
                else -> 0.12
            }
        val fuel = (context.commodities.getValue(Commodity.CRUDE_OIL).priceIndex / 100.0).coerceIn(0.3, 3.0)
        return ProgramMonth(completed / 0.012, renovated * heating * fuel, starts / 0.012)
    }
}

/** 연간 LNG 수입 슬롯을 매달 분할 예약하므로 기존 예약은 만기까지 유지된다. 겨울과 연료가 비쌀 때 활용도가 높다. */
internal class TerminalBookings : ProgramOperation {
    private val contracts = ArrayDeque(List(12) { 0.5 })
    private var capacity = 0.5

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        capacity += (level - contracts.removeFirst()) / 12.0
        contracts.addLast(level)
        val fuel = context.commodities.getValue(Commodity.CRUDE_OIL).priceIndex / 100.0
        val winter = context.date.monthValue in setOf(11, 12, 1, 2, 3)
        val demand = ((if (winter) 0.8 else 0.4) + (fuel - 1.0) * 0.3).coerceIn(0.1, 1.0)
        val delivered = minOf(capacity, demand)
        return ProgramMonth(capacity, delivered, (capacity - delivered).coerceAtLeast(0.0))
    }
}

/** 2023년 지진 피해 주택에 한정된 복구 수요. 기반 복구·시공의 고정 기간 후 가구가 귀환한다. */
internal class EarthquakeHousing : ProgramOperation {
    private var unassigned = 1.0
    private var returned = 0.0
    private val builds = ProgramCohorts(12)
    override val costFactor get() = if (unassigned > 0.0) 1.0 else 0.0

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val starts = minOf(unassigned, level / 24.0)
        unassigned -= starts
        val completions = builds.advance(starts)
        returned += completions
        return ProgramMonth((starts * 0.65 + completions * 0.35) * 24.0, returned, starts * 24.0)
    }
}
