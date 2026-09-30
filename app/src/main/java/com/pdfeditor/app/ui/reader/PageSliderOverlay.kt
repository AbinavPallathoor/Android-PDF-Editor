package com.pdfeditor.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.ui.theme.Accent
import kotlin.math.roundToInt

/**
 * Revealed by long-pressing the page in reading mode, for quick scrubbing through a large
 * document. Dismisses itself automatically shortly after the finger lifts (handled by the caller
 * toggling [visible] off on a timer).
 */
@Composable
fun PageSliderOverlay(
    visible: Boolean,
    currentPage: Int,
    pageCount: Int,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = currentPage.toFloat(),
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                    onValueChange = { onScrub(it.roundToInt()) },
                    colors =
                        SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                        ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${currentPage + 1} / $pageCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
