package team.themoment.axia.simulation

import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.economy.WorldEconomy
import team.themoment.axia.simulation.nation.BudgetItem
import team.themoment.axia.simulation.nation.Nation
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.politics.PoliticalProfile
import team.themoment.axia.simulation.politics.Politics
import team.themoment.axia.simulation.politics.PoliticsSnapshot
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

    private val politics = Politics(PoliticalProfile.forCountry(playerCountryId), nation.budget)

    var date: LocalDate = startDate
        private set

    init {
        val player = economy.country(playerCountryId)
        nation.start(player)
        politics.updateConstituencies(nation.groupSnapshots(player))
    }

    data class DayUpdate(
        val newMonth: Boolean,
        val budgetPassed: Boolean,
    )

    fun advanceDay(): DayUpdate {
        date = date.plusDays(1)
        val newMonth = date.dayOfMonth == 1
        if (newMonth) {
            economy.advanceMonth(random)
            val player = economy.country(playerCountryId)
            nation.advanceMonth(player)
            politics.updateConstituencies(nation.groupSnapshots(player))
        }
        return DayUpdate(newMonth, politics.advanceDay(date))
    }

    fun editBudgetDraft(
        item: BudgetItem,
        target: Double,
    ) = politics.edit(item, target)

    fun negotiateBudgetWith(party: String?) = politics.negotiate(party)

    fun clearBudgetDraft() = politics.clearDraft()

    fun reviseStalledBudget() = politics.reviseStalledBill()

    fun introduceBudget() = politics.introduce(date)

    fun politicsSnapshot(): PoliticsSnapshot = politics.snapshot()

    fun economySnapshot(): EconomySnapshot = economy.snapshot(date)

    fun nationSnapshot(): NationSnapshot = nation.snapshot(economy.country(playerCountryId))

    companion object {
        val START_DATE: LocalDate = LocalDate.of(2024, 1, 1)
        const val DEFAULT_SEED = 20240101L
    }
}
