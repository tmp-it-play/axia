package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 모든 주권국의 2023년 V-Dem 체제 분류(regimes.json). 자료가 없는 소국은 null이다. */
object Regimes {
    @Serializable
    private data class RegimeFile(
        val sourceNote: String,
        val regimes: Map<String, Regime>,
    )

    private val regimes by lazy {
        val text =
            requireNotNull(Regimes::class.java.getResourceAsStream("/world/regimes.json")) { "Missing regimes.json" }
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        Json.decodeFromString<RegimeFile>(text).regimes
    }

    fun of(countryId: String): Regime? = regimes[countryId]
}
