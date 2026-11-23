package team.themoment.axia.simulation.society

import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.nation.InstitutionItem
import team.themoment.axia.simulation.nation.PolicyItem
import team.themoment.axia.simulation.nation.RegulationItem
import team.themoment.axia.simulation.nation.SpendingKind
import team.themoment.axia.simulation.nation.TaxKind

/**
 * 서로 겹칠 수 있는 성향 집단(Democracy 4 참고). 한 사람이 여러 성향에 서로 다른 강도로 속한다.
 *
 * @property weight 소속 강도 1일 때 유권자 만족도에서 이 성향이 차지하는 비중(직업 집단 = 1).
 * @property demographic 태도가 아니라 인구학적 소속(청년, 부모)인지.
 */
enum class Identity(
    val label: String,
    val weight: Double,
    val demographic: Boolean = false,
) {
    ENVIRONMENTALISTS("환경주의", 0.8),
    PATRIOTS("안보·애국", 0.8),
    RELIGIOUS("종교·전통", 0.8),
    LIBERTARIANS("시민자유", 0.8),
    EGALITARIANS("진보·평등", 0.8),
    FREE_MARKET("시장주의", 0.8),
    UNIONISTS("노동조합원", 0.6),
    YOUTH("청년", 0.4, demographic = true),
    PARENTS("부모", 0.4, demographic = true),
    ;

    /**
     * 정책 항목이 1단위 바뀔 때 이 성향의 만족도 변화(점). 단위는 세율·지출은 GDP 대비 비율, 제도·규제는 0~1이다.
     * 월간 만족도(시작 대비 실제값 변화)와 법안 지지(변경안)에 같은 계수를 쓴다.
     */
    fun reaction(item: PolicyItem): Double =
        when (item) {
            is TaxKind -> TAX_REACTIONS[this]?.get(item)
            is SpendingKind -> SPENDING_REACTIONS[this]?.get(item)
            is RegulationItem -> REGULATION_REACTIONS[this]?.get(item)
            is InstitutionItem -> INSTITUTION_REACTIONS[this]?.get(item)
            is CountryPolicyItem -> item.identityReactions[this]
        } ?: 0.0

    private companion object {
        val TAX_REACTIONS =
            mapOf(
                FREE_MARKET to
                    mapOf(TaxKind.INCOME to -150.0, TaxKind.CORPORATE to -300.0, TaxKind.CONSUMPTION to -50.0, TaxKind.SOCIAL to -100.0),
                EGALITARIANS to mapOf(TaxKind.INCOME to 80.0, TaxKind.CORPORATE to 200.0, TaxKind.CONSUMPTION to -150.0),
                UNIONISTS to mapOf(TaxKind.CORPORATE to 100.0, TaxKind.CONSUMPTION to -80.0),
                PATRIOTS to mapOf(TaxKind.TARIFF to 150.0),
                YOUTH to mapOf(TaxKind.INCOME to -60.0, TaxKind.SOCIAL to -60.0),
            )
        val SPENDING_REACTIONS =
            mapOf(
                PATRIOTS to mapOf(SpendingKind.DEFENSE to 1200.0, SpendingKind.ORDER to 400.0),
                PARENTS to mapOf(SpendingKind.EDUCATION to 900.0, SpendingKind.HEALTH to 500.0),
                EGALITARIANS to
                    mapOf(
                        SpendingKind.SOCIAL to 800.0,
                        SpendingKind.HEALTH to 400.0,
                        SpendingKind.EDUCATION to 300.0,
                        SpendingKind.DEFENSE to -300.0,
                    ),
                ENVIRONMENTALISTS to mapOf(SpendingKind.DEFENSE to -200.0, SpendingKind.ECONOMY to -100.0),
                FREE_MARKET to mapOf(SpendingKind.SOCIAL to -400.0, SpendingKind.ECONOMY to 200.0),
                YOUTH to mapOf(SpendingKind.EDUCATION to 500.0),
                UNIONISTS to mapOf(SpendingKind.SOCIAL to 300.0, SpendingKind.ECONOMY to 300.0),
                LIBERTARIANS to mapOf(SpendingKind.ORDER to -300.0),
                RELIGIOUS to mapOf(SpendingKind.SOCIAL to 100.0),
            )
        val REGULATION_REACTIONS =
            mapOf(
                ENVIRONMENTALISTS to mapOf(RegulationItem.ENVIRONMENT to 60.0),
                FREE_MARKET to
                    mapOf(
                        RegulationItem.ENVIRONMENT to -35.0,
                        RegulationItem.LABOR to -45.0,
                        RegulationItem.IMMIGRATION to 25.0,
                    ),
                UNIONISTS to mapOf(RegulationItem.ENVIRONMENT to -10.0, RegulationItem.LABOR to 60.0, RegulationItem.IMMIGRATION to -15.0),
                EGALITARIANS to
                    mapOf(
                        RegulationItem.LABOR to 40.0,
                        RegulationItem.IMMIGRATION to 15.0,
                        RegulationItem.POLICING to -10.0,
                        RegulationItem.CIVIL_LIBERTIES to 25.0,
                    ),
                LIBERTARIANS to
                    mapOf(RegulationItem.IMMIGRATION to 25.0, RegulationItem.POLICING to -60.0, RegulationItem.CIVIL_LIBERTIES to 70.0),
                PATRIOTS to
                    mapOf(RegulationItem.IMMIGRATION to -50.0, RegulationItem.POLICING to 45.0, RegulationItem.CIVIL_LIBERTIES to -15.0),
                RELIGIOUS to
                    mapOf(RegulationItem.IMMIGRATION to -30.0, RegulationItem.POLICING to 15.0, RegulationItem.CIVIL_LIBERTIES to -10.0),
                YOUTH to
                    mapOf(
                        RegulationItem.ENVIRONMENT to 15.0,
                        RegulationItem.LABOR to 10.0,
                        RegulationItem.POLICING to -15.0,
                        RegulationItem.CIVIL_LIBERTIES to 20.0,
                    ),
                PARENTS to mapOf(RegulationItem.ENVIRONMENT to 10.0, RegulationItem.POLICING to 20.0),
            )
        val INSTITUTION_REACTIONS =
            mapOf(
                FREE_MARKET to mapOf(InstitutionItem.CENTRAL_BANK_INDEPENDENCE to 20.0),
                EGALITARIANS to mapOf(InstitutionItem.CENTRAL_BANK_INDEPENDENCE to -8.0),
            )
    }
}
