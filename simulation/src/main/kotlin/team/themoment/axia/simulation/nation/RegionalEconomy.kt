package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.economy.SectorLine
import kotlin.math.exp

/** 한 지역 안에서 부문별 부가가치와 취업자가 차지하는 비중. 각각 합이 1이다. */
data class RegionSector(
    val sector: Sector,
    val share: Double,
    val employment: Double,
)

data class RegionEconomySnapshot(
    /** 전국 부가가치 중 이 지역의 몫. */
    val gdpShare: Double,
    /** 전국 경제활동인구 중 이 지역의 몫. 지역 실업률을 합산할 때 쓴다. */
    val laborForceShare: Double,
    /** 지역 경제활동인구 중 실업자 비율. */
    val unemployment: Double,
    val sectors: List<RegionSector>,
)

/** 전국의 부가가치·취업자·실업자 총량을 각 부문이 입지한 지역에 배분한다. */
internal class RegionalEconomy(
    private val sectors: List<SectorLine>,
    private val unemployment: Double,
) {
    // 같은 수의 취업자가 있어도 경기가 나빠진 업종일수록 구직자가 더 많다.
    private val jobseekers =
        sectors.associate { it.sector to it.employment * it.sector.jobSearchWeight * exp(-JOB_SEARCH_RESPONSE * it.gap) }
    private val totalJobseekers = jobseekers.values.sum()

    fun estimate(
        populationShare: Double,
        density: Map<Sector, Double>,
    ): RegionEconomySnapshot {
        // 전국 1인당 평균에 대한 배율로 먼저 계산하면 작은 지역에서도 비율의 정밀도를 유지한다.
        val output = sectors.sumOf { it.share * density.getValue(it.sector) }
        val jobs = sectors.sumOf { it.employment * density.getValue(it.sector) }
        val unemployed = unemployment * jobseekers.entries.sumOf { (sector, weight) -> weight * density.getValue(sector) } / totalJobseekers
        val laborForce = (1.0 - unemployment) * jobs + unemployed
        return RegionEconomySnapshot(
            gdpShare = populationShare * output,
            laborForceShare = populationShare * laborForce,
            unemployment = unemployed / laborForce,
            sectors =
                sectors.map { line ->
                    val weight = density.getValue(line.sector)
                    RegionSector(line.sector, line.share * weight / output, line.employment * weight / jobs)
                },
        )
    }

    companion object {
        /** 부문 산출 갭이 1%p 낮아질 때 취업자당 상대 구직 수요가 약 4% 증가한다(게임 가정). */
        private const val JOB_SEARCH_RESPONSE = 4.0

        /** 도시·농촌 입지 배율을 전국 인구 가중 평균이 1인 지역별 활동 밀도로 환산한다. */
        fun density(
            urban: Double,
            nationalUrban: Double,
        ): Map<Sector, Double> =
            Sector.entries.associateWith { sector ->
                val premium = sector.urbanDensity - 1.0
                (1.0 + urban * premium) / (1.0 + nationalUrban * premium)
            }
    }
}
