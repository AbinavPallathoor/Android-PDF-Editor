package com.pdfeditor.app.ui.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.pdfeditor.app.domain.CompressionPreset
import com.pdfeditor.app.domain.CompressionResult
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.ui.theme.OnSurfaceSecondary
import com.pdfeditor.app.ui.theme.SurfaceElevated
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val THUMB_TARGET_WIDTH_DP = 200
private const val THUMB_TARGET_TOP_DP = 140
private const val GRADIENT_EXTRA_FADE_DP = 48

/**
 * A gradient bar (fades to transparent) showing a page-1 thumbnail with the document's name and a
 * page-position subtitle to its left, plus close/write-mode/invert buttons. Shows while scrolling,
 * same idle-hide timing as the page indicator. Tap on the thumbnail morphs it out to the center of
 * the screen with the document title (now tappable to rename) and Share/Compress actions beneath
 * it; a horizontal swipe on the collapsed bar instead switches to the next/previous document in
 * Recents, in place. One unified `awaitEachGesture` distinguishes tap from swipe on the collapsed
 * bar, per the "one gesture reader per surface" rule (see CLAUDE.md).
 */
@Composable
fun DocumentTitleBar(
    displayName: String,
    subtitle: String,
    thumbnail: Bitmap?,
    visible: Boolean,
    invertColors: Boolean,
    contentResolver: ContentResolver,
    adjacentDocument: (forward: Boolean) -> RecentDocument?,
    onRename: (String) -> Unit,
    onShare: () -> Unit,
    onCompress: suspend (CompressionPreset) -> CompressionResult?,
    onSwitchDocument: suspend (RecentDocument) -> Boolean,
    onClose: () -> Unit,
    onToggleWriteMode: () -> Unit,
    onToggleInvert: () -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    expandedContent: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    onHeaderHeightChanged: (Int) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    var thumbBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(expanded) {
        if (expanded) progress.animateTo(1f, tween(320)) else progress.animateTo(0f, tween(220))
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible || expanded || renaming || swiping || progress.value > 0f,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            CollapsedBar(
                displayName = displayName,
                subtitle = subtitle,
                thumbnail = thumbnail,
                thumbnailAlpha = 1f - progress.value,
                invertColors = invertColors,
                contentResolver = contentResolver,
                adjacentDocument = adjacentDocument,
                onTap = { expanded = true },
                onSwitchDocument = onSwitchDocument,
                onThumbnailBoundsChanged = { thumbBoundsInRoot = it },
                onClose = onClose,
                onToggleWriteMode = onToggleWriteMode,
                onToggleInvert = onToggleInvert,
                editing = editing,
                onHeaderHeightChanged = onHeaderHeightChanged,
                onSwipeActive = { swiping = it },
            )
        }

        if (progress.value > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.72f * progress.value))
                        .then(if (progress.value > 0.3f) Modifier.clickable(onClick = { expanded = false }) else Modifier),
            )

            thumbnail?.let { bmp ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val screenWidthPx = with(density) { maxWidth.toPx() }
                    val screenHeightPx = with(density) { maxHeight.toPx() }
                    val aspect = bmp.height.toFloat() / bmp.width.toFloat()
                    val targetWidthPx =
                        minOf(
                            with(density) { THUMB_TARGET_WIDTH_DP.dp.toPx() },
                            screenWidthPx * .75f,
                            screenHeightPx * .28f / aspect,
                        )
                    val targetHeightPx = targetWidthPx * aspect
                    val targetLeft = (screenWidthPx - targetWidthPx) / 2f
                    val targetTop =
                        minOf(
                            with(density) { THUMB_TARGET_TOP_DP.dp.toPx() },
                            maxOf(screenHeightPx * .1f, WindowInsets.statusBars.getTop(density) + with(density) { 16.dp.toPx() }),
                        )

                    val start = thumbBoundsInRoot ?: Rect(targetLeft, targetTop, targetLeft + targetWidthPx, targetTop + targetHeightPx)
                    val t = progress.value
                    val left = lerp(start.left, targetLeft, t)
                    val top = lerp(start.top, targetTop, t)
                    val width = lerp(start.width, targetWidthPx, t)
                    val height = lerp(start.height, targetHeightPx, t)

                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier =
                            Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                                .clip(RoundedCornerShape(10.dp)),
                    )

                    AnimatedVisibility(
                        visible = progress.value > 0.6f,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(100)),
                        modifier =
                            Modifier
                                .offset { IntOffset(0, (targetTop + targetHeightPx).roundToInt()) }
                                .width(maxWidth)
                                .heightIn(max = with(density) { (screenHeightPx - targetTop - targetHeightPx).coerceAtLeast(1f).toDp() })
                                .verticalScroll(rememberScrollState()),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .padding(top = 16.dp, start = 32.dp, end = 32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = {
                                            expanded = false
                                            renaming = true
                                        })
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            if (expandedContent != null) {
                                expandedContent { expanded = false }
                            } else {
                                ExpandedActions(
                                    onShare = {
                                        expanded = false
                                        onShare()
                                    },
                                    onCompress = onCompress,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        RenamePopup(
            initialText = displayName,
            onDismiss = { renaming = false },
            onCommit = { newName ->
                renaming = false
                onRename(newName)
            },
        )
    }
}

private fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsedBar(
    displayName: String,
    subtitle: String,
    thumbnail: Bitmap?,
    thumbnailAlpha: Float,
    invertColors: Boolean,
    contentResolver: ContentResolver,
    adjacentDocument: (forward: Boolean) -> RecentDocument?,
    onTap: () -> Unit,
    onSwitchDocument: suspend (RecentDocument) -> Boolean,
    onThumbnailBoundsChanged: (Rect) -> Unit,
    onClose: () -> Unit,
    onToggleWriteMode: () -> Unit,
    onToggleInvert: () -> Unit,
    editing: Boolean,
    onHeaderHeightChanged: (Int) -> Unit,
    onSwipeActive: (Boolean) -> Unit,
) {
    // Keep the compact controls above both the camera cutout and system status area.
    // Fade only below the controls; reserving header space must not expose a seam.
    Box(Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .onSizeChanged { onHeaderHeightChanged(it.height) }
                        .windowInsetsPadding(
                            WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top),
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .height(56.dp),
            ) {
                HeaderDocumentSwitcher(
                    displayName, subtitle, thumbnail, thumbnailAlpha, contentResolver,
                    adjacentDocument, onTap, onSwitchDocument, onThumbnailBoundsChanged,
                    onSwipeActive, Modifier.weight(1f),
                )
                IconGlyphButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Close",
                    onClick = onClose,
                    modifier = Modifier.testTag("close-document"),
                )
                IconGlyphButton(
                    icon = Icons.Filled.Edit,
                    contentDescription = if (editing) "Finish editing" else "Edit PDF",
                    modifier = Modifier.testTag("toggle-editing"),
                    onClick = onToggleWriteMode,
                    iconSize = 23.dp,
                )
                InvertToggleButton(invertColors = invertColors, onClick = onToggleInvert)
            }
            Spacer(
                Modifier.fillMaxWidth().height(GRADIENT_EXTRA_FADE_DP.dp).background(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        .25f to Color.Black,
                        1f to Color.Transparent,
                    ),
                ),
            )
        }
    }
}

/**
 * A small round icon button using a solid Material "Filled" glyph, always tinted white (never the
 * icon's own multi-tone default) so it stays pure monochrome. [iconSize] defaults to 20.dp but can
 * be bumped per-icon: Material's glyphs don't all fill their viewport equally (Close's X reads much
 * bolder than Edit's pencil at the same box size), so a uniform size doesn't give equal visual
 * weight - tune per icon by eye rather than assuming one size fits all.
 */
@Composable
private fun IconGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconSize: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(44.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/**
 * Sun/moon toggle for normal vs. inverted PDF view. Hand-drawn as solid filled shapes (not a font
 * glyph or an emoji-presentation Unicode symbol) so it's guaranteed to render as a single flat
 * white fill with no color of its own - a Material Icons brightness/dark-mode glyph isn't part of
 * the small "core" icon set this app depends on (see THIRD_PARTY_LICENSES), so this fills that gap.
 */
@Composable
private fun InvertToggleButton(
    invertColors: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .testTag("invert-toggle")
                .semantics { contentDescription = if (invertColors) "Dark PDF" else "Normal PDF" }
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            if (invertColors) {
                val outer = Path().apply { addOval(Rect(Offset.Zero, size)) }
                val cutout =
                    Path().apply {
                        addOval(
                            Rect(
                                Offset(size.width * 0.32f, -size.height * 0.08f),
                                Size(size.width * 0.9f, size.height * 0.9f),
                            ),
                        )
                    }
                val crescent = Path()
                crescent.op(outer, cutout, PathOperation.Difference)
                drawPath(crescent, color = Color.White)
            } else {
                val center = Offset(size.width / 2f, size.height / 2f)
                val coreRadius = size.minDimension * 0.26f
                drawCircle(color = Color.White, radius = coreRadius, center = center)
                val rayInner = coreRadius + size.minDimension * 0.1f
                val rayOuter = rayInner + size.minDimension * 0.16f
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
                        end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
                        strokeWidth = size.minDimension * 0.09f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedActions(
    onShare: () -> Unit,
    onCompress: suspend (CompressionPreset) -> CompressionResult?,
) {
    var compressing by remember { mutableStateOf(false) }
    var compressionResult by remember { mutableStateOf<CompressionResult?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceElevated)
                .clickable(onClick = {})
                .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconGlyphButton(icon = Icons.Filled.Share, contentDescription = "Share", onClick = onShare)
            PopupAction(
                label =
                    compressionResult?.let { r ->
                        "Compressed ${formatBytes(r.originalSizeBytes)} → ${formatBytes(r.compressedSizeBytes)}"
                    } ?: "Compress",
                onClick = { showPresets = !showPresets },
            )
        }
        AnimatedVisibility(visible = showPresets, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                for (preset in listOf(CompressionPreset.LOW, CompressionPreset.MEDIUM, CompressionPreset.HIGH)) {
                    PopupAction(
                        label = preset.name.lowercase().replaceFirstChar { it.uppercase() },
                        enabled = !compressing,
                        onClick = {
                            compressing = true
                            scope.launch {
                                compressionResult = onCompress(preset)
                                compressing = false
                                showPresets = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Color.White else OnSurfaceSecondary)
    }
}

@Composable
private fun RenamePopup(
    initialText: String,
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = { onCommit(text) }),
        ) {
            // TextField consumes its own press/drag internally (cursor placement, selection), so
            // taps on it never fall through to the scrim's onCommit behind it.
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceElevated,
                        unfocusedContainerColor = SurfaceElevated,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                        .width(220.dp)
                        .clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "${(bytes / 1000.0).roundToInt()} KB"
        else -> "$bytes B"
    }
