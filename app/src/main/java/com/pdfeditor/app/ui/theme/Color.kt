package com.pdfeditor.app.ui.theme

import androidx.compose.ui.graphics.Color

// True black background — the PDF is the only thing that should read as "bright" on screen.
val TrueBlack = Color(0xFF000000)
val SurfaceDim = TrueBlack
val SurfaceElevated = TrueBlack
val OnSurfacePrimary = Color(0xFFEDEDED)
val OnSurfaceSecondary = Color(0xFF9A9A9A)
val OnSurfaceDisabled = Color(0xFF5C5C5C)

// Monochrome only: pure black, shades of dark grey, and white - no hue anywhere in the UI. Accent
// marks interactive/selected state purely through brightness (pure white against dark grey/black).
val Accent = Color(0xFFFFFFFF)

// A neutral dark-grey hint background (e.g. behind a swipe-to-remove row) - Material3's default
// error/errorContainer roles are reds we deliberately never adopted into this scheme.
val SurfaceHint = TrueBlack
