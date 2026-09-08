package com.hermes.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage

/**
 * Draw [content] in Chinese, for tests that assert Chinese copy.
 *
 * These used to rely on `LocalAppLanguage`'s default, which was Chinese. The default now follows
 * the phone (HG-17), and a JVM test runs under en-US, so a test that means "the Chinese label is
 * here" has to say so. Saying it also keeps the assertion honest: it is about the copy, not about
 * whatever the default happens to be this year.
 */
@Composable
fun InChinese(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH, content = content)
}
