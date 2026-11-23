package team.themoment.axia.simulation.economy

import kotlin.math.exp
import kotlin.math.sqrt

/** 최근 결산월의 대외 거래와 월말 잔액. 모두 명목 달러. */
data class ExternalAccountSnapshot(
    val exports: Double,
    val imports: Double,
    val currentAccount: Double,
    val reserves: Double,
    val openingReserveSource: ReserveSource?,
    val importCover: Double?,
    val imfCredit: Double,
    val imfMonthsLeft: Int,
    val tradeArrears: Double,
    val pegFailed: Boolean,
    val canRequestAssistance: Boolean,
    val settled: Boolean,
)

/** 양자 수출 한 건을 수입국에도 같은 금액으로 기록한다. 초기 행·열 합을 국가별 교역 개방도에 맞춘다. */
internal class TradePayments(
    exportShares: DoubleArray,
    openness: DoubleArray,
    private val initialGdp: DoubleArray,
    routes: DoubleArray,
) {
    private val size = initialGdp.size
    private val values = DoubleArray(routes.size)
    val exports = DoubleArray(size)
    val imports = DoubleArray(size)
    private val scale = DoubleArray(size)
    private val demand = DoubleArray(size)

    init {
        val supply = DoubleArray(size) { exportShares[it] * initialGdp[it] / 12 }
        val purchases = DoubleArray(size) { (openness[it] - exportShares[it]).coerceAtLeast(0.0) * initialGdp[it] / 12 }
        val adjustment = supply.sum() / purchases.sum()
        for (j in purchases.indices) purchases[j] *= adjustment
        for (i in supply.indices) for (j in purchases.indices) values[i * size + j] = supply[i] * routes[i * size + j]
        repeat(32) {
            imports.fill(0.0)
            for (i in supply.indices) for (j in purchases.indices) imports[j] += values[i * size + j]
            for (j in purchases.indices) scale[j] = if (imports[j] > 0.0) purchases[j] / imports[j] else 0.0
            for (i in supply.indices) {
                var sold = 0.0
                for (j in purchases.indices) {
                    val k = i * size + j
                    values[k] *= scale[j]
                    sold += values[k]
                }
                if (sold > 0.0) for (j in purchases.indices) values[i * size + j] *= supply[i] / sold
            }
        }
    }

    fun settle(
        countries: List<CountryEconomy>,
        factors: DoubleArray,
    ) {
        exports.fill(0.0)
        imports.fill(0.0)
        countries.forEachIndexed { i, country ->
            val growth = sqrt(country.dollarGdp / initialGdp[i])
            scale[i] = growth * exp(-0.5 * country.realExchange)
            demand[i] = growth * exp(0.5 * country.realExchange) * country.externalAccount.importCapacity
        }
        for (i in countries.indices) {
            for (j in countries.indices) {
                val k = i * size + j
                val shipment = values[k] * scale[i] * demand[j] * factors[k]
                exports[i] += shipment
                imports[j] += shipment
            }
        }
    }
}

internal class ExternalAccount(
    initialGdp: Double,
    importShare: Double,
    opening: InitialReserveAssets?,
) {
    private val openingImports = initialGdp * importShare / 12
    private var settled = false
    private var imports = 0.0
    private var exports = 0.0
    private var current = 0.0
    private val openingReserveSource = opening?.source
    private var reserves = opening?.usd ?: (openingImports * 3)
    private var credit = 0.0
    private var payment = 0.0
    private var monthsLeft = 0
    private var arrears = 0.0
    private var need = 0.0
    var member = false
    val canRequest get() = member && credit == 0.0 && need > 0.0
    var pegFailed = false
        private set

    private val cover get() = (if (settled) imports else openingImports).takeIf { it > 0.0 }?.let { reserves / it }
    val importCapacity: Double
        get() = if (imports > 0.0) (1.0 - 0.18 * arrears / imports).coerceIn(0.4, 1.0) else 1.0

    /** 국제수지 위기 때 중앙은행이 외화를 차입한다. 국채 감면 대상과 장부를 분리한다. */
    fun request(): Boolean {
        if (!canRequest) return false
        credit = need
        reserves += credit
        monthsLeft = 36
        payment = credit / monthsLeft
        need = 0.0
        return true
    }

    /** 달러 이자는 경상수지, 원금은 금융 거래다. 미지급 수입대금은 무역신용 채무로 이월한다. */
    fun closeMonth(
        external: ExternalConditions,
        gdp: Double,
        debtRatio: Double,
        inflation: Double,
        member: Boolean,
        defendsPeg: Boolean,
    ): Double {
        settled = true
        exports = external.exports
        imports = external.imports
        val interest = credit * 0.03 / 12
        current = exports - imports - interest
        val trade = exports - imports
        // 통상의 경상적자는 민간 자본이 대부분 조달한다. 고물가·부채와 경기 충격은 자본 유출을 일으킨다.
        val flight =
            (inflation - 0.08).coerceIn(0.0, 0.5) * 0.15 +
                (debtRatio - 0.9).coerceIn(0.0, 1.5) * 0.025 + (-external.gapShock).coerceIn(0.0, 0.10)
        val privateFlow = -trade * 0.94 - gdp * flight / 12.0 + (3 * imports - reserves) * 0.07
        val balance = current + privateFlow
        val intervention = if (defendsPeg) balance else balance * 0.3 - interest * 0.7
        reserves += intervention
        if (reserves < 0.0) {
            arrears -= reserves
            reserves = 0.0
        }
        val due = if (monthsLeft <= 1) credit else minOf(credit, payment)
        val repayment = minOf(due, reserves)
        credit -= repayment
        reserves -= repayment
        if (monthsLeft > 0) monthsLeft--
        val clearArrears = minOf(arrears, (reserves - imports).coerceAtLeast(0.0))
        arrears -= clearArrears
        reserves -= clearArrears
        val coverage = cover
        this.member = member
        need = if (coverage != null && coverage < 1.5) minOf(gdp * 0.05, 3 * imports - reserves) else 0.0
        if (defendsPeg && coverage != null && coverage < 0.5 && balance < 0.0) pegFailed = true
        return ((balance - intervention) / gdp).coerceIn(-0.035, 0.035)
    }

    fun snapshot() =
        ExternalAccountSnapshot(
            exports,
            imports,
            current,
            reserves,
            openingReserveSource,
            cover,
            credit,
            monthsLeft,
            arrears,
            pegFailed,
            canRequest,
            settled,
        )
}
