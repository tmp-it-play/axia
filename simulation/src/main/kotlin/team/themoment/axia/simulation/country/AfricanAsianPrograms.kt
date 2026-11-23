package team.themoment.axia.simulation.country

import team.themoment.axia.simulation.economy.Commodity

/** 관광 개발 대출 보증. 사업자가 부실해졌을 때만 보증 원금의 일부를 재정에서 부담한다. */
internal class TourismGuarantees : ProgramOperation {
    private val loans = ProgramCohorts(24)
    private var projects = 0.0
    private var lossRate = 0.06
    override val costFactor get() = 0.04 + lossRate

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val maturing = loans.advance(level)
        lossRate = (0.06 - 2.0 * context.outputGap).coerceIn(0.01, 0.45)
        val losses = maturing * lossRate
        projects = projects * 0.985 + maturing * (1.0 - lossRate) * 0.015
        return ProgramMonth(level * 0.04 + losses, projects, losses)
    }
}

/** 발라디 빵 가격을 유지하기 위한 공공 밀 매입. 시차가 있는 제분 원가에 따라 같은 공급량의 보조 비용이 달라진다. */
internal class BreadSubsidy : ProgramOperation {
    private var flourCost = 1.0
    override val costFactor get() = flourCost

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val grain = context.commodities.getValue(Commodity.GRAIN)
        val balance = grain.countries.getValue("EGY")
        flourCost += (grain.priceIndex / 100.0 - flourCost) / 4.0
        val available = if (balance.demand > 0.0) (balance.consumption / balance.demand).coerceIn(0.0, 1.0) else 1.0
        val bread = level * available
        return ProgramMonth(bread * flourCost, bread * flourCost, bread)
    }
}

/** 정비로 생긴 계획 정지와 작업이 끝난 뒤 줄어드는 비계획 정지를 따로 계산한다. */
internal class GenerationMaintenance : ProgramOperation {
    private val overhaul = ProgramCohorts(3)
    private var reliability = 0.55

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val complete = overhaul.advance(level)
        reliability += (0.32 + 0.58 * complete - reliability) / 12.0
        val planned = level * 0.16
        val winterLoss = if (context.date.monthValue in 6..8) 0.06 else 0.0
        return ProgramMonth(level, (reliability - planned - winterLoss).coerceIn(0.0, 1.0), planned)
    }
}

/** 제련소 인허가 이후 설비가 준공되어도 전력망 여유가 없으면 가동률을 높일 수 없다. */
internal class SmelterPermits : ProgramOperation {
    private var installed = 0.4
    private val permits = ProgramCohorts(18)
    private var gridCapacity = 0.62

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val complete = permits.advance(level)
        installed = installed * 0.99 + complete * 0.014
        val powerAvailable = (0.62 - context.outputGap * 1.5).coerceIn(0.3, 0.9)
        gridCapacity += (powerAvailable - gridCapacity) / 6.0
        val production = minOf(installed, gridCapacity)
        return ProgramMonth(level, production, (installed - production).coerceAtLeast(0.0))
    }
}

/** 2030년까지 모집하는 메콩 삼각주 저배출 쌀 전환. 작기별 교육을 마친 논에서만 이후 수확·투입재 절약이 발생한다. */
internal class RiceConversion : ProgramOperation {
    private var unenrolled = 1.0
    private var converted = 0.0
    private val training = ProgramCohorts(4)
    private var closed = false
    override val costFactor get() = if (closed || unenrolled <= 0.0) 0.0 else 1.0

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        closed = context.date.year > 2030
        val season = context.date.monthValue in setOf(3, 7, 11)
        val intake = if (season && !closed) minOf(unenrolled, level / 11.0) else 0.0
        unenrolled -= intake
        val graduate = training.advance(intake)
        converted += graduate
        val harvest = if (season) 1.0 else 0.2
        return ProgramMonth((intake + graduate) * 22.0, converted * harvest, converted)
    }
}

/** 일차 진료 역량을 유지해 진료 대기와 병원 의뢰를 줄인다. 진료 여력을 넘으면 다음 달에 일부가 병원으로 이동한다. */
internal class PrimaryCare : ProgramOperation {
    private var capacity = 0.54
    private var waiting = 0.1

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        capacity += (0.22 + level * 0.6 - capacity) / 8.0
        val referrals = waiting * 0.4
        val demand = 0.64 + context.unemployment.coerceIn(0.0, 0.35) * 0.35
        waiting = waiting - referrals + demand
        val treated = minOf(waiting, capacity)
        waiting -= treated
        return ProgramMonth(level + referrals * 0.25, treated, referrals)
    }
}

/** 매입한 젖소와 앞서 지급한 소의 첫 암송아지를 각각 배분한다. 대상 가구 수와 번식 시차가 확산 속도를 제한한다. */
internal class Girinka : ProgramOperation {
    private val breeding = ArrayDeque(List(24) { 0.0 })
    private var supplied = 0.0
    override val costFactor get() = if (supplied < 1.0) 1.0 else 0.0

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        val remaining = (1.0 - supplied).coerceAtLeast(0.0)
        val passed = minOf(remaining, breeding.removeFirst() * 0.65)
        val purchased = minOf(remaining - passed, level / 90.0)
        val placements = passed + purchased
        supplied += placements
        breeding.addLast(placements)
        return ProgramMonth(purchased * 90.0, supplied, passed * 90.0)
    }
}

/** 가구 자격조사를 마친 뒤 매달 급여 권리를 쌓고 분기말에 지급한다. 중도 정책 변경도 이미 쌓인 권리를 보전한다. */
internal class Kafaalat : ProgramOperation {
    private var registered = 0.5

    // 첫 월 갱신은 2월 1일이다. 1월 기본 급여 권리는 이미 적립돼 있다.
    private var accrued = 0.5
    private var resources = 0.0

    override fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth {
        registered += (level - registered) / 6.0
        accrued += registered
        val paid = if (context.date.monthValue % 3 == 0) accrued else 0.0
        accrued -= paid
        resources = resources * 0.65 + paid * 0.35
        return ProgramMonth(paid, resources, paid)
    }
}
