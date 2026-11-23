package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * V-Dem 체제 분류.
 * @property incumbentBias 선거에서 집권 세력이 받는 가점(득표 가중치의 로그 값). 권위주의일수록 선거가 집권 세력에 기운다.
 */
enum class Regime(
    val label: String,
    val incumbentBias: Double,
) {
    CLOSED_AUTOCRACY("폐쇄형 권위주의", 1.0),
    ELECTORAL_AUTOCRACY("선거 권위주의", 0.5),
    ELECTORAL_DEMOCRACY("선거 민주주의", 0.0),
    LIBERAL_DEMOCRACY("자유 민주주의", 0.0),
    ;

    /** 권위주의 체제에서는 지지율이 선거 결과에 덜 반영된다. */
    val autocratic: Boolean get() = this == CLOSED_AUTOCRACY || this == ELECTORAL_AUTOCRACY
}

@Serializable
data class PoliticalProfile(
    val id: String,
    val initialGovernment: String,
    val reviewBody: String,
    val regime: Regime,
    val legislativeConstraints: Double,
    val civilParticipation: Double,
) {
    init {
        require(initialGovernment.isNotBlank() && reviewBody.isNotBlank())
        require(legislativeConstraints in 0.0..1.0 && civilParticipation in 0.0..1.0)
    }

    val legislature: Legislature? get() = chambers.getValue(id).chamber

    val elections: ElectionRules get() = electionRules.getValue(id)

    @Serializable
    private data class ChamberRecord(
        val chamber: Legislature?,
        /** 국가별 의석 기준일, 원자료 및 보정 내역. 데이터 전용 주석이다. */
        val sourceNote: String,
    ) {
        init {
            require(sourceNote.isNotBlank())
        }
    }

    companion object {
        private fun resource(name: String): String =
            requireNotNull(PoliticalProfile::class.java.getResourceAsStream("/world/$name.json"))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

        private val chambers by lazy { Json.decodeFromString<Map<String, ChamberRecord>>(resource("parliaments")) }

        private val electionRules by lazy { Json.decodeFromString<Map<String, ElectionRules>>(resource("elections")) }

        private val profiles by lazy {
            val entries = Json.decodeFromString<List<PoliticalProfile>>(resource("politics"))
            require(entries.map { it.id }.toSet() == chambers.keys) { "Parliaments must cover all political profiles" }
            require(entries.map { it.id }.toSet() == electionRules.keys) { "Elections must cover all political profiles" }
            entries.forEach { profile ->
                val rule = electionRules.getValue(profile.id).legislature
                if (rule != null) {
                    val hasChamber = chambers.getValue(profile.id).chamber != null
                    require(hasChamber != (rule.openingContest != null)) {
                        "${profile.id} needs either a sitting chamber or a pre-election contest"
                    }
                }
            }
            entries.associateBy { it.id }.also { require(it.size == entries.size) { "Duplicate political profile" } }
        }

        fun forCountry(id: String): PoliticalProfile = requireNotNull(profiles[id]) { "Missing political profile for $id" }
    }
}
