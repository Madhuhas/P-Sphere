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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.psphere.network.AlbumDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(viewModel: AlbumsViewModel = viewModel()) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { if (state.albums.isEmpty()) viewModel.load(context) }

    // ── Album detail overlay ──────────────────────────────────────────────────
    val openAlbum = state.openAlbum
    if (openAlbum != null) {
        AlbumDetailScreen(
            album     = openAlbum,
            photos    = state.albumPhotos,
            loading   = state.albumPhotosLoading,
            onBack    = { viewModel.closeAlbum() },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = {
                    IconButton(
                        onClick  = { viewModel.generateSmartAlbums(context) },
                        enabled  = !state.generatingSmartAlbums,
                    ) {
                        if (state.generatingSmartAlbums) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, "Generate smart albums", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error.isNotEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }

            state.albums.isEmpty() -> EmptyAlbumsState(
                onGenerate = { viewModel.generateSmartAlbums(context) },
                generating = state.generatingSmartAlbums,
                modifier   = Modifier.fillMaxSize().padding(padding),
            )

            else -> PullToRefreshBox(
                isRefreshing = false,
                onRefresh    = { viewModel.refresh(context) },
                modifier     = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyVerticalGrid(
                    columns               = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                    modifier              = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(4.dp)) }

                    items(state.albums, key = { it.id }) { album ->
                        AlbumCard(
                            album   = album,
                            onClick = { viewModel.openAlbum(context, album) },
                        )
                    }

                    item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: AlbumDto, onClick: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (album.cover_url != null) {
                    AsyncImage(
                        model              = album.cover_url,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoAlbum,
                        contentDescription = null,
                        tint     = Color.Gray,
                        modifier = Modifier.size(40.dp).align(Alignment.Center),
                    )
                }
                // Type badge
                val typeLabel = when (album.type) {
                    "trip"   -> "✈ Trip"
                    "people" -> "👤 People"
                    "pets"   -> "🐾 Pets"
                    "place"  -> "📍 Place"
                    "smart"  -> "✨ Smart"
                    else     -> null
                }
                if (typeLabel != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = .55f), MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(typeLabel, style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${album.photo_count} photo${if (album.photo_count != 1) "s" else ""}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun EmptyAlbumsState(onGenerate: () -> Unit, generating: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier.padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.PhotoAlbum, null, tint = Color.Gray, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text("No albums yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Tap the ✨ button to auto-generate albums from your photos using AI.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGenerate, enabled = !generating) {
            if (generating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Generate Smart Albums")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDetailScreen(
    album: AlbumDto,
    photos: List<com.psphere.network.PhotoDto>,
    loading: Boolean,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title       = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            photos.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No photos in this album", color = Color.Gray)
            }
            else -> LazyVerticalGrid(
                columns               = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
                modifier              = Modifier.fillMaxSize().padding(padding),
            ) {
                items(photos, key = { it.id }) { photo ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        AsyncImage(
                            model              = photo.thumb_url,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
