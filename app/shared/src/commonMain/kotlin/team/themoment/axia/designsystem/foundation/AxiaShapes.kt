package team.themoment.axia.designsystem.foundation

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

@Immutable
data class AxiaShapes(
    val control: CornerBasedShape,
    val badge: CornerBasedShape,
    val panel: CornerBasedShape,
    val pill: CornerBasedShape,
)

val defaultAxiaShapes =
    AxiaShapes(
        control = RoundedCornerShape(6.dp),
        badge = RoundedCornerShape(6.dp),
        panel = RoundedCornerShape(10.dp),
        pill = RoundedCornerShape(999.dp),
    )
