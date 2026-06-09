package com.psphere.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.psphere.network.AiSearchResultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel = viewModel()) {
    val context  = LocalContext.current
    val state    by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Search") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Search bar ────────────────────────────────────────────────────
            OutlinedTextField(
                value           = state.query,
                onValueChange   = { viewModel.onQueryChange(it) },
                placeholder     = { Text("\"Photos from Goa 2023\" or \"beach sunsets\"") },
                leadingIcon     = { Icon(Icons.Default.Search, null) },
                trailingIcon    = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clear() }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    } else if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    viewModel.search(context)
                }),
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth(),
            )

            // ── Explanation + fallback badge ───────────────────────────────────
            if (state.explanation.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(state.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.fallback) {
                        Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                            Text("Keyword", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }

            // ── Results / states ──────────────────────────────────────────────
            when {
                state.error.isNotEmpty() ->
                    Text(state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))

                !state.hasSearched -> EmptySearchState()

                state.results.isEmpty() ->
                    Text("No matching photos found.", color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))

                else -> {
                    Text("${state.results.size} result${if (state.results.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                    LazyVerticalGrid(
                        columns               = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement   = Arrangement.spacedBy(2.dp),
                        modifier              = Modifier.fillMaxSize(),
                    ) {
                        items(state.results, key = { it.id }) { result ->
                            SearchResultCell(result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCell(result: AiSearchResultItem) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model              = result.thumb_url,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
        // Score bar at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = .45f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                "%.0f%%".format(result.score * 100),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun EmptySearchState() {
    Column(
        modifier            = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Search your photos in plain English", style = MaterialTheme.typography.titleSmall, color = Color.Gray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Try: \"dog at the park\", \"birthday 2022\", or \"beach sunset\"",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
