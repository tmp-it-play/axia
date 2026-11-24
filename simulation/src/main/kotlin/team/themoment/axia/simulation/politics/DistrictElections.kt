package team.themoment.axia.simulation.politics

import team.themoment.axia.simulation.nation.ElectoralRegion
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** 각 원소가 모형 소선거구 한 곳의 당선 정당이다. 행정구역 안의 선거구 번호는 게임 식별자다. */
data class DistrictResult(
    val regionId: String,
    val regionName: String,
    val winners: List<String>,
    val constituencies: List<String> = emptyList(),
    val uncovered: Map<String, String> = emptyMap(),
)

internal class DistrictElectorate(
    private val regions: List<ElectoralRegion>,
    private val roles: Map<String, PartyRole>,
    private val nationalApproval: Double,
    private val modelNames: Map<String, String> = emptyMap(),
) {
    fun plurality(
        map: PluralityMap,
        shares: Map<String, Double>,
    ) = PluralityDistricts.elect(map, MixedVotes(shares, regions, roles, nationalApproval, modelNames))

    fun iranianCount(shares: Map<String, Double>) =
        IranianAssembly.count(MixedVotes(shares, regions, roles, nationalApproval, modelNames), nationalApproval)

    fun australianHouse(shares: Map<String, Double>) =
        AustralianHouse.elect(MixedVotes(shares, regions, roles, nationalApproval, modelNames))

    fun proportional(
        shares: Map<String, Double>,
        rule: LegislativeElectionRule,
    ): LegislativeSeats = ProportionalElections.elect(rule, MixedVotes(shares, regions, roles, nationalApproval, modelNames))

    fun mixedMember(
        shares: Map<String, Double>,
        system: MixedSystem,
    ): LegislativeSeats = MixedMemberElections.elect(system, MixedVotes(shares, regions, roles, nationalApproval, modelNames))

    fun frenchAssembly(shares: Map<String, Double>) = FrenchAssembly.count(shares, regions, roles, nationalApproval, modelNames)

    fun usHouse(shares: Map<String, Double>) =
        RegionalElections.house(
            shares,
            regions,
            nationalApproval,
            roles.entries
                .firstOrNull {
                    it.value ==
                        PartyRole.LEAD
                }?.key,
            modelNames,
        )

    fun pakistanAssembly(
        shares: Map<String, Double>,
        opening: OpeningContest,
    ): LegislativeSeats = PakistanAssembly.elect(shares, opening, regions, nationalApproval, roles, modelNames)

    fun elect(
        shares: Map<String, Double>,
        seats: Int,
        system: ElectoralSystem,
        constituencySeats: Int? = null,
    ): List<DistrictResult> {
        if (system == ElectoralSystem.PROPORTIONAL || regions.none { it.populationShare > 0.0 }) return emptyList()
        val districtSeats = if (system == ElectoralSystem.MIXED) requireNotNull(constituencySeats) else seats
        val counts =
            ElectionModel.allocateSeats(
                regions.associate { it.id to it.populationShare },
                ElectoralSystem.PROPORTIONAL,
                districtSeats,
            )
        return regions.mapNotNull { region ->
            val count = counts.getValue(region.id)
            if (count == 0) return@mapNotNull null
            val swing = ((region.approval - nationalApproval) / 35.0).coerceIn(-1.0, 1.0)
            val regionalWeights =
                shares.mapValues { (party, share) ->
                    val incumbency =
                        when (roles[party]) {
                            PartyRole.LEAD, PartyRole.COALITION -> 1.0
                            PartyRole.SUPPLY -> 0.5
                            PartyRole.OPPOSITION -> -0.5
                            else -> 0.0
                        }
                    (share * exp(incumbency * swing)).pow(ElectoralSystem.MAJORITARIAN.seatExponent)
                }
            // 동일 선거구·정당에는 매 선거 같은 지역 기반을 적용한다. 지지율 변화로 당선자가 바뀐다.
            val winners =
                List(count) { district ->
                    regionalWeights.keys.maxBy { party ->
                        val draw = localAffinity(region.id, district, modelNames[party] ?: party)
                        regionalWeights.getValue(party) / -ln(draw)
                    }
                }
            DistrictResult(region.id, region.name, winners)
        }
    }

    companion object {
        /** SplitMix64에서 얻은 고정 분위수. 월간 전망을 계산해도 게임 난수 상태는 변하지 않는다. */
        internal fun localAffinity(
            region: String,
            district: Int,
            party: String,
        ): Double {
            var bits = (region.hashCode().toLong() * 0x1f1f1f1f + district) * 0x369dea0f + party.hashCode()
            bits = (bits xor (bits ushr 30)) * -4658895280553007687L
            bits = (bits xor (bits ushr 27)) * -7723592293110705685L
            val bucket = (bits xor (bits ushr 31)) ushr 12
            return (bucket + 0.5) / 4503599627370496.0
        }
    }
}
