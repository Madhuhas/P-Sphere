package com.psphere.ui.sending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.psphere.backup.Engine
import com.psphere.core.node.FreeUpPlan
import com.psphere.core.node.FreeUpResult
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Stage {
    object Loading : Stage
    data class Plan(val plan: FreeUpPlan, val holders: List<String>) : Stage
    object Working : Stage
    data class Done(val result: FreeUpResult) : Stage
}

/**
 * Free up space: deletes files from this device once they're safely backed up elsewhere, keeping a
 * preview so they still show in Photos. Always manual, and online destinations are re-checked first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeUpScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Loading) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val plan = withContext(Dispatchers.IO) { Engine.node.planFreeUp() }
        val holders = withContext(Dispatchers.IO) {
            (plan.items.flatMap { Engine.node.catalog.holders(it.hash) }.toSet() - Engine.node.id)
                .map { Engine.node.nameOf(it) }.sorted()
        }
        stage = Stage.Plan(plan, holders)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free up space") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when (val s = stage) {
                is Stage.Loading -> CircularProgressIndicator()
                is Stage.Plan -> if (s.plan.items.isEmpty()) EmptyFreeUp() else PlanView(s) { confirm = true }
                is Stage.Working -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Freeing up space…")
                }
                is Stage.Done -> DoneView(s.result, onBack)
            }
        }
    }

    val current = stage
    if (confirm && current is Stage.Plan) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Filled.DeleteSweep, null) },
            title = { Text("Delete ${current.plan.items.size} files from this device?") },
            text = { Text("They stay backed up on your other devices, and their previews stay in Photos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    stage = Stage.Working
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { Engine.node.freeUp(current.plan, Engine.onlineLinked()) }
                        Engine.bump()
                        stage = Stage.Done(result)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlanView(s: Stage.Plan, onFree: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "${plural(s.plan.items.size, "file")} · ${formatBytes(s.plan.bytes)}",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            if (s.holders.isEmpty()) "ready to free up from ${Engine.node.deviceName}"
            else "safely backed up on ${s.holders.joinToString(", ")}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            "They'll be removed from this device only. The copies on your other devices stay, and a small preview stays in Photos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Button(onClick = onFree, modifier = Modifier.fillMaxWidth()) {
            Text("Free up ${formatBytes(s.plan.bytes)}")
        }
    }
}

@Composable
private fun DoneView(result: FreeUpResult, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "Freed ${formatBytes(result.bytes)} · ${plural(result.freed, "file")}",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (result.notRechecked.isNotEmpty()) {
            Text(
                "Couldn't re-check ${result.notRechecked.joinToString(", ") { Engine.node.nameOf(it) }}, so their last confirmation was used.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (result.skipped.isNotEmpty()) {
            Text(
                "${result.skipped.size} skipped: " +
                    result.skipped.groupingBy { it.second }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
    }
}

@Composable
private fun EmptyFreeUp() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nothing to free up", style = MaterialTheme.typography.titleMedium)
        Text(
            "Files appear here once they're safely backed up on another device. Nothing on this device is being wasted right now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
