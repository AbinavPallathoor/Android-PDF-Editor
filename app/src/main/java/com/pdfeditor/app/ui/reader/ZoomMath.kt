package com.pdfeditor.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Pure zoom/pan math for the reading-mode pinch and double-tap gestures, kept free of Compose's
 * pointer-input types so it can be unit-tested on the JVM without a device.
 */
data class ZoomState(val scale: Float = MIN_SCALE, val offset: Offset = Offset.Zero) {
    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f
        const val DOUBLE_TAP_SCALE = 2f
    }
}

object ZoomMath {
    /** Applies one frame of a pinch/pan gesture, keeping [centroid] visually fixed under the fingers. */
    fun applyPinch(
        current: ZoomState,
        centroid: Offset,
        pan: Offset,
        zoomFactor: Float,
        viewportSize: Size,
    ): ZoomState {
        val newScale = (current.scale * zoomFactor).coerceIn(ZoomState.MIN_SCALE, ZoomState.MAX_SCALE)
        val scaleRatio = if (current.scale == 0f) 1f else newScale / current.scale
        val newOffset = (current.offset - centroid) * scaleRatio + centroid + pan
        return ZoomState(newScale, clampOffset(newOffset, newScale, viewportSize))
    }

    /** Applies a one-finger pan, only meaningful while already zoomed in. */
    fun applyPan(
        current: ZoomState,
        pan: Offset,
        viewportSize: Size,
    ): ZoomState = ZoomState(current.scale, clampOffset(current.offset + pan, current.scale, viewportSize))

    /** Fit-width <-> 2x toggle, zooming in centered on [tapPoint] when zooming in. */
    fun toggleDoubleTap(
        current: ZoomState,
        tapPoint: Offset,
        viewportSize: Size,
    ): ZoomState {
        if (current.scale > ZoomState.MIN_SCALE + 0.01f) {
            return ZoomState(ZoomState.MIN_SCALE, Offset.Zero)
        }
        val newScale = ZoomState.DOUBLE_TAP_SCALE
        val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val newOffset = (center - tapPoint) * (newScale - 1f)
        return ZoomState(newScale, clampOffset(newOffset, newScale, viewportSize))
    }

    fun clampOffset(
        offset: Offset,
        scale: Float,
        viewportSize: Size,
    ): Offset {
        if (scale <= ZoomState.MIN_SCALE) return Offset.Zero
        val maxX = viewportSize.width * (scale - 1f) / 2f
        val maxY = viewportSize.height * (scale - 1f) / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}
