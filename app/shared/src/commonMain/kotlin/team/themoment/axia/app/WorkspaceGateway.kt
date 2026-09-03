package team.themoment.axia.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import team.themoment.axia.api.ApiError
import team.themoment.axia.api.ApiErrorCode

/** The Desktop implementation owns networking and credentials. Presentation receives only feature API data. */
fun interface WorkspaceGateway {
    suspend fun request(
        method: String,
        path: String,
        body: String?,
    ): WorkspaceResponse
}

class WorkspaceResponse(
    val status: Int,
    val body: String,
) {
    override fun toString(): String = "WorkspaceResponse(status=$status,body=[REDACTED])"
}

class WorkspaceFailure(
    val error: ApiError,
    val status: Int,
) : RuntimeException(error.message)

internal val workspaceJson = Json { ignoreUnknownKeys = true }

internal suspend inline fun <reified T> WorkspaceGateway.get(path: String): T = workspaceJson.decodeFromString<T>(checked("GET", path).body)

internal suspend inline fun <reified T> WorkspaceGateway.send(
    method: String,
    path: String,
    body: T,
): WorkspaceResponse = checked(method, path, workspaceJson.encodeToString(body))

internal suspend fun WorkspaceGateway.checked(
    method: String,
    path: String,
    body: String? = null,
): WorkspaceResponse {
    val response = request(method, path, body)
    if (response.status !in 200..299) {
        val error =
            runCatching { workspaceJson.decodeFromString<ApiError>(response.body) }.getOrElse {
                ApiError(ApiErrorCode.ExternalServiceUnavailable, "서버 응답을 확인할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.")
            }
        throw WorkspaceFailure(error, response.status)
    }
    return response
}
