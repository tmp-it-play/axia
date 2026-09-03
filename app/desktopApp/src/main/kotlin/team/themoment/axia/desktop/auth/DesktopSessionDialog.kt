package team.themoment.axia.desktop.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import team.themoment.axia.auth.DesktopSession
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.AxiaThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DesktopSessionDialog(
    controller: DesktopAuthController,
    themeMode: AxiaThemeMode,
    onClose: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<DesktopSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<SessionRevocation?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        message = null
        try {
            sessions = controller.listSessions().sessions
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            message = "기기 목록을 불러오지 못했습니다. 네트워크와 로그인 상태를 확인해 주세요."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(controller) { reload() }
    DialogWindow(onCloseRequest = onClose, title = "기기 세션 관리", state = rememberDialogState(width = 640.dp, height = 540.dp)) {
        AxiaTheme(themeMode) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AxiaTheme.spacing.space6),
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space4),
            ) {
                Text("로그인한 기기", style = AxiaTheme.typography.sectionTitle, color = AxiaTheme.semanticColors.textPrimary)
                Text(
                    "각 세션은 처음 로그인한 시점부터 240일 동안 유지됩니다. 기기를 해제하면 즉시 접근할 수 없습니다.",
                    style = AxiaTheme.typography.body,
                    color = AxiaTheme.semanticColors.textSecondary,
                )
                message?.let { AxiaBanner("요청을 확인해 주세요", description = it, style = AxiaBannerStyle.Warning) }
                if (loading) Text("기기 목록을 확인하고 있어요", color = AxiaTheme.semanticColors.textSecondary)
                sessions.forEach { session ->
                    AxiaDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
                            Text(
                                session.deviceName + if (session.isCurrent) " · 현재 기기" else "",
                                style = AxiaTheme.typography.bodyEmphasis,
                                color = AxiaTheme.semanticColors.textPrimary,
                            )
                            Text(
                                "만료 ${sessionDate(session.expiresAtEpochMillis)}",
                                style = AxiaTheme.typography.metadata,
                                color = AxiaTheme.semanticColors.textSecondary,
                            )
                        }
                        AxiaButton(
                            "해제",
                            onClick = { confirmation = SessionRevocation.One(session) },
                            enabled = !loading,
                            style = AxiaButtonStyle.Secondary,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                    AxiaButton("새로고침", onClick = { scope.launch { reload() } }, enabled = !loading, style = AxiaButtonStyle.Secondary)
                    AxiaButton("모든 기기 해제", onClick = { confirmation = SessionRevocation.All }, enabled = !loading && sessions.isNotEmpty())
                }
                confirmation?.let { selected ->
                    AxiaBanner(
                        title = if (selected is SessionRevocation.All) "현재 기기를 포함한 모든 세션을 종료할까요?" else "이 기기의 세션을 종료할까요?",
                        description = "다시 사용하려면 해당 기기에서 로그인해야 합니다.",
                        style = AxiaBannerStyle.Warning,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
                        AxiaButton("해제 확인", onClick = {
                            scope.launch {
                                loading = true
                                try {
                                    when (selected) {
                                        SessionRevocation.All -> controller.revokeAllSessions()
                                        is SessionRevocation.One -> controller.revokeSession(selected.session.id)
                                    }
                                    confirmation = null
                                    if (controller.state.value !is DesktopAuthState.SignedIn) onClose() else reload()
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Exception) {
                                    message = "해제 결과를 확인하지 못했습니다. 목록을 새로고침해 현재 상태를 확인해 주세요."
                                    confirmation = null
                                } finally {
                                    loading = false
                                }
                            }
                        }, enabled = !loading)
                        AxiaButton("취소", onClick = { confirmation = null }, enabled = !loading, style = AxiaButtonStyle.Secondary)
                    }
                }
            }
        }
    }
}

private sealed interface SessionRevocation {
    data object All : SessionRevocation

    data class One(
        val session: DesktopSession,
    ) : SessionRevocation
}

private fun sessionDate(epochMillis: Long): String =
    DateTimeFormatter
        .ofPattern("yyyy.MM.dd HH:mm")
        .withZone(ZoneId.of("Asia/Seoul"))
        .format(Instant.ofEpochMilli(epochMillis))
