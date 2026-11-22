package team.themoment.axia.simulation.world

enum class CountryKind {
    STATE,
    UNCLAIMED,
}

data class Country(
    val id: String,
    val name: String,
    val kind: CountryKind,
)

/** 주권국에 속하지만 따로 구분되는 영역. 예: 그린란드, 홍콩. */
data class Territory(
    val id: String,
    val name: String,
    val ownerId: String,
)

data class Region(
    val id: String,
    val name: String,
    val countryId: String,
    val territoryId: String?,
)

class World(
    countries: List<Country>,
    territories: List<Territory>,
    regions: List<Region>,
) {
    val countries: Map<String, Country> = countries.associateBy(Country::id)
    val territories: Map<String, Territory> = territories.associateBy(Territory::id)
    val regions: Map<String, Region> = regions.associateBy(Region::id)

    init {
        require(this.countries.size == countries.size) { "Duplicate country id" }
        require(this.territories.size == territories.size) { "Duplicate territory id" }
        require(this.regions.size == regions.size) { "Duplicate region id" }
        territories.forEach { territory ->
            require(territory.ownerId in this.countries) { "Territory ${territory.id} has unknown owner ${territory.ownerId}" }
        }
        regions.forEach { region ->
            require(region.countryId in this.countries) { "Region ${region.id} has unknown country ${region.countryId}" }
            region.territoryId?.let { territoryId ->
                require(this.territories[territoryId]?.ownerId == region.countryId) {
                    "Region ${region.id} has territory $territoryId not owned by ${region.countryId}"
                }
            }
        }
    }

    fun country(id: String): Country = countries.getValue(id)

    fun territory(id: String): Territory = territories.getValue(id)

    fun region(id: String): Region = regions.getValue(id)
}
