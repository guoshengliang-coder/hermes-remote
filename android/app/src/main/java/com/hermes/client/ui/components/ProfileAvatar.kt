package com.hermes.client.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.data.repository.AvatarStyle
import com.hermes.client.data.repository.ProfileIdentity
import com.hermes.client.data.repository.avatarInitialFor
import com.hermes.client.ui.theme.avatarColorArgb
import com.hermes.client.ui.theme.avatarOutlineColorArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Per-profile personalisation (from ProfileIdentityStore), provided at the app root. */
val LocalProfileIdentities = staticCompositionLocalOf<Map<String, ProfileIdentity>> { emptyMap() }

/** Where avatar photo files live (ProfileIdentityStore.avatarDir); null = photos unavailable. */
val LocalAvatarDir = staticCompositionLocalOf<File?> { null }

/** Everything the avatar needs, resolved once: photo wins, then custom colour, then the hash. */
data class AvatarLook(
    val initial: String,
    val colorArgb: Int,
    val style: AvatarStyle,
    val photo: File?,
)

fun resolveAvatarLook(profile: String?, identity: ProfileIdentity?, avatarDir: File?): AvatarLook {
    val photo = identity?.avatarFile
        ?.let { name -> avatarDir?.let { File(it, name) } }
        ?.takeIf { it.isFile }
    return AvatarLook(
        initial = avatarInitialFor(profile, identity),
        // Colour keys on the PROFILE name, never the display name — renaming keeps the colour,
        // and notifications (which only know the profile) agree with the avatar.
        colorArgb = identity?.colorArgb ?: avatarColorArgb(profile),
        style = identity?.style ?: AvatarStyle.SOLID,
        photo = photo,
    )
}

/**
 * The avatar token — the ONE place the per-profile identity colour appears. Three looks, in
 * priority order: the user's photo; a lettered circle (solid fill + white initial, or the
 * outline style: ring + coloured initial on the surface); the name-hashed solid fill. The solid
 * fill sits at a lightness where white text clears WCAG AA-large on every hue (see
 * avatarColorArgb), so no adaptive on-colour machinery is needed; the outline style lifts its
 * hue on dark surfaces instead (avatarOutlineColorArgb).
 */
@Composable
fun ProfileAvatar(
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    identity: ProfileIdentity? = name?.let { LocalProfileIdentities.current[it] },
) {
    val look = resolveAvatarLook(name, identity, LocalAvatarDir.current)
    val photo = look.photo?.let { rememberAvatarBitmap(it) }
    // The initial scales WITH the circle (cap height ≈ 35% of the diameter, i.e. font size
    // ≈ 0.47×size) instead of jumping between two fixed styles.
    val textStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = (size.value * 0.47f).sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).then(modifier),
        )
        return
    }
    if (look.style == AvatarStyle.OUTLINE) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val colour = Color(avatarOutlineColorArgb(look.colorArgb, dark))
        // Ring follows the stroke-icon weights: 2dp from 48dp up, 1.7dp below.
        val ring = if (size >= 48.dp) 2.dp else 1.7.dp
        Box(
            Modifier.size(size).clip(CircleShape).border(ring, colour, CircleShape).then(modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(look.initial, color = colour, style = textStyle)
        }
        return
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(look.colorArgb)).then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(look.initial, color = Color.White, style = textStyle)
    }
}

// Decoded avatar photos, keyed by path + mtime so a replaced file re-decodes. Small: one entry
// per profile photo, evicted wholesale when it grows past a handful.
private val avatarBitmaps = ConcurrentHashMap<String, ImageBitmap>()

@Composable
private fun rememberAvatarBitmap(file: File): ImageBitmap? {
    val key = file.path + "@" + file.lastModified()
    val state = produceState(initialValue = avatarBitmaps[key], key1 = key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
            }?.also {
                if (avatarBitmaps.size > 16) avatarBitmaps.clear()
                avatarBitmaps[key] = it
            }
        }
    }
    return state.value
}
