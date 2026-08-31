package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalAvatarColors
import com.hermes.client.ui.theme.avatarColorArgb

/**
 * A lettered avatar token — the ONE place the per-profile hashed hue appears. Solid fill at a
 * lightness where white text clears WCAG AA-large on every hue (see avatarColorArgb), so no
 * adaptive on-colour machinery is needed.
 */
@Composable
fun ProfileAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    // A user-chosen colour (device-local) wins; otherwise the name-hashed auto colour.
    val argb = name?.let { LocalAvatarColors.current[it] } ?: avatarColorArgb(name)
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(argb)).then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (name?.takeIf { it.isNotBlank() } ?: "·").take(1).uppercase(),
            color = Color.White,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}
