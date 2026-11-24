package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.country.CountryEffects
import team.themoment.axia.simulation.country.CountryIndicator
import team.themoment.axia.simulation.country.CountryModule
import team.themoment.axia.simulation.country.CountryMonthContext
import team.themoment.axia.simulation.country.CountryPolicyLine
import team.themoment.axia.simulation.economy.Commodity
import team.themoment.axia.simulation.economy.CommodityQuote
import team.themoment.axia.simulation.economy.CountryEconomy
import team.themoment.axia.simulation.economy.DebtWorkoutSnapshot
import team.themoment.axia.simulation.economy.FiscalPolicy
import team.themoment.axia.simulation.economy.FiscalShares
import team.themoment.axia.simulation.economy.GrainProducerSupport
import team.themoment.axia.simulation.economy.InitialEconomy
import team.themoment.axia.simulation.economy.RevenueCompositionSource
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.events.EventEffects
import team.themoment.axia.simulation.history.MonthlySeries
import team.themoment.axia.simulation.politics.InitialApproval
import team.themoment.axia.simulation.politics.PoliticalProfile
import team.themoment.axia.simulation.society.CivilSociety
import team.themoment.axia.simulation.society.CivilSocietyInputs
import team.themoment.axia.simulation.society.CivilSocietySnapshot
import team.themoment.axia.simulation.society.Demography
import team.themoment.axia.simulation.society.DemographyInputs
import team.themoment.axia.simulation.society.DemographySnapshot
import team.themoment.axia.simulation.society.Electorate
import team.themoment.axia.simulation.society.GovernmentAction
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.simulation.society.IdentityBase
import team.themoment.axia.simulation.society.OpinionWeights
import team.themoment.axia.simulation.society.OrganizationSeed
import team.themoment.axia.simulation.society.OrganizationStake
import team.themoment.axia.simulation.society.SocialIndicators
import team.themoment.axia.simulation.society.SocietyProfile
import team.themoment.axia.simulation.society.VoterConditions
import team.themoment.axia.simulation.world.CountryStructure
import team.themoment.axia.simulation.world.World
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.pow
import kotlin.random.Random

data class GroupSnapshot(
    val group: PopulationGroup,
    val population: Double,
    val share: Double,
    /** 이번 달 은퇴를 제외한 직업 간 순이동 인원. */
    val netJobMoves: Double,
    /** 1인당 연간 소득(현재 달러). */
    val income: Double,
    val satisfaction: Double,
    val factors: Map<SatisfactionFactor, Double>,
)

/** 재정 현황. 비율은 모두 GDP 대비(연율)다. */
data class BudgetSnapshot(
    val taxes: List<TaxLine>,
    val revenueCompositionSource: RevenueCompositionSource,
    val unreportedRevenueTaxes: Set<TaxKind>,
    val otherRevenue: Double,
    val spending: List<SpendingLine>,
    val revenue: Double,
    val primarySpending: Double,
    val interest: Double,
    val balance: Double,
    val debtRatio: Double,
    val debtWorkout: DebtWorkoutSnapshot,
    /** 달러 기준 명목 GDP. 비율을 금액으로 바꿀 때 쓴다. */
    val gdp: Double,
    /** 조직 보조금(GDP 대비 연간). 기초지출에 포함돼 있다. */
    val organizationSubsidies: Double,
    val regionalSpending: Double,
    val reconstructionSpending: Double,
    val countrySpendingChange: Double,
    /** 실제 정산한 농가 보조금(연율 GDP 비중). 사업이 없는 경우 null. */
    val grainProducerPayment: Double?,
)

data class IdentitySnapshot(
    val identity: Identity,
    /** 성인 중 이 성향에 속한 비율. */
    val memberShare: Double,
    val initialShare: Double,
    val satisfaction: Double,
    /** 정책(세금, 지출, 규제, 제도)이 시작 때보다 바뀌어 생긴 만족도 변화. */
    val policyEffect: Double,
    /** 오염, 범죄, 실업, 경기 같은 결과 지표로 생긴 만족도 변화. */
    val outcomeEffect: Double,
    /** 정부의 공개 비판·탄압이 남긴 일시적 변화. */
    val governmentEffect: Double,
    /** 이 성향 소속자 중 다른 성향에도 속한 비율. */
    val overlaps: Map<Identity, Double>,
)

data class SocialSnapshot(
    /** 시작 = 100. */
    val pollution: Double,
    val crime: Double,
    val regulations: List<RegulationLine>,
)

data class NationHistory(
    val start: YearMonth,
    val approval: List<Double>,
    val satisfaction: Map<PopulationGroup, List<Double>>,
    val identities: Map<Identity, List<Double>>,
    val pollution: List<Double>,
    val crime: List<Double>,
)

/** 국가별 특화 모듈의 고유 정책과 지표. */
data class CountrySpecialSnapshot(
    val specialized: Boolean,
    val policies: List<CountryPolicyLine>,
    val indicators: List<CountryIndicator>,
)

data class NationSnapshot(
    val countryId: String,
    val approval: Double,
    /** 시작 지지율이 2024년 1월 여론조사 수준이 되도록 정한 기준선. */
    val baseline: Double,
    /** 시작 지지율(2024년 1월 여론조사)과 그 출처. */
    val initialApproval: InitialApproval,
    val approvalFactors: Map<SatisfactionFactor, Double>,
    /** 성향 만족도가 지지율에 더하는 몫(점). */
    val identityContributions: Map<Identity, Double>,
    /** 조직의 시위·파업·과격 행동이 지지율에서 빼는 몫(점). */
    val unrest: Double,
    val groups: List<GroupSnapshot>,
    val identities: List<IdentitySnapshot>,
    val social: SocialSnapshot,
    val budget: BudgetSnapshot,
    val society: CivilSocietySnapshot,
    val special: CountrySpecialSnapshot,
    val demography: DemographySnapshot,
    val regions: List<RegionSnapshot>,
    val history: NationHistory,
)

/** 이번 달 여론. 정치 계산이 법안 지지를 구할 때 쓴다. */
internal data class Opinion(
    val groups: Map<PopulationGroup, Double>,
    val identities: Map<Identity, Double>,
    /** 시민사회 참여도로 가중한 정치적 영향력 계수. */
    val weights: OpinionWeights,
    /** 법안에 입장을 내는 조직들. */
    val stakes: List<OrganizationStake>,
    /** 성인 중 각 성향에 속한 비율. 새 정당의 잠재 지지층을 정한다. */
    val identityShares: Map<Identity, Double>,
)

/**
 * 플레이어 국가의 인구 집단, 성향 집단, 예산, 규제, 지지율. [Regions]가 전국 경제와 여론에서 지역 현황을 추정한다.
 *
 * 직업 집단 만족도 = 기준선 + 경제 요인별 기여도. 성향 만족도 = 기준선 + 정책 반응 + 결과 지표 반응.
 * 기여도는 매달 목표값으로 천천히 움직인다(지연).
 * 유권자 한 사람의 만족도는 직업 집단과 소속 성향의 가중 평균이고, 지지율은 이를 영향력으로 가중 평균한 값이다.
 * 유권자는 나이가 들고 직업을 옮기며 성향을 재평가한다. 매달 직업별 대표 인구로 여론 가중치([OpinionWeights])를 갱신한다.
 */
class Nation internal constructor(
    initial: InitialEconomy,
    identityBase: IdentityBase,
    profile: PoliticalProfile,
    organizations: List<OrganizationSeed>,
    internal val module: CountryModule,
    private val specialized: Boolean,
    structure: CountryStructure,
    world: World,
    private val initialApproval: InitialApproval,
    startDate: LocalDate,
    seed: Long,
) {
    private val initialCountryPolicies = module.policies.associateWith(module::policyValue)

    private val startMonth = YearMonth.from(startDate)
    private val civilParticipation = profile.civilParticipation
    val countryId = initial.id
    internal val budget =
        PlayerBudget(initial) {
            module.executionSpendingChange +
                initialCountryPolicies.entries.sumOf { (item, baseline) ->
                    (module.policyValue(item) - baseline) * item.spendingPerUnit
                }
        }
    internal val regulations = Regulations(initial.society.incomeGroup, identityBase.civilLiberties)

    /** 거시 모델에 넘기는 플레이어 국가의 재정·구조 정책. */
    internal val policy: FiscalPolicy =
        PlayerPolicy(budget, regulations, module as? GrainProducerSupport, { regions.employmentEffect }) { demography.laborGrowthEffect }
    private val indicators = SocialIndicators(initial.society.industryEmployment)
    private val demography = Demography(initial, structure)

    /** 사건이 집단 만족도에 남긴 점수. 매달 [EVENT_DECAY]만큼 남는다. */
    private val eventMood = PopulationGroup.entries.associateWith { 0.0 }.toMutableMap()

    private val composition = composeGroups(initial.society, initial.gdp, initial.population)
    private val mobility = OccupationalMobility(composition.shares, GROUP_SECTORS)
    private val regions = Regions(world, initial.id, structure.urbanShare)
    private val groups =
        PopulationGroup.entries.map { group ->
            GroupState(group, composition.incomes.getValue(group), initial.growth)
        }
    private val electorate =
        Electorate(
            composition.shares,
            SocietyProfile(
                base = identityBase,
                incomeGroup = initial.society.incomeGroup,
                gini = initial.society.gini,
                defenseShare = initial.budget.defense,
                childShare = initial.society.childShare,
                elderlyShare = initial.society.elderlyShare,
                lifeExpectancy = structure.lifeExpectancy,
            ),
            Random(seed),
        )
    private var constituents = electorate.summarize(composition.shares, civilParticipation)
    private val initialIdentityShares = constituents.memberShares
    private val civilSociety =
        CivilSociety(
            seeds = organizations,
            baseShares = { kind ->
                kind.identity?.let(constituents.memberShares::getValue) ?: kind.group?.let(constituents.groupShares::getValue) ?: 0.0
            },
            legislativeConstraints = profile.legislativeConstraints,
            autocratic = profile.regime.autocratic,
            start = startDate,
            random = Random(seed xor ORGANIZATION_SEED_SALT),
        )

    /** 조직의 과격 행동이 다음 달 범죄 지수에 더하는 값. */
    private var pendingCrime = 0.0
    private val approvalWeights get() = constituents.approvalWeights
    private val identityPolicy = Identity.entries.associateWith { 0.0 }.toMutableMap()
    private val identityOutcome = Identity.entries.associateWith { 0.0 }.toMutableMap()

    private var initialRealGdpPerCapita = 0.0
    private var initialRealGdp = 0.0
    private var initialUnemployment = 0.0
    private var months = 0
    private var baseline = BASELINE
    private val approvalSeries = MonthlySeries()
    private val satisfactionSeries = PopulationGroup.entries.associateWith { MonthlySeries() }
    private val identitySeries = Identity.entries.associateWith { MonthlySeries() }
    private val pollutionSeries = MonthlySeries()
    private val crimeSeries = MonthlySeries()

    internal fun start(economy: CountryEconomy) {
        initialRealGdpPerCapita = economy.realGdpPerCapita
        initialRealGdp = economy.realGdpPerCapita * economy.populationCount
        initialUnemployment = economy.unemployment
        mobility.start(economy.sectorLines())
        groups.forEach { it.start(burdenOf(it.group)) }
        update(economy, emptyMap(), smoothing = 1.0)
        // 지지율은 기준선에 거의 선형이지만 만족도가 0~100에서 잘릴 수 있어 두 번 맞춘다.
        val target = initialApproval.approval
        baseline = target + (BASELINE - approvalAt(BASELINE))
        baseline += target - approvalAt(baseline)
        record()
    }

    /** @param institutionChanges 제도 항목의 시작 대비 변화(예: 중앙은행 독립 1 → 0이면 -1). */
    internal fun advanceMonth(
        economy: CountryEconomy,
        institutionChanges: Map<InstitutionItem, Double>,
        date: LocalDate,
    ) {
        months++
        val incomeIndex = economy.realGdpPerCapita / initialRealGdpPerCapita
        demography.advanceMonth(
            DemographyInputs(
                socialChange = budget.level(SpendingKind.SOCIAL) - budget.initialLevel(SpendingKind.SOCIAL),
                healthChange = budget.level(SpendingKind.HEALTH) - budget.initialLevel(SpendingKind.HEALTH),
                educationChange = budget.level(SpendingKind.EDUCATION) - budget.initialLevel(SpendingKind.EDUCATION),
                immigrationChange = regulations.change(RegulationItem.IMMIGRATION),
                incomeIndex = incomeIndex,
                realRate = economy.policyRate - economy.expectedInflation,
                housingSupply = module.housingSupply,
            ),
        )
        val sectors = economy.sectorLines()
        val groupShares = mobility.advanceMonth(sectors, demography.retireeShare)
        val orderInitial = budget.initialLevel(SpendingKind.ORDER)
        indicators.advanceMonth(
            realGdpIndex = economy.realGdpPerCapita * economy.populationCount / initialRealGdp,
            incomeIndex = incomeIndex,
            months = months,
            unemploymentGap = economy.unemployment - initialUnemployment,
            orderChange = if (orderInitial > 0.0) budget.level(SpendingKind.ORDER) / orderInitial - 1.0 else 0.0,
            environmentChange = regulations.change(RegulationItem.ENVIRONMENT),
            policingChange = regulations.change(RegulationItem.POLICING),
            extraCrime = pendingCrime,
        )
        pendingCrime = 0.0
        regions.advanceMonth(economy.unemployment, sectors, indicators.crime)
        val demographics = demography.snapshot(incomeIndex)
        electorate.advanceMonth(
            groupShares,
            demographics,
            VoterConditions(
                unemploymentChange = economy.unemployment - initialUnemployment,
                crimeChange = regions.nationalCrime / 100.0 - 1.0,
                pollutionChange = indicators.pollution / 100.0 - 1.0,
                educationChange = demographics.education / 100.0 - 1.0,
                libertiesChange = regulations.change(RegulationItem.CIVIL_LIBERTIES),
            ),
        )
        constituents = electorate.summarize(groupShares, civilParticipation)
        update(economy, institutionChanges, smoothing = FACTOR_SMOOTHING)
        eventMood.keys.forEach { eventMood[it] = eventMood.getValue(it) * EVENT_DECAY }
        val effects =
            civilSociety.advanceMonth(
                date,
                CivilSocietyInputs(
                    identityMood = Identity.entries.associateWith { identitySatisfaction(it) - baseline },
                    groupMood = groups.associate { it.group to it.satisfaction(baseline) - baseline },
                    approval = approval(),
                    civilLiberties = regulations.level(RegulationItem.CIVIL_LIBERTIES),
                    defenseChange = budget.level(SpendingKind.DEFENSE) - budget.initialLevel(SpendingKind.DEFENSE),
                    orderChange = if (orderInitial > 0.0) budget.level(SpendingKind.ORDER) / orderInitial - 1.0 else 0.0,
                    policingChange = regulations.change(RegulationItem.POLICING),
                ),
            )
        pendingCrime += effects.crime
        economy.addOutputGapShock(effects.outputGapShock)
        budget.organizationSubsidies = civilSociety.subsidySpending
        record()
    }

    /** 국가별 특화 모듈의 월간 갱신과 그 결과 반영. */
    internal fun advanceCountryModule(
        economy: CountryEconomy,
        date: LocalDate,
        commodities: Map<Commodity, CommodityQuote>,
        relations: (String) -> Double,
    ): CountryEffects {
        val effects =
            module.advanceMonth(
                CountryMonthContext(
                    date = date,
                    approval = approval(),
                    outputGap = economy.outputGap,
                    inflation = economy.inflation,
                    unemployment = economy.unemployment,
                    debtRatio = economy.debtRatio,
                    budgetBalance =
                        (economy.monthlyRevenue - economy.monthlyPrimarySpending - economy.monthlyInterest) * 12.0 / economy.nominalGdp,
                    debt = economy.debtRatio * economy.nominalGdp,
                    defenseChange = budget.level(SpendingKind.DEFENSE) - budget.initialLevel(SpendingKind.DEFENSE),
                    relations = relations,
                    commodities = commodities,
                ),
            )
        applyEffects(economy, effects, date)
        return effects
    }

    internal fun applyEffects(
        economy: CountryEconomy,
        effects: CountryEffects,
        date: LocalDate,
    ) {
        economy.addOutputGapShock(effects.outputGapShock)
        economy.riskPremium = effects.interestPremium
        effects.identityMood.forEach { (identity, points) -> civilSociety.shiftMood(identity, points) }
        effects.sectorShocks.forEach { (sector, shock) -> economy.addSectorShock(sector, shock) }
        economy.addInflationShock(effects.inflationShock)
        effects.news.forEach { report(date, it.source, it.text, it.major) }
    }

    /** 사건(또는 사건 선택지)의 결과를 반영한다. */
    internal fun applyEvent(
        economy: CountryEconomy,
        effects: EventEffects,
    ) {
        economy.addOutputGapShock(effects.outputGap)
        economy.addInflationShock(effects.inflation)
        economy.addSpending(effects.spending)
        economy.addPotentialGrowth(effects.potentialGrowth)
        effects.debtPlan?.let(economy.debt::negotiate)
        effects.sectorShocks.forEach { (sector, shock) -> economy.addSectorShock(sector, shock) }
        PopulationGroup.entries.forEach { group ->
            eventMood[group] = eventMood.getValue(group) + effects.mood + (effects.groupMood[group] ?: 0.0)
        }
        effects.identityMood.forEach { (identity, points) -> civilSociety.shiftMood(identity, points) }
        pendingCrime += effects.crime
        civilSociety.adjustCapital(effects.politicalCapital)
    }

    /**
     * 정권 교체 허니문. 새 정부의 지지율이 지금 값과 [NEW_GOVERNMENT_APPROVAL]의 중간으로 오르도록 기준선을 옮긴다.
     * 이후 정책과 경제가 다시 지지율을 움직인다.
     */
    internal fun renewMandate() {
        baseline += (NEW_GOVERNMENT_APPROVAL - approval()) * HONEYMOON_SHARE
    }

    /** 사건 확률에 쓰는 국내 상태. 모두 "보통 = 0"으로 맞춘 값이다. */
    internal fun eventValues(economy: CountryEconomy): Map<String, Double> {
        fun relative(kind: SpendingKind): Double {
            val initialLevel = budget.initialLevel(kind)
            return if (initialLevel > 0.0) budget.level(kind) / initialLevel - 1.0 else 0.0
        }
        return mapOf(
            "laborProtection" to regulations.level(RegulationItem.LABOR) - 0.5,
            "healthSpending" to relative(SpendingKind.HEALTH),
            "educationSpending" to relative(SpendingKind.EDUCATION),
            "civilLiberties" to regulations.level(RegulationItem.CIVIL_LIBERTIES) - 0.5,
            "policing" to regulations.level(RegulationItem.POLICING) - 0.5,
            "unemploymentGap" to economy.unemployment - initialUnemployment,
            "inflation" to (economy.inflation - 0.03).coerceAtLeast(0.0),
            "crime" to (regions.nationalCrime - 100.0) / 100.0,
            "unrest" to civilSociety.unrest / MAX_UNREST,
            "coupRisk" to civilSociety.coupRisk,
            "approval" to (approval() - 50.0) / 50.0,
            "debtRatio" to (economy.debtRatio - 0.9).coerceAtLeast(0.0),
            "policyRate" to (economy.policyRate - 0.05).coerceAtLeast(0.0),
            "outputGap" to economy.outputGap,
        )
    }

    /** 쿠데타 뒤 군사평의회가 시민 자유를 줄인다. */
    internal fun suppressLiberties(cut: Double) {
        val item = RegulationItem.CIVIL_LIBERTIES
        regulations.enact(item, (regulations.target(item) - cut).coerceAtLeast(0.0))
    }

    /** 외교·군사 행위의 정치 자본. 모자라면 false다. */
    internal fun spendCapital(cost: Double): Boolean = civilSociety.spendCapital(cost)

    /** 원조·동원 비용을 예산에 반영한다(GDP 대비 연간). */
    internal fun setExternalSpending(
        aid: Double,
        mobilization: Double,
    ) {
        budget.foreignAid = aid
        budget.mobilizationCost = mobilization
    }

    /** 정치·사건·외교 소식을 국가 소식 목록에 남긴다. */
    internal fun report(
        date: LocalDate,
        source: String,
        text: String,
        major: Boolean,
    ) = civilSociety.report(date, source, text, major)

    internal fun performAction(
        organizationId: Int,
        action: GovernmentAction,
        date: LocalDate,
    ): Boolean {
        val done = civilSociety.perform(organizationId, action, date)
        if (done) budget.organizationSubsidies = civilSociety.subsidySpending
        return done
    }

    internal fun renameOrganization(
        organizationId: Int,
        name: String,
    ) = civilSociety.rename(organizationId, name)

    /** 통과한 법안의 변경(항목, 변화량)으로 조직과의 약속 위반을 확인한다. */
    internal fun onPolicyEnacted(
        changes: List<Pair<PolicyItem, Double>>,
        date: LocalDate,
    ) = civilSociety.onPolicyEnacted(changes, date)

    internal fun opinion(): Opinion =
        Opinion(
            groups = groups.associate { it.group to it.satisfaction(baseline) },
            identities = Identity.entries.associateWith { identitySatisfaction(it) },
            weights = constituents.politicalWeights,
            stakes = civilSociety.stakes(),
            identityShares = constituents.memberShares,
        )

    internal fun snapshot(
        economy: CountryEconomy,
        date: LocalDate,
    ): NationSnapshot {
        val gdp = economy.nominalGdp
        return NationSnapshot(
            countryId = countryId,
            approval = approval(),
            baseline = baseline,
            initialApproval = initialApproval,
            approvalFactors =
                SatisfactionFactor.entries.associateWith { factor ->
                    groups.sumOf { approvalWeights.groups.getValue(it.group) * it.factor(factor) }
                },
            identityContributions =
                Identity.entries.associateWith { approvalWeights.identities.getValue(it) * (identitySatisfaction(it) - baseline) },
            unrest = civilSociety.unrest.coerceAtMost(MAX_UNREST),
            groups = groupSnapshots(economy),
            identities =
                Identity.entries.map {
                    IdentitySnapshot(
                        identity = it,
                        memberShare = constituents.memberShares.getValue(it),
                        initialShare = initialIdentityShares.getValue(it),
                        satisfaction = identitySatisfaction(it),
                        policyEffect = identityPolicy.getValue(it),
                        outcomeEffect = identityOutcome.getValue(it),
                        governmentEffect = civilSociety.identityMood.getValue(it),
                        overlaps = constituents.overlaps.getValue(it),
                    )
                },
            social = SocialSnapshot(indicators.pollution, regions.nationalCrime, regulations.lines()),
            budget =
                BudgetSnapshot(
                    taxes = budget.taxLines(),
                    revenueCompositionSource = budget.revenueCompositionSource,
                    unreportedRevenueTaxes = budget.unreportedRevenueTaxes,
                    otherRevenue = budget.otherRevenue,
                    spending = budget.spendingLines(),
                    revenue = economy.monthlyRevenue * 12.0 / gdp,
                    primarySpending = economy.monthlyPrimarySpending * 12.0 / gdp,
                    interest = economy.monthlyInterest * 12.0 / gdp,
                    balance = (economy.monthlyRevenue - economy.monthlyPrimarySpending - economy.monthlyInterest) * 12.0 / gdp,
                    debtRatio = economy.debtRatio,
                    debtWorkout = economy.debt.snapshot(gdp),
                    gdp = economy.dollarGdp,
                    organizationSubsidies = budget.organizationSubsidies,
                    countrySpendingChange = budget.countrySpendingChange,
                    regionalSpending = budget.regionalSpending,
                    reconstructionSpending = economy.reconstructionShare,
                    grainProducerPayment =
                        if (policy.grainSupport != null) {
                            economy.monthlyProducerPayment * 12.0 / (economy.realGdpPerCapita * economy.populationCount)
                        } else {
                            null
                        },
                ),
            society = civilSociety.snapshot(date),
            demography = demography.snapshot(economy.realGdpPerCapita / initialRealGdpPerCapita),
            regions =
                regions.snapshot(
                    population = economy.populationCount,
                    groupShares = constituents.groupShares,
                    unemployment = economy.unemployment,
                    sectors = economy.sectorLines(),
                    approval = approval(),
                    approvalWeights = approvalWeights.groups,
                    satisfaction = groups.associate { it.group to it.satisfaction(baseline) },
                ),
            special =
                CountrySpecialSnapshot(
                    specialized = specialized,
                    policies =
                        module.policies.map { CountryPolicyLine(it, initialCountryPolicies.getValue(it), module.policyValue(it)) },
                    indicators = module.indicators(),
                ),
            history =
                NationHistory(
                    start = startMonth.plusMonths(approvalSeries.dropped.toLong()),
                    approval = approvalSeries.toList(),
                    satisfaction = satisfactionSeries.mapValues { it.value.toList() },
                    identities = identitySeries.mapValues { it.value.toList() },
                    pollution = pollutionSeries.toList(),
                    crime = crimeSeries.toList(),
                ),
        )
    }

    internal fun updateRegionOwners(
        owners: Map<String, String>,
        shares: Map<String, TerritorialShare> = emptyMap(),
    ) {
        regions.updateOwners(owners, shares)
        budget.regionalSpending = regions.spending
    }

    internal fun setRegionalProgram(
        regionId: String,
        program: RegionalProgram,
    ) {
        regions.assign(regionId, program)
        budget.regionalSpending = regions.spending
    }

    internal fun electoralRegions(): List<ElectoralRegion> =
        regions.electoralRegions(
            constituents.groupShares,
            approval(),
            approvalWeights.groups,
            groups.associate {
                it.group to
                    it.satisfaction(baseline)
            },
        )

    internal fun groupSnapshots(economy: CountryEconomy): List<GroupSnapshot> {
        val adults = economy.populationCount * demography.adultShare
        val dollarPerReal = economy.dollarGdp / (economy.realGdpPerCapita * economy.populationCount)
        return groups.map { state ->
            val share = constituents.groupShares.getValue(state.group)
            GroupSnapshot(
                group = state.group,
                population = adults * share,
                netJobMoves = adults * (mobility.netTransfers[state.group] ?: 0.0),
                share = share,
                income = state.realIncome * dollarPerReal,
                satisfaction = state.satisfaction(baseline),
                factors = SatisfactionFactor.entries.associateWith(state::factor),
            )
        }
    }

    private fun update(
        economy: CountryEconomy,
        institutionChanges: Map<InstitutionItem, Double>,
        smoothing: Double,
    ) {
        val incomeIndex = economy.realGdpPerCapita / initialRealGdpPerCapita
        val socialChange = budget.level(SpendingKind.SOCIAL) - budget.initialLevel(SpendingKind.SOCIAL)
        updateIdentities(economy, institutionChanges, smoothing)
        groups.forEach { state ->
            val group = state.group
            val burden = burdenOf(group)
            // 집단 소득은 그 집단이 일하는 산업 부문의 성과를 따라간다(전체 경제 대비 상대 수준).
            val sectorIndex =
                GROUP_SECTORS[group]?.let { weights ->
                    weights.entries.sumOf { (sector, w) -> w * economy.sectorLevel(sector) } /
                        weights.values.sum()
                }
                    ?: 1.0
            val groupShare = constituents.groupShares.getValue(group)
            val producerIncome =
                if (group == PopulationGroup.FARMERS && groupShare > 0.0) {
                    economy.monthlyProducerPayment * 12.0 / (economy.populationCount * demography.adultShare * groupShare)
                } else {
                    0.0
                }
            state.updateIncome(
                incomeIndex * sectorIndex,
                burden,
                (1.0 + group.transferWeight * socialChange) * regulations.incomeEffect(group),
                producerIncome,
            )
            val excessInflation = economy.inflation - INFLATION_COMFORT
            val targets =
                mapOf(
                    SatisfactionFactor.PRICES to
                        group.priceSensitivity *
                        if (excessInflation > 0.0) -PRICE_WEIGHT * excessInflation else -DEFLATION_BONUS * PRICE_WEIGHT * excessInflation,
                    SatisfactionFactor.JOBS to -JOB_WEIGHT * group.jobSensitivity * (economy.unemployment - economy.naturalUnemployment),
                    SatisfactionFactor.INCOME to INCOME_WEIGHT * state.yearOverYearIncomeGrowth(),
                    SatisfactionFactor.TAXES to -TAX_WEIGHT * (burden - state.initialBurden),
                    SatisfactionFactor.SERVICES to SERVICE_WEIGHT * serviceChange(group),
                    SatisfactionFactor.ECONOMY to ECONOMY_WEIGHT * group.economySensitivity * economy.outputGap,
                    SatisfactionFactor.EVENTS to eventMood.getValue(group),
                ).mapValues { (factor, value) -> value.coerceIn(FACTOR_LIMITS.getValue(factor)) }
            state.moveFactors(targets, smoothing)
        }
    }

    /** 세목별 실효세율 × 집단의 부담 정도 × 소득 대비 노출 정도. */
    private fun burdenOf(group: PopulationGroup): Double =
        TaxKind.entries.sumOf { kind -> group.taxIncidence.getValue(kind) * budget.rate(kind) * TAX_EXPOSURE.getValue(kind) }

    /** 지출 항목별 시작 대비 변화율을 집단의 관심도로 가중 평균한다. */
    private fun serviceChange(group: PopulationGroup): Double {
        val totalWeight = group.serviceWeights.values.sum()
        return group.serviceWeights.entries.sumOf { (kind, weight) ->
            val initialLevel = budget.initialLevel(kind)
            // 고령화로 연금 수요가 늘면 같은 사회보장 지출로도 1인당 수준이 낮아진다.
            val need = if (kind == SpendingKind.SOCIAL) demography.pensionPressure else 1.0
            val change = if (initialLevel > 0.0) budget.level(kind) / (initialLevel * need) - 1.0 else 0.0
            weight * change.coerceIn(-1.0, 1.5)
        } / totalWeight
    }

    /** 성향마다 정책 반응(시작 대비 실제값 변화 × 반응 계수)과 결과 지표 반응을 목표로 두고 천천히 따라간다. */
    private fun updateIdentities(
        economy: CountryEconomy,
        institutionChanges: Map<InstitutionItem, Double>,
        smoothing: Double,
    ) {
        val changes =
            TaxKind.entries.associateWith { budget.rate(it) - budget.initialRate(it) } +
                SpendingKind.entries.associateWith { budget.level(it) - budget.initialLevel(it) } +
                RegulationItem.entries.associateWith { regulations.change(it) } +
                institutionChanges +
                module.policies.associateWith { module.policyValue(it) - initialCountryPolicies.getValue(it) }
        val unemploymentChange = economy.unemployment - initialUnemployment
        val housing = AFFORDABILITY_WEIGHT * demography.affordability(economy.realGdpPerCapita / initialRealGdpPerCapita)
        val pollution = indicators.pollution - 100.0
        val crime = regions.nationalCrime - 100.0
        Identity.entries.forEach { identity ->
            val policyTarget = changes.entries.sumOf { (item, change) -> identity.reaction(item) * change }
            val outcomeTarget =
                when (identity) {
                    Identity.ENVIRONMENTALISTS -> -0.35 * pollution
                    Identity.PATRIOTS -> -0.15 * crime
                    Identity.PARENTS -> -0.12 * crime - 0.1 * pollution + 0.5 * housing
                    Identity.YOUTH -> -YOUTH_UNEMPLOYMENT_WEIGHT * YOUTH_UNEMPLOYMENT_RATIO * unemploymentChange + housing
                    Identity.UNIONISTS -> -UNION_UNEMPLOYMENT_WEIGHT * unemploymentChange
                    Identity.EGALITARIANS -> -EGALITARIAN_UNEMPLOYMENT_WEIGHT * unemploymentChange
                    Identity.FREE_MARKET -> MARKET_GROWTH_WEIGHT * economy.outputGap
                    Identity.RELIGIOUS, Identity.LIBERTARIANS -> 0.0
                }
            identityPolicy[identity] =
                identityPolicy.getValue(identity) + (policyTarget.coerceIn(IDENTITY_LIMITS) - identityPolicy.getValue(identity)) * smoothing
            identityOutcome[identity] =
                identityOutcome.getValue(identity) +
                (outcomeTarget.coerceIn(IDENTITY_LIMITS) - identityOutcome.getValue(identity)) * smoothing
        }
    }

    private fun identitySatisfaction(
        identity: Identity,
        level: Double = baseline,
    ): Double =
        (level + identityPolicy.getValue(identity) + identityOutcome.getValue(identity) + civilSociety.identityMood.getValue(identity))
            .coerceIn(0.0, 100.0)

    /** 지지율 = 집단·성향 만족도의 선형 결합 − 사회 불안. */
    internal fun approval(): Double = (approvalAt(baseline) - civilSociety.unrest.coerceAtMost(MAX_UNREST)).coerceIn(0.0, 100.0)

    private fun approvalAt(level: Double): Double =
        groups.sumOf { approvalWeights.groups.getValue(it.group) * it.satisfaction(level) } +
            Identity.entries.sumOf { approvalWeights.identities.getValue(it) * identitySatisfaction(it, level) }

    private fun record() {
        approvalSeries.record(approval())
        groups.forEach { satisfactionSeries.getValue(it.group).record(it.satisfaction(baseline)) }
        Identity.entries.forEach { identitySeries.getValue(it).record(identitySatisfaction(it)) }
        pollutionSeries.record(indicators.pollution)
        crimeSeries.record(regions.nationalCrime)
    }

    private class GroupState(
        val group: PopulationGroup,
        private val initialIncome: Double,
        initialGrowth: Double,
    ) {
        var initialBurden = 0.0
            private set
        var realIncome = initialIncome
            private set
        private var preTaxIncome = initialIncome
        private val factors = SatisfactionFactor.entries.associateWith { 0.0 }.toMutableMap()

        /** 최근 12개월 실질소득. 시작 시점은 2023년 성장률로 거슬러 채운다. */
        private val incomeHistory = DoubleArray(12) { month -> initialIncome / (1.0 + initialGrowth).pow((12 - month) / 12.0) }
        private var historyIndex = 0

        fun start(burden: Double) {
            initialBurden = burden
        }

        fun updateIncome(
            incomeIndex: Double,
            burden: Double,
            transfer: Double,
            producerIncome: Double,
        ) {
            incomeHistory[historyIndex] = preTaxIncome
            historyIndex = (historyIndex + 1) % incomeHistory.size
            preTaxIncome = initialIncome * incomeIndex * transfer + producerIncome
            realIncome = preTaxIncome * (1.0 - burden) / (1.0 - initialBurden)
        }

        /** 세전 실질소득의 전년 대비 증가율. 세금 변화는 세금 요인에서 따로 반영한다. */
        fun yearOverYearIncomeGrowth(): Double = preTaxIncome / incomeHistory[historyIndex] - 1.0

        fun moveFactors(
            targets: Map<SatisfactionFactor, Double>,
            smoothing: Double,
        ) {
            targets.forEach { (factor, target) ->
                factors[factor] =
                    factors.getValue(factor) + (target - factors.getValue(factor)) * smoothing
            }
        }

        fun factor(factor: SatisfactionFactor): Double = factors.getValue(factor)

        fun satisfaction(baseline: Double): Double = (baseline + factors.values.sum()).coerceIn(0.0, 100.0)
    }

    private companion object {
        const val BASELINE = 50.0
        const val FACTOR_SMOOTHING = 0.3
        const val INFLATION_COMFORT = 0.02
        const val DEFLATION_BONUS = 0.2
        const val PRICE_WEIGHT = 100.0
        const val JOB_WEIGHT = 300.0
        const val INCOME_WEIGHT = 150.0
        const val TAX_WEIGHT = 120.0
        const val SERVICE_WEIGHT = 20.0
        const val ECONOMY_WEIGHT = 150.0
        const val YOUTH_UNEMPLOYMENT_RATIO = 2.0
        const val YOUTH_UNEMPLOYMENT_WEIGHT = 250.0
        const val UNION_UNEMPLOYMENT_WEIGHT = 200.0
        const val EGALITARIAN_UNEMPLOYMENT_WEIGHT = 100.0
        const val MARKET_GROWTH_WEIGHT = 100.0
        val IDENTITY_LIMITS = -40.0..30.0
        const val MAX_UNREST = 15.0
        const val EVENT_DECAY = 0.85
        const val NEW_GOVERNMENT_APPROVAL = 55.0
        const val HONEYMOON_SHARE = 0.5

        /** 집단별 소득이 기대는 산업 부문과 비중. 은퇴층은 연금 생활이라 부문과 무관하다. */
        val GROUP_SECTORS =
            mapOf(
                PopulationGroup.CAPITALISTS to
                    mapOf(
                        Sector.HEAVY_INDUSTRY to 1.0,
                        Sector.HIGH_TECH to 1.0,
                        Sector.FINANCE to 1.5,
                        Sector.ENERGY to 1.0,
                        Sector.SERVICES to 1.0,
                        Sector.CONSTRUCTION to 0.5,
                    ),
                PopulationGroup.PROFESSIONALS to
                    mapOf(Sector.HIGH_TECH to 1.0, Sector.FINANCE to 1.0, Sector.PUBLIC to 1.0, Sector.SERVICES to 1.0),
                PopulationGroup.OFFICE to mapOf(Sector.SERVICES to 2.0, Sector.FINANCE to 1.0, Sector.PUBLIC to 0.5),
                PopulationGroup.SELF_EMPLOYED to mapOf(Sector.SERVICES to 2.0, Sector.CONSTRUCTION to 1.0, Sector.AGRICULTURE to 0.5),
                PopulationGroup.WORKERS to
                    mapOf(
                        Sector.LIGHT_INDUSTRY to 1.0,
                        Sector.HEAVY_INDUSTRY to 1.0,
                        Sector.HIGH_TECH to 0.7,
                        Sector.CONSTRUCTION to 1.0,
                        Sector.ENERGY to 0.5,
                    ),
                PopulationGroup.FARMERS to mapOf(Sector.AGRICULTURE to 1.0),
                PopulationGroup.PUBLIC_SECTOR to mapOf(Sector.PUBLIC to 1.0),
            )
        const val AFFORDABILITY_WEIGHT = 25.0
        const val ORGANIZATION_SEED_SALT = 0x0A61_7A71_0000L
        val FACTOR_LIMITS =
            mapOf(
                SatisfactionFactor.PRICES to -20.0..3.0,
                SatisfactionFactor.JOBS to -30.0..10.0,
                SatisfactionFactor.INCOME to -25.0..15.0,
                SatisfactionFactor.TAXES to -25.0..15.0,
                SatisfactionFactor.SERVICES to -20.0..15.0,
                SatisfactionFactor.ECONOMY to -15.0..10.0,
                SatisfactionFactor.EVENTS to -20.0..10.0,
            )

        /** 세목별로 세율이 집단 소득에 닿는 정도. */
        val TAX_EXPOSURE =
            mapOf(
                TaxKind.INCOME to 1.0,
                TaxKind.CORPORATE to 0.25,
                TaxKind.CONSUMPTION to 0.8,
                TaxKind.SOCIAL to 0.85,
                TaxKind.TARIFF to 0.25,
            )
    }
}

/** 플레이어 예산과 규제를 거시 모델 하나의 정책으로 묶는다. */
private class PlayerPolicy(
    private val budget: PlayerBudget,
    private val regulations: Regulations,
    override val grainSupport: GrainProducerSupport?,
    private val placementEffect: () -> Double,
    private val laborGrowth: () -> Double,
) : FiscalPolicy {
    override fun advanceMonth(
        debtRatio: Double,
        outputGap: Double,
    ): FiscalShares {
        regulations.advanceMonth()
        return budget.advanceMonth(debtRatio, outputGap)
    }

    override val potentialGrowthEffect: Double
        get() = budget.potentialGrowthEffect + regulations.potentialGrowthEffect + laborGrowth()

    override val inflationImpulse: Double get() = budget.inflationImpulse

    override val naturalUnemploymentShift: Double get() = regulations.naturalUnemploymentShift - placementEffect()

    override val populationGrowthShift: Double get() = regulations.populationGrowthShift

    /**
     * 환경 규제는 중화학·광업 비중을, 노동 보호는 경공업 비중을 줄인다. 관세 인상은 국내 제조업·농업을 보호하고,
     * 교육 투자는 첨단 제조 비중을 늘린다.
     */
    override val sectorPolicy: Map<Sector, Double>
        get() {
            val environment = regulations.change(RegulationItem.ENVIRONMENT)
            val labor = regulations.change(RegulationItem.LABOR)
            val tariff = budget.rate(TaxKind.TARIFF) - budget.initialRate(TaxKind.TARIFF)
            val education = budget.level(SpendingKind.EDUCATION) - budget.initialLevel(SpendingKind.EDUCATION)
            return mapOf(
                Sector.HEAVY_INDUSTRY to (1.0 - 0.15 * environment) * (1.0 + 0.6 * tariff),
                Sector.ENERGY to 1.0 - 0.15 * environment,
                Sector.LIGHT_INDUSTRY to (1.0 - 0.12 * labor) * (1.0 + 0.6 * tariff),
                Sector.AGRICULTURE to 1.0 + 0.4 * tariff,
                Sector.HIGH_TECH to 1.0 + 3.0 * education,
            ).mapValues { it.value.coerceIn(0.5, 1.5) }
        }
}
