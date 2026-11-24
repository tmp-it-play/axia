package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import team.themoment.axia.simulation.people.LeadershipState
import team.themoment.axia.simulation.people.Person
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class IndirectPresidentRule(
    val procedure: PresidentialCollege,
    @Serializable(with = LocalDateSerializer::class) val firstBallot: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val firstTermEnd: LocalDate,
    val sourceNote: String,
)

enum class PresidentialCollege(
    val body: String,
    val actingOffice: String,
    val years: Long,
    val limit: Int?,
    val advanceDays: Long,
    val vacancyDays: Long,
) {
    GERMANY("연방총회", "연방참사원 의장", 5, 2, 34, 25),
    INDIA("선출직 연방·주의원 선거인단", "부통령", 5, null, 7, 90),
    ITALY("양원합동회의·지역 대표", "상원의장", 7, null, 10, 13),
    ISRAEL("크네세트", "크네세트 의장", 7, 1, 45, 35),
    ESTONIA("리기코구", "리기코구 의장", 5, 2, 40, 10),
    LATVIA("사에이마", "사에이마 의장", 4, 2, 38, 25),
    PAKISTAN("양원·4개 주의회 선거인단", "상원의장", 5, 2, 45, 25),
}

data class PresidentialCollegeCount(
    val name: String,
    val voters: Int,
    val votes: Int,
)

data class PresidentialDivision(
    val date: LocalDate,
    val round: Int,
    val college: List<PresidentialCollegeCount>,
    val votes: Map<Person, Int>,
    val required: Int?,
    val against: Int = 0,
)

data class IndirectPresidentSnapshot(
    val procedure: PresidentialCollege,
    val nextBallot: LocalDate?,
    val accession: LocalDate?,
    val presidentElect: Person?,
    val divisions: List<PresidentialDivision>,
    val deputyBallot: LocalDate?,
    val deputyAccession: LocalDate?,
    val deputyElect: Person?,
    val lastDeputyBallot: PresidentialDivision?,
    val candidates: List<Person>,
)

/** 의회가 정부의 신임과 독립적으로 뽑는 국가원수. 선출된 후에도 취임일까지 전임자가 재임한다. */
internal class IndirectPresidency(
    rule: IndirectPresidentRule,
    private val leadership: LeadershipState,
    private val random: Random,
    private val electedSenators: () -> Map<String, Int>? = { null },
) {
    private val procedure = rule.procedure
    private var termEnd = rule.firstTermEnd
    private var next: LocalDate? = rule.firstBallot
    private var accession: LocalDate? = null
    private var elected: Person? = null
    private var round = 1
    private var field = emptyList<Person>()
    private val divisions = mutableListOf<PresidentialDivision>()
    private var vacancy = false
    private var nextDeputy = if (procedure == PresidentialCollege.INDIA) LocalDate.of(2027, 8, 6) else null
    private var deputyTermEnd = LocalDate.of(2027, 8, 11)
    private var deputyElect: Person? = null
    private var deputyVacancy = false
    private var lastDeputyBallot: PresidentialDivision? = null

    init {
        leadership.indirectVacancy = ::vacated
        leadership.indirectCandidates = { field + listOfNotNull(elected, deputyElect) }
    }

    fun snapshot() =
        IndirectPresidentSnapshot(
            procedure,
            next,
            accession,
            elected,
            divisions.toList(),
            nextDeputy,
            deputyTermEnd.takeIf {
                deputyElect !=
                    null
            },
            deputyElect,
            lastDeputyBallot,
            field.filter(leadership::available),
        )

    fun renamePerson(person: Person) {
        fun refresh(old: Person) = if (old.id == person.id) old.copy(name = person.name) else old
        field = field.map(::refresh)
        elected = elected?.let(::refresh)
        deputyElect = deputyElect?.let(::refresh)
    }

    fun renameParty(change: PartyRename) {
        fun refresh(old: Person) = old.copy(party = old.party?.let(change::label))
        field = field.map(::refresh)
        elected = elected?.let(::refresh)
        deputyElect = deputyElect?.let(::refresh)
    }

    private fun vacated(
        outgoing: Person,
        date: LocalDate,
    ): String {
        val deputy = leadership.current().vicePresident?.takeIf { procedure == PresidentialCollege.INDIA && leadership.available(it) }
        val actor = deputy ?: leadership.nominate(null, date)
        leadership.actingPresident(actor, date)
        val office = if (procedure == PresidentialCollege.INDIA && deputy == null) "대법원장" else procedure.actingOffice
        if (elected != null && leadership.available(requireNotNull(elected))) {
            accession = date.plusDays(1)
        } else if (!vacancy) {
            next = date.plusDays(procedure.vacancyDays)
            round = 1
            field = emptyList()
            elected = null
            accession = null
        }
        vacancy = true
        return "${outgoing.name} 퇴진. $office ${actor.name}이 국가원수 권한을 대행한다."
    }

    fun advance(
        date: LocalDate,
        chamber: Legislature?,
        upper: UpperHouse?,
        nextGeneral: LocalDate?,
        dissolved: Boolean,
    ): String? {
        if (chamber == null) return null
        val expired =
            if (!vacancy && date >= termEnd && elected?.let(leadership::available) != true &&
                procedure in setOf(PresidentialCollege.GERMANY, PresidentialCollege.ISRAEL, PresidentialCollege.LATVIA)
            ) {
                val scheduled = next
                val transition = vacated(leadership.current().headOfState, date)
                if (scheduled != null) next = minOf(requireNotNull(next), scheduled)
                transition
            } else {
                null
            }
        return listOfNotNull(
            expired,
            if (procedure == PresidentialCollege.INDIA) advanceDeputy(date, chamber, requireNotNull(upper)) else null,
            advancePresident(date, chamber, upper, nextGeneral, dissolved),
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** 헌법 제66~68조. 선출직·지명직 연방의원 모두 1인 1표이며 주의원은 참여하지 않는다. */
    private fun advanceDeputy(
        date: LocalDate,
        chamber: Legislature,
        upper: UpperHouse,
    ): String? {
        val incumbent = leadership.current().vicePresident
        if (deputyElect?.let { !leadership.available(it) } == true) {
            deputyElect = null
            nextDeputy = date.plusDays(21)
        }
        if (incumbent == null && !deputyVacancy) {
            deputyVacancy = true
            if (deputyElect !=
                null
            ) {
                deputyTermEnd = date.plusDays(1)
            } else {
                nextDeputy = minOf(nextDeputy ?: LocalDate.MAX, date.plusDays(30))
            }
        }
        deputyElect?.let { winner ->
            if (date < deputyTermEnd) return null
            leadership.seatVicePresident(winner, date, newTerm = true)
            if (vacancy) leadership.actingPresident(winner, date)
            deputyElect = null
            deputyVacancy = false
            deputyTermEnd = date.plusYears(5)
            nextDeputy = deputyTermEnd.minusDays(5)
            return "${winner.name} 부통령 취임 · 임기 5년."
        }
        if (nextDeputy?.let { date >= it } != true) return null
        val candidates = nominations(chamber, date, incumbent = incumbent)
        val ballot = count(date, chamber, upper, candidates, deputy = true)
        lastDeputyBallot = ballot
        val winner = ballot.votes.maxBy { it.value }
        deputyElect = winner.key
        if (deputyVacancy || deputyTermEnd <= date) deputyTermEnd = date.plusDays(1)
        nextDeputy = null
        return "연방 양원 부통령 간선 · ${winner.key.name} ${winner.value}표로 당선, 취임 $deputyTermEnd."
    }

    private fun advancePresident(
        date: LocalDate,
        chamber: Legislature?,
        upper: UpperHouse?,
        nextGeneral: LocalDate?,
        dissolved: Boolean,
    ): String? {
        if (chamber == null) return null
        elected?.let { winner ->
            if (!leadership.available(winner)) {
                elected = null
                accession = null
                field = emptyList()
                round = 1
                next = date.plusDays(procedure.vacancyDays)
            } else if (date >= requireNotNull(accession)) {
                leadership.seatPresident(winner, date)
                termEnd = if (procedure == PresidentialCollege.ISRAEL) hebrewAnniversary(date, 7) else date.plusYears(procedure.years)
                next = termEnd.minusDays(procedure.advanceDays)
                accession = null
                elected = null
                vacancy = false
                return "${winner.name} 대통령 취임 · 다음 정기 간선 $next."
            }
        }
        if (next?.let { date < it } != false) return null
        if ((procedure == PresidentialCollege.PAKISTAN && dissolved) ||
            (procedure == PresidentialCollege.ITALY && (dissolved || nextGeneral?.let { it >= date && it < date.plusMonths(3) } == true))
        ) {
            next = (nextGeneral?.takeIf { it >= date } ?: date).plusDays(if (procedure == PresidentialCollege.ITALY) 10 else 20)
            return null
        }
        field = field.filter(leadership::available)
        if (field.isEmpty()) {
            divisions.clear()
            field = nominations(chamber, date)
        }
        val reports = mutableListOf<String>()
        do {
            val count = count(date, chamber, upper)
            divisions += count
            val ranking =
                count.votes.entries
                    .shuffled(random)
                    .sortedByDescending { it.value }
            val leader = ranking.first()
            val won =
                count.required?.let { leader.value >= it }
                    ?: (procedure != PresidentialCollege.GERMANY || ranking.drop(1).none { it.value == leader.value })
            val institution = if (procedure == PresidentialCollege.ESTONIA && round >= 4) "지방대표 선거인단" else procedure.body
            if (won) {
                elected = leader.key
                accession = if (vacancy || termEnd <= date) date.plusDays(1) else termEnd
                next = null
                reports += "$institution ${round}차 · ${leader.key.name} ${leader.value}표로 대통령 당선, 취임 $accession."
                round = 1
                field = emptyList()
            } else {
                reports +=
                    if (count.required ==
                        null
                    ) {
                        "$institution ${round}차 · 최다득표 동률."
                    } else {
                        "$institution ${round}차 · 가결선 ${count.required}표 미달."
                    }
                val lastRound = round++
                when (procedure) {
                    PresidentialCollege.ESTONIA -> {
                        when (lastRound) {
                            1 -> {
                                field = nominations(chamber, date, field)
                                next = date.plusDays(1)
                            }

                            2, 4 -> {
                                field = ranking.take(2).map { it.key }
                                next = date
                            }

                            3 -> {
                                field = nominations(chamber, date, ranking.map { it.key })
                                next = date.plusDays(20)
                            }

                            else -> {
                                field = emptyList()
                                round = 1
                                next = date.plusDays(12)
                            }
                        }
                    }

                    PresidentialCollege.LATVIA -> {
                        if (lastRound >= 2) field = ranking.dropLast(1).map { it.key }
                        if (field.isEmpty()) {
                            round = 1
                            next = date.plusDays(12)
                        } else {
                            next = date
                        }
                    }

                    PresidentialCollege.ISRAEL -> {
                        if (ranking.size == 1 && leader.value < count.against) {
                            field = emptyList()
                            round = 1
                            next = date.plusDays(24)
                        } else {
                            field = ranking.take(2).map { it.key }
                            next = date
                        }
                    }

                    PresidentialCollege.ITALY -> {
                        next = date.plusDays(1)
                    }

                    else -> {
                        next = date
                    }
                }
            }
        } while (next == date)
        return reports.joinToString(" ")
    }

    private fun nominations(
        chamber: Legislature,
        date: LocalDate,
        retained: List<Person> = emptyList(),
        incumbent: Person? =
            leadership.current().headOfState.takeIf {
                !vacancy && (procedure.limit == null || it.termsServed < procedure.limit)
            },
    ): List<Person> {
        val groups = chamber.groups.filter { it.votingSeats > 0 }.sortedByDescending { it.votingSeats }
        val sponsors = groups.take(3)
        val minimumAge = if (procedure == PresidentialCollege.ITALY) 50 else 45
        val nominees = retained.toMutableList()
        sponsors.forEachIndexed { index, group ->
            val same = nominees.any { it.party == group.name }
            if (!same && nominees.size < 4) {
                val candidate =
                    if (index == 0 && incumbent != null &&
                        nominees.none { it.id == incumbent.id }
                    ) {
                        incumbent
                    } else {
                        leadership.nominate(group.name, date, minimumAge)
                    }
                nominees += candidate
            }
        }
        if (nominees.size == 1) nominees += leadership.nominate(null, date, minimumAge)
        return nominees
    }

    private data class Bloc(
        val name: String?,
        val role: PartyRole,
        val seats: Int,
    )

    private data class RankedVote(
        val order: List<Person>,
        val weight: Int,
    )

    private fun count(
        date: LocalDate,
        chamber: Legislature,
        upper: UpperHouse?,
        candidates: List<Person> = field,
        deputy: Boolean = false,
    ): PresidentialDivision {
        val lower = chamber.groups.map { Bloc(it.name, it.role, it.votingSeats) }.filter { it.seats > 0 }
        val electors = mutableListOf<PresidentialCollegeCount>()
        val votes = candidates.associateWithTo(linkedMapOf()) { 0 }
        val transferable = mutableListOf<RankedVote>()
        var against = 0
        val sponsors =
            candidates.associateWith { p ->
                lower.find { it.name == p.party } ?: if (p.id == leadership.current().headOfState.id) lower.maxBy { it.seats } else null
            }

        // 이탈리아는 네 번째 투표부터 후보 탈락 없이 원내 협상으로 특정 후보 지지가 모일 수 있다.
        val compromise =
            if (procedure == PresidentialCollege.ITALY && round >= 4) {
                divisions
                    .lastOrNull()
                    ?.votes
                    ?.maxByOrNull { it.value }
                    ?.key
            } else {
                null
            }

        fun ranking(bloc: Bloc): List<Person> =
            candidates
                .map { p ->
                    val sponsor = sponsors[p]
                    val sameSide = sponsor != null && (sponsor.role in governing) == (bloc.role in governing)
                    val affiliation =
                        when {
                            sponsor != null && sponsor.name == bloc.name -> 1.1
                            sameSide -> 0.65
                            else -> 0.0
                        }
                    val endorsement = if (p == compromise) (round - 3) * 0.24 else 0.0
                    p to affiliation + endorsement + random.nextDouble(1.55)
                }.sortedByDescending { it.second }
                .map { it.first }

        fun ballot(
            name: String,
            blocs: List<Bloc>,
            seats: Int = blocs.sumOf { it.seats },
            weight: Int = 1,
            equalized: Int? = null,
        ) {
            if (seats <= 0) return
            val picks = candidates.associateWithTo(linkedMapOf()) { 0 }
            val size = blocs.sumOf { it.seats }
            val sampled = blocs.isEmpty() || (seats != size)
            val cumulative = blocs.runningFold(0) { n, bloc -> n + bloc.seats }.drop(1)

            fun cast(bloc: Bloc) {
                val ranked = ranking(bloc)
                // 공석과 기권 구분. 1명 대상 또는 선거인단 결선에서도 백지표를 낼 수 있다.
                if (procedure != PresidentialCollege.INDIA && random.nextDouble() < 0.025) return
                if (procedure == PresidentialCollege.ISRAEL && candidates.size == 1) {
                    val sponsor = sponsors[ranked.first()]
                    val support = if (sponsor?.role == bloc.role) 0.85 else 0.45
                    if (random.nextDouble() >= support) {
                        against++
                        return
                    }
                }
                if (procedure == PresidentialCollege.INDIA) transferable += RankedVote(ranked, weight)
                picks[ranked.first()] = picks.getValue(ranked.first()) + weight
            }
            if (!sampled) {
                blocs.forEach { bloc -> repeat(bloc.seats) { cast(bloc) } }
            } else {
                repeat(seats) {
                    val draw = if (size == 0) -1 else random.nextInt(size)
                    val i = cumulative.indexOfFirst { n -> n > draw }
                    cast(blocs.getOrNull(i) ?: Bloc(null, PartyRole.OTHER, 1))
                }
            }
            picks.forEach { (person, amount) ->
                votes[person] = votes.getValue(person) + (equalized?.let { (amount * it.toDouble() / seats).roundToInt() } ?: amount)
            }
            electors += PresidentialCollegeCount(name, seats, equalized ?: seats * weight)
        }
        val upperSize = upper?.votingSeats ?: 0
        val senate =
            if (upper == null) {
                emptyList()
            } else if (upper.groups.isNotEmpty()) {
                upper.groups.map { Bloc(it.alignWith ?: it.name, it.role, it.votingSeats) }
            } else {
                listOf(
                    Bloc(null, PartyRole.LEAD, upper.governmentSeats),
                    Bloc(null, PartyRole.OPPOSITION, upper.oppositionSeats),
                    Bloc(null, PartyRole.OTHER, upper.otherSeats),
                )
            }
        val mpWeight = if (procedure == PresidentialCollege.INDIA && !deputy) 700 else 1
        ballot(chamber.name, lower, weight = mpWeight)
        when (procedure) {
            PresidentialCollege.GERMANY -> {
                ballot("주 의회 대표", lower, chamber.votingSeats)
            }

            PresidentialCollege.ITALY -> {
                ballot(requireNotNull(upper).name, senate)
                ballot("지역 대표", lower, 58)
            }

            PresidentialCollege.PAKISTAN -> {
                ballot(requireNotNull(upper).name, senate)
                for ((name, seats) in provinces) ballot(name, lower, seats, equalized = 65)
            }

            PresidentialCollege.ESTONIA -> {
                if (round >= 4) ballot("지방의회 대표", lower, 107)
            }

            PresidentialCollege.INDIA -> {
                if (deputy) {
                    ballot(requireNotNull(upper).name, senate)
                } else {
                    val eligible = electedSenators()
                    val electedBlocs =
                        eligible?.let { counts ->
                            requireNotNull(upper).groups.map { group ->
                                Bloc(
                                    group.alignWith ?: group.name,
                                    group.role,
                                    minOf(counts.getOrDefault(group.name, 0), group.votingSeats),
                                )
                            }
                        }
                    val seats = electedBlocs?.sumOf { it.seats } ?: (upperSize - 12).coerceIn(0, 233)
                    ballot(requireNotNull(upper).name + " 선출직", electedBlocs ?: senate, seats, weight = mpWeight)
                    for (state in indianAssemblies) ballot(state.first, lower, state.second, state.third)
                }
            }

            PresidentialCollege.LATVIA, PresidentialCollege.ISRAEL -> {
                // 본회의 의원만 투표한다.
            }
        }
        val total = electors.sumOf { it.votes }
        val threshold =
            when (procedure) {
                PresidentialCollege.GERMANY -> if (round <= 2) total / 2 + 1 else null
                PresidentialCollege.ITALY -> if (round <= 3) ceil(total * 2.0 / 3).toInt() else total / 2 + 1
                PresidentialCollege.ESTONIA -> if (round <= 3) 68 else total / 2 + 1
                PresidentialCollege.ISRAEL -> if (round == 1 && candidates.size > 1) 61 else (votes.values.sum() + against) / 2 + 1
                PresidentialCollege.LATVIA -> 51
                PresidentialCollege.PAKISTAN -> null
                PresidentialCollege.INDIA -> total / 2 + 1
            }
        if (procedure == PresidentialCollege.INDIA) {
            val continuing = candidates.toMutableList()
            while (votes.values.max() < requireNotNull(threshold) && continuing.size > 1) {
                val weakest = continuing.shuffled(random).minBy { votes.getValue(it) }
                continuing.remove(weakest)
                for (p in candidates) votes[p] = 0
                for (ballot in transferable) {
                    val preferred = ballot.order.first { it in continuing }
                    votes[preferred] = votes.getValue(preferred) + ballot.weight
                }
            }
        }
        return PresidentialDivision(date, if (deputy) 1 else round, electors, votes, threshold, against)
    }

    private companion object {
        val governing = setOf(PartyRole.LEAD, PartyRole.COALITION, PartyRole.SUPPLY)
        val provinces = mapOf("펀자브" to 371, "신드" to 168, "카이버파크툰크와" to 145, "발루치스탄" to 65)

        // ECI 2022 원표의 선출직 주의원 수와 의원별 투표 가치.
        val indianAssemblies =
            listOf(
                Triple("Andhra Pradesh", 175, 159),
                Triple("Arunachal Pradesh", 60, 8),
                Triple("Assam", 126, 116),
                Triple("Bihar", 243, 173),
                Triple("Chhattisgarh", 90, 129),
                Triple("Goa", 40, 20),
                Triple("Gujarat", 182, 147),
                Triple("Haryana", 90, 112),
                Triple("Himachal Pradesh", 68, 51),
                Triple("Jharkhand", 81, 176),
                Triple("Karnataka", 224, 131),
                Triple("Kerala", 140, 152),
                Triple("Madhya Pradesh", 230, 131),
                Triple("Maharashtra", 288, 175),
                Triple("Manipur", 60, 18),
                Triple("Meghalaya", 60, 17),
                Triple("Mizoram", 40, 8),
                Triple("Nagaland", 60, 9),
                Triple("Odisha", 147, 149),
                Triple("Punjab", 117, 116),
                Triple("Rajasthan", 200, 129),
                Triple("Sikkim", 32, 7),
                Triple("Tamil Nadu", 234, 176),
                Triple("Telangana", 119, 132),
                Triple("Tripura", 60, 26),
                Triple("Uttarakhand", 70, 64),
                Triple("Uttar Pradesh", 403, 208),
                Triple("West Bengal", 294, 151),
                Triple("Delhi", 70, 58),
                Triple("Puducherry", 30, 16),
            )
    }
}

/** 이스라엘 대통령 임기는 기본법 제3조에 따라 히브리력으로 센다. 티슈리 1일(5784년 = 2023-09-16)을 기준으로 변환한다. */
internal fun hebrewAnniversary(
    date: LocalDate,
    years: Int,
): LocalDate {
    fun elapsed(year: Int): Long {
        val months = (235L * year - 234) / 19
        val parts = 12084 + 13753 * months
        val day = 29 * months + parts / 25920
        return if (3 * (day + 1) % 7 < 3) day + 1 else day
    }

    fun start(year: Int): Long {
        val now = elapsed(year)
        val delay =
            when {
                elapsed(year + 1) - now == 356L -> 2
                now - elapsed(year - 1) == 382L -> 1
                else -> 0
            }
        return now + delay
    }

    fun months(year: Int): List<Pair<String, Int>> {
        val length = (start(year + 1) - start(year)).toInt()
        return buildList {
            add("Tishri" to 30)
            add("Heshvan" to if (length % 10 == 5) 30 else 29)
            add("Kislev" to if (length % 10 == 3) 29 else 30)
            add("Tevet" to 29)
            add("Shevat" to 30)
            if (length > 360) add("Adar I" to 30)
            add("Adar" to 29)
            add("Nisan" to 30)
            add("Iyar" to 29)
            add("Sivan" to 30)
            add("Tammuz" to 29)
            add("Av" to 30)
            add("Elul" to 29)
        }
    }
    val offset = LocalDate.of(2023, 9, 16).toEpochDay() - start(5784)
    val serial = date.toEpochDay() - offset
    var year = date.year + 3760
    while (serial >= start(year + 1)) year++
    while (serial < start(year)) year--
    var day = (serial - start(year)).toInt()
    val month =
        months(year)
            .first { (_, days) ->
                if (day < days) {
                    true
                } else {
                    day -= days
                    false
                }
            }.first
    val future = months(year + years)
    val target = if (month == "Adar I" && future.none { it.first == month }) "Adar" else month
    return LocalDate.ofEpochDay(offset + start(year + years) + future.takeWhile { it.first != target }.sumOf { it.second } + day)
}
