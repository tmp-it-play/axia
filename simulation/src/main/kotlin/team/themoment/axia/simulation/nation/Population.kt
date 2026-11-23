package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.economy.InitialSociety
import kotlin.math.pow

/** 지지율 원인 분해에 쓰는 만족도 요인. */
enum class SatisfactionFactor(
    val label: String,
) {
    PRICES("물가"),
    JOBS("일자리"),
    INCOME("소득"),
    TAXES("세금"),
    SERVICES("공공서비스"),
    ECONOMY("경기"),
    EVENTS("사건"),
}

/**
 * 성인 인구를 나눈 집단. 수치는 집단 간 상대적인 차이를 나타낸다.
 *
 * @property incomeMultiplier 평균 대비 소득 배율. 지니계수로 불평등을 반영해 조정한다.
 * @property influence 지지율에 미치는 영향력(인구 1명당).
 * @property transferWeight 사회보장 지출 1(GDP 대비)이 소득에 더하는 비율.
 */
enum class PopulationGroup(
    val label: String,
    val incomeMultiplier: Double,
    val influence: Double,
    val priceSensitivity: Double,
    val jobSensitivity: Double,
    val economySensitivity: Double,
    val transferWeight: Double,
    val serviceWeights: Map<SpendingKind, Double>,
    val taxIncidence: Map<TaxKind, Double>,
) {
    CAPITALISTS(
        "자본가·고소득층",
        6.0,
        4.0,
        0.4,
        0.3,
        1.5,
        0.1,
        services(defense = 0.3, education = 0.2, health = 0.2, social = 0.0, order = 0.4, economy = 0.6),
        taxes(income = 1.6, corporate = 2.5, consumption = 0.5, social = 0.3, tariff = 0.5),
    ),
    PROFESSIONALS(
        "전문·관리직",
        2.0,
        1.6,
        0.7,
        0.6,
        1.0,
        0.3,
        services(defense = 0.2, education = 0.6, health = 0.4, social = 0.1, order = 0.3, economy = 0.3),
        taxes(income = 1.4, corporate = 0.3, consumption = 0.8, social = 1.0, tariff = 0.8),
    ),
    OFFICE(
        "사무·서비스직",
        1.0,
        1.0,
        1.0,
        1.0,
        0.8,
        0.8,
        services(defense = 0.2, education = 0.5, health = 0.5, social = 0.3, order = 0.3, economy = 0.2),
        taxes(income = 1.0, corporate = 0.1, consumption = 1.0, social = 1.0, tariff = 1.0),
    ),
    SELF_EMPLOYED(
        "자영업자",
        0.9,
        1.1,
        1.0,
        0.8,
        1.3,
        0.6,
        services(defense = 0.2, education = 0.3, health = 0.4, social = 0.2, order = 0.4, economy = 0.5),
        taxes(income = 0.8, corporate = 0.4, consumption = 1.0, social = 0.6, tariff = 1.0),
    ),
    WORKERS(
        "산업 노동자",
        0.9,
        1.0,
        1.2,
        1.4,
        0.9,
        1.2,
        services(defense = 0.2, education = 0.3, health = 0.5, social = 0.5, order = 0.3, economy = 0.5),
        taxes(income = 0.9, corporate = 0.1, consumption = 1.05, social = 1.0, tariff = 1.1),
    ),
    FARMERS(
        "농림어업",
        0.5,
        0.8,
        1.2,
        0.6,
        0.6,
        1.5,
        services(defense = 0.2, education = 0.3, health = 0.5, social = 0.5, order = 0.2, economy = 0.7),
        taxes(income = 0.3, corporate = 0.0, consumption = 1.1, social = 0.2, tariff = 1.1),
    ),
    PUBLIC_SECTOR(
        "공공부문",
        1.15,
        1.2,
        0.9,
        0.3,
        0.4,
        0.5,
        services(defense = 0.3, education = 0.6, health = 0.5, social = 0.4, order = 0.6, economy = 0.3),
        taxes(income = 1.0, corporate = 0.0, consumption = 1.0, social = 1.0, tariff = 1.0),
    ),
    RETIREES(
        "은퇴·고령층",
        0.6,
        1.0,
        1.3,
        0.2,
        0.4,
        3.0,
        services(defense = 0.3, education = 0.1, health = 1.0, social = 1.0, order = 0.4, economy = 0.1),
        taxes(income = 0.4, corporate = 0.1, consumption = 1.0, social = 0.0, tariff = 1.0),
    ),
}

private fun services(
    defense: Double,
    education: Double,
    health: Double,
    social: Double,
    order: Double,
    economy: Double,
) = mapOf(
    SpendingKind.DEFENSE to defense,
    SpendingKind.EDUCATION to education,
    SpendingKind.HEALTH to health,
    SpendingKind.SOCIAL to social,
    SpendingKind.ORDER to order,
    SpendingKind.ECONOMY to economy,
)

private fun taxes(
    income: Double,
    corporate: Double,
    consumption: Double,
    social: Double,
    tariff: Double,
) = mapOf(
    TaxKind.INCOME to income,
    TaxKind.CORPORATE to corporate,
    TaxKind.CONSUMPTION to consumption,
    TaxKind.SOCIAL to social,
    TaxKind.TARIFF to tariff,
)

/** 집단별 성인 인구 비중과 시작 시점 1인당 소득(연간 달러). */
internal data class GroupComposition(
    val shares: Map<PopulationGroup, Double>,
    val incomes: Map<PopulationGroup, Double>,
)

/**
 * 고용 구조, 자영업 비중, 고령 인구 비중, 소득 그룹별 공공부문 비중으로 집단 구성을 정하고,
 * 기본 소득 배율에 지니계수를 반영해 가계소득(GDP의 [HOUSEHOLD_INCOME_SHARE])을 나눈다.
 */
internal fun composeGroups(
    society: InitialSociety,
    gdp: Double,
    population: Double,
): GroupComposition {
    val adults = 1.0 - society.childShare
    val retirees = (society.elderlyShare / adults).coerceIn(0.0, 0.6)
    val workforce = 1.0 - retirees
    val public = PUBLIC_SECTOR_SHARE[society.incomeGroup] ?: 0.1
    val rest = 1.0 - CAPITALIST_SHARE - public
    val services = society.servicesEmployment * rest
    val selfEmployed =
        ((society.selfEmployment - society.agricultureEmployment * 0.8).coerceAtLeast(0.0) * rest).coerceAtMost(services * 0.6)
    val professionals = (services - selfEmployed) * (PROFESSIONAL_SHARE[society.incomeGroup] ?: 0.2)
    val workforceShares =
        mapOf(
            PopulationGroup.CAPITALISTS to CAPITALIST_SHARE,
            PopulationGroup.PROFESSIONALS to professionals,
            PopulationGroup.OFFICE to services - selfEmployed - professionals,
            PopulationGroup.SELF_EMPLOYED to selfEmployed,
            PopulationGroup.WORKERS to society.industryEmployment * rest,
            PopulationGroup.FARMERS to society.agricultureEmployment * rest,
            PopulationGroup.PUBLIC_SECTOR to public,
        )
    val shares = workforceShares.mapValues { it.value * workforce } + (PopulationGroup.RETIREES to retirees)

    val inequality = society.gini / REFERENCE_GINI
    val multipliers = shares.keys.associateWith { it.incomeMultiplier.pow(inequality) }
    val weightedMean = shares.entries.sumOf { (group, share) -> share * multipliers.getValue(group) }
    val averageIncome = HOUSEHOLD_INCOME_SHARE * gdp / (population * adults)
    val incomes = multipliers.mapValues { (_, multiplier) -> averageIncome * multiplier / weightedMean }
    return GroupComposition(shares, incomes)
}

private const val CAPITALIST_SHARE = 0.015
private const val HOUSEHOLD_INCOME_SHARE = 0.6
private const val REFERENCE_GINI = 0.33
private val PUBLIC_SECTOR_SHARE = mapOf("HIC" to 0.16, "UMC" to 0.12, "LMC" to 0.09, "LIC" to 0.06)
private val PROFESSIONAL_SHARE = mapOf("HIC" to 0.35, "UMC" to 0.25, "LMC" to 0.18, "LIC" to 0.12)
