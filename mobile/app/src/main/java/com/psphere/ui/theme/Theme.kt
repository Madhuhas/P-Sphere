package com.psphere.ui.theme

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

private val LightColors = lightColorScheme(
    primary                 = Color(0xFF1565C0),
    onPrimary               = Color(0xFFFFFFFF),
    primaryContainer        = Color(0xFFD6E3FF),
    onPrimaryContainer      = Color(0xFF001B3E),
    inversePrimary          = Color(0xFFA9C7FF),
    secondary               = Color(0xFF535F70),
    onSecondary             = Color(0xFFFFFFFF),
    secondaryContainer      = Color(0xFFD7E3F8),
    onSecondaryContainer    = Color(0xFF101C2B),
    tertiary                = Color(0xFF00696E),
    onTertiary              = Color(0xFFFFFFFF),
    tertiaryContainer       = Color(0xFFB4EEF2),
    onTertiaryContainer     = Color(0xFF002022),
    background              = Color(0xFFF8F9FE),
    onBackground            = Color(0xFF191C20),
    surface                 = Color(0xFFF8F9FE),
    onSurface               = Color(0xFF191C20),
    surfaceVariant          = Color(0xFFDFE2EB),
    onSurfaceVariant        = Color(0xFF43474E),
    surfaceTint             = Color(0xFF1565C0),
    inverseSurface          = Color(0xFF2E3036),
    inverseOnSurface        = Color(0xFFEFF0F7),
    error                   = Color(0xFFBA1A1A),
    onError                 = Color(0xFFFFFFFF),
    errorContainer          = Color(0xFFFFDAD6),
    onErrorContainer        = Color(0xFF410002),
    outline                 = Color(0xFF73777F),
    outlineVariant          = Color(0xFFC3C6CF),
    scrim                   = Color(0xFF000000),
    surfaceBright           = Color(0xFFF8F9FE),
    surfaceDim              = Color(0xFFD8DAE0),
    surfaceContainerLowest  = Color(0xFFFFFFFF),
    surfaceContainerLow     = Color(0xFFF2F3F9),
    surfaceContainer        = Color(0xFFECEEF4),
    surfaceContainerHigh    = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
)

private val DarkColors = darkColorScheme(
    primary                 = Color(0xFFA9C7FF),
    onPrimary               = Color(0xFF003063),
    primaryContainer        = Color(0xFF00468C),
    onPrimaryContainer      = Color(0xFFD6E3FF),
    inversePrimary          = Color(0xFF1565C0),
    secondary               = Color(0xFFBBC7DB),
    onSecondary             = Color(0xFF253140),
    secondaryContainer      = Color(0xFF3B4858),
    onSecondaryContainer    = Color(0xFFD7E3F8),
    tertiary                = Color(0xFF82D3D8),
    onTertiary              = Color(0xFF00373A),
    tertiaryContainer       = Color(0xFF004F53),
    onTertiaryContainer     = Color(0xFFB4EEF2),
    background              = Color(0xFF111318),
    onBackground            = Color(0xFFE1E2E8),
    surface                 = Color(0xFF111318),
    onSurface               = Color(0xFFE1E2E8),
    surfaceVariant          = Color(0xFF43474E),
    onSurfaceVariant        = Color(0xFFC3C6CF),
    surfaceTint             = Color(0xFFA9C7FF),
    inverseSurface          = Color(0xFFE1E2E8),
    inverseOnSurface        = Color(0xFF2E3036),
    error                   = Color(0xFFFFB4AB),
    onError                 = Color(0xFF690005),
    errorContainer          = Color(0xFF93000A),
    onErrorContainer        = Color(0xFFFFDAD6),
    outline                 = Color(0xFF8D9199),
    outlineVariant          = Color(0xFF43474E),
    scrim                   = Color(0xFF000000),
    surfaceBright           = Color(0xFF37393E),
    surfaceDim              = Color(0xFF111318),
    surfaceContainerLowest  = Color(0xFF0C0E13),
    surfaceContainerLow     = Color(0xFF191C20),
    surfaceContainer        = Color(0xFF1D2024),
    surfaceContainerHigh    = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
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
