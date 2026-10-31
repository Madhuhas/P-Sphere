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

/** Small pieces of Windows the app needs: where data lives, start with Windows, File Explorer. */
object WindowsSystem {
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_VALUE = "PSphere"
    /** Passed by the "start with Windows" entry, so PSphere opens in the tray instead of a window. */
    const val TRAY_ARG = "--tray"

    /** %LOCALAPPDATA%\PSphere, or ~/.psphere outside Windows (for development). */
    fun dataDir(): File {
        System.getProperty("psphere.dataDir")?.let { return File(it) }
        val local = System.getenv("LOCALAPPDATA")
        return if (local != null) File(local, "PSphere") else File(System.getProperty("user.home"), ".psphere")
    }

    /**
     * The installed PSphere.exe, set by the launcher the installer puts in place. Null while
     * running from the source code, where "start with Windows" can't work.
     */
    fun installedExe(): String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    fun startsWithWindows(): Boolean = runCatching {
        val p = ProcessBuilder("reg", "query", RUN_KEY, "/v", RUN_VALUE).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** Adds or removes PSphere from the programs Windows starts at sign-in. Returns false if it failed. */
    fun setStartsWithWindows(on: Boolean): Boolean = runCatching {
        val cmd = if (on) {
            val exe = installedExe() ?: return false
            listOf("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", "\"$exe\" $TRAY_ARG", "/f")
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor(5, TimeUnit.SECONDS) && (p.exitValue() == 0 || !on)
    }.getOrDefault(false)

    fun openInExplorer(path: String): Boolean = runCatching {
        val dir = File(path)
        if (!dir.exists()) return false
        Desktop.getDesktop().open(dir)
        true
    }.getOrDefault(false)

    /**
     * True if two folders are the same folder or one is inside the other, e.g. "D:\Photos" and
     * "D:\Photos\PSphere". Windows paths are compared without regard to case.
     */
    fun overlaps(a: String, b: String): Boolean {
        fun norm(p: String) = runCatching { File(p).canonicalPath }.getOrDefault(p).trimEnd('\\', '/').lowercase()
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
