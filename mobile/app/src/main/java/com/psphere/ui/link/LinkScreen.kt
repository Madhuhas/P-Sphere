package com.psphere.ui.link

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.psphere.backup.Engine
import com.psphere.backup.NearbyDevice
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.IconBadge
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.theme.PSphereTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by Engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkedName by remember { mutableStateOf<String?>(null) }
    val initialIds = remember { Engine.node.devices().map { it.id }.toSet() }

    // Show this device as linkable only while this screen is open.
    DisposableEffect(Unit) {
        Engine.openLink()
        onDispose { Engine.closeLink() }
    }
    // Another device scanned our code: say so.
    LaunchedEffect(state.revision) {
        Engine.node.devices().firstOrNull { it.id !in initialIds }?.let { linkedName = it.name }
    }

    fun run(block: suspend () -> String) {
        busy = true; error = null
        scope.launch {
            try { linkedName = block() } catch (e: Exception) { error = e.message ?: "Couldn't link" } finally { busy = false }
        }
    }

    val scan = {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { raw -> run { Engine.linkWithQr(raw).name } } }
            .addOnFailureListener { error = "The scanner isn't available on this device. Use “Link without a camera” below." }
        Unit
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Link a device") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                linkedName?.let { name -> LinkedBanner(name, onDone = onBack) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Linking…", style = MaterialTheme.typography.bodyMedium)
                }

                SectionLabel("Scan the other device's code")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "On your other device, open PSphere › Link a device. A code appears there.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan a code")
                        }
                    }
                }

                SectionLabel("Or show this device's code")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val offer = state.linkOffer
                        if (offer != null) QrCode(offer.toUri())
                        Text(
                            "Scan this with PSphere on your other device. It works for 10 minutes, once.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }

                SectionLabel("Link without a camera")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    val nearby = state.nearby.values.filter { Engine.node.device(it.id) == null }.sortedBy { it.name.lowercase() }
                    if (nearby.isEmpty()) {
                        Text(
                            "Open “Link a device” on the other device too. It appears here when it's on this Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    nearby.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        NearbyRow(d, enabled = !busy) { run { Engine.linkWithCode(d).name } }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
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
                Text("Choose folders for it in Sending.", style = MaterialTheme.typography.bodyMedium)
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
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    // Always black on white, also in dark mode, so every scanner can read it.
    Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Image(bitmap, contentDescription = "Link code", filterQuality = FilterQuality.None, modifier = Modifier.size(240.dp))
    }
}
