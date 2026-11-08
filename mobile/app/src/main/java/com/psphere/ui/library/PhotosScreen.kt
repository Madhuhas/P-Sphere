package com.psphere.ui.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.model.ItemState
import com.psphere.core.model.Media
import com.psphere.core.node.LibraryItem
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A library item plus the labels the Photos screen filters and groups by. */
private data class Shown(val li: LibraryItem, val device: String, val folder: String)

/**
 * The Photos timeline: one gallery across every linked device, built from the shared library
 * catalog. Tapping opens the full size (from this device, or fetched from a holder on Wi-Fi),
 * with Share and Download. From/Folder chips filter the timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen() {
    val all by rememberEngineData(emptyList<Shown>()) {
        Engine.node.library(includeDeleted = true).sortedByDescending { it.item.takenAt }.map { li ->
            Shown(li, li.originName, Engine.node.catalog.rule(li.item.origin, li.item.ruleId)?.label ?: "Folder")
        }
    }
    var device by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var showDeleted by remember { mutableStateOf(false) }
    var openAt by remember { mutableStateOf<Int?>(null) }

    val devices = remember(all) { all.map { it.device }.distinct().sorted() }
    val folders = remember(all) { all.map { it.folder }.distinct().sorted() }
    val hasDeleted = remember(all) { all.any { it.li.item.state == ItemState.DELETED } }
    val shown = all.filter {
        (device == null || it.device == device) &&
            (folder == null || it.folder == folder) &&
            (showDeleted || it.li.item.state != ItemState.DELETED)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Photos")
                    if (shown.isNotEmpty()) {
                        Text(
                            plural(shown.size, "item"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (all.isNotEmpty()) {
                if (devices.size > 1) ChipRow(devices, device) { device = it }
                if (folders.size > 1) ChipRow(folders, folder) { folder = it }
                if (hasDeleted) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        FilterChip(
                            selected = showDeleted,
                            onClick = { showDeleted = !showDeleted },
                            label = { Text("Show deleted on original") },
                        )
                    }
                }
            }
            when {
                all.isEmpty() -> EmptyPhotos()
                shown.isEmpty() -> Centered("Nothing matches these filters.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(count = shown.size, key = { shown[it].li.item.origin + "/" + shown[it].li.item.key }) { i ->
                        PhotoCell(shown[i].li, onClick = { openAt = i })
                    }
                }
            }
        }
    }

    openAt?.let { idx -> PhotoViewer(shown.map { it.li }, idx, onClose = { openAt = null }) }
}

/** A horizontal row of filter chips: an "All" chip plus one per option. */
@Composable
private fun ChipRow(options: List<String>, selected: String?, onPick: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("All") })
        options.forEach { opt ->
            FilterChip(selected = selected == opt, onClick = { onPick(opt) }, label = { Text(opt) })
        }
    }
}

@Composable
private fun PhotoCell(li: LibraryItem, onClick: () -> Unit) {
    val preview = remember(li.item.hash) { Engine.node.previews.file(li.item.hash) }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (preview != null) {
            AsyncImage(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Outlined.Image, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (li.item.media == Media.VIDEO) {
            Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
        }
        if (!li.onThisDevice) {
            Icon(
                Icons.Outlined.CloudQueue, "On another device", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
            )
        }
    }
}

private sealed interface Full {
    object Loading : Full
    data class Ready(val file: File) : Full
    data class Away(val where: String) : Full
    object Video : Full
}

/**
 * Full-screen, swipeable viewer. Horizontal swipe moves between photos; pinch or double-tap zooms.
 * Each page loads its full size (from this device, or fetched from a holder). HEIC-safe via Coil.
 */
@Composable
private fun PhotoViewer(items: List<LibraryItem>, start: Int, onClose: () -> Unit) {
    if (items.isEmpty()) { onClose(); return }
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    var reloads by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }

    // The visible page's full file, for Share (cheap: fullFile returns the page's cached copy).
    val currentFile by produceState<File?>(null, current.item.hash, reloads) {
        value = if (current.item.media == Media.VIDEO) null
        else withContext(Dispatchers.IO) { Engine.node.fullFile(current.item, Engine.onlineLinked()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            PhotoPage(items[page], reloads, onZoom = { zoomed = it })
        }

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            currentFile?.let { f ->
                IconButton(onClick = { share(context, f, current.item.media) }) {
                    Icon(Icons.Filled.Share, "Share", tint = Color.White)
                }
            }
            if (!current.onThisDevice) {
                IconButton(
                    enabled = !downloading,
                    onClick = {
                        downloading = true
                        scope.launch {
                            withContext(Dispatchers.IO) { Engine.node.download(current.item, Engine.onlineLinked()) }
                            downloading = false
                            reloads++
                        }
                    },
                ) {
                    if (downloading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Download, "Download to this device", tint = Color.White)
                }
            }
        }
    }
}

/** One page of the viewer: the full image (zoomable), or a note for a video / unreachable item. */
@Composable
private fun PhotoPage(li: LibraryItem, reload: Int, onZoom: (Boolean) -> Unit) {
    val preview = remember(li.item.hash) { Engine.node.previews.file(li.item.hash) }
    val state by produceState<Full>(Full.Loading, li.item.hash, reload) {
        value = withContext(Dispatchers.IO) {
            if (li.item.media == Media.VIDEO) Full.Video
            else Engine.node.fullFile(li.item, Engine.onlineLinked())?.let { Full.Ready(it) } ?: Full.Away(holdersText(li))
        }
    }
    when (val s = state) {
        is Full.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        is Full.Ready -> ZoomableImage(s.file, onZoom)
        is Full.Video -> PreviewWithNote(preview, "Video — download to play. In-app playback comes soon.")
        is Full.Away -> PreviewWithNote(preview, s.where)
    }
}

/**
 * Zoomable image. Two-finger pinch zooms from any scale (and is consumed, so the pager doesn't
 * swipe mid-pinch); a one-finger drag pans when zoomed, or passes through to the pager for swiping
 * at normal zoom. Double-tap toggles zoom.
 */
@Composable
private fun ZoomableImage(file: File, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    if (scale == 1f) offset = Offset.Zero
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        when {
                            pointers >= 2 -> {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                                onZoom(scale > 1f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            scale > 1f -> {
                                offset += event.calculatePan()
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            // else: one finger at normal zoom — leave it for the pager to swipe.
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            file, null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}

private fun share(context: android.content.Context, file: File, media: Media) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (media == Media.VIDEO) "video/*" else "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun PreviewWithNote(preview: File?, note: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preview != null) {
            AsyncImage(preview, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
        } else {
            Icon(Icons.Outlined.CloudOff, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Text(note, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** A short "where the full size lives" line for an item that isn't reachable. */
private fun holdersText(li: LibraryItem): String {
    val names = li.holders.mapNotNull { id ->
        if (id == Engine.node.id) null else Engine.linked().firstOrNull { it.id == id }?.name
    }
    return if (names.isEmpty()) "Full size isn't on this device, and no other device is reachable right now."
    else "Full size is on ${names.joinToString(", ")} · not reachable right now."
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyPhotos() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text("No photos yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Photos and videos from your linked devices' backup folders show up here, all in one timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
