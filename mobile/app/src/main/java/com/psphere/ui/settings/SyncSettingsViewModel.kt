package com.psphere.ui.settings

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.data.TokenDataStore
import com.psphere.network.ApiClient
import com.psphere.sync.SyncScheduler
import com.psphere.sync.SyncedFilesTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

data class FolderOption(
    val path: String,
    val displayName: String,
    val selected: Boolean,
)

data class SyncSettingsState(
    val wifiOnly: Boolean             = true,
    val chargingOnly: Boolean         = false,
    val syncIntervalHours: Long       = 6L,
    val folders: List<FolderOption>   = emptyList(),
    val freeableBytes: Long           = 0L,
    val freeableCount: Int            = 0,
    val isDeleting: Boolean           = false,
    val justFreed: Boolean            = false,
    val isSigningOut: Boolean         = false,
    val signedOut: Boolean            = false,
    val showFreeSpaceConfirm: Boolean = false,
)

val SYNC_INTERVAL_OPTIONS = listOf(1L, 3L, 6L, 12L, 24L)

class SyncSettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SyncSettingsState())
    val state: StateFlow<SyncSettingsState> = _state

    fun load(context: Context) {
        val store = TokenDataStore(context)

        viewModelScope.launch {
            combine(
                store.wifiOnly,
                store.chargingOnly,
                store.syncIntervalHours,
                store.syncFolders,
            ) { wifi, charging, interval, selectedPaths ->
                val available = discoverPhotoFolders()
                val folders = available.map { f ->
                    FolderOption(
                        path        = f.absolutePath,
                        displayName = f.name,
                        selected    = selectedPaths.isEmpty() || f.absolutePath in selectedPaths,
                    )
                }
                val paths = SyncedFilesTracker.getFreeablePaths(context)
                val bytes = paths.sumOf { File(it).length() }
                SyncSettingsState(
                    wifiOnly          = wifi,
                    chargingOnly      = charging,
                    syncIntervalHours = interval,
                    folders           = folders,
                    freeableBytes     = bytes,
                    freeableCount     = paths.size,
                )
            }.collect { _state.value = it }
        }
    }

    fun setWifiOnly(context: Context, v: Boolean) {
        viewModelScope.launch {
            TokenDataStore(context).setWifiOnly(v)
            SyncScheduler.schedulePeriodic(context, _state.value.syncIntervalHours)
        }
    }

    fun setChargingOnly(context: Context, v: Boolean) {
        viewModelScope.launch {
            TokenDataStore(context).setChargingOnly(v)
            SyncScheduler.schedulePeriodic(context, _state.value.syncIntervalHours)
        }
    }

    fun setSyncInterval(context: Context, hours: Long) {
        viewModelScope.launch {
            TokenDataStore(context).setSyncIntervalHours(hours)
            SyncScheduler.schedulePeriodic(context, hours)
        }
    }

    fun toggleFolder(context: Context, path: String) {
        val current = _state.value.folders
        val toggled = current.map { if (it.path == path) it.copy(selected = !it.selected) else it }
        val selected = toggled.filter { it.selected }.map { it.path }.toSet()
        _state.value = _state.value.copy(folders = toggled)
        viewModelScope.launch { TokenDataStore(context).setSyncFolders(selected) }
    }

    fun showFreeSpaceConfirm() {
        _state.value = _state.value.copy(showFreeSpaceConfirm = true)
    }

    fun dismissFreeSpaceConfirm() {
        _state.value = _state.value.copy(showFreeSpaceConfirm = false)
    }

    fun freeUpSpace(context: Context) {
        if (_state.value.isDeleting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isDeleting = true, showFreeSpaceConfirm = false)
            SyncedFilesTracker.deleteAll(context)
            _state.value = _state.value.copy(isDeleting = false, freeableBytes = 0L, freeableCount = 0, justFreed = true)
        }
    }

    fun clearJustFreed() {
        _state.value = _state.value.copy(justFreed = false)
    }

    fun signOut(context: Context) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSigningOut = true)
            TokenDataStore(context).signOut()
            ApiClient.setBaseUrl(com.psphere.BuildConfig.BASE_URL)
            SyncScheduler.cancelPeriodic(context)
            _state.value = _state.value.copy(isSigningOut = false, signedOut = true)
        }
    }

    private fun discoverPhotoFolders(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            "DCIM",
            "Pictures",
            "Download",
            "WhatsApp/Media/WhatsApp Images",
            "Telegram",
            "Screenshots",
            "Instagram",
            "Snapchat",
        ).map { File(ext, it) }
        return candidates.filter { it.exists() && it.isDirectory }
    }
}
