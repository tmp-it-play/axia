package team.themoment.axia.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable error envelope returned by the Axia API.
 *
 * [message] must be safe to show to a user and must never contain an upstream
 * provider response, credential, or other sensitive diagnostic data.
 */
@Serializable
data class ApiError(
    val code: ApiErrorCode,
    val message: String,
    val requestId: String? = null,
)

/** Machine-readable error codes used by the authentication and session API. */
@Serializable
enum class ApiErrorCode {
    @SerialName("invalid_request")
    InvalidRequest,

    @SerialName("authentication_required")
    AuthenticationRequired,

    @SerialName("access_denied")
    AccessDenied,

    /**
     * The transaction cannot be polled because its id or secret is invalid, or
     * because it has already been consumed or expired.
     */
    @SerialName("auth_transaction_unavailable")
    AuthTransactionUnavailable,

    @SerialName("refresh_credential_invalid")
    RefreshCredentialInvalid,

    @SerialName("refresh_credential_reused")
    RefreshCredentialReused,

    @SerialName("session_not_found")
    SessionNotFound,

    @SerialName("conflict")
    Conflict,

    @SerialName("external_service_unavailable")
    ExternalServiceUnavailable,

    @SerialName("internal_server_error")
    InternalServerError,
}
