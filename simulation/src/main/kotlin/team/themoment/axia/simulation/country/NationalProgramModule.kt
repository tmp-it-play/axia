package team.themoment.axia.simulation.country

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import java.util.Locale

/** 시작 정책과 의결된 정책을 같은 월별 경제 조건에서 각각 집행한다. 기준 예산에 이미 포함된 사업비는 다시 부과하지 않는다. */
internal class NationalProgramModule private constructor(
    override val countryId: String,
    private val definition: ProgramDefinition,
    factory: () -> ProgramOperation,
) : CountryModule {
    private val actual = factory()
    private val baseline = factory()
    private var target = definition.initial
    private val item =
        CountryPolicyItem(
            definition.key,
            definition.label,
            definition.low,
            definition.high,
            definition.description,
            definition.identityReactions,
            definition.groupReactions,
        )
    override val policies = listOf(item)
    override var executionSpendingChange = 0.0
        private set

    private var latest: ProgramMonth? = null
    private var previousDeliveryGap = 0.0

    override fun policyValue(item: CountryPolicyItem): Double {
        require(item == this.item)
        return target
    }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == this.item && target in 0.0..1.0)
        this.target = target
    }

    override fun estimatedSpendingPerUnit(item: CountryPolicyItem): Double {
        require(item == this.item)
        return definition.costShare * actual.costFactor
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val current = actual.advance(target, context)
        val reference = baseline.advance(definition.initial, context)
        latest = current
        val delivery = current.delivery - reference.delivery
        val activity = current.activity - reference.activity
        executionSpendingChange = (current.cost - reference.cost) * definition.costShare
        val priceImpulse = (delivery - previousDeliveryGap) * definition.priceResponse
        previousDeliveryGap = delivery
        val sectors =
            (definition.deliverySectors.keys + definition.activitySectors.keys).associateWith { sector ->
                delivery * (definition.deliverySectors[sector] ?: 0.0) + activity * (definition.activitySectors[sector] ?: 0.0)
            }
        return CountryEffects(
            sectorShocks = sectors,
            identityMood = definition.deliveryMood.mapValues { delivery * it.value },
            inflationShock = priceImpulse,
        )
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            definition.deliveryMetric.indicator(latest?.delivery),
            definition.activityMetric.indicator(latest?.activity),
        )

    companion object {
        private val definitions: Map<String, ProgramDefinition> by lazy {
            val file = requireNotNull(NationalProgramModule::class.java.getResourceAsStream("/world/national_programs.json"))
            val catalog = file.bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<Map<String, ProgramDefinition>>(it.readText()) }
            require(catalog.keys == programOperations.keys)
            require(
                catalog.values
                    .map { it.key }
                    .distinct()
                    .size == catalog.size,
            )
            require(
                catalog.all { (country, program) ->
                    program.key.startsWith("${country.lowercase(Locale.ROOT)}.") && program.initial in 0.0..1.0 && program.costShare >= 0.0
                },
            )
            catalog
        }
        val countryIds: Set<String> get() = definitions.keys

        fun create(id: String) = NationalProgramModule(id, definitions.getValue(id), programOperations.getValue(id))
    }
}

@Serializable
private data class ProgramDefinition(
    val key: String,
    val label: String,
    val low: String,
    val high: String,
    val description: String,
    val initial: Double,
    /** 사업별 표준 비용 단위의 연율 GDP 비중. 실제 예산은 집행된 단위 수로 정한다. */
    val costShare: Double,
    val identityReactions: Map<Identity, Double>,
    val groupReactions: Map<PopulationGroup, Double>,
    val deliveryMetric: ProgramMetric,
    val activityMetric: ProgramMetric,
    val deliverySectors: Map<Sector, Double>,
    val activitySectors: Map<Sector, Double>,
    val deliveryMood: Map<Identity, Double>,
    val priceResponse: Double = 0.0,
)

@Serializable
private data class ProgramMetric(
    val label: String,
    val description: String,
) {
    fun indicator(value: Double?): CountryIndicator =
        CountryIndicator(label, value?.let { String.format(Locale.ROOT, "%.1f", it * 100.0) } ?: "첫 집행 전", description)
}

/** cost는 해당 사업의 표준 비용 단위다. 나머지 두 값은 사업마다 정의한 100점 지수(내부 단위 1). */
internal data class ProgramMonth(
    val cost: Double,
    val delivery: Double,
    val activity: Double,
)

internal interface ProgramOperation {
    /** 현재 조건에서 정책을 한 단위 확대할 때의 예상 연간 비용 계수. 결산은 실제 집행량을 따른다. */
    val costFactor: Double get() = 1.0

    fun advance(
        level: Double,
        context: CountryMonthContext,
    ): ProgramMonth
}

/** 인가·착수된 월별 물량은 정책을 철회한 뒤에도 만기가 되면 인도된다. */
internal class ProgramCohorts(
    months: Int,
) {
    private val batches = ArrayDeque(List(months) { 0.0 })

    fun advance(new: Double): Double = batches.removeFirst().also { batches.addLast(new) }
}

internal val programOperations: Map<String, () -> ProgramOperation> =
    mapOf(
        "POL" to ::FamilyAllowance,
        "ITA" to ::RecoveryMilestones,
        "FIN" to ::HousingFirst,
        "EST" to ::ElectronicResidency,
        "LVA" to ::ApartmentRenovation,
        "LTU" to ::TerminalBookings,
        "TUR" to ::EarthquakeHousing,
        "SAU" to ::TourismGuarantees,
        "EGY" to ::BreadSubsidy,
        "ZAF" to ::GenerationMaintenance,
        "IDN" to ::SmelterPermits,
        "VNM" to ::RiceConversion,
        "THA" to ::PrimaryCare,
        "RWA" to ::Girinka,
        "PAK" to ::Kafaalat,
    )
