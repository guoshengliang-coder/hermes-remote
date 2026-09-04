package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Link targets come from model output, so this is the boundary that decides what the app is
 * willing to hand to `startActivity` (HR-LINK-002).
 */
class ChatLinksTest {
    @Test fun httpAndHttpsPassThroughUnchanged() {
        assertEquals("https://example.com/a?b=c#d", openableChatLink("https://example.com/a?b=c#d"))
        assertEquals("http://example.com", openableChatLink("http://example.com"))
    }

    @Test fun mailtoAndTelAreOpenable() {
        assertEquals("mailto:a@example.com", openableChatLink("mailto:a@example.com"))
        assertEquals("tel:+8613800138000", openableChatLink("tel:+8613800138000"))
    }

    @Test fun schemeMatchingIsCaseInsensitive() {
        assertEquals("HTTPS://example.com", openableChatLink("HTTPS://example.com"))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("https://example.com", openableChatLink("  https://example.com \n"))
    }

    /** A bare `www.` autolink has no scheme; the system cannot open it without one. */
    @Test fun bareWwwAutolinkGetsHttps() {
        assertEquals("https://www.example.com/x", openableChatLink("www.example.com/x"))
        assertEquals("https://WWW.example.com", openableChatLink("WWW.example.com"))
    }

    @Test fun schemesOutsideTheAllowlistAreRefused() {
        assertNull(openableChatLink("javascript:alert(1)"))
        assertNull(openableChatLink("file:///data/data/com.hermes.remote/databases/app.db"))
        assertNull(openableChatLink("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(openableChatLink("content://com.hermes.remote.files/secret"))
        assertNull(openableChatLink("JavaScript:alert(1)"))
    }

    @Test fun relativeAndAnchorTargetsAreRefused() {
        assertNull(openableChatLink("#section"))
        assertNull(openableChatLink("/docs/setup"))
        assertNull(openableChatLink("../README.md"))
        assertNull(openableChatLink(""))
        assertNull(openableChatLink("   "))
    }

    /** "example.com" alone is ambiguous — the parser does not autolink it, and neither do we. */
    @Test fun bareHostWithoutWwwIsRefused() {
        assertNull(openableChatLink("example.com"))
    }
}
