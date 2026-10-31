package com.psphere.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.psphere.network.AlbumDto
import com.psphere.network.PhotoDto
import com.psphere.network.ServerImages
import com.psphere.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel = viewModel(),
    onOpenPhoto: (photos: List<PhotoDto>, index: Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { if (state.albums.isEmpty()) viewModel.load(context) }

    val openAlbum = state.openAlbum
    if (openAlbum != null) {
        AlbumDetailScreen(
            album       = openAlbum,
            photos      = state.albumPhotos,
            loading     = state.albumPhotosLoading,
            onBack      = { viewModel.closeAlbum() },
            onOpenPhoto = onOpenPhoto,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = {
                    IconButton(
                        onClick = { viewModel.generateSmartAlbums(context) },
                        enabled = !state.generatingSmartAlbums,
                    ) {
                        if (state.generatingSmartAlbums) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, "Create smart albums", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        },
    ) { padding ->
        val inner = Modifier.fillMaxSize().padding(padding)
        when {
            state.loading -> Box(inner, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error.isNotEmpty() -> EmptyState(
                icon     = Icons.Default.PhotoAlbum,
                title    = "Albums aren't available",
                body     = state.error,
                modifier = inner,
            ) { OutlinedButton(onClick = { viewModel.refresh(context) }) { Text("Try again") } }
            state.albums.isEmpty() -> EmptyState(
                icon     = Icons.Default.PhotoAlbum,
                title    = "No albums yet",
                body     = "PSphere can group your photos into trips, people, pets and places for you.",
                modifier = inner,
            ) {
                Button(onClick = { viewModel.generateSmartAlbums(context) }, enabled = !state.generatingSmartAlbums) {
                    if (state.generatingSmartAlbums) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Create smart albums")
                }
            }
            else -> PullToRefreshBox(isRefreshing = false, onRefresh = { viewModel.refresh(context) }, modifier = inner) {
                LazyVerticalGrid(
                    columns               = GridCells.Adaptive(minSize = 140.dp),  // two columns even on 360dp phones
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement   = Arrangement.spacedBy(12.dp),
                    modifier              = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
                    items(state.albums, key = { it.id }) { album ->
                        AlbumCard(album = album, onClick = { viewModel.openAlbum(context, album) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

private fun albumType(type: String): Pair<ImageVector, String>? = when (type) {
    "trip"   -> Icons.Default.Flight to "Trip"
    "people" -> Icons.Default.People to "People"
    "pets"   -> Icons.Default.Pets to "Pets"
    "place"  -> Icons.Default.Place to "Place"
    "smart"  -> Icons.Default.AutoAwesome to "Smart"
    else     -> null
}

@Composable
private fun AlbumCard(album: AlbumDto, onClick: () -> Unit) {
    Card(
        onClick  = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                val cover = ServerImages.absolute(album.cover_url)
                if (cover != null) {
                    AsyncImage(
                        model              = cover,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoAlbum,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp).align(Alignment.Center),
                    )
                }
                albumType(album.type)?.let { (icon, label) ->
                    Surface(
                        shape    = MaterialTheme.shapes.small,
                        color    = Color.Black.copy(alpha = .55f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Row(
                            modifier              = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${album.photo_count} photo${if (album.photo_count != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDetailScreen(
    album: AlbumDto,
    photos: List<PhotoDto>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (List<PhotoDto>, Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val inner = Modifier.fillMaxSize().padding(padding)
        when {
            loading -> Box(inner, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            photos.isEmpty() -> EmptyState(
                icon     = Icons.Default.PhotoAlbum,
                title    = "This album is empty",
                body     = "There are no photos in this album yet.",
                modifier = inner,
            )
            else -> LazyVerticalGrid(
                columns               = GridCells.Adaptive(minSize = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
                modifier              = inner,
            ) {
                itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClickLabel = "Open photo") { onOpenPhoto(photos, index) },
                    ) {
                        AsyncImage(
                            model              = ServerImages.absolute(photo.thumb_url),
                            contentDescription = "Photo",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
