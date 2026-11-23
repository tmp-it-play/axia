package team.themoment.axia.newgame

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import team.themoment.axia.CampaignSlot
import team.themoment.axia.SavedCampaign
import team.themoment.axia.format.formatDollars
import team.themoment.axia.format.formatPercent
import team.themoment.axia.format.formatPopulation
import team.themoment.axia.format.formatWholeDollars
import team.themoment.axia.simulation.Simulation
import team.themoment.axia.simulation.campaign.CampaignScenario
import team.themoment.axia.simulation.economy.EconomyData
import team.themoment.axia.simulation.economy.InitialEconomy
import team.themoment.axia.simulation.world.Country
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.Stamp
import team.themoment.axia.ui.dossier.StatRow
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 추정 항목이 이만큼 이상이면 카드에 "추정 자료" 도장을 찍는다. */
private const val ESTIMATED_STAMP_THRESHOLD = 5

/** 책상 위에 놓인 국가별 서류철에서 플레이할 나라를 고른다. */
@Composable
internal fun NewGameScreen(
    data: EconomyData,
    playable: List<Country>,
    slots: Map<CampaignSlot, SavedCampaign?>,
    onStart: (String, CampaignScenario) -> Unit,
    onLoad: (CampaignSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scenario by remember { mutableStateOf(CampaignScenario.HISTORICAL) }
    var selected by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize().background(DossierColors.desk).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text(text = "AXIA", style = DossierType.title(40.sp), color = DossierColors.deskText)
        Text(
            text = "${DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN).format(Simulation.START_DATE)} · 집무를 맡을 국가를 고르십시오",
            style = DossierType.number(15.sp),
            color = DossierColors.deskTextMuted,
        )
        if (slots.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("이어하기", style = DossierType.title(16.sp), color = DossierColors.deskText)
                CampaignSlot.entries.filter { it in slots }.forEach { slot ->
                    val saved = slots[slot]
                    val detail =
                        saved?.let { state ->
                            val country = playable.firstOrNull { it.id == state.countryId }
                            val scenarioName = if (state.scenario == CampaignScenario.HISTORICAL) "" else " · ${state.scenario.label}"
                            "${country?.name ?: state.countryId} · ${state.date}$scenarioName"
                        } ?: "파일 확인 필요"
                    PaperTag("${slot.label} · $detail", onClick = { onLoad(slot) })
                }
            }
        }
        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CampaignScenario.entries.forEach { choice ->
                PaperTag(choice.label, selected = scenario == choice, onClick = { scenario = choice })
            }
        }
        Text(scenario.description, style = DossierType.number(13.sp), color = DossierColors.deskTextMuted)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            modifier = Modifier.weight(1f).padding(top = 20.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(playable, key = Country::id) { country ->
                CountryCard(
                    country = country,
                    economy = scenario.startingEconomy(data.country(country.id)),
                    selected = country.id == selected,
                    onClick = { selected = country.id },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val chosen = playable.firstOrNull { it.id == selected }
            Text(
                text = chosen?.let { "선택: ${it.name}" } ?: "서류철을 눌러 국가를 고르십시오",
                style = DossierType.title(16.sp),
                color = DossierColors.deskText,
            )
            Spacer(Modifier.weight(1f))
            PaperTag(text = "이 국가로 집무 시작", enabled = chosen != null, onClick = { chosen?.let { onStart(it.id, scenario) } })
        }
    }
}

@Composable
private fun CountryCard(
    country: Country,
    economy: InitialEconomy,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .padding(start = 10.dp)
                .background(if (selected) DossierColors.paper else DossierColors.folder, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(text = country.name, style = DossierType.title(15.sp), color = DossierColors.ink)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(DossierColors.folder, RoundedCornerShape(topEnd = 5.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
                .border(
                    2.dp,
                    if (selected) DossierColors.blueInk else DossierColors.folderEdge.copy(alpha = 0.4f),
                    RoundedCornerShape(5.dp),
                ).padding(6.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().background(DossierColors.paper).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                StatRow("인구", formatPopulation(economy.population))
                StatRow("GDP", formatDollars(economy.gdp))
                StatRow("1인당 GDP", formatWholeDollars(economy.gdp / economy.population))
                StatRow("성장률", formatPercent(economy.growth, signed = true))
                StatRow("물가", formatPercent(economy.inflation))
                StatRow("국가부채", formatPercent(economy.debt))
                Spacer(Modifier.height(2.dp))
            }
            if (economy.estimated.size >= ESTIMATED_STAMP_THRESHOLD) {
                Stamp(text = "추정 자료", modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp))
            }
        }
    }
}
