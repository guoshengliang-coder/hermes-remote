package com.hermes.client.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import com.hermes.client.ui.workspace.rememberConversationValue
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.FileTransferState

/** File metadata only: no player, credentials, bytes or expiring URL. */
internal val VideoFileSaver = Saver<ChatFile?, List<Any?>>(
    save = { file -> file?.let { listOf(it.id, it.name, it.mimeType, it.sizeBytes, it.localPath, it.remotePath, it.state.name) } ?: emptyList() },
    restore = { values ->
        if (values.isEmpty()) null else ChatFile(values[0] as String, values[1] as String,
            values[2] as String?, values[3] as Long?, values[4] as String?, values[5] as String?,
            FileTransferState.valueOf(values[6] as String))
    },
)

private val VideoSelectionSaver = Saver<MutableState<ChatFile?>, List<Any?>>(
    save = { state -> with(VideoFileSaver) { save(state.value) } },
    restore = { mutableStateOf(VideoFileSaver.restore(it)) },
)

@Composable
internal fun rememberVideoFile(workspace: String): MutableState<ChatFile?> =
    rememberConversationValue("video:$workspace", VideoSelectionSaver) { mutableStateOf(null) }
