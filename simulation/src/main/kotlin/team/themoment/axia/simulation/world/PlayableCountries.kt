package team.themoment.axia.simulation.world

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.economy.EconomyData

/** 플레이어가 고를 수 있는 국가 목록. 순서는 목록 파일 순서다. */
object PlayableCountries {
    private const val DEFAULT_LIST = "/world/playable.json"

    fun loadDefault(
        world: World,
        economy: EconomyData,
    ): List<Country> {
        val text =
            requireNotNull(PlayableCountries::class.java.getResourceAsStream(DEFAULT_LIST)) { "Missing $DEFAULT_LIST" }
                .use { it.readBytes().decodeToString() }
        val ids = Json.decodeFromString<PlayableFile>(text).countries
        require(ids.toSet().size == ids.size) { "Duplicate playable country" }
        return ids.map { id ->
            val country = requireNotNull(world.countries[id]) { "Unknown playable country $id" }
            require(
                country.kind == CountryKind.STATE && id in economy.countries,
            ) { "Playable country $id must be a state with economy data" }
            country
        }
    }

    @Serializable
    private data class PlayableFile(
        val countries: List<String>,
    )
}
