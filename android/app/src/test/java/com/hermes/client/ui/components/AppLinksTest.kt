package com.hermes.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Link targets mostly come from model output, so this is the boundary that decides what the app
 * is willing to hand to `startActivity` (HR-LINK-002).
 */
class AppLinksTest {
    @Test fun httpAndHttpsPassThroughUnchanged() {
        assertEquals("https://example.com/a?b=c#d", openableAppLink("https://example.com/a?b=c#d"))
        assertEquals("http://example.com", openableAppLink("http://example.com"))
    }

    @Test fun mailtoAndTelAreOpenable() {
        assertEquals("mailto:a@example.com", openableAppLink("mailto:a@example.com"))
        assertEquals("tel:+8613800138000", openableAppLink("tel:+8613800138000"))
    }

    @Test fun schemeMatchingIsCaseInsensitive() {
        assertEquals("HTTPS://example.com", openableAppLink("HTTPS://example.com"))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://example.com", openableAppLink("  https://example.com \n"))
    }

    /** A bare `www.` autolink has no scheme; the system cannot open it without one. */
    @Test fun bareWwwAutolinkGetsHttps() {
        assertEquals("https://www.example.com/x", openableAppLink("www.example.com/x"))
        assertEquals("https://WWW.example.com", openableAppLink("WWW.example.com"))
    }

    @Test fun schemesOutsideTheAllowlistAreRefused() {
        assertNull(openableAppLink("javascript:alert(1)"))
        assertNull(openableAppLink("file:///data/data/com.hermes.remote/databases/app.db"))
        assertNull(openableAppLink("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(openableAppLink("content://com.hermes.remote.files/secret"))
        assertNull(openableAppLink("JavaScript:alert(1)"))
    }

    @Test fun relativeAndAnchorTargetsAreRefused() {
        assertNull(openableAppLink("#section"))
        assertNull(openableAppLink("/docs/setup"))
        assertNull(openableAppLink("../README.md"))
        assertNull(openableAppLink(""))
        assertNull(openableAppLink("   "))
    }

    /** "example.com" alone is ambiguous — the parser does not autolink it, and neither do we. */
    @Test fun bareHostWithoutWwwIsRefused() {
        assertNull(openableAppLink("example.com"))
    }
}
