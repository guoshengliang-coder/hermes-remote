package com.hermes.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * What a [SearchField] looks like. One component, two sets of values (docs/DESIGN.md §5.2 / §5.4).
 *
 * The search page and the in-chat bar were one shape until the 2026-09-12 Stitch pull drew the
 * chat bar as a full-round pill that swallows its own counter and navigation. Rather than fork the
 * component — and with it the IME action, the cursor colour, the placeholder and the clear button,
 * which the two still agree on — the values that differ live here. [Default] is the search page's,
 * unchanged; the chat bar builds its own in `ChatSearchBar.kt` from that screen's tokens.
 */
data class SearchFieldStyle(
    val shape: Shape,
    val containerColor: Color,
    val border: BorderStroke?,
    val leadingTint: Color,
    val textStyle: TextStyle,
    val placeholderColor: Color,
    val clearButtonSize: Dp,
    val clearDiscSize: Dp,
    val clearIconSize: Dp,
    val clearTint: Color,
    val clearBackground: Color,
    val endPadding: Dp,
) {
    companion object {
        /** 搜索页 (§5.2): filled `surfaceVariant`, 18dp corners, 36dp clear button. */
        @Composable
        fun Default(): SearchFieldStyle = SearchFieldStyle(
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            border = null,
            leadingTint = MaterialTheme.colorScheme.onSurfaceVariant,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            clearButtonSize = 36.dp,
            clearDiscSize = 36.dp,
            clearIconSize = 18.dp,
            clearTint = MaterialTheme.colorScheme.onSurfaceVariant,
            clearBackground = Color.Transparent,
            endPadding = 2.dp,
        )
    }
}

/**
 * The app's search field (docs/DESIGN.md §5.2 搜索页 / §5.4 聊天内搜索): 40dp tall, filled, leading
 * search glyph, trailing clear button while there is text. IME action is Search; [onSearch] fires
 * on it. Appearance comes from [style]; [trailing] puts extra controls INSIDE the field, which is
 * how the chat bar carries its hit counter and up/down arrows.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    style: SearchFieldStyle = SearchFieldStyle.Default(),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val language = LocalAppLanguage.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .height(40.dp)
            .clip(style.shape)
            .background(style.containerColor)
            .then(style.border?.let { Modifier.border(it, style.shape) } ?: Modifier),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = style.endPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = style.leadingTint,
                    modifier = Modifier.size(20.dp),
                )
                Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = style.textStyle,
                            color = style.placeholderColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(style.clearButtonSize)) {
                        // The disc is drawn at its own size inside the touch target: the chat mock
                        // draws a 16dp dot, and a 16dp target would be the smallest thing to hit in
                        // the app by a wide margin. Drawing and touching are separate numbers here.
                        Box(
                            Modifier
                                .size(style.clearDiscSize)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(style.clearBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = localized(language, "清除", "Clear"),
                                tint = style.clearTint,
                                modifier = Modifier.size(style.clearIconSize),
                            )
                        }
                    }
                }
                trailing()
            }
        },
    )
}
