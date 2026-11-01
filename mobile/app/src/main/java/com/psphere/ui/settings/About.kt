package com.psphere.ui.settings

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psphere.BuildConfig
import com.psphere.ui.components.SectionLabel

/** Where the About links go. The app itself never goes online; these open in the browser. */
private object Links {
    const val REPO = "https://github.com/Madhuhas/P-Sphere"
    const val SITE = "https://psphere.pages.dev"
    const val DOCS = "$SITE/docs"
    const val LICENSE = "$SITE/license"
    const val TERMS = "$SITE/terms"
    const val SECURITY = "$REPO/security/advisories/new"
    const val KADHAI = "https://kadhai.pages.dev"

    /** The bug report form, with the Devices field already filled in for this phone. */
    fun reportProblem(): String {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, PSphere ${BuildConfig.VERSION_NAME}"
        return "$REPO/issues/new?template=bug_report.yml&devices=${Uri.encode(device)}"
    }
}

/** "Help and feedback" and "Legal" cards, for the end of Settings. */
@Composable
fun AboutLinks() {
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    SectionLabel("Help and feedback")
    SettingsCard {
        LinkRow(Icons.Default.BugReport, "Report a problem", "Opens a bug report on GitHub") { uri.openUri(Links.reportProblem()) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        LinkRow(Icons.AutoMirrored.Filled.MenuBook, "Help and docs", "How linking and backups work") { uri.openUri(Links.DOCS) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        LinkRow(Icons.Default.Security, "Report a security issue", "Privately, only the maintainer sees it") { uri.openUri(Links.SECURITY) }
    }

    SectionLabel("Legal")
    SettingsCard {
        LinkRow(Icons.Default.Gavel, "Licence", "GNU AGPL-3.0, free and open source") { uri.openUri(Links.LICENSE) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        LinkRow(Icons.Default.Description, "Terms of use", "psphere.pages.dev/terms") { uri.openUri(Links.TERMS) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        LinkRow(Icons.Default.Code, "Source code", "github.com/Madhuhas/P-Sphere") { uri.openUri(Links.REPO) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        LinkRow(Icons.Default.Language, "Website", "psphere.pages.dev") { uri.openUri(Links.SITE) }
    }
}

/** Copyright line and the Built with Kadhai badge, at the very bottom of Settings. */
@Composable
fun AboutFooter() {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "PSphere ${BuildConfig.VERSION_NAME} · © 2026 Kotini Sai Madhuhas",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BuiltWithKadhai { uri.openUri(Links.KADHAI) }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

// The badge from kadhai.pages.dev/badges, drawn here so it needs no image file and no internet.
private val steam = PathParser().parsePathString("M23 25c-3-3 3-6 0-9s3-6 0-9M32 25c-3-3 3-6 0-9s3-6 0-9M41 25c-3-3 3-6 0-9s3-6 0-9").toPath()
private val pot = PathParser().parsePathString("M9 31 18 52h28l9-21").toPath()

@Composable
private fun BuiltWithKadhai(onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val back = if (dark) Color(0xFF141414) else Color.White
    val left = if (dark) Color(0xFF1F1F1F) else Color(0xFFECECEC)
    val edge = if (dark) Color(0xFF3A3A3A) else Color(0xFF999999)
    val soft = if (dark) Color(0xFF9D9D9D) else Color(0xFF5F5F5F)
    val strong = if (dark) Color(0xFFF2F2F2) else Color(0xFF111111)
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.height(28.dp).clip(shape).background(back).border(1.dp, edge, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Built with Kadhai" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.fillMaxHeight().background(left).padding(start = 5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) {
                scale(size.width / 64f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    drawRoundRect(Color(0xFF141414), size = Size(64f, 64f), cornerRadius = CornerRadius(14f))
                    drawPath(steam, Color(0xFF8A8A8A), style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(pot, Color(0xFFF2F2F2), style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Text("built with", Modifier.padding(start = 7.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = soft)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(edge))
        Text("kadhai", Modifier.padding(horizontal = 9.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = strong)
    }
}
