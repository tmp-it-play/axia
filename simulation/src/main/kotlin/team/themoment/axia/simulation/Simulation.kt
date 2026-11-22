package team.themoment.axia.simulation

import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.economy.WorldEconomy
import team.themoment.axia.simulation.nation.Nation
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind
import team.themoment.axia.simulation.world.World
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * 세계 상태와 하루 단위 진행을 담는다. 경제와 플레이어 국가는 매월 1일에 갱신한다.
 *
 * [SimulationRunner]의 시뮬레이션 코루틴에서만 변경한다.
 */
class Simulation(
    val world: World,
    data: EconomyData,
    val playerCountryId: String,
    seed: Long = DEFAULT_SEED,
    startDate: LocalDate = START_DATE,
) {
    private val random = Random(seed)
    private val nation = Nation(data.country(playerCountryId), YearMonth.from(startDate))
    private val economy = WorldEconomy(data, mapOf(playerCountryId to nation.budget), YearMonth.from(startDate))

    var date: LocalDate = startDate
        private set

    init {
        nation.start(economy.country(playerCountryId))
    }

    /** 하루를 진행하고, 경제가 갱신됐으면 true를 돌려준다. */
    fun advanceDay(): Boolean {
        date = date.plusDays(1)
        if (date.dayOfMonth != 1) return false
        economy.advanceMonth(random)
        nation.advanceMonth(economy.country(playerCountryId))
        return true
    }

    fun setTaxRate(
        kind: TaxKind,
        rate: Double,
    ) = nation.budget.setTaxTarget(kind, rate)

    fun setSpending(
        kind: SpendingKind,
        level: Double,
    ) = nation.budget.setSpendingTarget(kind, level)

    fun economySnapshot(): EconomySnapshot = economy.snapshot(date)

    fun nationSnapshot(): NationSnapshot = nation.snapshot(economy.country(playerCountryId))

    companion object {
        val START_DATE: LocalDate = LocalDate.of(2024, 1, 1)
        const val DEFAULT_SEED = 20240101L
    }
}
