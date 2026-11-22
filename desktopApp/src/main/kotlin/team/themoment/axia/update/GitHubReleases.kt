package team.themoment.axia.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

private val LATEST_RELEASE_URI = URI("https://api.github.com/repos/tmp-it-play/axia/releases/latest")
private val REQUEST_TIMEOUT = Duration.ofSeconds(30)

/** 공개된 릴리즈에 올라간 Windows 설치 패키지. */
data class ReleaseInstaller(
    val version: AppVersion,
    val fileName: String,
    val downloadUri: URI,
    val size: Long,
    val sha256: String?,
)

class GitHubReleases(
    private val userAgent: String,
) {
    private val client =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /**
     * 최신 공개 릴리즈의 MSI. 공개된 릴리즈가 하나도 없으면 null.
     * GitHub는 드래프트와 프리릴리즈를 `latest`에서 제외하므로, 드래프트는 직접 공개해야 배포된다.
     */
    fun latestInstaller(): ReleaseInstaller? {
        val request =
            HttpRequest
                .newBuilder(LATEST_RELEASE_URI)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build()
        val response = client.send(request, BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        check(response.statusCode() == 200) { "릴리즈 정보를 가져오지 못했습니다 (HTTP ${response.statusCode()})" }
        val release = JSON.decodeFromString<ReleaseResponse>(response.body())
        val version = checkNotNull(AppVersion.parse(release.tagName)) { "릴리즈 태그 형식이 올바르지 않습니다: ${release.tagName}" }
        val asset =
            checkNotNull(release.assets.singleOrNull { it.name.endsWith(".msi", ignoreCase = true) }) {
                "릴리즈 ${release.tagName}에 MSI 설치 패키지가 하나만 있어야 합니다"
            }
        return ReleaseInstaller(
            version = version,
            fileName = asset.name,
            downloadUri = URI(asset.downloadUrl),
            size = asset.size,
            sha256 = asset.digest?.removePrefix("sha256:"),
        )
    }

    /** [installer]를 [target]에 내려받고 크기와 SHA-256을 검증한다. [onProgress]는 0..1 진행률을 받는다. */
    fun download(
        installer: ReleaseInstaller,
        target: Path,
        onProgress: (Float) -> Unit,
    ) {
        val request =
            HttpRequest
                .newBuilder(installer.downloadUri)
                .header("User-Agent", userAgent)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build()
        val response = client.send(request, BodyHandlers.ofInputStream())
        response.body().use { input ->
            check(response.statusCode() == 200) { "설치 패키지를 내려받지 못했습니다 (HTTP ${response.statusCode()})" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    received += read
                    onProgress(received.toFloat() / installer.size)
                }
            }
            check(received == installer.size) { "설치 패키지 크기가 맞지 않습니다 ($received / ${installer.size} 바이트)" }
            installer.sha256?.let { expected ->
                val actual = HexFormat.of().formatHex(digest.digest())
                check(actual.equals(expected, ignoreCase = true)) { "설치 패키지 체크섬이 맞지 않습니다" }
            }
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    val assets: List<AssetResponse>,
)

@Serializable
private data class AssetResponse(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long,
    val digest: String? = null,
)
