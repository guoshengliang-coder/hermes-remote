package com.hermes.client.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BotTranscriptState(
    val messages: List<ChatMessage> = emptyList(),
    val title: String = "",
    val source: String? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
)

@HiltViewModel
class BotTranscriptViewModel @Inject constructor(
    private val sessions: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BotTranscriptState())
    val state: StateFlow<BotTranscriptState> = _state.asStateFlow()

    fun load(sessionId: String, profile: String?) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        // Title and channel come from the same coalesced list the segment already fetched, so
        // opening a row does not put them through the route as encoded strings.
        val row = runCatching { sessions.botSessions().firstOrNull { it.id == sessionId } }.getOrNull()
        runCatching { sessions.history(sessionId, profile) }
            .onSuccess {
                _state.value = BotTranscriptState(
                    messages = it, title = row?.title.orEmpty(), source = row?.source, loading = false,
                )
            }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    title = row?.title.orEmpty(),
                    source = row?.source,
                    error = AppError(
                        AppErrorCode.HISTORY_INCOMPLETE,
                        retryable = true,
                        technicalCause = it.message,
                        stage = "bot_transcript",
                    ),
                )
            }
    }
}

/**
 * A bot conversation, read-only.
 *
 * There is no composer and no action, and that is the design rather than an unfinished screen:
 * Hermes is a *bot* on these platforms, so anything sent from here would appear as the bot and
 * never as you. Handoff cannot rescue it either — it only moves a local session out to a platform,
 * and it needs a session live in the dashboard process, which a channel session is not.
 *
 * Bubble ownership follows the chat screen (docs/DESIGN.md §5.4): whoever is speaking to Hermes
 * gets the bubble, Hermes' replies are plain type. In a DM that speaker is you, reaching Hermes
 * through the other app; in a group it is someone else, which the banner names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotTranscriptScreen(
    sessionId: String,
    profile: String?,
    onBack: () -> Unit,
    vm: BotTranscriptViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    LaunchedEffect(sessionId, profile) { vm.load(sessionId, profile) }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = state.title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Says whose conversation this is before a single line is read.
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        localized(
                            language,
                            "来自${botSourceLabel(state.source ?: "")} · 只读",
                            "From ${botSourceLabel(state.source ?: "")} · read-only",
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxSize()) {
                when {
                    state.loading -> com.hermes.client.ui.components.LoadingState()
                    state.error != null -> com.hermes.client.ui.components.ErrorState(
                        error = state.error!!,
                        onRetry = { vm.load(sessionId, profile) },
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            BotTranscriptTurn(message)
                        }
                    }
                }
            }

            Text(
                localized(
                    language,
                    "Hermes 在这里是机器人身份，你发不了言，这条对话也拉不回手机。",
                    "Hermes is a bot on this platform: you can't speak here, and this conversation can't be moved to the phone.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun BotTranscriptTurn(message: ChatMessage) {
    // The chat screen strips Hermes' compression scaffolding in ChatUiState; this renderer is a
    // second path to the same history and has to do the same, or a turn that arrived with pages of
    // machine text stapled to it shows all of it. Timeline notes are collapsed to nothing here:
    // this is a read-only record, and a turn that was ONLY scaffolding is not something anyone said.
    val body = com.hermes.client.ui.chat.withoutCompressionScaffolding(message.text).trim()
    if (body.isBlank()) return
    if (message.role == Role.USER) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    } else {
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
}
