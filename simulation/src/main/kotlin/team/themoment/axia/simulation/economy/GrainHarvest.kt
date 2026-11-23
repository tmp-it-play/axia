package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Month
import kotlin.math.exp

/** 월 수확량과 월말 이월량을 그 나라 연평균 월 생산의 배수로 환산한 달력. */
internal class GrainHarvest private constructor(
    percent: List<Int>,
) {
    init {
        require(percent.size == Month.entries.size && percent.all { it in 0..100 } && percent.sum() == 100) {
            "Grain harvest shares must cover twelve months and total 100 percent"
        }
    }

    private val factors = DoubleArray(percent.size) { percent[it] * percent.size / 100.0 }

    // 매달 평균 생산량만큼 출하할 때의 누적 초과수확. 연중 최저 재고를 0에 맞추면 연말·연초가 이어진다.
    private val carry = DoubleArray(percent.size)

    init {
        var cumulative = 0
        for (i in percent.indices) {
            cumulative += percent[i] * percent.size - 100
            carry[i] = cumulative / 100.0
        }
        val trough = carry.min()
        for (i in carry.indices) carry[i] -= trough
    }

    fun factor(month: Month): Double = factors[month.ordinal]

    fun carryAfter(month: Month): Double = carry[month.ordinal]

    companion object {
        private val calendars: Map<String, GrainHarvest> by lazy {
            val file =
                requireNotNull(GrainHarvest::class.java.getResourceAsStream("/world/grain_harvest.json")) {
                    "Missing grain_harvest.json"
                }.bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<HarvestFile>(it.readText()) }
            file.countries.mapValues { (_, row) -> GrainHarvest(row.percent) }
        }

        fun forCountry(id: String): GrainHarvest? = calendars[id]
    }
}

@Serializable
private data class HarvestFile(
    val modelNote: String,
    val countries: Map<String, HarvestRow>,
)

@Serializable
private data class HarvestRow(
    val percent: List<Int>,
    val sourceNote: String,
)

/** 연중 소비를 위해 수확기에 쌓아 두는 물량. 농가 창고와 소비국 유통 창고에 나눠 보관한다. */
internal class HarvestStocks(
    private val baseline: List<CommodityBaseline>,
    openingMonth: Month,
) {
    private val baseDemand = baseline.sumOf { it.demand }
    private val domestic =
        Month.entries.map { month ->
            DoubleArray(baseline.size) { i -> baseline[i].production * (baseline[i].harvest?.carryAfter(month) ?: 0.0) }
        }
    private val global = domestic.map { it.sum() }
    private val openingStock = global[openingMonth.ordinal]

    fun stock(
        country: Int,
        month: Month,
    ): Double {
        val supplyStock = domestic[month.ordinal][country]
        val consumptionShare = if (baseDemand > 0.0) baseline[country].demand / baseDemand else 0.0
        return (1.0 - DISTRIBUTOR_SHARE) * supplyStock + DISTRIBUTOR_SHARE * global[month.ordinal] * consumptionShare
    }

    fun demandScale(input: List<CommodityInput>): Double =
        if (baseDemand > 0.0) baseline.indices.sumOf { baseline[it].demand * input[it].demandFactor } / baseDemand else 0.0

    fun stockChange(month: Month): Double = global[month.ordinal] - global[month.minus(1).ordinal]

    fun priceFactor(month: Month): Double =
        if (baseDemand > 0.0) exp(-STOCK_PRICE_RESPONSE * (global[month.ordinal] - openingStock) / baseDemand) else 1.0

    private companion object {
        const val DISTRIBUTOR_SHARE = 0.20
        const val STOCK_PRICE_RESPONSE = 0.03
    }
}
