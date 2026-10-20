package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownExtendedSpans
import com.mikepenz.markdown.compose.elements.MarkdownText
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

internal const val SEARCH_FOCUS_TAG = "hr-search-focus"

/** A real text-layout rectangle, never an estimated fraction of an oversized LazyColumn item. */
internal class SearchTextTarget(private val enabled: Boolean) {
    val requester = BringIntoViewRequester()
    var rectangle: Rect? by mutableStateOf(null)
        private set
    var placed by mutableStateOf(false)
        private set
    val modifier: Modifier = if (enabled) Modifier
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { placed = true } else Modifier

    fun onTextLayout(result: TextLayoutResult) {
        if (!enabled) return
        val text = result.layoutInput.text
        val mark = text.getStringAnnotations(SEARCH_FOCUS_TAG, 0, text.length).firstOrNull()
        rectangle = mark?.takeIf { it.start < text.length }?.let { result.getPathForRange(it.start, it.end).getBounds() }
    }
}

@Composable
internal fun rememberSearchTextTarget(): SearchTextTarget {
    val context = LocalChatSearch.current.takeIf { LocalTurnIsCurrentHit.current }
    val target = remember(context) { SearchTextTarget(context != null) }
    val pendingParses = LocalChatViewportController.current?.pendingParses ?: 0
    LaunchedEffect(target, target.rectangle, target.placed, pendingParses) {
        val rectangle = target.rectangle ?: return@LaunchedEffect
        if (!target.placed || pendingParses > 0) return@LaunchedEffect
        // The parent first mounts the matched turn. A following frame gives Markdown and the
        // reverse-layout list their final coordinates; a changed layout restarts this request.
        withFrameNanos { }
        target.requester.bringIntoView(rectangle)
    }
    return target
}

/** The library's text rendering, with its original styles/links/images and a layout callback. */
@Composable
internal fun SearchableMarkdownText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val settings = annotatorSettings()
    val styledText = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        if (node.children.isEmpty()) {
            buildMarkdownAnnotatedString(content, listOf(node), settings)
        } else {
            buildMarkdownAnnotatedString(content, node, settings)
        }
        pop()
    }
    val target = rememberSearchTextTarget()
    MarkdownText(
        content = styledText,
        node = node,
        modifier = modifier.then(target.modifier),
        style = style,
        sourceContent = content,
        extendedSpans = LocalMarkdownExtendedSpans.current.extendedSpans?.invoke(),
        onTextLayout = { result, _ -> target.onTextLayout(result) },
    )
}

@Composable
internal fun SearchableMarkdownHeader(content: String, node: ASTNode, style: TextStyle, setext: Boolean = false) {
    val type = if (setext) MarkdownTokenTypes.SETEXT_CONTENT else MarkdownTokenTypes.ATX_CONTENT
    val child = node.children.firstOrNull { it.type == type } ?: node
    SearchableMarkdownText(content, child, style, Modifier.semantics { heading() })
}

/** Same table cell widths/padding and accessibility indices as the normal renderer. */
@Composable
internal fun SearchableMarkdownTableRow(
    content: String,
    node: ASTNode,
    width: androidx.compose.ui.unit.Dp,
    style: TextStyle,
    header: Boolean = false,
) {
    val padding = com.mikepenz.markdown.compose.LocalMarkdownDimens.current.tableCellPadding
    val row = if (header) 0 else com.mikepenz.markdown.compose.elements.LocalTableRowIndex.current
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.widthIn(min = width),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        node.children.filter { it.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL }
            .forEachIndexed { column, cell ->
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(padding).weight(1f).semantics {
                        if (header) heading()
                        collectionItemInfo = androidx.compose.ui.semantics.CollectionItemInfo(row, 1, column, 1)
                    },
                ) { SearchableMarkdownText(content, cell, style) }
            }
    }
}
