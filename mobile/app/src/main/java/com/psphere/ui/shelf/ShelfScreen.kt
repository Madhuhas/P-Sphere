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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.psphere.backup.Engine
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.ShelfItem
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.EmptyState
import com.psphere.ui.components.IconBadge
import com.psphere.ui.components.NotesCard
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import com.psphere.ui.components.timeAgo
import kotlinx.coroutines.Dispatchers
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(ShelfData()) {
        val node = Engine.node
        ShelfData(node.shelfItems(), node.shelfOld().size, node.shelfShouldAskAboutOld(), node.devices().isNotEmpty(), loaded = true)
    }
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var savePending by remember { mutableStateOf<ShelfItem?>(null) }

    fun addText(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) { Engine.node.shelfAddText(text); Engine.shareShelf() }
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                runCatching { context.contentResolver.openInputStream(uri)!!.use { Engine.node.shelfAddFile(displayName(context, uri), it) } }
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
            Toast.makeText(context, if (ok) "Saved" else "Couldn't save it", Toast.LENGTH_SHORT).show()
        }
    }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        when {
            clip?.uri != null -> addUris(listOf(clip.uri))
            !clip?.coerceToText(context).isNullOrBlank() -> addText(clip!!.coerceToText(context).toString())
            else -> Toast.makeText(context, "There's nothing to paste", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shelf") },
                actions = {
                    IconButton(onClick = ::paste) { Icon(Icons.Default.ContentPaste, "Paste") }
                    IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, "Add files") }
                    if (data.items.any { !it.item.pinned }) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Clear") }
                },
            )
        },
    ) { padding ->
        ContentColumn(Modifier.padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = { Text("Type or paste text or a link") },
                                maxLines = 5,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { addText(draft); draft = "" }, enabled = draft.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Put on the Shelf")
                            }
                        }
                        Text(
                            "Or share from any app and pick PSphere Shelf.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (data.loaded && !data.hasLinked) item { NotesCard(listOf("Link a device first. What you put here shows up on your linked devices.")) }
                if (data.askAboutOld) item {
                    OldItemsCard(
                        count = data.oldCount,
                        onClear = { scope.launch(Dispatchers.IO) { Engine.node.shelfRemove(Engine.node.shelfOld().map { it.id }); Engine.shareShelf() } },
                        onKeep = { scope.launch(Dispatchers.IO) { Engine.node.shelfKeepOld() } },
                    )
                }
                if (data.loaded && data.items.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                        EmptyState(Icons.Default.Inventory2, "Nothing on the Shelf yet", "Anything you put here appears on your linked devices, ready to copy, open or save.")
                    }
                }
                items(data.items, key = { it.item.id }) { entry ->
                    ShelfCard(entry, onSave = { savePending = entry.item; saveTo.launch(entry.item.text) })
                }
            }
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (count == 1) "1 item has been on the Shelf for more than a week" else "$count items have been on the Shelf for more than a week",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (count == 1) "Clear it if you don't need it any more. Pinned items aren't counted." else "Clear them if you don't need them any more. Pinned items aren't counted.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClear) { Text(if (count == 1) "Clear it" else "Clear them") }
                OutlinedButton(onClick = onKeep) { Text("Keep") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfCard(entry: ShelfEntry, onSave: () -> Unit) {
    val item = entry.item
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var busy by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    /** The file on this phone, fetched first if needed. */
    fun withFile(action: (File) -> Unit) {
        busy = true; error = null
        scope.launch {
            val file = withContext(Dispatchers.IO) { Engine.node.shelfFile(item, Engine.onlineLinked()) }
            busy = false
            if (file == null) error = "Couldn't get it. Make sure ${entry.originName} is on and on the same Wi-Fi."
            else runCatching { action(file) }.onFailure { error = it.message ?: "Something went wrong." }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Leading(entry)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (item.kind) {
                    ShelfItem.Kind.TEXT -> Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 10, overflow = TextOverflow.Ellipsis)
                    ShelfItem.Kind.LINK -> Text(item.text, style = MaterialTheme.typography.bodyLarge, color = cs.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    ShelfItem.Kind.FILE -> Text(item.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val size = if (item.isFile) " · ${formatBytes(item.size)}" + if (!entry.here) " · on ${entry.originName}" else "" else ""
                Text("${entry.originName} · ${timeAgo(item.addedAt)}$size", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                FlowRow(verticalArrangement = Arrangement.Center) {
                    when (item.kind) {
                        ShelfItem.Kind.TEXT -> {
                            Action(Icons.Default.ContentCopy, "Copy") { copy(context, item.text) }
                            Action(Icons.Default.Share, "Share") { shareText(context, item.text) }
                        }
                        ShelfItem.Kind.LINK -> {
                            Action(Icons.AutoMirrored.Filled.OpenInNew, "Open") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webAddress(item.text)))) }
                                    .onFailure { error = "No app on this phone opens links like that." }
                            }
                            Action(Icons.Default.ContentCopy, "Copy") { copy(context, item.text) }
                        }
                        ShelfItem.Kind.FILE -> if (busy) {
                            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(if (entry.here) "Opening…" else "Getting it from ${entry.originName}…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        } else {
                            Action(if (entry.here) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.Download, "Open") { withFile { openFile(context, it, item) } }
                            Action(Icons.Default.SaveAlt, "Save") { onSave() }
                            Action(Icons.Default.Share, "Share") { withFile { shareFile(context, it, item) } }
                        }
                    }
                }
            }
            Column {
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
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (item.isFile && entry.here && isPicture(item.text)) {
            AsyncImage(
                model = Engine.node.shelf.file(item.hash),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
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
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Compact, so Open, Save and Share fit on one line on a phone.
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text(label)
    }
}

private fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif")

private fun webAddress(text: String) = text.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PSphere", text))
    // Android 13 and later show their own "Copied" message.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

private fun mimeOf(name: String) =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** Shelf files are stored by content, without a name; other apps need the real name to open them. */
private fun shareableUri(context: Context, file: File, item: ShelfItem): Uri {
    val dir = File(context.cacheDir, "shelf-open/${item.hash.take(12)}").apply { mkdirs() }
    val named = File(dir, item.text)
    if (named.length() != file.length()) file.copyTo(named, overwrite = true)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named)
}

private fun openFile(context: Context, file: File, item: ShelfItem) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(shareableUri(context, file, item), mimeOf(item.text))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(view) } catch (e: ActivityNotFoundException) { throw Exception("No app on this phone opens that kind of file.") }
}

private fun shareFile(context: Context, file: File, item: ShelfItem) {
    val send = Intent(Intent.ACTION_SEND).setType(mimeOf(item.text))
        .putExtra(Intent.EXTRA_STREAM, shareableUri(context, file, item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
