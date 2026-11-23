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

/** 같은 직업집단별 대표 인구로 계산한 소속률과 여론 가중치. */
internal data class ElectorateSummary(
    val groupShares: Map<PopulationGroup, Double>,
    val memberShares: Map<Identity, Double>,
    val overlaps: Map<Identity, Map<Identity, Double>>,
    val approvalWeights: OpinionWeights,
    val politicalWeights: OpinionWeights,
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
    /** 직업 하나의 표본을 한 번 집계하고 월별 인구 변화에는 대표 인구만 바꾼다. */
    private class SampleGroup {
        var count = 0
        var jobCoefficient = 0.0
        val identityCoefficients = DoubleArray(Identity.entries.size)

        // 대각선은 각 성향의 소속자 수, 그 밖의 칸은 두 성향의 공동 소속자 수다.
        val joint = Array(Identity.entries.size) { IntArray(Identity.entries.size) }

        fun record(
            strengths: Array<DoubleArray>,
            voter: Int,
        ) {
            count++
            val denominator = 1.0 + Identity.entries.sumOf { it.weight * strengths[it.ordinal][voter] }
            jobCoefficient += 1.0 / denominator
            for (identity in Identity.entries) {
                val strength = strengths[identity.ordinal][voter]
                if (strength == 0.0) continue
                identityCoefficients[identity.ordinal] += identity.weight * strength / denominator
                for (other in Identity.entries) {
                    if (strengths[other.ordinal][voter] > 0.0) joint[identity.ordinal][other.ordinal]++
                }
            }
        }
    }

    private val samples = PopulationGroup.entries.associateWith { SampleGroup() }

    init {
        val size = SAMPLE_SIZE
        val groupOf = IntArray(size)
        val ageOf = IntArray(size)
        val strengths = Array(Identity.entries.size) { DoubleArray(size) }
        val groups = PopulationGroup.entries
        val cumulative = groups.runningFold(0.0) { sum, group -> sum + groupShares.getValue(group) }.drop(1)
        val total = cumulative.last()
        val retirees = groupShares.getValue(PopulationGroup.RETIREES) / total
        val youngShare = (0.25 + 0.6 * profile.childShare - 0.3 * profile.elderlyShare).coerceIn(0.15, 0.5)
        val youngAmongWorking = (youngShare / (1.0 - retirees)).coerceIn(0.0, 0.8)
        val tradition = DoubleArray(size)
        val left = DoubleArray(size)
        for (i in 0 until size) {
            // 소수 직업도 적어도 한 명을 표집한다. 실제 인구 비중은 집계할 때 적용한다.
            val group =
                if (i < groups.size) {
                    i
                } else {
                    val draw = random.nextDouble() * total
                    cumulative.indexOfFirst { draw < it }.let { if (it < 0) groups.lastIndex else it }
                }
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
        for (i in 0 until size) samples.getValue(groups[groupOf[i]]).record(strengths, i)
    }

    fun summarize(
        population: Map<PopulationGroup, Double>,
        civilParticipation: Double,
    ): ElectorateSummary {
        // 표본 한 명이 대표하는 실제 성인 비중. 표본에 우연히 많거나 적게 뽑힌 효과도 함께 보정한다.
        val perVoter = population.mapValues { (group, share) -> share / samples.getValue(group).count }

        fun jointShare(
            first: Identity,
            second: Identity,
        ): Double = samples.entries.sumOf { (group, sample) -> perVoter.getValue(group) * sample.joint[first.ordinal][second.ordinal] }

        val members = Identity.entries.associateWith { jointShare(it, it) }
        val overlaps =
            Identity.entries.associateWith { first ->
                val memberShare = members.getValue(first)
                Identity.entries.filter { it != first }.associateWith { second ->
                    if (memberShare > 0.0) jointShare(first, second) / memberShare else 0.0
                }
            }

        fun weights(influence: (PopulationGroup) -> Double): OpinionWeights {
            val total = population.entries.sumOf { (group, share) -> share * influence(group) }
            val weights = perVoter.mapValues { (group, share) -> share * influence(group) / total }
            return OpinionWeights(
                groups = samples.mapValues { (group, sample) -> weights.getValue(group) * sample.jobCoefficient },
                identities =
                    Identity.entries.associateWith { identity ->
                        samples.entries.sumOf { (group, sample) -> weights.getValue(group) * sample.identityCoefficients[identity.ordinal] }
                    },
            )
        }

        return ElectorateSummary(
            groupShares = population,
            memberShares = members,
            overlaps = overlaps,
            approvalWeights = weights { it.influence },
            politicalWeights = weights { civilParticipation + (1.0 - civilParticipation) * it.influence },
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
