package com.hermes.client.ui.workspace

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceLayoutTest {
    @Test fun containerAndFontScaleMustLeaveRoomForBothPanes() {
        assertFalse(workspaceBudget(623f, 1f, WorkspacePreference()).split)
        assertTrue(workspaceBudget(624f, 1f, WorkspacePreference()).split)
        assertFalse(workspaceBudget(840f, 1.5f, WorkspacePreference()).split)
        assertTrue(workspaceBudget(1000f, 1.5f, WorkspacePreference()).split)
        assertFalse(workspaceBudget(1600f, 1f, WorkspacePreference(WorkspaceMode.SINGLE)).split)
        assertEquals(456f, workspaceBudget(840f, 1f, WorkspacePreference(listWidth = 480f)).listWidth, .01f)
    }

    @Test fun unfoldedPhoneUsesThePaneBudgetRatherThanTheTabletBreakpoint() {
        for (width in listOf(624f, 673f, 700f, 720f, 800f, 839f)) {
            val budget = workspaceBudget(width, 1f, WorkspacePreference(listWidth = 480f))
            assertTrue("An unfolded $width dp window has room for both panes", budget.split)
            assertTrue(budget.listWidth >= 240f)
            assertTrue(width - budget.listWidth - 24f >= 360f)
        }
        for (width in listOf(344f, 400f, 500f, 623f)) {
            assertFalse(workspaceBudget(width, 1f, WorkspacePreference()).split)
        }
        assertFalse(workspaceBudget(700f, 1.3f, WorkspacePreference()).split)
        assertTrue(workspaceBudget(804f, 1.3f, WorkspacePreference()).split)
        assertEquals(316f, workspaceBudget(700f, 1f, WorkspacePreference(listWidth = 480f)).listWidth, .01f)
    }

    @Test fun narrowWindowDoesNotRewriteRememberedWidthAndInvalidStorageIsSafe() {
        val choice = WorkspacePreference(listWidth = 480f)
        workspaceBudget(400f, 1f, choice)
        assertEquals(480f, workspaceBudget(1200f, 1f, choice).listWidth, .01f)
        assertEquals(300f, WorkspacePreference(listWidth = Float.NaN).normalized().listWidth, .01f)
        assertEquals(240f, WorkspacePreference(listWidth = -1f).normalized().listWidth, .01f)
    }

    @Test fun hingeFixesTheDivisionOrChoosesAnUnobstructedSinglePane() {
        assertEquals(WorkspaceRegion(0f, 1000f, 420f, 20f), workspaceRegion(1000f, 1f, true, WorkspaceHinge(420f, 440f)))
        assertEquals(WorkspaceRegion(440f, 560f, null), workspaceRegion(1000f, 1f, false, WorkspaceHinge(420f, 440f)))
        assertEquals(WorkspaceRegion(260f, 540f, null), workspaceRegion(800f, 1.3f, true, WorkspaceHinge(240f, 260f)))
        assertEquals(WorkspaceRegion(0f, 380f, null), workspaceRegion(800f, 1f, true, WorkspaceHinge(380f, 420f, false)))
    }

    @Test fun diskFailureKeepsChoicesInMemoryAndLateReadCannotUndoAnEdit() = runTest {
        val source = MutableSharedFlow<WorkspacePreference>()
        val writes = mutableListOf<WorkspacePreference>()
        val state = WorkspacePreferenceState(source, { writes += it; error("disk unavailable") }, backgroundScope)
        runCurrent()
        state.width(440f)
        source.emit(WorkspacePreference())
        runCurrent()
        assertEquals(440f, state.state.value.listWidth, .01f)
        state.mode(WorkspaceMode.SINGLE)
        runCurrent()
        assertEquals(WorkspaceMode.SINGLE, state.state.value.mode)
        assertEquals(440f, writes.last().listWidth, .01f)
        val unreadable = WorkspacePreferenceState(flow { error("unreadable") }, {}, backgroundScope)
        runCurrent()
        unreadable.width(360f)
        assertEquals(360f, unreadable.state.value.listWidth, .01f)
    }

    @Test fun conversationIdentityIncludesAllScopesWithoutDelimiterCollisions() {
        assertNotEquals(workspaceSessionKey(listOf("a/b", "c")), workspaceSessionKey(listOf("a", "b/c")))
        assertNotEquals(workspaceSessionKey(listOf("account", "mac-a", "p", "s")), workspaceSessionKey(listOf("account", "mac-b", "p", "s")))
    }

    @Test fun rapidReturnSnapshotsLiveStateAndLateExitCannotOverwriteIt() {
        val cache = SessionUiStateCache()
        val old = cache.acquire("a") { true }
        old.registerProvider("draft") { "first draft" }
        val next = cache.acquire("a") { true }
        assertEquals("first draft", next.consumeRestored("draft"))
        next.registerProvider("draft") { "latest draft" }
        cache.release("a", old)
        cache.release("a", next)
        assertEquals("latest draft", SessionUiStateCache(cache.snapshot()).acquire("a") { true }.consumeRestored("draft"))
        assertNull(cache.acquire("different account/a") { true }.consumeRestored("draft"))
    }

    @Test fun navigationCapturesBeforeExitAnimationsCanChangeTheOldScreensGeometry() {
        val cache = SessionUiStateCache()
        val registry = cache.acquire("a") { true }
        var offset = 37
        registry.registerProvider("position") { offset }
        cache.captureBeforeNavigation()
        offset = 111 // Exit/enter transitions can remeasure the outgoing screen.
        cache.release("a", registry)
        assertEquals(37, cache.acquire("a") { true }.consumeRestored("position"))
    }
}
