package team.themoment.axia.desktop

import java.awt.Color
import java.awt.Desktop
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.desktop.AppReopenedListener
import java.awt.image.BufferedImage

/** AWT maps the tray to the macOS menu bar and the Windows notification area. */
class DesktopLifecycle(
    onShow: () -> Unit,
    onSessions: () -> Unit,
    onLogout: () -> Unit,
    onExit: () -> Unit,
) : AutoCloseable {
    private val desktop = Desktop.getDesktop()
    private val reopenListener = AppReopenedListener { onShow() }
    private val trayIcon: TrayIcon

    init {
        check(SystemTray.isSupported()) { "The background application menu is unavailable" }
        val menu =
            PopupMenu().apply {
                add(MenuItem("Axia 열기").apply { addActionListener { onShow() } })
                add(MenuItem("기기 세션 관리").apply { addActionListener { onSessions() } })
                add(MenuItem("로그아웃").apply { addActionListener { onLogout() } })
                addSeparator()
                add(MenuItem("종료").apply { addActionListener { onExit() } })
            }
        trayIcon = TrayIcon(icon(), "Axia", menu).apply { isImageAutoSize = true }
        trayIcon.addActionListener { onShow() }
        SystemTray.getSystemTray().add(trayIcon)
        try {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.addAppEventListener(reopenListener)
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler { _, response ->
                    response.cancelQuit()
                    onExit()
                }
            }
        } catch (failure: Throwable) {
            SystemTray.getSystemTray().remove(trayIcon)
            throw failure
        }
    }

    fun notify(
        title: String,
        message: String,
    ) {
        trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    override fun close() {
        SystemTray.getSystemTray().remove(trayIcon)
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.removeAppEventListener(reopenListener)
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler(null)
    }

    private fun icon(): BufferedImage =
        BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.color = Color(0x36, 0x59, 0xD9)
                graphics.fillRoundRect(1, 1, 30, 30, 8, 8)
                graphics.color = Color.WHITE
                graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 23)
                graphics.drawString("A", 8, 24)
            } finally {
                graphics.dispose()
            }
        }
}
