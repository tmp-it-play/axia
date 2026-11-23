package team.themoment.axia.selection

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.game.panels.CommoditySection
import team.themoment.axia.game.panels.KeyIndicatorRows
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.RegionSnapshot
import team.themoment.axia.simulation.nation.RegionalProgram
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierPanel
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

/** 지도에서 고른 지역과 그 나라의 경제 지표를 담은 서류철 카드. */
@Composable
fun SelectionPanel(
    world: World,
    regionId: String,
    economy: EconomyIndicators?,
    snapshot: WorldSnapshot,
    playerRegion: RegionSnapshot?,
    playerId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRegionalProgram: (String, RegionalProgram) -> Unit,
    controllerId: String? = null,
) {
    val region = world.region(regionId)
    val country = world.country(controllerId ?: region.countryId)
    val territory = region.territoryId?.let(world::territory)
    DossierPanel(title = country.name, onClose = onClose, scrollable = true, modifier = modifier.width(320.dp)) {
        if (country.kind == CountryKind.UNCLAIMED) {
            Text(text = "무주지", style = DossierType.title(13.sp), color = DossierColors.stamp)
        }
        territory?.let { Text(text = it.name, style = DossierType.number(13.sp), color = DossierColors.inkMuted) }
        Text(text = region.name, style = DossierType.number(14.sp), color = DossierColors.ink)
        if (country.id != region.countryId) StatRow("평화협정에 따른 관할", country.name)
        playerRegion?.takeIf { playerId == country.id }?.let { r ->
            SectionTitle("지역 현황")
            if (!r.participatesInDistricts) StatRow("지역구 배분", "본국 지역구 배분에서 제외")
            StatRow("인구", formatPopulation(r.population))
            val source = r.populationSource ?: "도시 인구·면적 추정"
            Text("초기 인구 비중 · $source", style = DossierType.number(11.sp), color = DossierColors.inkMuted)
            StatRow("도시화율 (추정)", "${(r.urbanShare * 100).roundToInt()}%")
            StatRow("1인당 소득 (전국 = 100)", "${(r.relativeIncome * 100).roundToInt()}")
            val largest = r.groupShares.maxBy { it.value }
            StatRow("가장 큰 직업·은퇴 집단", largest.key.label, note = formatPercent(largest.value))
            StatRow("지역 범죄 지수", "%.1f".format(r.crime), note = "2024년 전국 = 100")
            StatRow("지역 지지율", "${r.approval.roundToInt()}%")
            StatRow("지역 실업률", formatPercent(r.economy.unemployment), note = economy?.let { "전국 ${formatPercent(it.unemployment)}" })
            StatRow("전국 GDP 중 지역 비중", formatPercent(r.economy.gdpShare))
            SectionTitle("지역 사업")
            StatRow("사업 예산 · 전국 GDP 대비", formatPercent(r.programSpending))
            if (country.id == playerId) {
                RegionalProgram.entries.forEach { program ->
                    PaperTag(program.label, selected = r.program == program, onClick = { onRegionalProgram(r.regionId, program) })
                }
            }
            SectionTitle("지역 산업 구성 (추정)")
            StatRow("부문", "부가가치 · 고용 비중")
            r.economy.sectors.sortedByDescending { it.share }.forEach { sector ->
                StatRow(sector.sector.label, "${formatPercent(sector.share)} · ${formatPercent(sector.employment)}")
            }
        }
        val id = country.id
        snapshot.politics[id]?.let { politics ->
            SectionTitle("정치")
            Text(politics.government, style = DossierType.title(14.sp), color = DossierColors.ink)
            StatRow("체제", politics.regime.label)
            StatRow("지지율 (추정)", "${politics.approval.roundToInt()}%")
            politics.nextElection?.let { StatRow("다음 선거", it.toString()) }
            politics.executiveCampaign?.let { campaign ->
                val contestants =
                    if (campaign.firstBallot.isEmpty()) {
                        campaign.nominations.map {
                            it.nominee
                        }
                    } else {
                        campaign.firstBallot.take(2).map { it.person }
                    }
                StatRow(if (campaign.runoffDate == null) "본선 후보" else "결선 후보", contestants.joinToString(" · ") { it.name })
            }
            politics.lastElection
                ?.executive
                ?.winner
                ?.let { StatRow("최근 대통령 당선인", it.name) }
            politics.newParties.forEach { party -> StatRow(party.name, "${party.identity.label} · ${formatPercent(party.support)}") }
            politics.pendingBill?.let { StatRow(if (it.conference) "양원 절충안" else "심의 중", "${it.title} · ${it.sponsor}") }
            politics.lastBill?.let { bill ->
                val vote = bill.vote
                StatRow("최근 예산안", "${bill.title} · ${if (bill.approved == true) "가결" else "부결"}")
                vote?.let { StatRow("찬성 · 가결선", "${it.yes}표 · ${it.required}표") }
            }
        }
        if (country.kind == CountryKind.STATE && id != playerId) {
            SectionTitle("우리나라와의 관계")
            snapshot.diplomacy.relations[id]?.let { StatRow("관계", "${it.roundToInt()}") }
            snapshot.military.forces[id]?.let { forces ->
                StatRow("군사력 (최강국 = 100)", "%.1f".format(forces.power) + if (forces.nuclearWarheads > 0) " · 핵보유" else "")
            }
        }
        val wars = snapshot.military.wars.filter { it.ceasefire == null && (id in it.sideA || id in it.sideB) }
        wars.forEach { StatRow("전쟁", it.name, valueColor = DossierColors.negative) }
        snapshot.military.civilWars.firstOrNull { it.country == id && it.ended == null }?.let {
            StatRow("내전", "${it.rebels} · ${(it.rebelControl * 100).roundToInt()}% 통제", valueColor = DossierColors.negative)
        }
        if (economy != null) {
            SectionTitle("국가 경제 지표")
            KeyIndicatorRows(economy)
            CommoditySection(id, snapshot.trade.commodities, snapshot.date, economy.supportedGrainTonnes) { world.country(it).name }
        }
    }
}
