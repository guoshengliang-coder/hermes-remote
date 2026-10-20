package com.hermes.client.ui.chat

import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android text geometry, without Espresso's obsolete InputManager reflection on API 36. */
@OptIn(InternalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class ChatSearchNavigationDeviceTest {
    @Test fun long_reply() = exercise(Role.ASSISTANT, dark = false)
    @Test fun dark_user_bubble() = exercise(Role.USER, dark = true)
    @Test fun long_table_cell() = exercise(Role.ASSISTANT, dark = false, table = true)

    private fun rootFor(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            rootFor(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun focusedRectangle(node: SemanticsNode, occurrence: Int): Rect? {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        for (layout in layouts) {
            val text = layout.layoutInput.text
            val mark = text.getStringAnnotations(SEARCH_FOCUS_TAG, 0, text.length)
                .firstOrNull { it.item == occurrence.toString() } ?: continue
            return layout.getPathForRange(mark.start, mark.end).getBounds().translate(node.positionInRoot)
        }
        for (child in node.children) focusedRectangle(child, occurrence)?.let { return it }
        return null
    }

    private fun exercise(role: Role, dark: Boolean, table: Boolean = false) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val prose = (0..70).joinToString(if (table) " " else "\n") { i ->
            if (i == 10 || i == 60) "学习目标 $i" else "第 $i 行，保留足够阅读距离。"
        }
        val text = if (table) "| 内容 |\n| --- |\n| $prose |" else prose
        val context = mutableStateOf(ChatSearchContext("学习", "answer", 0))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    HermesTheme(darkTheme = dark) {
                        Surface(Modifier.width(360.dp).height(420.dp)) {
                            ChatMessageList(
                                state = ChatUiState(messages = listOf(ChatMessage(id = "answer", role = role, text = text)), historyLoaded = true),
                                sessionId = "search-device", listState = rememberLazyListState(),
                                searchOpen = true, highlightIndex = 0, searchContext = context.value,
                            )
                        }
                    }
                }
            }
            for ((step, occurrence) in listOf(0, 1, 0).withIndex()) {
                scenario.onActivity { context.value = context.value.copy(currentOccurrence = occurrence) }
                val deadline = SystemClock.uptimeMillis() + 10_000
                var visible = false
                while (SystemClock.uptimeMillis() < deadline && !visible) {
                    scenario.onActivity { activity ->
                        rootFor(activity.window.decorView)?.let { root ->
                            val rectangle = focusedRectangle(root.semanticsOwner.unmergedRootSemanticsNode, occurrence)
                            visible = rectangle != null && rectangle.top >= 0 && rectangle.bottom <= 420 * root.density.density + 2
                        }
                    }
                    SystemClock.sleep(16)
                }
                assertTrue("$role table=$table occurrence=$occurrence must be visible", visible)
                if (step == 2) continue
                SystemClock.sleep(700)
                // AGP removes the app after connected tests. MediaStore keeps these synthetic
                // evidence images available for an explicit adb pull and visual inspection.
                val resolver = instrument.targetContext.contentResolver
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "hg221-search-$role-$dark-$table-$occurrence.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/hermes-ui-verification")
                }))
                val screenshot = requireNotNull(instrument.uiAutomation.takeScreenshot())
                try {
                    requireNotNull(resolver.openOutputStream(uri)).use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        }
    }
}
