package team.themoment.axia.simulation.country

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.society.Identity
import java.util.Locale

/** 주택 공급 정책의 효과를 2024년 1월 정책 기조를 유지한 경로와 비교한다. */
internal class HousingModule private constructor(
    override val countryId: String,
    private val program: HousingProgram,
) : CountryModule {
    private val item =
        CountryPolicyItem(
            key = program.key,
            label = program.label,
            lowLabel = program.lowLabel,
            highLabel = program.highLabel,
            description = program.description,
            identityReactions = program.identityReactions,
            spendingPerUnit = program.spendingPerUnit,
        )
    override val policies: List<CountryPolicyItem> = listOf(item)
    private var policy = program.initial

    // 기준 경로에서 벗어난 월 착공·준공 물량의 비율. 이미 착공한 물량은 정책을 바꿔도 공기를 마친다.
    private var startsGap = 0.0
    private var completionsGap = 0.0
    private val underConstruction = ArrayDeque(List(program.buildMonths) { 0.0 })
    override var housingSupply: Double = 0.0
        private set

    override fun policyValue(item: CountryPolicyItem): Double {
        require(item == this.item)
        return policy
    }

    override fun enact(
        item: CountryPolicyItem,
        target: Double,
    ) {
        require(item == this.item)
        policy = target.coerceIn(0.0, 1.0)
    }

    override fun advanceMonth(context: CountryMonthContext): CountryEffects {
        val targetStarts = (policy - program.initial) * program.startsPerUnit
        startsGap += (targetStarts - startsGap) / program.startAdjustmentMonths
        completionsGap = underConstruction.removeFirst()
        underConstruction.addLast(startsGap)
        // 준공이 누적되면서 전국 주택 재고가 새 균형에 접근한다. 정책을 되돌려도 기존 공급 효과는 서서히 사라진다.
        housingSupply += (completionsGap * program.priceResponse - housingSupply) / STOCK_ADJUSTMENT_MONTHS
        return CountryEffects(sectorShocks = mapOf(Sector.CONSTRUCTION to startsGap * program.constructionResponse))
    }

    override fun indicators(): List<CountryIndicator> =
        listOf(
            CountryIndicator(
                label = "${program.housingLabel} 착공 지수",
                value = index(startsGap),
                description = "시작 정책을 유지한 경로의 월 착공량이 100이다. 정책을 바꾸면 사업 준비를 거쳐 착공량과 건설 경기가 변한다.",
            ),
            CountryIndicator(
                label = "${program.housingLabel} 준공 지수",
                value = index(completionsGap),
                description = "같은 기준 경로의 월 준공량이 100이다. 착공 ${program.buildMonths}개월 뒤부터 준공 물량이 반영되고 주택가격에 서서히 영향을 준다.",
            ),
        )

    private fun index(gap: Double): String = String.format(Locale.ROOT, "%.1f", 100.0 * (1.0 + gap))

    companion object {
        private const val STOCK_ADJUSTMENT_MONTHS = 60.0
        private val programs: Map<String, HousingProgram> by lazy {
            val text =
                requireNotNull(HousingModule::class.java.getResourceAsStream("/world/housing_programs.json")) {
                    "Missing housing_programs.json"
                }.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<HousingFile>(text).programs
        }
        val countryIds: Set<String> get() = programs.keys

        fun create(countryId: String): HousingModule = HousingModule(countryId, programs.getValue(countryId))
    }
}

@Serializable
private data class HousingFile(
    val modelNote: String,
    val programs: Map<String, HousingProgram>,
)

@Serializable
private data class HousingProgram(
    val key: String,
    val label: String,
    val lowLabel: String,
    val highLabel: String,
    val description: String,
    val housingLabel: String,
    val initial: Double,
    val spendingPerUnit: Double,
    val startsPerUnit: Double,
    val startAdjustmentMonths: Int,
    val buildMonths: Int,
    val constructionResponse: Double,
    val priceResponse: Double,
    val identityReactions: Map<Identity, Double>,
    val sourceNote: String,
)
