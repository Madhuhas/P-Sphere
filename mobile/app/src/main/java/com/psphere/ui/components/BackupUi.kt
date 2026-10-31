package com.psphere.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psphere.backup.Activity
import com.psphere.backup.AndroidPlatform
import com.psphere.backup.Engine
import com.psphere.core.node.SyncProgress
import com.psphere.ui.theme.PSphereTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes [compute] off the main thread and recomputes it whenever the engine reports a change.
 * Catalog queries can touch thousands of items, so screens never run them on the UI thread.
 */
@Composable
fun <T> rememberEngineData(initial: T, compute: () -> T): State<T> {
    val state by Engine.state.collectAsState()
    return produceState(initial, state.revision, state.nearby.keys) {
        value = withContext(Dispatchers.IO) { compute() }
    }
}

/** Opens the system folder picker and keeps access to the chosen folder. */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { AndroidPlatform.persistAccess(context, uri) }
            onPicked(uri)
        }
    }
    return { launcher.launch(null) }
}

fun describeActivity(a: Activity): String? = when (a) {
    Activity.Idle -> null
    is Activity.Scanning -> if (a.total > 0) "Checking your folders · ${a.done} of ${a.total}" else "Checking your folders…"
    is Activity.Syncing -> {
        val p = a.progress
        when (p?.stage) {
            null, SyncProgress.Stage.CATALOG -> "Connecting to ${a.deviceName}…"
            SyncProgress.Stage.PREVIEWS -> "Updating previews from ${a.deviceName}"
            SyncProgress.Stage.SENDING -> "Sending to ${a.deviceName} · ${p.done} of ${p.total}"
            SyncProgress.Stage.RECEIVING -> "Receiving from ${a.deviceName} · ${p.done} of ${p.total}"
        }
    }
}

fun activityFraction(a: Activity): Float? = when (a) {
    is Activity.Scanning -> if (a.total > 0) a.done.toFloat() / a.total else null
    is Activity.Syncing -> a.progress?.takeIf { it.total > 0 && it.stage != SyncProgress.Stage.CATALOG }?.let { it.done.toFloat() / it.total }
    else -> null
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

/** A card listing problems or things the user should know. */
@Composable
fun NotesCard(notes: List<String>, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    val warn = PSphereTheme.status
    Card(
        modifier = modifier.fillMaxWidth(),
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
                Text("Check that ${r.otherDevice} shows the same code.")
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

fun folderLabelFromUri(uri: Uri): String =
    com.psphere.backup.SafFolder.describe(uri).substringAfterLast('/').ifBlank { "Folder" }
