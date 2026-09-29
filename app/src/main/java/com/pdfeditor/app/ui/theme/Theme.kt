package com.pdfeditor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark only, always — Material3 is used as a structural base and heavily restyled; there is no
// light theme and no dynamic color, per the design brief.
private val PdfEditorColorScheme =
    darkColorScheme(
        primary = Accent,
        onPrimary = TrueBlack,
        background = TrueBlack,
        onBackground = OnSurfacePrimary,
        surface = SurfaceDim,
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
        content = content,
    )
}
