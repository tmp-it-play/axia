package team.themoment.axia.simulation.economy

import team.themoment.axia.simulation.world.CountryStructure
import kotlin.math.pow

/**
 * 산업 부문. 계수는 게임 가정이다.
 * @property cyclicality 전체 경기(산출 갭) 1%p에 대한 부문 경기 반응
 * @property rateSensitivity 실질금리 갭 1%p가 부문 경기에서 빼는 몫(%p)
 * @property exportExposure 교역 상대 경기에 대한 반응(수출 비중을 곱한다)
 * @property incomeElasticity 1인당 소득이 오를 때 부가가치 비중이 바뀌는 탄력성(구조 변화)
 */
enum class Sector(
    val label: String,
    val cyclicality: Double,
    val rateSensitivity: Double,
    val exportExposure: Double,
    val incomeElasticity: Double,
) {
    AGRICULTURE("농림어업", 0.3, 0.0, 0.3, -0.6),
    ENERGY("광업·에너지", 0.6, 0.0, 0.8, 0.0),
    LIGHT_INDUSTRY("경공업", 1.0, 0.2, 1.0, -0.3),
    HEAVY_INDUSTRY("중화학", 1.5, 0.4, 1.5, 0.0),
    HIGH_TECH("첨단 제조", 1.4, 0.3, 2.0, 0.4),
    CONSTRUCTION("건설", 2.0, 1.2, 0.0, 0.0),
    SERVICES("유통·서비스", 1.0, 0.2, 0.2, 0.15),
    FINANCE("금융·부동산", 1.3, 0.8, 0.3, 0.3),
    PUBLIC("공공·사회서비스", 0.2, 0.0, 0.0, 0.1),
}

/** 한 부문의 현황. */
data class SectorLine(
    val sector: Sector,
    /** 부가가치 비중(합 1). */
    val share: Double,
    /** 부문 경기(잠재 대비 편차). */
    val gap: Double,
    /** 실질 부가가치의 전년 대비 증가율. */
    val growth: Double,
    /** 고용 비중(합 1). */
    val employment: Double,
)

/** 부문 경기를 정하는 이번 달 재료. */
internal data class SectorInputs(
    val outputGap: Double,
    val realRateGap: Double,
    val partnerGap: Double,
    /** 유가의 전년 대비 로그 변화. */
    val oilChange: Double,
    /** 1인당 실질소득 지수(시작 = 1). */
    val incomeIndex: Double,
    /** 정책으로 생긴 부문별 구조 변화 배율(규제, 관세). 없으면 1. */
    val policy: Map<Sector, Double>,
    /** 사건이 남긴 부문 충격(가뭄 → 농업 등). */
    val shocks: Map<Sector, Double>,
)

/**
 * 한 나라의 산업 구조. 시작 비중은 World Bank 부가가치 구성(농업·산업·제조업·서비스와 제조업 내 기술·식품·섬유 비중)에서 나눈다.
 *
 * - 산업 중 제조업이 아닌 몫은 건설(GDP의 최대 8%)과 광업·에너지로 나눈다. 자원 지대가 크면 광업·에너지 몫을 그만큼 보장한다.
 * - 제조업: 식품·섬유 → 경공업, 중고위 기술의 45% → 첨단 제조, 나머지 → 중화학
 * - 서비스: 소득 그룹별 금융·공공 비중 템플릿으로 나누고 나머지는 유통·서비스
 * - 부문 경기 = 전체 경기 × 반응 + 금리·해외 수요·유가 효과. 부문 경기를 비중으로 합하면 전체 산출 갭과 같도록 맞춘다.
 * - 구조 변화: 비중 목표 = 시작 비중 × 소득 지수^탄력성 × 정책 배율. 10년 반감기로 따라간다.
 */
internal class SectorEconomy(
    structure: CountryStructure,
    incomeGroup: String,
    agricultureEmployment: Double,
    industryEmployment: Double,
    servicesEmployment: Double,
    private val oilRents: Double,
    private val exportShare: Double,
) {
    private val initialShares: Map<Sector, Double> = initialShares(structure, incomeGroup)
    private val shares = initialShares.toMutableMap()
    private val gaps = Sector.entries.associateWith { 0.0 }.toMutableMap()
    private val levels = Sector.entries.associateWith { 1.0 }.toMutableMap()
    private val history = Sector.entries.associateWith { ArrayDeque(List(12) { 1.0 }) }
    private val employment: Map<Sector, Double>

    init {
        val industrySectors = listOf(Sector.ENERGY, Sector.LIGHT_INDUSTRY, Sector.HEAVY_INDUSTRY, Sector.HIGH_TECH, Sector.CONSTRUCTION)
        val serviceSectors = listOf(Sector.SERVICES, Sector.FINANCE, Sector.PUBLIC)
        val industryOutput = industrySectors.sumOf { initialShares.getValue(it) }
        val serviceOutput = serviceSectors.sumOf { initialShares.getValue(it) }
        // 같은 대분류 안에서는 부가가치 비중 × 노동 집약도로 고용을 나눈다.
        val raw =
            Sector.entries.associateWith { sector ->
                val share = initialShares.getValue(sector)
                when (sector) {
                    Sector.AGRICULTURE -> agricultureEmployment
                    in industrySectors -> industryEmployment * share * LABOR_INTENSITY.getValue(sector) / industryOutput
                    else -> servicesEmployment * share * LABOR_INTENSITY.getValue(sector) / serviceOutput
                }
            }
        val total = raw.values.sum()
        employment = raw.mapValues { it.value / total }
    }

    fun advanceMonth(inputs: SectorInputs) {
        val raw =
            Sector.entries.associateWith { sector ->
                val oil =
                    if (sector ==
                        Sector.ENERGY
                    ) {
                        OIL_SECTOR_RESPONSE * inputs.oilChange * (oilRents * OIL_WEIGHT).coerceIn(0.2, 1.0)
                    } else {
                        0.0
                    }
                sector.cyclicality * inputs.outputGap - sector.rateSensitivity * inputs.realRateGap * RATE_SCALE +
                    sector.exportExposure * exportShare.coerceAtMost(1.0) * inputs.partnerGap + oil + (inputs.shocks[sector] ?: 0.0)
            }
        // 부문 경기의 가중 합이 전체 산출 갭과 같도록 모든 부문에 같은 값을 더해 맞춘다.
        val correction = inputs.outputGap - Sector.entries.sumOf { shares.getValue(it) * raw.getValue(it) }
        Sector.entries.forEach { sector -> gaps[sector] = (raw.getValue(sector) + correction).coerceIn(-MAX_SECTOR_GAP, MAX_SECTOR_GAP) }

        val targets =
            Sector.entries.associateWith { sector ->
                initialShares.getValue(sector) * inputs.incomeIndex.coerceAtLeast(0.1).pow(sector.incomeElasticity) *
                    (inputs.policy[sector] ?: 1.0)
            }
        val targetSum = targets.values.sum()
        Sector.entries.forEach { sector ->
            shares[sector] = shares.getValue(sector) + (targets.getValue(sector) / targetSum - shares.getValue(sector)) * STRUCTURAL_SPEED
        }
        val shareSum = shares.values.sum()
        Sector.entries.forEach { sector ->
            shares[sector] = shares.getValue(sector) / shareSum
            val series = history.getValue(sector)
            series.removeFirst()
            series.addLast(levels.getValue(sector))
            levels[sector] = shares.getValue(sector) / initialShares.getValue(sector) * (1.0 + gaps.getValue(sector))
        }
    }

    /** 부문 수준 지수(시작 = 1, 비중 변화와 경기 포함). 인구 집단 소득에 쓴다. 전체 경제 대비 상대값이다. */
    fun relativeLevel(sector: Sector): Double = levels.getValue(sector)

    fun share(sector: Sector): Double = shares.getValue(sector)

    fun lines(realGdpGrowth: Double): List<SectorLine> =
        Sector.entries.map { sector ->
            val yearAgo = history.getValue(sector).first()
            SectorLine(
                sector = sector,
                share = shares.getValue(sector),
                gap = gaps.getValue(sector),
                growth = (1.0 + realGdpGrowth) * levels.getValue(sector) / yearAgo - 1.0,
                employment = employment.getValue(sector),
            )
        }

    companion object {
        private const val CONSTRUCTION_CAP = 0.08
        private const val CONSTRUCTION_OF_NON_MANUFACTURING = 0.55
        private const val HIGH_TECH_OF_TECH = 0.45
        private const val OIL_SECTOR_RESPONSE = 0.15
        private const val OIL_WEIGHT = 5.0
        private const val RATE_SCALE = 1.0
        private const val MAX_SECTOR_GAP = 0.5

        /** 10년 반감기. */
        private val STRUCTURAL_SPEED = 1.0 - 0.5.pow(1.0 / 120.0)
        private val FINANCE_OF_SERVICES = mapOf("HIC" to 0.25, "UMC" to 0.18, "LMC" to 0.14, "LIC" to 0.1)
        private val PUBLIC_OF_SERVICES = mapOf("HIC" to 0.28, "UMC" to 0.22, "LMC" to 0.2, "LIC" to 0.18)

        /** 부가가치 1당 고용(같은 대분류 안의 상대값). */
        private val LABOR_INTENSITY =
            mapOf(
                Sector.AGRICULTURE to 1.0,
                Sector.ENERGY to 0.3,
                Sector.LIGHT_INDUSTRY to 1.6,
                Sector.HEAVY_INDUSTRY to 0.8,
                Sector.HIGH_TECH to 0.6,
                Sector.CONSTRUCTION to 1.8,
                Sector.SERVICES to 1.4,
                Sector.FINANCE to 0.4,
                Sector.PUBLIC to 1.0,
            )

        fun initialShares(
            structure: CountryStructure,
            incomeGroup: String,
        ): Map<Sector, Double> {
            val manufacturing = structure.manufacturingValueAdded.coerceAtMost(structure.industryValueAdded)
            val nonManufacturing = (structure.industryValueAdded - manufacturing).coerceAtLeast(0.0)
            val construction = (nonManufacturing * CONSTRUCTION_OF_NON_MANUFACTURING).coerceAtMost(CONSTRUCTION_CAP)
            val energy = maxOf(nonManufacturing - construction, structure.resourceRents * 0.8)
            val light = manufacturing * (structure.foodManufacturing + structure.textileManufacturing).coerceAtMost(1.0)
            val high =
                (manufacturing - light).coerceAtLeast(0.0).coerceAtMost(manufacturing * structure.techManufacturing * HIGH_TECH_OF_TECH)
            val heavy = (manufacturing - light - high).coerceAtLeast(0.0)
            val services = structure.servicesValueAdded
            val finance = services * (FINANCE_OF_SERVICES[incomeGroup] ?: 0.15)
            val public = services * (PUBLIC_OF_SERVICES[incomeGroup] ?: 0.2)
            val raw =
                mapOf(
                    Sector.AGRICULTURE to structure.agricultureValueAdded,
                    Sector.ENERGY to energy,
                    Sector.LIGHT_INDUSTRY to light,
                    Sector.HEAVY_INDUSTRY to heavy,
                    Sector.HIGH_TECH to high,
                    Sector.CONSTRUCTION to construction,
                    Sector.SERVICES to services - finance - public,
                    Sector.FINANCE to finance,
                    Sector.PUBLIC to public,
                ).mapValues { it.value.coerceAtLeast(MIN_SHARE) }
            val total = raw.values.sum()
            return raw.mapValues { it.value / total }
        }

        private const val MIN_SHARE = 0.002
    }
}
