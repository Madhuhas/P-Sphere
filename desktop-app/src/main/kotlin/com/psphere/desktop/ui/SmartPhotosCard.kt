package com.psphere.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psphere.desktop.smart.Smart
import com.psphere.desktop.smart.SmartModels
import com.psphere.desktop.smart.SmartState

/** Settings → Smart photos: an optional download, then a quiet background scan. */
@Composable
fun SmartPhotosCard() {
    val state by Smart.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove smart photos?") },
            text = { Text("This deletes the models (${formatBytes(SmartModels.totalBytes)}) and what they learned about your photos, including the names you gave people. Your photos stay as they are. You can download it again any time.") },
            confirmButton = { Button(onClick = { Smart.remove(); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    PanelCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = cs.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Smart photos", style = MaterialTheme.typography.bodyLarge)
                when (val s = state) {
                    is SmartState.Off -> {
                        Sub("Search by describing a photo (“dog on the beach”), see photos by the people in them and where they were taken, and find words written in photos (“invoice”). Your phone and tablet can use it too, while this computer is on.")
                        Sub("It's an optional download. This computer looks through your photos once, which keeps it busy for a while; new photos are quick. Everything stays on your devices.")
                        Button(onClick = { Smart.download() }, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Download (${formatBytes(SmartModels.totalBytes)})")
                        }
                    }
                    is SmartState.Downloading -> {
                        Sub("Downloading · ${formatBytes(s.done)} of ${formatBytes(s.total)}")
                        LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { Smart.cancelDownload() }) { Text("Cancel") }
                    }
                    is SmartState.Scanning -> {
                        Sub(
                            if (s.paused) "Paused · ${"%,d".format(s.done)} of ${plural(s.total, "photo")} done. Everything works for those already."
                            else "Looking through your photos · ${"%,d".format(s.done)} of ${"%,d".format(s.total)}. Everything already works for the ones done.",
                        )
                        LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { Smart.setPaused(!s.paused) }) { Text(if (s.paused) "Resume" else "Pause") }
                            TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                        }
                    }
                    is SmartState.Ready -> {
                        Sub("On · ${plural(s.photos, "photo")}, ${plural(s.people, "person").replace("persons", "people")}, ${plural(s.places, "place")}. Use the search box in Photos, here or on your other devices.")
                        Text("Place names from GeoNames.org (CC BY 4.0).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                    }
                    is SmartState.Failed -> {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Smart.download() }) { Text("Try again") }
                            TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sub(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
