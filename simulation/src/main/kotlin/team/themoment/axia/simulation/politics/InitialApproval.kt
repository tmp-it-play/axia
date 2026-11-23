package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 2024년 1월 정부·지도자 지지율(approval.json). 공개 여론조사가 없는 나라는 [estimated]다. */
@Serializable
data class InitialApproval(
    val approval: Double,
    val estimated: Boolean,
    val source: String,
) {
    init {
        require(approval in 0.0..100.0 && source.isNotBlank())
    }

    companion object {
        @Serializable
        private data class ApprovalFile(
            val sourceNote: String,
            val countries: Map<String, InitialApproval>,
        )

        private val records by lazy {
            val text =
                requireNotNull(InitialApproval::class.java.getResourceAsStream("/world/approval.json")) { "Missing approval.json" }
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            Json.decodeFromString<ApprovalFile>(text).countries
        }

        fun forCountry(id: String): InitialApproval = requireNotNull(records[id]) { "Missing approval for $id" }
    }
}
