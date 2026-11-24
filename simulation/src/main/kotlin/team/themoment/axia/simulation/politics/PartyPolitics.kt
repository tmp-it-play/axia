package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/** 모형의 정당 기반. 자료가 없는 정당은 이름의 명시적 강령이나 직업 기반을 쓰고, 둘 다 없으면 중도로 둔다. */
internal fun partyIdentity(
    name: String,
    group: PopulationGroup?,
): Identity? =
    when {
        listOf("녹색", "생태", "기후").any(name::contains) -> Identity.ENVIRONMENTALISTS
        listOf("노동", "공산", "노총").any(name::contains) -> Identity.UNIONISTS
        listOf("좌파", "사회민주", "사회당", "평등", "진보").any(name::contains) -> Identity.EGALITARIANS
        listOf("기독", "종교", "토라", "무슬림", "가치연합").any(name::contains) -> Identity.RELIGIOUS
        listOf("보수", "애국", "민족주의", "국민수호").any(name::contains) -> Identity.PATRIOTS
        group == PopulationGroup.CAPITALISTS || group == PopulationGroup.SELF_EMPLOYED -> Identity.FREE_MARKET
        group == PopulationGroup.WORKERS -> Identity.UNIONISTS
        group == PopulationGroup.PUBLIC_SECTOR -> Identity.EGALITARIANS
        group == PopulationGroup.PROFESSIONALS -> Identity.LIBERTARIANS
        else -> null
    }

/** 재분배·전통주의 두 축의 모형 거리(0~1). 연정 교섭과 탈락 후보의 결선 지지 이전에 함께 쓴다. */
internal fun ideologicalDistance(
    a: Identity?,
    b: Identity?,
): Double {
    fun position(identity: Identity?) =
        when (identity) {
            Identity.ENVIRONMENTALISTS -> -0.4 to -0.7
            Identity.PATRIOTS -> 0.3 to 0.9
            Identity.RELIGIOUS -> 0.1 to 1.0
            Identity.LIBERTARIANS -> 0.4 to -1.0
            Identity.EGALITARIANS -> -1.0 to -0.6
            Identity.FREE_MARKET -> 1.0 to 0.0
            Identity.UNIONISTS -> -0.9 to 0.0
            Identity.YOUTH -> -0.2 to -0.5
            Identity.PARENTS -> -0.1 to 0.3
            null -> 0.0 to 0.0
        }
    val (economic, social) = position(a)
    val (otherEconomic, otherSocial) = position(b)
    return (abs(economic - otherEconomic) + abs(social - otherSocial)) / 4.0
}

internal class EmergingParties(
    profile: PoliticalProfile,
) {
    private val initialNames =
        buildSet {
            profile.legislature?.groups?.mapTo(this) { it.name }
            profile.elections.legislature
                ?.openingContest
                ?.parties
                ?.mapTo(this) { it.name }
        }
    private var nextId = 1
    private val parties = mutableListOf<NewParty>()

    fun snapshot(): List<NewParty> = parties.toList()

    fun clear() = parties.clear()

    fun advance(
        date: LocalDate,
        groups: List<ParliamentaryGroup>,
        moods: Map<Identity, Double>,
        shares: Map<Identity, Double>,
        random: Random,
        takenNames: () -> Set<String>,
    ): List<NewParty> {
        parties.replaceAll { party ->
            val target =
                (
                    shares.getValue(
                        party.identity,
                    ) * ((35.0 - moods.getValue(party.identity)) / 35.0).coerceAtLeast(0.0) * 0.5
                ).coerceAtMost(0.15)
            party.copy(support = party.support + (target - party.support) * 0.1)
        }
        parties.removeIf { it.support < 0.005 }
        val founded = mutableListOf<NewParty>()
        for (identity in Identity.entries) {
            if (parties.size >= 3) break
            if (moods.getValue(identity) >= 35.0 || parties.any { it.identity == identity } ||
                groups.any { it.foundation?.identity == identity }
            ) {
                continue
            }
            if (random.nextDouble() >= 0.08) continue
            val taken = takenNames() + groups.map { it.modelName } + parties.map { it.foundation.originalName }
            val bases = PARTY_NAMES.getValue(identity)
            val names = bases + listOf("새", "미래", "국민").flatMap { prefix -> bases.map { "$prefix$it" } }
            val name =
                names.firstOrNull { it !in taken }
                    ?: generateSequence(2) { it + 1 }.map { "${bases.first()} $it" }.first { it !in taken }
            val party = NewParty(name, PartyFoundation(nextId++, name, identity), date, 0.01)
            parties += party
            founded += party
        }
        return founded
    }

    fun voteShares(existing: Map<String, Double>): Map<String, Double> {
        if (parties.isEmpty()) return existing
        val fresh = parties.associate { it.name to it.support }
        val remaining = 1.0 - fresh.values.sum()
        return existing.mapValues { it.value * remaining } + fresh
    }

    fun contenders() = parties.map { ParliamentaryGroup(it.name, 1, PartyRole.OPPOSITION, foundation = it.foundation) }

    fun takenNames(
        chamber: Legislature?,
        executive: ExecutiveElection,
    ): Set<String> {
        if (chamber == null) return emptySet()
        return buildSet {
            addAll(initialNames)
            chamber.groups.mapTo(this) { it.name }
            parties.mapTo(this) { it.name }
            addAll(executive.partyNamesInOfficeOrCampaign())
        }
    }

    /** 원외 시점과 당선 이후에 같은 창당 번호를 사용한다. 시나리오의 실존 정당은 번호가 없다. */
    fun rename(
        id: Int,
        requested: String,
        chamber: Legislature,
        takenNames: Set<String>,
    ): PartyRename? {
        val index = parties.indexOfFirst { it.foundation.id == id }
        val old = parties.getOrNull(index)?.name ?: chamber.groups.firstOrNull { it.foundation?.id == id }?.name ?: return null
        val name = requested.trim()
        if (name.length !in 1..40 || name.any(Char::isISOControl) || name in takenNames) return null
        if (index >= 0) parties[index] = parties[index].copy(name = name)
        return PartyRename(old, name)
    }
}

/** 진행 중인 정치 상태에서 개명 전 이름으로 연결된 참조만 바꾼다. 과거 표결·선거 기록은 당시 명칭을 유지한다. */
internal data class PartyRename(
    val old: String,
    val name: String,
) {
    fun label(value: String): String = if (value == old) name else value

    fun group(party: ParliamentaryGroup): ParliamentaryGroup = if (party.name == old) party.copy(name = name) else party

    fun upper(
        house: UpperHouse,
        chamber: Legislature,
    ): UpperHouse {
        val modelName = chamber.groups.firstOrNull { it.name == name }?.modelName
        return house.copy(
            groups =
                house.groups.map {
                    if (it.name ==
                        old
                    ) {
                        it.copy(name = name, alignWith = modelName ?: it.alignWith)
                    } else {
                        it
                    }
                },
        )
    }

    fun rollCall(vote: RollCall): RollCall =
        vote.copy(rows = vote.rows.map { it.copy(party = group(it.party)) }, negotiatingWith = vote.negotiatingWith?.let(::label))
}

private val PARTY_NAMES =
    mapOf(
        Identity.ENVIRONMENTALISTS to listOf("녹색당", "기후정의당"),
        Identity.PATRIOTS to listOf("국민수호당", "애국연합"),
        Identity.RELIGIOUS to listOf("가치연합", "전통가정당"),
        Identity.LIBERTARIANS to listOf("자유시민당", "시민자유연대"),
        Identity.EGALITARIANS to listOf("정의평등당", "평등사회당"),
        Identity.FREE_MARKET to listOf("시장개혁당", "자유경제당"),
        Identity.UNIONISTS to listOf("노동자연대당", "일하는사람들당"),
        Identity.YOUTH to listOf("청년미래당", "다음세대당"),
        Identity.PARENTS to listOf("가족우선당", "아이키움당"),
    )
