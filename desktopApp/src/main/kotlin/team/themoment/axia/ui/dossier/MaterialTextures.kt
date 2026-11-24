package team.themoment.axia.ui.dossier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import team.themoment.axia.resources.Res
import team.themoment.axia.resources.bond_paper
import team.themoment.axia.resources.manila_stock
import team.themoment.axia.resources.patinated_steel
import team.themoment.axia.theme.DossierColors
import kotlin.math.roundToInt

@Composable
internal fun Modifier.folderTexture(): Modifier = sheetTexture(Res.drawable.manila_stock, DossierColors.folder, 0.9f)

@Composable
internal fun Modifier.paperTexture(): Modifier = sheetTexture(Res.drawable.bond_paper, DossierColors.paper, 0.8f)

@Composable
private fun Modifier.sheetTexture(
    resource: DrawableResource,
    base: Color,
    opacity: Float,
): Modifier {
    val bitmap = imageResource(resource)
    return drawWithCache {
        val sheet = IntSize(size.width.roundToInt(), size.height.roundToInt())
        // 종이의 접힌 자국은 표지와 작은 색인 탭에서도 같은 비율을 유지한다.
        val coverScale = maxOf(size.width.coerceAtLeast(1f) / bitmap.width, size.height.coerceAtLeast(1f) / bitmap.height)
        val sample =
            IntSize(
                (size.width / coverScale).roundToInt().coerceIn(1, bitmap.width),
                (size.height / coverScale).roundToInt().coerceIn(1, bitmap.height),
            )
        val origin = IntOffset((bitmap.width - sample.width) / 2, 0)
        onDrawBehind {
            drawRect(base)
            if (sheet.width > 0 && sheet.height > 0) {
                drawImage(bitmap, srcOffset = origin, srcSize = sample, dstSize = sheet, alpha = opacity)
            }
        }
    }
}

@Composable
internal fun Modifier.steelTexture(): Modifier {
    val bitmap = imageResource(Res.drawable.patinated_steel)
    return drawWithCache {
        val brushedSteel = ShaderBrush(ImageShader(bitmap, TileMode.Mirror, TileMode.Mirror))
        onDrawBehind {
            drawRect(Color(0xFF303735))
            drawRect(brushedSteel, alpha = 0.8f)
        }
    }
}
