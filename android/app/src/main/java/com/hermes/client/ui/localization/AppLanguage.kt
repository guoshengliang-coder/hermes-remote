package com.hermes.client.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import java.util.Locale

/** The language the UI is actually drawn in, after [LanguagePreference] has been resolved. */
enum class AppLanguage { ZH, EN }

/**
 * What the user picked, which is not the same thing as what they get.
 *
 * [SYSTEM] is the default and follows the phone: a Chinese phone gets Chinese, everything else
 * gets English. It is a separate type from [AppLanguage] on purpose — every one of the ~1000
 * `localized(language, zh, en)` call sites needs a language it can actually draw, so the choice
 * is resolved once, here, rather than leaving a third case for each of them to mishandle. The
 * same split already exists for theme: `ThemeMode` has SYSTEM, and the boolean it resolves to is
 * what the UI reads.
 */
enum class LanguagePreference { SYSTEM, ZH, EN }

/**
 * The language [this] preference produces on a phone set to [locale].
 *
 * Anything but Chinese resolves to English rather than to the product's own first language,
 * because a reader whose phone is not Chinese is far likelier to read English than Chinese
 * (HG-17). Traditional Chinese (zh-TW, zh-HK) matches on the language subtag and therefore lands
 * on Simplified for now; a Traditional translation is tracked separately and would add a case
 * here, not change the rule.
 */
fun LanguagePreference.resolve(locale: Locale = Locale.getDefault()): AppLanguage = when (this) {
    LanguagePreference.ZH -> AppLanguage.ZH
    LanguagePreference.EN -> AppLanguage.EN
    LanguagePreference.SYSTEM -> if (locale.language == "zh") AppLanguage.ZH else AppLanguage.EN
}

val LocalAppLanguage = compositionLocalOf { LanguagePreference.SYSTEM.resolve() }

/**
 * Small runtime-localized string helper. Takes a resolved [AppLanguage], never a preference —
 * see [LanguagePreference] for why the two are different types.
 */
fun localized(language: AppLanguage, zh: String, en: String): String =
    if (language == AppLanguage.ZH) zh else en

/** Language-independent copy that can safely cross ViewModel and background-service boundaries. */
data class LocalizedText(val zh: String, val en: String) {
    fun resolve(language: AppLanguage): String = localized(language, zh, en)
}

fun localizedText(zh: String, en: String): LocalizedText = LocalizedText(zh, en)

/** Composition-scoped variant for bulk call sites: reads [LocalAppLanguage] directly. */
@Composable
fun l10n(zh: String, en: String): String = localized(LocalAppLanguage.current, zh, en)

/** Composition-scoped resolver for copy produced outside Compose. */
@Composable
fun LocalizedText.resolve(): String = resolve(LocalAppLanguage.current)
