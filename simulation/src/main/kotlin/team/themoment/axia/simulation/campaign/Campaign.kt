package team.themoment.axia.simulation.campaign

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.InitialEconomy
import team.themoment.axia.simulation.events.EventCategory
import team.themoment.axia.simulation.politics.LocalDateSerializer
import team.themoment.axia.simulation.time.GameSpeed
import team.themoment.axia.simulation.world.PlayableCountries
import team.themoment.axia.simulation.world.World
import java.time.LocalDate

enum class CampaignScenario(
    val label: String,
    val description: String,
) {
    HISTORICAL("실제 세계", "2024년 1월 1일의 국가별 경제와 제도에서 시작합니다."),
    DEBT_ACCORD("가상역사 · 채무 경감 협정", "2023년 말 다자 협정으로 부채가 GDP의 100%를 넘은 국가의 국채 원금이 25% 줄어든 세계입니다."),
    ;

    fun startingEconomy(country: InitialEconomy): InitialEconomy =
        if (this == DEBT_ACCORD && country.debt > 1.0) country.copy(debt = country.debt * 0.75) else country

    internal fun economy(data: EconomyData): EconomyData = if (this == HISTORICAL) data else data.mapInitial(::startingEconomy)
}

@Serializable
data class CampaignStart(
    val countryId: String,
    val seed: Long,
    val scenario: CampaignScenario,
) {
    fun createSimulation(
        world: World,
        data: EconomyData,
    ): Simulation {
        require(PlayableCountries.loadDefault(world, data).any { it.id == countryId }) { "플레이할 수 없는 국가입니다: $countryId" }
        return Simulation(world, scenario.economy(data), countryId, seed)
    }
}

@Serializable
data class DatedAction(
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val action: CampaignAction,
)

/** 게임은 날짜별 명령을 원래 순서대로 재생한다. 배속과 일시정지 설정은 재생이 끝난 뒤 적용한다. */
@Serializable
data class CampaignSave(
    val version: Int,
    val start: CampaignStart,
    @Serializable(with = LocalDateSerializer::class)
    val date: LocalDate,
    val actions: List<DatedAction>,
    val speed: Int,
    val pauseCategories: Set<EventCategory>,
) {
    init {
        require(version == CURRENT_VERSION) { "지원하지 않는 캠페인 저장 버전입니다: $version" }
        require(date >= Simulation.START_DATE && speed in GameSpeed.MIN..GameSpeed.MAX) { "저장 날짜 또는 배속이 잘못되었습니다." }
        var previousDate = Simulation.START_DATE
        for (entry in actions) {
            require(entry.date in previousDate..date) { "저장된 명령의 날짜 순서가 잘못되었습니다." }
            previousDate = entry.date
        }
    }

    suspend fun restore(
        world: World,
        data: EconomyData,
    ): Simulation {
        val simulation = start.createSimulation(world, data)
        val context = currentCoroutineContext()

        fun advanceTo(target: LocalDate) {
            while (simulation.date < target) {
                context.ensureActive()
                simulation.advanceDay()
            }
        }
        for (entry in actions) {
            context.ensureActive()
            advanceTo(entry.date)
            entry.action.applyTo(simulation)
        }
        advanceTo(date)
        return simulation
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun new(start: CampaignStart): CampaignSave =
            CampaignSave(
                CURRENT_VERSION,
                start,
                Simulation.START_DATE,
                emptyList(),
                GameSpeed.NORMAL.multiplier,
                EventCategory.entries.filter { it.pauseByDefault }.toSet(),
            )
    }
}
