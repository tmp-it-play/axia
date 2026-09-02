package team.themoment.axia.designsystem.component

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AxiaDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = AxiaTheme.dimensions.hairline,
        color = AxiaTheme.semanticColors.border,
    )
}
