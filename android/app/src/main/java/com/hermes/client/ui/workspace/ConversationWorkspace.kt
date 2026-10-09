package com.hermes.client.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.window.layout.FoldingFeature
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

val LocalChatWidth = staticCompositionLocalOf { 0f }
val LocalWorkspaceSplit = staticCompositionLocalOf { false }
val LocalToggleSessionPane = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The content slot is always composed once in the same position, including single-column mode. */
@Composable
fun ConversationWorkspace(
    enabled: Boolean,
    showingList: Boolean,
    preference: WorkspacePreference,
    onWidth: (Float) -> Unit,
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val language = LocalAppLanguage.current
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var draggingWidth by remember { mutableStateOf<Float?>(null) }
    val feature = foldingFeature()
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        val budget = workspaceBudget(maxWidth.value, density.fontScale, preference)
        val vertical = feature?.orientation == FoldingFeature.Orientation.VERTICAL
        val hinge = feature?.let {
            WorkspaceHinge(
                ((if (vertical) it.bounds.left else it.bounds.top) - (if (vertical) origin.x else origin.y)) / density.density,
                ((if (vertical) it.bounds.right else it.bounds.bottom) - (if (vertical) origin.x else origin.y)) / density.density,
                vertical,
            )
        }
        val extent = if (hinge?.vertical == false) maxHeight.value else maxWidth.value
        val division = workspaceRegion(extent, density.fontScale, enabled && budget.split, hinge)
        val split = enabled && budget.split && (hinge == null || division.listWidth != null)
        val region = workspaceRegion(extent, density.fontScale, split && (showingList || !collapsed), hinge)
        val showList = enabled && (showingList || (split && !collapsed))
        val width = region.listWidth ?: (draggingWidth?.let(budget::clamp) ?: budget.listWidth)
        val currentWidth by rememberUpdatedState(width)
        val persistWidth by rememberUpdatedState(onWidth)
        val gap = if (region.listWidth != null) region.gap else 24f
        val regionWidth = if (hinge?.vertical == true) region.extent else maxWidth.value
        val paneWidth = if (showList && split) regionWidth - width - gap else regionWidth
        val toggle: (() -> Unit)? = if (split && !showingList) ({ collapsed = !collapsed }) else null
        val regionModifier = if (hinge?.vertical == false) Modifier.offset(y = region.offset.dp).fillMaxWidth().height(region.extent.dp)
            else Modifier.offset(x = region.offset.dp).width(regionWidth.dp).fillMaxHeight()
        Row(regionModifier) {
            Box(Modifier.width(if (showList) (if (split) width.dp else regionWidth.dp) else 0.dp).fillMaxHeight()) {
                if (showList) list()
            }
            Box(Modifier.width(if (showList && split) gap.dp else 0.dp).fillMaxHeight()) {
                if (showList && split && hinge == null) {
                    val label = localized(language, "调整会话栏宽度", "Resize conversation list")
                    val smaller = localized(language, "缩小会话栏", "Narrow conversation list")
                    val larger = localized(language, "加宽会话栏", "Widen conversation list")
                    val reset = localized(language, "恢复默认宽度", "Reset width")
                    Box(
                        Modifier.fillMaxSize().testTag("workspace-divider")
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.getSystemIcon(
                                androidx.compose.ui.platform.LocalContext.current, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)))
                            .semantics {
                                contentDescription = label
                                stateDescription = "${width.toInt()} dp"
                                progressBarRangeInfo = ProgressBarRangeInfo(width, budget.minimum..budget.maximum)
                                setProgress { onWidth(budget.clamp(it)); true }
                                customActions = listOf(
                                    CustomAccessibilityAction(smaller) { onWidth(budget.clamp(width - 24f)); true },
                                    CustomAccessibilityAction(larger) { onWidth(budget.clamp(width + 24f)); true },
                                    CustomAccessibilityAction(reset) { onWidth(300f); true },
                                )
                            }
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionLeft -> { onWidth(budget.clamp(width - 24f)); true }
                                    Key.DirectionRight -> { onWidth(budget.clamp(width + 24f)); true }
                                    Key.Home -> { onWidth(300f); true }
                                    else -> false
                                }
                            }.focusable()
                            .pointerInput(budget.minimum, budget.maximum) {
                                detectHorizontalDragGestures(
                                    onDragStart = { draggingWidth = currentWidth },
                                    onDragEnd = { draggingWidth?.let(persistWidth); draggingWidth = null },
                                    onDragCancel = { draggingWidth = null },
                                ) { change, delta ->
                                    change.consume()
                                    draggingWidth = budget.clamp((draggingWidth ?: currentWidth) + delta / density.density)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                        Box(Modifier.width(4.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(
                    LocalWorkspaceSplit provides split,
                    LocalToggleSessionPane provides toggle,
                    LocalChatWidth provides minOf(paneWidth, 720f),
                ) { content() }
            }
        }
    }
}
