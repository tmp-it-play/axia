package team.themoment.axia.simulation.politics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

enum class PluralityMap(
    internal val resource: String,
) {
    GBR("british_constituencies"),
    CAN("canadian_2023_order"),
    CAN_2013("canadian_2013_order"),
    IND("indian_constituencies"),
}

@Serializable
private data class PluralityDistrict(
    val name: String,
    val region: String,
    val parties: List<String>,
    val votes: List<Int>,
    val reserved: String? = null,
)

@Serializable
private data class PluralityReturns(
    val source: List<String>,
    val districts: List<PluralityDistrict>,
)

/** 득표 1위 후보가 1석을 얻는 선거구. 같은 정당으로 분류된 복수의 무소속 후보도 합산하지 않는다. */
internal object PluralityDistricts {
    private val maps =
        PluralityMap.entries.associateWith { map ->
            lazy {
                requireNotNull(javaClass.getResourceAsStream("/world/${map.resource}.json")).bufferedReader().use {
                    Json.decodeFromString<PluralityReturns>(it.readText()).districts
                }
            }
        }

    private val baselines =
        PluralityMap.entries.associateWith { map ->
            lazy {
                val total = mutableMapOf<String, Double>()
                for (row in maps.getValue(map).value) {
                    row.parties.forEachIndexed { i, party -> total[party] = total.getOrDefault(party, 0.0) + row.votes[i] }
                }
                val sum = total.values.sum()
                total.mapValues { it.value / sum }
            }
        }

    fun baseline(map: PluralityMap): Map<String, Double> = baselines.getValue(map).value

    fun contenders(
        map: PluralityMap,
        existing: List<ParliamentaryGroup>,
    ): List<ParliamentaryGroup> {
        val known = existing.map { it.modelName }.toSet()
        return existing +
            baseline(map).keys.filterNot { it in known }.map { party ->
                ParliamentaryGroup(party, 0, if (party == "무소속") PartyRole.OTHER else PartyRole.OPPOSITION)
            }
    }

    fun canadaOrder(proclamation: LocalDate): PluralityMap =
        if (proclamation < LocalDate.of(2024, 4, 23)) PluralityMap.CAN_2013 else PluralityMap.CAN

    fun elect(
        map: PluralityMap,
        public: MixedVotes,
    ): LegislativeSeats {
        val base = baseline(map)
        val names = public.shares.keys.associateBy(public::name)
        val fresh = public.shares.keys.filter { public.name(it) !in base }
        val regions =
            maps.getValue(map).value.groupBy { it.region }.map { (region, districts) ->
                val winners =
                    districts.map { row ->
                        var winner: String? = null
                        var maximum = -1.0
                        row.parties.forEachIndexed { index, name ->
                            val party = names[name] ?: return@forEachIndexed
                            val adjusted =
                                row.votes[index] * public.shares.getValue(party) / base.getValue(name) * public.regionalSwing(party, region)
                            if (adjusted > maximum) {
                                maximum = adjusted
                                winner = party
                            }
                        }
                        val registration = row.votes.sum()
                        for (party in fresh) {
                            // 알바당은 2019년 자료에 없으며 스코틀랜드 선거구에만 출마한다.
                            if (party == "알바당" && row.parties.none { it == "스코틀랜드 국민당" }) continue
                            val adjusted =
                                registration * public.shares.getValue(party) * public.regionalSwing(party, region) *
                                    (0.3 + 1.4 * public.affinity(row.name, 0, party))
                            if (adjusted > maximum) {
                                maximum = adjusted
                                winner = party
                            }
                        }
                        winner ?: public.shares.keys.maxBy { public.shares.getValue(it) * public.affinity(row.name, 0, it) }
                    }
                val labels =
                    districts.map { row ->
                        row.name +
                            when (row.reserved) {
                                "SC" -> " · 지정카스트"
                                "ST" -> " · 지정부족"
                                else -> ""
                            }
                    }
                DistrictResult(region, public.regionName(region), winners, labels)
            }
        val won = regions.flatMap { it.winners }.groupingBy { it }.eachCount()
        val seats = public.shares.keys.associateWith { won[it] ?: 0 }
        return LegislativeSeats(seats, districts = regions, districtMandates = seats)
    }
}
