package com.hermes.client.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    // Latin tokens are left alone: the gateway adds its own prefix wildcard.
    @Test fun latin_tokens_pass_through() {
        assertEquals("gradle build", buildSearchQuery("  gradle   build "))
    }

    // CJK tokens are quoted so the gateway keeps them verbatim (its `*` breaks the CJK paths).
    @Test fun cjk_tokens_are_quoted() {
        assertEquals("\"部署脚本\"", buildSearchQuery("部署脚本"))
        assertEquals("\"部署\" gradle", buildSearchQuery("部署 gradle"))
        assertEquals("\"部署\" \"脚本\"", buildSearchQuery("部署 脚本"))
    }

    // Mixed tokens containing any CJK are quoted as a whole.
    @Test fun mixed_token_with_cjk_is_quoted() {
        assertEquals("\"apk包\"", buildSearchQuery("apk包"))
    }

    // What the user already quoted or wildcarded is preserved as-is.
    @Test fun user_quotes_and_wildcards_are_preserved() {
        assertEquals("\"exact phrase\" nimb*", buildSearchQuery("\"exact phrase\" nimb*"))
        assertEquals("\"部署 脚本\"", buildSearchQuery("\"部署 脚本\""))
    }

    // Stray quotes inside a CJK token cannot produce an unbalanced phrase.
    @Test fun embedded_quotes_in_cjk_token_are_dropped() {
        assertEquals("\"部署\"", buildSearchQuery("部\"署"))
    }

    @Test fun blank_input_is_empty() {
        assertEquals("", buildSearchQuery("   "))
    }

    @Test fun cjk_detection_covers_kana_and_hangul() {
        assertTrue(containsCjk("テスト"))
        assertTrue(containsCjk("한국어"))
        assertTrue(containsCjk("x汉y"))
        assertFalse(containsCjk("plain ascii 123"))
    }
}
