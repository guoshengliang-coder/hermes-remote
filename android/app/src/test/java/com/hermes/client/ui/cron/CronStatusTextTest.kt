package com.hermes.client.ui.cron

import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronStatusTextTest {
    @Test fun known_outcomes_are_localized_in_both_languages() {
        assertEquals("已运行，未送达", cronStatusLabel("delivery_failed", AppLanguage.ZH))
        assertEquals("Ran, not delivered", cronStatusLabel("delivery_failed", AppLanguage.EN))
        assertEquals("失败", cronStatusLabel("error", AppLanguage.ZH))
        assertEquals("Succeeded", cronStatusLabel("ok", AppLanguage.EN))
    }

    @Test fun matching_ignores_case_and_padding() {
        assertEquals("已运行，未送达", cronStatusLabel("  DELIVERY_FAILED ", AppLanguage.ZH))
    }

    /** An outcome this release has never seen still has to say something. */
    @Test fun unknown_outcome_is_passed_through_rather_than_swallowed() {
        assertEquals("quarantined", cronStatusLabel("quarantined", AppLanguage.ZH))
    }

    @Test fun absent_or_blank_status_has_no_label() {
        assertNull(cronStatusLabel(null, AppLanguage.ZH))
        assertNull(cronStatusLabel("   ", AppLanguage.EN))
    }
}
