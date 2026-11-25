package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.game.color
import team.themoment.axia.game.formatPoints
import team.themoment.axia.game.satisfactionColor
import team.themoment.axia.simulation.nation.GroupSnapshot
import team.themoment.axia.simulation.nation.IdentitySnapshot
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.ui.dossier.CompositionBar
import team.themoment.axia.ui.dossier.Contribution
import team.themoment.axia.ui.dossier.ContributionBars
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Segment
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.Swatch
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class SocietyTab(
    val label: String,
) {
    POPULATION("인구·생활"),
    OCCUPATIONS("직업 집단"),
    IDENTITIES("성향 집단"),
    APPROVAL("정부 지지율"),
}

@Composable
internal fun SocietyPanel(
    nation: NationSnapshot,
    tab: SocietyTab,
) {
    var occupation by remember(nation.countryId) { mutableStateOf(nation.groups.maxByOrNull { it.share }?.group) }
    var identity by remember(nation.countryId) { mutableStateOf(nation.identities.maxByOrNull { it.memberShare }?.identity) }
    key(tab) {
        when (tab) {
            SocietyTab.POPULATION -> {
                PanelColumns(left = { Demographics(nation) }, right = { LivingConditions(nation) })
            }

            SocietyTab.OCCUPATIONS -> {
                SocietyRegister(
                    backLabel = "직업 집단 목록으로",
                    list = { openDetail ->
                        LedgerHeading("15세 이상 인구 구성")
                        val groups = remember(nation.groups) { nation.groups.sortedByDescending { it.share } }
                        CompositionBar(groups.map { Segment(it.group.label, it.share, it.group.color()) })
                        SocietyRegisterHeader("직업 집단", "인구 비중", "만족도")
                        Column(Modifier.selectableGroup()) {
                            groups.forEach { group ->
                                SocietyRegisterRow(
                                    group.group.label,
                                    formatPercent(group.share),
                                    group.satisfaction.roundToInt().toString(),
                                    satisfactionColor(group.satisfaction),
                                    group.group == occupation,
                                    group.group.color(),
                                ) {
                                    occupation = group.group
                                    openDetail()
                                }
                            }
                        }
                    },
                    detail = {
                        nation.groups.find { it.group == occupation }?.let { OccupationDetail(nation, it) }
                    },
                )
            }

            SocietyTab.IDENTITIES -> {
                SocietyRegister(
                    backLabel = "성향 집단 목록으로",
                    list = { openDetail ->
                        LedgerHeading("성향별 소속률")
                        SocietyRegisterHeader("성향 집단", "소속률", "만족도")
                        Column(Modifier.selectableGroup()) {
                            nation.identities.sortedByDescending { it.memberShare }.forEach { group ->
                                SocietyRegisterRow(
                                    group.identity.label,
                                    formatPercent(group.memberShare),
                                    group.satisfaction.roundToInt().toString(),
                                    satisfactionColor(group.satisfaction),
                                    group.identity == identity,
                                ) {
                                    identity = group.identity
                                    openDetail()
                                }
                            }
                        }
                        Hint("한 사람이 여러 성향에 속할 수 있습니다.")
                    },
                    detail = {
                        nation.identities.find { it.identity == identity }?.let { IdentityDetail(nation, it) }
                    },
                )
            }

            SocietyTab.APPROVAL -> {
                PanelColumns(
                    left = {
                        LedgerHeading("정부 지지율")
                        StatRow("현재 지지율", "%.1f%%".format(nation.approval), valueColor = satisfactionColor(nation.approval))
                        StatRow("시작 대비", formatPoints(nation.approval - nation.initialApproval.approval) + "%p")
                        SectionTitle("월별 지지율")
                        LineChart(nation.history.approval, nation.history.start, { "%.1f%%".format(it) })
                    },
                    right = {
                        LedgerHeading("지지율 변동 요인")
                        ApprovalBreakdown(nation)
                    },
                )
            }
        }
    }
}

@Composable
private fun Demographics(nation: NationSnapshot) {
    LedgerHeading("연령과 경제활동")
    val demography = nation.demography
    val ages =
        listOf(
            Segment("0~14세", demography.childShare, DossierColors.positive),
            Segment("15~64세", demography.workingShare, DossierColors.blueInk),
            Segment("65세 이상", demography.elderlyShare, DossierColors.folderEdge),
        )
    CompositionBar(ages)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ages.forEach { Swatch(it.color, "${it.label} ${formatPercent(it.fraction)}", Modifier.weight(1f)) }
    }
    StatRow("합계출산율", "%.2f".format(demography.fertility))
    StatRow("기대수명", "%.1f세".format(demography.lifeExpectancy))
    StatRow("경제활동참가율 (15세 이상)", formatPercent(demography.adultParticipation))
    StatRow(
        "15~64세 · 65세 이상 참가율",
        "${formatPercent(demography.workingAgeParticipation)} · ${formatPercent(demography.olderParticipation)}",
        note = "${demography.participationYear}년",
    )
    SectionTitle("노령연금")
    val earlyPensionAges = demography.earlyPensionFromAge?.let { "$it~64세" } ?: "65세 미만"
    StatRow(
        "$earlyPensionAges 노령연금 수급자",
        demography.earlyPensionOfWorkingAge?.let { formatPercent(it) } ?: "—",
        note = demography.earlyPensionYear?.let { "${it}년 · 15~64세 인구 대비" },
    )
    val pension = demography.olderPension
    if (pension != null) {
        val ages = pension.throughAge?.let { "65~${it}세" } ?: "65세 이상"
        val lower = formatPercent(pension.rate)
        val upper = pension.upperRate?.let { formatPercent(it) }
        val coverage = if (upper == null || upper == lower) lower else "$lower~$upper"
        StatRow("$ages 연금 수급률", coverage, note = "${pension.year}년")
    } else {
        StatRow("65세 이상 연금 수급률", "—")
    }
    StatRow("연금 수요 인구", formatPercent(demography.pensionDemandShare), note = "전체 인구 대비")
}

@Composable
private fun LivingConditions(nation: NationSnapshot) {
    val demography = nation.demography
    LedgerHeading("주거와 생활")
    StatRow("도시화율", formatPercent(demography.urbanShare))
    StatRow("순이민 (연환산)", formatPopulation(demography.netMigration))
    StatRow("인적 자본 (2024년 = 100)", demography.education.roundToInt().toString())
    StatRow("실질 집값 (2024년 = 100)", demography.housePrice.roundToInt().toString())
    StatRow("소득 대비 집값 부담", formatPercent(-demography.affordability, signed = true), note = "시작 대비")
    SectionTitle("오염 지수")
    StatRow("2024년 1월 = 100", indexFormat(nation.social.pollution))
    LineChart(nation.history.pollution, nation.history.start, indexFormat, height = 88.dp)
    SectionTitle("범죄 지수")
    StatRow("2024년 1월 = 100", indexFormat(nation.social.crime))
    LineChart(nation.history.crime, nation.history.start, indexFormat, height = 88.dp)
}

@Composable
private fun OccupationDetail(
    nation: NationSnapshot,
    group: GroupSnapshot,
) {
    LedgerHeading(group.group.label)
    SatisfactionRow("만족도", group.satisfaction)
    StatRow("인구", formatPopulation(group.population))
    StatRow("1인당 연간 소득", formatWholeDollars(group.income))
    if (group.group == PopulationGroup.RETIREES) {
        val earlyAge = nation.demography.earlyPensionFromAge?.let { "$it~64세" } ?: "65세 미만"
        val earlyRetirees = if (nation.demography.earlyPensionOfWorkingAge != null) "와 $earlyAge 비취업 노령연금 수급자" else ""
        Hint("65세 이상 비경제활동인구$earlyRetirees")
    } else {
        val movement =
            when {
                group.netJobMoves < 0.0 -> "${formatPopulation(abs(group.netJobMoves))} 순유출"
                group.netJobMoves > 0.0 -> "${formatPopulation(group.netJobMoves)} 순유입"
                else -> "이동 없음"
            }
        StatRow("이번 달 직업 이동", movement)
    }
    SectionTitle("만족도 변동 요인")
    FactorBars(group.factors)
    nation.history.satisfaction[group.group]?.takeIf { it.size > 1 }?.let {
        SectionTitle("월별 만족도")
        LineChart(it, nation.history.start, indexFormat)
    }
}

@Composable
private fun IdentityDetail(
    nation: NationSnapshot,
    group: IdentitySnapshot,
) {
    LedgerHeading(group.identity.label)
    SatisfactionRow("만족도", group.satisfaction)
    StatRow("성인 중 소속률", formatPercent(group.memberShare))
    StatRow("시작 대비 소속률", formatPoints(100.0 * (group.memberShare - group.initialShare)) + "%p")
    SectionTitle("만족도 변동 요인")
    ContributionBars(
        contributions =
            listOf(
                Contribution("정책", group.policyEffect),
                Contribution("결과 지표", group.outcomeEffect),
                Contribution("정부 언행", group.governmentEffect),
            ),
        format = ::formatPoints,
        scale = 20.0,
    )
    SectionTitle("함께 속한 성향")
    group.overlaps.entries.sortedByDescending { it.value }.take(OVERLAP_SHOWN).forEach {
        StatRow(it.key.label, formatPercent(it.value))
    }
    nation.history.identities[group.identity]?.takeIf { it.size > 1 }?.let {
        SectionTitle("월별 만족도")
        LineChart(it, nation.history.start, indexFormat)
    }
}

private val indexFormat: (Double) -> String = { it.roundToInt().toString() }
private const val OVERLAP_SHOWN = 3
