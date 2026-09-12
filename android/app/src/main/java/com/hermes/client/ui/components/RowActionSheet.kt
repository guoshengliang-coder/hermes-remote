package com.hermes.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.RowMenuAction
import com.hermes.client.ui.theme.RowMenuChipLabel
import com.hermes.client.ui.theme.RowMenuHint
import com.hermes.client.ui.theme.RowMenuTitle
import com.hermes.client.ui.theme.rowMenuChipBorderColor
import com.hermes.client.ui.theme.rowMenuChipColor
import com.hermes.client.ui.theme.rowMenuChipInkColor
import com.hermes.client.ui.theme.rowMenuCloseInkColor
import com.hermes.client.ui.theme.rowMenuDividerColor
import com.hermes.client.ui.theme.rowMenuHandleColor
import com.hermes.client.ui.theme.rowMenuInkFaintColor
import com.hermes.client.ui.theme.rowMenuSheetBorderColor
import com.hermes.client.ui.theme.rowMenuSheetColor

/**
 * The action sheet a LIST ROW opens on long-press — docs/DESIGN.md §5.5 行长按操作单, drawn from
 * Stitch 基线-会话列表页/长按下拉菜单 / 暗夜 (2026-09-12).
 *
 * Shared by the session list and the archived list on purpose. Before this existed each screen
 * hand-rolled the same `ModalBottomSheet` + `ListItem` stack, and they had already drifted: the
 * archived sheet's rows were a different height, and the session sheet's archive glyph was
 * Material's filled box while the same action in the top bar used the house stroke one. One
 * component means the next change reaches both.
 *
 * Rows are drawn by hand rather than with `ListItem`, for the reason the session row itself
 * stopped using it: the mock's row is 44dp (`py-3` around 20dp of content) and `ListItem` enforces
 * a 56dp one-line floor, which cannot be beaten from outside without clipping the text.
 *
 * The type chip is the one place this deliberately says more than the mock. The mock writes 「会话」
 * as a constant; here [typeLabel] follows the source, so the archived list reads 「已归档」 and the
 * chip carries information instead of decoration (product ruling 2026-09-12, recorded in
 * docs/design/stitch/stitch.lock.json).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    typeLabel: String,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = hermesSheetState(),
        // The mock's 24dp, not M3's 28dp default. Light is the page's OWN paper (§2.5 forbids
        // hardcoding a container colour to fix a cast; this one comes from the token family, and
        // the scrim plus the top hairline are what separate it from the list behind it).
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = rowMenuSheetColor(),
        dragHandle = { RowActionSheetHandle() },
    ) {
        RowActionSheetContent(typeLabel = typeLabel, title = title, onClose = onDismiss, content = content)
    }
}

/**
 * The hairline top edge and the 36×4dp grab bar, as one slot.
 *
 * The edge lives here rather than in the content because `dragHandle` is the only thing M3 draws
 * at the sheet's very top; a line placed further down would sit below the handle. It is clipped by
 * the 24dp corners, which is correct — a rounded rectangle's top edge IS only its straight middle.
 *
 * Not [SheetCloseHandle]: that one is the tap/pull-to-close bar §5.8 requires of sheets whose
 * content SCROLLS. This sheet is six rows tall, never scrolls, and carries an explicit ✕.
 */
@Composable
private fun RowActionSheetHandle() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(rowMenuSheetBorderColor()))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(rowMenuHandleColor()),
        )
        Spacer(Modifier.height(14.dp))
    }
}

/**
 * Everything below the grab bar.
 *
 * Separate from the [RowActionSheet] that hosts it for the reason `ThemeSheetContent` is: a sheet
 * renders in its own window, where `onRoot()` cannot reach it, so this is what the Roborazzi
 * goldens capture.
 */
@Composable
fun RowActionSheetContent(
    typeLabel: String,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = rowMenuChipColor(),
                border = BorderStroke(1.dp, rowMenuChipBorderColor()),
            ) {
                Text(
                    typeLabel,
                    style = RowMenuChipLabel,
                    color = rowMenuChipInkColor(),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = RowMenuTitle,
                color = MaterialTheme.colorScheme.onSurface,
                // One line, per the mock's `truncate`. §5.5's old rule allowed two; a wrapped
                // second line is what used to misalign against the action rows below.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(rowMenuChipColor())
                    .clickable(onClick = onClose)
                    .testTag("row-menu-close"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = localized(language, "关闭", "Close"),
                    tint = rowMenuCloseInkColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(1.dp)
                .background(rowMenuDividerColor()),
        )
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        // Clearance for the Android gesture bar (the mock's `h-6 pb-safe`; the window insets are
        // already applied by ModalBottomSheet).
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One action row: 44dp, a 20dp glyph, and at most one trailing thing.
 *
 * @param hint the right-aligned explanation 「可在归档箱恢复」/「不可撤销」.
 * @param value the right-aligned current value plus a chevron 「默认项目 ›」. Wins over [hint].
 * @param destructive paints the glyph, the label and the hint in `error`. Reserved for the
 *   IRREVERSIBLE (docs/DESIGN.md §5.2): archiving is reversible and stays neutral, or red would
 *   stop meaning anything.
 */
@Composable
fun RowActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    hint: String? = null,
    value: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val ink = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val glyph = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            // 8 + 20 = the mock's 28dp to the glyph, split so the press highlight is inset 8dp
            // and rounded 12dp the way the dark mock draws it.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = RowMenuAction, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        when {
            value != null -> {
                Text(
                    value,
                    style = RowMenuHint,
                    color = rowMenuInkFaintColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Capped so a long project name cannot squeeze the label it belongs to out of
                    // the row. The mock only ever shows a short one.
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = rowMenuInkFaintColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
            hint != null -> Text(
                hint,
                // The mock puts only the destructive hint on 500; the neutral ones stay 400.
                style = if (destructive) RowMenuHint.copy(fontWeight = FontWeight.Medium) else RowMenuHint,
                color = if (destructive) MaterialTheme.colorScheme.error else rowMenuInkFaintColor(),
                maxLines = 1,
            )
        }
    }
}

/** The hairline the mock puts above the destructive row, inset 28dp to match the glyph column. */
@Composable
fun RowActionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 6.dp)
            .height(1.dp)
            .background(rowMenuDividerColor()),
    )
}
