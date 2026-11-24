package team.themoment.axia.simulation.politics

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import kotlin.random.Random

data class PrimaryStanding(
    val party: String,
    val firstId: Int,
    val secondId: Int,
    val firstDelegates: Int,
    val secondDelegates: Int,
    val lastArea: String?,
    val nextDate: LocalDate?,
)

/** 두 후보의 주별 모의 경선. 민주당 기본 공약 대의원과 공화당 소집 공고 대의원을 배분한다. */
internal class UsPrimaries(
    season: Int,
) {
    private data class Contest(
        val date: LocalDate,
        val code: String,
        val delegates: Int,
    )

    private val calendar =
        listOf("민주당", "공화당").associateWith { party ->
            ElectoralAreas.default.primaries
                .mapNotNull { row ->
                    val dem = party == "민주당"
                    val mmdd = if (dem) row.demDate else row.repDate
                    val delegates = if (dem) row.dem else row.rep
                    if (mmdd == 0 || delegates == 0) return@mapNotNull null
                    val base = LocalDate.of(2024, mmdd / 100, mmdd % 100)
                    val ordinal = (base.dayOfMonth - 1) / 7 + 1
                    var date = LocalDate.of(season, base.month, 1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, base.dayOfWeek))
                    if (date.month != base.month) date = date.minusWeeks(1)
                    Contest(date, row.code, delegates)
                }.sortedWith(compareBy<Contest> { it.date }.thenBy { it.code })
        }
    private val processed = mutableMapOf<String, Int>()

    fun advance(
        slate: ExecutiveCampaign,
        date: LocalDate,
        incumbent: Int,
        approval: Double,
        random: Random,
    ): ExecutiveCampaign {
        var nominations = slate.nominations
        val standing = slate.primaryStandings.associateBy { it.party }.toMutableMap()
        for ((party, contests) in calendar) {
            val entry = nominations.find { it.nominee.party == party && it.rival != null } ?: continue
            val rival = requireNotNull(entry.rival)
            val previous = standing[party]
            var index = processed[party] ?: 0
            if (previous != null && (index == contests.size || contests[index].date > date)) continue
            var first = previous?.firstDelegates ?: 0
            var second = previous?.secondDelegates ?: 0
            var last = previous?.lastArea
            while (index < contests.size && contests[index].date <= date) {
                val contest = contests[index++]
                val local = DistrictElectorate.localAffinity(contest.code, date.year, entry.nominee.id.toString())
                val advantage = if (entry.nominee.id == incumbent) (approval - 35) / 130 else 0.0
                val share = (0.5 + advantage + 0.32 * (local - 0.5) + random.nextDouble(-0.10, 0.10)).coerceIn(0.0, 1.0)
                val allocated =
                    when {
                        party == "공화당" && contest.code in setOf("SC", "WI") -> {
                            val districtCount =
                                ElectoralAreas.default.states
                                    .first { it.code == contest.code }
                                    .houseDistricts.size
                            val statewide = contest.delegates - 3 * districtCount
                            val won =
                                (1..districtCount).count { district ->
                                    val localShare =
                                        share +
                                            0.30 *
                                            (
                                                DistrictElectorate.localAffinity(
                                                    contest.code,
                                                    district,
                                                    entry.nominee.id.toString(),
                                                ) - 0.5
                                            )
                                    localShare >= 0.5
                                }
                            won * 3 + if (share >= 0.5) statewide else 0
                        }

                        party == "공화당" && (contest.code in setOf("FL", "AZ") || (contest.code == "CA" && share != 0.5)) -> {
                            if (share >=
                                0.5
                            ) {
                                contest.delegates
                            } else {
                                0
                            }
                        }

                        party == "민주당" && share < 0.15 -> {
                            0
                        }

                        party == "민주당" && share > 0.85 -> {
                            contest.delegates
                        }

                        else -> {
                            (share * contest.delegates).roundToInt()
                        }
                    }
                first += allocated
                second += contest.delegates - allocated
                last = contest.code
            }
            processed[party] = index
            standing[party] = PrimaryStanding(party, entry.nominee.id, rival.id, first, second, last, contests.getOrNull(index)?.date)
            if (index == contests.size) {
                val fraction = first.toDouble() / (first + second)
                val result =
                    if (first >=
                        second
                    ) {
                        entry.copy(primaryShare = fraction)
                    } else {
                        entry.copy(
                            nominee = rival,
                            rival = entry.nominee,
                            primaryShare =
                                1 - fraction,
                        )
                    }
                nominations = nominations.map { if (it == entry) result else it }
            }
        }
        return if (standing.values.toList() == slate.primaryStandings && nominations == slate.nominations) {
            slate
        } else {
            slate.copy(nominations = nominations, primaryStandings = standing.values.toList())
        }
    }
}
