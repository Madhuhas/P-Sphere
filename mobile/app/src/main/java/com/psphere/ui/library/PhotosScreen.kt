package com.psphere.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.model.Media
import com.psphere.core.node.LibraryItem
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Photos timeline: one gallery across every linked device, built from the shared library
 * catalog. Each cell shows the ~50 KB preview; a badge marks items that live only on another
 * device. Tapping opens the full size (loaded from this device, or fetched from a holder on Wi-Fi).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen() {
    val items by rememberEngineData(emptyList<LibraryItem>()) {
        Engine.node.library().sortedByDescending { it.item.takenAt }
    }
    var selected by remember { mutableStateOf<LibraryItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Photos")
                    if (items.isNotEmpty()) {
                        Text(
                            plural(items.size, "item"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            })
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyPhotos(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.item.origin + "/" + it.item.key }) { li ->
                    PhotoCell(li, onClick = { selected = li })
                }
            }
        }
    }

    selected?.let { PhotoViewer(it, onClose = { selected = null }) }
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
            AsyncImage(
                model = preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (li.item.media == Media.VIDEO) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(30.dp),
            )
        }
        if (!li.onThisDevice) {
            Icon(
                Icons.Outlined.CloudQueue,
                contentDescription = "On another device",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
            )
        }
    }
}

private sealed interface Full {
    object Loading : Full
    data class Ready(val file: File) : Full
    /** The full file isn't here and no holder is reachable; [where] names where it lives. */
    data class Away(val where: String) : Full
    /** A video: shown as its preview for now; full playback comes later. */
    object Video : Full
}

/** Full-screen viewer for one item. Loads the full file (HEIC-safe via Coil's ImageDecoder). */
@Composable
private fun PhotoViewer(li: LibraryItem, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val preview = remember(li.item.hash) { Engine.node.previews.file(li.item.hash) }

    val state by produceState<Full>(Full.Loading, li.item.hash) {
        value = withContext(Dispatchers.IO) {
            when {
                li.item.media == Media.VIDEO -> Full.Video
                else -> {
                    val file = Engine.node.fullFile(li.item, Engine.onlineLinked())
                    if (file != null) Full.Ready(file) else Full.Away(holdersText(li))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            is Full.Loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
            )
            is Full.Ready -> AsyncImage(
                model = s.file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            is Full.Video -> PreviewWithNote(preview, "Video — open or download to play. Playback comes soon.")
            is Full.Away -> PreviewWithNote(preview, s.where)
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

@Composable
private fun PreviewWithNote(preview: File?, note: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
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
    return when {
        names.isEmpty() -> "Full size isn't on this device, and no other device is reachable right now."
        else -> "Full size is on ${names.joinToString(", ")} · not reachable right now."
    }
}

@Composable
private fun EmptyPhotos(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "No photos yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Photos and videos from your linked devices' backup folders show up here, all in one timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
