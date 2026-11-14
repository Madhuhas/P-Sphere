package com.psphere.desktop.engine

import java.awt.Desktop
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit

/** Which desktop system PSphere is running on. */
enum class Os { WINDOWS, MAC, LINUX }

/**
 * The small pieces of each desktop system the app needs: where data lives, starting at sign-in,
 * and opening a folder in the file manager. Everything else is the same on Windows, macOS and Linux.
 */
object DesktopSystem {
    val os: Os = System.getProperty("os.name").orEmpty().lowercase().let {
        when {
            "win" in it -> Os.WINDOWS
            "mac" in it || "darwin" in it -> Os.MAC
            else -> Os.LINUX
        }
    }

    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_VALUE = "PSphere"
    private const val MAC_AGENT = "com.psphere.desktop"
    /** Passed by the start-at-sign-in entry, so PSphere opens in the tray instead of a window. */
    const val TRAY_ARG = "--tray"

    private val home get() = File(System.getProperty("user.home"))

    /**
     * Where PSphere keeps its data: %LOCALAPPDATA%\PSphere on Windows,
     * ~/Library/Application Support/PSphere on macOS, and ~/.local/share/PSphere on Linux.
     */
    fun dataDir(): File {
        System.getProperty("psphere.dataDir")?.let { return File(it) }
        return when (os) {
            Os.WINDOWS -> System.getenv("LOCALAPPDATA")?.let { File(it, "PSphere") } ?: File(home, ".psphere")
            Os.MAC -> File(home, "Library/Application Support/PSphere")
            Os.LINUX -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "PSphere")
        }
    }

    /** What the start-at-sign-in setting is called on this system. */
    val startAtLoginLabel: String
        get() = if (os == Os.WINDOWS) "Start PSphere when Windows starts" else "Start PSphere when you log in"

    /** False on desktops without a tray, such as GNOME, Ubuntu's default: closing the window then quits. */
    val hasTray: Boolean by lazy { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    /** Where the tray icon is, for messages. */
    val trayPlace: String
        get() = when (os) {
            Os.WINDOWS -> "in the tray, at the bottom right of the screen"
            Os.MAC -> "in the menu bar, at the top right of the screen"
            Os.LINUX -> "in the system tray"
        }

    /**
     * The installed app's launcher, set by the launcher the installer puts in place. Null while
     * running from the source code, where starting at sign-in can't work.
     */
    fun installedExe(): String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    private val linuxAutostart: File
        get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "autostart/psphere.desktop")
    private val macAgent: File get() = File(home, "Library/LaunchAgents/$MAC_AGENT.plist")

    fun startsAtLogin(): Boolean = when (os) {
        Os.WINDOWS -> runCatching {
            val p = ProcessBuilder("reg", "query", RUN_KEY, "/v", RUN_VALUE).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        Os.MAC -> macAgent.isFile
        Os.LINUX -> linuxAutostart.isFile
    }

    /** Adds or removes PSphere from the programs started at sign-in. Returns false if it failed. */
    fun setStartsAtLogin(on: Boolean): Boolean = runCatching {
        val exe = if (on) installedExe() ?: return false else ""
        when (os) {
            Os.WINDOWS -> {
                val cmd = if (on) listOf("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", "\"$exe\" $TRAY_ARG", "/f")
                else listOf("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.inputStream.readAllBytes()
                p.waitFor(5, TimeUnit.SECONDS) && (p.exitValue() == 0 || !on)
            }
            Os.MAC -> writeOrDelete(macAgent, on) { macAgentPlist(exe) }
            Os.LINUX -> writeOrDelete(linuxAutostart, on) { linuxAutostartEntry(exe) }
        }
    }.getOrDefault(false)

    private fun writeOrDelete(file: File, on: Boolean, text: () -> String): Boolean {
        if (on) {
            file.parentFile.mkdirs()
            file.writeText(text())
            return file.isFile
        }
        file.delete()
        return true
    }

    /** The Linux autostart entry (~/.config/autostart/psphere.desktop). */
    internal fun linuxAutostartEntry(exe: String): String = listOf(
        "[Desktop Entry]",
        "Type=Application",
        "Name=PSphere",
        "Comment=Back up folders between your own devices",
        "Exec=\"" + exe.replace("\"", "\\\"") + "\" " + TRAY_ARG,
        "Terminal=false",
        "X-GNOME-Autostart-enabled=true",
    ).joinToString("\n", postfix = "\n")

    /** The macOS login agent (~/Library/LaunchAgents/com.psphere.desktop.plist). */
    internal fun macAgentPlist(exe: String): String {
        fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return listOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""",
            """<plist version="1.0">""",
            "<dict>",
            "  <key>Label</key><string>$MAC_AGENT</string>",
            "  <key>ProgramArguments</key>",
            "  <array><string>${xml(exe)}</string><string>$TRAY_ARG</string></array>",
            "  <key>RunAtLoad</key><true/>",
            "</dict>",
            "</plist>",
        ).joinToString("\n", postfix = "\n")
    }

    /** Shows [path] in the file manager (File Explorer, Finder, or the Linux default). */
    fun openFolder(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists()) return false
        if (runCatching { Desktop.getDesktop().open(dir); true }.getOrDefault(false)) return true
        val cmd = when (os) {
            Os.WINDOWS -> listOf("explorer", dir.path)
            Os.MAC -> listOf("open", dir.path)
            Os.LINUX -> listOf("xdg-open", dir.path)
        }
        return runCatching { ProcessBuilder(cmd).start(); true }.getOrDefault(false)
    }

    /** The computer's name, e.g. "DESKTOP-4KQ1" or "MacBook-Air", without a ".local" ending. */
    fun computerName(): String? =
        (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            ?.substringBefore('.')?.takeIf { it.isNotBlank() }

    /**
     * True if two folders are the same folder or one is inside the other, e.g. "D:\Photos" and
     * "D:\Photos\PSphere". Windows and macOS compare names without regard to case; Linux doesn't.
     */
    fun overlaps(a: String, b: String, ignoreCase: Boolean = os != Os.LINUX): Boolean {
        fun norm(p: String) = runCatching { File(p).canonicalPath }.getOrDefault(p).trimEnd('\\', '/')
            .let { if (ignoreCase) it.lowercase() else it }
        val x = norm(a); val y = norm(b)
        fun inside(child: String, parent: String) = child.startsWith("$parent\\") || child.startsWith("$parent/")
        return x == y || inside(x, y) || inside(y, x)
    }
}

/**
 * Makes sure only one PSphere runs at a time: two would fight over the same data and port.
 * The lock is released when the process ends, also when it crashes.
 *
 * Opening PSphere again while it runs in the tray should show its window, so the running copy
 * listens on a local-only port (written to running.port) and a second copy just knocks there.
 */
class SingleInstance(private val dir: File) {
    private val channel: FileChannel
    private val lock: FileLock?
    private val portFile = File(dir, "running.port")

    init {
        dir.mkdirs()
        channel = RandomAccessFile(File(dir, "running.lock"), "rw").channel
        lock = runCatching { channel.tryLock() }.getOrNull()
    }

    val acquired: Boolean get() = lock != null

    /** In the running copy: calls [onShow] whenever PSphere is opened again. */
    fun listen(onShow: () -> Unit) {
        val server = runCatching { ServerSocket(0, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        portFile.writeText(server.localPort.toString())
        Thread({
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { s.use { if (it.getInputStream().bufferedReader().readLine() == SHOW) onShow() } }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    /** In a second copy: asks the running one to show its window. False if it couldn't be reached. */
    fun askRunningToShow(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.getOutputStream().write("$SHOW\n".toByteArray())
            s.getOutputStream().flush()
        }
        true
    }.getOrDefault(false)

    private companion object { const val SHOW = "show" }
}
