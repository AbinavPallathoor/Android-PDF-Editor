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
}
