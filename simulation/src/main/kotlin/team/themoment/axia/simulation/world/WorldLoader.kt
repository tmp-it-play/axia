package team.themoment.axia.simulation.world

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WorldLoader {
    private const val DEFAULT_WORLD = "/world/world.json"

    fun loadDefault(): World {
        val text =
            requireNotNull(WorldLoader::class.java.getResourceAsStream(DEFAULT_WORLD)) { "Missing $DEFAULT_WORLD" }
                .use { it.readBytes().decodeToString() }
        return parse(text)
    }

    fun parse(text: String): World {
        val file = Json.decodeFromString<WorldFile>(text)
        return World(
            countries = file.countries.map { Country(it.id, it.name, it.kind) },
            territories = file.territories.map { Territory(it.id, it.name, it.owner) },
            regions = file.regions.map { Region(it.id, it.name, it.country, it.territory) },
        )
    }

    @Serializable
    private data class WorldFile(
        val countries: List<CountryEntry>,
        val territories: List<TerritoryEntry>,
        val regions: List<RegionEntry>,
    )

    @Serializable
    private data class CountryEntry(
        val id: String,
        val name: String,
        val kind: CountryKind,
    )

    @Serializable
    private data class TerritoryEntry(
        val id: String,
        val name: String,
        val owner: String,
    )

    @Serializable
    private data class RegionEntry(
        val id: String,
        val name: String,
        val country: String,
        val territory: String? = null,
    )
}
