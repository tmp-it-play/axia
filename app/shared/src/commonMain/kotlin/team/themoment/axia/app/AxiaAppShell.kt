package team.themoment.axia.app

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import team.themoment.axia.designsystem.component.AxiaEmptyState
import team.themoment.axia.designsystem.component.AxiaIcon
import team.themoment.axia.designsystem.component.AxiaIconKind
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import team.themoment.axia.feature.dashboard.DashboardScreen
import team.themoment.axia.feature.dashboard.DashboardUiState
import team.themoment.axia.feature.dashboard.GitHubConnectionState
import team.themoment.axia.feature.dashboard.GitHubReadOnlyBanner
import team.themoment.axia.feature.settings.SettingsScreen
import team.themoment.axia.navigation.AxiaDestination

private const val GITHUB_STATUS_URL = "https://www.githubstatus.com/"

@Composable
fun AxiaAppShell(
    appState: AxiaAppState,
    dashboardState: DashboardUiState,
    themeMode: AxiaThemeMode,
    onThemeModeChange: (AxiaThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier =
            modifier
                .fillMaxSize()
                .background(AxiaTheme.semanticColors.background)
                .safeContentPadding(),
    ) {
        AxiaSidebar(
            destinations = appState.destinations,
            currentDestination = appState.currentDestination,
            onDestinationSelected = appState::navigate,
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            PageHeader(destination = appState.currentDestination)
            if (dashboardState.githubConnection == GitHubConnectionState.ReadOnly) {
                GitHubReadOnlyBanner(
                    onOpenStatus = { uriHandler.openUri(GITHUB_STATUS_URL) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = AxiaTheme.spacing.space8,
                                end = AxiaTheme.spacing.space8,
                                bottom = AxiaTheme.spacing.space5,
                            ),
                )
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = AxiaTheme.spacing.space8,
                            end = AxiaTheme.spacing.space8,
                            bottom = AxiaTheme.spacing.space8,
                        ),
            ) {
                if (content != null) {
                    content()
                } else {
                    when (appState.currentDestination) {
                        AxiaDestination.Dashboard -> {
                            DashboardScreen(
                                state = dashboardState,
                                onBrowseTasks = { appState.navigate(AxiaDestination.Tasks) },
                            )
                        }

                        AxiaDestination.Settings -> {
                            SettingsScreen(
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                            )
                        }

                        else -> {
                            DestinationEmptyState(destination = appState.currentDestination)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxiaSidebar(
    destinations: List<AxiaDestination>,
    currentDestination: AxiaDestination,
    onDestinationSelected: (AxiaDestination) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(AxiaTheme.dimensions.sidebarWidth)
                .fillMaxHeight()
                .background(AxiaTheme.semanticColors.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = AxiaTheme.spacing.space5),
        ) {
            SidebarBrand()
            Column(
                modifier = Modifier.padding(top = AxiaTheme.spacing.space6),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
            ) {
                destinations.filterNot { it == AxiaDestination.Settings }.forEach { destination ->
                    SidebarItem(
                        destination = destination,
                        selected = destination == currentDestination,
                        onClick = { onDestinationSelected(destination) },
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (AxiaDestination.Settings in destinations) {
                SidebarItem(
                    destination = AxiaDestination.Settings,
                    selected = currentDestination == AxiaDestination.Settings,
                    onClick = { onDestinationSelected(AxiaDestination.Settings) },
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(AxiaTheme.dimensions.hairline)
                    .fillMaxHeight()
                    .background(AxiaTheme.semanticColors.border),
        )
    }
}

@Composable
private fun SidebarBrand() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AxiaTheme.spacing.space5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
    ) {
        Box(
            modifier =
                Modifier
                    .size(AxiaTheme.dimensions.brandMarkSize)
                    .background(AxiaTheme.semanticColors.action, AxiaTheme.shapes.control),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                color = AxiaTheme.semanticColors.onAction,
                style = AxiaTheme.typography.bodyEmphasis,
            )
        }
        Column {
            Text(
                text = "AXIA",
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.bodyEmphasis,
            )
            Text(
                text = "작업 원장",
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.metadata,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    destination: AxiaDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = AxiaTheme.semanticColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
                .padding(horizontal = AxiaTheme.spacing.space3)
                .border(
                    width = if (isFocused) AxiaTheme.dimensions.focusRing else 0.dp,
                    color = if (isFocused) colors.action else Color.Transparent,
                    shape = AxiaTheme.shapes.control,
                ).background(
                    color = if (selected) colors.surfaceSubtle else Color.Transparent,
                    shape = AxiaTheme.shapes.control,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    contentDescription = destination.label
                    stateDescription = if (selected) "선택됨" else "선택되지 않음"
                    role = Role.Button
                }.padding(end = AxiaTheme.spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(AxiaTheme.dimensions.flowRail)
                    .defaultMinSize(minHeight = AxiaTheme.dimensions.taskRowMinHeight)
                    .background(if (selected) colors.action else Color.Transparent),
        )
        Row(
            modifier = Modifier.padding(start = AxiaTheme.spacing.space3),
            horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxiaIcon(
                kind = destination.iconKind(),
                contentDescription = null,
                tint = if (selected) colors.action else colors.textSecondary,
            )
            Text(
                text = destination.label,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                style =
                    AxiaTheme.typography.body.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
            )
        }
    }
}

@Composable
private fun PageHeader(destination: AxiaDestination) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = AxiaTheme.spacing.space8,
                    top = AxiaTheme.spacing.space8,
                    end = AxiaTheme.spacing.space8,
                    bottom = AxiaTheme.spacing.space5,
                ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1),
        ) {
            Text(
                text = destination.label,
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.pageTitle,
            )
            Text(
                text = destination.description,
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.body,
            )
        }
    }
}

@Composable
private fun DestinationEmptyState(destination: AxiaDestination) {
    AxiaPanel(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AxiaEmptyState(
                title = "${destination.label} 화면은 아직 구현 중입니다",
                description = "연결되지 않은 데이터나 예시 값은 표시하지 않습니다. 기능이 준비되면 실제 상태를 여기에서 확인할 수 있습니다.",
            )
        }
    }
}

private fun AxiaDestination.iconKind(): AxiaIconKind =
    when (this) {
        AxiaDestination.Dashboard -> AxiaIconKind.Dashboard
        AxiaDestination.Tasks -> AxiaIconKind.Tasks
        AxiaDestination.Products -> AxiaIconKind.Products
        AxiaDestination.Members -> AxiaIconKind.Members
        AxiaDestination.Calendar -> AxiaIconKind.Calendar
        AxiaDestination.Analytics -> AxiaIconKind.Analytics
        AxiaDestination.Notifications -> AxiaIconKind.Notifications
        AxiaDestination.Management -> AxiaIconKind.Management
        AxiaDestination.Settings -> AxiaIconKind.Settings
    }
