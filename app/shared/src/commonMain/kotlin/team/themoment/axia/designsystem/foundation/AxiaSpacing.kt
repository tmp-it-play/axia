package team.themoment.axia.designsystem.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AxiaSpacing(
    val space1: Dp,
    val space2: Dp,
    val space3: Dp,
    val space4: Dp,
    val space5: Dp,
    val space6: Dp,
    val space8: Dp,
    val space10: Dp,
)

val defaultAxiaSpacing =
    AxiaSpacing(
        space1 = 4.dp,
        space2 = 8.dp,
        space3 = 12.dp,
        space4 = 16.dp,
        space5 = 20.dp,
        space6 = 24.dp,
        space8 = 32.dp,
        space10 = 40.dp,
    )
