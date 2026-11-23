package com.psphere.desktop.engine

import com.psphere.core.update.UpdateCheck
import com.psphere.core.update.UpdateDownload
import com.psphere.desktop.ui.APP_VERSION
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

/**
 * Check for updates, and on Windows a one-click update: download the new installer, check its
 * SHA-256, close PSphere, install over it (per-user, so Windows doesn't ask for permission) and
 * open it again. Elsewhere, or when run from source, it opens the release page instead.
 * Nothing here runs unless the user clicks.
 */
object AppUpdate {
    sealed interface State {
        object Idle : State
        object Checking : State
        data class UpToDate(val version: String) : State
        data class Failed(val reason: String) : State
        /** A newer version; [oneClick] is false where it can only be downloaded from the release page. */
        data class Available(val status: UpdateCheck.Status.Available, val oneClick: Boolean) : State
        data class Downloading(val status: UpdateCheck.Status.Available, val done: Long, val total: Long) : State
        object Installing : State
        data class DownloadFailed(val status: UpdateCheck.Status.Available, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    @Volatile private var cancelled = false

    /** True when this copy can update itself: the installed Windows app. */
    private val canInstall: Boolean get() = DesktopSystem.os == Os.WINDOWS && DesktopSystem.installedExe() != null

    private val downloads: File get() = File(DesktopSystem.dataDir(), "updates")

    fun check() {
        if (job?.isActive == true) return
        _state.value = State.Checking
        job = Engine.scope.launch {
            _state.value = when (val s = UpdateCheck.check(APP_VERSION)) {
                is UpdateCheck.Status.UpToDate -> State.UpToDate(s.current)
                is UpdateCheck.Status.Failed -> State.Failed(s.reason)
                is UpdateCheck.Status.Available -> State.Available(s, canInstall && s.assetEndingWith(".msi") != null)
            }
        }
    }

    /** Downloads and installs [status]'s Windows installer, then PSphere closes and opens again. */
    fun update(status: UpdateCheck.Status.Available) {
        val asset = status.assetEndingWith(".msi") ?: return
        if (job?.isActive == true || !canInstall) return
        cancelled = false
        _state.value = State.Downloading(status, 0, asset.size)
        job = Engine.scope.launch {
            val msi = try {
                UpdateDownload.download(asset, downloads, progress = { d, t -> _state.value = State.Downloading(status, d, t) }, cancelled = { cancelled })
            } catch (_: UpdateDownload.Cancelled) {
                _state.value = State.Available(status, oneClick = true)
                return@launch
            } catch (e: Exception) {
                _state.value = State.DownloadFailed(status, e.message ?: "The download failed. Try again.")
                return@launch
            }
            _state.value = State.Installing
            if (!startInstaller(msi)) {
                _state.value = State.DownloadFailed(status, "Couldn't start the installer. Try again, or download it from the release page.")
                return@launch
            }
            // The installer waits for this process to end before it replaces the files.
            Engine.stop()
            exitProcess(0)
        }
    }

    fun cancel() { cancelled = true }

    /**
     * Starts a hidden helper that waits for PSphere to close, runs the installer with only a
     * progress bar, and opens PSphere again (the new version, or the old one if the install
     * failed, which Windows rolls back).
     */
    private fun startInstaller(msi: File): Boolean {
        val exe = DesktopSystem.installedExe() ?: return false
        val dir = File(exe).parentFile.absolutePath.trimEnd('\\')
        val self = ProcessHandle.current()
        // The jpackage launcher can be a separate parent process; wait for it too.
        val pids = listOfNotNull(self.pid(), self.parent().orElse(null)?.takeIf { it.info().command().orElse("").equals(exe, true) }?.pid())
        val log = File(downloads, "install.log").absolutePath
        fun q(s: String) = s.replace("'", "''")
        val script = buildString {
            append("\$ErrorActionPreference = 'SilentlyContinue'; ")
            append("Wait-Process -Id ${pids.joinToString(",")} -Timeout 120; ")
            append("Start-Sleep -Milliseconds 500; ")
            append("Start-Process msiexec.exe -Wait -ArgumentList @('/i', '\"${q(msi.absolutePath)}\"', '/passive', '/norestart', 'INSTALLDIR=\"${q(dir)}\"', '/l*', '\"${q(log)}\"'); ")
            append("Start-Process -FilePath '${q(exe)}'")
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            true
        }.getOrDefault(false)
    }
}
