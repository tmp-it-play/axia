package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.YearMonth

enum class MonetaryFramework(
    val label: String,
) {
    INFLATION_TARGETING("물가안정목표제"),
    MONETARY_UNION("통화동맹"),
    CURRENCY_PEG("고정환율"),
    GOVERNMENT_DIRECTED("정부 지시"),
}

enum class MonetaryStance(
    val label: String,
) {
    TIGHT("긴축"),
    NEUTRAL("중립"),
    LOOSE("완화"),
}

/** 한 나라의 통화정책 현황. 비율은 모두 소수다. */
data class MonetaryPolicy(
    /** 데이터에 이름이 없는 나라는 null. 화면에서 "<국가> 중앙은행"으로 표기한다. */
    val bankName: String?,
    val framework: MonetaryFramework,
    val independent: Boolean,
    val credibility: Double,
    val policyRate: Double,
    /** 금리 하한에 닿아 자산을 매입하고 있는지. */
    val quantitativeEasing: Boolean,
    /** 플레이어가 지시해 적용 중인 금리. 없으면 정부 지시 준칙으로 자동 결정한다. */
    val directedRate: Double?,
    /** 지시 금리가 유지되는 남은 개월 수. */
    val directiveMonths: Int,
    val inflationTarget: Double,
    /** 기준금리 − 기대 물가. */
    val realRate: Double,
    /** 경기에 중립적인 실질금리. 시작 시점 값에서 장기 수준으로 수렴한다. */
    val neutralRealRate: Double,
    val estimated: Boolean,
    /** 통화동맹이면 금리를 정하는 동맹 중앙은행 이름. */
    val unionBank: String?,
    /** 통화동맹이면 공동 통화, 고정환율이면 환율을 묶은 통화. */
    val anchorCurrency: String?,
    /** 플레이어가 기준금리를 직접 정할 수 있는지. */
    val directable: Boolean,
    val governor: GovernorSnapshot,
) {
    val stance: MonetaryStance
        get() =
            when {
                realRate - neutralRealRate > STANCE_BAND -> MonetaryStance.TIGHT
                realRate - neutralRealRate < -STANCE_BAND -> MonetaryStance.LOOSE
                else -> MonetaryStance.NEUTRAL
            }

    private companion object {
        const val STANCE_BAND = 0.005
    }
}

@Serializable
internal data class CentralBankEntry(
    val name: String,
    val framework: MonetaryFramework,
    val independent: Boolean,
    val credibility: Double,
    val policyRate: Double,
    val inflationTarget: Double,
    val estimated: Boolean = false,
    val union: String? = null,
    val sourceNote: String,
) {
    init {
        require(credibility in 0.0..1.0 && sourceNote.isNotBlank())
        require((framework == MonetaryFramework.MONETARY_UNION) == (union != null))
    }
}

@Serializable
internal data class MonetaryUnionEntry(
    val id: String,
    val name: String,
    val currency: String,
    val policyRate: Double,
    val inflationTarget: Double,
    val credibility: Double,
    val members: List<String>,
    val sourceNote: String,
)

@Serializable
internal data class PegEntry(
    /** 기준 통화 발행국 코드 또는 통화동맹 코드. */
    val anchor: String,
    val currency: String,
    val members: List<String>,
    val sourceNote: String,
)

/** 중앙은행 초기값. 데이터에 없는 나라는 물가안정목표제로 보고 시작 금리와 신뢰도를 추정한다. */
class CentralBankData private constructor(
    internal val banks: Map<String, CentralBankEntry>,
    internal val unions: List<MonetaryUnionEntry>,
    internal val pegs: List<PegEntry>,
) {
    companion object {
        private const val DEFAULT_DATA = "/world/central_banks.json"

        fun loadDefault(economy: EconomyData): CentralBankData {
            val text =
                requireNotNull(CentralBankData::class.java.getResourceAsStream(DEFAULT_DATA)) { "Missing $DEFAULT_DATA" }
                    .use { it.readBytes().decodeToString() }
            val file = Json.decodeFromString<CentralBankFile>(text)
            val known = economy.countries.keys
            val unionIds = file.unions.map { it.id }.toSet()
            require(file.banks.keys.all(known::contains)) { "Unknown central bank country" }
            file.unions.forEach { union -> require(union.members.all(known::contains)) { "Unknown member in ${union.id}" } }
            file.pegs.forEach { peg ->
                require(peg.anchor in known || peg.anchor in unionIds) { "Unknown peg anchor ${peg.anchor}" }
                require(peg.members.all(known::contains)) { "Unknown member in peg to ${peg.anchor}" }
            }
            val unionMembers = file.unions.flatMap { it.members }
            val pegMembers = file.pegs.flatMap { it.members }
            require(unionMembers.toSet().size == unionMembers.size && pegMembers.toSet().size == pegMembers.size)
            require(unionMembers.none(pegMembers::contains)) { "A country cannot be both union member and pegged" }
            file.banks.forEach { (id, bank) ->
                when (bank.framework) {
                    MonetaryFramework.MONETARY_UNION -> {
                        require(
                            file.unions
                                .single { it.id == bank.union }
                                .members
                                .contains(id),
                        )
                    }

                    MonetaryFramework.CURRENCY_PEG -> {
                        require(id in pegMembers) { "$id is a peg without anchor" }
                    }

                    else -> {
                        require(id !in unionMembers && id !in pegMembers) { "$id framework conflicts with union or peg lists" }
                    }
                }
            }
            return CentralBankData(file.banks, file.unions, file.pegs)
        }
    }

    @Serializable
    private data class CentralBankFile(
        val banks: Map<String, CentralBankEntry>,
        val unions: List<MonetaryUnionEntry>,
        val pegs: List<PegEntry>,
    )
}

/** 국가의 거시 모델에 넘기는 이번 달 통화 여건. */
internal data class MonetaryConditions(
    val policyRate: Double,
    /** 양적완화 효과를 더한 금리. 경기와 국채 금리에는 이것이 전달된다. */
    val effectiveRate: Double,
    val initialPolicyRate: Double,
    val neutralRealRate: Double,
    val inflationTarget: Double,
    val credibility: Double,
    /** 시작 때보다 신뢰도를 잃어 국채에 붙는 추가 위험 프리미엄. */
    val credibilityPremium: Double,
    /** 이번 달 기대 물가에 한 번 더하는 충격(예: 독립성 박탈). */
    val expectationShock: Double,
    /** 통화동맹·고정환율 국가가 따라가는 기준 통화권의 지난달 물가. 변동환율이면 null. */
    val anchorInflation: Double?,
    /**
     * 기대 물가가 묶이는 수준. 변동환율 국가는 자기 물가 목표, 통화동맹 회원국은 동맹의 물가 목표,
     * 고정환율 국가는 기준 통화권의 지난달 물가다.
     */
    val expectationAnchor: Double,
    /** 고정환율 국가가 묶인 기준 통화권("USA", "EUR"). 변동환율·통화동맹이면 null. */
    val pegAnchor: String? = null,
)

/**
 * 금리를 정하는 주체. 테일러 준칙은 편차형이다. 목표 금리 = 기준 수준 + φ × (물가 − 기준 물가) + 0.5 × 산출 갭.
 * 기준 수준과 기준 물가는 시작값에서 [NORMALIZATION_MONTHS]에 걸쳐 장기 중립 금리와 물가 목표로 수렴한다.
 * 그래서 첫날 금리는 실제 값과 같고 시작 시점 통화 기조는 중립이며, 장기적으로는 금리가 정상화된다.
 */
internal class RateSetter(
    initialRate: Double,
    initialInflation: Double,
    val inflationTarget: Double,
) {
    var rate = initialRate
        private set

    /** 하한이 없다면 정했을 금리. 하한 아래로 내려가면 그 차이를 자산 매입(양적완화)으로 메운다. */
    private var shadowRate = initialRate
    private var baseLevel = initialRate
    private var referenceInflation = initialInflation

    val neutralRealRate: Double get() = baseLevel - referenceInflation

    val quantitativeEasing: Boolean get() = shadowRate < rate

    /** 경기와 국채 금리에 전달되는 금리. 양적완화는 그림자 금리와의 차이 중 [QE_EFFECTIVENESS]만큼 효과를 낸다. */
    val effectiveRate: Double get() = rate + QE_EFFECTIVENESS * (shadowRate - rate)

    /** 정부가 금리를 정한다. 자산 매입은 하지 않는다. */
    fun set(value: Double) {
        rate = value.coerceIn(MIN_POLICY_RATE, MAX_POLICY_RATE)
        shadowRate = rate
    }

    /** 통화동맹·고정환율: 기준 통화의 금리와 자산 매입을 [spread]만큼 차이를 두고 따른다. */
    fun follow(
        anchor: RateSetter,
        spread: Double,
    ) {
        rate = (anchor.rate + spread).coerceIn(MIN_POLICY_RATE, MAX_POLICY_RATE)
        shadowRate = (anchor.shadowRate + spread).coerceIn(MIN_SHADOW_RATE, rate)
    }

    fun normalize() {
        baseLevel += (LONG_RUN_REAL_RATE + inflationTarget - baseLevel) / NORMALIZATION_MONTHS
        referenceInflation += (inflationTarget - referenceInflation) / NORMALIZATION_MONTHS
    }

    fun followRule(
        inflation: Double,
        outputGap: Double,
        responsiveness: Double,
        outputResponse: Double = OUTPUT_RESPONSE,
        adjustment: Double = 0.0,
    ) {
        val target = baseLevel + responsiveness * (inflation - referenceInflation) + outputResponse * outputGap + adjustment
        shadowRate = (shadowRate + SMOOTHING * (target - shadowRate)).coerceIn(MIN_SHADOW_RATE, MAX_POLICY_RATE)
        rate = shadowRate.coerceAtLeast(MIN_POLICY_RATE)
    }

    companion object {
        const val LONG_RUN_REAL_RATE = 0.01
        const val NORMALIZATION_MONTHS = 120.0
        const val OUTPUT_RESPONSE = 0.5
        const val SMOOTHING = 0.3
        const val MIN_POLICY_RATE = -0.0075
        const val MAX_POLICY_RATE = 1.5
        const val MIN_SHADOW_RATE = -0.05
        const val QE_EFFECTIVENESS = 0.5
    }
}

/** 한 나라의 중앙은행. 통화동맹·고정환율이면 금리는 밖에서 정해지고, 자기 기준 수준은 경기 판단(중립 실질금리)에만 쓴다. */
internal class CentralBank(
    val countryId: String,
    val leadership: BankLeadership,
    val name: String?,
    framework: MonetaryFramework,
    independent: Boolean,
    private val baseCredibility: Double,
    val setter: RateSetter,
    val estimated: Boolean,
    val unionId: String?,
    pegAnchor: String?,
    val pegSpread: Double,
) {
    var pegAnchor = pegAnchor
        private set
    var framework = framework
        private set
    var independent = independent
        private set
    val initialRate = setter.rate
    var credibility = baseCredibility
        private set
    private var credibilityGoal = baseCredibility

    /** 플레이어가 정한 금리. 다음 달부터 [DIRECTIVE_MONTHS] 동안 적용한다. */
    var directedRate: Double? = null
        private set
    var directiveMonths = 0
        private set
    private var pendingShock = 0.0

    val adjustableIndependence: Boolean
        get() = framework == MonetaryFramework.INFLATION_TARGETING || framework == MonetaryFramework.GOVERNMENT_DIRECTED

    fun appoint(choice: GovernorMandate) {
        if (independent && leadership.earlyReplacement) credibility = (credibility - 0.08).coerceAtLeast(0.0)
        leadership.appoint(choice)
    }

    fun pressure(direction: BankPressure) {
        leadership.request(direction)
        credibility = (credibility - if (independent) 0.035 else 0.015).coerceAtLeast(0.0)
    }

    fun decideRate(
        inflation: Double,
        outputGap: Double,
        response: Double,
    ) {
        val stance = leadership.mandate
        val adjustment = (leadership.pressure?.direction ?: 0) * if (independent) 0.004 else 0.012
        setter.followRule(
            inflation,
            outputGap,
            response + stance.inflationResponse,
            RateSetter.OUTPUT_RESPONSE + stance.outputResponse,
            adjustment,
        )
    }

    fun direct(rate: Double) {
        if (framework == MonetaryFramework.GOVERNMENT_DIRECTED && rate.isFinite()) {
            directedRate = rate.coerceIn(RateSetter.MIN_POLICY_RATE, RateSetter.MAX_POLICY_RATE)
            directiveMonths = DIRECTIVE_MONTHS
        }
    }

    /** 정부가 금리 결정권을 가져간다. 시장 신뢰가 무너지고 기대 물가가 뛴다. 정부가 금리를 지시하기 전까지는 정부 지시 준칙으로 정한다. */
    fun revokeIndependence() {
        if (framework != MonetaryFramework.INFLATION_TARGETING) return
        framework = MonetaryFramework.GOVERNMENT_DIRECTED
        independent = false
        credibility = minOf(credibility, REVOKED_CREDIBILITY)
        credibilityGoal = credibility
        directedRate = null
        directiveMonths = 0
        pendingShock += REVOCATION_EXPECTATION_SHOCK
    }

    /** 독립성을 주거나 되돌린다. 신뢰도는 [RECOVERY_MONTHS]에 걸쳐 원래 수준(최소 [INDEPENDENT_CREDIBILITY])으로 오른다. */
    fun grantIndependence() {
        if (framework != MonetaryFramework.GOVERNMENT_DIRECTED) return
        framework = MonetaryFramework.INFLATION_TARGETING
        independent = true
        directedRate = null
        directiveMonths = 0
        credibilityGoal = maxOf(baseCredibility, INDEPENDENT_CREDIBILITY)
    }

    fun abandonPeg() {
        check(framework == MonetaryFramework.CURRENCY_PEG)
        framework = if (independent) MonetaryFramework.INFLATION_TARGETING else MonetaryFramework.GOVERNMENT_DIRECTED
        pegAnchor = null
        credibility *= 0.75
        pendingShock += 0.01
    }

    fun recover() {
        credibility += (credibilityGoal - credibility) / RECOVERY_MONTHS
    }

    /**
     * 플레이어가 지시한 금리가 유지 중이면 그 금리를 쓰고, 기간이 끝났거나 지시가 없으면 정부 지시 준칙으로 정한다.
     * 지시를 무기한 고정하면 명목금리 고정의 불안정(디플레이션·인플레이션 악순환)이 생기므로 유지 기간을 둔다.
     */
    fun applyDirectedRate(
        inflation: Double,
        outputGap: Double,
        response: Double,
    ) {
        val rate = directedRate
        if (rate != null && directiveMonths > 0) {
            setter.set(rate)
            directiveMonths--
        } else {
            directedRate = null
            decideRate(inflation, outputGap, response)
        }
    }

    /** 이번 달 기대 물가 충격을 꺼내 비운다. */
    fun takeShock(): Double = pendingShock.also { pendingShock = 0.0 }

    val credibilityPremium: Double get() = (baseCredibility - credibility).coerceAtLeast(0.0) * CREDIBILITY_PREMIUM

    companion object {
        const val REVOKED_CREDIBILITY = 0.4

        /** 정부 지시 금리가 유지되는 기간. 지나면 정부 지시 준칙으로 돌아간다. */
        const val DIRECTIVE_MONTHS = 12
        const val INDEPENDENT_CREDIBILITY = 0.7
        const val REVOCATION_EXPECTATION_SHOCK = 0.01
        const val RECOVERY_MONTHS = 60.0

        /** 신뢰도 1이 줄 때 국채에 붙는 위험 프리미엄. 0.9 → 0.4로 떨어지면 +1%p. */
        const val CREDIBILITY_PREMIUM = 0.02
    }
}

/**
 * 모든 나라의 금리를 정한다. 매월 국가 경제가 갱신되기 전에 지난달 물가와 경기로 결정한다.
 * - 물가안정목표제(독립): 테일러 준칙(물가 반응 1.5)
 * - 정부 지시: AI 국가는 물가 반응이 약한 준칙(1.0), 플레이어 국가는 플레이어가 정한 금리
 * - 통화동맹: 동맹 중앙은행이 회원국의 GDP 가중 평균 물가와 갭으로 하나의 금리를 정한다
 * - 고정환율: 기준 통화 금리 + 시작 시점 금리 차
 */
internal class MonetarySystem(
    data: CentralBankData,
    initial: Collection<InitialEconomy>,
    private val playerCountryId: String?,
    start: YearMonth,
    seed: Long,
) {
    private val unions = data.unions.associateBy { it.id }
    private val unionSetters =
        data.unions.associate { union ->
            val members = initial.filter { it.id in union.members }
            val weight = members.sumOf { it.gdp }
            val inflation = members.sumOf { it.inflation * it.gdp } / weight
            union.id to RateSetter(union.policyRate, inflation, union.inflationTarget)
        }
    private val unionGovernors =
        data.unions.associate {
            it.id to
                BankLeadership(it.members.first(), start, seed xor it.id.hashCode().toLong())
        }
    private val unionOf = data.unions.flatMap { union -> union.members.map { it to union.id } }.toMap()
    private val pegOf = data.pegs.flatMap { peg -> peg.members.map { it to peg.anchor } }.toMap()
    private val pegCurrency = data.pegs.flatMap { peg -> peg.members.map { it to peg.currency } }.toMap()

    /** 기준 통화권의 지난달 물가. 통화동맹은 회원국 GDP 가중 평균이다. */
    private val anchorInflation = mutableMapOf<String, Double>()

    val banks: Map<String, CentralBank> =
        initial.associate { economy ->
            val entry = data.banks[economy.id]
            val unionId = unionOf[economy.id]
            val anchor = pegOf[economy.id]
            val union = unionId?.let(unions::getValue)
            val target =
                entry?.inflationTarget ?: union?.inflationTarget ?: anchor?.let { anchorTarget(it, data) }
                    ?: estimatedTarget(economy.inflation)
            val rate =
                entry?.policyRate ?: union?.policyRate ?: anchor?.let { anchorRate(it, data) } ?: estimatedRate(economy.inflation)
            val framework =
                when {
                    unionId != null -> MonetaryFramework.MONETARY_UNION
                    anchor != null -> MonetaryFramework.CURRENCY_PEG
                    else -> entry?.framework ?: MonetaryFramework.INFLATION_TARGETING
                }
            economy.id to
                CentralBank(
                    countryId = economy.id,
                    leadership = unionId?.let(unionGovernors::getValue) ?: BankLeadership(economy.id, start, seed),
                    name = entry?.name,
                    framework = framework,
                    independent = entry?.independent ?: true,
                    baseCredibility = entry?.credibility ?: union?.credibility ?: estimatedCredibility(economy.society.incomeGroup),
                    setter = RateSetter(rate, economy.inflation, target),
                    estimated = entry?.estimated ?: (union == null),
                    unionId = unionId,
                    pegAnchor = anchor,
                    pegSpread = if (anchor == null) 0.0 else rate - anchorRate(anchor, data),
                )
        }

    init {
        require(playerCountryId == null || playerCountryId in banks)
        unions.values.forEach { union ->
            val members = initial.filter { it.id in union.members }
            anchorInflation[union.id] = members.sumOf { it.inflation * it.gdp } / members.sumOf { it.gdp }
        }
        pegOf.values.filter { it in banks }.forEach { id -> anchorInflation[id] = initial.single { it.id == id }.inflation }
    }

    /** 지난달 물가와 산출 갭으로 이번 달 금리를 정한다. */
    fun decide(
        countries: Map<String, CountryEconomy>,
        now: YearMonth,
    ) {
        unionSetters.forEach { (id, setter) ->
            val members = unions.getValue(id).members.map(countries::getValue)
            val weight = members.sumOf { it.dollarGdp }
            val inflation = members.sumOf { it.inflation * it.dollarGdp } / weight
            anchorInflation[id] = inflation
            setter.normalize()
            val gap = members.sumOf { it.outputGap * it.dollarGdp } / weight
            val governor = unionGovernors.getValue(id)
            governor.advance(now, inflation - setter.inflationTarget, gap)
            setter.followRule(
                inflation,
                gap,
                TARGETING_RESPONSE + governor.mandate.inflationResponse,
                RateSetter.OUTPUT_RESPONSE + governor.mandate.outputResponse,
            )
        }
        anchorInflation.keys.filter { it in banks }.forEach { id -> anchorInflation[id] = countries.getValue(id).inflation }
        banks.values.forEach { bank ->
            bank.setter.normalize()
            bank.recover()
            val economy = countries.getValue(bank.countryId)
            if (bank.unionId == null) {
                bank.leadership.advance(now, economy.inflation - bank.setter.inflationTarget, economy.outputGap)
                if (bank.countryId != playerCountryId && bank.adjustableIndependence && bank.leadership.pressure == null &&
                    now.monthValue == 1
                ) {
                    when {
                        economy.inflation > bank.setter.inflationTarget + 0.08 -> bank.pressure(BankPressure.TIGHTEN)
                        economy.outputGap < -0.06 && economy.inflation < bank.setter.inflationTarget -> bank.pressure(BankPressure.EASE)
                    }
                }
            }
            when (bank.framework) {
                MonetaryFramework.INFLATION_TARGETING -> {
                    bank.decideRate(economy.inflation, economy.outputGap, TARGETING_RESPONSE)
                }

                MonetaryFramework.GOVERNMENT_DIRECTED -> {
                    if (bank.countryId == playerCountryId) {
                        bank.applyDirectedRate(economy.inflation, economy.outputGap, DIRECTED_RESPONSE)
                    } else {
                        bank.decideRate(economy.inflation, economy.outputGap, DIRECTED_RESPONSE)
                    }
                }

                MonetaryFramework.MONETARY_UNION, MonetaryFramework.CURRENCY_PEG -> {
                    // 기준 통화의 이번 달 금리가 정해진 뒤 아래에서 따라간다.
                }
            }
            if (bank.unionId == null) bank.leadership.finishRateDecision()
        }
        banks.values.forEach { bank ->
            when (bank.framework) {
                MonetaryFramework.MONETARY_UNION -> bank.setter.follow(unionSetters.getValue(requireNotNull(bank.unionId)), 0.0)
                MonetaryFramework.CURRENCY_PEG -> bank.setter.follow(anchorSetter(requireNotNull(bank.pegAnchor)), bank.pegSpread)
                else -> Unit
            }
        }
    }

    fun conditions(countryId: String): MonetaryConditions {
        val bank = banks.getValue(countryId)
        return MonetaryConditions(
            policyRate = bank.setter.rate,
            effectiveRate = bank.setter.effectiveRate,
            initialPolicyRate = bank.initialRate,
            neutralRealRate = bank.setter.neutralRealRate,
            inflationTarget = bank.setter.inflationTarget,
            credibility = bank.credibility,
            credibilityPremium = bank.credibilityPremium,
            expectationShock = bank.takeShock(),
            anchorInflation = (bank.unionId ?: bank.pegAnchor)?.let(anchorInflation::getValue),
            expectationAnchor = bank.pegAnchor?.let(anchorInflation::getValue) ?: bank.setter.inflationTarget,
            pegAnchor = bank.pegAnchor,
        )
    }

    fun policy(
        countryId: String,
        expectedInflation: Double,
    ): MonetaryPolicy {
        val bank = banks.getValue(countryId)
        val setter = bank.setter
        return MonetaryPolicy(
            bankName = bank.name,
            governor = bank.leadership.snapshot(),
            framework = bank.framework,
            independent = bank.independent,
            credibility = bank.credibility,
            policyRate = setter.rate,
            quantitativeEasing = setter.quantitativeEasing,
            directedRate = bank.directedRate,
            directiveMonths = bank.directiveMonths,
            inflationTarget = setter.inflationTarget,
            realRate = setter.rate - expectedInflation,
            neutralRealRate = setter.neutralRealRate,
            estimated = bank.estimated,
            unionBank = bank.unionId?.let { unions.getValue(it).name },
            anchorCurrency = bank.unionId?.let { unions.getValue(it).currency } ?: bank.pegAnchor?.let { pegCurrency[countryId] },
            directable = bank.countryId == playerCountryId && bank.framework == MonetaryFramework.GOVERNMENT_DIRECTED,
        )
    }

    private fun anchorSetter(anchor: String): RateSetter = unionSetters[anchor] ?: banks.getValue(anchor).setter

    private fun anchorRate(
        anchor: String,
        data: CentralBankData,
    ): Double = unions[anchor]?.policyRate ?: data.banks[anchor]?.policyRate ?: error("Peg anchor $anchor has no rate")

    private fun anchorTarget(
        anchor: String,
        data: CentralBankData,
    ): Double = unions[anchor]?.inflationTarget ?: data.banks[anchor]?.inflationTarget ?: error("Peg anchor $anchor has no target")

    private companion object {
        const val TARGETING_RESPONSE = 1.5
        const val DIRECTED_RESPONSE = 1.0

        /** 데이터가 없는 나라의 시작 금리: 물가 + 1%p. */
        fun estimatedRate(inflation: Double) = (inflation + 0.01).coerceIn(0.0025, RateSetter.MAX_POLICY_RATE)

        /** 데이터가 없는 나라의 물가 목표: 물가가 6% 이하면 2%, 높으면 지금 물가의 30%(3~8%). */
        fun estimatedTarget(inflation: Double) = if (inflation <= 0.06) 0.02 else (inflation * 0.3).coerceIn(0.03, 0.08)

        /** 데이터가 없는 나라의 신뢰도(게임 가정): 소득 수준이 높을수록 높다. */
        fun estimatedCredibility(incomeGroup: String) =
            when (incomeGroup) {
                "HIC" -> 0.85
                "UMC" -> 0.7
                "LMC" -> 0.6
                else -> 0.5
            }
    }
}
