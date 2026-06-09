package com.psphere.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psphere.PSphereApp
import com.psphere.data.TokenDataStore
import com.psphere.network.ApiClient
import com.psphere.network.AiSummaryResponse
import com.psphere.p2p.PeerDevice
import com.psphere.sync.SyncScheduler
import com.psphere.sync.SyncWorkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SyncStatus { IDLE, QUEUED, SYNCING, DONE, ERROR }

data class HomeUiState(
    val isP2pMode: Boolean             = false,
    val knownPeers: List<PeerDevice>   = emptyList(),
    val syncStatus: SyncStatus         = SyncStatus.IDLE,
    val syncProgress: String           = "",
    val syncError: String              = "",
    val syncDone: Int                  = 0,
    val syncTotal: Int                 = 0,
    val lastSynced: String             = "",
    val isRefreshing: Boolean          = false,
    val hapticSuccess: Boolean         = false,
    val hapticError: Boolean           = false,
    val librarySummary: String         = "",
    val summaryLoading: Boolean        = false,
    val totalPhotos: Int               = 0,
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    fun load(context: Context) {
        val store = TokenDataStore(context)

        viewModelScope.launch {
            store.p2pMode.collect { p2p ->
                _state.value = _state.value.copy(isP2pMode = p2p)
            }
        }

        viewModelScope.launch {
            SyncScheduler.syncState(context).collect { workerState ->
                val prev = _state.value
                _state.value = when (workerState) {
                    is SyncWorkerState.Idle    -> prev.copy(
                        syncStatus = SyncStatus.IDLE, syncProgress = "", syncDone = 0, syncTotal = 0)
                    is SyncWorkerState.Queued  -> prev.copy(
                        syncStatus = SyncStatus.QUEUED, syncProgress = "Waiting…")
                    is SyncWorkerState.Running -> prev.copy(
                        syncStatus   = SyncStatus.SYNCING,
                        syncDone     = workerState.done,
                        syncTotal    = workerState.total,
                        syncProgress = if (workerState.total > 0)
                            "${workerState.done} / ${workerState.total} files"
                        else "Starting…"
                    )
                    is SyncWorkerState.Succeeded -> {
                        val summary = buildList {
                            if (workerState.coreUploaded > 0)  add("↑${workerState.coreUploaded} to server")
                            if (workerState.p2pPeers     > 0)  add("↑${workerState.p2pUploaded}↓${workerState.p2pDownloaded} P2P")
                            if (isEmpty()) add("Already up to date")
                        }.joinToString(" · ")
                        prev.copy(
                            syncStatus    = SyncStatus.DONE,
                            syncProgress  = summary,
                            lastSynced    = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) },
                            hapticSuccess = true,
                            syncDone      = 0,
                            syncTotal     = 0,
                            isRefreshing  = false,
                        )
                    }
                    is SyncWorkerState.Failed -> prev.copy(
                        syncStatus   = SyncStatus.ERROR,
                        syncError    = workerState.message,
                        hapticError  = true,
                        isRefreshing = false,
                    )
                }
            }
        }

        // Auto-refresh peers every 5 seconds
        viewModelScope.launch {
            while (true) {
                refreshPeers()
                delay(5_000)
            }
        }

        loadSummary(context)
    }

    fun startSync(context: Context) {
        if (_state.value.syncStatus == SyncStatus.SYNCING) return
        viewModelScope.launch { SyncScheduler.syncNow(context) }
    }

    fun refresh(context: Context) {
        _state.value = _state.value.copy(isRefreshing = true)
        viewModelScope.launch {
            SyncScheduler.syncNow(context)
            // isRefreshing is cleared when the worker state changes to Succeeded/Failed
        }
        loadSummary(context)
    }

    fun clearHapticSignals() {
        _state.value = _state.value.copy(hapticSuccess = false, hapticError = false)
    }

    private fun loadSummary(context: Context) {
        viewModelScope.launch {
            val jwt = TokenDataStore(context).jwtToken.first() ?: return@launch
            _state.value = _state.value.copy(summaryLoading = true)
            try {
                val result = ApiClient.api.aiSummary("Bearer $jwt")
                _state.value = _state.value.copy(
                    librarySummary = result.summary,
                    totalPhotos    = result.photoCount,
                    summaryLoading = false,
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(summaryLoading = false)
            }
        }
    }

    private fun refreshPeers() {
        if (PSphereApp.isInitialized) {
            _state.value = _state.value.copy(
                knownPeers = PSphereApp.instance.peerSyncManager.knownPeers()
            )
        }
    }
}
