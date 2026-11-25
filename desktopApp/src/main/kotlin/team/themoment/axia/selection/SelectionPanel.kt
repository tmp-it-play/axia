package team.themoment.axia.selection

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.game.panels.CommoditySection
import team.themoment.axia.game.panels.KeyIndicatorRows
import team.themoment.axia.game.panels.OpeningPolicyRate
import team.themoment.axia.game.panels.RenameControl
import team.themoment.axia.simulation.WorldSnapshot
import team.themoment.axia.simulation.economy.EconomySnapshot
import team.themoment.axia.simulation.nation.RegionSnapshot
import team.themoment.axia.simulation.nation.RegionalProgram
import team.themoment.axia.simulation.politics.CountryPoliticsSnapshot
import team.themoment.axia.simulation.world.CountryKind
import team.themoment.axia.simulation.world.World
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.DossierIndex
import team.themoment.axia.ui.dossier.DossierPanel
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import kotlin.math.roundToInt

private enum class RegionPage(
    val label: String,
) {
    SUMMARY("현황"),
    POLITICS("정치"),
    ECONOMY("경제"),
}

/** 지도에서 고른 지역과 그 나라의 경제 지표를 담은 서류철 카드. */
@Composable
fun SelectionPanel(
    world: World,
    regionId: String,
    economy: EconomySnapshot,
    snapshot: WorldSnapshot,
    playerRegion: RegionSnapshot?,
    playerId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRegionalProgram: (String, RegionalProgram) -> Unit,
    onRenameParty: (String, Int, String) -> Unit,
    onOpenDiplomacy: (String) -> Unit,
    controllerId: String? = null,
) {
    val region = world.region(regionId)
    val country = world.country(controllerId ?: region.countryId)
    val indicators = economy.countries[country.id]
    val territory = region.territoryId?.let(world::territory)
    val id = country.id
    val localRegion = playerRegion?.takeIf { id == playerId }
    val politics = snapshot.politics[id]
    var page by remember(regionId, id) { mutableStateOf(RegionPage.SUMMARY) }
    val pages =
        buildList {
            add(RegionPage.SUMMARY)
            if (politics != null) add(RegionPage.POLITICS)
            if (indicators != null) add(RegionPage.ECONOMY)
        }
    DossierPanel(
        title = country.name,
        onClose = onClose,
        scrollable = true,
        scrollKey = page,
        index = { if (pages.size > 1) DossierIndex(pages, page, RegionPage::label) { page = it } },
        modifier = modifier.width(320.dp),
    ) {
        if (country.kind == CountryKind.UNCLAIMED) {
            Text(text = "무주지", style = DossierType.title(13.sp), color = DossierColors.stamp)
        }
        territory?.let { Text(text = it.name, style = DossierType.number(13.sp), color = DossierColors.inkMuted) }
        Text(text = region.name, style = DossierType.number(14.sp), color = DossierColors.ink)
        if (country.id != region.countryId) StatRow("평화협정에 따른 관할", country.name)
        when (page) {
            RegionPage.SUMMARY -> {
                localRegion?.let { r ->
                    SectionTitle("지역 현황")
                    if (!r.participatesInDistricts) StatRow("지역구 배분", "본국 지역구 배분에서 제외")
                    StatRow("인구", formatPopulation(r.population))
                    StatRow("도시화율", "${(r.urbanShare * 100).roundToInt()}%")
                    StatRow("1인당 소득 (전국 = 100)", "${(r.relativeIncome * 100).roundToInt()}")
                    val largest = r.groupShares.maxBy { it.value }
                    StatRow("가장 큰 직업·은퇴 집단", largest.key.label, note = formatPercent(largest.value))
                    StatRow("지역 범죄 지수", "%.1f".format(r.crime), note = "2024년 전국 = 100")
                    StatRow("지역 지지율", "${r.approval.roundToInt()}%")
                    StatRow(
                        "지역 실업률",
                        formatPercent(r.economy.unemployment),
                        note = indicators?.let { "전국 ${formatPercent(it.unemployment)}" },
                    )
                    StatRow("전국 GDP 중 지역 비중", formatPercent(r.economy.gdpShare))
                    SectionTitle("지역 사업")
                    StatRow("사업 예산 · 전국 GDP 대비", formatPercent(r.programSpending))
                    RegionalProgram.entries.forEach { program ->
                        PaperTag(program.label, selected = r.program == program, onClick = { onRegionalProgram(r.regionId, program) })
                    }
                }
                if (country.kind == CountryKind.STATE && id != playerId) {
                    SectionTitle("우리나라와의 관계")
                    snapshot.diplomacy.partners.firstOrNull { it.countryId == id }?.let {
                        StatRow("관계", it.relation.roundToInt().toString())
                        PaperTag("외교 열기", onClick = { onOpenDiplomacy(id) })
                    }
                    snapshot.military.forces[id]?.let { forces ->
                        StatRow("군사력 (최강국 = 100)", "%.1f".format(forces.power) + if (forces.nuclearWarheads > 0) " · 핵보유" else "")
                    }
                }
                val wars = snapshot.military.wars.filter { it.ceasefire == null && (id in it.sideA || id in it.sideB) }
                wars.forEach { StatRow("전쟁", it.name, valueColor = DossierColors.negative) }
                snapshot.military.civilWars.firstOrNull { it.country == id && it.ended == null }?.let {
                    StatRow("내전", "${it.rebels} · ${(it.rebelControl * 100).roundToInt()}% 통제", valueColor = DossierColors.negative)
                }
            }

            RegionPage.POLITICS -> {
                politics?.let { CountryPolitics(id, it, onRenameParty) }
            }

            RegionPage.ECONOMY -> {
                localRegion?.let { r ->
                    SectionTitle("지역 산업 구성")
                    StatRow("부문", "부가가치 · 고용 비중")
                    r.economy.sectors.sortedByDescending { it.share }.forEach { sector ->
                        StatRow(sector.sector.label, "${formatPercent(sector.share)} · ${formatPercent(sector.employment)}")
                    }
                }
                if (indicators != null) {
                    SectionTitle("국가 경제 지표")
                    KeyIndicatorRows(indicators)
                    OpeningPolicyRate(economy.monetary.getValue(id))
                    CommoditySection(
                        id,
                        snapshot.trade.commodities,
                        snapshot.date,
                        indicators.supportedGrainTonnes,
                    ) { world.country(it).name }
                }
            }
        }
    }
}

@Composable
private fun CountryPolitics(
    id: String,
    politics: CountryPoliticsSnapshot,
    onRenameParty: (String, Int, String) -> Unit,
) {
    SectionTitle("정치")
    Text(politics.government, style = DossierType.title(14.sp), color = DossierColors.ink)
    StatRow("체제", politics.regime.label)
    StatRow("지지율", "${politics.approval.roundToInt()}%")
    politics.indirectPresident?.let { election ->
        StatRow("대통령 간선", election.procedure.body)
        election.nextBallot?.let { StatRow("다음 투표", it.toString()) }
        election.presidentElect?.let { StatRow("대통령 당선인", "${it.name} · 취임 ${election.accession}") }
        election.deputyBallot?.let { StatRow("부통령 간선", it.toString()) }
        election.deputyElect?.let { StatRow("부통령 당선인", "${it.name} · 취임 ${election.deputyAccession}") }
        election.divisions.lastOrNull()?.let { ballot ->
            StatRow("최근 간선 ${ballot.round}차", "${ballot.date} · ${ballot.required?.let { "가결 ${it}표" } ?: "최다 득표"}")
            StatRow("선거인단", "${ballot.college.sumOf { it.voters }}명 · 총 ${ballot.college.sumOf { it.votes }}표")
            ballot.votes.entries
                .sortedByDescending { it.value }
                .forEach { (person, count) -> StatRow(person.name, "${count}표") }
        }
    }
    politics.appointments?.let { procedure ->
        StatRow(procedure.procedure.body, procedure.procedure.label)
        procedure.nextSession?.let { StatRow("다음 인선·신임 심의", it.toString()) }
        procedure.decision?.let { StatRow("최근 인선·신임 결정", it) }
    }
    politics.listRegistration?.let { StatRow("총선 명부 등록", "${it.filed} · ${it.lists.size}건") }
    politics.nextElection?.let { StatRow("다음 선거", it.toString()) }
    politics.executiveCampaign?.let { campaign ->
        val (label, contestants) =
            when {
                campaign.houseDate != null -> {
                    "하원 선출 후보" to
                        campaign.firstBallot
                            .sortedByDescending {
                                campaign.regionalCount?.electors?.get(it.person.id) ?: 0
                            }.take(3)
                            .map { it.person }
                }

                campaign.runoffDate != null -> {
                    "결선 후보" to campaign.firstBallot.take(2).map { it.person }
                }

                campaign.primaryStandings.any { it.nextDate != null } -> {
                    "경선 후보" to
                        campaign.nominations.flatMap { listOfNotNull(it.nominee, it.rival) }
                }

                else -> {
                    "본선 후보" to campaign.nominations.map { it.nominee }
                }
            }
        StatRow(label, contestants.joinToString(" · ") { it.name })
    }
    politics.newHouseOpens?.let { StatRow("새 의회 임기 시작", it.toString()) }
    politics.upperHouse?.let { StatRow(it.name, "재적 ${it.filledSeats}명 · 정부 ${it.governmentSeats} · 야당 ${it.oppositionSeats}") }
    politics.lastUpperRenewal?.let { StatRow("상원 갱신", "${it.date} · ${it.method} · ${it.renewed}석") }
    politics.legislativeRunoff?.let { StatRow("총선 결선", "${it.nextRound} · ${it.contested}개 선거구") }
    politics.presidentElect?.let { StatRow("취임 예정", "${it.person.name} · ${it.inauguration}") }
    politics.lastElection
        ?.executive
        ?.winner
        ?.let { StatRow("최근 대통령 당선인", it.name) }
    politics.newParties.forEach { party ->
        StatRow(party.name, "${party.identity.label} · ${formatPercent(party.support)}")
        RenameControl(id to party.foundation.id, party.name, politics.takenPartyNames) {
            onRenameParty(id, party.foundation.id, it)
        }
    }
    politics.legislature?.groups.orEmpty().forEach { party ->
        party.foundation?.let { origin ->
            StatRow(party.name, "${party.role.label} · ${party.seats}석")
            RenameControl(id to origin.id, party.name, politics.takenPartyNames) { onRenameParty(id, origin.id, it) }
        }
    }
    politics.pendingBill?.let { StatRow(if (it.conference) "양원 절충안" else "심의 중", "${it.title} · ${it.sponsor}") }
    politics.lastBill?.let { bill ->
        val vote = bill.vote
        StatRow("최근 예산안", "${bill.title} · ${if (bill.approved == true) "가결" else "부결"}")
        vote?.let { StatRow("찬성 · 가결선", "${it.yes}표 · ${it.required}표") }
    }
}
