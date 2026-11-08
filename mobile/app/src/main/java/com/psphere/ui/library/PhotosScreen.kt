package com.psphere.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.model.Media
import com.psphere.core.node.LibraryItem
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData

/**
 * The Photos timeline: one gallery across every linked device, built from the shared library
 * catalog. Each cell shows the ~50 KB preview; a badge marks items that live only on another device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen() {
    val items by rememberEngineData(emptyList<LibraryItem>()) {
        Engine.node.library().sortedByDescending { it.item.takenAt }
    }

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
                    PhotoCell(li)
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(li: LibraryItem) {
    val preview = remember(li.item.hash) { Engine.node.previews.file(li.item.hash) }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
