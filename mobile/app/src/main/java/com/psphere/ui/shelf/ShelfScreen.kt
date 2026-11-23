package com.psphere.ui.shelf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.ui.components.EmptyState
import com.psphere.ui.components.NotesCard
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import com.psphere.ui.components.timeAgo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ShelfData(
    val items: List<ShelfEntry> = emptyList(),
    val oldCount: Int = 0,
    val askAboutOld: Boolean = false,
    val hasLinked: Boolean = true,
    val loaded: Boolean = false,
)

/** What a card or the sheet can do; messages and errors go to the snackbar. */
private class ShelfActions(val context: Context, val scope: CoroutineScope, val message: (String) -> Unit, val save: (ShelfItem) -> Unit) {
    fun copy(item: ShelfItem) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PSphere", item.text))
        // Android 13 and later show their own "Copied" message.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message("Copied")
    }

    fun openLink(item: ShelfItem) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webAddress(item.text)))) }
            .onFailure { message("No app on this phone opens links like that.") }
    }

    fun shareText(item: ShelfItem) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text), null))
    }

    private fun withFile(entry: ShelfEntry, action: (File) -> Unit) {
        if (!entry.here) message("Getting it from ${entry.originName}…")
        scope.launch {
            val file = withContext(Dispatchers.IO) { Engine.node.shelfFile(entry.item, Engine.onlineLinked()) }
            if (file == null) { message("Couldn't get it. Make sure ${entry.originName} is on and on the same Wi-Fi."); return@launch }
            runCatching { action(file) }.onFailure { message(it.message ?: "Something went wrong.") }
        }
    }

    fun openFile(entry: ShelfEntry) = withFile(entry) { f ->
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, f, entry.item), mimeOf(entry.item.text))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(view) } catch (e: ActivityNotFoundException) { throw Exception("No app on this phone opens that kind of file.") }
    }

    fun shareFile(entry: ShelfEntry) = withFile(entry) { f ->
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(entry.item.text))
            .putExtra(Intent.EXTRA_STREAM, shareableUri(context, f, entry.item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    fun pin(item: ShelfItem) { scope.launch(Dispatchers.IO) { Engine.node.shelfPin(item.id, !item.pinned); Engine.shareShelf() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(ShelfData()) {
        val node = Engine.node
        ShelfData(node.shelfItems(), node.shelfOld().size, node.shelfShouldAskAboutOld(), node.devices().isNotEmpty(), loaded = true)
    }
    val hidden by Engine.shelfHidden.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var opened by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var savePending by remember { mutableStateOf<ShelfItem?>(null) }

    fun message(text: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text, duration = SnackbarDuration.Short) }
    }

    fun remove(ids: Collection<String>, label: String) {
        if (ids.isEmpty()) return
        Engine.removeShelfLater(ids)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) Engine.undoShelfRemoval()
        }
    }

    fun addText(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) { Engine.node.shelfAddText(text); Engine.shareShelf() }
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Engine.node.shelfAddFile(displayName(context, uri), it) } }
                    .onFailure { message("Couldn't add that file.") }
            }
            Engine.shareShelf()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val item = savePending ?: return@rememberLauncherForActivityResult
        savePending = null
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = Engine.node.shelfFile(item, Engine.onlineLinked()) ?: error("not reachable")
                    context.contentResolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            message(if (ok) "Saved" else "Couldn't save it")
        }
    }
    val actions = remember { ShelfActions(context, scope, ::message) { item -> savePending = item; saveTo.launch(item.text) } }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        when {
            clip?.uri != null -> addUris(listOf(clip.uri))
            clip != null && !clip.coerceToText(context).isNullOrBlank() -> addText(clip.coerceToText(context).toString())
            else -> message("There's nothing to paste")
        }
    }

    val visible = data.items.filter { it.item.id !in hidden }
    val pinned = visible.filter { it.item.pinned }
    val others = visible.filter { !it.item.pinned }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Done") } },
                    title = { Text("${selected.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            val chosen = visible.filter { it.item.id in selected }
                            val pinAll = !chosen.all { it.item.pinned }
                            scope.launch(Dispatchers.IO) { chosen.forEach { Engine.node.shelfPin(it.item.id, pinAll) }; Engine.shareShelf() }
                            selected = emptySet()
                        }) { Icon(Icons.Outlined.PushPin, "Pin") }
                        IconButton(onClick = {
                            val ids = selected
                            selected = emptySet()
                            remove(ids, if (ids.size == 1) "Removed" else "Removed ${ids.size} items")
                        }) { Icon(Icons.Default.DeleteOutline, "Remove") }
                    },
                )
            } else {
                TopAppBar(title = { Text("Shelf") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Composer(onAdd = ::addText, onPaste = ::paste, onAttach = { pickFiles.launch(arrayOf("*/*")) })
                    if (data.loaded && !data.hasLinked) NotesCard(listOf("Link a device first. What you put here shows up on your linked devices."))
                    if (data.askAboutOld) OldBar(
                        count = data.oldCount,
                        onClear = { remove(Engine.node.shelfOld().map { it.id }, if (data.oldCount == 1) "Cleared 1 item" else "Cleared ${data.oldCount} items") },
                        onKeep = { scope.launch(Dispatchers.IO) { Engine.node.shelfKeepOld() } },
                    )
                }
            }
            if (data.loaded && visible.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    EmptyState(Icons.Default.Inventory2, "Nothing on the Shelf yet", "Anything you put here appears on your linked devices. You can also share from any app and pick PSphere Shelf.")
                }
            }
            if (pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) { GroupLabel("Pinned") }
                items(pinned, key = { it.item.id }) { e ->
                    ShelfCard(e, selected = e.item.id in selected, actions = actions,
                        onTap = { if (selecting) toggle(e.item.id) else opened = e.item.id }, onLongPress = { toggle(e.item.id) })
                }
            }
            if (others.isNotEmpty()) {
                if (pinned.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { GroupLabel("Others") }
                items(others, key = { it.item.id }) { e ->
                    ShelfCard(e, selected = e.item.id in selected, actions = actions,
                        onTap = { if (selecting) toggle(e.item.id) else opened = e.item.id }, onLongPress = { toggle(e.item.id) })
                }
            }
        }
    }

    visible.firstOrNull { it.item.id == opened }?.let { e ->
        ItemSheet(e, actions, onDismiss = { opened = null }, onRemove = { opened = null; remove(listOf(e.item.id), "Removed") })
    }
}

/** The slim "Put something on the Shelf…" bar, which opens into a text box when tapped. */
@Composable
private fun Composer(onAdd: (String) -> Unit, onPaste: () -> Unit, onAttach: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun submit() { if (draft.isNotBlank()) { onAdd(draft); draft = ""; expanded = false } }

    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        if (!expanded) {
            Row(Modifier.clickable { expanded = true }.height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Put something on the Shelf…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") }
                IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Add files") }
            }
        } else {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).heightIn(min = 56.dp).focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused && draft.isBlank()) expanded = false },
                    decorationBox = { inner ->
                        Box { if (draft.isEmpty()) Text("Type or paste text or a link", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant); inner() }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Or share from any app", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") }
                    IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Add files") }
                    IconButton(onClick = ::submit, enabled = draft.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Put on the Shelf") }
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun OldBar(count: Int, onClear: () -> Unit, onKeep: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(99.dp)).background(cs.surfaceContainerHigh).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (count == 1) "1 item is over a week old" else "$count items are over a week old",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("Clear") }
        TextButton(onClick = onKeep) { Text("Keep") }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(entry: ShelfEntry, selected: Boolean, actions: ShelfActions, onTap: () -> Unit, onLongPress: () -> Unit) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    val picture = item.isFile && entry.here && isPicture(item.text)
    val copyable = item.kind != ShelfItem.Kind.FILE

    Surface(
        shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, cs.onSurface) else BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box {
            Column {
                if (picture) {
                    // Tall pictures are cropped on the card; they're shown whole when opened.
                    AsyncImage(
                        model = Engine.node.shelf.file(item.hash), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
                    )
                }
                Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (copyable) 30.dp else 12.dp)) {
                    CardContent(entry, picture)
                    Meta(entry)
                }
            }
            if (copyable && !selected) CopyCorner(Modifier.align(Alignment.TopEnd)) { actions.copy(item) }
            if (item.pinned && picture) PinBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (selected) {
                // Top right, where the copy button is while not selecting, so it never covers the text.
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(cs.onSurface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = cs.surface)
                }
            }
        }
    }
}

@Composable
private fun CardContent(entry: ShelfEntry, picture: Boolean) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    when (item.kind) {
        ShelfItem.Kind.TEXT -> Text(
            item.text, maxLines = 10, overflow = TextOverflow.Ellipsis,
            style = if (item.text.length < 28) MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp) else MaterialTheme.typography.bodyMedium,
        )
        ShelfItem.Kind.LINK -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Link, null, Modifier.size(15.dp))
                Text(siteOf(item.text), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ShelfItem.Kind.FILE -> if (picture) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FileBadge(item.text)
                Column {
                    Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(item.size), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Meta(entry: ShelfEntry) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!entry.here) Icon(Icons.Default.CloudDownload, "On ${entry.originName}, downloads when you open it", Modifier.size(13.dp), tint = cs.onSurfaceVariant)
        Text("${entry.originName} · ${timeAgo(entry.item.addedAt)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileBadge(name: String) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Text(extensionLabel(name), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyCorner(modifier: Modifier, onCopy: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(1500); done = false } }
    IconButton(onClick = { onCopy(); done = true }, modifier = modifier.size(36.dp)) {
        Icon(if (done) Icons.Default.Check else Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp), tint = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PinBadge(modifier: Modifier) {
    Box(modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(14.dp), tint = Color.White)
    }
}

/** A tapped card: the item in full, with all its actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemSheet(entry: ShelfEntry, actions: ShelfActions, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    val picture = item.isFile && entry.here && isPicture(item.text)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column {
                    if (picture) AsyncImage(
                        model = Engine.node.shelf.file(item.hash), contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).background(Color.Black),
                    )
                    Column(Modifier.padding(14.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        when (item.kind) {
                            ShelfItem.Kind.TEXT -> Text(item.text, style = MaterialTheme.typography.bodyLarge)
                            ShelfItem.Kind.LINK -> {
                                Text(siteOf(item.text), style = MaterialTheme.typography.titleMedium)
                                Text(item.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                            ShelfItem.Kind.FILE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!picture) FileBadge(item.text)
                                Column {
                                    Text(item.text, style = MaterialTheme.typography.titleSmall)
                                    Text(formatBytes(item.size) + if (!entry.here) " · on ${entry.originName}" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                        Meta(entry)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                when (item.kind) {
                    ShelfItem.Kind.TEXT -> {
                        SheetAction(Icons.Default.ContentCopy, "Copy") { actions.copy(item); onDismiss() }
                        SheetAction(Icons.Default.Share, "Share") { actions.shareText(item); onDismiss() }
                    }
                    ShelfItem.Kind.LINK -> {
                        SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openLink(item); onDismiss() }
                        SheetAction(Icons.Default.ContentCopy, "Copy") { actions.copy(item); onDismiss() }
                        SheetAction(Icons.Default.Share, "Share") { actions.shareText(item); onDismiss() }
                    }
                    ShelfItem.Kind.FILE -> {
                        SheetAction(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openFile(entry); onDismiss() }
                        SheetAction(Icons.Default.SaveAlt, "Save") { actions.save(item); onDismiss() }
                        SheetAction(Icons.Default.Share, "Share") { actions.shareFile(entry); onDismiss() }
                    }
                }
                SheetAction(if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (item.pinned) "Unpin" else "Pin") { actions.pin(item); onDismiss() }
                SheetAction(Icons.Default.DeleteOutline, "Remove", onClick = onRemove)
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

private fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif")

private fun extensionLabel(name: String) = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }

/** "https://www.example.com/a/b" → "example.com". */
private fun siteOf(url: String) = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

private fun webAddress(text: String) = text.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }

private fun mimeOf(name: String) =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** Shelf files are stored by content, without a name; other apps need the real name to open them. */
private fun shareableUri(context: Context, file: File, item: ShelfItem): Uri {
    val dir = File(context.cacheDir, "shelf-open/${item.hash.take(12)}").apply { mkdirs() }
    val named = File(dir, item.text)
    if (named.length() != file.length()) file.copyTo(named, overwrite = true)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named)
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
