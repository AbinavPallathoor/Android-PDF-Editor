package com.pdfeditor.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag

internal data class DocumentSwitchFrame(val uri: String, val image: ImageBitmap, val ready: Boolean = false)

/** A temporary display frame only. PDF files and annotation models are never rasterized. */
@Composable
internal fun DocumentSwitchOverlay(
    frame: DocumentSwitchFrame,
    onFinished: () -> Unit,
) {
    val alpha = remember(frame.image) { Animatable(1f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(frame.image, frame.ready) {
        if (frame.ready) {
            // The render callback precedes Compose drawing; let its first actual frame land.
            withFrameNanos { }
            withFrameNanos { }
            alpha.animateTo(0f, tween(220))
            finished()
        }
    }
    Image(
        frame.image,
        null,
        contentScale = ContentScale.FillBounds,
        modifier =
            Modifier.fillMaxSize().testTag("document-switch-frame")
                .graphicsLayer { this.alpha = alpha.value }
                .pointerInput(frame.image) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
    )
}
