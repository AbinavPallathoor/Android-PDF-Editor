package com.pdfeditor.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// One clean sans-serif (the platform default), a handful of sizes, no decoration.
val PdfEditorTypography =
    Typography(
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 22.sp,
            ),
        bodyLarge =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
        bodyMedium =
            TextStyle(
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
    )
