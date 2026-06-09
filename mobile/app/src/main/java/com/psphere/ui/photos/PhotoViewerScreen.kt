package com.psphere.ui.photos

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneId.systemDefault())

private fun formatDate(isoString: String?): String {
    if (isoString.isNullOrBlank()) return ""
    return try { DATE_FMT.format(Instant.parse(isoString)) } catch (_: Exception) { isoString.take(10) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    startIndex: Int = 0,
    onBack: () -> Unit,
    viewModel: PhotoViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()
    val photos  = state.photos
    if (photos.isEmpty()) return

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex), pageCount = { photos.size })
    var showChrome by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val photo = photos[page]
            val raw   = photo.thumb_url
            val url   = when {
                raw.startsWith("content://") || raw.startsWith("http") -> raw
                else -> "${com.psphere.BuildConfig.BASE_URL}${raw.replace("/sm", "/lg")}"
            }
            ZoomablePhoto(url = url, onTap = { showChrome = !showChrome })
        }

        AnimatedVisibility(visible = showChrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Back
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }

                // Page counter
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = .8f), fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )

                // Share button — handles both content:// and http:// URLs correctly
                IconButton(
                    onClick  = {
                        val photo    = photos[pagerState.currentPage]
                        val raw      = photo.thumb_url
                        val fullUrl  = when {
                            raw.startsWith("content://") || raw.startsWith("http") -> raw
                            else -> "${com.psphere.BuildConfig.BASE_URL}${raw.replace("/sm", "/lg")}"
                        }

                        val intent = if (fullUrl.startsWith("content://")) {
                            // Local file — share as stream
                            Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, fullUrl.toUri())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        } else {
                            // Remote URL — share as text link (can't stream HTTP directly)
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, fullUrl)
                            }
                        }
                        context.startActivity(Intent.createChooser(intent, "Share photo"))
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White)
                }

                // Date caption
                val date = formatDate(photos[pagerState.currentPage].taken_at)
                if (date.isNotEmpty()) {
                    Text(date, color = Color.White.copy(alpha = .75f), fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePhoto(url: String, onTap: () -> Unit) {
    var scale     by remember { mutableFloatStateOf(1f) }
    var offset    by remember { mutableStateOf(Offset.Zero) }
    var isLoading by remember { mutableStateOf(true) }
    var isError   by remember { mutableStateOf(false) }
    var retryKey  by remember { mutableStateOf(0) }

    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale  = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model              = "$url?retry=$retryKey",
            contentDescription = null,
            onState            = { s ->
                isLoading = s is AsyncImagePainter.State.Loading
                isError   = s is AsyncImagePainter.State.Error
            },
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )

        if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
        if (isError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { retryKey++ }) {
                Icon(Icons.Default.BrokenImage, "Failed to load", tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("Tap to retry", color = Color.White.copy(alpha = .5f), fontSize = 12.sp)
            }
        }
    }
}
