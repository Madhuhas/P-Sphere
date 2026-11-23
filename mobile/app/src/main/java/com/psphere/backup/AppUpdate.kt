package com.psphere.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.psphere.BuildConfig
import com.psphere.core.update.UpdateCheck
import com.psphere.core.update.UpdateDownload
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Check for updates and one-tap update: download the new APK from GitHub, check its SHA-256 and
 * that it's PSphere signed with the same key, then hand it to Android's installer (which asks
 * the user to tap Install; apps can't update themselves silently outside an app store).
 * Nothing here runs unless the user taps.
 */
object AppUpdate {
    sealed interface State {
        object Idle : State
        object Checking : State
        data class UpToDate(val version: String) : State
        data class Failed(val reason: String) : State
        data class Available(val status: UpdateCheck.Status.Available) : State
        data class Downloading(val status: UpdateCheck.Status.Available, val done: Long, val total: Long) : State
        /** Downloaded; Android needs the user to allow PSphere to install apps first. */
        data class NeedsPermission(val status: UpdateCheck.Status.Available, val apk: File) : State
        /** Android's installer was opened. */
        data class Installing(val status: UpdateCheck.Status.Available, val apk: File) : State
        data class DownloadFailed(val status: UpdateCheck.Status.Available, val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    @Volatile private var cancelled = false

    /** Developer setting for testing against a local copy of a release: `adb shell setprop debug.psphere.updates <url>`. */
    fun applyTestSetting() {
        val url = runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.psphere.updates")).inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        if (url.isNotEmpty()) UpdateCheck.apiUrl = url
    }

    fun check() {
        if (job?.isActive == true) return
        _state.value = State.Checking
        job = Engine.scope.launch {
            _state.value = when (val s = UpdateCheck.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheck.Status.UpToDate -> State.UpToDate(s.current)
                is UpdateCheck.Status.Failed -> State.Failed(s.reason)
                is UpdateCheck.Status.Available -> State.Available(s)
            }
        }
    }

    fun update(ctx: Context, status: UpdateCheck.Status.Available) {
        val asset = status.assetEndingWith(".apk") ?: return
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        cancelled = false
        _state.value = State.Downloading(status, 0, asset.size)
        job = Engine.scope.launch {
            val apk = try {
                UpdateDownload.download(asset, File(app.cacheDir, "updates"), progress = { d, t -> _state.value = State.Downloading(status, d, t) }, cancelled = { cancelled })
            } catch (_: UpdateDownload.Cancelled) {
                _state.value = State.Available(status); return@launch
            } catch (e: Exception) {
                _state.value = State.DownloadFailed(status, e.message ?: "The download failed. Try again."); return@launch
            }
            problemWith(app, apk)?.let { apk.delete(); _state.value = State.DownloadFailed(status, it); return@launch }
            install(app, status, apk)
        }
    }

    fun cancel() { cancelled = true }

    /** Opens Android's "Install unknown apps" page for PSphere. */
    fun openPermission(ctx: Context) {
        runCatching {
            // From the app's own screen, so Back returns to PSphere (a new task would land in Settings' own history).
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    /** Called when the app comes back to the front: continue if the user just allowed installing. */
    fun onResume(ctx: Context) {
        val s = _state.value
        if (s is State.NeedsPermission && ctx.packageManager.canRequestPackageInstalls()) install(ctx.applicationContext, s.status, s.apk)
    }

    private fun install(ctx: Context, status: UpdateCheck.Status.Available, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) { _state.value = State.NeedsPermission(status, apk); return }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        _state.value = runCatching { ctx.startActivity(intent); State.Installing(status, apk) }
            .getOrElse { State.DownloadFailed(status, "Couldn't open Android's installer.") }
    }

    /** Why [apk] must not be installed, or null if it's a newer PSphere signed with the same key. */
    private fun problemWith(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The download isn't an app Android can read."
        val ours = pm.getPackageInfo(ctx.packageName, flags)
        if (theirs.packageName != ctx.packageName) return "The download isn't PSphere."
        if (versionCode(theirs) <= versionCode(ours)) return "The download isn't newer than this version."
        if (signatures(theirs) != signatures(ours) || signatures(ours).isEmpty()) return "The download isn't signed like this copy of PSphere, so it wasn't installed."
        return null
    }

    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun signatures(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }
}
