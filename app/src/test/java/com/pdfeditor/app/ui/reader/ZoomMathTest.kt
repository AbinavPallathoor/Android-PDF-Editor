package com.pdfeditor.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    private val viewport = Size(1000f, 2000f)

    @Test
    fun `pinch zoom clamps to max scale`() {
        val start = ZoomState(scale = 2.8f)

        val result = ZoomMath.applyPinch(start, centroid = Offset(500f, 1000f), pan = Offset.Zero, zoomFactor = 2f, viewportSize = viewport)

        assertEquals(ZoomState.MAX_SCALE, result.scale, 0.001f)
    }

    @Test
    fun `pinch zoom clamps to min scale`() {
        val start = ZoomState(scale = 1.2f)

        val result =
            ZoomMath.applyPinch(
                start,
                centroid = Offset(500f, 1000f),
                pan = Offset.Zero,
                zoomFactor = 0.1f,
                viewportSize = viewport,
            )

        assertEquals(ZoomState.MIN_SCALE, result.scale, 0.001f)
    }

    @Test
    fun `pinch zoom at scale 1 keeps offset at zero`() {
        val start = ZoomState(scale = ZoomState.MIN_SCALE, offset = Offset.Zero)

        val result = ZoomMath.applyPinch(start, centroid = Offset(500f, 1000f), pan = Offset.Zero, zoomFactor = 1f, viewportSize = viewport)

        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun `double tap zooms in when at fit width`() {
        val start = ZoomState(scale = ZoomState.MIN_SCALE)

        val result = ZoomMath.toggleDoubleTap(start, tapPoint = Offset(500f, 1000f), viewportSize = viewport)

        assertEquals(ZoomState.DOUBLE_TAP_SCALE, result.scale, 0.001f)
    }

    @Test
    fun `double tap zooms back out when already zoomed`() {
        val start = ZoomState(scale = ZoomState.DOUBLE_TAP_SCALE, offset = Offset(50f, 50f))

        val result = ZoomMath.toggleDoubleTap(start, tapPoint = Offset(500f, 1000f), viewportSize = viewport)

        assertEquals(ZoomState.MIN_SCALE, result.scale, 0.001f)
        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun `pan is a no-op at min scale`() {
        val start = ZoomState(scale = ZoomState.MIN_SCALE, offset = Offset.Zero)

        val result = ZoomMath.applyPan(start, pan = Offset(100f, 100f), viewportSize = viewport)

        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun `pan while zoomed is clamped within bounds`() {
        val start = ZoomState(scale = 2f, offset = Offset.Zero)

        val result = ZoomMath.applyPan(start, pan = Offset(10_000f, 10_000f), viewportSize = viewport)

        val maxX = viewport.width * (2f - 1f) / 2f
        val maxY = viewport.height * (2f - 1f) / 2f
        assertTrue(result.offset.x <= maxX + 0.001f)
        assertTrue(result.offset.y <= maxY + 0.001f)
    }

    @Test
    fun `focal pinch keeps the content under the fingers in place`() {
        val center = 500f
        val pan = 40f
        val focus = 200f
        // Content coordinate (relative to the content centre) currently under the focus.
        val zoom = 1.5f
        val contentUnderFocus = (focus - (center + pan)) / zoom
        val ratio = 2f

        val newPan = ZoomMath.focalPan(pan, center, focus, ratio)

        assertEquals(focus, center + newPan + contentUnderFocus * zoom * ratio, 0.001f)
    }

    @Test
    fun `focal pinch at the centre with no pan stays centred`() {
        assertEquals(0f, ZoomMath.focalPan(0f, 500f, 500f, 3f), 0.001f)
    }

    @Test
    fun `focal top pins the point under the focus to its target`() {
        val top = 100f
        val size = 800f
        val focus = 500f
        val ratio = 1.25f

        val newTop = ZoomMath.focalTop(top, size, focus, target = 520f, ratio = ratio)

        // Same fraction of the item lands on the target after scaling.
        assertEquals(520f, newTop + (focus - top) / size * size * ratio, 0.001f)
    }

    @Test
    fun `focal top with no zoom follows the finger pan`() {
        assertEquals(130f, ZoomMath.focalTop(100f, 800f, focus = 400f, target = 430f, ratio = 1f), 0.001f)
    }

    @Test
    fun `elastic zoom resists pushing past the limit and caps the overshoot`() {
        val resisted = ZoomMath.elasticZoom(5f, 1.5f, .25f, 5f, elastic = true)
        assertTrue("past the limit it still moves, but less than the fingers", resisted > 5f && resisted < 5f * 1.5f)
        assertEquals(5f * 1.15f, ZoomMath.elasticZoom(5.7f, 3f, .25f, 5f, elastic = true), .001f)
        assertEquals(.25f * .85f, ZoomMath.elasticZoom(.22f, .1f, .25f, 5f, elastic = true), .001f)
    }

    @Test
    fun `zoom inside the limits follows the fingers and non elastic zoom clamps`() {
        assertEquals(3f, ZoomMath.elasticZoom(2f, 1.5f, .25f, 5f, elastic = true), .001f)
        assertEquals(5f, ZoomMath.elasticZoom(4f, 2f, .25f, 5f, elastic = false), .001f)
        // Pulling back from an overshoot is never resisted.
        assertEquals(5.5f * .9f, ZoomMath.elasticZoom(5.5f, .9f, .25f, 5f, elastic = true), .001f)
    }
}
