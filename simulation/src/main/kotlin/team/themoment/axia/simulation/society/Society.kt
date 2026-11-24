package team.themoment.axia.simulation.society

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.math.nextGaussian
import team.themoment.axia.simulation.nation.PopulationGroup
import kotlin.math.exp
import kotlin.math.pow
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
    val activity: AgeActivity,
    val lifeExpectancy: Double,
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

/** 시작 시점과 비교한 생활 여건. 가치관 재평가 때 각 사안의 중요도를 바꾼다. */
internal data class VoterConditions(
    val unemploymentChange: Double = 0.0,
    val crimeChange: Double = 0.0,
    val pollutionChange: Double = 0.0,
    val educationChange: Double = 0.0,
    val libertiesChange: Double = 0.0,
) {
    fun salience(identity: Identity): Double =
        (
            1.0 +
                when (identity) {
                    Identity.ENVIRONMENTALISTS -> 0.7 * pollutionChange + 0.3 * educationChange
                    Identity.PATRIOTS -> 0.5 * crimeChange
                    Identity.RELIGIOUS -> 0.0
                    Identity.LIBERTARIANS -> -0.5 * libertiesChange + 0.3 * educationChange
                    Identity.EGALITARIANS -> 2.0 * unemploymentChange
                    Identity.FREE_MARKET -> -1.5 * unemploymentChange
                    Identity.UNIONISTS -> 2.5 * unemploymentChange
                    Identity.YOUTH, Identity.PARENTS -> 0.0
                }
        ).coerceIn(0.5, 1.8)
}

/** 개인별 잠재 성향과 가입 문턱을 보존하고, 연령·직업과 생활 여건이 바뀌면 소속을 재평가한다. */
internal class Electorate(
    groupShares: Map<PopulationGroup, Double>,
    private val profile: SocietyProfile,
    private val random: Random,
) {
    private class Voter(
        var group: PopulationGroup,
        var ageMonths: Int,
        val lifespan: Int,
        random: Random,
    ) {
        val tradition = random.nextGaussian()
        val left = random.nextGaussian()
        val threshold = DoubleArray(Identity.entries.size) { random.nextDouble() }
        val attachment = DoubleArray(Identity.entries.size) { 0.5 + 0.5 * random.nextDouble() }
        val strengths = DoubleArray(Identity.entries.size)
        val age: AgeBand get() =
            when {
                ageMonths < 35 * 12 -> AgeBand.YOUNG
                ageMonths < SENIOR_AGE -> AgeBand.MIDDLE
                else -> AgeBand.OLD
            }
    }

    private class SampleGroup {
        var count = 0
        var jobCoefficient = 0.0
        val identityCoefficients = DoubleArray(Identity.entries.size)
        val joint = Array(Identity.entries.size) { IntArray(Identity.entries.size) }

        fun countVoter(
            voter: Voter,
            delta: Int,
        ) {
            count += delta
            val strengths = voter.strengths
            val denominator = 1.0 + Identity.entries.sumOf { it.weight * strengths[it.ordinal] }
            jobCoefficient += delta / denominator
            for (identity in Identity.entries) {
                val strength = strengths[identity.ordinal]
                if (strength == 0.0) continue
                identityCoefficients[identity.ordinal] += delta * identity.weight * strength / denominator
                for (other in Identity.entries) {
                    if (strengths[other.ordinal] > 0.0) joint[identity.ordinal][other.ordinal] += delta
                }
            }
        }
    }

    private val occupations = PopulationGroup.entries.filter { it != PopulationGroup.RETIREES }
    private val samples = PopulationGroup.entries.associateWith { SampleGroup() }
    private val voters: Array<Voter>
    private val scale: Map<Identity, Double>
    private val attitudes = Identity.entries.filterNot { it.demographic }

    init {
        val targets = sampleCounts(groupShares, SAMPLE_SIZE)
        val assignments = targets.flatMap { (group, count) -> List(count) { group } }.shuffled(random)
        val youngShare = (0.25 + 0.6 * profile.childShare - 0.3 * profile.elderlyShare).coerceIn(0.15, 0.5)
        val retired = groupShares.getValue(PopulationGroup.RETIREES)
        val olderAdults = profile.elderlyShare / (1.0 - profile.childShare)
        val workingAge = 1.0 - profile.childShare - profile.elderlyShare
        val earlyRetired = workingAge * (profile.activity.earlyPension?.nonEmployedOfWorkingAge ?: 0.0)
        val earlyAmongRetired = earlyRetired / ((1.0 - profile.childShare) * retired)
        val olderAmongOthers = olderAdults * profile.activity.olderParticipation / (1.0 - retired)
        val youngAmongOthers = (youngShare / (1.0 - retired)).coerceAtMost(1.0 - olderAmongOthers)
        voters =
            Array(SAMPLE_SIZE) { i ->
                val group = assignments[i]
                val lifespan = lifespan()
                val draw = random.nextDouble()
                val age =
                    when {
                        group == PopulationGroup.RETIREES && draw < earlyAmongRetired -> initialMiddleAge(earlyRetired = true)
                        group == PopulationGroup.RETIREES -> random.nextInt(SENIOR_AGE, lifespan)
                        draw < olderAmongOthers -> random.nextInt(SENIOR_AGE, lifespan)
                        draw < olderAmongOthers + youngAmongOthers -> random.nextInt(ENTRY_AGE, 35 * 12)
                        else -> initialMiddleAge(earlyRetired = false)
                    }
                Voter(group, age, lifespan, random)
            }
        scale =
            attitudes.associateWith { identity ->
                baseRate(identity, profile) * voters.size / voters.sumOf { propensity(it, identity) }
            }
        val salience = DoubleArray(Identity.entries.size) { 1.0 }
        voters.forEach { voter ->
            assess(voter, profile.childShare, salience)
            samples.getValue(voter.group).countVoter(voter, 1)
        }
    }

    /** 국가별 경제활동·연금 자료에 맞춰 은퇴하며, 사망한 표본 자리는 새로 진입하는 18세 코호트가 채운다. */
    fun advanceMonth(
        population: Map<PopulationGroup, Double>,
        demographics: DemographySnapshot,
        conditions: VoterConditions,
    ) {
        val salience = DoubleArray(Identity.entries.size) { conditions.salience(Identity.entries[it]) }
        val counts = IntArray(PopulationGroup.entries.size)
        for (i in voters.indices) {
            var voter = voters[i]
            voter.ageMonths++
            val exits = voter.ageMonths >= voter.lifespan + ((demographics.lifeExpectancy - profile.lifeExpectancy) * 12).toInt()
            // 연 1회 생일에 은퇴 여부와 가치관을 재평가한다. 표본 진입은 해당 월에 반영한다.
            if (exits || voter.ageMonths % 12 == 0) {
                samples.getValue(voter.group).countVoter(voter, -1)
                if (exits) {
                    voter = Voter(occupations.random(random), ENTRY_AGE, lifespan(), random)
                    voters[i] = voter
                } else if (voter.group != PopulationGroup.RETIREES) {
                    val chance = profile.activity.retirementProbability(voter.ageMonths / 12)
                    if (chance > 0.0 && random.nextDouble() < chance) voter.group = PopulationGroup.RETIREES
                }
                assess(voter, demographics.childShare, salience)
                samples.getValue(voter.group).countVoter(voter, 1)
            }
            counts[voter.group.ordinal]++
        }

        // 실제 직업 비중에 맞춰 남는 집단에서 빈 집단으로 표본을 옮긴다. 같은 사람의 잠재 성향은 유지한다.
        val working = population.filterKeys { it != PopulationGroup.RETIREES }
        val targets = sampleCounts(working, voters.size - counts[PopulationGroup.RETIREES.ordinal])
        val vacancies = occupations.flatMap { group -> List((targets.getValue(group) - counts[group.ordinal]).coerceAtLeast(0)) { group } }
        if (vacancies.isEmpty()) return
        var filled = 0
        val start = random.nextInt(voters.size)
        for (offset in voters.indices) {
            val voter = voters[(start + offset) % voters.size]
            val source = voter.group
            if (source == PopulationGroup.RETIREES || counts[source.ordinal] <= targets.getValue(source)) continue
            samples.getValue(source).countVoter(voter, -1)
            counts[source.ordinal]--
            voter.group = vacancies[filled++]
            counts[voter.group.ordinal]++
            assess(voter, demographics.childShare, salience)
            samples.getValue(voter.group).countVoter(voter, 1)
            if (filled == vacancies.size) break
        }
    }

    private fun initialMiddleAge(earlyRetired: Boolean): Int {
        if (earlyRetired) {
            // 수급 시작 나이부터 일정하게 은퇴자가 유입돼 나이별 표본 수는 1, 2, …에 비례한다.
            val firstAge = checkNotNull(profile.activity.earlyPension).fromAge
            val years = SENIOR_AGE / 12 - firstAge
            val draw = random.nextInt(years * (years + 1) / 2)
            val year = (1..years).first { draw < it * (it + 1) / 2 }
            return (firstAge + year - 1) * 12 + random.nextInt(12)
        }
        var age: Int
        do {
            age = random.nextInt(35 * 12, SENIOR_AGE)
        } while (random.nextDouble() < profile.activity.earlyRetiredAt(age / 12))
        return age
    }

    private fun lifespan(): Int = ((profile.lifeExpectancy + 7.0 + 7.0 * random.nextGaussian()).coerceIn(70.0, 100.0) * 12).toInt()

    /** 소수 집단에 한 명을 보장하고 나머지 표본을 최대 잔여 방식으로 배분한다. */
    private fun sampleCounts(
        shares: Map<PopulationGroup, Double>,
        total: Int,
    ): Map<PopulationGroup, Int> {
        val sum = shares.values.sum()
        val extra = shares.mapValues { (_, share) -> share / sum * (total - shares.size) }
        val counts = extra.mapValues { 1 + it.value.toInt() }.toMutableMap()
        extra.keys.sortedByDescending { extra.getValue(it) % 1.0 }.take(total - counts.values.sum()).forEach { group ->
            counts[group] = counts.getValue(group) + 1
        }
        return counts
    }

    private fun propensity(
        voter: Voter,
        identity: Identity,
    ): Double =
        (GROUP_MULTIPLIER[identity]?.get(voter.group) ?: 1.0) * AGE_MULTIPLIER.getValue(identity)[voter.age.ordinal] *
            latentFactor(identity, voter.tradition, voter.left)

    private fun assess(
        voter: Voter,
        childShare: Double,
        salience: DoubleArray,
    ) {
        for (identity in Identity.entries) {
            val i = identity.ordinal
            val probability =
                when (identity) {
                    Identity.YOUTH -> if (voter.age == AgeBand.YOUNG) 1.0 else 0.0
                    Identity.PARENTS -> PARENT_RATE[voter.age.ordinal] * (childShare / REFERENCE_CHILD_SHARE).coerceIn(0.5, 1.6)
                    else -> (propensity(voter, identity) * scale.getValue(identity) * salience[i]).coerceAtMost(MAX_PROBABILITY)
                }
            voter.strengths[i] =
                if (voter.threshold[i] < probability) {
                    if (identity.demographic) 1.0 else voter.attachment[i]
                } else {
                    0.0
                }
        }
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
        const val ENTRY_AGE = 18 * 12
        const val SENIOR_AGE = 65 * 12
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
