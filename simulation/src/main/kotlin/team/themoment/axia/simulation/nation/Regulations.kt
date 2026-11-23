package team.themoment.axia.simulation.nation

/** 예산이 아닌 정책. 0은 가장 약함, 1은 가장 강함이다. */
enum class RegulationItem(
    override val label: String,
    /** 0과 1 끝의 설명. */
    val lowLabel: String,
    val highLabel: String,
) : PolicyItem {
    ENVIRONMENT("환경 규제", "느슨함", "엄격함"),
    LABOR("노동 보호", "유연함", "강한 보호"),
    IMMIGRATION("이민 개방", "제한", "개방"),
    POLICING("치안·감시 강도", "최소한", "강력함"),
    CIVIL_LIBERTIES("시민 자유", "억압", "완전 보장"),
    ;

    override val maxTarget: Double get() = 1.0
}

data class RegulationLine(
    val item: RegulationItem,
    val initialLevel: Double,
    val targetLevel: Double,
    val level: Double,
    val estimated: Boolean,
)

/**
 * 플레이어 국가의 비예산 정책. 통과한 목표는 다음 달부터 [PHASE_IN_MONTHS]에 걸쳐 실제값이 따라간다.
 * 시작값은 시민 자유만 V-Dem 지수이고 나머지는 소득 그룹 기반 추정이다.
 */
internal class Regulations(
    incomeGroup: String,
    civilLiberties: Double,
) {
    val initialLevels: Map<RegulationItem, Double> =
        mapOf(
            RegulationItem.ENVIRONMENT to (ENVIRONMENT_BY_INCOME[incomeGroup] ?: 0.3),
            RegulationItem.LABOR to (LABOR_BY_INCOME[incomeGroup] ?: 0.4),
            RegulationItem.IMMIGRATION to (IMMIGRATION_BY_INCOME[incomeGroup] ?: 0.3),
            RegulationItem.POLICING to (0.35 + 0.5 * (1.0 - civilLiberties)).coerceIn(0.0, 1.0),
            RegulationItem.CIVIL_LIBERTIES to civilLiberties.coerceIn(0.0, 1.0),
        )
    private val targets = initialLevels.toMutableMap()
    private val levels = initialLevels.toMutableMap()

    fun target(item: RegulationItem): Double = targets.getValue(item)

    fun level(item: RegulationItem): Double = levels.getValue(item)

    /** 시작 대비 실제값 변화. */
    fun change(item: RegulationItem): Double = levels.getValue(item) - initialLevels.getValue(item)

    fun enact(
        item: RegulationItem,
        target: Double,
    ) {
        require(target in 0.0..item.maxTarget)
        targets[item] = target
    }

    fun advanceMonth() {
        RegulationItem.entries.forEach { levels[it] = levels.getValue(it) + (targets.getValue(it) - levels.getValue(it)) / PHASE_IN_MONTHS }
    }

    /** 환경 규제 강화는 잠재성장률을 조금 깎는다. */
    val potentialGrowthEffect: Double get() = -ENVIRONMENT_GROWTH_COST * change(RegulationItem.ENVIRONMENT)

    /** 노동 보호 강화는 자연실업률을 조금 올린다. */
    val naturalUnemploymentShift: Double get() = LABOR_UNEMPLOYMENT * change(RegulationItem.LABOR)

    /** 이민 개방은 인구 증가율을 올린다. */
    val populationGrowthShift: Double get() = IMMIGRATION_POPULATION * change(RegulationItem.IMMIGRATION)

    /** 노동 보호가 집단 세전소득에 주는 배율. */
    fun incomeEffect(group: PopulationGroup): Double = 1.0 + (LABOR_INCOME[group] ?: 0.0) * change(RegulationItem.LABOR)

    fun lines(): List<RegulationLine> =
        RegulationItem.entries.map {
            RegulationLine(it, initialLevels.getValue(it), targets.getValue(it), levels.getValue(it), it != RegulationItem.CIVIL_LIBERTIES)
        }

    companion object {
        const val PHASE_IN_MONTHS = 6.0
        private const val ENVIRONMENT_GROWTH_COST = 0.005
        private const val LABOR_UNEMPLOYMENT = 0.015
        private const val IMMIGRATION_POPULATION = 0.004
        private val ENVIRONMENT_BY_INCOME = mapOf("HIC" to 0.6, "UMC" to 0.4, "LMC" to 0.3, "LIC" to 0.2)
        private val LABOR_BY_INCOME = mapOf("HIC" to 0.55, "UMC" to 0.45, "LMC" to 0.4, "LIC" to 0.3)
        private val IMMIGRATION_BY_INCOME = mapOf("HIC" to 0.5, "UMC" to 0.3, "LMC" to 0.25, "LIC" to 0.25)
        private val LABOR_INCOME =
            mapOf(
                PopulationGroup.WORKERS to 0.05,
                PopulationGroup.OFFICE to 0.04,
                PopulationGroup.FARMERS to 0.02,
                PopulationGroup.PUBLIC_SECTOR to 0.02,
                PopulationGroup.SELF_EMPLOYED to -0.02,
                PopulationGroup.CAPITALISTS to -0.04,
            )
    }
}
