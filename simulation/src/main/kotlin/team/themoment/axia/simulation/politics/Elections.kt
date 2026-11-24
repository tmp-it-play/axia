package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

data class PartyResult(
    val name: String,
    val role: PartyRole,
    val voteShare: Double,
    /** 개원 전 첫 선거에는 비교할 현역 의회가 없다. */
    val seatsBefore: Int?,
    val seatsAfter: Int,
)

data class CoalitionPreference(
    val formateur: String,
    val partner: String,
)

data class ElectionResult(
    val date: LocalDate,
    val early: Boolean,
    /** 의회 선거가 있었으면 정당별 결과. 선거 전 의석 순으로 정렬한다. */
    val parties: List<PartyResult>?,
    val executive: ExecutiveResult?,
    val governmentBefore: String,
    val governmentAfter: String,
    /** 의회 해산으로 폐기돼 초안으로 돌아간 재정안이 있었는지. */
    val billLapsed: Boolean,
    val districts: List<DistrictResult> = emptyList(),
    val coalitionReport: String? = null,
    val senate: List<SenateReturn> = emptyList(),
    val pakistanReturn: PakistanReturn? = null,
    val listSeats: Map<String, Map<String, Int>> = emptyMap(),
) {
    val governmentChanged: Boolean get() = governmentBefore != governmentAfter
}

/** 지금 선거를 치르면 나올 예상(무작위 변동 없음). */
data class SeatProjection(
    val name: String,
    val current: Int?,
    val projected: Int,
    val voteShare: Double,
)

internal data class LegislativeSeats(
    val direct: Map<String, Int>,
    val reserved: Map<String, Int> = emptyMap(),
    val districts: List<DistrictResult> = emptyList(),
    val districtMandates: Map<String, Int> = emptyMap(),
    val listSeats: Map<String, Map<String, Int>> = emptyMap(),
) {
    fun total(name: String): Int = direct.getValue(name) + reserved.getOrDefault(name, 0)
}

/** 해산 상태에서 치른 첫 총선의 새 의회와 정당별 득표 결과. */
internal data class OpeningElection(
    val legislature: Legislature,
    val parties: List<PartyResult>,
    val districts: List<DistrictResult>,
)

/**
 * 득표와 의석 계산. 무작위 변동은 호출자가 넘겨 결정론을 유지한다.
 *
 * 정당의 기준 득표율은 현재 의석 비중을 선거제도의 의석 편향만큼 되돌린 값이다. 그래서 조건이 그대로면 의석도 그대로 나온다.
 * 정부 정당은 지지율이 50%에서 벗어난 만큼, 야당은 지지 집단의 불만만큼 득표가 움직인다. 권위주의 체제는 집권 세력에 가점을 준다.
 */
internal object ElectionModel {
    private const val GOVERNMENT_SWING = 0.6
    private const val OPPOSITION_SWING = 0.3
    private const val CONSTITUENCY_WEIGHT = 0.4
    private const val AUTOCRATIC_DAMPING = 0.5

    fun voteShares(
        groups: List<ParliamentaryGroup>,
        system: ElectoralSystem,
        regime: Regime,
        approval: Double,
        satisfaction: Map<PopulationGroup, Double>,
        lastVote: Map<String, Double> = emptyMap(),
        identityMood: Map<Identity, Double> = emptyMap(),
        identityShares: Map<Identity, Double> = emptyMap(),
        noise: (ParliamentaryGroup) -> Double,
    ): Map<String, Double> {
        // 공석만 남은 정당도 보궐·다음 총선에 출마한다. 특별대표 집단은 직선 득표가 없다.
        val bases = groups.associate { it.name to (if (it.seats == 0 && it.role != PartyRole.OTHER) 1 else it.directSeats) }
        val totalSeats = bases.values.sum().toDouble()
        val implied =
            groups.associate {
                it.name to
                    (lastVote[it.name] ?: (bases.getValue(it.name) / totalSeats).pow(1.0 / system.seatExponent))
            }
        // 권위주의 체제의 집권 우위는 이미 현재 의석에 담겨 있다. 가점을 더하면 선거마다 누적되므로,
        // 대신 지지율, 불만, 무작위 변동이 덜 반영되게만 한다.
        val damping = if (regime.autocratic) AUTOCRATIC_DAMPING else 1.0
        val swing = ((approval - 50.0) / 50.0).coerceIn(-1.0, 1.0) * damping
        val weights =
            groups.associate { party ->
                val shift =
                    when (party.role) {
                        PartyRole.LEAD, PartyRole.COALITION -> {
                            GOVERNMENT_SWING * swing
                        }

                        PartyRole.SUPPLY -> {
                            0.5 * GOVERNMENT_SWING * swing
                        }

                        PartyRole.OPPOSITION -> {
                            val discontent = (50.0 - party.supportMood(satisfaction, identityMood, 50.0)) / 50.0
                            -OPPOSITION_SWING * swing + CONSTITUENCY_WEIGHT * discontent * damping
                        }

                        PartyRole.OTHER -> {
                            0.0
                        }
                    }
                val mobilization =
                    party.identity?.let { id ->
                        (identityShares[id] ?: 0.0) * (50.0 - (identityMood[id] ?: 50.0)) / 50.0
                    } ?: 0.0
                party.name to implied.getValue(party.name) * exp(shift + (noise(party) + mobilization * 0.3) * damping)
            }
        val sum = weights.values.sum()
        return weights.mapValues { it.value / sum }
    }

    /** 해산 전 마지막 총선의 득표를 기준으로 개원 선거를 치른다. 과도 내각에는 집권당이 없다. */
    fun openingElection(
        rule: LegislativeElectionRule,
        regime: Regime,
        electorate: DistrictElectorate? = null,
        noise: () -> Double,
    ): OpeningElection {
        val opening = requireNotNull(rule.openingContest)
        val damping = if (regime.autocratic) AUTOCRATIC_DAMPING else 1.0
        val weights = opening.parties.associate { it.name to it.votes * exp(noise() * damping) }
        val total = weights.values.sum()
        val shares = weights.mapValues { it.value / total }
        val seats = legislativeSeats(shares, rule, electorate)
        val elected =
            opening.parties.mapNotNull { party ->
                val count = seats.total(party.name)
                if (count > 0) {
                    ParliamentaryGroup(
                        name = party.name,
                        seats = count,
                        role = if (party.other) PartyRole.OTHER else PartyRole.OPPOSITION,
                        reservedSeats = seats.reserved.getOrDefault(party.name, 0),
                        districtMandates = seats.districtMandates[party.name] ?: 0,
                    )
                } else {
                    null
                }
            }
        val chamber = Legislature(opening.chamberName, elected, elected.sumOf { it.seats })
        val governed =
            if (rule.pakistanAssembly) {
                chamber.groups
            } else {
                formGovernment(
                    chamber.groups,
                    chamber.required,
                    previousRoles = emptyMap(),
                )
            }
        val roles = governed.associate { it.name to it.role }
        val results =
            opening.parties.map { party ->
                val role = roles[party.name] ?: if (party.other) PartyRole.OTHER else PartyRole.OPPOSITION
                PartyResult(party.name, role, shares.getValue(party.name), null, seats.total(party.name))
            }
        return OpeningElection(chamber.withGroups(governed), results, seats.districts)
    }

    /** 일반 의석을 먼저 채우고, 유보석이 있는 의회는 정당이 얻은 일반 의석에 비례해 나머지를 배분한다. */
    fun legislativeSeats(
        shares: Map<String, Double>,
        rule: LegislativeElectionRule,
        electorate: DistrictElectorate? = null,
    ): LegislativeSeats {
        rule.pluralityMap?.let { return electorate?.plurality(it, shares) ?: PluralityDistricts.elect(it, MixedVotes(shares)) }
        if (rule.iranianAssembly) {
            return (
                electorate?.iranianCount(
                    shares,
                ) ?: IranianAssembly.count(MixedVotes(shares), 50.0)
            ).result(50.0, null, 0)
        }
        if (rule.australianHouse) return electorate?.australianHouse(shares) ?: AustralianHouse.elect(MixedVotes(shares))
        if (rule.proportionalSystem !=
            null
        ) {
            return electorate?.proportional(shares, rule) ?: ProportionalElections.elect(rule, MixedVotes(shares))
        }
        rule.mixedSystem?.let { return electorate?.mixedMember(shares, it) ?: MixedMemberElections.elect(it, MixedVotes(shares)) }
        if (rule.frenchAssembly) return (electorate?.frenchAssembly(shares) ?: FrenchAssembly.count(shares)).seats
        if (rule.pakistanAssembly) {
            val opening = requireNotNull(rule.openingContest)
            return electorate?.pakistanAssembly(shares, opening) ?: PakistanAssembly.elect(shares, opening)
        }
        val openSeats = rule.seats - rule.reservedSeats
        val districts =
            if (rule.usHouse) {
                electorate?.usHouse(
                    shares,
                ) ?: RegionalElections.house(
                    shares,
                )
            } else {
                electorate?.elect(shares, openSeats, rule.electoralSystem, rule.constituencySeats).orEmpty()
            }
        val direct =
            if (districts.isEmpty()) {
                allocateSeats(shares, rule.electoralSystem, openSeats)
            } else {
                val winners = districts.flatMap { it.winners }.groupingBy { it }.eachCount()
                val listSeats = openSeats - winners.values.sum()
                allocateSeats(shares, ElectoralSystem.PROPORTIONAL, listSeats).mapValues { (name, count) -> count + (winners[name] ?: 0) }
            }
        val districtMandates =
            when {
                districts.isNotEmpty() -> {
                    districts.flatMap { it.winners }.groupingBy { it }.eachCount()
                }

                rule.electoralSystem == ElectoralSystem.MAJORITARIAN -> {
                    direct
                }

                rule.constituencySeats != null -> {
                    allocateSeats(
                        direct.mapValues {
                            it.value.toDouble()
                        },
                        ElectoralSystem.PROPORTIONAL,
                        rule.constituencySeats,
                    )
                }

                else -> {
                    emptyMap()
                }
            }
        if (rule.reservedSeats == 0) return LegislativeSeats(direct, districts = districts, districtMandates = districtMandates)
        rule.reservedDelegation?.let { delegation ->
            require(delegation in direct && direct.getValue(delegation) == 0)
            return LegislativeSeats(direct, mapOf(delegation to rule.reservedSeats), districts, districtMandates)
        }
        val candidates = rule.openingContest?.parties.orEmpty()
        val independents = candidates.filter { it.other }.map { it.name }.toSet()
        val eligible = direct.filter { (name, seats) -> name !in independents && seats > 0 }
        if (eligible.isEmpty()) return LegislativeSeats(direct, districts = districts, districtMandates = districtMandates)
        val reserved = allocateSeats(eligible.mapValues { it.value.toDouble() }, ElectoralSystem.PROPORTIONAL, rule.reservedSeats)
        return LegislativeSeats(direct, reserved, districts, districtMandates)
    }

    /** 최대잉여법. 의석 비중 ∝ 득표율^지수. */
    fun allocateSeats(
        shares: Map<String, Double>,
        system: ElectoralSystem,
        seats: Int,
    ): Map<String, Int> {
        val weights = shares.mapValues { it.value.pow(system.seatExponent) }
        val sum = weights.values.sum()
        val quotas = weights.mapValues { it.value / sum * seats }
        val allocated = quotas.mapValues { floor(it.value).toInt() }.toMutableMap()
        var remaining = seats - allocated.values.sum()
        quotas.entries.sortedByDescending { it.value - floor(it.value) }.forEach { (name, _) ->
            if (remaining > 0) {
                allocated[name] = allocated.getValue(name) + 1
                remaining -= 1
            }
        }
        return allocated
    }

    /** 새 의석으로 정당 목록을 다시 만든다. 의석을 모두 잃은 정당은 빠지고, 표결 불참 의석은 같은 비율로 유지한다. */
    fun reseat(
        groups: List<ParliamentaryGroup>,
        seats: LegislativeSeats,
    ): List<ParliamentaryGroup> =
        groups.mapNotNull { party ->
            val newSeats = seats.total(party.name)
            if (newSeats == 0) {
                null
            } else {
                val nonVoting = (newSeats * party.nonVoting.toDouble() / party.seats.coerceAtLeast(1)).roundToInt().coerceIn(0, newSeats)
                party.copy(
                    seats = newSeats,
                    nonVoting = nonVoting,
                    reservedSeats = seats.reserved.getOrDefault(party.name, 0),
                    districtMandates =
                        seats.districtMandates[party.name] ?: 0,
                )
            }
        }

    /**
     * 의회에서 정부를 만든다. 무소속은 그대로 둔다.
     * - 기존 정부 블록(주도·연정·협력)이 과반을 지키면 정부를 유지하고, 블록 안의 제1당이 주도한다.
     * - 아니면 [president]가 있으면 그 정당이, 없으면 제1당이 과반이 될 때까지 영입한다.
     *   부족한 의석·기존 협력·이념 거리로 접촉 순서를 정한다. 합의한 파트너가 과반에 모자라면 소수 내각이 된다.
     * 해산 상태에서 의회를 처음 구성할 때는 이전 역할이 비어 있다. 기존 무당파 정부(예: 이란, 이집트)는 유지한다.
     */
    fun formGovernment(
        groups: List<ParliamentaryGroup>,
        required: Int,
        previousRoles: Map<String, PartyRole>,
        president: String? = null,
        preference: CoalitionPreference? = null,
        maxPartnerDistance: Double = 0.58,
    ): List<ParliamentaryGroup> {
        if (previousRoles.isNotEmpty() && PartyRole.LEAD !in previousRoles.values) return groups
        val inGovernment = { name: String ->
            previousRoles[name].let { it == PartyRole.LEAD || it == PartyRole.COALITION || it == PartyRole.SUPPLY }
        }
        val bloc = groups.filter { it.role != PartyRole.OTHER && inGovernment(it.name) }
        val incumbentLeader = bloc.maxByOrNull { it.votingSeats }?.takeIf { bloc.sumOf { p -> p.votingSeats } >= required }
        if (president == null && incumbentLeader != null && preference?.formateur != incumbentLeader.name) {
            val leader = incumbentLeader.name
            return groups.map { party ->
                val role =
                    when {
                        party.name == leader -> PartyRole.LEAD
                        party.role == PartyRole.OTHER -> PartyRole.OTHER
                        previousRoles[party.name] == PartyRole.LEAD -> PartyRole.COALITION
                        else -> previousRoles[party.name] ?: PartyRole.OPPOSITION
                    }
                party.copy(role = role)
            }
        }
        val candidates = groups.filter { it.role != PartyRole.OTHER && it.votingSeats > 0 }.sortedByDescending { it.votingSeats }
        val formateur = candidates.firstOrNull { it.name == president } ?: incumbentLeader ?: candidates.firstOrNull() ?: return groups
        val coalition = mutableMapOf(formateur.name to PartyRole.LEAD)
        var seats = formateur.votingSeats
        val remaining = (candidates - formateur).toMutableList()
        while (seats < required && remaining.isNotEmpty()) {
            fun affinity(party: ParliamentaryGroup): Double {
                val distance =
                    coalition.keys
                        .map { name ->
                            ideologicalDistance(candidates.first { it.name == name }.identity, party.identity)
                        }.average()
                val continuity = if (inGovernment(formateur.name) == inGovernment(party.name)) 0.16 else 0.0
                val neededSeats = minOf(party.votingSeats, required - seats).toDouble() / (required - seats)
                return 0.55 * (1.0 - distance) + 0.29 * neededSeats + continuity
            }
            val requested = preference?.takeIf { it.formateur == formateur.name }?.partner
            val partner = remaining.firstOrNull { it.name == requested } ?: remaining.maxBy(::affinity)
            remaining.remove(partner)
            val distance = ideologicalDistance(formateur.identity, partner.identity)
            val formerPartners = inGovernment(formateur.name) && inGovernment(partner.name)
            if (distance > maxPartnerDistance && !formerPartners) continue
            coalition[partner.name] =
                if (distance > 0.43 || previousRoles[partner.name] == PartyRole.SUPPLY) PartyRole.SUPPLY else PartyRole.COALITION
            seats += partner.votingSeats
        }
        return groups.map { party ->
            val role =
                when {
                    party.role == PartyRole.OTHER -> PartyRole.OTHER
                    party.name == formateur.name -> PartyRole.LEAD
                    party.name in coalition -> coalition.getValue(party.name)
                    else -> PartyRole.OPPOSITION
                }
            party.copy(role = role)
        }
    }

    fun afterLegislativeElection(
        chamber: Legislature,
        rules: ElectionRules,
        previousRoles: Map<String, PartyRole>,
        presidentialParty: String?,
        preference: CoalitionPreference? = null,
    ): Legislature {
        if (!rules.coalitionNegotiations) return chamber
        val president =
            if (rules.system == GovernmentSystem.PRESIDENTIAL) {
                presidentialParty?.takeIf { name -> chamber.groups.any { it.name == name } } ?: return chamber
            } else {
                null
            }
        return chamber.withGroups(formGovernment(chamber.groups, chamber.required, previousRoles, president, preference))
    }

    fun presidentialLegislature(
        chamber: Legislature,
        rules: ElectionRules,
        winner: String,
    ): Legislature {
        if (rules.system != GovernmentSystem.PRESIDENTIAL ||
            chamber.groups.any { it.name == winner && it.role == PartyRole.LEAD }
        ) {
            return chamber
        }
        val installed = chamber.withGroups(installPresident(chamber.groups, winner))
        if (rules.legislature?.electoralSystem != ElectoralSystem.PROPORTIONAL ||
            installed.groups.none { it.name == winner }
        ) {
            return installed
        }
        val previousRoles = chamber.groups.associate { it.name to it.role }
        return installed.withGroups(formGovernment(installed.groups, installed.required, previousRoles, president = winner))
    }

    /** 대통령 정당이 바뀌면 새 대통령 정당이 정부를 주도하고 나머지 정당은 야당이 된다. */
    private fun installPresident(
        groups: List<ParliamentaryGroup>,
        winner: String,
    ): List<ParliamentaryGroup> {
        if (groups.firstOrNull { it.role == PartyRole.LEAD }?.name == winner) return groups
        return groups.map { party ->
            val role =
                when {
                    party.name == winner -> PartyRole.LEAD
                    party.role == PartyRole.OTHER -> PartyRole.OTHER
                    else -> PartyRole.OPPOSITION
                }
            party.copy(role = role)
        }
    }
}
