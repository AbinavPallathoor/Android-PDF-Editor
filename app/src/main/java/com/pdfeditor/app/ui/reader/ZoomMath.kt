package com.pdfeditor.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.pow

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

    /**
     * One axis of a pinch about a focal point. Content centred at `center + pan` is scaled by
     * [ratio] about [focus], so the content under [focus] stays put. Returns the new pan.
     */
    fun focalPan(
        pan: Float,
        center: Float,
        focus: Float,
        ratio: Float,
    ): Float = focus + (center + pan - focus) * ratio - center

    /**
     * New top of an item spanning [top] to `top + size`, scaled by [ratio], so the content that
     * was under [focus] lands at [target]. Focus points outside the item pin its nearest edge.
     */
    fun focalTop(
        top: Float,
        size: Float,
        focus: Float,
        target: Float,
        ratio: Float,
    ): Float {
        val fraction = if (size > 0f) ((focus - top) / size).coerceIn(0f, 1f) else 0f
        return target - fraction * size * ratio
    }

    /**
     * Next zoom for a pinch step of [factor]. With [elastic], pinching past [min]/[max] keeps
     * going with strong resistance (up to a small overshoot) so the limit is felt rather than hit;
     * the caller springs back on release. Without it the result is clamped.
     */
    fun elasticZoom(
        current: Float,
        factor: Float,
        min: Float,
        max: Float,
        elastic: Boolean,
    ): Float {
        if (!elastic) return (current * factor).coerceIn(min, max)
        val pushingOut = (current >= max && factor > 1f) || (current <= min && factor < 1f)
        val applied = if (pushingOut) factor.pow(OVERSHOOT_RESISTANCE) else factor
        return (current * applied).coerceIn(min * MIN_OVERSHOOT, max * MAX_OVERSHOOT)
    }

    private const val OVERSHOOT_RESISTANCE = .3f
    private const val MIN_OVERSHOOT = .85f
    private const val MAX_OVERSHOOT = 1.15f

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
