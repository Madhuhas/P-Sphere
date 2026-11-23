package com.psphere.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.psphere.backup.ConnectionCheck
import com.psphere.backup.Engine
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.IconBadge
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.components.timeAgo
import com.psphere.ui.theme.PSphereTheme

/** Settings › Check connection: why linked devices can't reach this device, in plain words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onBack: () -> Unit) {
    val result by Engine.connection.collectAsState()
    val checking by Engine.checkingConnection.collectAsState()
    LaunchedEffect(Unit) { Engine.checkConnection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check connection") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = result
                if (r == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Checking the Wi-Fi and your devices…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@Column
                }
                VerdictCard(r.verdict, checking)

                SectionLabel("Checks")
                SettingsCard {
                    r.steps.forEachIndexed { i, step ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        StepRow(step)
                    }
                }
                Text(
                    "Checked ${timeAgo(r.at)}. PSphere only connects to your linked devices, and everything is encrypted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerdictCard(v: ConnectionCheck.Verdict, checking: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val accent = if (v.ok) PSphereTheme.status.success else PSphereTheme.status.warning
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        border = if (v.ok) null else BorderStroke(1.dp, accent.copy(alpha = .6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (v.ok) Icons.Default.CheckCircle else Icons.Default.WifiFind, container = accent.copy(alpha = .12f), content = accent, size = 40.dp)
                Text(v.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(v.detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                v.fixes.forEach { fix -> Button(onClick = { ConnectionCheck.open(context, fix) }) { Text(fix.label) } }
                OutlinedButton(onClick = { Engine.checkConnection() }, enabled = !checking) {
                    if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (checking) "Checking…" else "Check again")
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: ConnectionCheck.Step) {
    val (icon, tint) = when (step.state) {
        ConnectionCheck.State.OK -> Icons.Default.CheckCircle to PSphereTheme.status.success
        ConnectionCheck.State.WARN -> Icons.Default.WarningAmber to PSphereTheme.status.warning
        ConnectionCheck.State.FAIL -> Icons.Default.ErrorOutline to PSphereTheme.status.warning
        ConnectionCheck.State.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyLarge)
            Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
