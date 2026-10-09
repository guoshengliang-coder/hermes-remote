package com.hermes.client.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.ui.localization.*
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.intellij.markdown.parser.MarkdownParser

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1100dp-h700dp-mdpi")
class ConversationWorkspaceTest {
    @get:Rule val compose = createComposeRule()

    private fun workspace(width: State<Float>, dark: Boolean = false, fontScale: Float = 1f, preference: MutableState<WorkspacePreference> = mutableStateOf(WorkspacePreference()), mounted: () -> Unit = {}) {
        compose.setContent {
            HermesTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ZH, LocalDensity provides Density(1f, fontScale)) {
                    Surface(Modifier.width(width.value.dp).fillMaxHeight()) {
                        ConversationWorkspace(true, false, preference.value, { preference.value = preference.value.copy(listWidth = it) }, list = {
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                Text("会话", style = MaterialTheme.typography.titleLarge)
                                Text("当前 Mac · 工作身份", style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                                Text("平板与折叠屏界面方案", style = MaterialTheme.typography.titleMedium)
                                Text("讨论 · 有草稿", style = MaterialTheme.typography.bodySmall)
                            }
                        }) {
                            DisposableEffect(Unit) { mounted(); onDispose {} }
                            var draft by rememberSaveable { mutableStateOf("尚未发送的草稿") }
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                com.hermes.client.ui.chat.ChatTopBar("平板与折叠屏界面方案", true, false, false, "我的提问", {}, {}, {}, {}, {}, {}, {})
                                Text("按可用窗口宽度自适应，两栏之间可以拖动调整。", Modifier.weight(1f).padding(top = 24.dp))
                                TextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("draft"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun resizeDragKeyboardAndModeChangeKeepTheChatMountedAndDraftIntact() {
        val width = mutableStateOf(1100f)
        val preference = mutableStateOf(WorkspacePreference())
        var mounts = 0
        workspace(width, preference = preference, mounted = { mounts++ })
        compose.onNodeWithTag("draft").performTextReplacement("我的草稿")
        compose.onNodeWithTag("workspace-divider").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue(preference.value.listWidth > 300f)
        val firstDragWidth = preference.value.listWidth
        compose.onNodeWithTag("workspace-divider").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue("Every drag starts at the current saved width", preference.value.listWidth > firstDragWidth)
        compose.onNodeWithTag("workspace-divider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(10000f)) }
        compose.waitForIdle()
        assertEquals(480f, preference.value.listWidth, .01f)
        compose.onNodeWithTag("workspace-divider").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("workspace-divider").performKeyInput { pressKey(Key.Home) }
        compose.waitForIdle()
        assertEquals(300f, preference.value.listWidth, .01f)
        compose.runOnIdle { width.value = 500f }
        compose.onNodeWithTag("workspace-divider").assertDoesNotExist()
        compose.onNodeWithTag("draft").assertTextEquals("我的草稿")
        compose.runOnIdle { width.value = 1100f; preference.value = preference.value.copy(mode = WorkspaceMode.SINGLE) }
        compose.onNodeWithTag("workspace-divider").assertDoesNotExist()
        compose.runOnIdle { preference.value = preference.value.copy(mode = WorkspaceMode.AUTO) }
        compose.onNodeWithContentDescription("收起或展开会话栏").performClick()
        compose.onNodeWithTag("workspace-divider").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起或展开会话栏").performClick()
        compose.onNodeWithTag("workspace-divider").assertExists()
        assertEquals(1, mounts)
        compose.onNodeWithTag("draft").assertTextEquals("我的草稿")
    }

    @Test fun tabletLight() { workspace(mutableStateOf(1100f)); compose.onRoot().captureRoboImage("screenshots/workspace-tablet-light.png") }
    @Test fun tabletAppDarkSystemLightLargeFont() { workspace(mutableStateOf(1100f), true, 1.3f); compose.onRoot().captureRoboImage("screenshots/workspace-tablet-dark-fs13.png") }
    @Test fun narrowChat() { workspace(mutableStateOf(500f)); compose.onRoot().captureRoboImage("screenshots/workspace-narrow.png") }

    @Test fun leavingCompositionSnapshotsBeforeSaveableProvidersUnregister() {
        val route = mutableStateOf("a")
        val entryId = mutableStateOf(0)
        val cache = SessionUiStateCache()
        compose.setContent { HermesTheme {
            androidx.compose.runtime.key(entryId.value) { cache.SessionState(route.value) {
                var draft by rememberConversationState("draft") { "" }
                TextField(draft, { draft = it }, Modifier.testTag("cached-draft"))
            } }
        } }
        compose.onNodeWithTag("cached-draft").performTextReplacement("A 的草稿和搜索")
        compose.runOnIdle { route.value = "b"; entryId.value++ }
        compose.onNodeWithTag("cached-draft").assertTextEquals("")
        compose.runOnIdle { route.value = "a"; entryId.value++ }
        compose.onNodeWithTag("cached-draft").assertTextEquals("A 的草稿和搜索")
    }

    @Test fun transcriptReturnsToTheSameStableItemAndOffsetAfterANewNavigationEntry() = transcriptRestoration(false)

    @Test fun restoredTranscriptWaitsForMarkdownParsingBeforeSettlingItsPosition() = transcriptRestoration(true)

    @Test fun readingLineSurvivesNarrowAndWidePaneReflow() = transcriptRestoration(false, reflow = true)

    @Test fun aTemporaryLayoutScrollMutationDoesNotAbandonRestoration() = transcriptRestoration(false, stealScroll = true)

    private fun transcriptRestoration(blockParsing: Boolean, reflow: Boolean = false, stealScroll: Boolean = false) {
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        if (blockParsing) {
            mockkConstructor(MarkdownParser::class)
            every { anyConstructed<MarkdownParser>().buildMarkdownTreeFromString(any()) } answers {
                if (pending.get()) {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                callOriginal()
            }
        }
        try {
            val visible = mutableStateOf(true)
            val loading = mutableStateOf(false)
            val entry = mutableStateOf(0)
            val cache = SessionUiStateCache()
            val paneWidth = mutableStateOf(720f)
            lateinit var list: androidx.compose.foundation.lazy.LazyListState
            lateinit var scope: kotlinx.coroutines.CoroutineScope
            val messages = (1..80).map { n -> com.hermes.client.domain.ChatMessage(
                id = "message-$n", role = if (n % 2 == 0) com.hermes.client.domain.Role.ASSISTANT else com.hermes.client.domain.Role.USER,
                text = if (n % 2 == 0) "第 $n 答。\n\n保留当前阅读位置。\n\n- 要点一\n- 要点二\n- 要点三" else "第 $n 问：如何适应当前窗口？",
            ) }
            compose.setContent { HermesTheme {
                if (visible.value) androidx.compose.runtime.key(entry.value) { cache.SessionState("session-a") {
                    scope = rememberCoroutineScope()
                    list = rememberConversationValue("chat:list", androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
                    if (stealScroll && entry.value > 0 && !loading.value) LaunchedEffect(list, entry.value) {
                        list.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
                            repeat(12) { withFrameNanos { } }
                        }
                    }
                    val viewport = rememberConversationValue("chat:viewport", com.hermes.client.ui.chat.ChatViewportController.Saver) { com.hermes.client.ui.chat.ChatViewportController() }
                    CompositionLocalProvider(LocalChatWidth provides paneWidth.value) {
                        com.hermes.client.ui.chat.ChatMessageList(
                            state = com.hermes.client.ui.chat.ChatUiState(messages = if (loading.value) emptyList() else messages, historyLoaded = !loading.value, historyLoading = loading.value),
                            sessionId = "session-a", listState = list, viewportController = viewport,
                            modifier = Modifier.width(paneWidth.value.dp).fillMaxHeight(),
                        )
                    }
                } } else Text("another conversation")
            } }
            compose.waitForIdle()
            compose.runOnIdle { scope.launch { list.scrollToItem(1, 130) } }
            compose.waitForIdle()
            val index = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            val title = compose.onNodeWithText("第 80 答。")
            val titleTop = title.fetchSemanticsNode().boundsInRoot.top
            if (reflow) {
                compose.runOnIdle { paneWidth.value = 400f }
                compose.waitForIdle()
                compose.runOnIdle { paneWidth.value = 720f }
                compose.waitForIdle()
            } else {
                compose.runOnIdle { visible.value = false }
                compose.onNodeWithText("another conversation").assertExists()
                pending.set(blockParsing)
                compose.runOnIdle { entry.value++; loading.value = true; visible.value = true }
                compose.waitForIdle()
                compose.runOnIdle { loading.value = false }
                if (blockParsing) {
                    compose.waitUntil(5_000) { entered.count == 0L }
                    compose.mainClock.advanceTimeBy(500)
                    compose.waitForIdle()
                    pending.set(false)
                    release.countDown()
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("第 80 答。")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(titleTop, title.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(index, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        } finally {
            pending.set(false)
            release.countDown()
            if (blockParsing) unmockkConstructor(MarkdownParser::class)
        }
    }
}
