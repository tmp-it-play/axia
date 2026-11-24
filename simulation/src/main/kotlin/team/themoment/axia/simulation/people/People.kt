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
)

/**
 * 국가원수와 정부 수반. 같은 사람이면 [headOfGovernment]가 null이다.
 * @property parliamentary 의원내각제처럼 정부 수반이 실권자인지. 아니면 국가원수(대통령, 총서기, 최고지도자)가 실권자다.
 */
data class Leadership(
    val headOfState: Person,
    val headOfGovernment: Person?,
    val parliamentary: Boolean,
) {
    /** 실권을 쥔 사람. */
    val leader: Person get() = if (parliamentary) headOfGovernment ?: headOfState else headOfState

    fun all(): List<Person> = listOfNotNull(headOfState, headOfGovernment)
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
        val born = since.year - random.nextInt(MIN_LEADER_AGE, MAX_LEADER_AGE + 1)
        return add(Person(nextId, name, title, party, since, termsServed = 1, generated = true, born = born))
    }

    internal fun update(person: Person): Person = person.also { persons[it.id] = it }

    fun rename(
        id: Int,
        name: String,
    ): Person? {
        val trimmed = name.trim()
        val person = persons[id] ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return null
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
        val MALE_TITLES = setOf("최고지도자")
        const val MAX_ATTEMPTS = 20
        const val MAX_NAME_LENGTH = 30
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

    /** 선거로 뽑는 행정부 수반이 국가원수인지(이란은 대통령이 정부 수반이다). */
    private val executiveIsHeadOfState = record.headOfGovernment?.title != executiveTitle || executiveTitle == null

    private val initialIds = listOfNotNull(headOfStateId, headOfGovernmentId)

    /**
     * 정부 표기. 시작 지도자가 그대로면 데이터 문구를 쓴다. 바뀌었으면 실권자와 정당을 쓰고,
     * 대통령과 총리가 함께 실권을 나누는 [semiPresidential] 체제는 두 사람을 함께 쓴다.
     */
    fun governmentLabel(
        initial: String,
        semiPresidential: Boolean,
    ): String {
        val now = current()
        if (now.all().map { it.id } == initialIds || (parliamentary && now.leader.id == initialIds.last())) return initial
        val head = now.headOfGovernment
        if ((semiPresidential || !executiveIsHeadOfState) && head != null) return "${now.headOfState.name} · ${head.name}"
        val leader = now.leader
        return "${leader.name} · ${leader.party ?: leader.title}"
    }

    fun current(): Leadership =
        Leadership(requireNotNull(registry.get(headOfStateId)), headOfGovernmentId?.let { requireNotNull(registry.get(it)) }, parliamentary)

    /** 연임 제한에 걸려 다음 행정부 선거에 나설 수 없는지. */
    fun termLimited(limit: Int?): Boolean = limit != null && executive().termsServed >= limit

    fun executiveIncumbent(): Person = executive()

    fun eligibleIncumbent(limit: Int?): Person? = executive().takeIf { !termLimited(limit) && it.termsServed < RETIREMENT_TERMS }

    fun nominate(
        party: String?,
        date: LocalDate,
    ): Person = registry.generate(countryId, "후보", party, date)

    internal fun changeAffiliation(
        id: Int,
        party: String,
    ): Person {
        val person = requireNotNull(registry.get(id))
        return registry.update(person.copy(party = party))
    }

    fun seatPresident(
        candidate: Person,
        date: LocalDate,
    ) {
        val incumbent = executive()
        val nominee = requireNotNull(registry.get(candidate.id))
        val elected =
            if (incumbent.id == nominee.id) {
                incumbent.copy(termsServed = incumbent.termsServed + 1)
            } else {
                nominee.copy(title = incumbent.title, since = date, termsServed = 1)
            }
        setExecutive(registry.update(elected))
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
    ): String? {
        for (person in current().all()) {
            if (!person.retires(date, random)) continue
            val successor = registry.generate(countryId, person.title, person.party, date)
            if (person.id == headOfStateId) headOfStateId = successor.id else headOfGovernmentId = successor.id
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
        headOfGovernmentId = null
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

    private fun executive(): Person =
        requireNotNull(registry.get(if (executiveIsHeadOfState) headOfStateId else headOfGovernmentId ?: headOfStateId))

    private fun setExecutive(person: Person) {
        if (executiveIsHeadOfState) headOfStateId = person.id else headOfGovernmentId = person.id
    }

    private fun parliamentaryHead(): Person = requireNotNull(registry.get(headOfGovernmentId ?: headOfStateId))

    private companion object {
        const val JUNTA_TITLE = "국가재건평의회 의장"

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
