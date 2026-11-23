package team.themoment.axia.simulation.nation

import team.themoment.axia.simulation.society.Identity

/**
 * 국가별 특화 모듈(`country` 패키지)이 정의하는 그 나라 고유의 정책. 0~1 연속값이고 법안에 담아 심의한다.
 * 반응 계수는 값이 0에서 1로 바뀔 때의 만족도 변화(점)다.
 */
data class CountryPolicyItem(
    val key: String,
    override val label: String,
    val lowLabel: String,
    val highLabel: String,
    val description: String,
    val identityReactions: Map<Identity, Double> = emptyMap(),
    val groupReactions: Map<PopulationGroup, Double> = emptyMap(),
) : PolicyItem {
    override val maxTarget: Double get() = 1.0
}
