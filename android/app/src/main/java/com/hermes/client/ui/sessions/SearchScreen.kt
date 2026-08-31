package com.hermes.client.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title match with its archived flag (archived sessions are first-class search results). */
data class TitleMatch(val session: Session, val archived: Boolean)

data class SearchUiState(
    val query: String = "",
    val titleMatches: List<TitleMatch> = emptyList(),
    val messageResults: List<SearchResultDto> = emptyList(),
    val searching: Boolean = false,
)

/**
 * Search, moved off the list into its own screen (the list's field was always-on chrome for a
 * low-frequency action). Scope = the ACTIVE profile, archived included: titles filter instantly
 * and client-side; message content hits the gateway only on the explicit Search action.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // Live + archived rows for the active profile, fetched once per entry (refresh() re-pulls).
    private var live: List<Session> = emptyList()
    private var archived: List<Session> = emptyList()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val active = profileManager.active.value
        fun scope(all: List<Session>) =
            if (active.isNullOrBlank()) all else all.filter { it.profile == active }
        live = runCatching { sessions.listAllProfiles() }.getOrNull()?.let(::scope) ?: emptyList()
        archived = runCatching { sessions.archivedAllProfiles() }.getOrNull()?.let(::scope) ?: emptyList()
        applyFilter()
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
        if (q.isBlank()) _state.value = _state.value.copy(messageResults = emptyList())
        applyFilter()
    }

    private fun applyFilter() {
        val q = _state.value.query.trim()
        val matches = if (q.isEmpty()) emptyList() else buildList {
            live.filter { it.title.contains(q, true) || it.workspace.contains(q, true) }
                .forEach { add(TitleMatch(it, archived = false)) }
            archived.filter { it.title.contains(q, true) }
                .forEach { add(TitleMatch(it, archived = true)) }
        }
        _state.value = _state.value.copy(titleMatches = matches)
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Gateway full-text search over message content. Cancels any in-flight query. */
    fun searchMessages() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val q = _state.value.query.trim()
            if (q.isBlank()) { _state.value = _state.value.copy(messageResults = emptyList()); return@launch }
            _state.value = _state.value.copy(searching = true)
            runCatching { sessions.search(q, profileManager.active.value) }
                .onSuccess { _state.value = _state.value.copy(messageResults = it, searching = false) }
                .onFailure { _state.value = _state.value.copy(messageResults = emptyList(), searching = false) }
        }
    }

    /** Ensure the tapped session's profile is active before the chat opens (same as the list). */
    suspend fun prepareOpen(session: Session): Boolean {
        val target = session.profile ?: return true
        return target == profileManager.active.value || profileManager.switchTo(target)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpen: (ChatLaunch) -> Unit,
    onBack: () -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun openExisting(session: Session) {
        scope.launch {
            if (vm.prepareOpen(session)) onOpen(ChatLaunch.existing(session))
            else Toast.makeText(context, localized(language, "无法切换到该会话所属身份，请稍后重试", "Could not switch to this session's profile. Try again."), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
                }
                TextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = { Text(localized(language, "搜索会话…", "Search sessions…")) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchMessages() }),
                    trailingIcon = {
                        if (state.query.isNotBlank()) {
                            IconButton(onClick = { vm.onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = localized(language, "清除搜索", "Clear search"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.titleMatches.isNotEmpty()) {
                    item(key = "h-title") { SearchHeader(localized(language, "标题匹配", "Title matches"), state.titleMatches.size) }
                    items(state.titleMatches, key = { "t-${it.archived}-${it.session.profile.orEmpty()}:${it.session.id}" }) { m ->
                        ListItem(
                            headlineContent = { Text(m.session.title) },
                            supportingContent = { Text(m.session.model ?: "") },
                            trailingContent = if (m.archived) ({
                                Text(
                                    localized(language, "已归档", "Archived"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }) else null,
                            modifier = Modifier.clickable { openExisting(m.session) },
                        )
                        HorizontalDivider()
                    }
                }
                if (state.messageResults.isNotEmpty()) {
                    item(key = "h-msg") { SearchHeader(localized(language, "消息匹配", "Message matches"), state.messageResults.size) }
                    items(state.messageResults) { r ->
                        ListItem(
                            headlineContent = { Text(r.snippet?.take(140)?.replace("\n", " ") ?: r.sessionId) },
                            supportingContent = { Text(r.model ?: r.role ?: "") },
                            modifier = Modifier.clickable { onOpen(ChatLaunch.unknown(r.sessionId)) },
                        )
                        HorizontalDivider()
                    }
                }
                if (state.query.isNotBlank() && state.titleMatches.isEmpty() && state.messageResults.isEmpty() && !state.searching) {
                    item(key = "empty") {
                        Text(
                            localized(
                                language,
                                "没有标题匹配“${state.query.trim()}”。按键盘上的搜索键可搜索消息正文。",
                                "No titles match \"${state.query.trim()}\". Press search on the keyboard to search message text.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (state.searching) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SearchHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
