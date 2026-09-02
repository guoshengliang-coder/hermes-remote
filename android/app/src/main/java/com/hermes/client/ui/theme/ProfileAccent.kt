package com.hermes.client.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Per-profile identity colour. Each profile (tenant) deterministically maps to a hue. Since the
// single-screen redesign, scope is the AVATAR ONLY: chrome (top bar, FAB, group headers) uses the
// brand palette, and identity is carried by the avatar's solid hashed colour. The lightness is
// fixed low enough (0.32) that white text clears WCAG AA-large (3.0:1) on EVERY hue — the worst
// case across all 360 hues is ~3.96:1 — so no adaptive on-colour machinery is needed.
//
// The math is pure (ARGB Int in/out) so it unit-tests without Compose or an Android runtime.

/** Fallback hue for Hermes Remote — the icon blue the brand palette is built on. */
internal const val DEFAULT_ACCENT_HUE = 215f

private const val AVATAR_SATURATION = 0.62f
private const val AVATAR_LIGHTNESS = 0.32f

/**
 * Deterministic hue in [0,360) from a profile name: FNV-1a for the string, then a murmur3
 * fmix32 avalanche so that similar names (e.g. "personal" vs "odos") decorrelate into
 * visibly distinct hues rather than clustering. The final unsigned modulo keeps the spread
 * even (a signed modulo would re-cluster ~half the space). Distinctness is guaranteed-tested
 * for the known tenant set; for arbitrarily many profiles occasional near-collisions are
 * mathematically unavoidable (birthday problem) and acceptable — this is a hint, not an ID.
 */
internal fun profileHue(name: String): Float {
    var h = -0x7ee3623b // 2166136261 (FNV offset basis) as a signed Int
    for (c in name) {
        h = h xor c.code
        h *= 0x01000193 // FNV prime
    }
    h = h xor (h ushr 16)
    h *= 0x85ebca6b.toInt()
    h = h xor (h ushr 13)
    h *= 0xc2b2ae35.toInt()
    h = h xor (h ushr 16)
    val unsigned = h.toLong() and 0xFFFFFFFFL
    return (unsigned % 360L).toFloat()
}

/** Solid avatar fill for [profile] — the one place the hashed identity hue appears. */
fun avatarColorArgb(profile: String?): Int {
    val hue = if (profile.isNullOrBlank()) DEFAULT_ACCENT_HUE else profileHue(profile)
    return hslToArgb(hue, AVATAR_SATURATION, AVATAR_LIGHTNESS)
}

/**
 * Lightness the OUTLINE avatar style lifts its hue to on a dark surface. The solid fill's 0.32
 * is invisible against `#121921`; 0.70 keeps every hue (s = 0.62) at ≥ 5.3:1 there, so the ring
 * and the coloured initial clear AA for normal text — pinned by ProfileAccentTest.
 */
private const val OUTLINE_DARK_LIGHTNESS = 0.70f

/**
 * Ring + initial colour for the outline style. Light surfaces use the identity colour as-is
 * (l = 0.32 clears ≥ 3.9:1 on white); dark surfaces keep the hue and saturation but lift the
 * lightness. The solid style never goes through here — its fill is the same in both themes.
 */
fun avatarOutlineColorArgb(argb: Int, dark: Boolean): Int {
    if (!dark) return argb
    val (h, s, _) = argbToHsl(argb)
    return hslToArgb(h, s, OUTLINE_DARK_LIGHTNESS)
}

/**
 * Snapshot of per-profile personalisation for readers outside Compose (the notification
 * renderer). The DI layer keeps it current from ProfileIdentityStore; nothing here blocks on I/O.
 */
object ProfileIdentitySnapshot {
    @Volatile var identities: Map<String, com.hermes.client.data.repository.ProfileIdentity> = emptyMap()
}

/**
 * Tenant identity colour for a notification: the profile's avatar colour — the user's pick when
 * there is one, else the hashed hue — so a card from a non-active profile still reads as "whose".
 */
fun avatarAccentArgb(profile: String): Int =
    ProfileIdentitySnapshot.identities[profile]?.colorArgb ?: avatarColorArgb(profile)

/** The avatar colour for a slider-picked hue: saturation and lightness stay locked. */
fun avatarColorForHue(hue: Float): Int = hslToArgb(hue, AVATAR_SATURATION, AVATAR_LIGHTNESS)

/** Hue in [0,360) of an avatar colour — positions the hue slider's thumb. */
fun avatarHueOf(argb: Int): Float = argbToHsl(argb)[0]

internal fun argbToHsl(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h = when (max) {
        r -> (g - b) / d + (if (g < b) 6f else 0f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    h *= 60f
    if (h >= 360f) h -= 360f
    return floatArrayOf(h, s, l)
}

/**
 * Curated avatar swatches: ten hues plus a neutral and near-black, all at the avatar
 * lightness so white initials clear AA-large on every pick.
 */
val AVATAR_SWATCHES: List<Int> = listOf(
    hslToArgb(20f, 0.62f, 0.32f),
    hslToArgb(65f, 0.62f, 0.32f),
    hslToArgb(105f, 0.62f, 0.32f),
    hslToArgb(140f, 0.62f, 0.32f),
    hslToArgb(180f, 0.62f, 0.32f),
    hslToArgb(214f, 0.62f, 0.32f),
    hslToArgb(258f, 0.62f, 0.32f),
    hslToArgb(292f, 0.62f, 0.32f),
    hslToArgb(338f, 0.62f, 0.32f),
    hslToArgb(0f, 0.62f, 0.32f),
    0xFF5B6763.toInt(),
    0xFF17201D.toInt(),
)

internal fun hslToArgb(hDeg: Float, s: Float, l: Float): Int {
    val h = ((hDeg % 360f) + 360f) % 360f / 360f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val r = hueToChannel(p, q, h + 1f / 3f)
    val g = hueToChannel(p, q, h)
    val b = hueToChannel(p, q, h - 1f / 3f)
    return (0xFF shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
}

private fun hueToChannel(p: Float, q: Float, tIn: Float): Float {
    var t = tIn
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 1f / 2f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

// --- Transitional brand-accent bundle -------------------------------------------------------
// Only the screens slated for deletion in the nav rework (Mission Control, You hub, FeedTabs)
// still read LocalProfileAccent. It now always carries the BRAND accent; this whole section is
// deleted together with those screens.

/** Compose-facing accent bundle. Transitional — see note above. */
data class ProfileAccentColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

/** Brand (mint) accent bundle for the given theme. */
fun brandAccentColors(dark: Boolean): ProfileAccentColors =
    if (dark) ProfileAccentColors(
        accent = Color(hslToArgb(DEFAULT_ACCENT_HUE, 0.52f, 0.62f)),
        onAccent = Color(0xFF00306A),
        container = Color(hslToArgb(DEFAULT_ACCENT_HUE, 0.38f, 0.24f)),
        onContainer = Color(0xFFD6E3FF),
    ) else ProfileAccentColors(
        accent = Color(hslToArgb(DEFAULT_ACCENT_HUE, 0.62f, 0.44f)),
        onAccent = Color.White,
        container = Color(hslToArgb(DEFAULT_ACCENT_HUE, 0.42f, 0.90f)),
        onContainer = Color(0xFF001B3D),
    )

/** Available anywhere in the tree; always the brand accent now (transitional). */
val LocalProfileAccent = staticCompositionLocalOf { brandAccentColors(dark = false) }

/** Transitional alias — profile no longer affects chrome colour. */
@androidx.compose.runtime.Composable
fun rememberProfileAccent(@Suppress("UNUSED_PARAMETER") profile: String?, dark: Boolean): ProfileAccentColors =
    brandAccentColors(dark)
