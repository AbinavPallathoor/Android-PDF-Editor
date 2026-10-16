package com.pdfeditor.app.ui.reader

import android.graphics.Typeface
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.data.fonts.EditorFont

/** The document stays full size; this compact tray only exists during annotation editing. */
@Composable
internal fun DocumentEditingTray(
    tool: EditorTool,
    color: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onTool: (EditorTool) -> Unit,
    onColor: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    fonts: List<EditorFont> = emptyList(),
    fontId: String = "roboto",
    onChooseFont: ((String) -> Unit)? = null,
    onImportFont: () -> Unit = {},
    fontTypeface: (String) -> Typeface = { Typeface.DEFAULT },
) {
    var fontsVisible by remember { mutableStateOf(false) }
    var colorsVisible by remember { mutableStateOf(false) }
    Surface(modifier.testTag("editing-tray-surface"), shape = RoundedCornerShape(20.dp), color = Color.Black, shadowElevation = 0.dp) {
        Column(Modifier.animateContentSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onChooseFont != null) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { fontsVisible = !fontsVisible }, Modifier.testTag("font-options")) {
                            Text(
                                "${fonts.firstOrNull { it.id == fontId }?.displayName ?: "Font"} ▾",
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = fontsVisible,
                            onDismissRequest = { fontsVisible = false },
                            modifier = Modifier.heightIn(max = 300.dp),
                            containerColor = Color.Black,
                        ) {
                            fonts.forEach { font ->
                                DropdownMenuItem(
                                    text = { Text(font.displayName, color = Color.White, fontFamily = FontFamily(fontTypeface(font.id))) },
                                    onClick = {
                                        fontsVisible = false
                                        onChooseFont(font.id)
                                    },
                                    modifier = Modifier.testTag("font-option-${font.id}"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Add font…", color = Color.White) },
                                onClick = {
                                    fontsVisible = false
                                    onImportFont()
                                },
                                modifier = Modifier.testTag("import-font"),
                            )
                        }
                    }
                } else {
                    Text(
                        tool.label,
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.LightGray,
                    )
                }
                if ((tool != EditorTool.HAND && tool != EditorTool.ERASE) || onChooseFont != null) {
                    Box {
                        QuietIconButton(OptionsGlyph, "Ink colour", { colorsVisible = !colorsVisible }, Modifier.testTag("ink-options"))
                        DropdownMenu(expanded = colorsVisible, onDismissRequest = { colorsVisible = false }, containerColor = Color.Black) {
                            Row(Modifier.padding(8.dp).testTag("ink-colors"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    0xFF000000.toInt(),
                                    0xFFFFFFFF.toInt(),
                                    0xFFFF3B30.toInt(),
                                    0xFF007AFF.toInt(),
                                    0xFF34C759.toInt(),
                                    0xFFFFFF00.toInt(),
                                ).forEachIndexed {
                                        index,
                                        value,
                                    ->
                                    Box(
                                        Modifier.size(40.dp).clip(CircleShape).clickable {
                                            onColor(value)
                                            colorsVisible = false
                                        }.padding(7.dp).border(if (color == value) 2.dp else 1.dp, Color.LightGray, CircleShape)
                                            .padding(3.dp).background(Color(value), CircleShape)
                                            .testTag("ink-color-$index"),
                                    )
                                }
                            }
                        }
                    }
                }
                if (canUndo || canRedo) {
                    QuietIconButton(UndoGlyph, "Undo", onUndo, Modifier.testTag("undo"), enabled = canUndo)
                    QuietIconButton(RedoGlyph, "Redo", onRedo, Modifier.testTag("redo"), enabled = canRedo)
                }
                QuietIconButton(Icons.Filled.Check, "Finish editing", onDone, Modifier.testTag("finish-editing"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                EditorTool.entries.forEach { entry ->
                    QuietIconButton(
                        toolGlyph(entry),
                        entry.label,
                        { onTool(entry) },
                        // Expose the active tool to accessibility services, not only as a ring.
                        Modifier.testTag("tool-${entry.name}").semantics { selected = tool == entry },
                        selected = tool == entry,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DocumentOptions(
    paged: Boolean,
    onPages: () -> Unit,
    onLayout: () -> Unit,
    onFit: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DocumentAction(PagesGlyph, "Pages", onPages, Modifier.weight(1f).testTag("pages"))
            DocumentAction(FitGlyph, "Fit page", onFit, Modifier.weight(1f).testTag("fit-page"))
            DocumentAction(ViewGlyph, if (paged) "Scroll view" else "Page view", onLayout, Modifier.weight(1f).testTag("layout-toggle"))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = .14f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DocumentAction(Icons.Filled.Share, "Share", onShare, Modifier.weight(1f))
            DocumentAction(CompressGlyph, "Compress", onCompress, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DocumentAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

private val PagesGlyph = glyph("Pages", "M3,2L9,2L9,10L3,10ZM13,2L21,2L21,10L13,10ZM3,14L9,14L9,22L3,22ZM13,14L21,14L21,22L13,22Z")
private val FitGlyph =
    glyph(
        "Fit page",
        "M3,8L3,2L9,2L9,4L5,4L5,8ZM15,2L21,2L21,8L19,8L19,4L15,4ZM3,16L5,16L5,20L9,20L9,22L3,22Z" +
            "M19,16L21,16L21,22L15,22L15,20L19,20ZM8,7L16,7L16,17L8,17Z",
    )
private val ViewGlyph = glyph("Page view", "M5,2L19,2L19,22L5,22ZM7,4L7,20L17,20L17,4ZM9,6L15,6L15,8L9,8ZM9,10L15,10L15,12L9,12Z")
private val CompressGlyph =
    glyph(
        "Compress",
        "M5,2L19,2L19,4L5,4ZM11,5L13,5L13,9L16,9L12,13L8,9L11,9ZM5,20L19,20L19,22L5,22ZM11,19L13,19L13,15L16,15L12,11L8,15L11,15Z",
    )

@Composable
internal fun QuietIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .background(Color.Black)
            .then(if (selected) Modifier.border(1.dp, Color.White, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(21.dp), tint = Color.White.copy(alpha = if (enabled) 1f else .28f))
    }
}

private fun glyph(
    name: String,
    data: String,
): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.White)).build()

// Compact filled monochrome glyphs; colours belong to annotations, not the interface.
private val PenGlyph =
    glyph(
        "Pen",
        "M3,17.25L3,21L6.75,21L17.81,9.94L14.06,6.19ZM20.71,7.04C21.1,6.65 21.1,6.02 20.71,5.63L18.37,3.29C17" +
            ".98,2.9 17.35,2.9 16.96,3.29L15.13,5.12L18.88,8.87Z",
    )
private val HandGlyph =
    glyph(
        "Hand",
        "M8,12L8,5C8,3 11,3 11,5L11,11L12,11L12,3C12,1 15,1 15,3L15,11L16,11L16,5C16,3 19,3 19,5L19,12L20,12L" +
            "20,8C20,6 23,6 23,8L23,15C23,20 20,23 15,23L12,23C10,23 9,22 8,21L2,15C0,13 2,11 4,12L8,15Z",
    )
private val HighlightGlyph = glyph("Highlight", "M14,2L22,10L12,20L4,12ZM3,14L10,21L5,21L1,17ZM1,23L23,23L23,21L1,21Z")
private val TextGlyph = glyph("Text", "M3,3L21,3L21,7L19,7L19,6L14,6L14,20L17,20L17,22L7,22L7,20L10,20L10,6L5,6L5,7L3,7Z")
private val SignGlyph =
    glyph(
        "Signature",
        "M2,17C6,15 9,4 11,3C14,1 14,9 11,13C9,16 8,18 10,18C12,18 14,11 16,12C18,13 14,17 17,17L22,17L22,20L" +
            "17,20C15,20 14,19 14,18C10,24 5,21 7,17L3,20Z",
    )
private val EraseGlyph =
    glyph("Erase", "M15,3C16,2 17,2 18,3L23,8C24,9 24,10 23,11L12,22L5,22L0,17C-1,16 -1,15 0,14ZM9,10L3,16L7,20L11,20L16,15Z")
private val OptionsGlyph =
    glyph(
        "Colour",
        "M12,2C6.5,2 2,6.5 2,12C2,17.5 6.5,22 12,22C14,22 15,20 14,18C13,16 15,15 17,15L19,15C23,15 23,9 21,6" +
            "C19,3 16,2 12,2ZM7,7A1.5,1.5 0,1 1,7,10A1.5,1.5 0,1 1,7,7ZM12,5A1.5,1.5 0,1 1,12,8A1.5,1.5 0,1 1,12," +
            "5ZM17,7A1.5,1.5 0,1 1,17,10A1.5,1.5 0,1 1,17,7Z",
    )
private val UndoGlyph = glyph("Undo", "M7,7L7,3L0,10L7,17L7,13L14,13C18,13 20,15 20,19L23,19C23,12 20,7 14,7Z")
private val RedoGlyph = glyph("Redo", "M17,7L17,3L24,10L17,17L17,13L10,13C6,13 4,15 4,19L1,19C1,12 4,7 10,7Z")

private fun toolGlyph(tool: EditorTool): ImageVector =
    when (tool) {
        EditorTool.HAND -> HandGlyph
        EditorTool.DRAW -> PenGlyph
        EditorTool.HIGHLIGHT -> HighlightGlyph
        EditorTool.TEXT -> TextGlyph
        EditorTool.SIGN -> SignGlyph
        EditorTool.ERASE -> EraseGlyph
    }
