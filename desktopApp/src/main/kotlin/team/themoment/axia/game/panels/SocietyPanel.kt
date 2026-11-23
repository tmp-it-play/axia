package team.themoment.axia.game.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.game.color
import team.themoment.axia.game.formatPoints
import team.themoment.axia.simulation.nation.NationSnapshot
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.simulation.society.Identity
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.CompositionBar
import team.themoment.axia.ui.dossier.Contribution
import team.themoment.axia.ui.dossier.ContributionBars
import team.themoment.axia.ui.dossier.LineChart
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.Segment
import team.themoment.axia.ui.dossier.StatRow
import team.themoment.axia.ui.dossier.Swatch
import kotlin.math.roundToInt

@Composable
fun SocietyPanel(nation: NationSnapshot) {
    var expanded by remember { mutableStateOf<PopulationGroup?>(null) }
    SectionTitle("15세 이상 인구 구성")
    CompositionBar(nation.groups.map { Segment(it.group.label, it.share, it.group.color()) })
    Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        nation.groups.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { Swatch(it.group.color(), "${it.group.label} ${formatPercent(it.share)}", Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    SectionTitle("인구 구조와 생활")
    val demography = nation.demography
    StatRow("합계출산율", "%.2f".format(demography.fertility))
    StatRow("기대수명", "%.1f세".format(demography.lifeExpectancy))
    StatRow(
        "아동 · 생산가능 · 고령 인구",
        "${formatPercent(demography.childShare)} · ${formatPercent(demography.workingShare)} · ${formatPercent(demography.elderlyShare)}",
    )
    StatRow("도시화율", formatPercent(demography.urbanShare))
    StatRow("순이민 (연간)", formatPopulation(demography.netMigration))
    StatRow("인적 자본 (2024년 = 100)", demography.education.roundToInt().toString())
    StatRow("실질 집값 (2024년 = 100)", demography.housePrice.roundToInt().toString())
    StatRow("소득 대비 집값 부담", formatPercent(-demography.affordability, signed = true), note = "시작 대비")
    SectionTitle("지지율이 움직인 이유")
    ApprovalBreakdown(nation)
    SectionTitle("집단 (눌러서 이유 보기)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nation.groups.forEach { group ->
            Column(Modifier.clickable { expanded = if (expanded == group.group) null else group.group }) {
                SatisfactionRow(group.group.label, group.satisfaction)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(104.dp))
                    Text(
                        text = "${formatPopulation(group.population)} · 1인당 소득 ${formatWholeDollars(group.income)}",
                        style = DossierType.number(11.sp),
                        color = DossierColors.inkMuted,
                    )
                }
                if (expanded == group.group) {
                    Column(Modifier.padding(start = 8.dp, top = 4.dp)) { FactorBars(group.factors) }
                }
            }
        }
    }
    IdentitySection(nation)
    SocialIndicatorSection(nation)
}

/** 겹칠 수 있는 성향 집단. 눌러서 만족도의 이유와 다른 성향과의 겹침을 본다. */
@Composable
private fun IdentitySection(nation: NationSnapshot) {
    var expanded by remember { mutableStateOf<Identity?>(null) }
    SectionTitle("성향 집단 (한 사람이 여러 성향에 속함)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nation.identities.forEach { identity ->
            Column(Modifier.clickable { expanded = if (expanded == identity.identity) null else identity.identity }) {
                SatisfactionRow("${identity.identity.label} ${formatPercent(identity.memberShare)}", identity.satisfaction)
                if (expanded == identity.identity) {
                    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                        ContributionBars(
                            contributions =
                                listOf(
                                    Contribution("정책", identity.policyEffect),
                                    Contribution("결과 지표", identity.outcomeEffect),
                                    Contribution("정부 언행", identity.governmentEffect),
                                ),
                            format = ::formatPoints,
                            scale = 20.0,
                        )
                        val overlaps =
                            identity.overlaps.entries
                                .sortedByDescending { it.value }
                                .take(OVERLAP_SHOWN)
                        Text(
                            text = "함께 속한 성향: " + overlaps.joinToString(" · ") { "${it.key.label} ${formatPercent(it.value)}" },
                            style = DossierType.number(11.sp),
                            color = DossierColors.inkMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SocialIndicatorSection(nation: NationSnapshot) {
    val history = nation.history
    SectionTitle("오염 지수 (2024년 1월 = 100)")
    LineChart(history.pollution, history.start, indexFormat)
    SectionTitle("범죄 지수 (2024년 1월 = 100)")
    LineChart(history.crime, history.start, indexFormat)
}

private val indexFormat: (Double) -> String = { it.roundToInt().toString() }
private const val OVERLAP_SHOWN = 3
