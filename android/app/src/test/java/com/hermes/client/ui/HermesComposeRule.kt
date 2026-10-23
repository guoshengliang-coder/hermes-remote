package com.hermes.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * Queue composition work on the rule's scheduler. The v1 rule's unconfined default can resume
 * a composition on an image-decoder or Markdown-parser worker when an effect completes there.
 * Keep real asynchronous work and every assertion; waitForIdle/runOnIdle drain the UI scheduler.
 * https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
fun createHermesComposeRule(): ComposeContentTestRule =
    createComposeRule(effectContext = StandardTestDispatcher())
