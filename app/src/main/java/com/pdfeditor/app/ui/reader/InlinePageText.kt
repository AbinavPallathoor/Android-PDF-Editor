package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfeditor.app.domain.TextBoxAnnotation
import java.util.UUID
import kotlin.math.roundToInt

internal data class PageTextEdit(
    val pageId: String,
    val annotation: TextBoxAnnotation,
    val session: String = UUID.randomUUID().toString(),
    val pdfText: String = annotation.text,
)

/** Converts visual wrapping to explicit PDF lines, keeping the exported layout identical. */
internal fun TextLayoutResult.pdfLines(): String =
    (0 until lineCount).joinToString("\n") { line ->
        layoutInput.text.text.substring(getLineStart(line), getLineEnd(line)).trimEnd('\n', '\r')
    }

@Composable
internal fun InlinePageText(
    edit: PageTextEdit,
    scale: Float,
    pageWidthPx: Float,
    invert: Boolean,
    onChange: (raw: String, pdfText: String) -> Unit,
    onFinish: () -> Unit,
    onBounds: (Rect) -> Unit,
    onCaret: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val focus = remember(edit.session) { FocusRequester() }
    val bringIntoView = remember(edit.session) { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val measurer = rememberTextMeasurer()
    val annotation = edit.annotation
    var input by remember(edit.session) { mutableStateOf(TextFieldValue(annotation.text, TextRange(annotation.text.length))) }
    val value = if (input.text == annotation.text) input else TextFieldValue(annotation.text, TextRange(annotation.text.length))
    var textLayout by remember(edit.session) { mutableStateOf<TextLayoutResult?>(null) }
    val paddingPx = with(density) { 4.dp.toPx() }
    val available = (pageWidthPx - annotation.position.x * scale - paddingPx).coerceAtLeast(paddingPx * 4)
    val contentWidth = (available - paddingPx * 2).roundToInt().coerceAtLeast(1)
    val argb = Color(annotation.colorArgb)
    val ink = if (invert) Color(1 - argb.red, 1 - argb.green, 1 - argb.blue, argb.alpha) else argb
    val outline = if (invert) Color.White else Color.Black
    val style =
        TextStyle(
            color = ink,
            fontFamily = FontFamily.SansSerif,
            fontSize = (annotation.fontSizePoints * scale / density.density / density.fontScale).sp,
            lineHeight = (annotation.fontSizePoints * scale * 1.25f / density.density / density.fontScale).sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    var baseline by remember(edit.session, scale) { mutableFloatStateOf(annotation.fontSizePoints * scale) }
    LaunchedEffect(edit.session) {
        focus.requestFocus()
        keyboard?.show()
        bringIntoView.bringIntoView()
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(edit.session, imeBottom, value.selection, textLayout, annotation.position, scale) {
        textLayout?.takeIf { it.layoutInput.text.text == value.text }?.let { layout ->
            val caret = layout.getCursorRect(value.selection.end.coerceIn(0, value.text.length))
            onCaret(
                Rect(
                    annotation.position.x * scale + caret.left,
                    annotation.position.y * scale - baseline + caret.top,
                    annotation.position.x * scale + caret.right,
                    annotation.position.y * scale - baseline + caret.bottom,
                ),
            )
            if (imeBottom > 0) {
                val saveSpace = with(density) { 64.dp.toPx() }
                bringIntoView.bringIntoView(Rect(caret.left, caret.top, caret.right, caret.bottom + saveSpace))
            }
        }
    }
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(outline, outline.copy(alpha = .2f))) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                input = next
                val normalized = next.text.replace("\r\n", "\n").replace('\r', '\n')
                if (normalized != annotation.text) {
                    val layout = measurer.measure(normalized, style, constraints = Constraints(maxWidth = contentWidth))
                    onChange(normalized, layout.pdfLines())
                }
            },
            textStyle = style,
            cursorBrush = SolidColor(outline),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish() }),
            onTextLayout = {
                baseline = it.firstBaseline
                textLayout = it
            },
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            (annotation.position.x * scale - paddingPx).roundToInt(),
                            (annotation.position.y * scale - baseline - paddingPx).roundToInt(),
                        )
                    }
                    .width(with(density) { available.toDp() })
                    .onGloballyPositioned {
                        onBounds(
                            Rect(
                                it.positionInParent(),
                                androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()),
                            ),
                        )
                    }
                    .border(1.dp, outline)
                    .padding(4.dp)
                    .focusRequester(focus)
                    .bringIntoViewRequester(bringIntoView)
                    .testTag("annotation-text"),
        )
    }
}
