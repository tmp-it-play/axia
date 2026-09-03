package team.themoment.axia.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject

class ApiFailure(
    val status: HttpStatusCode,
    val code: ApiErrorCode,
    val publicMessage: String,
    val details: JsonObject? = null,
) : RuntimeException(publicMessage) {
    companion object {
        fun invalidRequest(message: String): ApiFailure = ApiFailure(HttpStatusCode.BadRequest, ApiErrorCode.InvalidRequest, message)

        fun authenticationRequired(): ApiFailure =
            ApiFailure(
                HttpStatusCode.Unauthorized,
                ApiErrorCode.AuthenticationRequired,
                "인증이 필요합니다.",
            )

        fun transactionUnavailable(): ApiFailure =
            ApiFailure(
                HttpStatusCode.NotFound,
                ApiErrorCode.AuthTransactionUnavailable,
                "인증 거래를 사용할 수 없습니다. 로그인을 다시 시작해 주세요.",
            )

        fun refreshCredentialInvalid(): ApiFailure =
            ApiFailure(
                HttpStatusCode.Unauthorized,
                ApiErrorCode.RefreshCredentialInvalid,
                "세션을 갱신할 수 없습니다. 다시 로그인해 주세요.",
            )

        fun refreshCredentialReused(): ApiFailure =
            ApiFailure(
                HttpStatusCode.Unauthorized,
                ApiErrorCode.RefreshCredentialReused,
                "세션 보안을 위해 이 기기의 로그인을 종료했습니다.",
            )

        fun sessionNotFound(): ApiFailure =
            ApiFailure(
                HttpStatusCode.NotFound,
                ApiErrorCode.SessionNotFound,
                "세션을 찾을 수 없습니다.",
            )

        fun externalServiceUnavailable(): ApiFailure =
            ApiFailure(
                HttpStatusCode.BadGateway,
                ApiErrorCode.ExternalServiceUnavailable,
                "외부 인증 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            )
    }
}
