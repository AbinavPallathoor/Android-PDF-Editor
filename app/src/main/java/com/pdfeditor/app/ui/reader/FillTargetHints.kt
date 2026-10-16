package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.PdfFillTarget
import kotlin.math.roundToInt

/** Physical taps stay in the page's unified gesture detector; semantics also supports accessibility. */
@Composable
internal fun FillTargetHints(
    targets: List<PdfFillTarget>,
    scale: Float,
    invert: Boolean,
    onFill: (PdfFillTarget) -> Unit,
) {
    val density = LocalDensity.current
    val foreground = if (invert) Color.White else Color.Black
    val paper = if (invert) Color.Black else Color.White
    targets.forEach { target ->
        val rect = target.bounds
        val width = with(density) { ((rect.right - rect.left) * scale).toDp() }
        val height = with(density) { ((rect.bottom - rect.top) * scale).toDp() }
        val action = if (target.kind == FillTargetKind.SIGNATURE) "Sign" else "Fill"
        Box(
            Modifier.offset { IntOffset((rect.left * scale).roundToInt(), (rect.top * scale).roundToInt()) }
                .size(width, height).testTag("fill-target-${target.id}")
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "$action ${target.label.ifBlank { "field" }}"
                    onClick(label = action) {
                        onFill(target)
                        true
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    foreground.copy(alpha = .55f),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            }
            val textSize = minOf(11f, height.value * .65f).coerceAtLeast(6f).sp
            Text(
                action,
                Modifier.align(Alignment.CenterStart).padding(start = 3.dp).background(paper.copy(alpha = .9f)),
                color = foreground,
                fontSize = textSize,
                lineHeight = textSize,
                maxLines = 1,
            )
        }
    }
}
