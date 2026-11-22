package com.psphere.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.desktop.engine.DesktopSystem
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.Images
import kotlinx.coroutines.Dispatchers
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ShelfScreen() {
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(ShelfData()) {
        val node = Engine.node
        ShelfData(node.shelfItems(), node.shelfOld().size, node.shelfShouldAskAboutOld(), node.devices().isNotEmpty(), loaded = true)
    }
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val cs = MaterialTheme.colorScheme

    fun addFiles(files: List<File>) {
        val usable = files.filter { it.isFile }
        note = if (usable.size < files.size) "Folders can't go on the Shelf, only files." else null
        if (usable.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            usable.forEach { f -> runCatching { f.inputStream().use { Engine.node.shelfAddFile(f.name, it) } }.onFailure { note = "Couldn't add ${f.name}: ${it.message}" } }
            Engine.shareShelf()
        }
    }

    fun addText(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) { Engine.node.shelfAddText(text); Engine.shareShelf() }
    }

    /** Files or a picture from a paste or a drop. False if it holds neither (so plain text pastes as usual). */
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

    fun pasteButton() {
        val clip = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
        if (addTransfer(clip)) return
        val text = runCatching { clip?.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (text.isNullOrBlank()) note = "There's nothing to paste." else addText(text)
    }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                return addTransfer(event.awtTransferable)
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
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
        ScreenTitle("Shelf") {
            TextButton(onClick = ::pasteButton) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Paste")
            }
            TextButton(onClick = { pickFiles()?.let(::addFiles) }) {
                Icon(Icons.Default.InsertDriveFile, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Add files…")
            }
            if (data.items.any { !it.item.pinned }) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        ScreenBody {
            Text(
                "Put text, links and files here to pick them up on your other devices.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Type or paste text or a link") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && e.isCtrlPressed) { addText(draft); draft = ""; true } else false
                    },
                )
                Button(onClick = { addText(draft); draft = "" }, enabled = draft.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Put on Shelf") }
            }
            Text(
                if (dragging) "Drop to put it on the Shelf" else "Drag files here, or press Ctrl+V to paste files and screenshots. Ctrl+Enter puts your text on the Shelf.",
                style = MaterialTheme.typography.bodySmall, color = if (dragging) cs.primary else cs.onSurfaceVariant,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
            if (!data.hasLinked && data.loaded) {
                NotesCard(listOf("Link a device first. What you put here shows up on your linked devices."))
            }

            if (data.askAboutOld) {
                OldItemsCard(
                    count = data.oldCount,
                    onClear = { scope.launch(Dispatchers.IO) { Engine.node.shelfRemove(Engine.node.shelfOld().map { it.id }); Engine.shareShelf() } },
                    onKeep = { scope.launch(Dispatchers.IO) { Engine.node.shelfKeepOld() } },
                )
            }

            if (data.loaded && data.items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    EmptyState(Icons.Default.Inventory2, "Nothing on the Shelf yet", "Anything you put here appears on your linked devices, ready to copy, open or save.")
                }
            }
            data.items.forEach { entry -> ShelfRow(entry) }
        }
    }

    if (confirmClear) {
        val n = data.items.count { !it.item.pinned }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the Shelf?") },
            text = { Text("This takes ${plural(n, "item")} off the Shelf on all your devices. Pinned items stay.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    val ids = data.items.filter { !it.item.pinned }.map { it.item.id }
                    scope.launch(Dispatchers.IO) { Engine.node.shelfRemove(ids); Engine.shareShelf() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OldItemsCard(count: Int, onClear: () -> Unit, onKeep: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (count == 1) "1 item has been on the Shelf for more than a week" else "$count items have been on the Shelf for more than a week",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(if (count == 1) "Clear it if you don't need it any more. Pinned items aren't counted." else "Clear them if you don't need them any more. Pinned items aren't counted.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClear) { Text(if (count == 1) "Clear it" else "Clear them") }
                OutlinedButton(onClick = onKeep) { Text("Keep") }
            }
        }
    }
}

@Composable
private fun ShelfRow(entry: ShelfEntry) {
    val item = entry.item
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var busy by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var copied by remember(item.id) { mutableStateOf(false) }

    /** The file on this device, fetching it if needed; shows an error if no device that has it is around. */
    fun withFile(action: (File) -> Unit) {
        busy = true; error = null
        scope.launch {
            val file = withContext(Dispatchers.IO) { Engine.node.shelfFile(item, Engine.onlineLinked()) }
            busy = false
            if (file == null) error = "Couldn't get it. Make sure ${entry.originName} is on and on the same Wi-Fi."
            else runCatching { withContext(Dispatchers.IO) { action(file) } }.onFailure { error = it.message ?: "Something went wrong." }
        }
    }

    PanelCard {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Leading(entry)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (item.kind) {
                    ShelfItem.Kind.TEXT -> SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 12, overflow = TextOverflow.Ellipsis) }
                    ShelfItem.Kind.LINK -> Text(item.text, style = MaterialTheme.typography.bodyLarge, color = cs.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    ShelfItem.Kind.FILE -> Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val size = if (item.isFile) " · ${formatBytes(item.size)}" + if (!entry.here) " · on ${entry.originName}" else "" else ""
                Text("${entry.originName} · ${timeAgo(item.addedAt)}$size", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (item.kind) {
                        ShelfItem.Kind.TEXT -> ActionButton(Icons.Default.ContentCopy, if (copied) "Copied" else "Copy") { copy(item.text); copied = true }
                        ShelfItem.Kind.LINK -> {
                            ActionButton(Icons.AutoMirrored.Filled.OpenInNew, "Open") { runCatching { uri.openUri(webAddress(item.text)) }.onFailure { error = "Couldn't open that link." } }
                            ActionButton(Icons.Default.ContentCopy, if (copied) "Copied" else "Copy") { copy(item.text); copied = true }
                        }
                        ShelfItem.Kind.FILE -> {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp).padding(start = 8.dp), strokeWidth = 2.dp)
                                Text(if (entry.here) "Opening…" else "Getting it from ${entry.originName}…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            } else {
                                ActionButton(if (entry.here) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.Download, "Open") {
                                    withFile { f -> if (!DesktopSystem.openFolder(namedCopy(f, item).path)) throw Exception("No app on this computer opens that kind of file.") }
                                }
                                ActionButton(Icons.Default.SaveAlt, "Save as…") {
                                    withFile { f -> saveAs(item.text)?.let { target -> f.copyTo(target, overwrite = true) } }
                                }
                            }
                        }
                    }
                }
            }
            Row {
                IconButton(onClick = { scope.launch(Dispatchers.IO) { Engine.node.shelfPin(item.id, !item.pinned); Engine.shareShelf() } }) {
                    Icon(if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (item.pinned) "Unpin" else "Pin", tint = if (item.pinned) cs.primary else cs.onSurfaceVariant)
                }
                IconButton(onClick = { scope.launch(Dispatchers.IO) { Engine.node.shelfRemove(listOf(item.id)); Engine.shareShelf() } }) {
                    Icon(Icons.Default.Close, "Remove from the Shelf", tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Leading(entry: ShelfEntry) {
    val item = entry.item
    val thumb by produceState<ImageBitmap?>(null, item.hash, entry.here) {
        value = if (item.isFile && entry.here && isPicture(item.text)) withContext(Dispatchers.IO) {
            Engine.node.shelf.file(item.hash)?.let { f -> runCatching { Images.readUpright(f, 160)?.toComposeImageBitmap() }.getOrNull() }
        } else null
    }
    // Same width for thumbnails and icons, so every row's text lines up.
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        val t = thumb
        if (t != null) {
            Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            val icon = when (item.kind) {
                ShelfItem.Kind.TEXT -> Icons.AutoMirrored.Filled.Notes
                ShelfItem.Kind.LINK -> Icons.Default.Link
                ShelfItem.Kind.FILE -> if (isPicture(item.text)) Icons.Default.Image else Icons.Default.InsertDriveFile
            }
            IconBadge(icon, container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text(label)
    }
}

private fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

private fun copy(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }

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
