package com.psphere.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.psphere.core.model.Media
import com.psphere.core.net.Wire
import com.psphere.core.node.LibraryItem
import com.psphere.core.node.Memory
import com.psphere.core.node.SmartAnswer
import com.psphere.core.node.SmartItem
import com.psphere.core.node.SmartOps
import com.psphere.core.node.timelineSections
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.smart.Smart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.imageio.ImageIO

/** Loads a JPEG/PNG file into a Compose bitmap, or null if it can't be read. Call off the UI thread. */
private fun loadBitmap(file: File?): ImageBitmap? =
    file?.takeIf { it.isFile }?.let { runCatching { ImageIO.read(it)?.toComposeImageBitmap() }.getOrNull() }

private fun decode(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }

/** Where the Photos tab is: the timeline, Explore, or a list of photos (search, person, place, thing). */
private sealed interface PhotosView {
    object Timeline : PhotosView
    object Explore : PhotosView
    data class Results(val query: String, val title: String = "“$query”") : PhotosView
    data class Person(val item: SmartItem) : PhotosView
    data class Place(val item: SmartItem) : PhotosView
}

/** Grid sizes, smallest first. The smallest groups photos by month. */
private val CELL_SIZES = listOf(84.dp, 140.dp, 220.dp)

/** Photos: one timeline across every linked device, with smart search, people, places and memories. */
@Composable
fun PhotosScreen(onOpenSettings: () -> Unit) {
    val loaded by rememberEngineData<List<LibraryItem>?>(null) {
        Engine.node.library().sortedByDescending { it.item.takenAt }
    }
    val items = loaded.orEmpty()
    val memories by rememberEngineData(emptyList<Memory>()) { Engine.node.memories() }
    val stack = remember { mutableStateListOf<PhotosView>(PhotosView.Timeline) }
    val view = stack.last()
    var query by remember { mutableStateOf("") }
    var zoom by remember { mutableIntStateOf(1) }
    var viewer by remember { mutableStateOf<Pair<List<LibraryItem>, Int>?>(null) }

    fun go(v: PhotosView) { stack.add(v) }
    fun home() { stack.clear(); stack.add(PhotosView.Timeline); query = "" }

    // A Box, so the full-size viewer sits on top of everything else.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        val title = when (view) {
            PhotosView.Timeline -> "Photos"
            PhotosView.Explore -> "Explore"
            is PhotosView.Results -> view.title
            is PhotosView.Person -> view.item.title.ifEmpty { "Someone" }
            is PhotosView.Place -> view.item.title
        }
        ScreenTitle(title, onBack = if (view == PhotosView.Timeline) null else ::home) {
            if (items.isNotEmpty() && view !is PhotosView.Explore && view !is PhotosView.Person) {
                IconButton(onClick = { zoom = (zoom - 1).coerceAtLeast(0) }, enabled = zoom > 0) { Icon(Icons.Outlined.ZoomOut, "Smaller") }
                IconButton(onClick = { zoom = (zoom + 1).coerceAtMost(CELL_SIZES.lastIndex) }, enabled = zoom < CELL_SIZES.lastIndex) { Icon(Icons.Outlined.ZoomIn, "Bigger") }
                Spacer(Modifier.width(8.dp))
            }
            if (items.isNotEmpty()) SearchBox(
                query,
                onChange = { query = it.take(Wire.MAX_QUERY) },
                onFocus = { if (query.isBlank() && view == PhotosView.Timeline) go(PhotosView.Explore) },
                onSearch = { if (query.isNotBlank()) go(PhotosView.Results(query.trim())) },
                onClear = { query = ""; home() },
            )
        }
        val zoomable = Modifier.zoomWithCtrlWheel { d -> zoom = (zoom + d).coerceIn(0, CELL_SIZES.lastIndex) }
        when (view) {
            PhotosView.Timeline ->
                if (loaded == null) Box(Modifier.fillMaxSize())
                else if (items.isEmpty()) EmptyPhotos()
                else Box(zoomable) { Timeline(items, memories, zoom, onOpen = { list, i -> viewer = list to i }) }
            PhotosView.Explore -> Explore(
                onPerson = { go(PhotosView.Person(it)) }, onPlace = { go(PhotosView.Place(it)) },
                onThing = { go(PhotosView.Results(it.id, it.title)) }, onOpenSettings = onOpenSettings,
            )
            is PhotosView.Results -> Box(zoomable) { SmartPhotos(SmartOps.SEARCH, listOf(view.query), zoom, onOpenSettings) { l, i -> viewer = l to i } }
            is PhotosView.Person -> PersonPage(view.item, onRenamed = { stack[stack.lastIndex] = PhotosView.Person(it) }, onOpen = { l, i -> viewer = l to i })
            is PhotosView.Place -> Box(zoomable) {
                Column {
                    if (view.item.subtitle.isNotEmpty()) Text(
                        view.item.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
                    )
                    SmartPhotos(SmartOps.PLACE, listOf(view.item.id), zoom, onOpenSettings) { l, i -> viewer = l to i }
                }
            }
        }
    }

    viewer?.let { (list, i) -> Viewer(list, i) { viewer = null } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.zoomWithCtrlWheel(onZoom: (Int) -> Unit): Modifier = this.onPointerEvent(PointerEventType.Scroll) { e ->
    if (e.keyboardModifiers.isCtrlPressed) {
        val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (dy != 0f) onZoom(if (dy < 0) 1 else -1)
        e.changes.forEach { it.consume() }
    }
}

// ── Timeline ─────────────────────────────────────────────────────────────────

@Composable
private fun Timeline(items: List<LibraryItem>, memories: List<Memory>, zoom: Int, onOpen: (List<LibraryItem>, Int) -> Unit) {
    val sections = remember(items, zoom) { timelineSections(items, byMonth = zoom == 0) }
    val index = remember(items) { items.withIndex().associate { (i, li) -> li.item.origin + "/" + li.item.key to i } }
    PhotoGridFrame(zoom) {
        if (memories.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "memories") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(memories, key = { it.yearsAgo }) { m -> MemoryCard(m) { onOpen(m.items, 0) } }
            }
        }
        for (s in sections) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:" + s.key) {
                Text(
                    s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            items(s.items, key = { it.item.origin + "/" + it.item.key }) { li ->
                PhotoCell(li, small = zoom == 0) { onOpen(items, index[li.item.origin + "/" + li.item.key] ?: 0) }
            }
        }
    }
}

@Composable
private fun PhotoGridFrame(zoom: Int, content: LazyGridScope.() -> Unit) {
    val gap = if (zoom == 0) 2.dp else 4.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = CELL_SIZES[zoom]),
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
private fun MemoryCard(m: Memory, onClick: () -> Unit) {
    val cover = m.items.firstOrNull { it.item.media == Media.PHOTO } ?: m.items.first()
    val bmp by previewOf(cover.item.hash)
    Box(Modifier.size(150.dp, 200.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(m.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(plural(m.items.size, "photo"), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun previewOf(hash: String) = produceState<ImageBitmap?>(null, hash) {
    value = withContext(Dispatchers.IO) { loadBitmap(Engine.node.previews.file(hash)) }
}

@Composable
private fun PhotoCell(li: LibraryItem, small: Boolean = false, onClick: () -> Unit) {
    val bmp by previewOf(li.item.hash)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(if (small) 2.dp else 6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(24.dp))
        if (li.item.media == Media.VIDEO) Icon(Icons.Filled.PlayCircle, "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(if (small) 20.dp else 32.dp))
        if (!li.onThisDevice && !small) Icon(Icons.Outlined.CloudQueue, "On another device", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
    }
}

// ── Search, Explore, people and places ───────────────────────────────────────

@Composable
private fun SearchBox(query: String, onChange: (String) -> Unit, onFocus: () -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search people, places, things…") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear search") } },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.width(340.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onSearch(); true } else false
            },
    )
}

/** Asks the smart features (this computer's, or a linked device's) and keeps the answer. */
@Composable
private fun smartAnswer(op: String, args: List<String>): SmartAnswer? {
    val smart by Smart.state.collectAsState()
    val engine by Engine.state.collectAsState()
    return produceState<SmartAnswer?>(null, op, args, smart::class, engine.revision) {
        value = withContext(Dispatchers.IO) { Engine.node.smartAsk(op, args, Engine.onlineLinked()) }
    }.value
}

@Composable
private fun SmartOff(onOpenSettings: () -> Unit) = EmptyState(
    Icons.Outlined.ImageSearch, "Smart photos is off",
    "Turn it on to search by describing photos, and to see your photos by people, places and things. It runs on this computer; nothing leaves your devices.",
) { Button(onClick = onOpenSettings) { Text("Open Settings") } }

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/** A grid of photos from a smart answer: a search, a place, or a kind of thing. */
@Composable
private fun SmartPhotos(op: String, args: List<String>, zoom: Int, onOpenSettings: () -> Unit, onOpen: (List<LibraryItem>, Int) -> Unit) {
    val answer = smartAnswer(op, args) ?: return Loading()
    val photos = remember(answer) { Engine.node.photosOf(answer.items) }
    when {
        answer.from.isEmpty() && !answer.saved -> SmartOff(onOpenSettings)
        photos.isEmpty() -> EmptyState(Icons.Outlined.ImageSearch, "No matches", "Try other words: what's in the photo, a person's name, a place, or words written in it.")
        else -> Column {
            Text(
                plural(photos.size, "photo") + if (op == SmartOps.SEARCH) ", best matches first" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
            )
            PhotoGridFrame(zoom) { items(photos, key = { it.item.origin + "/" + it.item.key }) { li -> PhotoCell(li, zoom == 0) { onOpen(photos, photos.indexOf(li)) } } }
        }
    }
}

@Composable
private fun Explore(onPerson: (SmartItem) -> Unit, onPlace: (SmartItem) -> Unit, onThing: (SmartItem) -> Unit, onOpenSettings: () -> Unit) {
    val answer = smartAnswer(SmartOps.EXPLORE, emptyList()) ?: return Loading()
    if (answer.from.isEmpty() && !answer.saved) return SmartOff(onOpenSettings)
    val people = answer.items.filter { it.kind == SmartItem.Kind.PERSON }
    val places = answer.items.filter { it.kind == SmartItem.Kind.PLACE }
    val things = answer.items.filter { it.kind == SmartItem.Kind.THING }
    val scanning = Smart.state.collectAsState().value as? com.psphere.desktop.smart.SmartState.Scanning
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning != null) item {
            Text(
                "Still looking through your photos (${"%,d".format(scanning.done)} of ${"%,d".format(scanning.total)}). More people, places and things appear as it goes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (people.isEmpty() && places.isEmpty() && things.isEmpty()) item {
            Text("Nothing to show yet. People appear once they're in a couple of photos; places come from photos with a location.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (people.isNotEmpty()) {
            item { SectionLabel("People") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(people, key = { it.id }) { p -> PersonBubble(p) { onPerson(p) } }
                }
            }
        }
        if (places.isNotEmpty()) {
            item { SectionLabel("Places") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(places, key = { it.id }) { pl -> CoverCard(pl.cover, pl.title, plural(pl.count, "photo")) { onPlace(pl) } } } }
        }
        if (things.isNotEmpty()) {
            item { SectionLabel("Things") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(things, key = { it.id }) { t -> CoverCard(t.cover, t.title, plural(t.count, "photo")) { onThing(t) } } } }
        }
    }
}

@Composable
private fun Avatar(item: SmartItem, size: Dp) {
    val bmp = remember(item.image) { decode(item.image) }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonBubble(p: SmartItem, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(p, 88.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            p.title.ifEmpty { "Add a name" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (p.title.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CoverCard(hash: String, title: String, sub: String, onClick: () -> Unit) {
    val bmp by previewOf(hash)
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.size(168.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
    }
}

@Composable
private fun PersonPage(person: SmartItem, onRenamed: (SmartItem) -> Unit, onOpen: (List<LibraryItem>, Int) -> Unit) {
    val answer = smartAnswer(SmartOps.PERSON, listOf(person.id))
    val photos = remember(answer) { answer?.let { Engine.node.photosOf(it.items) }.orEmpty() }
    var naming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (naming) NameDialog(person.title, onDismiss = { naming = false }) { name ->
        naming = false
        scope.launch {
            val after = withContext(Dispatchers.IO) { Engine.node.smartAsk(SmartOps.NAME, listOf(person.id, name), Engine.onlineLinked()) }
            // Naming someone with an existing name merges them; follow the merged person.
            val updated = after.items.firstOrNull { it.id == person.id } ?: after.items.firstOrNull { it.title.equals(name.trim(), true) }
            onRenamed(updated ?: person.copy(title = name.trim()))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(person, 64.dp)
            Column {
                TextButton(onClick = { naming = true }) {
                    Text(if (person.title.isEmpty()) "Add a name" else "Rename", style = MaterialTheme.typography.bodyLarge)
                }
                Text(plural(if (photos.isEmpty()) person.count else photos.size, "photo"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
        if (answer == null) Loading()
        else PhotoGridFrame(1) { items(photos, key = { it.item.origin + "/" + it.item.key }) { li -> PhotoCell(li) { onOpen(photos, photos.indexOf(li)) } } }
    }
}

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current.isEmpty()) "Who is this?" else "Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it.take(40) }, singleLine = true, label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onSave(text); true } else false
                    },
                )
                Text("If you use a name you've already given someone, the two are joined.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Viewer ───────────────────────────────────────────────────────────────────

private sealed interface Full {
    object Loading : Full
    data class Ready(val bitmap: ImageBitmap) : Full
    data class Note(val text: String) : Full
}

/** Full-size overlay. Left and right arrows (or keys) move through [items]; Esc closes. */
@Composable
private fun Viewer(items: List<LibraryItem>, start: Int, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(start.coerceIn(0, items.lastIndex)) }
    val li = items[index]
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val preview by previewOf(li.item.hash)
    val state by produceState<Full>(Full.Loading, li.item.hash) {
        value = Full.Loading
        value = withContext(Dispatchers.IO) {
            if (li.item.media == Media.VIDEO) Full.Note("Video. For now, open it from the backup folder.")
            else loadBitmap(Engine.node.fullFile(li.item, Engine.onlineLinked()))?.let { Full.Ready(it) }
                ?: loadBitmap(Engine.node.previews.file(li.item.hash))?.let { Full.Ready(it) }
                ?: Full.Note(holdersText(li))
        }
    }
    val info by produceState<SmartAnswer?>(null, li.item.hash) {
        value = withContext(Dispatchers.IO) { runCatching { Engine.node.smartAsk(SmartOps.PHOTO, listOf(li.item.hash), Engine.onlineLinked()) }.getOrNull() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f)).focusRequester(focus).focusable().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { if (index > 0) index--; true }
                Key.DirectionRight -> { if (index < items.lastIndex) index++; true }
                Key.Escape -> { onClose(); true }
                else -> false
            }
        },
    ) {
        when (val s = state) {
            // The preview right away while the full size loads (it may come from another device).
            is Full.Loading -> {
                preview?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 56.dp, bottom = 72.dp)) }
                CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp).size(22.dp), color = Color.White, strokeWidth = 2.dp)
            }
            is Full.Ready -> Image(s.bitmap, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 56.dp, bottom = 72.dp))
            is Full.Note -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CloudOff, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(s.text, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
        IconButton(onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        if (index > 0) IconButton({ index-- }, modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
        }
        if (index < items.lastIndex) IconButton({ index++ }, modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
        }
        // What, when, who, where.
        val people = info?.items.orEmpty().filter { it.kind == SmartItem.Kind.PERSON && it.title.isNotEmpty() }.map { it.title }
        val place = info?.items.orEmpty().firstOrNull { it.kind == SmartItem.Kind.PLACE }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(li.item.takenAt)), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            Text("from ${li.originName}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            if (people.isNotEmpty()) InfoChip(Icons.Outlined.Person, people.joinToString(", "))
            place?.let { InfoChip(Icons.Outlined.Place, it.title) }
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

private fun holdersText(li: LibraryItem): String {
    val names = li.holders.mapNotNull { id -> if (id == Engine.node.id) null else Engine.node.device(id)?.name }
    return if (names.isEmpty()) "Full size isn't on this computer, and no other device is reachable right now."
    else "Full size is on ${names.joinToString(", ")} · not reachable right now."
}

@Composable
private fun EmptyPhotos() = EmptyState(
    Icons.Outlined.PhotoLibrary, "No photos yet",
    "Photos and videos from your linked devices' backup folders show up here, all in one timeline.",
)
