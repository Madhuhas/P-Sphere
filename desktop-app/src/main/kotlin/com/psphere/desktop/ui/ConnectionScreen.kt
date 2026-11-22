package com.psphere.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psphere.desktop.engine.ConnectionCheck
import com.psphere.desktop.engine.Engine

/** Settings › Check connection: why devices can't reach this computer, and the fix. */
@Composable
fun ConnectionScreen(onBack: () -> Unit) {
    val result by Engine.connection.collectAsState()
    val checking by Engine.checkingConnection.collectAsState()
    LaunchedEffect(Unit) { Engine.checkConnection() }

    Column {
        ScreenTitle("Check connection", onBack = onBack)
        ScreenBody(spacing = 12.dp) {
            val r = result
            if (r == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Checking the network, Windows Firewall and your devices…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@ScreenBody
            }
            VerdictCard(r.verdict, checking)

            SectionLabel("Checks")
            PanelCard {
                r.steps.forEachIndexed { i, step ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    StepRow(step)
                }
            }
            Text(
                "Checked ${timeAgo(r.at)}. PSphere only connects to your linked devices, and everything is encrypted, also on public networks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The answer and its fix buttons; also used as the warning card on the Devices screen. */
@Composable
internal fun VerdictCard(v: ConnectionCheck.Verdict, checking: Boolean, onOpenDetails: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val accent = if (v.ok) PSphereTheme.status.success else PSphereTheme.status.warning
    var applying by remember { mutableStateOf<ConnectionCheck.Fix?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        border = if (v.ok) null else BorderStroke(1.dp, accent.copy(alpha = .6f)),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (v.ok) Icons.Default.CheckCircle else Icons.Default.WifiFind, container = accent.copy(alpha = .12f), content = accent, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleMedium)
                Text(v.detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    v.fixes.forEachIndexed { i, fix ->
                        val busy = applying == fix
                        val onClick = {
                            applying = fix; note = null
                            Engine.applyFix(fix) { r ->
                                applying = null
                                note = r.error
                            }
                        }
                        if (i == 0) Button(onClick = onClick, enabled = applying == null) { Text(if (busy) "Waiting for Windows…" else fix.label) }
                        else OutlinedButton(onClick = onClick, enabled = applying == null) { Text(if (busy) "Waiting for Windows…" else fix.label) }
                    }
                    if (onOpenDetails != null) {
                        OutlinedButton(onClick = onOpenDetails) { Text("Details") }
                    } else {
                        OutlinedButton(onClick = { Engine.checkConnection() }, enabled = !checking && applying == null) {
                            if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (checking) "Checking…" else "Check again")
                        }
                    }
                }
                if (v.fixes.isNotEmpty()) {
                    Text("Windows asks for permission once, because these change system settings.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PSphereTheme.status.warning) }
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
