package com.hermes.client.ui.chat.imageedit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.client.data.media.imageedit.BrushSize
import com.hermes.client.data.media.imageedit.CropAspect
import com.hermes.client.data.media.imageedit.StrokeWeight
import com.hermes.client.ui.components.CropStrokeIcon
import com.hermes.client.ui.components.DoodleStrokeIcon
import com.hermes.client.ui.components.MosaicStrokeIcon
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.InkColor
import com.hermes.client.ui.theme.InkPalette
import com.hermes.client.ui.theme.inkColor

/**
 * Chrome sits on the photo, so it is black-on-white in **both** themes: there is no `surface`
 * underneath to branch on. Same language as the image viewer's round buttons.
 */
internal val EditorScrim = Color.Black.copy(alpha = 0.58f)

@Composable
internal fun EditorActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = if (selected) Color.White.copy(alpha = 0.92f) else EditorScrim,
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = when {
                    selected -> Color.Black
                    enabled -> Color.White
                    // Disabled has to read as disabled on an unpredictable backdrop, so it dims
                    // rather than changing hue.
                    else -> Color.White.copy(alpha = 0.35f)
                },
            )
        }
    }
}

@Composable
internal fun ToolRow(tool: EditorTool, onSelect: (EditorTool) -> Unit, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        EditorActionButton(
            icon = DoodleStrokeIcon,
            contentDescription = toolLabel(EditorTool.DOODLE, language),
            selected = tool == EditorTool.DOODLE,
            onClick = { onSelect(EditorTool.DOODLE) },
        )
        EditorActionButton(
            icon = MosaicStrokeIcon,
            contentDescription = toolLabel(EditorTool.MOSAIC, language),
            selected = tool == EditorTool.MOSAIC,
            onClick = { onSelect(EditorTool.MOSAIC) },
        )
        EditorActionButton(
            icon = CropStrokeIcon,
            contentDescription = toolLabel(EditorTool.CROP, language),
            selected = tool == EditorTool.CROP,
            onClick = { onSelect(EditorTool.CROP) },
        )
    }
}

internal fun toolLabel(tool: EditorTool, language: AppLanguage): String = when (tool) {
    EditorTool.DOODLE -> localized(language, "涂鸦", "Draw")
    EditorTool.MOSAIC -> localized(language, "打码", "Redact")
    EditorTool.CROP -> localized(language, "裁切", "Crop")
}

internal fun inkLabel(color: InkColor, language: AppLanguage): String = when (color) {
    InkColor.RED -> localized(language, "红色", "Red")
    InkColor.AMBER -> localized(language, "琥珀", "Amber")
    InkColor.GREEN -> localized(language, "绿色", "Green")
    InkColor.BLUE -> localized(language, "蓝色", "Blue")
    InkColor.WHITE -> localized(language, "白色", "White")
    InkColor.INK -> localized(language, "墨黑", "Ink")
}

/** Ink swatches plus three pen weights. */
@Composable
internal fun DoodleOptions(
    ink: InkColor,
    weight: StrokeWeight,
    onInk: (InkColor) -> Unit,
    onWeight: (StrokeWeight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    // Two rows, not one. Six 44dp swatches plus three 44dp weights is 396dp before spacing, which
    // overflows a 411dp screen and clips the thickest pen outright — and 360dp phones and
    // fontScale 1.3 only make it worse.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        InkPalette.forEach { swatch ->
            val selected = swatch == ink
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(onClickLabel = inkLabel(swatch, language)) { onInk(swatch) }
                    .semantics { contentDescription = inkLabel(swatch, language) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (selected) 26.dp else 22.dp)
                        .clip(CircleShape)
                        .background(inkColor(swatch))
                        // Every swatch carries a ring, or INK is invisible on the black chrome.
                        // Selection thickens the ring and grows the dot rather than adding a tick,
                        // which is unreadable at this size.
                        .border(if (selected) 2.5.dp else 1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StrokeWeight.entries.forEach { step ->
            val selected = step == weight
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(onClickLabel = weightLabel(step, language)) { onWeight(step) }
                    .semantics { contentDescription = weightLabel(step, language) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(
                            when (step) {
                                StrokeWeight.THIN -> 6.dp
                                StrokeWeight.MEDIUM -> 11.dp
                                StrokeWeight.THICK -> 17.dp
                            },
                        )
                        .clip(CircleShape)
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.45f)),
                )
            }
        }
    }
    }
}

internal fun weightLabel(weight: StrokeWeight, language: AppLanguage): String = when (weight) {
    StrokeWeight.THIN -> localized(language, "细笔", "Thin")
    StrokeWeight.MEDIUM -> localized(language, "中等笔", "Medium")
    StrokeWeight.THICK -> localized(language, "粗笔", "Thick")
}

internal fun brushLabel(brush: BrushSize, language: AppLanguage): String = when (brush) {
    BrushSize.SMALL -> localized(language, "小号笔刷", "Small brush")
    BrushSize.MEDIUM -> localized(language, "中号笔刷", "Medium brush")
    BrushSize.LARGE -> localized(language, "大号笔刷", "Large brush")
}

/** Three brush sizes. Block size is proportional and deliberately not exposed. */
@Composable
internal fun MosaicOptions(brush: BrushSize, onBrush: (BrushSize) -> Unit, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrushSize.entries.forEach { step ->
            val selected = step == brush
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = brushLabel(step, language)) { onBrush(step) }
                    .semantics { contentDescription = brushLabel(step, language) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(
                            when (step) {
                                BrushSize.SMALL -> 14.dp
                                BrushSize.MEDIUM -> 22.dp
                                BrushSize.LARGE -> 30.dp
                            },
                        )
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.45f))
                        .scale(1f),
                )
            }
        }
    }
}

internal fun aspectLabel(aspect: CropAspect, language: AppLanguage): String = when (aspect) {
    CropAspect.FREE -> localized(language, "自由", "Free")
    CropAspect.ORIGINAL -> localized(language, "原图", "Original")
    CropAspect.SQUARE -> "1:1"
    CropAspect.FOUR_THREE -> "4:3"
    CropAspect.SIXTEEN_NINE -> "16:9"
}

/** Aspect presets. "Original" is here because trimming a status bar while keeping the shape is *the* screenshot case. */
@Composable
internal fun CropOptions(aspect: CropAspect, onAspect: (CropAspect) -> Unit, modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    // Scrollable rather than clipped: five chips fit at 411dp, but not at 360dp with fontScale 1.3,
    // and losing "16:9" off the edge is worse than a scroll the user may never need.
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CropAspect.entries.forEach { preset ->
            val selected = preset == aspect
            Surface(
                onClick = { onAspect(preset) },
                shape = RoundedCornerShape(18.dp),
                color = if (selected) Color.White.copy(alpha = 0.92f) else EditorScrim,
            ) {
                Text(
                    aspectLabel(preset, language),
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    color = if (selected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
