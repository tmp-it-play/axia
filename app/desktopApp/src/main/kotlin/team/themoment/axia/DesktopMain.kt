package team.themoment.axia

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import team.themoment.axia.app.WorkspaceGateway
import team.themoment.axia.app.WorkspaceResponse
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.foundation.defaultAxiaDimensions
import team.themoment.axia.designsystem.theme.AxiaTheme
import team.themoment.axia.designsystem.theme.LocalAxiaReduceMotion
import team.themoment.axia.desktop.DesktopInstanceLock
import team.themoment.axia.desktop.DesktopLifecycle
import team.themoment.axia.desktop.DesktopMotionPreferenceState
import team.themoment.axia.desktop.DesktopMotionState
import team.themoment.axia.desktop.auth.DesktopAuthApi
import team.themoment.axia.desktop.auth.DesktopAuthController
import team.themoment.axia.desktop.auth.DesktopAuthHost
import team.themoment.axia.desktop.auth.DesktopAuthState
import team.themoment.axia.desktop.auth.DesktopSessionDialog
import team.themoment.axia.desktop.auth.DesktopSystemBrowser
import team.themoment.axia.desktop.auth.JdkDesktopApiTransport
import team.themoment.axia.desktop.notifications.DesktopNotificationPump
import team.themoment.axia.desktop.notifications.DesktopNotificationStatus
import team.themoment.axia.desktop.security.DesktopPlatformSupport
import team.themoment.axia.desktop.updates.DesktopUpdateStatus
import team.themoment.axia.desktop.updates.DesktopUpdater
import team.themoment.axia.feature.auth.AuthScreen
import team.themoment.axia.feature.auth.AuthUiState
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun main() {
    System.setProperty("apple.awt.application.name", "Axia")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    val instanceLock =
        try {
            DesktopInstanceLock.acquire()
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(null, "Axia 실행 잠금을 만들지 못했습니다. 앱 데이터 폴더 접근 권한을 확인해 주세요.")
            return
        }
    if (instanceLock == null) {
        JOptionPane.showMessageDialog(null, "Axia가 이미 실행 중입니다. 메뉴바 또는 트레이에서 Axia 열기를 선택해 주세요.")
        return
    }
    val motion = DesktopMotionState()
    runBlocking(motion) {
        awaitApplication {
            val motionPreference by motion.state.collectAsState()
            LaunchedEffect(motion) { motion.observe() }
            CompositionLocalProvider(LocalAxiaReduceMotion provides motionPreference.reduceMotion) {
                val theme = remember { DesktopThemeState() }
                val themeMode = theme.mode
                var windowVisible by remember { mutableStateOf(true) }
                var showSessions by remember { mutableStateOf(false) }
                var lifecycleFailure by remember { mutableStateOf<String?>(null) }
                var backgroundMenuReady by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val startup = remember { runCatching { DesktopRuntime.create() } }
                val runtime = startup.getOrNull()
                val controller = runtime?.controller
                val gateway =
                    remember(controller) {
                        controller?.let { auth ->
                            WorkspaceGateway { method, path, body ->
                                val response = auth.request(method, path, body)
                                WorkspaceResponse(response.status, response.body)
                            }
                        }
                    }
                val authState = controller?.state?.collectAsState()?.value
                var lifecycle by remember { mutableStateOf<DesktopLifecycle?>(null) }
                val notificationPump =
                    remember(controller) {
                        controller?.let { auth ->
                            DesktopNotificationPump(auth, notify = { notification ->
                                withContext(Dispatchers.Swing) {
                                    val activeLifecycle = lifecycle
                                    if (activeLifecycle == null) {
                                        false
                                    } else {
                                        activeLifecycle.notify(notification.title, notification.body)
                                        true
                                    }
                                }
                            })
                        }
                    }
                val notificationStatus = notificationPump?.status?.collectAsState()?.value
                val updater = remember { DesktopUpdater.create { SwingUtilities.invokeLater { exitApplication() } } }
                val updateStatus by updater.status.collectAsState()

                fun logout() {
                    scope.launch {
                        try {
                            val confirmed = controller?.logout() ?: return@launch
                            showSessions = false
                            windowVisible = true
                            if (!confirmed) lifecycle?.notify("이 기기에서 로그아웃했어요", "서버 세션 종료는 확인하지 못했습니다. 다른 로그인 기기에서 세션 목록을 확인해 주세요.")
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            lifecycleFailure = "보안 저장소의 로그인 정보를 삭제하지 못했습니다. 접근 권한을 확인한 뒤 로그아웃을 다시 시도해 주세요."
                            windowVisible = true
                        }
                    }
                }

                DisposableEffect(runtime) {
                    controller?.start()
                    try {
                        lifecycle =
                            DesktopLifecycle(
                                onShow = { windowVisible = true },
                                onSessions = {
                                    windowVisible = true
                                    showSessions = true
                                },
                                onLogout = { logout() },
                                onExit = { exitApplication() },
                            )
                        backgroundMenuReady = true
                        notificationPump?.start()
                    } catch (_: Exception) {
                        lifecycleFailure = "백그라운드 메뉴를 만들지 못했습니다. 다시 열 수 없는 상태가 되지 않도록 창을 유지합니다."
                    }
                    onDispose {
                        updater.close()
                        notificationPump?.close()
                        lifecycle?.close()
                        runtime?.close()
                        instanceLock.close()
                    }
                }

                Window(
                    onCloseRequest = { if (backgroundMenuReady) windowVisible = false },
                    visible = windowVisible,
                    state =
                        rememberWindowState(
                            width = defaultAxiaDimensions.defaultWindowWidth,
                            height = defaultAxiaDimensions.defaultWindowHeight,
                        ),
                    title = "Axia",
                ) {
                    DisposableEffect(window, updater) {
                        // WinSparkle initializes only after the native main window is visible.
                        val listener =
                            object : WindowAdapter() {
                                override fun windowOpened(event: WindowEvent) {
                                    updater.start()
                                }
                            }
                        window.addWindowListener(listener)
                        if (window.isShowing) updater.start()
                        onDispose { window.removeWindowListener(listener) }
                    }
                    LaunchedEffect(window) {
                        window.minimumSize =
                            Dimension(
                                defaultAxiaDimensions.minimumWindowWidth.value.roundToInt(),
                                defaultAxiaDimensions.minimumWindowHeight.value.roundToInt(),
                            )
                    }
                    LaunchedEffect(windowVisible) {
                        if (windowVisible) {
                            window.toFront()
                            window.requestFocus()
                        }
                    }

                    MenuBar {
                        Menu("Axia") {
                            Item("기기 세션 관리", onClick = { showSessions = true }, enabled = authState is DesktopAuthState.SignedIn)
                            Item("로그아웃", onClick = { logout() }, enabled = controller != null)
                            Item("업데이트 확인", onClick = updater::checkForUpdates)
                            Separator()
                            Item("창 숨기기", onClick = { windowVisible = false }, enabled = backgroundMenuReady)
                            Item("종료", onClick = ::exitApplication)
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        DesktopThemeFailureNotice(theme)
                        if (motionPreference == DesktopMotionPreferenceState.Unavailable) {
                            AxiaTheme(themeMode) {
                                AxiaBanner(
                                    "운영체제 동작 설정을 읽지 못했어요",
                                    description = "동작 감소를 적용한 상태로 설정을 다시 확인합니다.",
                                    style = AxiaBannerStyle.Warning,
                                    modifier = Modifier.padding(AxiaTheme.spacing.space3),
                                )
                            }
                        }
                        if (notificationStatus == DesktopNotificationStatus.DeliveryUnavailable) {
                            AxiaTheme(themeMode) {
                                AxiaBanner(
                                    "시스템 알림을 전달하지 못했어요",
                                    description = "운영체제의 Axia 알림 설정을 확인해 주세요. 알림함에서는 계속 확인할 수 있습니다.",
                                    style = AxiaBannerStyle.Warning,
                                    modifier = Modifier.padding(AxiaTheme.spacing.space3),
                                )
                            }
                        }
                        lifecycleFailure?.let { message ->
                            AxiaTheme(themeMode) {
                                AxiaBanner(
                                    title = "Desktop 기능을 확인해 주세요",
                                    description = message,
                                    style = AxiaBannerStyle.Warning,
                                    modifier = Modifier.padding(AxiaTheme.spacing.space3),
                                )
                            }
                        }
                        if (controller != null) {
                            DesktopAuthHost(controller, themeMode) {
                                App(
                                    themeMode = themeMode,
                                    onThemeModeChange = theme::select,
                                    gateway = gateway,
                                    onManageSessions = { showSessions = true },
                                    onLogout = { logout() },
                                    onCheckUpdates = updater::checkForUpdates,
                                    updateStatusMessage = updateStatus.label,
                                )
                            }
                        } else {
                            AxiaTheme(themeMode) {
                                AuthScreen(
                                    state = startupFailure(startup.exceptionOrNull()),
                                    onLogin = {},
                                    onReopenBrowser = {},
                                    onCancel = {},
                                    onRetry = {},
                                    onOpenDataGsmMyInfo = {},
                                )
                            }
                        }
                    }
                }
                if (showSessions && controller != null && authState is DesktopAuthState.SignedIn) {
                    DesktopSessionDialog(controller, themeMode, onClose = { showSessions = false })
                }
            }
        }
    }
    exitProcess(0)
}

private val DesktopUpdateStatus.label: String
    get() =
        when (this) {
            is DesktopUpdateStatus.Unavailable -> reason
            DesktopUpdateStatus.Ready -> "자동 업데이트가 활성화되어 있습니다."
            DesktopUpdateStatus.Checking -> "새 업데이트를 확인하고 있습니다."
            DesktopUpdateStatus.Downloading -> "서명된 업데이트를 다운로드하고 있습니다."
            DesktopUpdateStatus.UpdateReady -> "업데이트 준비가 끝났습니다. 업데이트 창에서 다시 시작하거나 나중에 설치할 수 있습니다."
            DesktopUpdateStatus.Installing -> "업데이트를 설치하고 다시 시작합니다."
            DesktopUpdateStatus.NoUpdate -> "현재 최신 버전입니다."
            is DesktopUpdateStatus.Error -> reason
        }

private fun startupFailure(failure: Throwable?): AuthUiState.Failure {
    val description =
        when (failure) {
            is UnsupportedOperationException -> "Apple Silicon macOS 또는 x64 Windows에서 실행해 주세요."
            is LinkageError -> "운영체제의 보안 저장소 연결 모듈을 불러오지 못했습니다. 올바른 아키텍처의 앱을 다시 설치해 주세요."
            is IllegalArgumentException -> "AXIA_DESKTOP_API_BASE_URL이 사용자 정보나 query 없이 올바른 HTTPS 주소인지 확인해 주세요."
            else -> "Desktop 초기화에 실패했습니다. 앱 설치와 운영체제 보안 설정을 확인해 주세요."
        }
    return AuthUiState.Failure("Desktop을 시작하지 못했어요", description, canRetry = false)
}

private class DesktopRuntime(
    val controller: DesktopAuthController,
    private val transport: JdkDesktopApiTransport,
) : AutoCloseable {
    override fun close() {
        controller.close()
        transport.close()
    }

    companion object {
        fun create(): DesktopRuntime {
            val platform = DesktopPlatformSupport.current()
            val baseUrl = JdkDesktopApiTransport.configuredBaseUrl()
            val store = DesktopPlatformSupport.credentialStore(baseUrl)
            val transport = JdkDesktopApiTransport(baseUrl)
            return try {
                DesktopRuntime(DesktopAuthController(DesktopAuthApi(transport), store, platform, DesktopSystemBrowser()), transport)
            } catch (failure: Throwable) {
                transport.close()
                throw failure
            }
        }
    }
}
