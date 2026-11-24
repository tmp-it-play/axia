package team.themoment.axia.simulation.society

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 50~64세 노령연금 수급자. 취업한 수급자도 연금 수요에는 포함한다. */
@Serializable
internal data class EarlyPension(
    val recipientsOfWorkingAge: Double,
    val nonEmployedOfWorkingAge: Double,
    val nonEmployedOfAgeBand: Double,
    val year: Int,
    val source: String,
)

/** 조사 연령 안에서 수급이 확인된 비율. 수급 범위를 구간으로만 식별하면 [rate]는 하한이다. */
@Serializable
data class OlderPensionCoverage(
    val rate: Double,
    val year: Int,
    val source: String,
    val throughAge: Int? = null,
    val shareOfOlder: Double = 1.0,
    val upperRate: Double? = null,
    val rangeNote: String? = null,
) {
    init {
        require(rate in 0.0..1.0 && (upperRate == null || upperRate in rate..1.0))
        require((upperRate == null) == (rangeNote == null))
        require(shareOfOlder > 0.0 && shareOfOlder <= 1.0)
        require(if (throughAge == null) shareOfOlder == 1.0 else throughAge >= 65)
    }

    /** 조사 밖 연령에는 기존 1인당 수요를 유지한다. 비공개 연금 유형은 추가 수급자로 가정하지 않는다. */
    val modeledRate: Double get() = shareOfOlder * rate + (1.0 - shareOfOlder)
}

/** ILOSTAT·대만 인력자원조사 경제활동참가율. 분모는 각 연령대의 전체 인구로, 경제활동에는 실업자의 구직도 포함된다. */
@Serializable
internal data class AgeActivity(
    val workingAgeParticipation: Double,
    val olderParticipation: Double,
    val year: Int,
    val source: String,
    val series: String,
    val earlyPension: EarlyPension? = null,
    val olderPension: OlderPensionCoverage? = null,
) {
    // 50~64세 비취업 수급자 비율에 맞춰 15개 생일에 같은 수의 사람이 은퇴층으로 이동한다고 가정한다.
    // 50세의 누적 비율은 1단위, 64세는 15단위이므로 15개 연령의 평균은 8단위다.
    fun earlyRetiredAt(age: Int): Double = (earlyPension?.nonEmployedOfAgeBand ?: 0.0) * (age - 49).coerceIn(0, 15) / 8.0

    /** 해당 생일 직전까지 직업 집단에 남은 사람 중 은퇴층으로 들어갈 비율. */
    fun retirementProbability(age: Int): Double {
        if (age !in 50..65) return 0.0
        val previous = earlyRetiredAt(age - 1)
        val target = if (age == 65) 1.0 - olderParticipation else earlyRetiredAt(age)
        return (target - previous) / (1.0 - previous)
    }

    companion object {
        private val countries by lazy {
            val path = "/world/age_activity.json"
            val stream = requireNotNull(AgeActivity::class.java.getResourceAsStream(path)) { "Missing $path" }
            val text = stream.use { it.readBytes().decodeToString() }
            Json.decodeFromString<Map<String, AgeActivity>>(text).onEach { (code, rate) ->
                require(rate.workingAgeParticipation in 0.0..1.0 && rate.olderParticipation in 0.0..1.0) { code }
                rate.earlyPension?.let {
                    require(it.nonEmployedOfWorkingAge in 0.0..it.recipientsOfWorkingAge && it.recipientsOfWorkingAge <= 1.0) { code }
                    require(it.nonEmployedOfAgeBand in 0.0..1.0 && rate.earlyRetiredAt(64) < 1.0) { code }
                    require(rate.retirementProbability(65) in 0.0..1.0) { code }
                }
            }
        }

        fun country(id: String): AgeActivity = countries.getValue(id)
    }
}
