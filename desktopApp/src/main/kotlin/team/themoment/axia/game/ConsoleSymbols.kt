package team.themoment.axia.game

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser

private val pewter = Color(0xFFCED1BD)
private val bronze = Color(0xFFDAC18C)
private val copper = Color(0xFFDEAB8D)
private val enamel = Color(0xFFA9C8C0)
private val recess = Color(0xFF26302C)

/** 같은 24단위 판형에 주조한 실루엣과, 안쪽에 새긴 선으로 구성한 콘솔 표식이다. */
internal enum class ConsoleSymbol(
    val face: String,
    val cuts: String = "",
    val metal: Color = pewter,
) {
    FOLDER(
        "M2 6H9L11 8H21V10H6L3 19H2Z M6.8 11H23L20 20H4Z",
        "M9 14H17",
        bronze,
    ),
    FACTORY(
        "M2 20V11L9 7V11L15 7V11H16.5L17 3H20.5L21 11H22V20Z",
        "M5 14H7V16H5Z M10 14H12V16H10Z M15 14H17V16H15Z",
    ),
    VAULT(
        "M4 3H20Q22 3 22 5V18Q22 20 20 20H19V22H16V20H8V22H5V20H3V5Q3 3 4 3Z",
        "M6 6H19V17H6Z M15 11.5A2.6 2.6 0 1 1 9.8 11.5A2.6 2.6 0 1 1 15 11.5 M12.4 9V7.8 M10.3 13L9.4 14 M14.6 13L15.4 14",
        bronze,
    ),
    DECREE(
        "M3 4H13L16 7H7V18H14L13 21H3Z M10 9H15L17 7L21 11L16 16L10 18L11 13Z M18 6L20 4L23 7L21 10Z",
        "M10.8 17L14.5 13.4",
        pewter,
    ),
    ASSEMBLY(
        "M2 21V18H4V10H2L12 3L22 10H20V18H22V21Z",
        "M5 10H19 M8 12V17 M12 12V17 M16 12V17 M5 18H19",
        bronze,
    ),
    HIERARCHY(
        "M8 2H16V8H13V10H20V15H23V22H16V15H18V12H6V15H8V22H1V15H4V10H11V8H8Z",
        "M10.5 4.8H13.5 M3 18.5H5.7 M18 18.5H20.6",
        enamel,
    ),
    CITIZENS(
        "M13 7.5A3.5 3.5 0 1 1 6 7.5A3.5 3.5 0 1 1 13 7.5Z M2 20V17Q3 12 9.5 12Q16 12 17 17V20Z " +
            "M20 8.5A2.7 2.7 0 1 1 14.6 8.5A2.7 2.7 0 1 1 20 8.5Z M17 12.5Q22 12.5 23 17V19H19V17Q18.7 14 17 12.5Z",
        "M6 16V18 M13.5 16V18",
    ),
    GLOBE(
        "M14 2A7 7 0 1 1 14 16A7 7 0 1 1 14 2Z M4.2 6L6.2 6.7A8.8 8.8 0 0 0 19.8 16.4L21.2 18A10.9 10.9 0 0 1 14 20V21H18V23H7V21H11V19.6A10.6 10.6 0 0 1 4.2 6Z",
        "M8.5 7.8Q14 10.1 19.8 10.3 M15 3.5Q11.5 7.5 13 14.8",
        enamel,
    ),
    SHIELD(
        "M3 3L12 5L21 3V12Q20 17.5 12 22Q4 17.5 3 12Z",
        "M7 12.5L12 8L17 12.5 M8 15.5L12 12L16 15.5",
    ),
    FIELD_RADIO(
        "M5 8L9.5 1L11.2 1.7L7.5 8H19Q21 8 21 10V20H3V10Q3 8 5 8Z M6 20H9V22H6Z M16 20H19V22H16Z",
        "M5.8 11H13V16.5H5.8Z M16 11.8H18.5 M16 15H18.5",
        copper,
    ),
    CHART(
        "M2 4H4V19H22V21H2Z M6 18V12H9.5V18Z M11.5 18V8H15V18Z M17 18V3H20.5V18Z",
        metal = enamel,
    ),
    FLAG(
        "M3 2H5V22H3Z M6.5 3Q10 1 14 3.3Q18 5.6 22 3.2V13Q18 15.2 14 13Q10 10.7 6.5 13Z",
        "M14 5V10.7",
        bronze,
    ),
    BALLOT_PAPER(
        "M6 3L16 1L19 13L9 15Z M2 17H22V21H2Z",
        "M9.5 7.7L12 9.7L15.6 5.1",
        bronze,
    ),
    BALLOT_BOX(
        "M3 12L6 8H9L10 12H17L18.5 8H19L22 12V21H2V12Z M8 4L16 2L18 9L10 11Z",
        "M5 13H19 M6 17H11",
        enamel,
    ),
    LECTERN(
        "M3 8H21L19 12H17L16 19H19V22H5V19H8L7 12H5Z M9 7V5L12 3H15V5H12.4L11 6V7Z",
        "M8 11H16 M10 14H14",
        bronze,
    ),
    GROWTH(
        "M2 17L8 10L12 13L18 6H14V3H23V12H20V8.5L12 18L8.3 14.5L4 20Z",
        metal = enamel,
    ),
    PRICE_TAG(
        "M2 10L10 2H20L22 4V13L13 22Z",
        "M16 6.8A1.7 1.7 0 1 1 19.4 6.8A1.7 1.7 0 1 1 16 6.8 M7.5 11L12.3 15.8",
        copper,
    ),
    BRIEFCASE(
        "M8 5Q8 3 10 3H14Q16 3 16 5V7H21Q22 7 22 9V19Q22 21 20 21H4Q2 21 2 19V9Q2 7 4 7H8Z",
        "M10 7V5H14V7 M3.5 11Q12 15.5 20.5 11 M11 13H13.5V16H11Z",
    ),
    SCALES(
        "M11 3H13V5.3L21 4.3L21.3 6.4L14 7.4V18L18 20V21H6V20L10 18V8L2.8 8.8L2.5 6.7L11 5.6Z " +
            "M1 13H8Q7.5 16 4.5 16Q1.5 16 1 13Z M16 11H23Q22.5 14 19.5 14Q16.5 14 16 11Z",
        "M2.2 13L4.4 8.8L6.8 13 M17.3 11L19.3 6.6L21.8 11",
        bronze,
    ),
    BOND(
        "M3 6H5V19H18V21H3Z M7 3H21V17H7Z",
        "M10 7H18 M10 10H16 M10 13H12 M16 13.2L18 12.4",
        copper,
    ),
    INTEREST(
        "M2 11C2 8.7 5.5 7 10 7S18 8.7 18 11V18C18 20.3 14.5 22 10 22S2 20.3 2 18Z " +
            "M12 5C12 3.3 14.4 2 17.4 2S23 3.3 23 5V13C23 14.5 21.4 15.6 19.5 16V10Q19.5 6.2 12 5.7Z",
        "M3.5 11.3Q10 16 16.6 11.3 M3.5 15Q10 19.4 16.6 15 M15 4.9Q18.3 6.4 21.5 4.9 M20.5 9L22 8.5",
        bronze,
    ),
    CALENDAR(
        "M3 5H6V2H8V5H16V2H18V5H21V21H3Z",
        "M5 9H19 M7 12H10 M13 12H16 M7 16H10",
    ),
    PLAY("M6 2L21 12L6 22Z"),
    PAUSE("M4 3H10V21H4Z M14 3H20V21H14Z"),
    NEXT_DAY("M3 3L16 12L3 21Z M17.5 3H21V21H17.5Z"),
    SPEED("M2 4L12 12L2 20Z M12 4L22 12L12 20Z", metal = bronze),
    ;

    val silhouette by lazy { PathParser().parsePathString(face).toPath() }
    val engraving by lazy { PathParser().parsePathString(cuts).toPath() }
}

@Composable
internal fun ConsoleGlyph(
    symbol: ConsoleSymbol,
    modifier: Modifier,
    color: Color = symbol.metal,
) {
    val face =
        remember(color) {
            Brush.verticalGradient(
                listOf(lerp(color, Color.White, 0.22f), color, lerp(color, recess, 0.3f)),
                startY = 2f,
                endY = 22f,
            )
        }
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            translate(left = 0.45f, top = 1.2f) { drawPath(symbol.silhouette, recess) }
            drawPath(symbol.silhouette, face)
            if (symbol.cuts.isNotEmpty()) {
                drawPath(symbol.engraving, recess, style = Stroke(width = 1.55f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
