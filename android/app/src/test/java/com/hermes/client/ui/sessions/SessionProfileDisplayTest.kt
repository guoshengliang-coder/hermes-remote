package com.hermes.client.ui.sessions

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionProfileDisplayTest {
    @Test fun single_profile_is_hidden() {
        assertNull(profileDisplayLabel("default", 1, AppLanguage.ZH))
        assertNull(profileDisplayLabel("work", 1, AppLanguage.ZH))
    }

    @Test fun multiple_profiles_use_clear_localized_labels() {
        assertEquals("默认身份", profileDisplayLabel("default", 2, AppLanguage.ZH))
        assertEquals("身份：work", profileDisplayLabel("work", 2, AppLanguage.ZH))
        assertEquals("Default profile", profileDisplayLabel(null, 2, AppLanguage.EN))
        assertEquals("Profile: work", profileDisplayLabel("work", 2, AppLanguage.EN))
    }
}
