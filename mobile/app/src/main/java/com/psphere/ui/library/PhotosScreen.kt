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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.model.ItemState
import com.psphere.core.model.Media
import com.psphere.core.net.Wire
import com.psphere.core.node.LibraryItem
import com.psphere.core.node.Memory
import com.psphere.core.node.SmartAnswer
import com.psphere.core.node.SmartItem
import com.psphere.core.node.SmartOps
import com.psphere.core.node.timelineSections
import com.psphere.ui.components.rememberEngineData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the Photos tab is: the timeline, search (Explore), or one list of photos. */
private sealed interface PhotosView {
    object Timeline : PhotosView
    object Search : PhotosView
    data class Results(val query: String, val title: String) : PhotosView
    data class Person(val item: SmartItem) : PhotosView
    data class Place(val item: SmartItem) : PhotosView
}

/** Columns in the grid; pinch to change. The densest groups photos by month. */
private val COLUMNS = listOf(2, 3, 5, 7)

/**
 * Photos: one timeline across every linked device, newest first under date headings, with
 * Memories on top. Pinch to change the grid size. Search opens Explore (people, places, things),
 * answered by the computer's smart photos. Tapping a photo opens the full size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen() {
    val loaded by rememberEngineData<List<LibraryItem>?>(null) {
        Engine.node.library(includeDeleted = true).sortedByDescending { it.item.takenAt }
    }
    val all = loaded.orEmpty()
    val memories by rememberEngineData(emptyList<Memory>()) { Engine.node.memories() }
    var device by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var showDeleted by remember { mutableStateOf(false) }
    var columnsAt by rememberSaveable { mutableIntStateOf(1) }
    var viewer by remember { mutableStateOf<Pair<List<LibraryItem>, Int>?>(null) }
    val stack = remember { mutableStateListOf<PhotosView>(PhotosView.Timeline) }
    val view = stack.last()
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<SmartItem?>(null) }

    fun go(v: PhotosView) { stack.add(v) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex); if (stack.last() == PhotosView.Timeline) query = "" }
    if (stack.size > 1) BackHandler(onBack = ::back)

    val folderOf = remember(all) { all.associate { it.item.hash to (Engine.node.catalog.rule(it.item.origin, it.item.ruleId)?.label ?: "Folder") } }
    val devices = remember(all) { all.map { it.originName }.distinct().sorted() }
    val folders = remember(all) { folderOf.values.distinct().sorted() }
    val hasDeleted = remember(all) { all.any { it.item.state == ItemState.DELETED } }
    val shown = all.filter {
        (device == null || it.originName == device) && (folder == null || folderOf[it.item.hash] == folder) &&
            (showDeleted || it.item.state != ItemState.DELETED)
    }

    naming?.let { p ->
        NameDialog(p.title, onDismiss = { naming = null }) { name ->
            naming = null
            scope.launch {
                val after = withContext(Dispatchers.IO) { Engine.node.smartAsk(SmartOps.NAME, listOf(p.id, name), Engine.onlineLinked()) }
                val updated = after.items.firstOrNull { it.id == p.id } ?: after.items.firstOrNull { it.title.equals(name.trim(), true) }
                if (stack.last() is PhotosView.Person) stack[stack.lastIndex] = PhotosView.Person(updated ?: p.copy(title = name.trim()))
                refresh++
            }
        }
    }

    Scaffold(
        topBar = {
            when (view) {
                PhotosView.Timeline -> TopAppBar(
                    title = { Text("Photos") },
                    actions = {
                        if (all.isNotEmpty()) {
                            IconButton(onClick = { query = ""; go(PhotosView.Search) }) { Icon(Icons.Filled.Search, "Search photos") }
                            FilterMenu(devices, device, { device = it }, folders, folder, { folder = it }, hasDeleted, showDeleted, { showDeleted = it })
                        }
                    },
                )
                PhotosView.Search -> SearchBar(query, { query = it.take(Wire.MAX_QUERY) }, onSearch = {
                    if (query.isNotBlank()) go(PhotosView.Results(query.trim(), "“${query.trim()}”"))
                }, onClose = ::back)
                is PhotosView.Results -> TopAppBar(title = { Text(view.title) }, navigationIcon = { BackButton(::back) })
                is PhotosView.Person -> TopAppBar(title = { Text(view.item.title.ifEmpty { "Someone" }) }, navigationIcon = { BackButton(::back) })
                is PhotosView.Place -> TopAppBar(
                    title = {
                        Column {
                            Text(view.item.title)
                            if (view.item.subtitle.isNotEmpty()) Text(view.item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { BackButton(::back) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().pinchToResize { d -> columnsAt = (columnsAt + d).coerceIn(0, COLUMNS.lastIndex) }) {
            val columns = COLUMNS[columnsAt]
            when (view) {
                PhotosView.Timeline -> when {
                    loaded == null -> Unit
                    all.isEmpty() -> EmptyPhotos()
                    shown.isEmpty() -> Centered("Nothing matches these filters.")
                    else -> Timeline(shown, if (device == null && folder == null) memories else emptyList(), columns) { list, i -> viewer = list to i }
                }
                PhotosView.Search -> Explore(
                    onPerson = { go(PhotosView.Person(it)) }, onPlace = { go(PhotosView.Place(it)) },
                    onThing = { go(PhotosView.Results(it.id, it.title)) },
                )
                is PhotosView.Results -> SmartPhotos(SmartOps.SEARCH, listOf(view.query), columns) { l, i -> viewer = l to i }
                is PhotosView.Person -> SmartPhotos(
                    SmartOps.PERSON, listOf(view.item.id), columns, refresh,
                    header = { n -> PersonHeader(view.item, n) { naming = view.item } },
                ) { l, i -> viewer = l to i }
                is PhotosView.Place -> SmartPhotos(SmartOps.PLACE, listOf(view.item.id), columns) { l, i -> viewer = l to i }
            }
        }
    }

    viewer?.let { (list, i) ->
        // A full-screen window, so the viewer covers the tab bar too.
        Dialog(
            onDismissRequest = { viewer = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { PhotoViewer(list, i, onClose = { viewer = null }) }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) = IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }

/**
 * Two-finger pinch changes the grid size (spread = bigger photos); one finger still scrolls.
 * Watches the gesture before the grid does, and only takes it once a second finger is down.
 */
private fun Modifier.pinchToResize(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (zoom > 1.35f) { onStep(-1); zoom = 1f }      // spread: fewer, bigger photos
                else if (zoom < 0.74f) { onStep(1); zoom = 1f }  // pinch: more, smaller photos
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun FilterMenu(
    devices: List<String>, device: String?, onDevice: (String?) -> Unit,
    folders: List<String>, folder: String?, onFolder: (String?) -> Unit,
    hasDeleted: Boolean, showDeleted: Boolean, onShowDeleted: (Boolean) -> Unit,
) {
    if (devices.size < 2 && folders.size < 2 && !hasDeleted) return
    var open by remember { mutableStateOf(false) }
    val active = device != null || folder != null || showDeleted
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.FilterList, "Filter", tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (devices.size > 1) {
                MenuLabel("From")
                MenuOption("All devices", device == null) { onDevice(null); open = false }
                devices.forEach { d -> MenuOption(d, device == d) { onDevice(d); open = false } }
            }
            if (folders.size > 1) {
                HorizontalDivider()
                MenuLabel("Folder")
                MenuOption("All folders", folder == null) { onFolder(null); open = false }
                folders.forEach { f -> MenuOption(f, folder == f) { onFolder(f); open = false } }
            }
            if (hasDeleted) {
                HorizontalDivider()
                MenuOption("Show deleted on original", showDeleted) { onShowDeleted(!showDeleted); open = false }
            }
        }
    }
}

@Composable
private fun MenuOption(label: String, on: Boolean, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(label) }, onClick = onClick,
    trailingIcon = { if (on) Icon(Icons.Filled.Check, null) },
)

@Composable
private fun MenuLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

@Composable
private fun Timeline(items: List<LibraryItem>, memories: List<Memory>, columns: Int, onOpen: (List<LibraryItem>, Int) -> Unit) {
    val byMonth = columns >= 7
    val sections = remember(items, byMonth) { timelineSections(items, byMonth = byMonth) }
    val index = remember(items) { items.withIndex().associate { (i, li) -> li.item.origin + "/" + li.item.key to i } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (memories.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "memories") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            ) {
                items(memories, key = { it.yearsAgo }) { m -> MemoryCard(m) { onOpen(m.items, 0) } }
            }
        }
        for (s in sections) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:" + s.key) {
                Text(
                    s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(s.items, key = { it.item.origin + "/" + it.item.key }) { li ->
                PhotoCell(li, small = columns > 4) { onOpen(items, index[li.item.origin + "/" + li.item.key] ?: 0) }
            }
        }
    }
}

@Composable
private fun MemoryCard(m: Memory, onClick: () -> Unit) {
    val cover = m.items.firstOrNull { it.item.media == Media.PHOTO } ?: m.items.first()
    val file = remember(cover.item.hash) { Engine.node.previews.file(cover.item.hash) }
    Box(Modifier.size(112.dp, 150.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        if (file != null) AsyncImage(file, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
        Text(m.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
    // No keyboard until the box is tapped, so Explore (people, places, things) stays in view.
    TopAppBar(
        navigationIcon = { BackButton(onClose) },
        title = {
            TextField(
                value = query,
                onValueChange = onChange,
                placeholder = { Text("Search people, places, things") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear") } },
    )
}

@Composable
internal fun PhotoCell(li: LibraryItem, small: Boolean = false, onClick: () -> Unit) {
    val preview = remember(li.item.hash) { Engine.node.previews.file(li.item.hash) }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        if (preview != null) {
            AsyncImage(preview, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(if (small) 16.dp else 28.dp))
        }
        if (li.item.media == Media.VIDEO) {
            Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(if (small) 18.dp else 30.dp))
        }
        if (!li.onThisDevice && !small) {
            Icon(Icons.Outlined.CloudQueue, "On another device", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
        }
    }
}

/** Who's in the photo and where it was taken, from the computer's smart photos (or what this phone kept). */
@Composable
private fun PhotoInfo(li: LibraryItem, modifier: Modifier = Modifier) {
    val info by produceState<SmartAnswer?>(null, li.item.hash) {
        value = withContext(Dispatchers.IO) { runCatching { Engine.node.smartAsk(SmartOps.PHOTO, listOf(li.item.hash), Engine.onlineLinked()) }.getOrNull() }
    }
    val people = info?.items.orEmpty().filter { it.kind == SmartItem.Kind.PERSON && it.title.isNotEmpty() }.map { it.title }
    val place = info?.items.orEmpty().firstOrNull { it.kind == SmartItem.Kind.PLACE }
    if (people.isEmpty() && place == null) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (people.isNotEmpty()) InfoChip(Icons.Outlined.Person, people.joinToString(", "))
        place?.let { InfoChip(Icons.Outlined.Place, it.title) }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
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
        if (!zoomed) PhotoInfo(current, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))

        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        // Show the preview straight away while the full size loads (it may come from another device).
        is Full.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (preview != null) AsyncImage(preview, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).size(24.dp))
        }
        is Full.Ready -> ZoomableImage(s.file, preview, onZoom)
        is Full.Video -> PreviewWithNote(preview, "Video. Download it to play it; playing videos in the app is coming.")
        is Full.Away -> PreviewWithNote(preview, s.where)
    }
}

/**
 * Zoomable image. Two-finger pinch zooms from any scale (and is consumed, so the pager doesn't
 * swipe mid-pinch); a one-finger drag pans when zoomed, or passes through to the pager for swiping
 * at normal zoom. Double-tap toggles zoom.
 */
@Composable
private fun ZoomableImage(file: File, preview: File?, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var failed by remember(file) { mutableStateOf(false) }

    // Not a decodable image (e.g. a non-media file) — show the preview + a note instead of black.
    if (failed) {
        PreviewWithNote(preview, "Can't preview this file. Download or share it to open it in another app.")
        return
    }

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
            onError = { failed = true },
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
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
