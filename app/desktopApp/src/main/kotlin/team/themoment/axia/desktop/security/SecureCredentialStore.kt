package team.themoment.axia.desktop.security

import team.themoment.axia.auth.DesktopPlatform
import java.net.URI
import java.security.MessageDigest

/** Implementations must never fall back to a file, preferences, or command-line tools. */
interface SecureCredentialStore {
    fun read(): String?

    fun write(value: String)

    fun delete()
}

class CredentialStoreException(
    val operation: String,
    val nativeStatus: Int? = null,
) : IllegalStateException("Secure credential storage failed: $operation (status=${nativeStatus ?: "unavailable"})")

object DesktopPlatformSupport {
    fun current(): DesktopPlatform {
        val os = System.getProperty("os.name").lowercase()
        val architecture = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && architecture in setOf("aarch64", "arm64") -> DesktopPlatform.MacOS
            os.contains("windows") && architecture in setOf("amd64", "x86_64") -> DesktopPlatform.Windows
            else -> throw UnsupportedOperationException("Axia Desktop requires Apple Silicon macOS or x64 Windows")
        }
    }

    fun credentialStore(apiBaseUrl: URI): SecureCredentialStore {
        val environmentId =
            MessageDigest
                .getInstance("SHA-256")
                .digest(apiBaseUrl.toASCIIString().toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
        val service = "team.themoment.axia.session.$environmentId"
        return when (current()) {
            DesktopPlatform.MacOS -> MacKeychainCredentialStore(service)
            DesktopPlatform.Windows -> WindowsCredentialStore(service)
        }
    }
}
