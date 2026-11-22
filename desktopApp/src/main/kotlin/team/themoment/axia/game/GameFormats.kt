package team.themoment.axia.game

import androidx.compose.ui.graphics.Color
import team.themoment.axia.map.MapPalette
import team.themoment.axia.simulation.economy.EconomyIndicators
import team.themoment.axia.simulation.nation.PopulationGroup
import team.themoment.axia.theme.DossierColors

/** 인구 집단 색. 지도 정치 색과 같은 계열로 맞춘다. */
fun PopulationGroup.color(): Color = MapPalette.countries[ordinal % MapPalette.countries.size]

/** 지지율, 만족도 수준에 따른 잉크 색. */
fun satisfactionColor(value: Double): Color =
    when {
        value >= 55.0 -> DossierColors.positive
        value < 40.0 -> DossierColors.negative
        else -> DossierColors.ink
    }

/** 실측값이 없어 추정한 항목이면 "추정"을 돌려준다. */
fun EconomyIndicators.note(vararg fields: String): String? = if (fields.any { it in estimated }) "추정" else null

fun formatPoints(value: Double): String = String.format(java.util.Locale.KOREA, "%+.1f", value)
