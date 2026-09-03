package team.themoment.axia.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import team.themoment.axia.auth.AuthBlockReason
import team.themoment.axia.designsystem.component.AxiaBanner
import team.themoment.axia.designsystem.component.AxiaBannerStyle
import team.themoment.axia.designsystem.component.AxiaButton
import team.themoment.axia.designsystem.component.AxiaButtonStyle
import team.themoment.axia.designsystem.component.AxiaDivider
import team.themoment.axia.designsystem.component.AxiaPanel
import team.themoment.axia.designsystem.theme.AxiaTheme

@Composable
fun AuthScreen(
    state: AuthUiState,
    onLogin: () -> Unit,
    onReopenBrowser: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenDataGsmMyInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(AxiaTheme.semanticColors.background)
                .padding(
                    horizontal = AxiaTheme.spacing.space10,
                    vertical = AxiaTheme.spacing.space8,
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        AxiaPanel(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = state.accessibilityDescription
                    },
                verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space6),
            ) {
                AxiaBrandLockup()
                AxiaDivider()
                when (state) {
                    AuthUiState.SignedOut -> {
                        SignedOutContent(onLogin = onLogin)
                    }

                    AuthUiState.RestoringSession -> {
                        AuthStateHeader("세션을 확인하고 있어요", "이 기기의 보안 저장소에서 로그인 상태를 확인합니다.")
                    }

                    AuthUiState.CreatingTransaction -> {
                        CreatingTransactionContent(onCancel = onCancel)
                    }

                    is AuthUiState.AwaitingBrowser -> {
                        AwaitingBrowserContent(
                            onReopenBrowser = onReopenBrowser,
                            onCancel = onCancel,
                            browserLaunchFailed = state.browserLaunchFailed,
                        )
                    }

                    is AuthUiState.Failure -> {
                        AuthStateHeader(state.title, state.description)
                        if (state.canRetry) {
                            AxiaButton(label = state.retryLabel, onClick = onRetry)
                        }
                    }

                    is AuthUiState.Blocked -> {
                        BlockedContent(
                            state = state,
                            onRetry = onRetry,
                            onOpenDataGsmMyInfo = onOpenDataGsmMyInfo,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AxiaBrandLockup() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space3),
    ) {
        Box(
            modifier =
                Modifier
                    .size(AxiaTheme.dimensions.brandMarkSize)
                    .background(
                        color = AxiaTheme.semanticColors.action,
                        shape = AxiaTheme.shapes.control,
                    ).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                color = AxiaTheme.semanticColors.onAction,
                style = AxiaTheme.typography.bodyEmphasis,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space1)) {
            Text(
                text = "Axia",
                color = AxiaTheme.semanticColors.textPrimary,
                style = AxiaTheme.typography.bodyEmphasis,
            )
            Text(
                text = "더모먼트 작업 원장",
                color = AxiaTheme.semanticColors.textSecondary,
                style = AxiaTheme.typography.metadata,
            )
        }
    }
}

@Composable
private fun SignedOutContent(onLogin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        AuthStateHeader(
            title = "Axia에 로그인",
            description = "DataGSM 계정으로 더모먼트 업무 공간에 들어갑니다.",
        )
        AxiaButton(
            label = "DataGSM으로 로그인",
            onClick = onLogin,
            style = AxiaButtonStyle.Primary,
        )
        Text(
            text = "로그인은 시스템 기본 브라우저에서 진행됩니다.",
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.metadata,
        )
    }
}

@Composable
private fun CreatingTransactionContent(onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        AuthStateHeader(
            title = "로그인을 준비하고 있어요",
            description = "DataGSM에서 본인 확인을 시작할 수 있도록 안전한 요청을 만들고 있습니다.",
        )
        AxiaBanner(
            title = "로그인 요청 생성 중",
            description = "잠시만 기다려 주세요.",
            style = AxiaBannerStyle.Info,
            announceChanges = true,
        )
        AxiaButton(
            label = "로그인 취소",
            onClick = onCancel,
            style = AxiaButtonStyle.Secondary,
        )
    }
}

@Composable
private fun AwaitingBrowserContent(
    onReopenBrowser: () -> Unit,
    onCancel: () -> Unit,
    browserLaunchFailed: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        AuthStateHeader(
            title = "브라우저에서 로그인을 완료해 주세요",
            description = "시스템 기본 브라우저에서 DataGSM 로그인을 마치면 Axia가 자동으로 이어집니다.",
        )
        AxiaBanner(
            title = if (browserLaunchFailed) "기본 브라우저를 열지 못했어요" else "브라우저 응답 대기 중",
            description = if (browserLaunchFailed) "기본 브라우저 설정을 확인한 뒤 다시 열어 주세요." else "이 창은 닫지 않아도 됩니다.",
            style = if (browserLaunchFailed) AxiaBannerStyle.Warning else AxiaBannerStyle.Info,
            announceChanges = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
            AxiaButton(
                label = "브라우저 다시 열기",
                onClick = onReopenBrowser,
                style = AxiaButtonStyle.Primary,
            )
            AxiaButton(
                label = "로그인 취소",
                onClick = onCancel,
                style = AxiaButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun BlockedContent(
    state: AuthUiState.Blocked,
    onRetry: () -> Unit,
    onOpenDataGsmMyInfo: () -> Unit,
) {
    val content = state.reason.content
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space5)) {
        AuthStateHeader(
            title = content.title,
            description = content.description,
        )
        state.safeCurrentUserDisplayName
            ?.takeIf { state.reason == AuthBlockReason.GitHubIdConflict }
            ?.takeIf(String::isNotBlank)
            ?.let { displayName ->
                Text(
                    text = "확인한 계정: $displayName",
                    color = AxiaTheme.semanticColors.textSecondary,
                    style = AxiaTheme.typography.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        AxiaBanner(
            title = content.guidanceTitle,
            description = content.guidance,
            style = content.bannerStyle,
            announceChanges = true,
        )
        BlockedActions(
            reason = state.reason,
            onRetry = onRetry,
            onOpenDataGsmMyInfo = onOpenDataGsmMyInfo,
        )
    }
}

@Composable
private fun BlockedActions(
    reason: AuthBlockReason,
    onRetry: () -> Unit,
    onOpenDataGsmMyInfo: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
        when (reason) {
            AuthBlockReason.NotTheMomentMember -> {
                AxiaButton(
                    label = "다른 계정으로 로그인",
                    onClick = onRetry,
                )
            }

            AuthBlockReason.GitHubIdMissing,
            AuthBlockReason.GitHubAccountNotFound,
                -> {
                    AxiaButton(
                        label = "DataGSM 내 정보 열기",
                        onClick = onOpenDataGsmMyInfo,
                    )
                    AxiaButton(
                        label = "수정 완료 — 다시 확인",
                        onClick = onRetry,
                        style = AxiaButtonStyle.Secondary,
                    )
                }

            AuthBlockReason.GitHubIdConflict -> {
                AxiaButton(
                    label = "DataGSM 내 정보 확인",
                    onClick = onOpenDataGsmMyInfo,
                )
                AxiaButton(
                    label = "다시 확인",
                    onClick = onRetry,
                    style = AxiaButtonStyle.Secondary,
                )
            }

            AuthBlockReason.GitHubOrganizationMembershipRequired -> {
                AxiaButton(
                    label = "가입 완료 — 다시 확인",
                    onClick = onRetry,
                )
            }

            AuthBlockReason.ExternalAuthenticationFailed -> {
                AxiaButton(
                    label = "로그인 다시 시도",
                    onClick = onRetry,
                )
            }
        }
    }
}

@Composable
private fun AuthStateHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AxiaTheme.spacing.space2)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = AxiaTheme.semanticColors.textPrimary,
            style = AxiaTheme.typography.pageTitle,
        )
        Text(
            text = description,
            color = AxiaTheme.semanticColors.textSecondary,
            style = AxiaTheme.typography.body,
        )
    }
}

private data class AuthBlockContent(
    val title: String,
    val description: String,
    val guidanceTitle: String,
    val guidance: String,
    val bannerStyle: AxiaBannerStyle,
)

private val AuthBlockReason.content: AuthBlockContent
    get() =
        when (this) {
            AuthBlockReason.NotTheMomentMember -> {
                AuthBlockContent(
                    title = "더모먼트 구성원만 사용할 수 있어요",
                    description = "DataGSM에서 현재 더모먼트 전공동아리 소속을 확인할 수 없습니다.",
                    guidanceTitle = "소속 정보를 확인해 주세요",
                    guidance = "소속 정보가 잘못되었다면 DataGSM에서 확인한 뒤 다른 계정으로 다시 로그인해 주세요.",
                    bannerStyle = AxiaBannerStyle.Warning,
                )
            }

            AuthBlockReason.GitHubIdMissing -> {
                AuthBlockContent(
                    title = "GitHub ID가 등록되어 있지 않아요",
                    description = "Axia를 사용하려면 DataGSM 내 정보에 본인의 GitHub 사용자 이름이 필요합니다.",
                    guidanceTitle = "DataGSM에서 GitHub ID를 등록해 주세요",
                    guidance = "등록을 마치고 이 화면으로 돌아와 다시 확인하면 됩니다.",
                    bannerStyle = AxiaBannerStyle.Warning,
                )
            }

            AuthBlockReason.GitHubAccountNotFound -> {
                AuthBlockContent(
                    title = "GitHub 계정을 찾을 수 없어요",
                    description = "DataGSM에 등록된 GitHub ID와 일치하는 계정이 없습니다.",
                    guidanceTitle = "GitHub 사용자 이름을 확인해 주세요",
                    guidance = "철자와 현재 사용자 이름을 확인해 DataGSM 내 정보에서 수정해 주세요.",
                    bannerStyle = AxiaBannerStyle.Danger,
                )
            }

            AuthBlockReason.GitHubIdConflict -> {
                AuthBlockContent(
                    title = "이 GitHub ID는 이미 사용 중이에요",
                    description = "Axia에서는 하나의 GitHub ID를 한 사람만 연결할 수 있습니다.",
                    guidanceTitle = "본인 계정인지 확인해 주세요",
                    guidance = "DataGSM의 GitHub ID가 본인 계정인지 확인한 뒤 다시 시도해 주세요. Axia에서 연결을 강제로 바꿀 수는 없습니다.",
                    bannerStyle = AxiaBannerStyle.Danger,
                )
            }

            AuthBlockReason.GitHubOrganizationMembershipRequired -> {
                AuthBlockContent(
                    title = "themoment-team 조직 가입이 필요해요",
                    description = "현재 GitHub 계정이 themoment-team의 정식 조직 멤버로 확인되지 않았습니다.",
                    guidanceTitle = "조직 초대를 확인해 주세요",
                    guidance = "초대를 수락해 정식 멤버가 된 뒤 다시 확인해 주세요. Outside collaborator는 로그인할 수 없습니다.",
                    bannerStyle = AxiaBannerStyle.Warning,
                )
            }

            AuthBlockReason.ExternalAuthenticationFailed -> {
                AuthBlockContent(
                    title = "로그인 확인을 완료하지 못했어요",
                    description = "외부 인증 서비스의 응답을 확인하는 중 문제가 발생했습니다.",
                    guidanceTitle = "잠시 후 다시 시도해 주세요",
                    guidance = "문제가 계속되면 서비스 상태를 확인해 주세요.",
                    bannerStyle = AxiaBannerStyle.Danger,
                )
            }
        }

private val AuthUiState.accessibilityDescription: String
    get() =
        when (this) {
            AuthUiState.SignedOut -> "로그아웃됨"
            AuthUiState.RestoringSession -> "기존 세션 확인 중"
            AuthUiState.CreatingTransaction -> "로그인 준비 중"
            is AuthUiState.AwaitingBrowser -> "브라우저 로그인 대기 중"
            is AuthUiState.Failure -> stateDescriptionForFailure()
            is AuthUiState.Blocked -> "로그인 차단됨: ${reason.content.title}"
        }

private fun AuthUiState.Failure.stateDescriptionForFailure(): String = "$title: $description"
