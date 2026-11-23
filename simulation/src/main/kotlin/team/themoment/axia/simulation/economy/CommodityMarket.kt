package team.themoment.axia.simulation.economy

import java.time.Month
import java.time.YearMonth
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

enum class Commodity(
    val label: String,
    val unit: String,
) {
    GRAIN("곡물", "톤"),
    CRUDE_OIL("원유", "배럴"),
}

/** 국가·상품별 기준 연도의 월평균 실물 생산·수요. */
internal data class CommodityBaseline(
    val production: Double,
    val demand: Double,
    val productionReported: Boolean,
    val harvest: GrainHarvest?,
)

/** 이번 달 생산 능력과 수요의 기준 대비 배율. */
internal data class CommodityInput(
    val productionFactor: Double,
    val demandFactor: Double,
)

data class CommodityBalance(
    val openingStock: Double,
    val production: Double,
    val demand: Double,
    val consumption: Double,
    val imports: Double,
    val exports: Double,
    val closingStock: Double,
    /** 이달 평년 수확량 / 연평균 월 생산량. 수확 달력이 없는 경우에는 null. */
    val harvestFactor: Double? = null,
)

data class CommodityShipment(
    val exporter: String,
    val importer: String,
    val quantity: Double,
)

data class CommodityQuote(
    val priceIndex: Double,
    val yearlyLogChange: Double,
    val countries: Map<String, CommodityBalance>,
    val shipments: List<CommodityShipment>,
    /** 기준 연도에 별도 생산 통계가 없어 모형에서 생산을 배정하지 않은 국가. */
    val unreportedProduction: Set<String>,
    /** 월초에 정산한 직전 달. 시작 재고 스냅숏에는 결산이 없다. */
    val period: YearMonth?,
)

/** 품목별로 한 달의 실물 거래를 결산한다. 배열의 국가 순서는 무역망과 같다. */
internal class CommodityMarket(
    private val ids: List<String>,
    baseline: Map<Commodity, List<CommodityBaseline>>,
    private val baseShare: DoubleArray,
    private val startMonth: YearMonth,
) {
    private val count = ids.size

    init {
        require(count > 0 && ids.distinct().size == count)
        require(baseShare.size == count * count && baseShare.all { it.isFinite() && it >= 0.0 })
        require(Commodity.entries.all { baseline.getValue(it).size == count })
    }

    private val markets = Commodity.entries.associateWith { Book(it, baseline.getValue(it)) }

    // 먼저 거래할 수출국별 주요 상대. 남은 물량은 그 밖의 접근 가능한 상대에게도 보낸다.
    private val preferred =
        Array(count) { seller ->
            ids.indices
                .filter { it != seller && baseShare[seller * count + it] > 0.0 }
                .sortedByDescending { baseShare[seller * count + it] }
                .take(PREFERRED_PARTNERS)
                .toIntArray()
        }

    /** 경로 배율은 시작 시점 대비 협정·제재 변화다. 1은 평상시, 0은 수송 차단이다. */
    fun advanceMonth(
        inputs: Map<Commodity, List<CommodityInput>>,
        routeFactors: DoubleArray,
        period: YearMonth,
        capacity: DoubleArray,
    ) {
        require(capacity.size == count * count && capacity.all { it in 0.0..1.0 })
        require(routeFactors.size == count * count && routeFactors.all { it.isFinite() && it >= 0.0 })
        markets.forEach { (commodity, book) -> book.advance(inputs.getValue(commodity), routeFactors, period, capacity) }
    }

    fun snapshot(): Map<Commodity, CommodityQuote> = markets.mapValues { it.value.quote }

    private inner class Book(
        commodity: Commodity,
        private val baseline: List<CommodityBaseline>,
    ) {
        // 평시 비축량과 가격 탄력성은 게임 가정이다.
        private val reserveMonths = if (commodity == Commodity.GRAIN) 2.0 else 1.5
        private val supplyElasticity = if (commodity == Commodity.GRAIN) 0.28 else 0.20
        private val demandElasticity = if (commodity == Commodity.GRAIN) 0.12 else 0.18
        private val harvest = if (commodity == Commodity.GRAIN) HarvestStocks(baseline, startMonth.month.minus(1)) else null
        private val stocks =
            DoubleArray(count) { baseline[it].demand * reserveMonths + (harvest?.stock(it, startMonth.month.minus(1)) ?: 0.0) }
        private val history = ArrayDeque(List(12) { BASE_PRICE * seasonalPrice(startMonth.month.plus(it.toLong())) })
        private val unreported = ids.indices.filter { !baseline[it].productionReported }.mapTo(mutableSetOf()) { ids[it] }
        var quote =
            CommodityQuote(
                BASE_PRICE,
                0.0,
                ids.indices.associate { i ->
                    ids[i] to CommodityBalance(stocks[i], 0.0, 0.0, 0.0, 0.0, 0.0, stocks[i])
                },
                emptyList(),
                unreported,
                period = null,
            )
            private set

        init {
            require(baseline.all { it.production.isFinite() && it.production >= 0.0 && it.demand.isFinite() && it.demand >= 0.0 })
        }

        private fun seasonalPrice(month: Month): Double = harvest?.priceFactor(month) ?: 1.0

        fun advance(
            inputs: List<CommodityInput>,
            routes: DoubleArray,
            period: YearMonth,
            capacity: DoubleArray,
        ) {
            require(inputs.size == count)
            val month = period.month
            val previousSeason = seasonalPrice(month.minus(1))
            // 평년 수확기의 가격 등락을 제외한 신호로 다음 생산·수요를 조정한다.
            val price = quote.priceIndex / (BASE_PRICE * previousSeason)
            val supplyResponse = price.pow(supplyElasticity)
            val demandResponse = price.pow(-demandElasticity)
            val production =
                DoubleArray(count) { i ->
                    baseline[i].production * inputs[i].productionFactor * supplyResponse * (baseline[i].harvest?.factor(month) ?: 1.0)
                }
            val demand = DoubleArray(count) { baseline[it].demand * inputs[it].demandFactor * demandResponse }
            require(production.all { it.isFinite() && it >= 0.0 } && demand.all { it.isFinite() && it >= 0.0 })
            val seasonalScale = harvest?.demandScale(inputs) ?: 0.0
            val seasonalStocks = DoubleArray(count) { (harvest?.stock(it, month) ?: 0.0) * seasonalScale }
            val targetStocks = DoubleArray(count) { demand[it] * reserveMonths + seasonalStocks[it] }
            val offer = DoubleArray(count)
            val request = DoubleArray(count)
            for (i in ids.indices) {
                // 기초 재고를 포함하여 국내 수요와 목표 재고를 채운 뒤 남는 물량을 수출한다.
                val surplus = stocks[i] + production[i] - demand[i] - targetStocks[i]
                offer[i] = surplus.coerceAtLeast(0.0)
                request[i] = (-surplus).coerceAtLeast(0.0)
            }
            val shipments = mutableMapOf<Int, Double>()
            if (harvest != null) {
                // 다른 나라의 창고를 보충하기 전에 기초 재고로도 채우지 못하는 이달 곡물 소비를 공급한다.
                val immediateNeed = DoubleArray(count) { (demand[it] - stocks[it] - production[it]).coerceAtLeast(0.0) }
                if (immediateNeed.any { it > 0.0 }) {
                    for (i in ids.indices) request[i] -= immediateNeed[i]
                    match(offer, immediateNeed, routes, capacity, shipments)
                }
            }
            match(offer, request, routes, capacity, shipments)
            val imports = DoubleArray(count)
            val exports = DoubleArray(count)
            shipments.forEach { (route, quantity) ->
                exports[route / count] += quantity
                imports[route % count] += quantity
            }

            val balances =
                ids.indices.associate { i ->
                    val opening = stocks[i]
                    val available = opening + production[i] + imports[i] - exports[i]
                    val consumption = minOf(demand[i], available.coerceAtLeast(0.0))
                    // 판로·저장 공간이 없으면 수확·채굴을 줄인다. 이미 생산된 재고는 폐기하지 않는다.
                    val storage = MAX_STORAGE_MONTHS * maxOf(demand[i], baseline[i].demand, baseline[i].production) + seasonalStocks[i]
                    val producible = (storage + consumption + exports[i] - opening - imports[i]).coerceAtLeast(0.0)
                    production[i] = minOf(production[i], producible)
                    val closing = (opening + production[i] + imports[i] - exports[i] - consumption).coerceAtLeast(0.0)
                    stocks[i] = closing
                    ids[i] to
                        CommodityBalance(
                            opening,
                            production[i],
                            demand[i],
                            consumption,
                            imports[i],
                            exports[i],
                            closing,
                            baseline[i].harvest?.factor(month),
                        )
                }
            val totalDemand = demand.sum()
            val priceChange =
                if (totalDemand > 0.0) {
                    // 이달 평년 수확으로 축적·방출할 물량은 미예상 공급 부족에서 제외한다.
                    val plannedStockChange = (harvest?.stockChange(month) ?: 0.0) * seasonalScale
                    val imbalance = (totalDemand - production.sum() + plannedStockChange) / totalDemand
                    val targetStock = targetStocks.sum()
                    val reserveGap = 1.0 - stocks.sum() / targetStock
                    // 일부 수입국이 목표 재고에 못 미치면 다른 나라의 잉여로 이를 온전히 상쇄할 수 없다.
                    val localShortfall =
                        ids.indices.sumOf { (targetStocks[it] - stocks[it]).coerceAtLeast(0.0) } / targetStock
                    val stranded = localShortfall - reserveGap.coerceAtLeast(0.0)
                    val unmet = balances.values.sumOf { it.demand - it.consumption } / totalDemand
                    PRICE_SPEED * (imbalance + RESERVE_PRESSURE * (reserveGap + stranded) + unmet)
                } else {
                    if (stocks.sum() > 0.0) -MAX_MONTHLY_CHANGE else 0.0
                }
            val monthlyFactor = exp(priceChange.coerceIn(-MAX_MONTHLY_CHANGE, MAX_MONTHLY_CHANGE))
            val index = (quote.priceIndex * monthlyFactor * seasonalPrice(month) / previousSeason).coerceIn(MIN_PRICE, MAX_PRICE)
            val yearAgo = history.removeFirst()
            history.addLast(index)
            quote =
                CommodityQuote(
                    index,
                    ln(index / yearAgo),
                    balances,
                    shipments.map { (route, quantity) -> CommodityShipment(ids[route / count], ids[route % count], quantity) },
                    unreported,
                    period,
                )
        }
    }

    private fun match(
        offer: DoubleArray,
        request: DoubleArray,
        routes: DoubleArray,
        capacity: DoubleArray,
        shipments: MutableMap<Int, Double>,
    ) {
        fun ship(
            seller: Int,
            buyer: Int,
            planned: Double,
        ) {
            val route = seller * count + buyer
            // 제재로 선적하지 못한 계약 물량은 수출국 재고에 남고 이번 달 수입 실적에서도 빠진다.
            val shipped = planned * capacity[route]
            if (shipped > 0.0) shipments[route] = (shipments[route] ?: 0.0) + shipped
            offer[seller] = (offer[seller] - planned).coerceAtLeast(0.0)
            request[buyer] = (request[buyer] - planned).coerceAtLeast(0.0)
        }

        // 주요 거래 상대들에게 수출 가능 물량을 제시한다. 한 수입국에 제안이 몰리면 같은 비율로 줄인다.
        val proposals = Array(count) { DoubleArray(preferred[it].size) }
        val proposedImports = DoubleArray(count)
        for (seller in ids.indices) {
            if (offer[seller] <= 0.0) continue
            val buyers = preferred[seller]
            val weights = proposals[seller]
            for (j in buyers.indices) {
                val buyer = buyers[j]
                val route = seller * count + buyer
                if (request[buyer] > 0.0) weights[j] = baseShare[route] * routes[route]
            }
            val total = weights.sum()
            if (total <= 0.0) continue
            for (j in buyers.indices) {
                val buyer = buyers[j]
                weights[j] = minOf(request[buyer], offer[seller] * weights[j] / total)
                proposedImports[buyer] += weights[j]
            }
        }
        val acceptance = DoubleArray(count) { if (proposedImports[it] > request[it]) request[it] / proposedImports[it] else 1.0 }
        for (seller in ids.indices) {
            preferred[seller].forEachIndexed { j, buyer ->
                val planned = minOf(proposals[seller][j] * acceptance[buyer], offer[seller], request[buyer])
                if (planned > 0.0) ship(seller, buyer, planned)
            }
        }

        // 각 잔여 계약은 적어도 한쪽의 잔량을 소진한다. 모든 국가 쌍을 거래 내역에 보관할 필요가 없다.
        for (seller in ids.indices) {
            while (offer[seller] > 0.0) {
                var buyer = -1
                var bestWeight = -1.0
                for (candidate in ids.indices) {
                    if (candidate == seller || request[candidate] <= 0.0) continue
                    val route = seller * count + candidate
                    if (routes[route] <= 0.0) continue
                    val weight = baseShare[route] * routes[route]
                    if (weight > bestWeight) {
                        buyer = candidate
                        bestWeight = weight
                    }
                }
                if (buyer < 0) break
                ship(seller, buyer, minOf(offer[seller], request[buyer]))
            }
        }
    }

    private companion object {
        const val PREFERRED_PARTNERS = 8
        const val BASE_PRICE = 100.0
        const val MIN_PRICE = 25.0
        const val MAX_PRICE = 400.0
        const val MAX_STORAGE_MONTHS = 6.0
        const val PRICE_SPEED = 0.35
        const val RESERVE_PRESSURE = 0.2
        const val MAX_MONTHLY_CHANGE = 0.2
    }
}
