package com.hermes.client.ui.sessions

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

private fun msg(text: String, role: Role = Role.USER, displayKind: String? = null) =
    ChatMessage(id = "m", role = role, text = text, displayKind = displayKind)

class BotTranscriptKindTest {
    /** The bug this exists for: a delegation report was drawn as a chat bubble with the DingTalk
     *  peer's name above it, as if a person had typed it. */
    @Test fun a_delegation_report_is_a_note_not_somebody_speaking() {
        assertEquals(
            BotTurnKind.NOTE,
            botTurnKind(msg("[ASYNC DELEGATION COMPLETE — deleg_a44fed01]\nA background subagent…")),
        )
    }

    @Test fun a_background_process_report_is_a_note_too() {
        assertEquals(
            BotTurnKind.NOTE,
            botTurnKind(msg("[IMPORTANT: Background process 42 matched watch pattern …")),
        )
    }

    @Test fun a_real_message_from_the_peer_stays_a_turn() {
        assertEquals(BotTurnKind.TURN, botTurnKind(msg("昨天公司数据如何？")))
        assertEquals(BotTurnKind.TURN, botTurnKind(msg("按新版口径。", role = Role.ASSISTANT)))
    }

    /** Someone quoting a marker mid-sentence is still saying something. */
    @Test fun a_person_quoting_a_marker_is_not_reclassified() {
        assertEquals(
            BotTurnKind.TURN,
            botTurnKind(msg("那个 [ASYNC DELEGATION COMPLETE] 的提示能不能别显示？".replaceFirst("那", "那"))),
        )
    }

    @Test fun an_empty_turn_is_not_drawn() {
        assertEquals(BotTurnKind.HIDDEN, botTurnKind(msg("   ")))
    }
}
