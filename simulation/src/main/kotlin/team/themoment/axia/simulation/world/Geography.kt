package team.themoment.axia.simulation.world

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 국가 지리 요약(geography.json, `tools/geodata`가 지도 형상에서 계산). */
@Serializable
data class CountryGeography(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val areaKm2: Double,
    val coastal: Boolean,
    val neighbors: List<String>,
)

/** 국가 구조 초기값(structure.json, World Bank·유엔·국가별 통계). 비율은 소수다. */
@Serializable
data class CountryStructure(
    val id: String,
    val tradeOpenness: Double,
    val exportShare: Double,
    val resourceRents: Double,
    val oilRents: Double,
    val armedForces: Double,
    val fertility: Double,
    val birthRate: Double,
    val deathRate: Double,
    val lifeExpectancy: Double,
    val urbanShare: Double,
    val netMigration: Double,
    /** 부가가치 구성(GDP 대비): 농업, 산업(광업·제조·전기·건설), 제조업, 서비스. */
    val agricultureValueAdded: Double,
    val industryValueAdded: Double,
    val manufacturingValueAdded: Double,
    val servicesValueAdded: Double,
    /** 제조업 부가가치 중 중고위 기술, 식품·음료·담배, 섬유·의복·가죽 비중. */
    val techManufacturing: Double,
    val foodManufacturing: Double,
    val textileManufacturing: Double,
    /** WDI 결측을 2023년 국민계정·국제수지로 보완한 지표. 출처 코드별로 지표명을 기록한다. */
    val nationalAccounts: Map<String, Set<String>> = emptyMap(),
    val estimated: Set<String>,
)

class Geography private constructor(
    val countries: Map<String, CountryGeography>,
) {
    fun of(id: String): CountryGeography = countries.getValue(id)

    fun neighbors(
        a: String,
        b: String,
    ): Boolean = b in of(a).neighbors

    /** 두 나라 중심점 사이의 대권 거리(km). */
    fun distanceKm(
        a: String,
        b: String,
    ): Double {
        val p = of(a)
        val q = of(b)
        val lat1 = Math.toRadians(p.latitude)
        val lat2 = Math.toRadians(q.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(q.longitude - p.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceAtMost(1.0)))
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0

        @Serializable
        private data class GeographyFile(
            val sourceNote: String,
            val countries: List<CountryGeography>,
        )

        val default: Geography by lazy {
            val file = Json.decodeFromString<GeographyFile>(resource("geography"))
            Geography(file.countries.associateBy { it.id })
        }
    }
}

class StructureData private constructor(
    val countries: Map<String, CountryStructure>,
) {
    fun of(id: String): CountryStructure = countries.getValue(id)

    companion object {
        @Serializable
        private data class StructureFile(
            val baseYear: Int,
            val source: String,
            val countries: List<CountryStructure>,
        )

        val default: StructureData by lazy {
            StructureData(Json.decodeFromString<StructureFile>(resource("structure")).countries.associateBy { it.id })
        }
    }
}

internal fun resource(name: String): String =
    requireNotNull(Geography::class.java.getResourceAsStream("/world/$name.json")) { "Missing $name.json" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
