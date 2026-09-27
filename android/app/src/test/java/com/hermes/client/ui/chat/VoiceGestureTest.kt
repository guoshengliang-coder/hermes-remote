package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceGestureTest {
    @Test fun releaseDirection_selectsSendCancelOrEdit() {
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(20f, -20f, 200f, 80f))
        assertEquals(VoiceReleaseAction.CANCEL, voiceReleaseAction(30f, -100f, 200f, 80f))
        assertEquals(VoiceReleaseAction.EDIT, voiceReleaseAction(170f, -100f, 200f, 80f))
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(100f, -100f, 200f, 80f))
        assertEquals(VoiceReleaseAction.SEND, voiceReleaseAction(230f, -79f, 200f, 80f))
    }
}
