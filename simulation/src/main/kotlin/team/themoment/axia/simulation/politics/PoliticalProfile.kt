package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Regime(
    val label: String,
) {
    CLOSED_AUTOCRACY("폐쇄형 권위주의"),
    ELECTORAL_AUTOCRACY("선거 권위주의"),
    ELECTORAL_DEMOCRACY("선거 민주주의"),
    LIBERAL_DEMOCRACY("자유 민주주의"),
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

        private val profiles by lazy {
            val entries = Json.decodeFromString<List<PoliticalProfile>>(resource("politics"))
            require(entries.map { it.id }.toSet() == chambers.keys) { "Parliaments must cover all political profiles" }
            entries.associateBy { it.id }.also { require(it.size == entries.size) { "Duplicate political profile" } }
        }

        fun forCountry(id: String): PoliticalProfile = requireNotNull(profiles[id]) { "Missing political profile for $id" }
    }
}
