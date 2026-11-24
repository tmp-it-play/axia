package team.themoment.axia.simulation.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.themoment.axia.simulation.world.resource

enum class RemittanceSource(
    val label: String,
    val partial: Boolean = false,
) {
    WDI("세계은행 WDI"),
    NATIONAL_BOP("공표 국제수지"),
    FISCAL_BOP_SEPTEMBER("공표 국제수지 · 9월 말 회계연도"),
    NATIONAL_BOP_COMPONENTS("공표 국제수지 · 확인된 구성 항목만", true),
    KNOMAD("KNOMAD 양자 추계"),
    WORLD_BANK_ESTIMATE("세계은행 국가별 송금 추계"),
    MIGRANT_TRANSFER_ESTIMATE("이주민 송금 수취 추계", true),
    BOP_MINIMUM("개인이전 순액과 피용자보수로 계산한 최소액", true),
    WDI_PERSONAL_TRANSFERS("세계은행 WDI · 전국 가계 간 개인이전만, 피용자보수 미보고", true),
    LICENSED_REMITTANCES("ESAAMLG · 허가 송금업자의 개인송금만", true),
    BANK_CHANNELS("중앙은행 개인송금 집계", true),
    UNCTAD_REMITTANCES("UNCTAD · 가계송금 수취 추계만", true),
    HOUSEHOLD_SURVEY("세계은행 가구조사 · 가계송금 수취 추계만", true),
    PARTNER_REPORTS("상대국 신고분만", true),
    WAGE_PAYMENT_FLOOR("국민계정 피용자보수 순지급과 상대국 신고로 계산한 지급 하한", true),
}

@Serializable
data class RemittanceBenchmark(
    val usd: Long,
    val year: Int,
    val source: RemittanceSource,
    val unchangedSince: Int? = null,
    val scope: String? = null,
) {
    init {
        require(usd >= 0 && year in 2014..2023)
        require(unchangedSince == null || (source == RemittanceSource.WORLD_BANK_ESTIMATE && unchangedSince < year))
    }

    val label get() =
        "${source.label} · ${year}년" + (unchangedSince?.let { " (${it}년부터 같은 금액)" } ?: "") +
            (scope?.let { " · $it" } ?: "")
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
