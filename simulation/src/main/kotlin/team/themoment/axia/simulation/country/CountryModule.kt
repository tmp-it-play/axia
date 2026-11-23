package team.themoment.axia.simulation.country

import team.themoment.axia.simulation.country.chn.ChinaModule
import team.themoment.axia.simulation.country.deu.GermanyModule
import team.themoment.axia.simulation.country.fra.FranceModule
import team.themoment.axia.simulation.country.ind.IndiaModule
import team.themoment.axia.simulation.country.irn.IranModule
import team.themoment.axia.simulation.country.isr.IsraelModule
import team.themoment.axia.simulation.country.jpn.JapanModule
import team.themoment.axia.simulation.country.kor.KoreaModule
import team.themoment.axia.simulation.country.prk.NorthKoreaModule
import team.themoment.axia.simulation.country.rus.RussiaModule
import team.themoment.axia.simulation.country.twn.TaiwanModule
import team.themoment.axia.simulation.country.ukr.UkraineModule
import team.themoment.axia.simulation.country.usa.UnitedStatesModule
import team.themoment.axia.simulation.economy.Sector
import team.themoment.axia.simulation.nation.CountryPolicyItem
import team.themoment.axia.simulation.politics.PolicyChange
import team.themoment.axia.simulation.society.Identity
import java.time.LocalDate
import kotlin.random.Random

/**
 * 국가별 특화 분기와 고유 시스템. 공통 모델로 표현하기 어려운 그 나라만의 제도, 정책, 상황을 여기에 둔다.
 *
 * 새 나라를 추가하려면 `country/<ISO3 소문자>/` 패키지에 구현을 만들고 [CountryModules.create]에 등록한다.
 * 모든 훅은 기본 구현이 있어 필요한 것만 덮어쓰면 된다. 상태는 모듈 인스턴스가 가지며 게임마다 새로 만든다.
 */
interface CountryModule {
    val countryId: String

    /** 이 나라 고유의 정책. 정책 화면에 슬라이더로 나오고 법안에 담아 심의한다. */
    val policies: List<CountryPolicyItem> get() = emptyList()

    /** 고유 정책의 현재 값(0~1). */
    fun policyValue(item: CountryPolicyItem): Double = error("${item.key} is not a policy of $countryId")

    /** 법안이 통과한 고유 정책을 집행한다. */
    fun enact(
        item: CountryPolicyItem,
        target: Double,
    ): Unit = error("${item.key} is not a policy of $countryId")

    /** 공통 헌법기관 심사 외에 이 나라에만 있는 거부 지점(예: 미국 상원 필리버스터, 이란 헌법수호위원회). */
    fun billChecks(review: BillReview): List<BillCheck> = emptyList()

    /**
     * 하원에서 부결된 법안을 정부가 표결 없이 밀어붙일 수 있으면 그 근거(예: 프랑스 헌법 49조 3항)를 돌려준다.
     * 상원 등 후속 심사는 그대로 거친다.
     */
    fun forceThrough(review: BillReview): String? = null

    /** 매월 1일, 경제와 여론이 갱신된 뒤 호출한다. */
    fun advanceMonth(context: CountryMonthContext): CountryEffects = CountryEffects.NONE

    /** 정치·개요 화면의 "국가 특수 상황"에 보여 줄 지표. */
    fun indicators(): List<CountryIndicator> = emptyList()
}

/** 고유 시스템이 아직 없는 나라. */
internal class DefaultCountryModule(
    override val countryId: String,
) : CountryModule

object CountryModules {
    /** 특화 모듈이 있는 나라. */
    val specialized: Set<String> =
        setOf(
            KoreaModule.ID,
            UnitedStatesModule.ID,
            IranModule.ID,
            GermanyModule.ID,
            FranceModule.ID,
            JapanModule.ID,
            ChinaModule.ID,
            IsraelModule.ID,
            TaiwanModule.ID,
            RussiaModule.ID,
            UkraineModule.ID,
            IndiaModule.ID,
            NorthKoreaModule.ID,
        )

    fun create(
        countryId: String,
        start: LocalDate,
        random: Random,
    ): CountryModule =
        when (countryId) {
            KoreaModule.ID -> KoreaModule(random)
            UnitedStatesModule.ID -> UnitedStatesModule(start)
            IranModule.ID -> IranModule(random)
            GermanyModule.ID -> GermanyModule()
            FranceModule.ID -> FranceModule()
            JapanModule.ID -> JapanModule()
            ChinaModule.ID -> ChinaModule(random)
            IsraelModule.ID -> IsraelModule(random)
            TaiwanModule.ID -> TaiwanModule(random)
            RussiaModule.ID -> RussiaModule()
            UkraineModule.ID -> UkraineModule()
            IndiaModule.ID -> IndiaModule(random)
            NorthKoreaModule.ID -> NorthKoreaModule(random)
            else -> DefaultCountryModule(countryId)
        }
}

/** 심의 중인 법안. [upperHouseYes]는 상원이 있는 나라에서 예상 찬성표다. */
data class BillReview(
    val changes: List<PolicyChange>,
    val score: Double,
    val upperHouseYes: Int?,
    val upperHouseSeats: Int?,
    /** 하원 예상 찬성표와 표결권 있는 의석. 의회가 없으면 null. */
    val lowerHouseYes: Int? = null,
    val lowerHouseSeats: Int? = null,
    /** 법안이 늘리는 적자(GDP 대비, 감세는 과세 기반 기준). */
    val addedDeficit: Double = 0.0,
)

/**
 * 법안이 거쳐야 하는 거부 지점 하나의 판정.
 * @property delayDays 통과하지 못했을 때 법안을 죽이지 않고 늦추기만 하는 경우의 지연 일수. 0이면 거부다.
 */
data class BillCheck(
    val body: String,
    val passes: Boolean,
    val detail: String,
    val delayDays: Int = 0,
)

data class CountryMonthContext(
    val date: LocalDate,
    val approval: Double,
    val outputGap: Double,
    val inflation: Double,
    val unemployment: Double,
    val debtRatio: Double,
    /** 최근 재정수지(GDP 대비 연율, 흑자 양수). */
    val budgetBalance: Double,
    /** 명목 국가부채(자국 통화 기준, 시작 시점 달러 가치 단위). */
    val debt: Double,
    /** 국방비의 시작 대비 변화(GDP 대비). */
    val defenseChange: Double,
    /** 플레이어 국가와 다른 나라의 관계(-100~100). 외교 시스템이 채운다. */
    val relations: (String) -> Double,
)

data class CountryNews(
    val source: String,
    val text: String,
    val major: Boolean,
)

/** 고유 시스템이 이번 달 나라에 남긴 결과. 성향 변화는 점수이고 서서히 사라진다. */
data class CountryEffects(
    val outputGapShock: Double = 0.0,
    val identityMood: Map<Identity, Double> = emptyMap(),
    /** 국채 금리 목표에 더하는 위험 프리미엄(연율). 다음 달까지만 유효하다. */
    val interestPremium: Double = 0.0,
    val news: List<CountryNews> = emptyList(),
    /** 다른 나라와의 관계 변화(점). */
    val relationShifts: Map<String, Double> = emptyMap(),
    /** 한 달 동안 산업 부문 경기에 더하는 충격. */
    val sectorShocks: Map<Sector, Double> = emptyMap(),
    /** 다음 달 연율 물가에 더하는 몫. */
    val inflationShock: Double = 0.0,
) {
    companion object {
        val NONE = CountryEffects()
    }
}

data class CountryIndicator(
    val label: String,
    val value: String,
    val description: String,
    /** 0~1 막대로 보여 줄 값. 없으면 글자만 표시한다. */
    val level: Double? = null,
)

/** 고유 정책 한 줄. 정책 화면에서 쓴다. */
data class CountryPolicyLine(
    val item: CountryPolicyItem,
    val initial: Double,
    val target: Double,
)
