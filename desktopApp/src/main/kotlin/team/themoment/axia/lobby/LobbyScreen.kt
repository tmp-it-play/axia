package team.themoment.axia.lobby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.themoment.axia.map.MapProjection
import team.themoment.axia.map.WorldMapModel
import team.themoment.axia.theme.DossierColors
import team.themoment.axia.theme.DossierType

private val Ocean = Color(0xFF182F32)
private val Shadow = Color(0xFF10191D)
private val Coast = Color(0xFFB5A67F)
private val LandInks = listOf(Color(0xFF52645C), Color(0xFF627466), Color(0xFF69776A), Color(0xFF596C64))

@Composable
internal fun LobbyScreen(
    mapModel: WorldMapModel,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onLoad: () -> Unit,
    onNewGame: () -> Unit,
    onExit: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Shadow)) {
        LobbyAtlas(mapModel)
        Text(
            "AXIA",
            modifier = Modifier.align(Alignment.TopStart).padding(start = maxWidth * 0.066f, top = maxHeight * 0.09f),
            style = DossierType.title(96.sp).copy(letterSpacing = 8.sp),
            color = DossierColors.paper,
        )
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = maxWidth * 0.058f).width(272.dp),
            horizontalAlignment = Alignment.End,
        ) {
            LobbyItem("게임 이어하기", canContinue, onContinue)
            LobbyItem("게임 불러오기", onClick = onLoad)
            LobbyItem("새 게임 시작하기", onClick = onNewGame)
            Spacer(Modifier.height(22.dp))
            LobbyItem("모드", enabled = false)
            LobbyItem("설정", enabled = false)
            Spacer(Modifier.height(22.dp))
            LobbyItem("종료", onClick = onExit)
        }
    }
}

@Composable
private fun LobbyItem(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val highlighted = enabled && (hovered || focused)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (highlighted) {
            Spacer(Modifier.width(2.dp).height(24.dp).background(Coast))
        }
        val color =
            when {
                highlighted -> DossierColors.paper
                enabled -> DossierColors.deskText
                else -> Color(0xFF83908C)
            }
        Text(label, modifier = Modifier.padding(end = 17.dp), style = DossierType.title(26.sp, FontWeight.SemiBold), color = color)
    }
}

/** 게임의 해안선과 국가 형상을 사용해 인쇄한 해도처럼 그린다. */
@Composable
private fun LobbyAtlas(model: WorldMapModel) {
    Canvas(Modifier.fillMaxSize()) {
        val zoom = minOf(size.width / 300f, size.height / 172f)
        val origin = Offset(size.width * 0.34f, size.height * 0.61f)
        drawRect(Brush.radialGradient(listOf(Ocean, Shadow), center = origin, radius = size.width * 0.76f))
        withTransform({
            translate(origin.x, origin.y)
            rotate(-9f, pivot = Offset.Zero)
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            model.countryFills.forEach { land -> drawPath(land.path, LandInks[land.colorIndex.mod(LandInks.size)]) }
            val grid = Coast.copy(alpha = 0.23f)
            val line = 0.6.dp.toPx() / zoom
            for (longitude in -180..180 step 15) {
                val x = longitude.toFloat()
                drawLine(grid, Offset(x, MapProjection.top), Offset(x, MapProjection.bottom), line)
            }
            for (latitude in -75..75 step 15) {
                val y = MapProjection.y(latitude.toFloat())
                drawLine(grid, Offset(-180f, y), Offset(180f, y), line)
            }
            val coastStroke = Stroke(width = 0.8.dp.toPx() / zoom)
            model.coasts.forEach { tile -> drawPath(tile.path, Coast.copy(alpha = 0.72f), style = coastStroke) }
        }
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.41f to Color.Transparent,
                0.73f to Shadow.copy(alpha = 0.9f),
                1f to Shadow,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Shadow.copy(alpha = 0.87f),
                0.34f to Color.Transparent,
                0.71f to Color.Transparent,
                1f to Shadow.copy(alpha = 0.81f),
            ),
        )
    }
}
