package com.psphere.ui.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

private const val COLS = 3
private const val LOAD_MORE_THRESHOLD = 12

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (index: Int) -> Unit,
    onGoToSync: () -> Unit = {},
    viewModel: GalleryViewModel = viewModel(),
) {
    val context   = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state     by viewModel.state.collectAsState()

    var hasPermission by remember { mutableStateOf(checkMediaPermission(context)) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasPermission = checkMediaPermission(context)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && state.allPhotos.isEmpty() && !state.loading) {
            viewModel.load(context)
        }
    }

    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - (COLS * LOAD_MORE_THRESHOLD)
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd && state.hasMore) viewModel.loadMore(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        state.totalCount > 0 -> "${state.totalCount} ${if (state.isServerSource) "Photos (Server)" else "Photos"}"
                        state.loading        -> "Loading…"
                        else                 -> "Gallery"
                    })
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasPermission && !state.isServerSource -> PermissionDeniedState(
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        })
                    }
                )

                state.loading && state.allPhotos.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error.isNotEmpty() ->
                    Text(state.error, modifier = Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)

                state.allPhotos.isEmpty() -> EmptyGalleryState(onGoToSync = onGoToSync)

                else -> PullToRefreshBox(
                    isRefreshing = state.loading && state.allPhotos.isNotEmpty(),
                    onRefresh    = { viewModel.refresh(context) },
                ) {
                    Column {
                        LazyVerticalGrid(
                            columns               = GridCells.Fixed(COLS),
                            state                 = gridState,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement   = Arrangement.spacedBy(2.dp),
                            modifier              = Modifier.weight(1f),
                        ) {
                            items(
                                items = state.items,
                                span  = { item -> when (item) {
                                    is GridItem.Header -> GridItemSpan(COLS)
                                    is GridItem.Photo  -> GridItemSpan(1)
                                }},
                                key = { item -> when (item) {
                                    is GridItem.Header -> "h_${item.label}"
                                    is GridItem.Photo  -> item.photo.id
                                }}
                            ) { item ->
                                when (item) {
                                    is GridItem.Header -> DateHeader(item.label)
                                    is GridItem.Photo  -> PhotoCell(
                                        thumbUri = item.photo.thumbUri,
                                        isVideo  = item.photo.isVideo,
                                        onClick  = { onPhotoClick(item.globalIndex) },
                                    )
                                }
                            }

                            if (!state.hasMore && state.allPhotos.isNotEmpty()) {
                                item(span = { GridItemSpan(COLS) }) {
                                    Text(
                                        "All ${state.totalCount} photos loaded",
                                        style     = MaterialTheme.typography.bodySmall,
                                        color     = Color.Gray,
                                        textAlign = TextAlign.Center,
                                        modifier  = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    )
                                }
                            }
                        }

                        if (state.loadingMore) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text     = label,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun PhotoCell(thumbUri: String, isVideo: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model              = thumbUri,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Video",
                tint     = Color.White.copy(alpha = .85f),
                modifier = Modifier.size(28.dp).align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PermissionDeniedState(onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.LockOpen, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text("Photos access required", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("PSphere needs access to your photos to show your gallery and sync with nearby devices.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) { Text("Open App Settings") }
    }
}

@Composable
private fun EmptyGalleryState(onGoToSync: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoLibrary, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text("No photos yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Take a photo or sync with a nearby device to fill your gallery.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGoToSync) { Text("Go to Sync") }
    }
}

private fun checkMediaPermission(context: android.content.Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
