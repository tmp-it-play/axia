package team.themoment.axia.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.themoment.axia.designsystem.theme.AxiaTheme

/** Shared column geometry for ledger headers and rows, including the narrow detail-panel layout. */
@Composable
fun AxiaTaskLedgerColumns(
    title: @Composable () -> Unit,
    status: @Composable () -> Unit,
    product: @Composable () -> Unit,
    assignee: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = Arrangement.spacedBy(AxiaTheme.spacing.space3)
        if (maxWidth >= AxiaTheme.dimensions.taskLedgerInlineMinWidth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
                Box(Modifier.weight(1f)) { title() }
                Box(Modifier.width(AxiaTheme.dimensions.taskStatusColumnWidth)) { status() }
                Box(Modifier.width(AxiaTheme.dimensions.taskProductColumnWidth)) { product() }
                Box(Modifier.width(AxiaTheme.dimensions.assigneeColumnWidth)) { assignee() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3)) {
                title()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
                    Box(Modifier.width(AxiaTheme.dimensions.taskStatusColumnWidth)) { status() }
                    Box(Modifier.width(AxiaTheme.dimensions.taskProductColumnWidth)) { product() }
                    Box(Modifier.weight(1f)) { assignee() }
                }
            }
        }
    }
}
