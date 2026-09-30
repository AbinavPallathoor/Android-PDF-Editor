package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.ui.theme.OnSurfaceSecondary

/**
 * Milestone 4 fills this in with sign/text/draw/erase tools. For now it just proves the mode-toggle
 * gesture and transition work, and lets a two-finger swipe take the user back to reading mode
 * (there's no scrollable/zoomable content here to conflict with, so the minimal detector suffices).
 */
@Composable
fun WritingModePlaceholder(
    onModeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 96.dp.toPx() }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .twoFingerVerticalSwipeToTrigger(thresholdPx, onModeToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Writing mode",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Text(
                text = "Sign, text, draw and erase tools arrive in Milestone 4",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}
