package com.psphere.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.NearbyDevice
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage

/** This laptop has no camera, so it always shows the code and the phone or tablet scans it. */
@Composable
fun LinkScreen(onBack: () -> Unit) {
    val state by Engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkedName by remember { mutableStateOf<String?>(null) }
    val initialIds = remember { Engine.node.devices().map { it.id }.toSet() }
    val cs = MaterialTheme.colorScheme

    // Show this laptop as linkable only while this screen is open.
    DisposableEffect(Unit) {
        Engine.openLink()
        onDispose { Engine.closeLink() }
    }
    // A phone scanned our code: say so.
    LaunchedEffect(state.revision) {
        Engine.node.devices().firstOrNull { it.id !in initialIds }?.let { linkedName = it.name }
    }

    fun linkByCode(d: NearbyDevice) {
        busy = true; error = null
        scope.launch {
            try { linkedName = Engine.linkWithCode(d).name } catch (e: Exception) { error = e.message ?: "Couldn't link" } finally { busy = false }
        }
    }

    Column {
        ScreenTitle("Link a device", onBack = onBack)
        ScreenBody {
            linkedName?.let { name -> LinkedBanner(name, onDone = onBack) }
            error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Linking…", style = MaterialTheme.typography.bodyMedium)
            }

            SectionLabel("Scan this code with your phone or tablet")
            PanelCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    val offer = state.linkOffer
                    if (offer != null) QrCode(offer.toUri())
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Step(1, "Open PSphere on your phone or tablet.")
                        Step(2, buildAnnotatedString { append("Go to "); bold("Devices › Link a device"); append(".") })
                        Step(3, buildAnnotatedString { append("Tap "); bold("Scan a code"); append(" and point it at this screen.") })
                        Text(
                            "The code works once, for 10 minutes. This laptop has no camera, so it always shows the code.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SectionLabel("Can't scan? Link with a 6-digit code")
            PanelCard {
                val nearby = state.nearby.values.filter { Engine.node.device(it.id) == null }.sortedBy { it.name.lowercase() }
                nearby.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = cs.outlineVariant)
                    NearbyRow(d, enabled = !busy) { linkByCode(d) }
                }
                if (nearby.isNotEmpty()) HorizontalDivider(color = cs.outlineVariant)
                Text(
                    "Devices appear here when they're on this Wi-Fi with “Link a device” open.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun AnnotatedString.Builder.bold(text: String) = withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text) }

@Composable
private fun Step(n: Int, text: String) = Step(n, AnnotatedString(text))

@Composable
private fun Step(n: Int, text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NearbyRow(d: NearbyDevice, enabled: Boolean, onLink: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(Icons.Default.Devices, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !d.compatible -> "Has an older version of PSphere. Update it first."
                    !d.linkable -> "Open “Link a device” on it"
                    else -> "Ready to link"
                },
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onLink, enabled = enabled && d.compatible && d.linkable) { Text("Link") }
    }
}

@Composable
private fun LinkedBanner(name: String, onDone: () -> Unit) {
    val ok = PSphereTheme.status
    Card(colors = CardDefaults.cardColors(containerColor = ok.successContainer, contentColor = ok.onSuccessContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.CheckCircle, null)
            Column(Modifier.weight(1f)) {
                Text("Linked with $name", style = MaterialTheme.typography.titleMedium)
                Text("On $name, choose folders to back up to ${Engine.node.deviceName} in Sending.", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun QrCode(text: String) {
    val bitmap = remember(text) {
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        img.toComposeImageBitmap()
    }
    // Always black on white, also in dark mode, so every scanner can read it.
    Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Image(bitmap, contentDescription = "Link code", filterQuality = FilterQuality.None, modifier = Modifier.size(220.dp))
    }
}
