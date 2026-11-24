package team.themoment.axia.simulation.people

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.text.josa
import java.time.LocalDate
import kotlin.random.Random

/** 실존 인물이거나 플레이 중 생성된 인물. 플레이어가 이름을 바꿀 수 있다. */
data class Person(
    val id: Int,
    val name: String,
    val title: String,
    val party: String?,
    val since: LocalDate,
    /** 현재 임기를 포함한 연속 재임 횟수. */
    val termsServed: Int,
    val generated: Boolean,
    val born: Int,
) {
    companion object {
        const val MAX_NAME_LENGTH = 30
    }
}

/**
 * 국가원수와 정부 수반. 같은 사람이면 [headOfGovernment]가 null이다.
 * @property parliamentary 의원내각제처럼 정부 수반이 실권자인지. 아니면 국가원수(대통령, 총서기, 최고지도자)가 실권자다.
 */
data class Leadership(
    val headOfState: Person,
    val headOfGovernment: Person?,
    val parliamentary: Boolean,
    val partyLeader: Person? = null,
    val crownPrince: Person? = null,
    val vicePresident: Person? = null,
    val actingCouncil: List<Person> = emptyList(),
) {
    /** 실권을 쥔 사람. */
    val leader: Person get() = partyLeader ?: if (parliamentary) headOfGovernment ?: headOfState else headOfState

    fun all(): List<Person> =
        (
            (actingCouncil.ifEmpty { listOf(headOfState) }) +
                listOfNotNull(headOfGovernment, partyLeader, crownPrince, vicePresident)
        ).distinctBy { it.id }
}

@Serializable
internal data class CultureNames(
    val familyFirst: Boolean,
    val separator: String,
    val surnames: List<String>,
    val male: List<String>,
    val female: List<String>,
    /** 생성되는 지도자 중 여성 비율. */
    val femaleShare: Double,
) {
    init {
        require(surnames.isNotEmpty() && male.isNotEmpty() && female.isNotEmpty())
    }
}

@Serializable
internal data class LeaderRecord(
    val name: String,
    val title: String,
    val party: String? = null,
    val termsServed: Int = 1,
    val born: Int,
)

@Serializable
internal data class CountryLeaders(
    val culture: String,
    val headOfState: LeaderRecord,
    val headOfGovernment: LeaderRecord? = null,
    val partyLeader: LeaderRecord? = null,
    val crownPrinceIsPremier: Boolean = false,
    val vicePresident: LeaderRecord? = null,
)

/** 2024년 1월 1일 지도자(leaders.json)와 문화권별 이름 재료(names.json). */
internal class PeopleData private constructor(
    private val cultures: Map<String, CultureNames>,
    private val leaders: Map<String, CountryLeaders>,
) {
    fun leaders(countryId: String): CountryLeaders = requireNotNull(leaders[countryId]) { "Missing leaders for $countryId" }

    fun culture(countryId: String): CultureNames = cultures.getValue(leaders[countryId]?.culture ?: DEFAULT_CULTURE)

    companion object {
        private const val DEFAULT_CULTURE = "english"

        @Serializable
        private data class NamesFile(
            val sourceNote: String,
            val cultures: Map<String, CultureNames>,
        )

        @Serializable
        private data class LeadersFile(
            val sourceNote: String,
            val countries: Map<String, CountryLeaders>,
        )

        private fun resource(name: String): String =
            requireNotNull(PeopleData::class.java.getResourceAsStream("/world/$name.json")) { "Missing $name.json" }
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        val default: PeopleData by lazy {
            val names = Json.decodeFromString<NamesFile>(resource("names"))
            val leaders = Json.decodeFromString<LeadersFile>(resource("leaders"))
            leaders.countries.forEach { (id, entry) ->
                require(entry.culture in names.cultures) { "$id has unknown culture ${entry.culture}" }
            }
            PeopleData(names.cultures, leaders.countries)
        }
    }
}

/**
 * 인물 명부. 시작 지도자를 실명으로 등록하고, 이후 새 지도자와 후보는 문화권별 이름 생성기로 만든다.
 * 이름이 겹치면 다시 뽑는다.
 */
class PeopleRegistry internal constructor(
    private val random: Random,
) {
    private val persons = linkedMapOf<Int, Person>()
    private var nextId = 1

    internal fun register(
        record: LeaderRecord,
        since: LocalDate,
    ): Person = add(Person(nextId, record.name, record.title, record.party, since, record.termsServed, generated = false, record.born))

    /** [countryId] 문화권 이름으로 새 인물을 만든다. */
    internal fun generate(
        countryId: String,
        title: String,
        party: String?,
        since: LocalDate,
        minimumAge: Int = MIN_LEADER_AGE,
    ): Person {
        val culture = PeopleData.default.culture(countryId)
        val taken = persons.values.mapTo(HashSet()) { it.name }
        // 군주와 신정 지도자는 남성이고, 군주는 성 없이 이름으로 부른다.
        val monarch = title in MONARCH_TITLES
        val maleOnly = monarch || title in MALE_TITLES
        var name: String
        var attempts = 0
        do {
            val given = (if (!maleOnly && random.nextDouble() < culture.femaleShare) culture.female else culture.male).random(random)
            val family = culture.surnames.random(random)
            name =
                when {
                    monarch -> given
                    culture.familyFirst -> "$family${culture.separator}$given"
                    else -> "$given${culture.separator}$family"
                }
            attempts++
        } while (name in taken && attempts < MAX_ATTEMPTS)
        if (name in taken) name = "$name ${attempts + 1}세"
        val born = since.year - random.nextInt(maxOf(minimumAge, MIN_LEADER_AGE), MAX_LEADER_AGE + 1)
        return add(Person(nextId, name, title, party, since, termsServed = 1, generated = true, born = born))
    }

    internal fun update(person: Person): Person = person.also { persons[it.id] = it }

    fun rename(
        id: Int,
        name: String,
    ): Person? {
        val trimmed = name.trim()
        val person = persons[id] ?: return null
        if (trimmed.length !in 1..Person.MAX_NAME_LENGTH || trimmed.any(Char::isISOControl)) return null
        return update(person.copy(name = trimmed))
    }

    fun get(id: Int): Person? = persons[id]

    private fun add(person: Person): Person {
        nextId++
        persons[person.id] = person
        return person
    }

    private companion object {
        val MONARCH_TITLES = setOf("국왕", "천황")
        val MALE_TITLES = setOf("최고지도자", "왕세자", "왕세자 겸 총리")
        const val MAX_ATTEMPTS = 20
        const val MIN_LEADER_AGE = 45
        const val MAX_LEADER_AGE = 66
    }
}

/** 한 나라의 지도자 자리. 정부가 바뀌면 새 지도자를 만들고, 같은 정부가 재선되면 임기 횟수를 늘린다. */
internal class LeadershipState(
    private val countryId: String,
    private val registry: PeopleRegistry,
    start: LocalDate,
    /** 직선 행정부 선거로 뽑는 직함(대통령, 총통). 없으면 국가원수를 행정부 수반으로 본다. */
    executiveTitle: String?,
    private val parliamentary: Boolean,
) {
    private val record = PeopleData.default.leaders(countryId)
    private var headOfStateId = registry.register(record.headOfState, start).id
    private var headOfGovernmentId = record.headOfGovernment?.let { registry.register(it, start).id }
    private var partyLeaderId = record.partyLeader?.let { registry.register(it, start).id }
    private var crownPrinceId = if (record.crownPrinceIsPremier) requireNotNull(headOfGovernmentId) else null
    private var vicePresidentId = record.vicePresident?.let { registry.register(it, start).id }

    /** 선거로 뽑는 행정부 수반이 국가원수인지(이란은 대통령이 정부 수반이다). */
    private val executiveIsHeadOfState = record.headOfGovernment?.title != executiveTitle || executiveTitle == null

    var indirectVacancy: ((Person, LocalDate) -> String)? = null
    var indirectCandidates: () -> List<Person> = { emptyList() }
    private var actingSince: LocalDate? = null
    private var council = emptyList<Int>()
    private val withdrawn = mutableSetOf<Int>()
    private val usTerms = mutableMapOf(headOfStateId to record.headOfState.termsServed)
    private var usTermEnd = usInaugurationAfter(start)

    private fun usInaugurationAfter(date: LocalDate): LocalDate {
        val year = date.year - Math.floorMod(date.year - 2025, 4)
        val boundary = LocalDate.of(year, 1, 20)
        return if (date < boundary) boundary else boundary.plusYears(4)
    }

    fun available(person: Person): Boolean = person.id !in withdrawn

    val doubleVacancy: Boolean get() = headOfStateId in withdrawn && countryId in setOf("USA", "IDN")

    private val initialLeadership = current()

    /**
     * 정부 표기. 시작 지도자가 그대로면 데이터 문구를 쓴다. 바뀌었으면 실권자와 정당을 쓰고,
     * 대통령과 총리가 함께 실권을 나누는 [semiPresidential] 체제는 두 사람을 함께 쓴다.
     */
    fun governmentLabel(
        initial: String,
        semiPresidential: Boolean,
    ): String {
        val now = current()
        if (now.all().map { it.id to it.name } == initialLeadership.all().map { it.id to it.name } ||
            (parliamentary && now.leader.id == initialLeadership.leader.id && now.leader.name == initialLeadership.leader.name)
        ) {
            return initial
        }
        val head = now.headOfGovernment
        if ((semiPresidential || !executiveIsHeadOfState || record.crownPrinceIsPremier) &&
            head != null
        ) {
            return "${now.headOfState.name} · ${head.name}"
        }
        val leader = now.leader
        return "${leader.name} · ${leader.party ?: leader.title}"
    }

    fun current(): Leadership =
        Leadership(
            stateHead(),
            headOfGovernmentId?.let { requireNotNull(registry.get(it)) },
            parliamentary,
            partyLeaderId?.let { requireNotNull(registry.get(it)) },
            crownPrinceId?.let { requireNotNull(registry.get(it)) },
            vicePresidentId?.let { requireNotNull(registry.get(it)) },
            council.map { requireNotNull(registry.get(it)) },
        )

    /** 국가기관이 현직자를 다시 선출하거나 같은 소속의 새 인물을 임명한다. */
    fun appointStateHead(
        date: LocalDate,
        retain: Boolean,
    ): Person = appointee(current().headOfState, date, retain).also { headOfStateId = it.id }

    fun appointPremier(
        date: LocalDate,
        retain: Boolean,
    ): Person = appointee(requireNotNull(current().headOfGovernment), date, retain).also { headOfGovernmentId = it.id }

    private fun appointee(
        incumbent: Person,
        date: LocalDate,
        retain: Boolean,
    ): Person =
        if (retain) {
            registry.update(
                incumbent.copy(termsServed = incumbent.termsServed + 1),
            )
        } else {
            registry.generate(countryId, incumbent.title, incumbent.party, date)
        }

    /** 연임 제한에 걸려 다음 행정부 선거에 나설 수 없는지. */
    fun termLimited(limit: Int?): Boolean =
        limit != null && (if (countryId == "USA") usTerms[executive().id] ?: 0 else executive().termsServed) >= limit

    fun executiveIncumbent(): Person = executive()

    fun eligibleIncumbent(limit: Int?): Person? =
        executive().takeIf {
            !termLimited(limit) && it.termsServed < RETIREMENT_TERMS &&
                it.title != ACTING_PRESIDENT &&
                council.isEmpty()
        }

    val iranActingPresident: Boolean get() = countryId == "IRN" && executive().title == ACTING_PRESIDENT

    fun nominate(
        party: String?,
        date: LocalDate,
    ): Person = nominate(party, date, 45)

    fun nominate(
        party: String?,
        date: LocalDate,
        minimumAge: Int,
    ): Person = registry.generate(countryId, "후보", party, date, minimumAge)

    fun nominate(
        record: LeaderRecord,
        date: LocalDate,
    ): Person = registry.register(record, date)

    internal fun changeAffiliation(
        id: Int,
        party: String,
    ): Person {
        val person = requireNotNull(registry.get(id))
        return registry.update(person.copy(party = party))
    }

    fun seatVicePresident(
        candidate: Person?,
        date: LocalDate,
        newTerm: Boolean = false,
    ) {
        if (candidate == null) {
            vicePresidentId = null
            return
        }
        val nominee = requireNotNull(registry.get(candidate.id))
        require(available(nominee) && (nominee.id != headOfStateId || actingSince != null))
        if (vicePresidentId != nominee.id) {
            registry.update(nominee.copy(title = record.vicePresident?.title ?: "부통령", since = date, termsServed = 1))
        } else if (newTerm) {
            registry.update(nominee.copy(termsServed = nominee.termsServed + 1))
        }
        vicePresidentId = nominee.id
    }

    fun installIndonesianCouncil(date: LocalDate) {
        require(countryId == "IDN")
        council = listOf("외교장관", "내무장관", "국방장관").map { registry.generate(countryId, it, null, date).id }
        headOfStateId = council.first()
        actingSince = date
    }

    fun actingPresident(
        candidate: Person,
        date: LocalDate,
    ) {
        val acting = requireNotNull(registry.get(candidate.id))
        require(available(acting))
        if (acting.id != vicePresidentId &&
            acting.id != headOfGovernmentId
        ) {
            registry.update(acting.copy(title = ACTING_PRESIDENT, since = date, termsServed = 0))
        }
        headOfStateId = acting.id
        actingSince = date
    }

    fun seatPresident(
        candidate: Person,
        date: LocalDate,
    ) {
        val incumbent = executive()
        val nominee = requireNotNull(registry.get(candidate.id))
        require(available(nominee))
        val elected =
            if (incumbent.id == nominee.id && incumbent.title != ACTING_PRESIDENT) {
                incumbent.copy(termsServed = incumbent.termsServed + 1)
            } else {
                nominee.copy(
                    title =
                        if (iranActingPresident) {
                            requireNotNull(record.headOfGovernment).title
                        } else if (incumbent.title ==
                            ACTING_PRESIDENT
                        ) {
                            record.headOfState.title
                        } else {
                            incumbent.title
                        },
                    since = date,
                    termsServed = 1,
                )
            }
        if (countryId == "USA") {
            usTerms[elected.id] = (usTerms[elected.id] ?: 0) + 1
            usTermEnd = usInaugurationAfter(date)
        }
        actingSince = null
        council = emptyList()
        if (vicePresidentId == elected.id) vicePresidentId = null
        setExecutive(registry.update(elected))
        if (countryId == "IRN" && elected.id != incumbent.id) {
            vicePresidentId = registry.generate(countryId, requireNotNull(record.vicePresident).title, null, date).id
        }
    }

    /** 의회에서 내각을 구성한다. 과도 내각을 이을 때는 새 정부 수반의 정식 [title]을 지정한다. */
    fun onGovernmentFormed(
        leadParty: String,
        date: LocalDate,
        reelected: Boolean,
        title: String? = null,
    ) {
        val head = parliamentaryHead()
        val office = title ?: head.title
        val successorNeeded = head.party != leadParty || head.title != office || (reelected && head.termsServed >= RETIREMENT_TERMS)
        val next =
            when {
                successorNeeded -> registry.generate(countryId, office, leadParty, date)
                reelected -> registry.update(head.copy(termsServed = head.termsServed + 1))
                else -> head
            }
        if (headOfGovernmentId != null) headOfGovernmentId = next.id else headOfStateId = next.id
    }

    /**
     * 매월 지도자의 사망·건강상 퇴진을 판정한다(곰페르츠 사망률, 고령일수록 가파르다).
     * 물러나면 같은 직함·정당의 후임자를 세우고 소식 문구를 돌려준다.
     */
    fun checkMortality(
        date: LocalDate,
        random: Random,
        incoming: List<Person> = emptyList(),
        transition: Boolean = false,
    ): String? {
        val current = current().all()
        for (person in (current + incoming + indirectCandidates()).distinctBy { it.id }) {
            if (!available(person)) continue
            if (!person.retires(date, random)) continue
            withdrawn += person.id
            if (person.id in council) {
                val replacement = registry.generate(countryId, person.title, null, date)
                council = council.map { if (it == person.id) replacement.id else it }
                headOfStateId = council.first()
                return "${person.title} ${person.name} 퇴진. 후임 장관 ${replacement.name}이 공동 권한대행에 합류했다."
            }
            if (current.none { it.id == person.id }) return "${person.name} 당선인 또는 후보가 건강 문제로 직무를 수행할 수 없게 됐다."
            if (countryId == "IND" && person.id == vicePresidentId && person.id != headOfStateId) {
                vicePresidentId = null
                return "${person.name} 부통령 퇴진으로 연방 양원이 보궐선거를 준비한다."
            }
            if (person.id == headOfStateId && indirectVacancy != null) {
                if (vicePresidentId == person.id) vicePresidentId = null
                return requireNotNull(indirectVacancy)(person, date)
            }
            if (countryId == "IRN" && person.id == headOfGovernmentId) {
                val vice = current().vicePresident
                val acting =
                    if (vice !=
                        null
                    ) {
                        registry.update(vice.copy(title = ACTING_PRESIDENT, since = date, termsServed = 0))
                    } else {
                        registry.generate(countryId, ACTING_PRESIDENT, null, date)
                    }
                headOfGovernmentId = acting.id
                vicePresidentId = null
                return "${person.name} ${person.title} 퇴진. 최고지도자의 승인으로 ${acting.name} 권한대행이 대통령 직무를 맡았다."
            }
            if (transition && person.id == headOfStateId && countryId in setOf("KOR", "FIN", "TWN")) {
                val vice = current().vicePresident?.takeIf(::available)
                if (vice != null && countryId == "TWN") {
                    seatPresident(vice, date)
                    return "${person.name} 총통 퇴진으로 ${vice.name} 부총통이 남은 임기를 승계했다."
                }
                val premier = requireNotNull(current().headOfGovernment)
                val acting = if (available(premier)) premier else appointPremier(date, false)
                actingPresident(acting, date)
                return "${person.name} 퇴진으로 ${acting.name} ${acting.title}이 현 임기의 대통령 권한을 대행한다."
            }
            if (countryId in setOf("USA", "IDN", "BRA", "TWN")) {
                if (person.id == vicePresidentId && person.id != headOfStateId) {
                    vicePresidentId = null
                    return "${person.name} 부통령이 건강 문제로 퇴진했다. 후임 부통령 인선이 필요하다."
                }
                if (person.id == headOfStateId) {
                    val vice = current().vicePresident
                    if (vice != null && vice.id != headOfStateId) {
                        if (countryId == "USA" && date.plusYears(2) < usTermEnd) usTerms[vice.id] = (usTerms[vice.id] ?: 0) + 1
                        actingSince = null
                        headOfStateId = registry.update(vice.copy(title = record.headOfState.title, since = date, termsServed = 1)).id
                        vicePresidentId = null
                        return "${person.name} 대통령 퇴진으로 ${vice.name} 부통령이 남은 임기를 승계했다."
                    }
                    vicePresidentId = null
                    if (countryId == "BRA" && transition) {
                        val speaker = nominate(null, date)
                        actingPresident(speaker, date)
                        return "${person.name} 대통령 퇴진과 부통령 공석으로 하원의장이 남은 임기 동안 대통령 권한을 대행한다."
                    }
                    if (countryId == "USA" || countryId == "IDN") {
                        return "${person.name} 퇴진으로 대통령과 부통령이 모두 공석이 됐다."
                    }
                }
            }
            val heir = current().crownPrince
            if (heir != null && person.id == headOfStateId) {
                registry.update(heir.copy(title = "국왕", since = date, termsServed = 1))
                headOfStateId = heir.id
                if (headOfGovernmentId == heir.id) headOfGovernmentId = null
                crownPrinceId = registry.generate(countryId, "왕세자", null, date).id
                return "${person.name} 국왕이 물러나 ${heir.name} 왕세자가 왕위를 계승했다. 국왕이 각료회의를 주재한다."
            }
            if (heir != null && person.id == heir.id) {
                val nextHeir = registry.generate(countryId, "왕세자", null, date)
                crownPrinceId = nextHeir.id
                if (headOfGovernmentId == heir.id) headOfGovernmentId = null
                return "${person.name} 왕세자가 물러나 ${nextHeir.name} 왕세자가 후계자로 지명됐다."
            }
            val successor = registry.generate(countryId, person.title, person.party, date)
            when (person.id) {
                headOfStateId -> headOfStateId = successor.id
                vicePresidentId -> vicePresidentId = successor.id
                partyLeaderId -> partyLeaderId = successor.id
                else -> headOfGovernmentId = successor.id
            }
            return "${person.title} ${person.name.josa("이", "가")} 건강 문제로 물러나고 ${successor.name.josa("이", "가")} 뒤를 이었다."
        }
        return null
    }

    /** 쿠데타로 군사평의회가 국가원수와 정부를 모두 장악한다. */
    fun installJunta(date: LocalDate): Person = installCouncil(JUNTA_TITLE, null, date)

    fun installRebelGovernment(
        rebels: String,
        date: LocalDate,
    ): Person = installCouncil("과도정부 수반", rebels, date)

    private fun installCouncil(
        title: String,
        faction: String?,
        date: LocalDate,
    ): Person {
        val leader = registry.generate(countryId, title, faction, date)
        headOfStateId = leader.id
        indirectVacancy = null
        indirectCandidates = { emptyList() }
        council = emptyList()
        actingSince = null
        headOfGovernmentId = null
        partyLeaderId = null
        crownPrinceId = null
        vicePresidentId = null
        return leader
    }

    /** 불신임 등으로 같은 정당 안에서 정부 수반만 바뀐다. */
    fun replaceHead(
        party: String?,
        date: LocalDate,
        title: String? = null,
    ): Person {
        val head = parliamentaryHead()
        val next = registry.generate(countryId, title ?: head.title, party, date)
        if (headOfGovernmentId != null) headOfGovernmentId = next.id else headOfStateId = next.id
        return next
    }

    /** 헌법기관이 이미 지명한 후보의 신원과 소속을 그대로 유지해 정부 수반에 임명한다. */
    fun seatHeadNominee(
        candidate: Person,
        title: String,
        date: LocalDate,
    ): Person {
        val person = requireNotNull(registry.get(candidate.id)).copy(title = title, since = date)
        registry.update(person)
        if (headOfGovernmentId != null) headOfGovernmentId = person.id else headOfStateId = person.id
        return person
    }

    private fun stateHead(): Person {
        val head = requireNotNull(registry.get(headOfStateId))
        val start = actingSince ?: return head
        val name = if (council.isEmpty()) head.name else council.joinToString(" · ") { requireNotNull(registry.get(it)).name }
        return head.copy(name = name, title = ACTING_PRESIDENT, since = start, termsServed = 0)
    }

    private fun executive(): Person =
        if (executiveIsHeadOfState) {
            stateHead()
        } else {
            requireNotNull(
                registry.get(headOfGovernmentId ?: headOfStateId),
            )
        }

    private fun setExecutive(person: Person) {
        if (executiveIsHeadOfState) headOfStateId = person.id else headOfGovernmentId = person.id
    }

    private fun parliamentaryHead(): Person = requireNotNull(registry.get(headOfGovernmentId ?: headOfStateId))

    private companion object {
        const val JUNTA_TITLE = "국가재건평의회 의장"
        const val ACTING_PRESIDENT = "대통령 권한대행"

        /** 임기 제한이 없어도 세 번 연임한 지도자는 다음 선거 전에 당내에서 교체된다. */
        const val RETIREMENT_TERMS = 3
    }
}

internal fun Person.retires(
    date: LocalDate,
    random: Random,
): Boolean {
    val yearly = (MORTALITY_BASE * kotlin.math.exp(MORTALITY_SLOPE * (date.year - born - 30))).coerceAtMost(0.5)
    return random.nextDouble() < yearly / 12.0
}

private const val MORTALITY_BASE = 0.0005
private const val MORTALITY_SLOPE = 0.085
