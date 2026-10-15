package com.pdfeditor.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Dark only, always — Material3 is used as a structural base and heavily restyled; there is no
// light theme and no dynamic color, per the design brief.
private val PdfEditorColorScheme =
    darkColorScheme(
        primary = Accent,
        onPrimary = TrueBlack,
        background = TrueBlack,
        onBackground = OnSurfacePrimary,
        surface = TrueBlack,
        surfaceDim = TrueBlack,
        surfaceBright = TrueBlack,
        surfaceContainer = TrueBlack,
        surfaceContainerLow = TrueBlack,
        surfaceContainerLowest = TrueBlack,
        surfaceContainerHigh = TrueBlack,
        surfaceContainerHighest = TrueBlack,
        surfaceTint = androidx.compose.ui.graphics.Color.Transparent,
        onSurface = OnSurfacePrimary,
        surfaceVariant = SurfaceElevated,
        onSurfaceVariant = OnSurfaceSecondary,
        outline = OnSurfaceDisabled,
    )

@Composable
fun PdfEditorTheme(content: @Composable () -> Unit) {
    // The system dark/light setting is intentionally not consulted: this app has one theme.
    MaterialTheme(
        colorScheme = PdfEditorColorScheme,
        typography = PdfEditorTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides OnSurfacePrimary) {
            content()
        }
    }
}
