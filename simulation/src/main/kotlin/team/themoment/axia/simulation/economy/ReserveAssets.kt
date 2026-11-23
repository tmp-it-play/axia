package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.resource

/** 2023년 말 국가별 준비자산(금 제외)의 출처. IMF 국제투자대조표는 금을 빼거나 SDR·IMF 포지션·기타 준비자산을 합산한다. */
enum class ReserveSource(
    val label: String,
) {
    WDI_YEAR_PROXY("세계은행 WDI · 2023년 연간 대용치"),
    IL_DECEMBER("IMF 국제유동성 · 2023년 12월"),
    IIP_Q4_MINUS_GOLD("IMF 국제투자대조표 · 2023년 4분기 말 · 금 차감"),
    IIP_Q4_COMPONENTS("IMF 국제투자대조표 · 2023년 4분기 말 · 금 외 구성항목 합계"),
    IIP_YEAR_MINUS_GOLD("IMF 국제투자대조표 · 2023년 말 · 금 차감"),
    IIP_YEAR_COMPONENTS("IMF 국제투자대조표 · 2023년 말 · 금 외 구성항목 합계"),
}

@Serializable
internal data class InitialReserveAssets(
    val usd: Double,
    val source: ReserveSource,
) {
    init {
        require(usd.isFinite() && usd >= 0.0)
    }
}

@Serializable
private data class ReserveFile(
    val year: Int,
    val unit: String,
    val countries: Map<String, InitialReserveAssets?>,
) {
    init {
        require(year == 2023 && unit == "USD")
    }
}

internal object ReserveAssets {
    private val countries by lazy { Json.decodeFromString<ReserveFile>(resource("reserve_assets")).countries }

    fun of(id: String): InitialReserveAssets? = countries.getValue(id)
}
