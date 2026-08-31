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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // The initial scales WITH the circle (reference: cap height ≈ 35% of the diameter,
            // i.e. font size ≈ 0.47×size) instead of jumping between two fixed styles that left
            // large avatars with a tiny letter.
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (size.value * 0.47f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
