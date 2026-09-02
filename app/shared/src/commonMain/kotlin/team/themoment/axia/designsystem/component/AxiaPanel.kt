package team.themoment.axia.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AxiaPanel(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AxiaTheme.semanticColors.surface,
    contentColor: Color = AxiaTheme.semanticColors.textPrimary,
    borderColor: Color = AxiaTheme.semanticColors.border,
    contentPadding: PaddingValues = PaddingValues(AxiaTheme.spacing.space6),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = AxiaTheme.shapes.panel,
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(AxiaTheme.dimensions.hairline, borderColor),
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}
