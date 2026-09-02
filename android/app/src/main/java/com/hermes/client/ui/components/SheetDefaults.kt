package com.hermes.client.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

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
