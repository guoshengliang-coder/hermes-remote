package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.chat.ApprovalRequest
import com.hermes.client.ui.chat.ChatUiState
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPlannerTest {
    private val on = NotificationPrefs(enabled = true)
    private val a = SessionRuntimeKey("work", "a")
    private val b = SessionRuntimeKey("work", "b")

    private fun runtime(
        key: SessionRuntimeKey,
        phase: SessionRunPhase,
        title: String? = "Task ${key.sessionId}",
        approval: ApprovalRequest? = null,
    ) = SessionRuntime(
        key = key,
        phase = phase,
        title = title,
        chat = ChatUiState(pendingApproval = approval),
        occurredAt = 1_000L,
    )

    private val approval = ApprovalRequest("ls", "", emptyList(), allowPermanent = true)

    private fun plan(
        runtimes: List<SessionRuntime>,
        visible: Set<SessionRuntimeKey> = emptySet(),
        foreground: Boolean = false,
        dismissed: Map<SessionRuntimeKey, NotificationKind> = emptyMap(),
        actionStates: Map<SessionRuntimeKey, NotificationActionState> = emptyMap(),
        titleOf: (SessionRuntimeKey) -> String? = { null },
    ) = planNotifications(runtimes, visible, foreground, on, AppLanguage.EN, showProfile = false, titleOf, actionStates, dismissed)

    @Test fun background_posts_every_session_that_has_a_card() {
        val plan = plan(listOf(runtime(a, SessionRunPhase.THINKING), runtime(b, SessionRunPhase.WAITING_APPROVAL, approval = approval)))
        assertEquals(setOf(a, b), plan.cards.keys)
        assertTrue(plan.cards.values.none { it.silent && it.kind != NotificationKind.RUNNING })
        assertEquals(NotificationSummary(waiting = 1, running = 1, finished = 0), plan.summary)
    }

    @Test fun the_chat_being_looked_at_gets_no_card_but_the_others_go_silent() {
        val plan = plan(
            listOf(runtime(a, SessionRunPhase.WAITING_APPROVAL, approval = approval), runtime(b, SessionRunPhase.COMPLETED_UNREAD)),
            visible = setOf(a),
            foreground = true,
        )
        assertEquals(setOf(b), plan.cards.keys)
        assertTrue(plan.cards.getValue(b).silent)
        assertTrue(plan.cards.getValue(b).onlyAlertOnce)
        assertTrue(plan.seen.isEmpty())
    }

    @Test fun a_visible_chat_in_the_background_still_gets_its_card() {
        val plan = plan(listOf(runtime(a, SessionRunPhase.COMPLETED_UNREAD)), visible = setOf(a), foreground = false)
        assertEquals(setOf(a), plan.cards.keys)
        assertFalse(plan.cards.getValue(a).silent)
    }

    @Test fun terminal_cards_seen_in_the_open_chat_are_reported_so_they_do_not_return() {
        val plan = plan(listOf(runtime(a, SessionRunPhase.FAILED), runtime(b, SessionRunPhase.WAITING_ATTENTION)), visible = setOf(a, b), foreground = true)
        assertEquals(mapOf(a to NotificationKind.FAILED), plan.seen)
        assertTrue(plan.cards.isEmpty())
    }

    @Test fun a_dismissed_kind_stays_gone_until_the_kind_changes() {
        val dismissed = mapOf(a to NotificationKind.RUNNING)
        assertTrue(plan(listOf(runtime(a, SessionRunPhase.THINKING)), dismissed = dismissed).cards.isEmpty())
        assertEquals(setOf(a), plan(listOf(runtime(a, SessionRunPhase.COMPLETED_UNREAD)), dismissed = dismissed).cards.keys)
    }

    @Test fun runtime_title_wins_over_the_cached_session_title_which_wins_over_the_fallback() {
        val cached: (SessionRuntimeKey) -> String? = { key -> if (key == b) "Cached B" else null }
        val plan = plan(
            listOf(runtime(a, SessionRunPhase.THINKING, title = "Live A"), runtime(b, SessionRunPhase.THINKING, title = null), runtime(SessionRuntimeKey("work", "c"), SessionRunPhase.THINKING, title = null)),
            titleOf = cached,
        )
        assertEquals("Live A", plan.cards.getValue(a).title)
        assertEquals("Cached B", plan.cards.getValue(b).title)
        assertEquals("New chat", plan.cards.getValue(SessionRuntimeKey("work", "c")).title)
    }

    @Test fun action_state_reaches_the_projected_card() {
        val plan = plan(
            listOf(runtime(a, SessionRunPhase.WAITING_APPROVAL, approval = approval)),
            actionStates = mapOf(a to NotificationActionState.PENDING),
        )
        assertEquals("Working…", plan.cards.getValue(a).body)
    }

    @Test fun diff_posts_new_cards_refreshes_same_kind_silently_and_realerts_on_kind_change() {
        val running = plan(listOf(runtime(a, SessionRunPhase.THINKING)))
        val first = diffPlan(emptyMap(), null, running)
        assertEquals(listOf<NotificationOp>(NotificationOp.Post(running.cards.getValue(a))), first)

        val tool = plan(listOf(runtime(a, SessionRunPhase.USING_TOOL).copy(toolName = "terminal")))
        val refresh = diffPlan(running.cards, running.summary, tool)
        val post = refresh.single() as NotificationOp.Post
        assertEquals("Calling terminal", post.spec.body)
        assertTrue(post.spec.onlyAlertOnce)

        val done = plan(listOf(runtime(a, SessionRunPhase.COMPLETED_UNREAD)))
        val alert = diffPlan(tool.cards, tool.summary, done).single() as NotificationOp.Post
        assertEquals(NotificationKind.COMPLETED, alert.spec.kind)
        assertFalse(alert.spec.onlyAlertOnce)
    }

    @Test fun diff_is_empty_when_nothing_changed_and_cancels_removed_cards() {
        val current = plan(listOf(runtime(a, SessionRunPhase.THINKING)))
        assertTrue(diffPlan(current.cards, current.summary, current).isEmpty())
        val gone = plan(listOf(runtime(a, SessionRunPhase.IDLE)))
        assertEquals(listOf<NotificationOp>(NotificationOp.Cancel(current.cards.getValue(a).id, a)), diffPlan(current.cards, current.summary, gone))
    }

    @Test fun diff_emits_summary_changes_only_when_the_counts_change() {
        val one = plan(listOf(runtime(a, SessionRunPhase.THINKING)))
        val two = plan(listOf(runtime(a, SessionRunPhase.THINKING), runtime(b, SessionRunPhase.COMPLETED_UNREAD)))
        val ops = diffPlan(one.cards, one.summary, two)
        assertTrue(ops.any { it is NotificationOp.Post && it.spec.sessionKey == b })
        assertEquals(NotificationOp.Summary(NotificationSummary(0, 1, 1)), ops.last())
        val back = diffPlan(two.cards, two.summary, one)
        assertEquals(NotificationOp.Summary(null), back.last())
    }
}
