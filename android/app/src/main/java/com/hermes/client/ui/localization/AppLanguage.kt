package com.hermes.client.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

enum class AppLanguage { ZH, EN }

val LocalAppLanguage = compositionLocalOf { AppLanguage.ZH }

/** Small runtime-localized string helper. Chinese is deliberately the product default. */
fun localized(language: AppLanguage, zh: String, en: String): String =
    if (language == AppLanguage.ZH) zh else en

/** Composition-scoped variant for bulk call sites: reads [LocalAppLanguage] directly. */
@Composable
fun l10n(zh: String, en: String): String = localized(LocalAppLanguage.current, zh, en)
