package team.themoment.axia.desktop.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

fun interface SystemBrowser {
    suspend fun open(uri: URI)
}

class DesktopSystemBrowser : SystemBrowser {
    override suspend fun open(uri: URI) {
        require(uri.scheme == "https" && uri.userInfo == null && !uri.host.isNullOrBlank()) { "A secure browser URL is required" }
        withContext(Dispatchers.IO) {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw UnsupportedOperationException("The system browser is unavailable")
            }
            // Native desktop integration; OAuth URLs never enter process command lines or logs.
            Desktop.getDesktop().browse(uri)
        }
    }
}
