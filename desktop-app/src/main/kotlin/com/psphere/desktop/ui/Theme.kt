package com.psphere.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Every role is set explicitly. The old theme set only four colours, so cards, chips, the
// navigation highlight and containers fell back to Material's default purple palette.
// Mono: black, white and greys, no brand hue. Green and amber are kept for status only.
// surfaceTint is transparent so raised surfaces stay neutral grey instead of picking up a tint.

private val LightColors = lightColorScheme(
    primary                 = Color(0xFF141518),
    onPrimary               = Color(0xFFFFFFFF),
    primaryContainer        = Color(0xFFE4E5E9),
    onPrimaryContainer      = Color(0xFF141518),
    inversePrimary          = Color(0xFFECEDEF),
    secondary               = Color(0xFF5D6170),
    onSecondary             = Color(0xFFFFFFFF),
    secondaryContainer      = Color(0xFFE4E5E9),
    onSecondaryContainer    = Color(0xFF141518),
    tertiary                = Color(0xFF3D4047),
    onTertiary              = Color(0xFFFFFFFF),
    tertiaryContainer       = Color(0xFFE4E5E9),
    onTertiaryContainer     = Color(0xFF141518),
    background              = Color(0xFFF7F8FA),
    onBackground            = Color(0xFF141518),
    surface                 = Color(0xFFF7F8FA),
    onSurface               = Color(0xFF141518),
    surfaceVariant          = Color(0xFFE4E5E9),
    onSurfaceVariant        = Color(0xFF5D6170),
    surfaceTint             = Color(0x00000000),
    inverseSurface          = Color(0xFF141518),
    inverseOnSurface        = Color(0xFFECEDEF),
    error                   = Color(0xFFBA1A1A),
    onError                 = Color(0xFFFFFFFF),
    errorContainer          = Color(0xFFFFDAD6),
    onErrorContainer        = Color(0xFF410002),
    outline                 = Color(0xFF8A8E98),
    outlineVariant          = Color(0xFFDDDEE2),
    scrim                   = Color(0xFF000000),
    surfaceBright           = Color(0xFFF7F8FA),
    surfaceDim              = Color(0xFFDCDDE1),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFFFFFFF),
    surfaceContainer        = Color(0xFFF0F1F3),
    surfaceContainerHigh    = Color(0xFFEAEBEE),
    surfaceContainerHighest = Color(0xFFE4E5E9),
)

private val DarkColors = darkColorScheme(
    primary                 = Color(0xFFECEDEF),
    onPrimary               = Color(0xFF0C0D10),
    primaryContainer        = Color(0xFF2A2C32),
    onPrimaryContainer      = Color(0xFFECEDEF),
    inversePrimary          = Color(0xFF141518),
    secondary               = Color(0xFF9A9FA9),
    onSecondary             = Color(0xFF0C0D10),
    secondaryContainer      = Color(0xFF26282E),
    onSecondaryContainer    = Color(0xFFECEDEF),
    tertiary                = Color(0xFFC4C6CC),
    onTertiary              = Color(0xFF0C0D10),
    tertiaryContainer       = Color(0xFF26282E),
    onTertiaryContainer     = Color(0xFFECEDEF),
    background              = Color(0xFF0C0D10),
    onBackground            = Color(0xFFECEDEF),
    surface                 = Color(0xFF0C0D10),
    onSurface               = Color(0xFFECEDEF),
    surfaceVariant          = Color(0xFF26282E),
    onSurfaceVariant        = Color(0xFF9A9FA9),
    surfaceTint             = Color(0x00000000),
    inverseSurface          = Color(0xFFECEDEF),
    inverseOnSurface        = Color(0xFF141518),
    error                   = Color(0xFFFFB4AB),
    onError                 = Color(0xFF690005),
    errorContainer          = Color(0xFF93000A),
    onErrorContainer        = Color(0xFFFFDAD6),
    outline                 = Color(0xFF6B6F78),
    outlineVariant          = Color(0xFF26282E),
    scrim                   = Color(0xFF000000),
    surfaceBright           = Color(0xFF2A2C32),
    surfaceDim              = Color(0xFF0C0D10),
    surfaceContainerLowest  = Color(0xFF08090B),
    surfaceContainerLow     = Color(0xFF15171C),
    surfaceContainer        = Color(0xFF1A1C21),
    surfaceContainerHigh    = Color(0xFF1F2127),
    surfaceContainerHighest = Color(0xFF26282E),
)

/** Colours Material 3 has no role for. Read with `PSphereTheme.status`. */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF1B6D2F), onSuccess = Color.White,
    successContainer = Color(0xFFD3F5D6), onSuccessContainer = Color(0xFF002109),
    warning = Color(0xFF8A5100), warningContainer = Color(0xFFFFDDB8), onWarningContainer = Color(0xFF2C1600),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF8BD89A), onSuccess = Color(0xFF003914),
    successContainer = Color(0xFF0B5321), onSuccessContainer = Color(0xFFA6F4B4),
    warning = Color(0xFFFFB866), warningContainer = Color(0xFF693C00), onWarningContainer = Color(0xFFFFDDB8),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object PSphereTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

@Composable
fun PSphereTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes      = AppShapes,
            content     = content,
        )
    }
}
