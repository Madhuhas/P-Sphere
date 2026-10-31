package com.psphere.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.psphere.desktop.engine.Activity
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.SingleInstance
import com.psphere.desktop.engine.WindowsSystem
import com.psphere.desktop.ui.App
import com.psphere.desktop.ui.PSphereTheme
import com.psphere.desktop.ui.Starting
import com.psphere.desktop.ui.describeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val dataDir = WindowsSystem.dataDir()
    // Two copies would fight over the same data and port. Opening it again shows the running one.
    val instance = SingleInstance(dataDir)
    if (!instance.acquired) {
        if (!instance.askRunningToShow()) {
            JOptionPane.showMessageDialog(null, "PSphere is already running. Look for the P icon in the tray, at the bottom right of the screen.", "PSphere", JOptionPane.INFORMATION_MESSAGE)
        }
        exitProcess(0)
    }
    val showRequests = MutableStateFlow(0)
    instance.listen { showRequests.update { it + 1 } }
    // Started from "start with Windows": stay in the tray until someone opens the window.
    val startHidden = WindowsSystem.TRAY_ARG in args

    application(exitProcessOnExit = true) {
        var started by remember { mutableStateOf(false) }
        var startError by remember { mutableStateOf<String?>(null) }
        var visible by remember { mutableStateOf(!startHidden) }
        val scope = rememberCoroutineScope()
        val trayState = rememberTrayState()
        val shows by showRequests.collectAsState()
        LaunchedEffect(shows) { if (shows > 0) visible = true }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching { Engine.start(dataDir) }.onFailure { startError = it.message ?: it.toString() }
            }
            started = startError == null
        }
        if (startError != null) {
            JOptionPane.showMessageDialog(null, "PSphere couldn't start:\n$startError", "PSphere", JOptionPane.ERROR_MESSAGE)
            exitApplication()
            return@application
        }

        fun quit() {
            Engine.stop()
            exitApplication()
        }

        if (started) {
            LaunchedEffect(Unit) {
                Engine.notices.collect { n ->
                    trayState.sendNotification(Notification(n.title, n.text, if (n.warning) Notification.Type.Warning else Notification.Type.Info))
                }
            }
            // Another device wants the codes compared: the window has to be visible for that.
            val codeRequest by Engine.codeRequest.collectAsState()
            LaunchedEffect(codeRequest) { if (codeRequest != null) visible = true }

            val state by Engine.state.collectAsState()
            val paused = remember(state.revision) { Engine.node.state.receivingPaused }
            val status = describeActivity(state.activity) ?: if (paused) "Receiving is paused" else "Ready"
            Tray(
                icon = trayIcon(busy = state.activity != Activity.Idle, paused = paused),
                state = trayState,
                tooltip = "PSphere · $status",
                onAction = { visible = true },
                menu = {
                    Item("Open PSphere", onClick = { visible = true })
                    Item("Back up now", enabled = state.activity == Activity.Idle, onClick = { scope.launch { Engine.backUp() } })
                    CheckboxItem("Pause receiving", checked = paused) { v ->
                        scope.launch(Dispatchers.IO) { Engine.node.setReceivingPaused(v); Engine.bump() }
                    }
                    Separator()
                    Item("Quit PSphere", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = {
                if (started && Engine.prefs.keepInTray) {
                    visible = false
                    if (!Engine.prefs.trayNoticeShown) {
                        Engine.prefs.trayNoticeShown = true
                        trayState.sendNotification(Notification(
                            "PSphere is still running",
                            "It keeps receiving backups in the tray. To stop it, right-click the P icon and choose Quit.",
                        ))
                    }
                } else {
                    quit()
                }
            },
            visible = visible,
            title = "PSphere",
            icon = windowIcon,
            state = rememberWindowState(size = startSize(), position = WindowPosition(Alignment.Center)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(720, 520) }
            LaunchedEffect(visible) { if (visible) { window.isMinimized = false; window.toFront() } }
            PSphereTheme {
                Surface {
                    if (started) App() else Starting()
                }
            }
        }
    }
}

/** 1040 × 720, but never larger than the screen above the taskbar (laptops are often 1366 × 768). */
private fun startSize(): DpSize {
    val free = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val w = free?.width?.minus(32)?.coerceAtMost(1040) ?: 1040
    val h = free?.height?.minus(24)?.coerceAtMost(720) ?: 720
    return DpSize(w.coerceAtLeast(720).dp, h.coerceAtLeast(520).dp)
}

private val windowIcon: Painter by lazy { drawIcon(64, null).toPainter() }

/** The "P" badge, with a small dot while busy (blue) or paused (amber). */
@Composable
private fun trayIcon(busy: Boolean, paused: Boolean): Painter = remember(busy, paused) {
    drawIcon(32, when { busy -> Color(0xA9, 0xC7, 0xFF); paused -> Color(0xFF, 0xB8, 0x66); else -> null }).toPainter()
}

private fun drawIcon(size: Int, dot: Color?): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0x15, 0x65, 0xC0)
    val r = size / 4
    g.fillRoundRect(0, 0, size, size, r, r)
    g.color = Color.WHITE
    g.font = Font("Segoe UI", Font.BOLD, (size * 0.68).toInt())
    val fm = g.fontMetrics
    val x = (size - fm.stringWidth("P")) / 2
    val y = (size - fm.height) / 2 + fm.ascent
    g.drawString("P", x, y)
    if (dot != null) {
        val d = size * 7 / 16
        g.color = dot
        g.fillOval(size - d, size - d, d - 1, d - 1)
        g.color = Color(0x20, 0x20, 0x24)
        g.stroke = BasicStroke(size / 16f)
        g.drawOval(size - d, size - d, d - 1, d - 1)
    }
    g.dispose()
    return img
}
