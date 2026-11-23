package team.themoment.axia.game.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import team.themoment.axia.simulation.economy.Commodity
import team.themoment.axia.simulation.economy.CommodityQuote
import team.themoment.axia.simulation.economy.CommodityShipment
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType
import team.themoment.axia.ui.dossier.PaperTag
import team.themoment.axia.ui.dossier.SectionTitle
import team.themoment.axia.ui.dossier.StatRow
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun CommoditySection(
    countryId: String,
    quotes: Map<Commodity, CommodityQuote>,
    date: LocalDate,
    supportedGrainTonnes: Double?,
    countryName: (String) -> String,
) {
    var selected by remember(countryId) { mutableStateOf(Commodity.GRAIN) }
    val quote = quotes.getValue(selected)
    val balance = quote.countries.getValue(countryId)
    SectionTitle("상품 수급")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Commodity.entries.forEach { commodity ->
            PaperTag(commodity.label, selected = selected == commodity, onClick = { selected = commodity })
        }
    }
    StatRow("국제 가격지수 (시작 = 100)", String.format(Locale.KOREA, "%.1f", quote.priceIndex))
    if (selected == Commodity.GRAIN) {
        val domesticSupply = quote.cerealDomesticSupplyTonnes[countryId]
        if (domesticSupply != null) {
            StatRow("FAO 곡물 국내 공급 (2023년)", quantity(domesticSupply, "톤"))
            Hint("식용·사료·가공 등 전체 용도를 포함한 연간 추계입니다. 시작 수요는 이 공급 비중을 게임의 세계 생산량에 맞춰 배분합니다.")
        } else {
            Hint("2023년 FAO 곡물 국내 공급 자료가 없어 수록국의 평균 1인당 공급량과 이 나라 인구로 시작 수요를 추정합니다.")
        }
    }
    val period = quote.period
    if (period == null) {
        StatRow("시작 재고", quantity(balance.openingStock, selected.unit))
        Hint("월간 수급과 거래량은 첫 월 정산 후 표시됩니다.")
        return
    }

    Hint("${period.year}년 ${period.monthValue}월 수급 · ${date.monthValue}월 1일 정산 · 단위 ${selected.unit}")
    StatRow("기초 재고", quantity(balance.openingStock))
    StatRow("생산", quantity(balance.production), note = if (countryId in quote.unreportedProduction) "기준연도 생산 미보고" else null)
    if (selected == Commodity.GRAIN && supportedGrainTonnes != null) {
        StatRow("생산 중 농가 가격차 보조 물량", quantity(supportedGrainTonnes))
    }
    if (selected == Commodity.GRAIN && countryId !in quote.unreportedProduction) {
        val harvest = balance.harvestFactor
        if (harvest != null) {
            StatRow("평년 연간 수확 중 ${period.monthValue}월 몫 (추정)", String.format(Locale.KOREA, "%.0f%%", harvest * 100.0 / 12.0))
            Hint("수확기에 농가와 유통 창고에 비축한 곡물은 비수기에 출하됩니다.")
        } else {
            Hint("국가별 수확 달력이 없어 기준 생산량을 매달 균등하게 배분합니다.")
        }
    }
    StatRow("수입", quantity(balance.imports))
    StatRow("수출", quantity(balance.exports))
    StatRow("수요", quantity(balance.demand))
    StatRow("실제 소비", quantity(balance.consumption))
    val unmet = (balance.demand - balance.consumption).coerceAtLeast(0.0)
    StatRow("미충족 수요", quantity(unmet), valueColor = if (unmet >= 0.5) DossierColors.negative else DossierColors.ink)
    StatRow("기말 재고", quantity(balance.closingStock))
    val routes =
        remember(countryId, quote.shipments) {
            quote.shipments.filter { it.exporter == countryId || it.importer == countryId }.sortedByDescending { it.quantity }
        }
    CommodityRoutes(countryId, selected, routes, countryName)
}

@Composable
private fun CommodityRoutes(
    countryId: String,
    commodity: Commodity,
    routes: List<CommodityShipment>,
    countryName: (String) -> String,
) {
    var expanded by remember(countryId, commodity) { mutableStateOf(false) }
    if (routes.isEmpty()) {
        Hint("이번 달에 성사된 ${commodity.label} 교역이 없습니다.")
        return
    }
    SectionTitle("거래 상대 · ${commodity.unit}")
    if (routes.size > ROUTE_PREVIEW) {
        PaperTag(
            if (expanded) "상위 ${ROUTE_PREVIEW}건만 보기" else "거래 ${routes.size}건 전체 보기",
            onClick = { expanded = !expanded },
        )
    }
    (if (expanded) routes else routes.take(ROUTE_PREVIEW)).forEach { shipment ->
        val exporting = shipment.exporter == countryId
        val partner = if (exporting) shipment.importer else shipment.exporter
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (exporting) "수출 · ${countryName(partner)}" else "수입 · ${countryName(partner)}",
                modifier = Modifier.weight(1f),
                style = DossierType.number(13.sp),
                color = DossierColors.inkMuted,
            )
            Text(quantity(shipment.quantity), style = DossierType.number(13.sp), color = DossierColors.ink)
        }
    }
}

private fun quantity(
    value: Double,
    unit: String = "",
): String = if (value > 0.0 && value < 1.0) "1$unit 미만" else String.format(Locale.KOREA, "%,.0f%s", value, unit)

private const val ROUTE_PREVIEW = 3
