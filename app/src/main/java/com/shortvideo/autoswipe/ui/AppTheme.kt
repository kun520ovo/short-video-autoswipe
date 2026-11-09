package com.shortvideo.autoswipe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppPalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val field: Color,
    val line: Color,
    val accent: Color,
    val error: Color,
)

private val LightPalette = AppPalette(
    Color.White, Color(0xFF24372F), Color(0xFF748179), Color(0xFFF3F5F2),
    Color(0xFFEDF0EC), Color(0xFF267D5B), Color(0xFF985A52),
)
private val DarkPalette = AppPalette(
    Color(0xFF161616), Color(0xFFF2F2F2), Color(0xFFB8B8B8), Color(0xFF262626),
    Color(0xFF373737), Color(0xFF80D6AB), Color(0xFFEBAAA0),
)
val LocalAppPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun AutoSwipeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        // Keep Material dialogs and switch surfaces neutral, like the custom form fields.
        primary = palette.accent, background = palette.background, surface = palette.background,
        onSurface = palette.ink, onBackground = palette.ink,
        onPrimary = palette.background, onSurfaceVariant = palette.muted,
        surfaceVariant = palette.field, surfaceTint = Color.Transparent,
        surfaceContainerLow = palette.field, surfaceContainer = palette.field,
        surfaceContainerHigh = palette.field, surfaceContainerHighest = palette.field,
        outline = Color(0xFF858585), outlineVariant = palette.line,
        error = palette.error,
    ) else lightColorScheme(
        primary = palette.accent, background = palette.background, surface = palette.background,
        onPrimary = Color.White, onSurface = palette.ink, onBackground = palette.ink,
    )
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
