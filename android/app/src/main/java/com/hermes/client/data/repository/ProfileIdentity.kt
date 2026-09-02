package com.hermes.client.data.repository

/** How a lettered (non-photo) avatar is drawn: solid fill with a white initial, or a ring + coloured initial. */
enum class AvatarStyle { SOLID, OUTLINE }

/**
 * Device-local personalisation layered over a Hermes server profile. The profile itself (its
 * name, its existence) is the gateway's; everything here is the phone's, keyed by profile name,
 * and moves to account sync later — [updatedAt] is kept from day one so that merge is last-writer-wins.
 *
 * Absent fields mean "default": name = the profile name, avatar = hashed colour + initial, solid.
 */
data class ProfileIdentity(
    val displayName: String? = null,
    /** Basename of a square WebP under the avatar directory; null = no photo. */
    val avatarFile: String? = null,
    /** User-chosen avatar colour (ARGB at the avatar lightness); null = the name-hashed colour. */
    val colorArgb: Int? = null,
    val style: AvatarStyle = AvatarStyle.SOLID,
    val updatedAt: Long = 0L,
) {
    val isDefault: Boolean
        get() = displayName == null && avatarFile == null && colorArgb == null && style == AvatarStyle.SOLID

    companion object {
        val DEFAULT = ProfileIdentity()
        const val MAX_DISPLAY_NAME_LENGTH = 24

        /** Trims and empties a typed display name: blank means "no custom name". */
        fun normalizeDisplayName(raw: String?): String? =
            raw?.trim()?.take(MAX_DISPLAY_NAME_LENGTH)?.takeIf { it.isNotEmpty() }
    }
}

/** True when the user set a display name — the one case the profile name moves to the subline. */
fun ProfileIdentity?.hasCustomName(): Boolean = !this?.displayName.isNullOrBlank()

/** What to call the profile: the custom name if set, else the profile name, else a dash. */
fun displayNameFor(profile: String?, identity: ProfileIdentity?): String =
    identity?.displayName?.takeIf { it.isNotBlank() }
        ?: profile?.takeIf { it.isNotBlank() }
        ?: "—"

/**
 * The letter on a lettered avatar: first character of the DISPLAY name (what the user reads),
 * uppercased; a middle dot when nothing is known. Colour, by contrast, hashes the PROFILE name —
 * renaming never recolours.
 */
fun avatarInitialFor(profile: String?, identity: ProfileIdentity?): String {
    val name = identity?.displayName?.takeIf { it.isNotBlank() } ?: profile
    return (name?.takeIf { it.isNotBlank() } ?: "·").take(1).uppercase()
}
