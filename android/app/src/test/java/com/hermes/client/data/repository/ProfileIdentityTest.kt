package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIdentityTest {

    @Test fun normalizeDisplayName_trims_empties_and_caps_length() {
        assertNull(ProfileIdentity.normalizeDisplayName(null))
        assertNull(ProfileIdentity.normalizeDisplayName("   "))
        assertEquals("Rex", ProfileIdentity.normalizeDisplayName("  Rex "))
        val long = "x".repeat(40)
        assertEquals(ProfileIdentity.MAX_DISPLAY_NAME_LENGTH, ProfileIdentity.normalizeDisplayName(long)!!.length)
    }

    // The big line: custom name wins; blank custom name is "no custom name"; nothing known = dash.
    @Test fun displayName_prefers_custom_then_profile_then_dash() {
        assertEquals("Rex", displayNameFor("personal", ProfileIdentity(displayName = "Rex")))
        assertEquals("personal", displayNameFor("personal", ProfileIdentity(displayName = "  ")))
        assertEquals("personal", displayNameFor("personal", null))
        assertEquals("—", displayNameFor(null, null))
    }

    // The letter follows what the user READS (the display name), uppercased; colour does not.
    @Test fun initial_comes_from_display_name() {
        assertEquals("R", avatarInitialFor("personal", ProfileIdentity(displayName = "rex")))
        assertEquals("工", avatarInitialFor("odos", ProfileIdentity(displayName = "工作台")))
        assertEquals("P", avatarInitialFor("personal", null))
        assertEquals("·", avatarInitialFor(null, null))
        assertEquals("·", avatarInitialFor("", ProfileIdentity(displayName = "")))
    }

    @Test fun hasCustomName_only_for_non_blank_names() {
        assertTrue(ProfileIdentity(displayName = "Rex").hasCustomName())
        assertFalse(ProfileIdentity(displayName = " ").hasCustomName())
        assertFalse((null as ProfileIdentity?).hasCustomName())
    }

    @Test fun isDefault_ignores_the_timestamp() {
        assertTrue(ProfileIdentity(updatedAt = 42L).isDefault)
        assertFalse(ProfileIdentity(style = AvatarStyle.OUTLINE).isDefault)
        assertFalse(ProfileIdentity(colorArgb = 0xFF1F4B84.toInt()).isDefault)
    }
}
