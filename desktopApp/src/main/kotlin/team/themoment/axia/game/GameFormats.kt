package team.themoment.axia.game

import androidx.compose.ui.graphics.Color
import team.themoment.axia.map.MapPalette
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

fun formatPoints(value: Double): String = String.format(java.util.Locale.KOREA, "%+.1f", value)
