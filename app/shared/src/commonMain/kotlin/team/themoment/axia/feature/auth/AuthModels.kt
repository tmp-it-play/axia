@file:Suppress("ktlint:standard:filename")

package team.themoment.axia.feature.auth

import androidx.compose.runtime.Immutable
import team.themoment.axia.auth.AuthBlockReason

@Immutable
sealed interface AuthUiState {
    data object SignedOut : AuthUiState

    data object CreatingTransaction : AuthUiState

    data object AwaitingBrowser : AuthUiState

    @Immutable
    data class Blocked(
        val reason: AuthBlockReason,
        val safeCurrentUserDisplayName: String? = null,
    ) : AuthUiState
}
