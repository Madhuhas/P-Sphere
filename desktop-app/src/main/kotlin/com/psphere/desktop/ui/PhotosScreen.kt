package com.psphere.desktop.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.psphere.core.model.Media
import com.psphere.core.node.LibraryItem
import com.psphere.desktop.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

/** Loads a JPEG/PNG file into a Compose bitmap, or null if it can't be read. Call off the UI thread. */
private fun loadBitmap(file: File?): ImageBitmap? =
    file?.takeIf { it.isFile }?.let { runCatching { ImageIO.read(it)?.toComposeImageBitmap() }.getOrNull() }

/** The Photos timeline on Windows: one gallery across every linked device, from the shared catalog. */
@Composable
fun PhotosScreen() {
    val items by rememberEngineData(emptyList<LibraryItem>()) {
        Engine.node.library().sortedByDescending { it.item.takenAt }
    }
    var selected by remember { mutableStateOf<LibraryItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Photos")
        if (items.isEmpty()) {
            EmptyPhotos()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.item.origin + "/" + it.item.key }) { li ->
                    PhotoCell(li) { selected = li }
                }
            }
        }
    }

    selected?.let { Viewer(it) { selected = null } }
}

@Composable
private fun PhotoCell(li: LibraryItem, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, li.item.hash) {
        value = withContext(Dispatchers.IO) { loadBitmap(Engine.node.previews.file(li.item.hash)) }
    }
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Outlined.Image, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (li.item.media == Media.VIDEO) {
            Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
        }
        if (!li.onThisDevice) {
            Icon(
                Icons.Outlined.CloudQueue, "On another device", tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}

private sealed interface Full {
    object Loading : Full
    data class Ready(val bitmap: ImageBitmap) : Full
    data class Note(val text: String) : Full
}

/** Full-size overlay for one item: loaded from this device, or fetched from a reachable holder. */
@Composable
private fun Viewer(li: LibraryItem, onClose: () -> Unit) {
    val state by produceState<Full>(Full.Loading, li.item.hash) {
        value = withContext(Dispatchers.IO) {
            if (li.item.media == Media.VIDEO) Full.Note("Video. For now, open it from the backup folder.")
            else {
                val file = Engine.node.fullFile(li.item, Engine.onlineLinked())
                val bmp = loadBitmap(file)
                when {
                    bmp != null -> Full.Ready(bmp)
                    else -> Full.Note(holdersText(li))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))) {
        when (val s = state) {
            is Full.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is Full.Ready -> Image(
                s.bitmap, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
            is Full.Note -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.CloudOff, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(s.text, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
        IconButton(onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(Icons.Filled.Close, "Close", tint = Color.White)
        }
    }
}

private fun holdersText(li: LibraryItem): String {
    val names = li.holders.mapNotNull { id ->
        if (id == Engine.node.id) null else Engine.node.device(id)?.name
    }
    return if (names.isEmpty()) "Full size isn't on this computer, and no other device is reachable right now."
    else "Full size is on ${names.joinToString(", ")} · not reachable right now."
}

@Composable
private fun EmptyPhotos() {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text("No photos yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Photos and videos from your linked devices' backup folders show up here, all in one timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.7f),
        )
    }
}
