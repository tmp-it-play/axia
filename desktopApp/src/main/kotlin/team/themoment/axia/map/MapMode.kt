package team.themoment.axia.map

import androidx.compose.ui.graphics.Color
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.RegionSnapshot
import team.themoment.axia.simulation.world.StructureData

/** 지도 색칠에 쓰는 자료 묶음. */
class MapData(
    val economy: EconomySnapshot,
    val world: WorldSnapshot,
    val nation: NationSnapshot,
) {
    val regions = nation.regions.associateBy(RegionSnapshot::regionId)

    /** 지지율. 플레이어 국가와 플레이 가능 국가에만 있다. */
    fun approval(countryId: String): Double? = if (countryId == nation.countryId) nation.approval else world.politics[countryId]?.approval

    /** 플레이어와의 관계. 플레이어 국가는 null. */
    fun relation(countryId: String): Double? = world.diplomacy.relations[countryId]

    fun power(countryId: String): Double? = world.military.forces[countryId]?.power

    /** 0 = 평화, 1 = 내전, 2 = 전쟁 중, 3 = 플레이어와 전쟁 중. */
    fun conflict(countryId: String): Double {
        val wars = world.military.wars.filter { it.ceasefire == null }
        val involved = wars.filter { countryId in it.sideA || countryId in it.sideB }
        val player = nation.countryId
        return when {
            involved.any { war ->
                (countryId in war.sideA && player in war.sideB) || (countryId in war.sideB && player in war.sideA)
            } -> 3.0

            involved.isNotEmpty() -> 2.0

            world.military.civilWars.any { it.country == countryId && it.ended == null } -> 1.0

            else -> 0.0
        }
    }
}

/** 값을 구간으로 나눠 색칠하는 단계 구분도 척도. [thresholds]보다 하나 많은 [colors]와 [labels]를 가진다. */
class ChoroplethScale(
    val thresholds: List<Double>,
    val colors: List<Color>,
    val labels: List<String>,
    /** 값이 없으면 null(자료 없음 색). */
    val value: (MapData, String) -> Double?,
) {
    init {
        require(colors.size == thresholds.size + 1 && labels.size == colors.size)
    }

    fun colorOf(
        data: MapData,
        countryId: String,
    ): Color {
        val measured = value(data, countryId) ?: return MapPalette.noData
        return colorOf(measured)
    }

    fun colorOf(measured: Double): Color = colors[thresholds.count { measured >= it }]
}

private fun economic(field: (EconomyIndicators) -> Double): (MapData, String) -> Double? =
    { data, id -> data.economy.countries[id]?.let(field) }

enum class MapMode(
    val label: String,
    val scale: ChoroplethScale?,
    private val regionValue: ((RegionSnapshot) -> Double)? = null,
) {
    POLITICAL("정치", null),
    GDP_PER_CAPITA(
        "1인당 GDP",
        ChoroplethScale(
            thresholds = listOf(1_000.0, 4_000.0, 13_000.0, 30_000.0, 60_000.0),
            colors = MapPalette.wealth,
            labels = listOf("1천 달러 미만", "1천~4천 달러", "4천~1.3만 달러", "1.3만~3만 달러", "3만~6만 달러", "6만 달러 이상"),
            value = economic(EconomyIndicators::gdpPerCapita),
        ),
    ),
    GROWTH(
        "경제성장률",
        ChoroplethScale(
            thresholds = listOf(-0.02, 0.0, 0.02, 0.04, 0.06),
            colors = MapPalette.diverging,
            labels = listOf("-2% 미만", "-2~0%", "0~2%", "2~4%", "4~6%", "6% 이상"),
            value = economic(EconomyIndicators::growth),
        ),
    ),
    INFLATION(
        "물가상승률",
        ChoroplethScale(
            thresholds = listOf(0.02, 0.04, 0.07, 0.15, 0.5),
            colors = MapPalette.severity,
            labels = listOf("2% 미만", "2~4%", "4~7%", "7~15%", "15~50%", "50% 이상"),
            value = economic(EconomyIndicators::inflation),
        ),
    ),
    UNEMPLOYMENT(
        "실업률",
        ChoroplethScale(
            thresholds = listOf(0.03, 0.05, 0.08, 0.12, 0.2),
            colors = MapPalette.severity,
            labels = listOf("3% 미만", "3~5%", "5~8%", "8~12%", "12~20%", "20% 이상"),
            value = economic(EconomyIndicators::unemployment),
        ),
        regionValue = { it.economy.unemployment },
    ),
    PUBLIC_SAFETY(
        "치안",
        ChoroplethScale(
            thresholds = listOf(85.0, 95.0, 100.0, 105.0, 115.0),
            colors = MapPalette.severity,
            labels = listOf("범죄 지수 85 미만", "85~95", "95~100", "100~105", "105~115", "115 이상"),
            value = { data, id ->
                data.nation.social.crime
                    .takeIf { id == data.nation.countryId }
            },
        ),
        regionValue = RegionSnapshot::crime,
    ),
    DEBT(
        "국가부채",
        ChoroplethScale(
            thresholds = listOf(0.3, 0.6, 0.9, 1.2, 2.0),
            colors = MapPalette.severity,
            labels = listOf("GDP의 30% 미만", "30~60%", "60~90%", "90~120%", "120~200%", "200% 이상"),
            value = economic(EconomyIndicators::debtRatio),
        ),
    ),
    POLICY_RATE(
        "기준금리",
        ChoroplethScale(
            thresholds = listOf(0.02, 0.04, 0.07, 0.12, 0.25),
            colors = MapPalette.severity,
            labels = listOf("2% 미만", "2~4%", "4~7%", "7~12%", "12~25%", "25% 이상"),
            value = economic(EconomyIndicators::policyRate),
        ),
    ),
    APPROVAL(
        "민심",
        ChoroplethScale(
            thresholds = listOf(30.0, 40.0, 47.0, 53.0, 60.0),
            colors = MapPalette.diverging,
            labels = listOf("지지율 30% 미만", "30~40%", "40~47%", "47~53%", "53~60%", "60% 이상"),
            value = { data, id -> data.approval(id) },
        ),
        regionValue = RegionSnapshot::approval,
    ),
    RELATIONS(
        "외교 관계",
        ChoroplethScale(
            thresholds = listOf(-50.0, -15.0, 15.0, 40.0, 70.0),
            colors = MapPalette.diverging,
            labels = listOf("적대 (-50 미만)", "냉랭", "중립", "우호", "긴밀", "동맹급 (70 이상)"),
            value = { data, id -> data.relation(id) },
        ),
    ),
    MILITARY(
        "군사력",
        ChoroplethScale(
            thresholds = listOf(0.5, 2.0, 5.0, 12.0, 30.0),
            colors = MapPalette.wealth,
            labels = listOf("최강국 대비 0.5 미만", "0.5~2", "2~5", "5~12", "12~30", "30 이상"),
            value = { data, id -> data.power(id) },
        ),
    ),
    RESOURCES(
        "자원",
        ChoroplethScale(
            thresholds = listOf(0.01, 0.03, 0.08, 0.15, 0.3),
            colors = MapPalette.wealth,
            labels = listOf("자원 지대 GDP의 1% 미만", "1~3%", "3~8%", "8~15%", "15~30%", "30% 이상"),
            value = { _, id -> StructureData.default.countries[id]?.resourceRents },
        ),
    ),
    CONFLICT(
        "전쟁",
        ChoroplethScale(
            thresholds = listOf(0.5, 1.5, 2.5),
            colors = listOf(MapPalette.peace, MapPalette.severity[2], MapPalette.severity[4], MapPalette.diverging[0]),
            labels = listOf("평화", "내전", "전쟁 중", "우리나라와 교전"),
            value = { data, id -> data.conflict(id) },
        ),
    ),
    ;

    /** 플레이어 국가를 지역 단위로 칠할 수 있는 모드면 지역 색. */
    fun regionColor(
        regionId: String,
        data: MapData,
    ): Color? {
        val measure = regionValue ?: return null
        val region = data.regions[regionId] ?: return null
        return requireNotNull(scale).colorOf(measure(region))
    }

    fun colorOf(
        fill: CountryFill,
        data: MapData,
    ): Color {
        if (fill.unclaimed) return MapPalette.unclaimed
        val scale = scale ?: return MapPalette.countries[fill.colorIndex]
        return scale.colorOf(data, fill.countryId)
    }
}
