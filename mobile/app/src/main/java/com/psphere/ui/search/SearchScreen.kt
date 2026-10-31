package com.psphere.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.psphere.network.AiSearchResultItem
import com.psphere.network.PhotoDto
import com.psphere.network.ServerImages
import com.psphere.ui.components.EmptyState

private val SUGGESTIONS = listOf("Beach sunsets", "Birthday 2025", "Dogs at the park", "Mountains", "Family dinner")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenPhoto: (photos: List<PhotoDto>, index: Int) -> Unit = { _, _ -> },
) {
    val context  = LocalContext.current
    val state    by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val runSearch = { keyboard?.hide(); viewModel.search(context) }

    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { padding ->
        Column(
            modifier            = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value         = state.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder   = { Text("Describe a photo, e.g. “Goa beach 2023”") },
                leadingIcon   = { Icon(Icons.Default.Search, null) },
                trailingIcon  = {
                    when {
                        state.loading          -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        state.query.isNotEmpty() -> IconButton(onClick = { viewModel.clear() }) { Icon(Icons.Default.Clear, "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                singleLine      = true,
                shape           = MaterialTheme.shapes.extraLarge,
                modifier        = Modifier.fillMaxWidth(),
            )

            if (state.explanation.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    Text(
                        state.explanation,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.fallback) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                "Keyword match",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            when {
                state.error.isNotEmpty() -> EmptyState(
                    icon  = Icons.Default.SearchOff,
                    title = "Search isn't available",
                    body  = state.error,
                )
                !state.hasSearched && !state.loading -> Column(Modifier.padding(top = 24.dp)) {
                    Text("Search your photos in plain words", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your server's local AI looks at places, dates, people and what's in each photo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s ->
                            SuggestionChip(
                                onClick = { viewModel.onQueryChange(s); runSearch() },
                                label   = { Text(s) },
                                icon    = { Icon(Icons.Default.ImageSearch, null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
                state.loading -> Unit
                state.results.isEmpty() -> EmptyState(
                    icon  = Icons.Default.SearchOff,
                    title = "No matching photos",
                    body  = "Try fewer words, or a place or year instead.",
                )
                else -> {
                    Text(
                        "${state.results.size} result${if (state.results.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val asPhotos = state.results.map { it.toPhotoDto() }
                    LazyVerticalGrid(
                        columns               = GridCells.Adaptive(minSize = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement   = Arrangement.spacedBy(2.dp),
                        modifier              = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(state.results, key = { _, r -> r.id }) { index, result ->
                            SearchResultCell(result) { onOpenPhoto(asPhotos, index) }
                        }
                    }
                }
            }
        }
    }
}

private fun AiSearchResultItem.toPhotoDto() =
    PhotoDto(id = id, taken_at = taken_at, thumb_url = thumb_url, width = null, height = null, camera = null)

@Composable
private fun SearchResultCell(result: AiSearchResultItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Open photo", onClick = onClick),
    ) {
        AsyncImage(
            model              = ServerImages.absolute(result.thumb_url),
            contentDescription = listOfNotNull(result.city, result.tags.firstOrNull()).joinToString(", ").ifEmpty { "Photo" },
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
        Text(
            "%.0f%% match".format(result.score * 100),
            style     = MaterialTheme.typography.labelSmall,
            color     = Color.White,
            textAlign = TextAlign.Start,
            modifier  = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = .55f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
