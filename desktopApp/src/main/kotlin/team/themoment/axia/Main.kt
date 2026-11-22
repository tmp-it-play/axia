package team.themoment.axia

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import team.themoment.axia.theme.AxiaTheme

fun main() {
    System.setProperty("apple.awt.application.name", "Axia")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Axia",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            AxiaTheme {
                AxiaApp()
            }
        }
    }
}
