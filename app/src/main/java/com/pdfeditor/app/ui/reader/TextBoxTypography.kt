package com.pdfeditor.app.ui.reader

import android.graphics.Typeface
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.pdfeditor.app.domain.TextBoxAnnotation
import kotlin.math.roundToInt

internal fun textBoxStyle(
    size: Float,
    scale: Float,
    density: Density,
    typeface: Typeface,
): TextStyle =
    TextStyle(
        fontFamily = FontFamily(typeface),
        fontSize = (size * scale / density.density / density.fontScale).sp,
        lineHeight = (size * scale * 1.25f / density.density / density.fontScale).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

/** Canonical PDF-space wrapping stays stable when the reader zoom changes. */
internal fun TextMeasurer.reflowTextBox(
    annotation: TextBoxAnnotation,
    raw: String,
    widthPoints: Float,
    density: Density,
    typeface: Typeface,
): String =
    measure(
        raw,
        textBoxStyle(annotation.fontSizePoints, 4f, density, typeface),
        constraints = Constraints(maxWidth = (widthPoints * 4f).roundToInt().coerceAtLeast(1)),
    ).pdfLines()
