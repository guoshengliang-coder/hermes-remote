package com.hermes.client.ui.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HG-25. The library sizes an inline image for the case where the image is the content: with no
 * known intrinsic size it reserves a 200dp — or whole-container — square. Inside a 110dp table cell
 * that is the row. These pin the sizing rule that keeps an inline icon on its line.
 */
class InlineMarkdownImageTransformerTest {
    private val density = Density(1f) // 1px == 1dp, so the numbers below read as both
    private val lineBox = 17f

    private object NoFiles : MarkdownImageLoader {
        override fun cached(url: String): File? = null
        override suspend fun load(url: String): File? = null
    }

    private fun transformer() = InlineMarkdownImageTransformer(NoFiles, allowFetch = true, lineHeightPx = lineBox)

    private fun config(imageSize: Size, containerSize: Size = Size(110f, 400f)) =
        transformer().placeholderConfig(
            link = "https://example.com/icon.png",
            density = density,
            containerSize = containerSize,
            imageWidth = ImageWidth.IMAGE_WIDTH,
            imageSize = imageSize,
            imageSizeChanged = null,
        )

    /**
     * The regression that mattered: before the first load there is no intrinsic size, and the
     * library's fallback reserves min(200dp, container) — a cell-wide block for an icon that has
     * not arrived yet, which then reflows the whole table when it does.
     */
    @Test fun an_image_that_has_not_loaded_yet_reserves_one_line_box_not_the_cell() {
        val size = config(Size.Unspecified).size
        assertEquals(lineBox, size.width, 0.01f)
        assertEquals(lineBox, size.height, 0.01f)
    }

    @Test fun a_tall_image_is_scaled_down_to_the_line_box_keeping_its_aspect() {
        val size = config(Size(128f, 64f)).size // 2:1, four line boxes tall
        assertEquals(lineBox, size.height, 0.01f)
        assertEquals(lineBox * 2f, size.width, 0.01f)
    }

    @Test fun an_image_already_smaller_than_the_line_box_is_left_alone() {
        val size = config(Size(10f, 8f)).size
        assertEquals(10f, size.width, 0.01f)
        assertEquals(8f, size.height, 0.01f)
    }

    /**
     * A degenerate size must not divide by zero or reserve nothing — a zero-height Placeholder is
     * not something Compose will lay out.
     */
    @Test fun a_degenerate_size_falls_back_to_the_line_box() {
        val size = config(Size(0f, 0f)).size
        assertTrue(size.width > 0f && size.height > 0f)
        assertEquals(lineBox, size.height, 0.01f)
    }

    /** Beside CJK text a bottom-aligned icon reads as dropped; it sits on the text's centre. */
    @Test fun the_placeholder_is_centred_on_the_text_line() {
        assertEquals(PlaceholderVerticalAlign.TextCenter, config(Size(16f, 16f)).verticalAlign)
    }
}
