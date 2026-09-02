package com.hermes.client.ui.components

import com.hermes.client.data.repository.AvatarStyle
import com.hermes.client.data.repository.ProfileIdentity
import com.hermes.client.ui.theme.avatarColorArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AvatarLookTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaults_are_the_hashed_colour_solid_and_the_profile_initial() {
        val look = resolveAvatarLook("personal", null, tmp.root)
        assertEquals("P", look.initial)
        assertEquals(avatarColorArgb("personal"), look.colorArgb)
        assertEquals(AvatarStyle.SOLID, look.style)
        assertNull(look.photo)
    }

    // Custom colour wins over the hash; the initial follows the display name; colour does not.
    @Test fun custom_colour_and_display_name_apply_independently() {
        val look = resolveAvatarLook("odos", ProfileIdentity(displayName = "工作台", colorArgb = 0xFF1F4B84.toInt(), style = AvatarStyle.OUTLINE), tmp.root)
        assertEquals("工", look.initial)
        assertEquals(0xFF1F4B84.toInt(), look.colorArgb)
        assertEquals(AvatarStyle.OUTLINE, look.style)
    }

    // A named photo is only a photo when the file actually exists — a stale name degrades to
    // the lettered avatar instead of a blank circle.
    @Test fun photo_only_when_the_file_exists() {
        assertNull(resolveAvatarLook("p", ProfileIdentity(avatarFile = "missing.webp"), tmp.root).photo)
        assertNull(resolveAvatarLook("p", ProfileIdentity(avatarFile = "x.webp"), null).photo)
        val f = tmp.newFile("real.webp")
        assertEquals(f, resolveAvatarLook("p", ProfileIdentity(avatarFile = "real.webp"), tmp.root).photo)
    }
}
