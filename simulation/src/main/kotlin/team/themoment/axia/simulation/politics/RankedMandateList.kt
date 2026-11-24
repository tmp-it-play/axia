package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable

/** 선거 때 확정된 명부별 현역 소속과 아직 당선되지 않은 차순위 후보의 정당. */
@Serializable
data class RankedMandateList(
    val name: String,
    val seated: List<String>,
    val remaining: List<String>,
    val nominees: List<ParliamentaryGroup> = emptyList(),
) {
    internal fun withNominees(contenders: List<ParliamentaryGroup>): RankedMandateList {
        val parties = remaining.toSet()
        return copy(
            nominees =
                contenders.filter { it.name in parties }.map {
                    it.copy(seats = 0, role = PartyRole.OPPOSITION, nonVoting = 0, reservedSeats = 0, districtMandates = 0)
                },
        )
    }

    internal fun renamed(change: PartyRename): RankedMandateList =
        copy(
            name = change.label(name),
            seated = seated.map(change::label),
            remaining = remaining.map(change::label),
            nominees = nominees.map(change::group),
        )

    companion object {
        fun elected(
            name: String,
            rankedParties: List<String>,
            seats: Int,
        ): RankedMandateList {
            require(seats in 0..rankedParties.size)
            return RankedMandateList(name, rankedParties.take(seats), rankedParties.drop(seats))
        }
    }
}
