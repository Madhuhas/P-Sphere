package com.psphere.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()
    val haptic  = LocalHapticFeedback.current

    LaunchedEffect(Unit) { viewModel.load(context) }

    LaunchedEffect(state.hapticSuccess) {
        if (state.hapticSuccess) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearHapticSignals()
        }
    }
    LaunchedEffect(state.hapticError) {
        if (state.hapticError) {
            repeat(2) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(120)
            }
            viewModel.clearHapticSignals()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PSphere") },
                actions = {
                    if (state.isP2pMode) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier       = Modifier.padding(end = 16.dp)
                        ) {
                            Text("P2P", modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh    = { viewModel.refresh(context) },
            modifier     = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ── Library Summary (Core mode only) ──────────────────────────
                if (!state.isP2pMode && (state.librarySummary.isNotEmpty() || state.summaryLoading)) {
                    LibrarySummaryCard(state)
                }

                // ── Sync Status ───────────────────────────────────────────────
                SyncStatusCard(state)

                // ── Nearby Devices (P2P) ──────────────────────────────────────
                NearbyDevicesCard(state)

                Spacer(Modifier.height(8.dp))

                // ── Sync button ───────────────────────────────────────────────
                Button(
                    onClick  = { viewModel.startSync(context) },
                    enabled  = state.syncStatus != SyncStatus.SYNCING &&
                               state.syncStatus != SyncStatus.QUEUED,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.syncStatus) {
                            SyncStatus.QUEUED  -> "Queued…"
                            SyncStatus.SYNCING -> "Syncing…"
                            else               -> if (state.isP2pMode) "Sync with Nearby Devices" else "Sync Now"
                        }
                    )
                }

                if (state.syncStatus == SyncStatus.ERROR) {
                    OutlinedButton(
                        onClick  = { viewModel.startSync(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun LibrarySummaryCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))

            if (state.summaryLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
            } else {
                if (state.totalPhotos > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Photo, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Text("${state.totalPhotos} photos", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (state.librarySummary.isNotEmpty()) {
                    Text(state.librarySummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Sync Status",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                    when (state.syncStatus) {
                        SyncStatus.IDLE    -> Icon(Icons.Default.Sync,           null, tint = Color.Gray)
                        SyncStatus.QUEUED  -> Icon(Icons.Default.HourglassBottom, null, tint = Color.Gray)
                        SyncStatus.SYNCING -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        SyncStatus.DONE    -> Icon(Icons.Default.CheckCircle,    null, tint = Color(0xFF4CAF50))
                        SyncStatus.ERROR   -> Icon(Icons.Default.Warning,        null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                Column {
                    Text(
                        text = when (state.syncStatus) {
                            SyncStatus.IDLE    -> "Idle — tap to sync"
                            SyncStatus.QUEUED  -> "Waiting for Wi-Fi…"
                            SyncStatus.SYNCING -> "Syncing…"
                            SyncStatus.DONE    -> state.syncProgress
                            SyncStatus.ERROR   -> "Sync failed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.syncStatus == SyncStatus.ERROR && state.syncError.isNotEmpty()) {
                        Text(state.syncError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.lastSynced.isNotEmpty()) {
                        Text("Last synced: ${state.lastSynced}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            if (state.syncStatus == SyncStatus.SYNCING) {
                Spacer(Modifier.height(14.dp))
                if (state.syncTotal > 0) {
                    val progress by animateFloatAsState(
                        targetValue   = state.syncDone.toFloat() / state.syncTotal,
                        animationSpec = tween(300),
                        label         = "syncProg",
                    )
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text(state.syncProgress, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NearbyDevicesCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Nearby Devices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))

            if (state.knownPeers.isEmpty()) {
                Text(
                    if (state.isP2pMode) "No devices found on this network.\nMake sure both phones are on the same Wi-Fi."
                    else "P2P discovery is active. Other PSphere devices on the same Wi-Fi will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            } else {
                state.knownPeers.forEach { peer ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                            Text(peer.ipAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text("Online", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                }
            }
        }
    }
}
