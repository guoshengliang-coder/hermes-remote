package com.hermes.client.ui.localization

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HG-17. The app used to open in Chinese on every phone, because the stored default was the
 * string "ZH" and nothing ever looked at the device locale. Following the system is now the
 * default, and English is the fallback for everything that is not Chinese.
 */
class LanguagePreferenceTest {
    @Test fun `a chinese phone gets chinese`() {
        listOf("zh-CN", "zh-Hans-CN", "zh-SG").forEach { tag ->
            assertEquals(AppLanguage.ZH, LanguagePreference.SYSTEM.resolve(Locale.forLanguageTag(tag)))
        }
    }

    // The decision on the item: Traditional matches on the language subtag and lands on Simplified
    // for now. A Traditional translation is tracked separately; it would add a case here rather
    // than change this rule.
    @Test fun `traditional chinese falls to simplified for now`() {
        listOf("zh-TW", "zh-HK", "zh-Hant-TW").forEach { tag ->
            assertEquals(AppLanguage.ZH, LanguagePreference.SYSTEM.resolve(Locale.forLanguageTag(tag)))
        }
    }

    // Everything else is English, not the product's own first language. This is the half that was
    // backwards: an English phone was shown Chinese it had never asked for.
    @Test fun `every other phone gets english`() {
        listOf("en-US", "en-GB", "ja-JP", "de-DE", "ar-EG", "pt-BR").forEach { tag ->
            assertEquals(AppLanguage.EN, LanguagePreference.SYSTEM.resolve(Locale.forLanguageTag(tag)))
        }
    }

    // An explicit choice is an explicit choice: it must not consult the phone at all, in either
    // direction.
    @Test fun `an explicit choice ignores the phone`() {
        listOf("zh-CN", "en-US", "ja-JP").map(Locale::forLanguageTag).forEach { locale ->
            assertEquals(AppLanguage.ZH, LanguagePreference.ZH.resolve(locale))
            assertEquals(AppLanguage.EN, LanguagePreference.EN.resolve(locale))
        }
    }

    // Migration, which has no other test surface: the stored value is the preference's own name,
    // and the two names an existing install can already hold still parse. An install that never
    // opened the language screen has no value at all and falls to SYSTEM — that is the only
    // behaviour change existing users see.
    @Test fun `stored names survive the type change`() {
        assertEquals(LanguagePreference.ZH, LanguagePreference.valueOf("ZH"))
        assertEquals(LanguagePreference.EN, LanguagePreference.valueOf("EN"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.valueOf("SYSTEM"))
    }
}
