package com.psphere.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psphere.core.node.SyncProgress
import com.psphere.desktop.engine.Activity
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.WindowsSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Computes [compute] off the UI thread and recomputes it whenever the engine reports a change.
 * Catalog queries can touch thousands of items, so screens never run them on the UI thread.
 */
@Composable
fun <T> rememberEngineData(initial: T, compute: () -> T): State<T> {
    val state by Engine.state.collectAsState()
    return produceState(initial, state.revision, state.nearby.keys) {
        value = withContext(Dispatchers.IO) { compute() }
    }
}

/**
 * Asks for a folder with Windows' folder picker. Blocks until the user chooses, like any
 * Windows dialog. Returns the chosen folder's path, or null if they cancelled.
 */
fun pickFolder(title: String, startAt: String? = null): String? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser(startAt?.let { File(it) }?.takeIf { it.isDirectory }).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showDialog(null, "Select folder") == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

fun describeActivity(a: Activity): String? = when (a) {
    Activity.Idle -> null
    is Activity.Scanning -> if (a.total > 0) "Checking your folders · ${a.done} of ${a.total}" else "Checking your folders…"
    is Activity.Syncing -> syncText(a.deviceName, a.progress)
    is Activity.Serving -> syncText(a.deviceName, a.progress)
}

private fun syncText(device: String, p: SyncProgress?): String = when (p?.stage) {
    null, SyncProgress.Stage.CATALOG -> "Connecting to $device…"
    SyncProgress.Stage.PREVIEWS -> "Updating previews from $device"
    SyncProgress.Stage.SENDING -> "Sending to $device · ${p.done} of ${p.total}"
    SyncProgress.Stage.RECEIVING -> "Receiving from $device · ${p.done} of ${p.total}"
}

fun activityFraction(a: Activity): Float? {
    val p = when (a) {
        is Activity.Scanning -> return if (a.total > 0) a.done.toFloat() / a.total else null
        is Activity.Syncing -> a.progress
        is Activity.Serving -> a.progress
        Activity.Idle -> null
    }
    return p?.takeIf { it.total > 0 && it.stage != SyncProgress.Stage.CATALOG }?.let { it.done.toFloat() / it.total }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun timeAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ms).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        d < 2 * 86_400 -> "yesterday"
        else -> "${d / 86_400} days ago"
    }
}

fun plural(n: Int, word: String) = "%,d $word${if (n != 1) "s" else ""}".format(n)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** A screen's title row, with a back arrow when [onBack] is set. */
@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = if (onBack != null) 12.dp else 28.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp))
        actions()
    }
}

/** The scrolling body of a screen: a readable column on the left of wide windows. */
@Composable
fun ScreenBody(spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
}

/** Round tinted badge holding an icon, used for device and status rows. */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Full-screen message for empty or blocked states, with an optional action below. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(icon, container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant, size = 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** A card listing problems or things the user should know. */
@Composable
fun NotesCard(notes: List<String>) {
    if (notes.isEmpty()) return
    val warn = PSphereTheme.status
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = warn.warningContainer, contentColor = warn.onWarningContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            notes.forEach { n ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.WarningAmber, null, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                    Text(n, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** "Open folder": shows [path] in File Explorer. */
@Composable
fun OpenFolderButton(path: String) {
    TextButton(onClick = { WindowsSystem.openInExplorer(path) }) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Open folder")
    }
}

/** Shown on any screen while the other device waits for the user to compare the 6-digit code. */
@Composable
fun CodeDialog() {
    val request by Engine.codeRequest.collectAsState()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Link with ${r.otherDevice}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check that ${r.otherDevice} shows the same code, then press Codes match on both.")
                Text(
                    r.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        },
        confirmButton = { Button(onClick = { r.reply(true) }) { Text("Codes match") } },
        dismissButton = { TextButton(onClick = { r.reply(false) }) { Text("Cancel") } },
    )
}
