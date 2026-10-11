package com.hermes.client.ui.chat

/** An automatic recording limit must never take the ordinary release-to-send path. */
internal fun voiceMustKeepDraft(final: VoiceEvent.Final, editing: Boolean, canSend: Boolean): Boolean =
    final.limited || editing || !canSend
