package com.hermes.client.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

enum class AppLanguage { ZH, EN }

val LocalAppLanguage = compositionLocalOf { AppLanguage.ZH }

/** Small runtime-localized string helper. Chinese is deliberately the product default. */
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
