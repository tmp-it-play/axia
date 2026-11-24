package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.resource

enum class RemittanceSource(
    val label: String,
) {
    WDI("세계은행 WDI"),
    NATIONAL_BOP("공표 국제수지"),
    KNOMAD("KNOMAD 추계"),
}

@Serializable
data class RemittanceBenchmark(
    val usd: Long,
    val year: Int,
    val source: RemittanceSource,
) {
    init {
        require(usd >= 0 && year in 2019..2023)
    }

    val label get() = "${source.label} · ${year}년"
}

/** 최근 결산월 개인송금(달러). 상대 미배정액도 수취·지급 총액에 포함한다. */
data class MonthlyRemittances(
    val received: Double = 0.0,
    val paid: Double = 0.0,
    val unallocatedReceived: Double = 0.0,
    val unallocatedPaid: Double = 0.0,
)

@Serializable
internal data class OpeningRemittances(
    val received: RemittanceBenchmark?,
    val paid: RemittanceBenchmark?,
    val destinations: Map<String, Long>,
)

@Serializable
private data class RemittanceFile(
    val unit: String,
    val countries: Map<String, OpeningRemittances>,
)

internal object RemittanceData {
    val countries by lazy {
        val file = Json.decodeFromString<RemittanceFile>(resource("remittances"))
        require(file.unit == "annual_USD")
        file.countries
    }
}

/** 양자 송금은 송금국 경제 규모에 연동해 양쪽에 같은 달러로 결제한다. 상대 미상의 수취액은 세계 경제 규모에 연동한다. */
internal class RemittanceNetwork(
    private val countries: List<CountryEconomy>,
) {
    private data class Route(
        val recipient: Int,
        val usd: Long,
    )

    private val opening = countries.map { RemittanceData.countries.getValue(it.id) }
    private val openingGdp = countries.map(CountryEconomy::dollarGdp)
    private val openingWorldGdp = openingGdp.sum()
    private val indices = countries.mapIndexed { i, country -> country.id to i }.toMap()
    private val routes = opening.map { totals -> totals.destinations.map { (id, usd) -> Route(indices.getValue(id), usd) } }
    private val unallocatedPaid = LongArray(countries.size) { opening[it].paid?.usd ?: 0L }
    private val unallocatedReceived = LongArray(countries.size) { opening[it].received?.usd ?: 0L }

    init {
        require(indices.keys == RemittanceData.countries.keys)
        routes.forEachIndexed { sender, transfers ->
            for (route in transfers) {
                require(sender != route.recipient && route.usd > 0)
                unallocatedPaid[sender] -= route.usd
                unallocatedReceived[route.recipient] -= route.usd
            }
        }
        require(unallocatedPaid.all { it >= 0 } && unallocatedReceived.all { it >= 0 })
    }

    fun settle(): List<MonthlyRemittances> {
        val monthlyScale = DoubleArray(countries.size) { countries[it].dollarGdp / openingGdp[it] / 12 }
        val worldScale = countries.sumOf(CountryEconomy::dollarGdp) / openingWorldGdp / 12
        val received = DoubleArray(countries.size)
        val paid = DoubleArray(countries.size)
        routes.forEachIndexed { sender, transfers ->
            for (route in transfers) {
                val usd = route.usd * monthlyScale[sender]
                paid[sender] += usd
                received[route.recipient] += usd
            }
        }
        return countries.indices.map { i ->
            val otherReceived = unallocatedReceived[i] * worldScale
            val otherPaid = unallocatedPaid[i] * monthlyScale[i]
            MonthlyRemittances(received[i] + otherReceived, paid[i] + otherPaid, otherReceived, otherPaid)
        }
    }
}
