package com.hermes.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The one sheet state every Hermes bottom sheet must use (docs/DESIGN.md §5): partial expansion
 * is skipped, so a sheet is exactly as tall as its content and a taller-than-half-screen sheet
 * opens FULLY expanded instead of stopping half-way with content cut off. Pass
 * [confirmValueChange] to additionally constrain transitions (e.g. the approval sheet forbids
 * swipe-dismiss so a decision can't be lost by a stray drag).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun hermesSheetState(
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState = rememberModalBottomSheetState(
    skipPartiallyExpanded = true,
    confirmValueChange = confirmValueChange,
)

/**
 * The only touch route to dismissing a sheet whose content scrolls (docs/DESIGN.md §5.8, global
 * rule since 2026-09-03): tap the grab bar, or pull it down a short distance. Pair it with
 * `sheetGesturesEnabled = false` so scrolling the list can never collapse or close the sheet.
 * Deliberately simple — no follow-the-finger animation.
 */
@Composable
fun SheetCloseHandle(onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    val thresholdPx = with(LocalDensity.current) { SHEET_CLOSE_DRAG_DP.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = localized(language, "关闭", "Close")) { onDismiss() }
            .pointerInput(Unit) {
                var dragTotal = 0f
                var fired = false
                detectVerticalDragGestures(
                    onDragStart = { dragTotal = 0f; fired = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragTotal += dragAmount
                        if (!fired && dragTotal > thresholdPx) {
                            fired = true
                            onDismiss()
                        }
                    },
                )
            }
            .padding(top = 14.dp, bottom = 10.dp)
            .testTag("sheet-close-handle"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(2.dp),
        ) { Box(Modifier.size(32.dp, 4.dp)) }
    }
}

/** Pull-down distance on the grab bar that closes the sheet. */
const val SHEET_CLOSE_DRAG_DP = 48
