package team.themoment.axia.simulation.society

import team.themoment.axia.simulation.economy.InitialEconomy
import team.themoment.axia.simulation.world.CountryStructure
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** 플레이어 국가의 인구 구조와 생활 지표 요약. */
data class DemographySnapshot(
    val fertility: Double,
    val lifeExpectancy: Double,
    val childShare: Double,
    val workingShare: Double,
    val elderlyShare: Double,
    /** 전체 15세 이상 인구 중 경제활동인구 비율. */
    val adultParticipation: Double,
    val workingAgeParticipation: Double,
    val olderParticipation: Double,
    val participationYear: Int,
    val participationModeled: Boolean,
    /** 조사한 65세 미만 수급자를 15~64세 전체 인구로 나눈 비율. */
    val earlyPensionOfWorkingAge: Double?,
    val earlyPensionFromAge: Int?,
    val earlyPensionSource: String?,
    val olderPension: OlderPensionCoverage?,
    /** 전체 인구 대비 이번 달 추정 연금 수요 인구. */
    val pensionDemandShare: Double,
    val urbanShare: Double,
    /** 순이민(연간, 명). */
    val netMigration: Double,
    /** 시작 = 100. 교육 투자가 5년에 걸쳐 쌓인 인적 자본. */
    val education: Double,
    /** 시작 = 100. 실질 집값. */
    val housePrice: Double,
    /** 소득 대비 집값의 시작 대비 변화. 양수면 집 사기가 쉬워졌다. */
    val affordability: Double,
)

/** 인구 구조 계산에 쓰는 이번 달 정책·경제 재료. */
internal data class DemographyInputs(
    /** 사회보장 지출의 시작 대비 변화(GDP 대비). */
    val socialChange: Double,
    val healthChange: Double,
    val educationChange: Double,
    /** 국경 간 이동과 이민 정책으로 해당 월 기준 인구 성장 경로에 더해진 인원(연환산). */
    val annualizedMigrationChange: Double,
    /** 1인당 실질소득 지수(시작 = 1). */
    val incomeIndex: Double,
    val realRate: Double,
    val housingSupply: Double,
)

/**
 * 플레이어 국가의 인구 구조와 장기 추세. 매월 조금씩 움직인다.
 *
 * - 출산율은 소득 수준에 맞는 장기 수준(저출산국 1.3, 그 밖 1.6)으로 서서히 수렴하고, 사회보장 확대가 조금 올린다.
 * - 아동·고령 비중은 출산율과 기대수명이 정하는 목표로 각각 25년, 40년 반감기로 움직인다.
 * - 기대수명은 소득과 보건 지출로 오른다. 도시화율은 90%를 향해 천천히 오른다.
 * - 연령대별 경제활동참가율을 적용한 노동력 비중의 변화가 잠재성장률에 반영된다.
 * - 집값은 소득 증가와 실질금리로 움직이고, 소득 대비 집값은 청년·부모 성향의 만족도에 들어간다.
 */
internal class Demography(
    initial: InitialEconomy,
    structure: CountryStructure,
) {
    private var fertility = structure.fertility
    private var lifeExpectancy = structure.lifeExpectancy
    private var lifeTrend = structure.lifeExpectancy
    private var childShare = initial.society.childShare
    private var elderlyShare = initial.society.elderlyShare
    val activity = AgeActivity.country(initial.id)
    private val workingShare: Double get() = 1.0 - childShare - elderlyShare
    private val laborShare: Double get() = workingShare * activity.workingAgeParticipation + elderlyShare * activity.olderParticipation
    private val pensionDemand: Double
        get() =
            elderlyShare * (activity.olderPension?.modeledRate ?: 1.0) +
                workingShare * (activity.earlyPension?.recipientsOfWorkingAge ?: 0.0)
    private val initialPensionDemand = pensionDemand
    private var urbanShare = structure.urbanShare
    private val baseMigration = structure.netMigration
    private var netMigration = baseMigration
    private var education = 100.0
    private var housePrice = 100.0
    private var lastIncome = 1.0
    private var lastLaborShare = laborShare

    /** 이번 달 경제활동인구 비중 변화로 생긴 연율 노동력 증가 효과. */
    var laborGrowthEffect = 0.0
        private set

    /** 연령 자료에서 0~14세를 뺀 15세 이상 인구를 성인 집단의 분모로 쓴다. */
    val adultShare: Double get() = 1.0 - childShare
    val retireeShare: Double get() {
        val younger = workingShare * (activity.earlyPension?.nonEmployedOfWorkingAge ?: 0.0)
        return (elderlyShare * (1.0 - activity.olderParticipation) + younger) / adultShare
    }

    /** 고령층 수급 범위와 50~64세 노령연금 수급자에 따른 사회보장 수요 배율(시작 = 1). */
    val pensionPressure: Double get() = pensionDemand / initialPensionDemand

    fun advanceMonth(inputs: DemographyInputs) {
        val floor = if (fertility < LOW_FERTILITY_TRAP) LOW_FERTILITY_FLOOR else LONG_RUN_FERTILITY
        val fertilityTarget = floor + (fertility - floor) * FERTILITY_PERSISTENCE + SOCIAL_FERTILITY * inputs.socialChange
        fertility += (fertilityTarget.coerceIn(0.6, 8.0) - fertility) / FERTILITY_ADJUST_MONTHS
        // 기대수명 = 의료 기술로 오르는 기본 추세 + 소득 효과 + 보건 지출 효과.
        lifeTrend += (BASE_LIFE_GAIN * (MAX_LIFE - lifeTrend) / LIFE_GAIN_SPAN).coerceIn(0.0, MAX_LIFE_GAIN) / 12.0
        val lifeTarget = lifeTrend + LIFE_PER_LOG_INCOME * ln(inputs.incomeIndex.coerceAtLeast(0.1)) + HEALTH_LIFE * inputs.healthChange
        lifeExpectancy += (lifeTarget.coerceAtMost(MAX_LIFE) - lifeExpectancy) / LIFE_ADJUST_MONTHS
        val childTarget = (0.1 + 0.08 * (fertility - 1.2)).coerceIn(0.08, 0.5)
        val elderlyTarget = (0.05 + 0.012 * (lifeExpectancy - 60.0) - 0.04 * (fertility - 1.5)).coerceIn(0.02, 0.45)
        childShare += (childTarget - childShare) * (1.0 - 0.5.pow(1.0 / (CHILD_HALF_LIFE_YEARS * 12.0)))
        elderlyShare += (elderlyTarget - elderlyShare) * (1.0 - 0.5.pow(1.0 / (ELDERLY_HALF_LIFE_YEARS * 12.0)))
        laborGrowthEffect = ln(laborShare / lastLaborShare) * 12.0
        lastLaborShare = laborShare
        urbanShare += (URBAN_CEILING - urbanShare) * URBANIZATION_RATE / 12.0
        netMigration = baseMigration + inputs.annualizedMigrationChange
        education += (100.0 * (1.0 + EDUCATION_RESPONSE * inputs.educationChange) - education) / EDUCATION_LAG_MONTHS
        val incomeGrowth = ln(inputs.incomeIndex / lastIncome) * 12.0
        lastIncome = inputs.incomeIndex
        // 소득 대비 집값이 시작보다 높으면 수요가 줄어 서서히 되돌아온다.
        val valuation = ln(housePrice / (100.0 * inputs.incomeIndex)) + inputs.housingSupply
        val realRate = inputs.realRate.coerceIn(-MAX_REAL_RATE, MAX_REAL_RATE)
        val priceGrowth =
            (incomeGrowth + RATE_SENSITIVITY * (NEUTRAL_REAL_RATE - realRate) - VALUATION_REVERSION * valuation).coerceIn(-0.2, 0.2)
        housePrice *= exp(priceGrowth / 12.0)
    }

    /** 소득 대비 집값의 시작 대비 로그 변화(양수 = 사기 쉬워짐). */
    fun affordability(incomeIndex: Double): Double = ln(incomeIndex * 100.0 / housePrice)

    fun snapshot(incomeIndex: Double): DemographySnapshot =
        DemographySnapshot(
            fertility = fertility,
            lifeExpectancy = lifeExpectancy,
            childShare = childShare,
            workingShare = workingShare,
            elderlyShare = elderlyShare,
            adultParticipation = laborShare / adultShare,
            workingAgeParticipation = activity.workingAgeParticipation,
            olderParticipation = activity.olderParticipation,
            participationYear = activity.year,
            participationModeled = activity.series == "EAP_2WAP_SEX_AGE_RT",
            earlyPensionOfWorkingAge = activity.earlyPension?.recipientsOfWorkingAge,
            earlyPensionFromAge = activity.earlyPension?.fromAge,
            earlyPensionSource = activity.earlyPension?.let { "${it.year}년 ${it.source}" },
            olderPension = activity.olderPension,
            pensionDemandShare = pensionDemand,
            urbanShare = urbanShare,
            netMigration = netMigration,
            education = education,
            housePrice = housePrice,
            affordability = affordability(incomeIndex),
        )

    private companion object {
        const val LOW_FERTILITY_TRAP = 1.4
        const val LOW_FERTILITY_FLOOR = 1.0
        const val LONG_RUN_FERTILITY = 1.6
        const val FERTILITY_PERSISTENCE = 0.97
        const val FERTILITY_ADJUST_MONTHS = 24.0

        /** 사회보장 지출 GDP 1%p 확대가 올리는 합계출산율. */
        const val SOCIAL_FERTILITY = 3.0
        const val LIFE_PER_LOG_INCOME = 4.0
        const val HEALTH_LIFE = 150.0
        const val LIFE_ADJUST_MONTHS = 60.0
        const val BASE_LIFE_GAIN = 0.15
        const val LIFE_GAIN_SPAN = 25.0
        const val MAX_LIFE_GAIN = 0.3
        const val MAX_LIFE = 95.0
        const val CHILD_HALF_LIFE_YEARS = 25.0
        const val ELDERLY_HALF_LIFE_YEARS = 40.0
        const val URBAN_CEILING = 0.9
        const val URBANIZATION_RATE = 0.02
        const val EDUCATION_RESPONSE = 8.0
        const val EDUCATION_LAG_MONTHS = 60.0
        const val RATE_SENSITIVITY = 2.0
        const val NEUTRAL_REAL_RATE = 0.01
        const val VALUATION_REVERSION = 0.08
        const val MAX_REAL_RATE = 0.1
    }
}

/** 세계 장기 추세 요약. */
data class TrendsSnapshot(
    /** 산업화 이전 대비 지구 평균기온 상승(°C). */
    val temperature: Double,
    /** 세계 온실가스 배출 지수(시작 = 100). */
    val emissions: Double,
    /** 기술 수준 지수(시작 = 100). */
    val technology: Double,
)

/**
 * 세계 장기 추세: 기후와 기술.
 * - 배출 = 세계 실질 GDP × 탄소 집약도. 집약도는 기술 발전으로 매년 2%씩 줄고, 플레이어의 환경 규제가 자국 몫을 더 줄인다.
 * - 기온은 누적 배출에 비례해 오른다. 시작 배출이 유지되면 연 0.025°C 오른다(최근 10년 추세).
 * - 기온 상승은 홍수·가뭄·폭염·산불·폭풍 확률을 높인다(사건사고).
 * - 기술 수준은 선진국 생산성 증가(연 1.2%)로 오른다.
 */
internal class WorldTrends {
    private var temperature = START_TEMPERATURE
    private var intensity = 1.0
    private var emissions = 1.0
    private var technology = 100.0
    private var startGdp = 0.0

    /** @param playerShare 세계 GDP 중 플레이어 비중, @param playerCut 플레이어 환경 규제로 줄어든 배출 비율 */
    fun advanceMonth(
        worldRealGdp: Double,
        playerShare: Double,
        playerCut: Double,
    ) {
        if (startGdp == 0.0) startGdp = worldRealGdp
        intensity *= (1.0 - INTENSITY_DECLINE).pow(1.0 / 12.0)
        emissions = worldRealGdp / startGdp * intensity * (1.0 - playerShare * playerCut)
        temperature += WARMING_PER_YEAR * emissions / 12.0
        technology *= (1.0 + TECHNOLOGY_GROWTH).pow(1.0 / 12.0)
    }

    /** 사건 확률에 쓰는 기온 편차(시작 대비). */
    val climateAnomaly: Double get() = temperature - START_TEMPERATURE

    fun snapshot(): TrendsSnapshot = TrendsSnapshot(temperature, emissions * 100.0, technology)

    private companion object {
        const val START_TEMPERATURE = 1.45
        const val INTENSITY_DECLINE = 0.02
        const val WARMING_PER_YEAR = 0.025
        const val TECHNOLOGY_GROWTH = 0.012
    }
}
