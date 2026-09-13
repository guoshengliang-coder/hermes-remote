package com.hermes.client.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import java.io.File

/**
 * Fetches the bytes behind an inline Markdown image. Narrow on purpose: the renderer has no
 * business knowing about profiles, the Relay or the attachment cache, and the export path needs a
 * loader that is physically unable to reach the network.
 */
interface MarkdownImageLoader {
    /** The cached file for [url], or null if it has not been fetched in this install. */
    fun cached(url: String): File?

    /** The cached file for [url], fetching it if necessary. Null when it cannot be had. */
    suspend fun load(url: String): File?
}

/**
 * What a renderer with no loader wired in sees. The component gallery, the Roborazzi goldens and
 * any future surface that draws Markdown without a chat behind it all land here and simply draw
 * no inline images, which is what they did before this existed.
 */
object NoMarkdownImages : MarkdownImageLoader {
    override fun cached(url: String): File? = null
    override suspend fun load(url: String): File? = null
}

val LocalMarkdownImageLoader = staticCompositionLocalOf<MarkdownImageLoader> { NoMarkdownImages }

/**
 * Renders inline Markdown images at the size of the text around them.
 *
 * The library's own placeholder sizing is built for images that are the content — with no known
 * intrinsic size it reserves a 200dp (or whole-container) square. Inside a 110dp table cell that
 * turns a 16px GitHub mark into a block that owns the row. Everything here exists to say: an image
 * sharing a line with text is punctuation, not content. [Mappers] has already hoisted the images
 * that really are content into the message's image grid, so everything reaching this transformer
 * is inline by construction.
 *
 * Non-`https` links never load. The fetch goes through the credential-free, SSRF-guarded client in
 * `ChatMediaRepository` — an assistant can put any URL in a table cell, so this must never be able
 * to reach a private address, and must never carry a Relay credential to a third party.
 */
class InlineMarkdownImageTransformer(
    private val loader: MarkdownImageLoader,
    /** False on the export path: DESIGN.md §5.13 — an export renders what is cached and downloads nothing. */
    private val allowFetch: Boolean,
    /** Line box, in px. Inline images are clamped to it so a cell keeps its height. */
    private val lineHeightPx: Float,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (!link.startsWith("https://")) return null
        val loader = this.loader
        var file by remember(link) { mutableStateOf(loader.cached(link)) }
        LaunchedEffect(link) {
            if (file == null && allowFetch) file = loader.load(link)
        }
        val painter = rememberFilePainter(file) ?: return null
        return ImageData(painter = painter, contentDescription = null)
    }

    /**
     * Reported clamped, and that does two jobs at once. It keeps the drawn icon on the text's line,
     * and it keeps the library's `shouldPromote` heuristic — which turns any inline image taller
     * than a couple of lines into a block — from lifting a table-cell icon out of its cell.
     */
    @Composable
    override fun intrinsicSize(painter: Painter): Size = clamp(painter.intrinsicSize)

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        // Before the first load there is no intrinsic size, and the library would fall back to a
        // square the size of the container. Reserve one line box instead: an icon that has not
        // arrived yet must not reflow the table on its way in.
        val sizePx = if (imageSize.isUnspecified) Size(lineHeightPx, lineHeightPx) else clamp(imageSize)
        val sizeDp = with(density) { Size(sizePx.width.toDp().value, sizePx.height.toDp().value) }
        // Centred rather than the library's Bottom: an icon beside CJK text reads as dropped when
        // its baseline is the text's descender line.
        return PlaceholderConfig(sizeDp, PlaceholderVerticalAlign.TextCenter)
    }

    private fun clamp(size: Size): Size {
        if (size.isUnspecified || size.height <= 0f || size.width <= 0f) {
            return Size(lineHeightPx, lineHeightPx)
        }
        if (size.height <= lineHeightPx) return size
        val ratio = lineHeightPx / size.height
        return Size(size.width * ratio, lineHeightPx)
    }
}

/**
 * Decodes [file] once per path. Held in composition rather than recreated per frame: a streaming
 * answer recomposes its Markdown many times a second and re-decoding there would be ruinous.
 */
@Composable
private fun rememberFilePainter(file: File?): Painter? {
    if (file == null) return null
    return remember(file.absolutePath, file.length()) {
        decodeSampled(ImageSource.Path(file.absolutePath), MAX_INLINE_IMAGE_PX)
            ?.let(::BitmapPainter)
    }
}

/**
 * The transformer for the live chat. [allowFetch] is false on export surfaces, which must render
 * from the cache and never start a download (DESIGN.md §5.13).
 */
@Composable
fun rememberInlineImageTransformer(allowFetch: Boolean = true): ImageTransformer {
    val loader = LocalMarkdownImageLoader.current
    val lineHeightPx = with(LocalDensity.current) { INLINE_IMAGE_LINE_BOX.toPx() }
    return remember(loader, allowFetch, lineHeightPx) {
        InlineMarkdownImageTransformer(loader, allowFetch, lineHeightPx)
    }
}

/**
 * One line box for an inline image. Matches the 17sp body and the external-link glyph's own 17sp
 * placeholder, so an icon, a link mark and the text they sit between share one optical line; being
 * in sp, all three track the system font scale together.
 */
private val INLINE_IMAGE_LINE_BOX = 17.sp

/** An inline icon is never drawn large; decoding beyond this only costs memory. */
private const val MAX_INLINE_IMAGE_PX = 256
