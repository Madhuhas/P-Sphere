package com.psphere.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.desktop.engine.DesktopSystem
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

private data class ShelfData(
    val items: List<ShelfEntry> = emptyList(),
    val oldCount: Int = 0,
    val askAboutOld: Boolean = false,
    val hasLinked: Boolean = true,
    val loaded: Boolean = false,
)

/** What a card or the open item can do; messages and errors go to the snackbar. */
private class ShelfActions(val scope: CoroutineScope, val uri: UriHandler, val message: (String) -> Unit) {
    fun copy(item: ShelfItem) { copyText(item.text); message("Copied") }

    fun openLink(item: ShelfItem) { runCatching { uri.openUri(webAddress(item.text)) }.onFailure { message("Couldn't open that link.") } }

    private fun withFile(entry: ShelfEntry, done: String? = null, action: (File) -> Unit) {
        if (!entry.here) message("Getting it from ${entry.originName}…")
        scope.launch {
            val file = withContext(Dispatchers.IO) { Engine.node.shelfFile(entry.item, Engine.onlineLinked()) }
            if (file == null) { message("Couldn't get it. Make sure ${entry.originName} is on and on the same Wi-Fi."); return@launch }
            runCatching { withContext(Dispatchers.IO) { action(file) } }
                .onSuccess { done?.let(message) }
                .onFailure { message(it.message ?: "Something went wrong.") }
        }
    }

    fun openFile(entry: ShelfEntry) = withFile(entry) { f ->
        if (!DesktopSystem.openFolder(namedCopy(f, entry.item).path)) throw Exception("No app on this computer opens that kind of file.")
    }

    fun saveFile(entry: ShelfEntry) = withFile(entry, done = "Saved") { f ->
        val target = saveAs(entry.item.text) ?: throw Exception("Not saved.")
        f.copyTo(target, overwrite = true)
    }

    fun pin(item: ShelfItem) { scope.launch(Dispatchers.IO) { Engine.node.shelfPin(item.id, !item.pinned); Engine.shareShelf() } }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ShelfScreen() {
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(ShelfData()) {
        val node = Engine.node
        ShelfData(node.shelfItems(), node.shelfOld().size, node.shelfShouldAskAboutOld(), node.devices().isNotEmpty(), loaded = true)
    }
    val hidden by Engine.shelfHidden.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var opened by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    fun message(text: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text, duration = SnackbarDuration.Short) }
    }
    val actions = remember { ShelfActions(scope, uri, ::message) }

    fun remove(ids: Collection<String>, label: String) {
        if (ids.isEmpty()) return
        Engine.removeShelfLater(ids)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(label, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) Engine.undoShelfRemoval()
        }
    }

    fun addFiles(files: List<File>) {
        val usable = files.filter { it.isFile }
        if (usable.size < files.size) message("Folders can't go on the Shelf, only files.")
        if (usable.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            usable.forEach { f -> runCatching { f.inputStream().use { Engine.node.shelfAddFile(f.name, it) } }.onFailure { message("Couldn't add ${f.name}.") } }
            Engine.shareShelf()
        }
    }

    fun addText(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) { Engine.node.shelfAddText(text); Engine.shareShelf() }
    }

    /** Files or a picture from a paste or a drop. False if it holds neither, so plain text pastes as usual. */
    fun addTransfer(t: Transferable?): Boolean {
        t ?: return false
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching { t.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull() ?: return false
            addFiles(files)
            return true
        }
        if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val png = runCatching { pngOf(t.getTransferData(DataFlavor.imageFlavor) as java.awt.Image) }.getOrNull() ?: return false
            val name = "Screenshot ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))}.png"
            scope.launch(Dispatchers.IO) { Engine.node.shelfAddFile(name, png.inputStream()); Engine.shareShelf() }
            return true
        }
        return false
    }

    fun paste() {
        val clip = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
        if (addTransfer(clip)) return
        val text = runCatching { clip?.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (text.isNullOrBlank()) message("There's nothing to paste.") else addText(text)
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean { dragging = false; return addTransfer(event.awtTransferable) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val visible = data.items.filter { it.item.id !in hidden }
    val pinned = visible.filter { it.item.pinned }
    val others = visible.filter { !it.item.pinned }

    Box(
        Modifier.fillMaxSize()
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                // Files and screenshots are taken from any paste; plain text goes to the text box as usual.
                paste && addTransfer(runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull())
            }
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            .then(if (dragging) Modifier.border(2.dp, cs.primary, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Shelf") {
                if (others.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(210.dp),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Composer(
                            hint = "Ctrl+Enter to put it on the Shelf",
                            onAdd = ::addText, onPaste = ::paste, onAttach = { pickFiles()?.let(::addFiles) },
                            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        )
                        Text(
                            if (dragging) "Drop to put it on the Shelf" else "Drag files anywhere here, or press Ctrl+V to paste files and screenshots.",
                            style = MaterialTheme.typography.bodySmall, color = if (dragging) cs.primary else cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        if (data.loaded && !data.hasLinked) Box(Modifier.widthIn(max = 640.dp).padding(top = 8.dp)) {
                            NotesCard(listOf("Link a device first. What you put here shows up on your linked devices."))
                        }
                        if (data.askAboutOld) OldBar(
                            count = data.oldCount,
                            onClear = { remove(Engine.node.shelfOld().map { it.id }, if (data.oldCount == 1) "Cleared 1 item" else "Cleared ${data.oldCount} items") },
                            onKeep = { scope.launch(Dispatchers.IO) { Engine.node.shelfKeepOld() } },
                            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
                if (data.loaded && visible.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        EmptyState(Icons.Default.Inventory2, "Nothing on the Shelf yet", "Anything you put here appears on your linked devices, ready to copy, open or save.")
                    }
                }
                if (pinned.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) { GroupLabel("Pinned") }
                    items(pinned, key = { it.item.id }) { e ->
                        ShelfCard(e, actions, onOpen = { opened = e.item.id }, onRemove = { remove(listOf(e.item.id), "Removed") })
                    }
                }
                if (others.isNotEmpty()) {
                    if (pinned.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { GroupLabel("Others") }
                    items(others, key = { it.item.id }) { e ->
                        ShelfCard(e, actions, onOpen = { opened = e.item.id }, onRemove = { remove(listOf(e.item.id), "Removed") })
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomEnd).padding(24.dp).widthIn(max = 380.dp))
    }

    visible.firstOrNull { it.item.id == opened }?.let { e ->
        OpenItemDialog(e, actions, onDismiss = { opened = null }, onRemove = { opened = null; remove(listOf(e.item.id), "Removed") })
    }

    if (confirmClear) {
        val ids = others.map { it.item.id }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the Shelf?") },
            text = { Text("This takes ${plural(ids.size, "item")} off the Shelf on all your devices. Pinned items stay.") },
            confirmButton = { Button(onClick = { confirmClear = false; remove(ids, "Cleared ${plural(ids.size, "item")}") }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** The slim "Put something on the Shelf…" bar, which opens into a text box when clicked. */
@Composable
private fun Composer(hint: String, onAdd: (String) -> Unit, onPaste: () -> Unit, onAttach: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun submit() { if (draft.isNotBlank()) { onAdd(draft); draft = ""; expanded = false } }

    Surface(shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant), modifier = modifier) {
        if (!expanded) {
            Row(Modifier.clickable { expanded = true }.height(52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Put something on the Shelf…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") }
                IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Add files") }
            }
        } else {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused && draft.isBlank()) expanded = false }
                        .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && e.isCtrlPressed) { submit(); true } else false },
                    decorationBox = { inner ->
                        Box { if (draft.isEmpty()) Text("Type or paste text or a link", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant); inner() }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") }
                    IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Add files") }
                    Button(onClick = ::submit, enabled = draft.isNotBlank()) { Text("Put on Shelf") }
                }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

@Composable
private fun OldBar(count: Int, onClear: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.clip(RoundedCornerShape(99.dp)).background(cs.surfaceContainerHigh).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (count == 1) "1 item is over a week old" else "$count items are over a week old", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Clear") }
        TextButton(onClick = onKeep) { Text("Keep") }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 10.dp),
    )
}

@Composable
private fun ShelfCard(entry: ShelfEntry, actions: ShelfActions, onOpen: () -> Unit, onRemove: () -> Unit) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thumb by rememberThumb(entry, 420)
    val copyable = item.kind != ShelfItem.Kind.FILE

    Surface(
        shape = RoundedCornerShape(14.dp), color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, if (hovered) cs.outline else cs.outlineVariant),
        modifier = Modifier.fillMaxWidth().hoverable(hover).clickable(onClick = onOpen),
    ) {
        Box {
            Column {
                thumb?.let { t ->
                    // Tall pictures are cropped on the card; they're shown whole when opened.
                    Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio((t.width.toFloat() / t.height).coerceAtLeast(1.05f)))
                }
                Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (copyable) 36.dp else 12.dp)) {
                    CardContent(entry, hasThumb = thumb != null)
                    Meta(entry)
                }
                if (hovered) {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (item.kind) {
                            ShelfItem.Kind.LINK -> SmallIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openLink(item) }
                            ShelfItem.Kind.FILE -> {
                                SmallIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openFile(entry) }
                                SmallIcon(Icons.Default.SaveAlt, "Save as…") { actions.saveFile(entry) }
                            }
                            ShelfItem.Kind.TEXT -> {}
                        }
                        Spacer(Modifier.weight(1f))
                        SmallIcon(if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (item.pinned) "Unpin" else "Pin") { actions.pin(item) }
                        SmallIcon(Icons.Default.DeleteOutline, "Remove", onClick = onRemove)
                    }
                }
            }
            if (copyable) CopyCorner(Modifier.align(Alignment.TopEnd)) { actions.copy(item) }
            if (item.pinned && thumb != null) PinBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

@Composable
private fun CardContent(entry: ShelfEntry, hasThumb: Boolean) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    when (item.kind) {
        ShelfItem.Kind.TEXT -> Text(
            item.text, maxLines = 10, overflow = TextOverflow.Ellipsis,
            style = if (item.text.length < 28) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
        )
        ShelfItem.Kind.LINK -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                Text(siteOf(item.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        ShelfItem.Kind.FILE -> if (hasThumb) {
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
        Text("${entry.originName} · ${timeAgo(entry.item.addedAt)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
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
    IconButton(onClick = { onCopy(); done = true }, modifier = modifier.padding(2.dp).size(32.dp)) {
        Icon(if (done) Icons.Default.Check else Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp), tint = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PinBadge(modifier: Modifier) {
    Box(modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(14.dp), tint = Color.White)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, label, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An item opened from the grid: shown whole, with all its actions. */
@Composable
private fun OpenItemDialog(entry: ShelfEntry, actions: ShelfActions, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val item = entry.item
    val cs = MaterialTheme.colorScheme
    val picture by rememberThumb(entry, 1400)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = cs.surfaceContainer, modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(18.dp)) {
                picture?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) }
                Column(Modifier.padding(top = if (picture != null) 12.dp else 0.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when (item.kind) {
                        ShelfItem.Kind.TEXT -> SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
                        ShelfItem.Kind.LINK -> {
                            Text(siteOf(item.text), style = MaterialTheme.typography.titleLarge)
                            SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                        }
                        ShelfItem.Kind.FILE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (picture == null) FileBadge(item.text)
                            Column {
                                Text(item.text, style = MaterialTheme.typography.titleMedium)
                                Text(formatBytes(item.size) + if (!entry.here) " · on ${entry.originName}" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                Meta(entry)
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (item.kind) {
                        ShelfItem.Kind.TEXT -> Chip(Icons.Default.ContentCopy, "Copy") { actions.copy(item) }
                        ShelfItem.Kind.LINK -> {
                            Chip(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openLink(item) }
                            Chip(Icons.Default.ContentCopy, "Copy") { actions.copy(item) }
                        }
                        ShelfItem.Kind.FILE -> {
                            Chip(Icons.AutoMirrored.Filled.OpenInNew, "Open") { actions.openFile(entry) }
                            Chip(Icons.Default.SaveAlt, "Save as…") { actions.saveFile(entry) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Chip(if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (item.pinned) "Unpin" else "Pin") { actions.pin(item); onDismiss() }
                    Chip(Icons.Default.DeleteOutline, "Remove", onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text(label)
    }
}

/** A picture's thumbnail (or larger, for the open view), once its file is on this computer. */
@Composable
private fun rememberThumb(entry: ShelfEntry, maxPx: Int) = produceState<ImageBitmap?>(null, entry.item.hash, entry.here, maxPx) {
    val item = entry.item
    value = if (item.isFile && entry.here && isPicture(item.text)) withContext(Dispatchers.IO) {
        Engine.node.shelf.file(item.hash)?.let { f -> runCatching { Images.readUpright(f, maxPx)?.toComposeImageBitmap() }.getOrNull() }
    } else null
}

private fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

private fun extensionLabel(name: String) = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }

/** "https://www.example.com/a/b" → "example.com". */
private fun siteOf(url: String) = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")

private fun copyText(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }

/** "www.example.com" opens as "https://www.example.com". */
private fun webAddress(text: String) = text.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }

/** Shelf files are stored by content, without a name; apps need the real name (and extension) to open them. */
private fun namedCopy(file: File, item: ShelfItem): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "PSphere Shelf/${item.hash.take(12)}").apply { mkdirs() }
    val target = File(dir, item.text)
    if (target.length() != file.length()) file.copyTo(target, overwrite = true)
    return target
}

private fun pickFiles(): List<File>? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Put files on the Shelf", java.awt.FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
    return dialog.files?.toList()?.takeIf { it.isNotEmpty() }
}

private fun saveAs(name: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Save", java.awt.FileDialog.SAVE).apply { file = name; isVisible = true }
    val file = dialog.file ?: return null
    return File(dialog.directory, file)
}

private fun pngOf(image: java.awt.Image): ByteArray {
    val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also { b ->
        b.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    }
    return ByteArrayOutputStream().also { ImageIO.write(buffered, "png", it) }.toByteArray()
}
