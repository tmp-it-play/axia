package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.people.PeopleRegistry
import java.time.YearMonth
import kotlin.random.Random

enum class GovernorMandate(
    val label: String,
    val inflationResponse: Double,
    val outputResponse: Double,
) {
    PRICE_STABILITY("물가 우선", 0.30, -0.10),
    BALANCED("균형", 0.0, 0.0),
    EMPLOYMENT("고용 우선", -0.15, 0.35),
}

enum class BankPressure(
    val label: String,
    internal val direction: Int,
) {
    EASE("금리 인하 요구", -1),
    TIGHTEN("금리 인상 요구", 1),
}

data class GovernorSnapshot(
    val name: String,
    val mandate: GovernorMandate,
    val termEnds: YearMonth,
    val replacementCost: Double,
    val pressure: BankPressure?,
    val pressureMonths: Int,
    val pressureCost: Double,
)

/** 임기·성향·정치비용은 공통 게임 규칙이다. 가상 총재의 이름은 국가별 문화권 명부에서 뽑는다. */
internal class BankLeadership(
    private val countryId: String,
    start: YearMonth,
    seed: Long,
) {
    private val random = Random(seed xor countryId.hashCode().toLong())
    private val people = PeopleRegistry(random)
    private var month = start
    private var termEnds = start.plusMonths(random.nextLong(12, TERM_MONTHS + 1))
    private var governor = people.generate(countryId, "중앙은행 총재", null, termEnds.minusMonths(TERM_MONTHS).atDay(1))
    var mandate = GovernorMandate.BALANCED
        private set
    var pressure: BankPressure? = null
        private set
    private var pressureMonths = 0

    val earlyReplacement get() = month.plusMonths(6) < termEnds
    val replacementCost get() = if (earlyReplacement) 12.0 else 3.0

    fun appoint(choice: GovernorMandate) {
        governor = people.generate(countryId, "중앙은행 총재", null, month.atDay(1))
        mandate = choice
        termEnds = month.plusMonths(TERM_MONTHS)
        pressure = null
        pressureMonths = 0
    }

    fun request(direction: BankPressure) {
        check(pressure == null)
        pressure = direction
        pressureMonths = 3
    }

    fun advance(
        now: YearMonth,
        inflationGap: Double,
        outputGap: Double,
    ) {
        month = now
        if (month >= termEnds) {
            appoint(
                when {
                    inflationGap > 0.02 -> GovernorMandate.PRICE_STABILITY
                    outputGap < -0.02 -> GovernorMandate.EMPLOYMENT
                    else -> GovernorMandate.BALANCED
                },
            )
        }
    }

    fun finishRateDecision() {
        if (pressureMonths > 0 && --pressureMonths == 0) pressure = null
    }

    fun snapshot() =
        GovernorSnapshot(
            governor.name,
            mandate,
            termEnds,
            replacementCost,
            pressure,
            pressureMonths,
            PRESSURE_COST,
        )

    companion object {
        private const val TERM_MONTHS = 72L
        const val PRESSURE_COST = 4.0
    }
}
