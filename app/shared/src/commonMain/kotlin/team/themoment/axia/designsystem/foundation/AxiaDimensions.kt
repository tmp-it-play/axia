package team.themoment.axia.designsystem.foundation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AxiaDimensions(
    val controlHeight: Dp,
    val minimumInteractiveSize: Dp,
    val taskRowMinHeight: Dp,
    val sidebarWidth: Dp,
    val detailPanelWidth: Dp,
    val calendarDayMinHeight: Dp,
    val dashboardSplitMinWidth: Dp,
    val dashboardStatusRailWidth: Dp,
    val dashboardStackedRailHeight: Dp,
    val taskKindColumnWidth: Dp,
    val taskLedgerInlineMinWidth: Dp,
    val taskStatusColumnWidth: Dp,
    val taskProductColumnWidth: Dp,
    val assigneeColumnWidth: Dp,
    val dueColumnWidth: Dp,
    val brandMarkSize: Dp,
    val defaultWindowWidth: Dp,
    val defaultWindowHeight: Dp,
    val minimumWindowWidth: Dp,
    val minimumWindowHeight: Dp,
    val hairline: Dp,
    val flowRail: Dp,
    val focusRing: Dp,
    val pressedBorder: Dp,
)

val defaultAxiaDimensions =
    AxiaDimensions(
        controlHeight = 36.dp,
        minimumInteractiveSize = 44.dp,
        taskRowMinHeight = 44.dp,
        sidebarWidth = 224.dp,
        detailPanelWidth = 380.dp,
        calendarDayMinHeight = 192.dp,
        dashboardSplitMinWidth = 760.dp,
        dashboardStatusRailWidth = 284.dp,
        dashboardStackedRailHeight = 320.dp,
        taskKindColumnWidth = 92.dp,
        taskLedgerInlineMinWidth = 680.dp,
        taskStatusColumnWidth = 112.dp,
        taskProductColumnWidth = 104.dp,
        assigneeColumnWidth = 104.dp,
        dueColumnWidth = 84.dp,
        brandMarkSize = 32.dp,
        defaultWindowWidth = 1440.dp,
        defaultWindowHeight = 900.dp,
        minimumWindowWidth = 1100.dp,
        minimumWindowHeight = 700.dp,
        hairline = 1.dp,
        flowRail = 2.dp,
        focusRing = 2.dp,
        pressedBorder = 2.dp,
    )
