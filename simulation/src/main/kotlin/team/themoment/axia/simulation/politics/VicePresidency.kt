package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import java.time.LocalDate
import kotlin.random.Random

/** 대통령 하원 선출과 별개로 진행되는 미국 상원 부통령 선거. */
data class SenateViceElection(
    val ballot: LocalDate,
    val inauguration: LocalDate,
    val candidates: List<Person>,
    val elected: Person?,
)

/** 미국의 상원 부통령 선출 및 양국의 임기 중 부통령 결원. 표 이탈과 인선일은 모형값이다. */
internal class VicePresidency(
    private val country: String,
    private val leadership: LeadershipState,
    private val random: Random,
) {
    private var senateCandidates = emptyList<Person>()
    private var firstCount: LocalDate? = null
    private var elected: Person? = null
    private var accession: LocalDate? = null
    private var acting = false
    private var nextNomination: LocalDate? = null

    fun cancel() {
        senateCandidates = emptyList()
        firstCount = null
        elected = null
        accession = null
        acting = false
        nextNomination = null
    }

    fun beginSenateBallot(
        election: LocalDate,
        candidates: List<Person>,
    ) {
        require(country == "USA" && candidates.size == 2)
        senateCandidates = candidates
        firstCount = LocalDate.of(election.year + 1, 1, 6)
        accession = LocalDate.of(election.year + 1, 1, 20)
    }

    fun renameParty(
        change: PartyRename,
        update: (Person) -> Person,
    ) {
        senateCandidates = senateCandidates.map { if (it.party == change.old) update(it) else it }
        elected = elected?.let(update)
    }

    fun renamePerson(person: Person) {
        senateCandidates = senateCandidates.map { if (it.id == person.id) it.copy(name = person.name) else it }
        elected = elected?.let { if (it.id == person.id) it.copy(name = person.name) else it }
    }

    fun officersAwaitingOffice(): List<Person> = elected?.let(::listOf) ?: senateCandidates

    fun awaitingSenate(): Boolean = firstCount != null

    fun snapshot(): SenateViceElection? =
        firstCount?.let { ballot ->
            val candidates = if (elected == null) senateCandidates.filter(leadership::available) else emptyList()
            SenateViceElection(ballot, requireNotNull(accession), candidates, senateChoice())
        }

    fun senateChoice(): Person? = elected?.takeIf(leadership::available)

    fun advance(
        date: LocalDate,
        chamber: Legislature?,
        upper: UpperHouse?,
        approval: Double,
        houseVoting: Boolean,
    ): String? {
        val start = firstCount
        val inauguration = accession
        if (start != null && inauguration != null) {
            if (date < start) return fillCurrentVacancy(date, chamber, upper, approval)
            var message: String? = null
            if (elected != null && !leadership.available(requireNotNull(elected))) {
                // 상원이 부통령 당선인을 이미 선출했다면 새 임기 개시 때 제25조의 결원 보충을 적용한다.
                if (date < inauguration) return null
                leadership.seatVicePresident(null, date)
                if (houseVoting) {
                    val speaker = leadership.nominate(chamber?.groups?.maxByOrNull { it.seats }?.name, date)
                    leadership.actingPresident(speaker, date)
                }
                cancel()
                return "상원에서 선출한 부통령 당선인의 유고로 부통령직이 공석이 됐다."
            }
            if (elected == null) {
                val senate = requireNotNull(upper)
                val votes = IntArray(2)
                val round = date.toEpochDay() - start.toEpochDay()
                val standing = senateCandidates.indices.filter { leadership.available(senateCandidates[it]) }
                for (group in senate.groups) {
                    val own = senateCandidates.indexOfFirst { it.party == (group.alignWith ?: group.name) }
                    repeat(group.votingSeats) {
                        if (standing.isEmpty()) return@repeat
                        val committed = own in standing && (round == 0L || random.nextDouble() >= (round * 0.005).coerceAtMost(0.25))
                        votes[if (committed) own else standing.random(random)]++
                    }
                }
                val chosen = votes.indices.firstOrNull { votes[it] > senate.seats / 2 }
                if (chosen != null) {
                    elected = senateCandidates[chosen]
                    message = "상원이 ${votes[chosen]}표로 ${elected?.name} 후보를 부통령으로 선출했다."
                }
            }
            if (date >= inauguration) {
                val deputy = elected
                if (date == inauguration && deputy == null) leadership.seatVicePresident(null, date)
                if (deputy != null) {
                    leadership.seatVicePresident(deputy, date, newTerm = true)
                    if (houseVoting) {
                        leadership.actingPresident(deputy, date)
                        message = "하원에서 대통령이 결정될 때까지 ${deputy.name} 부통령이 대통령 권한을 대행한다."
                    }
                    cancel()
                } else if (houseVoting && !acting) {
                    val speakerParty = chamber?.groups?.maxByOrNull { it.seats }?.name
                    val speaker = leadership.nominate(speakerParty, date)
                    leadership.actingPresident(speaker, date)
                    acting = true
                    message = "양원에서 선출이 계속돼 하원의장이 사임하고 대통령 권한을 대행한다."
                }
            }
            val incumbentVacancy = if (date < inauguration) fillCurrentVacancy(date, chamber, upper, approval) else null
            return listOfNotNull(message, incumbentVacancy).takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
        return fillCurrentVacancy(date, chamber, upper, approval)
    }

    private fun fillCurrentVacancy(
        date: LocalDate,
        chamber: Legislature?,
        upper: UpperHouse?,
        approval: Double,
    ): String? {
        if (leadership.current().vicePresident != null || leadership.executiveIncumbent().title == "대통령 권한대행") {
            nextNomination = null
            return null
        }
        val due = nextNomination
        if (due == null) {
            nextNomination = date.plusDays(if (country == "IDN") 28 else 14)
            return null
        }
        if (date < due) return null
        val president = leadership.executiveIncumbent()
        val candidate = leadership.nominate(president.party, date)
        if (country == "IDN") {
            val other = leadership.nominate(president.party, date)
            val selected = if (random.nextBoolean()) candidate else other
            leadership.seatVicePresident(selected, date)
            nextNomination = null
            return "국민협의회가 대통령이 제안한 두 후보 중 ${selected.name} 후보를 후임 부통령으로 선출했다."
        }
        val senate = requireNotNull(upper).session(chamber, date)
        val basis = (approval + random.nextDouble(-8.0, 14.0)).coerceIn(0.0, 100.0)
        val lowerYes = chamber?.groups?.sumOf { it.votingSeats * (basis + it.role.backing).coerceIn(0.0, 100.0) / 100.0 } ?: 0.0
        if (chamber != null && lowerYes >= chamber.required && senate.forecast(basis) > senate.votingSeats / 2) {
            leadership.seatVicePresident(candidate, date)
            nextNomination = null
            return "양원이 대통령이 지명한 ${candidate.name} 후보를 인준해 부통령이 취임했다."
        }
        nextNomination = date.plusDays(21)
        return "부통령 후보가 양원 인준을 얻지 못해 대통령이 후임을 다시 지명한다."
    }
}
