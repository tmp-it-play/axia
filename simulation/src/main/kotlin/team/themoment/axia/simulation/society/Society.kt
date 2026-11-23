package team.themoment.axia.simulation.society

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.PopulationGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** 성향 집단 기준률을 정하는 국가별 실측값. 비율은 소수다. */
@Serializable
data class IdentityBase(
    val unionDensity: Double,
    val unionYear: Int? = null,
    val religionImportance: Double,
    val civilLiberties: Double,
    val estimated: List<String>,
)

/** 플레이 가능 국가의 [IdentityBase]. */
class SocietyData private constructor(
    private val countries: Map<String, IdentityBase>,
) {
    fun country(id: String): IdentityBase = countries[id] ?: error("No society data for $id")

    @Serializable
    private data class SocietyFile(
        val sourceNote: String,
        val countries: Map<String, IdentityBase>,
    )

    companion object {
        private const val DEFAULT_DATA = "/world/society_identities.json"

        fun loadDefault(): SocietyData {
            val text =
                requireNotNull(SocietyData::class.java.getResourceAsStream(DEFAULT_DATA)) { "Missing $DEFAULT_DATA" }
                    .use { it.readBytes().decodeToString() }
            val file = Json.decodeFromString<SocietyFile>(text)
            file.countries.values.forEach { base ->
                require(base.unionDensity in 0.0..1.0 && base.religionImportance in 0.0..1.0 && base.civilLiberties in 0.0..1.0)
            }
            return SocietyData(file.countries)
        }
    }
}

enum class AgeBand(
    val label: String,
) {
    YOUNG("청년(18~34세)"),
    MIDDLE("중장년"),
    OLD("고령"),
}

/** 성향 기준률을 정하는 국가 구조 값. */
internal data class SocietyProfile(
    val base: IdentityBase,
    val incomeGroup: String,
    val gini: Double,
    val defenseShare: Double,
    val childShare: Double,
    val elderlyShare: Double,
)

/** 여론을 직업 집단 만족도와 성향 만족도의 선형 결합으로 쓰기 위한 계수. 합은 1이다. */
internal data class OpinionWeights(
    val groups: Map<PopulationGroup, Double>,
    val identities: Map<Identity, Double>,
)

/**
 * 시드 기반 대표 유권자 표본. 각자 직업 집단, 연령대, 성향별 소속 강도(0~1)를 가진다.
 * 성향 사이에는 권위·전통 축과 경제 좌우 축으로 약한 상관을 두어, 교차 소속도 자연스럽게 생긴다.
 * 소속은 게임 동안 고정한다.
 */
internal class Electorate(
    groupShares: Map<PopulationGroup, Double>,
    profile: SocietyProfile,
    random: Random,
) {
    val size = SAMPLE_SIZE
    private val groupOf = IntArray(size)
    private val ageOf = IntArray(size)
    private val strengths = Array(Identity.entries.size) { DoubleArray(size) }

    init {
        val groups = PopulationGroup.entries
        val cumulative = groups.runningFold(0.0) { sum, group -> sum + groupShares.getValue(group) }.drop(1)
        val total = cumulative.last()
        val retirees = groupShares.getValue(PopulationGroup.RETIREES) / total
        val youngShare = (0.25 + 0.6 * profile.childShare - 0.3 * profile.elderlyShare).coerceIn(0.15, 0.5)
        val youngAmongWorking = (youngShare / (1.0 - retirees)).coerceIn(0.0, 0.8)
        val tradition = DoubleArray(size)
        val left = DoubleArray(size)
        for (i in 0 until size) {
            val draw = random.nextDouble() * total
            val group = cumulative.indexOfFirst { draw < it }.let { if (it < 0) groups.lastIndex else it }
            groupOf[i] = group
            ageOf[i] =
                when {
                    groups[group] == PopulationGroup.RETIREES -> AgeBand.OLD.ordinal
                    random.nextDouble() < youngAmongWorking -> AgeBand.YOUNG.ordinal
                    else -> AgeBand.MIDDLE.ordinal
                }
            tradition[i] = random.nextGaussian()
            left[i] = random.nextGaussian()
        }
        Identity.entries.forEach { identity ->
            val row = strengths[identity.ordinal]
            if (identity.demographic) {
                for (i in 0 until size) {
                    val member =
                        when (identity) {
                            Identity.YOUTH -> {
                                ageOf[i] == AgeBand.YOUNG.ordinal
                            }

                            else -> {
                                random.nextDouble() <
                                    PARENT_RATE[ageOf[i]] * (profile.childShare / REFERENCE_CHILD_SHARE).coerceIn(0.5, 1.6)
                            }
                        }
                    row[i] = if (member) 1.0 else 0.0
                }
            } else {
                val propensity =
                    DoubleArray(size) { i ->
                        val group = groups[groupOf[i]]
                        val latent = latentFactor(identity, tradition[i], left[i])
                        (GROUP_MULTIPLIER[identity]?.get(group) ?: 1.0) * AGE_MULTIPLIER.getValue(identity)[ageOf[i]] * latent
                    }
                // 표본 전체의 평균 소속 확률이 국가 기준률이 되도록 맞춘다.
                val scale = baseRate(identity, profile) / propensity.average()
                for (i in 0 until size) {
                    val probability = (propensity[i] * scale).coerceAtMost(MAX_PROBABILITY)
                    row[i] = if (random.nextDouble() < probability) 0.5 + 0.5 * random.nextDouble() else 0.0
                }
            }
        }
    }

    /** 성향별 소속 비율(소속 강도와 무관하게 속한 사람의 비율). */
    fun memberShare(identity: Identity): Double = strengths[identity.ordinal].count { it > 0.0 }.toDouble() / size

    /** [first] 소속자 중 [second]에도 속한 비율. */
    fun overlap(
        first: Identity,
        second: Identity,
    ): Double {
        val a = strengths[first.ordinal]
        val b = strengths[second.ordinal]
        val members = a.count { it > 0.0 }
        if (members == 0) return 0.0
        return (0 until size).count { a[it] > 0.0 && b[it] > 0.0 }.toDouble() / members
    }

    /** 직업 집단별 유권자 가중치로 여론 계수를 구한다. */
    fun weights(groupWeight: (PopulationGroup) -> Double): OpinionWeights {
        val groups = PopulationGroup.entries
        val groupCoefficients = DoubleArray(groups.size)
        val identityCoefficients = DoubleArray(Identity.entries.size)
        var total = 0.0
        for (i in 0 until size) {
            val weight = groupWeight(groups[groupOf[i]])
            val denominator = 1.0 + Identity.entries.sumOf { it.weight * strengths[it.ordinal][i] }
            groupCoefficients[groupOf[i]] += weight / denominator
            Identity.entries.forEach { identity ->
                identityCoefficients[identity.ordinal] += weight * identity.weight * strengths[identity.ordinal][i] / denominator
            }
            total += weight
        }
        return OpinionWeights(
            groups = groups.associateWith { groupCoefficients[it.ordinal] / total },
            identities = Identity.entries.associateWith { identityCoefficients[it.ordinal] / total },
        )
    }

    private fun latentFactor(
        identity: Identity,
        tradition: Double,
        left: Double,
    ): Double {
        val (traditionLoading, leftLoading) = LATENT_LOADINGS[identity] ?: (0.0 to 0.0)
        return exp(traditionLoading * tradition + leftLoading * left)
    }

    private fun baseRate(
        identity: Identity,
        profile: SocietyProfile,
    ): Double =
        when (identity) {
            Identity.ENVIRONMENTALISTS -> ENVIRONMENT_RATE[profile.incomeGroup] ?: 0.1
            Identity.PATRIOTS -> (0.18 + 3.0 * profile.defenseShare).coerceAtMost(0.45)
            Identity.RELIGIOUS -> 0.5 * profile.base.religionImportance
            Identity.LIBERTARIANS -> 0.08 + 0.1 * profile.base.civilLiberties
            Identity.EGALITARIANS -> 0.12 + 0.6 * (profile.gini - 0.3).coerceAtLeast(0.0)
            Identity.FREE_MARKET -> if (profile.incomeGroup == "HIC") 0.15 else 0.12
            Identity.UNIONISTS -> 0.8 * profile.base.unionDensity
            Identity.YOUTH, Identity.PARENTS -> error("Demographic identities have no base rate")
        }.coerceIn(0.0, MAX_PROBABILITY)

    private companion object {
        const val SAMPLE_SIZE = 3_000
        const val MAX_PROBABILITY = 0.95
        const val REFERENCE_CHILD_SHARE = 0.18

        /** 연령대별 부모 비율(미성년 자녀가 있는 성인). */
        val PARENT_RATE = doubleArrayOf(0.3, 0.6, 0.05)

        val ENVIRONMENT_RATE = mapOf("HIC" to 0.22, "UMC" to 0.16, "LMC" to 0.12, "LIC" to 0.08)

        /** (권위·전통 축, 경제 좌파 축) 부하. 양수면 그 축이 높은 사람이 더 잘 속한다. */
        val LATENT_LOADINGS =
            mapOf(
                Identity.PATRIOTS to (0.5 to 0.0),
                Identity.RELIGIOUS to (0.5 to 0.0),
                Identity.LIBERTARIANS to (-0.4 to 0.0),
                Identity.ENVIRONMENTALISTS to (-0.15 to 0.15),
                Identity.EGALITARIANS to (0.0 to 0.5),
                Identity.FREE_MARKET to (0.0 to -0.5),
                Identity.UNIONISTS to (0.0 to 0.3),
            )

        val GROUP_MULTIPLIER =
            mapOf(
                Identity.ENVIRONMENTALISTS to
                    mapOf(
                        PopulationGroup.PROFESSIONALS to 1.4,
                        PopulationGroup.OFFICE to 1.1,
                        PopulationGroup.WORKERS to 0.8,
                        PopulationGroup.FARMERS to 0.7,
                        PopulationGroup.CAPITALISTS to 0.8,
                        PopulationGroup.RETIREES to 0.7,
                    ),
                Identity.PATRIOTS to
                    mapOf(
                        PopulationGroup.PUBLIC_SECTOR to 1.2,
                        PopulationGroup.RETIREES to 1.3,
                        PopulationGroup.FARMERS to 1.2,
                        PopulationGroup.WORKERS to 1.1,
                        PopulationGroup.PROFESSIONALS to 0.8,
                    ),
                Identity.RELIGIOUS to
                    mapOf(PopulationGroup.FARMERS to 1.3, PopulationGroup.RETIREES to 1.3, PopulationGroup.PROFESSIONALS to 0.8),
                Identity.LIBERTARIANS to
                    mapOf(
                        PopulationGroup.PROFESSIONALS to 1.5,
                        PopulationGroup.OFFICE to 1.1,
                        PopulationGroup.CAPITALISTS to 1.1,
                        PopulationGroup.FARMERS to 0.6,
                        PopulationGroup.RETIREES to 0.7,
                    ),
                Identity.EGALITARIANS to
                    mapOf(
                        PopulationGroup.WORKERS to 1.3,
                        PopulationGroup.OFFICE to 1.1,
                        PopulationGroup.PUBLIC_SECTOR to 1.2,
                        PopulationGroup.CAPITALISTS to 0.2,
                        PopulationGroup.SELF_EMPLOYED to 0.7,
                    ),
                Identity.FREE_MARKET to
                    mapOf(
                        PopulationGroup.CAPITALISTS to 4.0,
                        PopulationGroup.SELF_EMPLOYED to 1.6,
                        PopulationGroup.PROFESSIONALS to 1.3,
                        PopulationGroup.PUBLIC_SECTOR to 0.5,
                        PopulationGroup.WORKERS to 0.7,
                    ),
                Identity.UNIONISTS to
                    mapOf(
                        PopulationGroup.WORKERS to 1.6,
                        PopulationGroup.PUBLIC_SECTOR to 2.0,
                        PopulationGroup.OFFICE to 1.0,
                        PopulationGroup.PROFESSIONALS to 0.8,
                        PopulationGroup.FARMERS to 0.2,
                        PopulationGroup.SELF_EMPLOYED to 0.05,
                        PopulationGroup.CAPITALISTS to 0.0,
                        PopulationGroup.RETIREES to 0.3,
                    ),
            )

        /** 연령대(청년, 중장년, 고령)별 배율. */
        val AGE_MULTIPLIER =
            mapOf(
                Identity.ENVIRONMENTALISTS to doubleArrayOf(1.4, 1.0, 0.8),
                Identity.PATRIOTS to doubleArrayOf(0.7, 1.0, 1.3),
                Identity.RELIGIOUS to doubleArrayOf(0.7, 1.0, 1.3),
                Identity.LIBERTARIANS to doubleArrayOf(1.4, 1.0, 0.7),
                Identity.EGALITARIANS to doubleArrayOf(1.2, 1.0, 0.9),
                Identity.FREE_MARKET to doubleArrayOf(0.9, 1.0, 1.0),
                Identity.UNIONISTS to doubleArrayOf(0.8, 1.0, 1.0),
            )
    }
}

/** 오염·범죄 지수. 시작 시점 = 100. */
internal class SocialIndicators(
    industryEmployment: Double,
) {
    private val pollutionElasticity = 0.3 + industryEmployment
    var pollution = 100.0
        private set
    var crime = 100.0
        private set

    /**
     * 오염 = 실질 GDP^탄력성 × 1인당 소득^(-[INCOME_INTENSITY]) × 기술 개선 × 규제 효과. 소득이 오를수록 오염 집약도가 떨어진다(환경 쿠즈네츠 곡선).
     *
     * @param realGdpIndex 시작 대비 실질 GDP
     * @param incomeIndex 시작 대비 1인당 실질 GDP
     * @param months 시작 후 지난 달 수
     * @param unemploymentGap 시작 대비 실업률 변화
     * @param orderChange 치안·행정 지출의 시작 대비 변화율
     * @param extraCrime 이번 달 과격 행동 등으로 더해지는 범죄 지수
     */
    fun advanceMonth(
        realGdpIndex: Double,
        incomeIndex: Double,
        months: Int,
        unemploymentGap: Double,
        orderChange: Double,
        environmentChange: Double,
        policingChange: Double,
        extraCrime: Double,
    ) {
        val decoupling = (1.0 - DECOUPLING).pow(months / 12.0)
        val pollutionTarget =
            100.0 * realGdpIndex.pow(pollutionElasticity) * incomeIndex.pow(-INCOME_INTENSITY) * decoupling *
                (1.0 - ENVIRONMENT_EFFECT * environmentChange).coerceAtLeast(0.2)
        pollution += (pollutionTarget - pollution) / POLLUTION_ADJUST_MONTHS
        val crimeTarget =
            100.0 *
                (
                    1.0 + UNEMPLOYMENT_CRIME * unemploymentGap - ORDER_EFFECT * orderChange.coerceIn(-1.0, 1.5) -
                        POLICING_EFFECT * policingChange
                )
        crime += (crimeTarget.coerceIn(MIN_CRIME, MAX_CRIME) - crime) / CRIME_ADJUST_MONTHS + extraCrime
        crime = crime.coerceIn(MIN_CRIME, MAX_CRIME)
    }

    private companion object {
        /** 기술 발전에 따른 연간 오염 감소율. */
        const val DECOUPLING = 0.005
        const val INCOME_INTENSITY = 0.4
        const val ENVIRONMENT_EFFECT = 0.7
        const val POLLUTION_ADJUST_MONTHS = 24.0
        const val UNEMPLOYMENT_CRIME = 4.0
        const val ORDER_EFFECT = 0.4
        const val POLICING_EFFECT = 0.5
        const val CRIME_ADJUST_MONTHS = 12.0
        const val MIN_CRIME = 30.0
        const val MAX_CRIME = 300.0
    }
}
