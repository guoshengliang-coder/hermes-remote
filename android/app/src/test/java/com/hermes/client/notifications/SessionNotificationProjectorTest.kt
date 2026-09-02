package com.hermes.client.notifications

import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntimeKey
import com.hermes.client.ui.chat.ApprovalRequest
import com.hermes.client.ui.chat.ClarifyQuestion
import com.hermes.client.ui.chat.ClarifyRequest
import com.hermes.client.ui.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationProjectorTest {
    private val on = NotificationPrefs(enabled = true)
    private val key = SessionRuntimeKey("work", "stored-1")

    private fun input(
        phase: SessionRunPhase,
        title: String? = "Fix build script",
        toolName: String? = null,
        todoDone: Int = 0,
        todoTotal: Int = 0,
        approval: ApprovalRequest? = null,
        clarify: ClarifyRequest? = null,
        lastAssistantText: String? = null,
        runStartedAt: Long? = null,
        eventAt: Long = 1_000_000L,
        showProfile: Boolean = false,
        actionState: NotificationActionState = NotificationActionState.NONE,
        key: SessionRuntimeKey = this.key,
    ) = SessionNotificationInput(
        key = key,
        liveSessionId = "runtime-1",
        title = title,
        phase = phase,
        toolName = toolName,
        todoDone = todoDone,
        todoTotal = todoTotal,
        pendingApproval = approval,
        pendingClarify = clarify,
        lastAssistantText = lastAssistantText,
        runStartedAt = runStartedAt,
        eventAt = eventAt,
        showProfile = showProfile,
        actionState = actionState,
    )

    private fun approval(command: String = "rm -rf build && npm run build", description: String = "", allowPermanent: Boolean = true) =
        ApprovalRequest(command = command, description = description, patternKeys = emptyList(), allowPermanent = allowPermanent)

    private fun clarify(vararg choices: String, question: String = "Deploy where?", multiSelect: Boolean = false, qid: String = "q1") =
        ClarifyRequest(requestId = "req-9", questions = listOf(ClarifyQuestion(qid, question, choices.toList(), multiSelect)))

    @Test fun disabled_or_idle_has_no_card() {
        assertNull(projectSessionNotification(input(SessionRunPhase.THINKING), NotificationPrefs(enabled = false)))
        assertNull(projectSessionNotification(input(SessionRunPhase.IDLE), on))
    }

    @Test fun every_kind_of_the_same_session_shares_one_id_and_route() {
        val ids = listOf(
            input(SessionRunPhase.THINKING),
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval()),
            input(SessionRunPhase.COMPLETED_UNREAD),
            input(SessionRunPhase.FAILED),
        ).map { projectSessionNotification(it, on)!! }
        assertEquals(1, ids.map { it.id }.distinct().size)
        assertTrue(ids.all { it.route == "chat/stored-1?profile=work" })
        assertTrue(ids.all { it.groupKey == Notif.GROUP_SESSIONS && it.sessionKey == key })
    }

    @Test fun ids_are_stable_never_reserved_and_differ_by_profile() {
        assertEquals(notificationIdFor(key), notificationIdFor(SessionRuntimeKey("work", "stored-1")))
        assertNotEquals(notificationIdFor(key), notificationIdFor(SessionRuntimeKey("personal", "stored-1")))
        assertFalse(notificationIdFor(key) in Notif.RESERVED_IDS)
    }

    @Test fun running_card_is_a_silent_ongoing_progress_card_with_tool_step_and_chronometer() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.USING_TOOL, toolName = "terminal", todoDone = 3, todoTotal = 7, runStartedAt = 500L),
            on,
        )!!
        assertEquals(NotificationKind.RUNNING, spec.kind)
        assertEquals(Notif.CHANNEL_RUN_PROGRESS, spec.channelId)
        assertEquals("Fix build script", spec.title)
        assertEquals("Running", spec.stateLabel)
        assertEquals("Calling terminal · Step 3/7", spec.body)
        assertEquals(NotificationProgress(3, 7, indeterminate = false, shortText = "3/7"), spec.progress)
        assertEquals(500L, spec.whenMs)
        assertTrue(spec.chronometer && spec.ongoing && spec.silent && spec.onlyAlertOnce && !spec.autoCancel)
        assertEquals("work", spec.accentProfile)
        assertNull(spec.profileLabel)
    }

    @Test fun running_without_tool_or_todos_thinks_indeterminately() {
        val spec = projectSessionNotification(input(SessionRunPhase.THINKING), on)!!
        assertEquals("Thinking…", spec.body)
        assertTrue(spec.progress!!.indeterminate)
        assertNull(spec.progress!!.shortText)
        assertFalse(spec.chronometer)
    }

    @Test fun reconnecting_updates_the_same_ongoing_card() {
        val spec = projectSessionNotification(input(SessionRunPhase.RECONNECTING), on)!!
        assertEquals(NotificationKind.RECONNECTING, spec.kind)
        assertEquals("Reconnecting", spec.stateLabel)
        assertEquals("Connection lost, reconnecting", spec.body)
        assertTrue(spec.ongoing && spec.silent)
    }

    @Test fun progress_pref_off_hides_running_only() {
        val prefs = on.copy(runProgress = false)
        assertNull(projectSessionNotification(input(SessionRunPhase.THINKING), prefs))
        assertNull(projectSessionNotification(input(SessionRunPhase.RECONNECTING), prefs))
        assertEquals(NotificationKind.COMPLETED, projectSessionNotification(input(SessionRunPhase.COMPLETED_UNREAD), prefs)!!.kind)
    }

    @Test fun standard_approval_offers_allow_once_session_and_deny_with_command_body() {
        val spec = projectSessionNotification(input(SessionRunPhase.WAITING_APPROVAL, approval = approval()), on)!!
        assertEquals(NotificationKind.NEEDS_APPROVAL, spec.kind)
        assertEquals(Notif.CHANNEL_ATTENTION, spec.channelId)
        assertEquals("Approval needed", spec.stateLabel)
        assertEquals("Wants to run a command\nrm -rf build && npm run build", spec.body)
        assertEquals(listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_ALLOW_SESSION, Notif.ACTION_DENY), spec.actions.map { it.action })
        assertTrue(spec.actions.all { it.sessionId == "runtime-1" && it.storedSessionId == "stored-1" && it.profile == "work" })
        assertFalse(spec.autoCancel)
    }

    @Test fun elevated_approval_offers_deny_and_open_and_flags_risk() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(allowPermanent = false)),
            on,
        )!!
        assertEquals(listOf(Notif.ACTION_DENY, Notif.ACTION_OPEN), spec.actions.map { it.action })
        assertTrue(spec.body.startsWith("High-risk action\n"))
    }

    @Test fun approval_description_leads_and_command_follows_without_duplication() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(command = "git push", description = "Push to origin")),
            on,
        )!!
        assertEquals("Push to origin\ngit push", spec.body)
        val same = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(command = "git push", description = "git push")),
            on,
        )!!
        assertEquals("git push", same.body)
    }

    @Test fun an_answered_wait_projects_as_running_until_the_gateway_moves_on() {
        // In-app respondApproval / the shade receiver clear the request before continueAfterInput;
        // that intermediate state must never flash a "waiting for you" card on the alerting channel.
        val approval = projectSessionNotification(input(SessionRunPhase.WAITING_APPROVAL), on)!!
        assertEquals(NotificationKind.RUNNING, approval.kind)
        val answered = clarify("a", "b").let { it.copy(lockedAnswers = mapOf("q1" to "a")) }
        val clarifyDone = projectSessionNotification(input(SessionRunPhase.WAITING_CLARIFICATION, clarify = answered), on)!!
        assertEquals(NotificationKind.RUNNING, clarifyDone.kind)
        assertEquals(NotificationKind.RUNNING, projectSessionNotification(input(SessionRunPhase.WAITING_CLARIFICATION), on)!!.kind)
    }

    @Test fun observed_waits_without_details_are_generic_attention_cards() {
        val spec = projectSessionNotification(input(SessionRunPhase.WAITING_ATTENTION), on)!!
        assertEquals(NotificationKind.NEEDS_ATTENTION, spec.kind)
        assertEquals("Waiting for you", spec.stateLabel)
        assertEquals(listOf(Notif.ACTION_OPEN), spec.actions.map { it.action })
    }

    @Test fun two_choices_become_buttons_plus_reply() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_CLARIFICATION, clarify = clarify("staging", "production")),
            on,
        )!!
        assertEquals(NotificationKind.NEEDS_ANSWER, spec.kind)
        assertEquals("Needs your answer", spec.stateLabel)
        assertEquals("Deploy where?", spec.body)
        assertEquals(listOf("staging", "production", "Reply…"), spec.actions.map { it.label })
        assertEquals(listOf(Notif.ACTION_CHOICE, Notif.ACTION_CHOICE, Notif.ACTION_REPLY), spec.actions.map { it.action })
        assertEquals("staging", spec.actions[0].answer)
        assertTrue(spec.actions.all { it.requestId == "req-9" && it.questionId == "q1" })
        assertTrue(spec.actions.last().reply)
    }

    @Test fun three_choices_list_numbered_options_and_offer_reply_and_open() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_CLARIFICATION, clarify = clarify("a", "b", "c")),
            on,
        )!!
        assertEquals("Deploy where?\n1. a\n2. b\n3. c", spec.body)
        assertEquals(listOf(Notif.ACTION_REPLY, Notif.ACTION_OPEN), spec.actions.map { it.action })
    }

    @Test fun multi_select_never_becomes_buttons() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_CLARIFICATION, clarify = clarify("a", "b", multiSelect = true)),
            on,
        )!!
        assertEquals(listOf(Notif.ACTION_REPLY, Notif.ACTION_OPEN), spec.actions.map { it.action })
    }

    @Test fun batch_clarify_shows_the_next_unanswered_question() {
        val request = ClarifyRequest(
            requestId = "req-9",
            questions = listOf(ClarifyQuestion("q1", "First?", listOf("x", "y")), ClarifyQuestion("q2", "Second?")),
            lockedAnswers = mapOf("q1" to "x"),
        )
        val spec = projectSessionNotification(input(SessionRunPhase.WAITING_CLARIFICATION, clarify = request), on)!!
        assertEquals("Second?", spec.body)
        assertEquals("q2", spec.actions.first().questionId)
    }

    @Test fun single_question_without_qid_sends_no_question_id() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_CLARIFICATION, clarify = clarify("a", "b", qid = "")),
            on,
        )!!
        assertTrue(spec.actions.all { it.questionId == null })
    }

    @Test fun approvals_pref_off_hides_every_needs_you_kind() {
        val prefs = on.copy(approvals = false)
        assertNull(projectSessionNotification(input(SessionRunPhase.WAITING_APPROVAL, approval = approval()), prefs))
        assertNull(projectSessionNotification(input(SessionRunPhase.WAITING_CLARIFICATION, clarify = clarify("a")), prefs))
        assertNull(projectSessionNotification(input(SessionRunPhase.WAITING_ATTENTION), prefs))
    }

    @Test fun completed_card_quotes_the_final_reply_and_the_duration() {
        val spec = projectSessionNotification(
            input(
                SessionRunPhase.COMPLETED_UNREAD,
                lastAssistantText = "Done.\n\nThe README   now has\tboth languages.",
                runStartedAt = 1_000_000L - 192_000L,
            ),
            on,
        )!!
        assertEquals(NotificationKind.COMPLETED, spec.kind)
        assertEquals(Notif.CHANNEL_COMPLETED, spec.channelId)
        assertEquals("Done", spec.stateLabel)
        assertEquals("Done. The README now has both languages.", spec.body)
        assertEquals("Took 3m 12s", spec.subText)
        assertEquals(1_000_000L, spec.whenMs)
        assertTrue(spec.actions.isEmpty() && spec.autoCancel && !spec.ongoing && !spec.silent)
    }

    @Test fun completed_without_a_reply_falls_back_and_omits_duration() {
        val spec = projectSessionNotification(input(SessionRunPhase.COMPLETED_UNREAD), on)!!
        assertEquals("Tap to view the result", spec.body)
        assertNull(spec.subText)
        assertNull(projectSessionNotification(input(SessionRunPhase.COMPLETED_UNREAD), on.copy(runFinished = false)))
    }

    @Test fun reply_snippet_is_capped_at_140_characters() {
        val long = "x".repeat(300)
        val spec = projectSessionNotification(input(SessionRunPhase.COMPLETED_UNREAD, lastAssistantText = long), on)!!
        assertEquals(140, spec.body.length)
        assertTrue(spec.body.endsWith("…"))
    }

    @Test fun failed_card_keeps_the_error_code_out_of_the_body() {
        val spec = projectSessionNotification(input(SessionRunPhase.FAILED), on)!!
        assertEquals(NotificationKind.FAILED, spec.kind)
        assertEquals(Notif.CHANNEL_FAILURES, spec.channelId)
        assertEquals("Run failed", spec.stateLabel)
        assertEquals("The agent run failed. You can retry.", spec.body)
        assertEquals("HR-RPC-001", spec.subText)
        assertEquals(listOf(Notif.ACTION_OPEN), spec.actions.map { it.action })
        assertNull(projectSessionNotification(input(SessionRunPhase.FAILED), on.copy(runFailed = false)))
    }

    @Test fun unconfirmed_card_uses_its_own_code_and_the_failures_channel() {
        val spec = projectSessionNotification(input(SessionRunPhase.INTERRUPTED), on)!!
        assertEquals(NotificationKind.UNCONFIRMED, spec.kind)
        assertEquals(Notif.CHANNEL_FAILURES, spec.channelId)
        assertEquals("HR-SYNC-002", spec.subText)
        assertEquals("Needs a check", spec.stateLabel)
        assertNull(projectSessionNotification(input(SessionRunPhase.INTERRUPTED), on.copy(runFailed = false)))
    }

    @Test fun identity_name_only_appears_in_the_header_for_multi_profile_users() {
        assertNull(projectSessionNotification(input(SessionRunPhase.THINKING), on)!!.profileLabel)
        assertEquals("work", projectSessionNotification(input(SessionRunPhase.THINKING, showProfile = true), on)!!.profileLabel)
        val noProfile = projectSessionNotification(
            input(SessionRunPhase.THINKING, showProfile = true, key = SessionRuntimeKey(null, "s")),
            on,
        )!!
        assertNull(noProfile.profileLabel)
        assertNull(noProfile.accentProfile)
        assertEquals("chat/s", noProfile.route)
    }

    @Test fun public_version_carries_only_title_and_state_word() {
        val spec = projectSessionNotification(input(SessionRunPhase.WAITING_APPROVAL, approval = approval()), on)!!
        assertEquals("Fix build script", spec.publicTitle)
        assertEquals("Approval needed", spec.publicBody)
        assertFalse(spec.publicBody!!.contains("rm -rf"))
    }

    @Test fun missing_title_reads_new_chat() {
        assertEquals("New chat", projectSessionNotification(input(SessionRunPhase.THINKING, title = "  "), on)!!.title)
        assertEquals("新会话", projectSessionNotification(input(SessionRunPhase.THINKING, title = null), on, AppLanguage.ZH)!!.title)
    }

    @Test fun pending_action_replaces_body_and_removes_buttons() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(), actionState = NotificationActionState.PENDING),
            on,
        )!!
        assertEquals("Working…", spec.body)
        assertTrue(spec.actions.isEmpty())
        assertEquals(NotificationKind.NEEDS_APPROVAL, spec.kind)
        assertTrue(spec.onlyAlertOnce)
    }

    @Test fun failed_action_keeps_buttons_and_adds_the_notif_code() {
        val spec = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(), actionState = NotificationActionState.FAILED),
            on,
        )!!
        assertTrue(spec.body.endsWith("\nCouldn't send. Try again."))
        assertEquals("HR-NOTIF-001", spec.subText)
        assertEquals(3, spec.actions.size)
    }

    @Test fun chinese_copy_follows_the_app_language() {
        val approval = projectSessionNotification(input(SessionRunPhase.WAITING_APPROVAL, approval = approval()), on, AppLanguage.ZH)!!
        assertEquals("需要审批", approval.stateLabel)
        assertEquals("要运行命令\nrm -rf build && npm run build", approval.body)
        assertEquals(listOf("允许一次", "本会话允许", "拒绝"), approval.actions.map { it.label })
        val done = projectSessionNotification(
            input(SessionRunPhase.COMPLETED_UNREAD, runStartedAt = 1_000_000L - 45_000L),
            on,
            AppLanguage.ZH,
        )!!
        assertEquals("已完成", done.stateLabel)
        assertEquals("点击查看结果", done.body)
        assertEquals("用时 45 秒", done.subText)
        val failed = projectSessionNotification(input(SessionRunPhase.FAILED), on, AppLanguage.ZH)!!
        assertEquals("智能体运行失败，可以重试。", failed.body)
        assertEquals("查看详情", failed.actions.single().label)
    }

    @Test fun summary_counts_by_kind_and_needs_two_cards() {
        val running = projectSessionNotification(input(SessionRunPhase.THINKING), on)!!
        val waiting = projectSessionNotification(
            input(SessionRunPhase.WAITING_APPROVAL, approval = approval(), key = SessionRuntimeKey("work", "s2")),
            on,
        )!!
        val done = projectSessionNotification(input(SessionRunPhase.COMPLETED_UNREAD, key = SessionRuntimeKey("work", "s3")), on)!!
        assertNull(summarize(listOf(running)))
        val summary = summarize(listOf(running, waiting, done))!!
        assertEquals(NotificationSummary(waiting = 1, running = 1, finished = 1), summary)
        assertEquals("1 waiting for you · 1 running · 1 finished", summary.label(AppLanguage.EN))
        assertEquals("1 个等待处理 · 1 个运行中 · 1 个已结束", summary.label(AppLanguage.ZH))
    }

    @Test fun long_choice_labels_are_trimmed_for_buttons() {
        assertEquals("a".repeat(20), choiceLabel("a".repeat(20)))
        assertEquals("a".repeat(19) + "…", choiceLabel("a".repeat(30)))
    }
}
