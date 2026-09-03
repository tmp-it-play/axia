@file:Suppress("ktlint:standard:filename")

package team.themoment.axia.feature.auth

import androidx.compose.runtime.Immutable
import team.themoment.axia.auth.AuthBlockReason

@Immutable
sealed interface AuthUiState {
    data object SignedOut : AuthUiState

    data object RestoringSession : AuthUiState

    data object CreatingTransaction : AuthUiState

    @Immutable
    data class AwaitingBrowser(
        val browserLaunchFailed: Boolean = false,
    ) : AuthUiState

    @Immutable
    data class Failure(
        val title: String,
        val description: String,
        val canRetry: Boolean = true,
        val retryLabel: String = "다시 시도",
    ) : AuthUiState

    @Immutable
    data class Blocked(
        val reason: AuthBlockReason,
        val safeCurrentUserDisplayName: String? = null,
    ) : AuthUiState
}
