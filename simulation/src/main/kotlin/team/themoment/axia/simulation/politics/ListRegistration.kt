package team.themoment.axia.simulation.politics

import java.time.LocalDate

data class RegisteredList(
    val name: String,
    val members: List<String>,
    /** 지역구 또는 비례 순번별 추천 정당. */
    val candidateOrder: List<String> = emptyList(),
    val joint: Boolean = members.size > 1,
)

data class ListRegistration(
    val election: LocalDate,
    val filed: LocalDate,
    val lists: List<RegisteredList>,
)

data class ListFilingOptions(
    val deadline: LocalDate,
    val lead: String,
    val choice: String?,
    val partners: List<String>,
)

/** 매 총선에 명부를 한 번 확정한다. 투표 전 전망에서는 같은 공천 규칙을 쓰되 접수 상태를 바꾸지 않는다. */
internal class ListFiling private constructor(
    private val system: MixedSystem,
    private val daysBefore: Long,
) {
    private var registration: ListRegistration? = null
    private var choice: Pair<String, String>? = null

    fun deadline(poll: LocalDate?): LocalDate? = poll?.minusDays(daysBefore)

    fun filed(poll: LocalDate?): ListRegistration? = registration?.takeIf { it.election == poll }

    fun options(
        poll: LocalDate?,
        groups: List<ParliamentaryGroup>,
    ): ListFilingOptions? {
        val deadline = deadline(poll) ?: return null
        if (filed(poll) != null) return null
        val lead = groups.firstOrNull { it.role == PartyRole.LEAD } ?: return null
        val partners =
            if (system == MixedSystem.KOR) {
                listOf("위성정당")
            } else {
                groups.filter { it.name != lead.name && JointListNominations.eligible(it) }.map { it.name }
            }
        return ListFilingOptions(deadline, lead.name, choice?.takeIf { it.first == lead.name }?.second, partners)
    }

    fun choose(
        options: ListFilingOptions,
        partner: String?,
    ) {
        if (partner == null || partner.isEmpty() || partner in options.partners) choice = partner?.let { options.lead to it }
    }

    fun forecast(
        poll: LocalDate?,
        groups: List<ParliamentaryGroup>,
        closing: Boolean = false,
    ): ListRegistration? {
        if (poll == null) return null
        filed(poll)?.let { return it }
        val leadChoice = choice?.takeIf { (party, _) -> groups.any { it.name == party && it.role == PartyRole.LEAD } }?.second
        val lists =
            if (system == MixedSystem.KOR) {
                KoreanListNominations.register(poll, groups, leadChoice)
            } else {
                JointListNominations.register(system, poll.takeIf { closing }, groups, leadChoice)
            }
        return ListRegistration(poll, requireNotNull(deadline(poll)), lists)
    }

    fun advance(
        today: LocalDate,
        poll: LocalDate?,
        groups: () -> List<ParliamentaryGroup>,
    ): ListRegistration? {
        if (poll == null || filed(poll) != null || today < poll.minusDays(daysBefore)) return null
        return requireNotNull(forecast(poll, groups(), closing = true)).copy(filed = today).also { registration = it }
    }

    fun finish() {
        registration = null
        choice = null
    }

    fun rename(change: PartyRename) {
        choice = choice?.let { change.label(it.first) to change.label(it.second) }
        registration =
            registration?.let { filing ->
                filing.copy(
                    lists =
                        filing.lists.map {
                            it.copy(
                                name = change.label(it.name),
                                members = it.members.map(change::label),
                                candidateOrder = it.candidateOrder.map(change::label),
                            )
                        },
                )
            }
    }

    companion object {
        fun forSystem(system: MixedSystem?): ListFiling? =
            when (system) {
                MixedSystem.KOR -> ListFiling(system, 19)
                MixedSystem.ITA -> ListFiling(system, 42)
                MixedSystem.LTU -> ListFiling(system, 65)
                else -> null
            }
    }
}
