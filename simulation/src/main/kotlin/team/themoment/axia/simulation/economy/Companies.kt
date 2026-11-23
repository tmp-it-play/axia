package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.people.PeopleData
import java.time.YearMonth
import kotlin.random.Random

/** 기업의 부가가치는 이미 해당 산업 총액에 포함되어 있다. */
data class Company(
    val id: Int,
    val name: String,
    val sector: Sector,
    val founded: YearMonth,
    val sectorShare: Double,
    val annualValueAdded: Double,
)

internal class Companies(
    countryId: String,
    private val random: Random,
) {
    private val families = PeopleData.default.culture(countryId).surnames
    private var nextId = 1
    private val firms = mutableListOf<Company>()
    private val privateSectors = Sector.entries.filter { it != Sector.PUBLIC }
    var current: List<Company> = emptyList()
        private set
    var closures = 0
        private set

    fun rename(
        id: Int,
        name: String,
    ) {
        val i = firms.indexOfFirst { it.id == id }
        val trimmed = name.trim()
        if (i < 0 || trimmed.length !in 1..48 || firms.any { it.id != id && it.name == trimmed }) return
        firms[i] = firms[i].copy(name = trimmed)
        current = firms.toList()
    }

    fun advance(
        date: YearMonth,
        sectors: List<SectorLine>,
        gdp: Double,
        creditCost: Double,
        onExit: (Sector, Double) -> Unit,
    ) {
        for (sector in privateSectors) {
            val line = sectors[sector.ordinal]
            if (line.share <= 0.0) continue
            val stress = (-line.gap + creditCost.coerceIn(-0.02, 0.20) * 0.25).coerceAtLeast(0.0)
            val sectorGdp = line.share * gdp
            val it = firms.listIterator()
            var count = 0
            while (it.hasNext()) {
                val firm = it.next()
                if (firm.sector != sector) continue
                if (random.nextDouble() < 0.003 + stress * 0.15) {
                    it.remove()
                    closures++
                    onExit(sector, -firm.sectorShare * 0.10)
                } else {
                    val share = (firm.sectorShare * (1 + line.gap * 0.12 + random.nextDouble(-0.025, 0.025))).coerceIn(0.002, 0.08)
                    it.set(firm.copy(sectorShare = share, annualValueAdded = share * sectorGdp))
                    count++
                }
            }
            val entry = (0.035 + line.gap * 0.3 - stress * 0.2).coerceIn(0.005, 0.2)
            if (count < 4 && random.nextDouble() < entry * (4 - count)) {
                val id = nextId++
                val stem = families.random(random) + " " + sector.label.substringBefore('·')
                var name = stem
                var suffix = id
                while (firms.any { it.name == name }) name = "$stem ${suffix++}"
                val share = random.nextDouble(0.003, 0.025)
                firms += Company(id, name, sector, date, share, share * sectorGdp)
            }
        }
        current = firms.toList()
    }
}
