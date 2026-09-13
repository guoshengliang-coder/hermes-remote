package com.hermes.client.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.ui.components.ExternalLinkIcon
import com.hermes.client.ui.components.rememberSafeUriHandler
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

/**
 * Which surface is drawing assistant Markdown. It decides only the things that legitimately
 * differ between surfaces; everything else [HermesMarkdown] supplies identically, on purpose.
 */
internal enum class MarkdownSurface {
    /** The live conversation. */
    CHAT,

    /** The fullscreen table dialog. On screen and interactive, so it behaves like [CHAT]. */
    FULLSCREEN,

    /**
     * Long-image share and table PNG. Renders from cache and never downloads (DESIGN.md §5.13),
     * and carries no search highlight — that mark says "this is where your search matched right
     * now", which is not a fact about the conversation and has no business in a saved image.
     */
    EXPORT,
}

/**
 * Every renderer of assistant Markdown in the app, so that they cannot drift apart.
 *
 * There were five `Markdown(...)` call sites and only one of them — the conversation — passed the
 * annotator and the inline-content map. The other four each lost something a reader was supposed
 * to get, and one of them lost something a reader was supposed to be protected from:
 *
 *  - the external-link glyph (DESIGN.md §5.4 makes it one of three redundant encodings for
 *    "this is a link", because in CJK body text colour and underline are not enough on their own)
 *    was missing from the fullscreen table, both table exports and the shared long image;
 *  - **links opened through Compose's default `UriHandler`** anywhere but the conversation. That
 *    handler opens whatever scheme it is handed — `intent:` can name an arbitrary component,
 *    `file:` can point into local storage — and rethrows `ActivityNotFoundException` as
 *    `IllegalArgumentException`, i.e. it crashes on a device with no browser. The allowlist and
 *    the `HR-LINK-001` / `HR-LINK-002` recovery in `AppLinks.kt` were reachable from exactly one
 *    of the five. Table cells are model output, so this was the wrong one to leave open.
 *
 * The argument list is deliberately short: `typography`, `components` and `dimens` genuinely
 * differ per surface — a table export sets a wider cell than the card in the chat does — while
 * colours, the annotator, the inline-content map, the image transformer and the URI handler must
 * not, and so are not offered as parameters at all.
 *
 * Exactly one of [content] and [state] must be given; the parsed-state form is for callers that
 * need `immediate = true` parsing.
 */
@Composable
internal fun HermesMarkdown(
    surface: MarkdownSurface,
    typography: MarkdownTypography,
    modifier: Modifier = Modifier,
    content: String? = null,
    state: MarkdownState? = null,
    components: MarkdownComponents = markdownComponents(),
    dimens: MarkdownDimens = markdownDimens(),
    padding: MarkdownPadding = markdownPadding(),
) {
    require((content == null) != (state == null)) {
        "HermesMarkdown takes either content or a parsed state, not both and not neither"
    }
    val colors = markdownColor(
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
    )
    val annotator = rememberHermesAnnotator(highlightSearch = surface != MarkdownSurface.EXPORT)
    val inlineContent = rememberLinkIconContent()
    val imageTransformer = rememberInlineImageTransformer(allowFetch = surface != MarkdownSurface.EXPORT)
    // The renderer captures LocalUriHandler when it builds the link annotations, so the guarded
    // handler has to be in scope around Markdown() rather than at the tap site.
    CompositionLocalProvider(LocalUriHandler provides rememberSafeUriHandler()) {
        if (state != null) {
            Markdown(
                markdownState = state,
                modifier = modifier,
                colors = colors,
                typography = typography,
                components = components,
                annotator = annotator,
                inlineContent = inlineContent,
                imageTransformer = imageTransformer,
                padding = padding,
                dimens = dimens,
            )
        } else {
            Markdown(
                content = requireNotNull(content),
                modifier = modifier,
                colors = colors,
                typography = typography,
                components = components,
                annotator = annotator,
                inlineContent = inlineContent,
                imageTransformer = imageTransformer,
                padding = padding,
                dimens = dimens,
            )
        }
    }
}

/**
 * Colour AND underline AND a leading glyph. In CJK body text an underlined run is nearly
 * indistinguishable from `**bold**`, and colour alone is not an accessible-enough signal.
 */
@Composable
internal fun hermesLinkStyles(): TextLinkStyles {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(linkColor) {
        TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(color = linkColor.copy(alpha = 0.7f), textDecoration = TextDecoration.Underline),
        )
    }
}

/**
 * Held across recompositions on purpose: [InlineTextContent] has no `equals()`, so rebuilding this
 * map on every streaming tick would hand Markdown a "changed" argument each frame. The placeholder
 * is 17sp square while the glyph is 13sp, which is where the gap between icon and link text comes
 * from; both are sp so the pair tracks the system font scale.
 */
@Composable
private fun rememberLinkIconContent(): MarkdownInlineContent {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(linkColor) {
        DefaultMarkdownInlineContent(
            mapOf(
                MARKDOWN_LINK_ICON_TAG to InlineTextContent(
                    Placeholder(17.sp, 17.sp, PlaceholderVerticalAlign.TextCenter),
                ) {
                    Icon(
                        ExternalLinkIcon,
                        contentDescription = null,
                        modifier = Modifier.size(with(LocalDensity.current) { 13.sp.toDp() }),
                        tint = linkColor,
                    )
                },
            ),
        )
    }
}

/**
 * Two annotators share this one slot. They are disjoint — search highlighting only claims TEXT
 * tokens, the glyph only reacts to link nodes — so the link pass runs first and always defers,
 * then search decides whether it handled the node.
 */
@Composable
private fun rememberHermesAnnotator(highlightSearch: Boolean): MarkdownAnnotator {
    val searchAnnotator = rememberSearchAnnotator()
    return remember(searchAnnotator, highlightSearch) {
        markdownAnnotator(config = if (highlightSearch) searchAnnotator.config else markdownAnnotatorConfig()) { content, child ->
            if (shouldPrefixLinkIcon(child)) {
                appendInlineContent(MARKDOWN_LINK_ICON_TAG, "\uFFFC")
                // WORD JOINER: without it the line breaker treats the glyph as its own word and
                // happily leaves it stranded at the end of the previous line.
                append('\u2060')
            }
            if (highlightSearch) searchAnnotator.annotate?.invoke(this, content, child) ?: false else false
        }
    }
}

/** Table body type. The export sets a looser line because it also sets a wider cell. */
@Composable
internal fun hermesTableTextStyle(exportScale: Boolean = false): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = if (exportScale) 24.sp else 23.sp,
    )

internal const val MARKDOWN_LINK_ICON_TAG = "hermes-link-icon"

/** Table geometry in the conversation, and in the component gallery that mirrors it. */
internal val CHAT_TABLE_CELL_WIDTH = 110.dp
internal val CHAT_TABLE_CELL_PADDING = 8.dp

/** Roomier, because the fullscreen dialog and the PNG export both have the width to spare. */
internal val EXPORT_TABLE_CELL_PADDING = 10.dp
