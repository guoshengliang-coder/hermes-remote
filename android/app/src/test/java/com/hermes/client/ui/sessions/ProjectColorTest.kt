package com.hermes.client.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectColorTest {
    /** The palette is written in the desktop's own notation so a project looks the same there. */
    @Test fun the_palette_is_twelve_evenly_spaced_desktop_hues() {
        assertEquals(12, PROJECT_COLORS.size)
        assertEquals("hsl(0 68% 58%)", PROJECT_COLORS.first())
        assertEquals("hsl(330 68% 58%)", PROJECT_COLORS.last())
        assertEquals(PROJECT_COLORS.size, PROJECT_COLORS.distinct().size)
    }

    @Test fun every_palette_entry_parses() {
        PROJECT_COLORS.forEach { assertNotNull("unparsed: $it", parseProjectColor(it)) }
    }

    /** Projects created on the desktop carry hsl(); older/hand-set rows may carry hex. */
    @Test fun both_notations_parse_and_agree_on_a_known_hue() {
        assertNotNull(parseProjectColor("#3b82f6"))
        assertEquals(parseProjectColor("hsl(0 100% 50%)"), parseProjectColor("#ff0000"))
    }

    /** An unset or unusable colour must fall through to the accent, never crash the row. */
    @Test fun unusable_values_return_null() {
        assertNull(parseProjectColor(null))
        assertNull(parseProjectColor(""))
        assertNull(parseProjectColor("   "))
        assertNull(parseProjectColor("rebeccapurple"))
        assertNull(parseProjectColor("hsl(0)"))
    }
}
