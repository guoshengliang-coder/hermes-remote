package com.hermes.client.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.SearchResultDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ProjectPrefsStore
import com.hermes.client.data.repository.RecentSearchesStore
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.domain.Session
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.components.SearchField
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title match with its archived flag (archived sessions are first-class search results). */
data class TitleMatch(val session: Session, val archived: Boolean)

/** One message hit, resolved for the row: title/time/project come from the local session when known. */
data class MessageHit(
    val sessionId: String,
    val profile: String?,
    val title: String,
    val snippet: String,
    val lastActiveMs: Long?,
    val archived: Boolean,
    val projectLabel: String?,
)

/** Lifecycle of the message (gateway) section — docs/DESIGN.md §5.2 搜索页, six states. */
sealed interface MessageSearch {
    /** Query shorter than [SearchViewModel.MIN_MESSAGE_QUERY]; nothing to search. */
    data object Idle : MessageSearch
    /** Query changed; the debounce window is running. Old results are gone. */
    data object Pending : MessageSearch
    data class Searching(val query: String) : MessageSearch
    data class Results(val query: String, val hits: List<MessageHit>) : MessageSearch
    data class Empty(val query: String) : MessageSearch
    data class Failed(val query: String, val error: AppError) : MessageSearch

    val queryOrNull: String?
        get() = when (this) {
            is Searching -> query
            is Results -> query
            is Empty -> query
            is Failed -> query
            else -> null
        }
}

data class SearchUiState(
    val query: String = "",
    val titleMatches: List<TitleMatch> = emptyList(),
    val messages: MessageSearch = MessageSearch.Idle,
    val recent: List<String> = emptyList(),
) {
    val searching: Boolean get() = messages is MessageSearch.Searching
}

/**
 * Search screen state. Scope = the ACTIVE profile, archived included. Titles (and project
 * labels) filter instantly and client-side; message content goes to the gateway automatically
 * after a debounce, or immediately on the keyboard's Search action. Failures surface as
 * HR-SEARCH-001 in the message section with Retry; title matches are never affected.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
    projectPrefs: ProjectPrefsStore,
    private val recentStore: RecentSearchesStore,
) : ViewModel() {
    companion object {
        const val MIN_MESSAGE_QUERY = 2
        const val DEBOUNCE_MS = 450L
        private const val SAVED_QUERY = "q"
    }

    private val _state = MutableStateFlow(SearchUiState(query = savedState.get<String>(SAVED_QUERY).orEmpty()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Gateway launch directory (the default project) so result sublines fold it correctly. */
    val defaultProjectPath: StateFlow<String?> =
        projectPrefs.defaultProjectPath.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The live or archived session a message hit belongs to (null when it is not in scope). */
    fun sessionFor(sessionId: String): Session? =
        live.firstOrNull { it.id == sessionId } ?: archived.firstOrNull { it.id == sessionId }

    // Live + archived rows for the active profile. First frame comes from the repository cache;
    // refresh() re-pulls both lists.
    private var live: List<Session> = emptyList()
    private var archived: List<Session> = emptyList()

    private val queryFlow = MutableStateFlow(_state.value.query)
    private var searchJob: Job? = null

    init {
        live = scoped(sessions.cachedAllProfiles())
        applyFilter()
        refresh()
        viewModelScope.launch {
            profileManager.active
                .flatMapLatest { recentStore.recent(it) }
                .collect { list -> _state.value = _state.value.copy(recent = list) }
        }
        viewModelScope.launch {
            queryFlow.map { it.trim() }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { q -> autoSearch(q) }
        }
    }

    private fun scoped(all: List<Session>): List<Session> {
        val active = profileManager.active.value
        return if (active.isNullOrBlank()) all else all.filter { it.profile == active }
    }

    fun refresh() = viewModelScope.launch { refreshNow() }

    /** Refreshes the visible search source before the warm-start overlay is removed. */
    suspend fun recoverForForeground(): Boolean = refreshNow()

    private suspend fun refreshNow(): Boolean {
        val active = profileManager.active.value
        return try {
            val refreshedLive = sessions.listAllProfiles()
            val refreshedArchived = sessions.archivedAllProfiles()
            if (active != profileManager.active.value) return false
            live = scoped(refreshedLive)
            archived = scoped(refreshedArchived)
            applyFilter()
            refreshResolvedHits()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    fun onQueryChange(q: String) {
        savedState[SAVED_QUERY] = q
        val trimmed = q.trim()
        val current = _state.value.messages
        val messages = when {
            trimmed.length < MIN_MESSAGE_QUERY -> MessageSearch.Idle
            // Same effective query (e.g. trailing space typed): keep what we have.
            current.queryOrNull == trimmed -> current
            else -> MessageSearch.Pending
        }
        if (messages is MessageSearch.Pending || messages is MessageSearch.Idle) searchJob?.cancel()
        _state.value = _state.value.copy(query = q, messages = messages)
        applyFilter()
        queryFlow.value = q
    }

    private fun applyFilter() {
        val q = _state.value.query.trim()
        val defaultPath = defaultProjectPath.value
        val matches = if (q.isEmpty()) emptyList() else buildList {
            live.filter { titleMatches(it.title, projectLabelOf(it, defaultPath), q) }
                .forEach { add(TitleMatch(it, archived = false)) }
            archived.filter { titleMatches(it.title, projectLabelOf(it, defaultPath), q) }
                .forEach { add(TitleMatch(it, archived = true)) }
        }
        _state.value = _state.value.copy(titleMatches = matches)
    }

    /** Debounced path: run unless the section already holds (or is fetching) this query. */
    private fun autoSearch(q: String) {
        if (q.length < MIN_MESSAGE_QUERY) return
        if (_state.value.messages.queryOrNull == q) return
        launchSearch(q)
    }

    /** Keyboard Search action: search now, even if the debounce has not elapsed. */
    fun searchMessages() {
        val q = _state.value.query.trim()
        if (q.length < MIN_MESSAGE_QUERY) return
        val current = _state.value.messages
        if (current is MessageSearch.Searching && current.query == q) return
        launchSearch(q)
    }

    /** Retry after HR-SEARCH-001. */
    fun retry() {
        val failed = _state.value.messages as? MessageSearch.Failed ?: return
        launchSearch(failed.query)
    }

    private fun launchSearch(q: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(messages = MessageSearch.Searching(q))
            val profile = profileManager.active.value
            val result = try {
                Result.success(sessions.search(q, profile))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
            // The query moved on while we were in flight: the newer search owns the section.
            if (_state.value.query.trim() != q) return@launch
            result.onSuccess { dtos ->
                lastHits = dtos
                val hits = resolveHits(dtos, q)
                _state.value = _state.value.copy(
                    messages = if (hits.isEmpty()) MessageSearch.Empty(q) else MessageSearch.Results(q, hits),
                )
                recentStore.push(profile, q)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    messages = MessageSearch.Failed(
                        q,
                        AppError(AppErrorCode.SEARCH_FAILED, retryable = true, technicalCause = e.toString(), stage = "sessions.search"),
                    ),
                )
            }
        }
    }

    private var lastHits: List<SearchResultDto> = emptyList()

    /** Re-resolve rows after a list refresh so titles/times follow the fresher session rows. */
    private fun refreshResolvedHits() {
        val current = _state.value.messages as? MessageSearch.Results ?: return
        _state.value = _state.value.copy(messages = current.copy(hits = resolveHits(lastHits, current.query)))
    }

    private fun resolveHits(dtos: List<SearchResultDto>, q: String): List<MessageHit> {
        val defaultPath = defaultProjectPath.value
        return dtos
            // Defensive: the request already excludes these sources; drop any that slip through.
            .filterNot { it.source != null && it.source in SessionRepository.EXCLUDED_SOURCES }
            .map { dto ->
                val session = sessionFor(dto.sessionId)
                MessageHit(
                    sessionId = dto.sessionId,
                    profile = session?.profile ?: profileManager.active.value,
                    title = session?.title ?: dto.title?.ifBlank { null } ?: dto.sessionId,
                    snippet = centerSnippet(dto.snippet, q),
                    lastActiveMs = session?.lastActive ?: dto.lastActive?.let { (it * 1000).toLong() },
                    archived = session?.archived ?: dto.archived,
                    projectLabel = session?.let { projectLabelOf(it, defaultPath) },
                )
            }
    }

    /** Recent searches (per profile). */
    fun useRecent(q: String) {
        onQueryChange(q)
        searchMessages()
    }

    fun removeRecent(q: String) = viewModelScope.launch { recentStore.remove(profileManager.active.value, q) }
    fun clearRecent() = viewModelScope.launch { recentStore.clear(profileManager.active.value) }

    /** Opening any result also records the query. */
    fun noteOpened() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { recentStore.push(profileManager.active.value, q) }
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
    val keyboard = LocalSoftwareKeyboardController.current
    val state by vm.state.collectAsStateWithLifecycle()
    val defaultProjectPath by vm.defaultProjectPath.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var openRequestJob by remember { mutableStateOf<Job?>(null) }
    var openRequestSerial by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val nowMs = remember(state.messages) { System.currentTimeMillis() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val query = state.query.trim()

    fun openExisting(session: Session) {
        keyboard?.hide()
        vm.noteOpened()
        val request = ++openRequestSerial
        openRequestJob?.cancel()
        openRequestJob = scope.launch {
            if (vm.prepareOpen(session) && request == openRequestSerial) {
                onOpen(ChatLaunch.existing(session, initialQuery = query))
            } else if (request == openRequestSerial) {
                Toast.makeText(context, localized(language, "无法切换到该会话所属身份，请稍后重试", "Could not switch to this session's profile. Try again."), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openHit(hit: MessageHit) {
        keyboard?.hide()
        vm.noteOpened()
        val session = vm.sessionFor(hit.sessionId)
        if (session != null) {
            openExisting(session)
        } else {
            onOpen(ChatLaunch.searchHit(hit.sessionId, profile = hit.profile, title = hit.title, query = query))
        }
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
                }
                SearchField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    placeholder = localized(language, "搜索会话与消息…", "Search sessions and messages…"),
                    onSearch = { vm.searchMessages() },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.isEmpty()) {
                    if (state.recent.isNotEmpty()) {
                        item(key = "h-recent") {
                            SearchSectionHeader(
                                label = localized(language, "最近搜索", "Recent searches"),
                                trailing = localized(language, "清除", "Clear"),
                                onTrailing = { vm.clearRecent() },
                            )
                        }
                        items(state.recent, key = { "r-$it" }) { q ->
                            RecentSearchRow(q, onClick = { vm.useRecent(q) }, onRemove = { vm.removeRecent(q) })
                        }
                    }
                    return@LazyColumn
                }

                if (state.titleMatches.isNotEmpty()) {
                    item(key = "h-title") {
                        SearchSectionHeader(localized(language, "标题匹配", "Title matches"), trailing = state.titleMatches.size.toString())
                    }
                    items(state.titleMatches, key = { "t-${it.archived}-${it.session.profile.orEmpty()}:${it.session.id}" }) { m ->
                        TitleMatchRow(
                            match = m,
                            query = query,
                            defaultProjectPath = defaultProjectPath,
                            onClick = { openExisting(m.session) },
                        )
                        HorizontalDivider()
                    }
                }

                when (val messages = state.messages) {
                    MessageSearch.Idle -> if (state.titleMatches.isEmpty()) {
                        item(key = "hint") {
                            SearchNote(
                                localized(
                                    language,
                                    "输入至少 ${SearchViewModel.MIN_MESSAGE_QUERY} 个字符可搜索消息正文。",
                                    "Type at least ${SearchViewModel.MIN_MESSAGE_QUERY} characters to search message text.",
                                ),
                            )
                        }
                    }
                    MessageSearch.Pending, is MessageSearch.Searching -> item(key = "h-msg") {
                        SearchSectionHeader(localized(language, "消息匹配", "Message matches"), trailing = localized(language, "搜索中…", "Searching…"))
                    }
                    is MessageSearch.Results -> {
                        item(key = "h-msg") {
                            SearchSectionHeader(localized(language, "消息匹配", "Message matches"), trailing = messages.hits.size.toString())
                        }
                        items(messages.hits, key = { "m-${it.sessionId}" }) { hit ->
                            MessageHitRow(hit = hit, query = query, nowMs = nowMs, onClick = { openHit(hit) })
                            HorizontalDivider()
                        }
                    }
                    is MessageSearch.Empty -> {
                        item(key = "h-msg") { SearchSectionHeader(localized(language, "消息匹配", "Message matches")) }
                        item(key = "empty") { SearchNote(localized(language, "消息中没有匹配“${messages.query}”。", "No messages match \"${messages.query}\".")) }
                    }
                    is MessageSearch.Failed -> {
                        item(key = "h-msg") { SearchSectionHeader(localized(language, "消息匹配", "Message matches")) }
                        item(key = "error") { SearchErrorStrip(messages.error, onRetry = { vm.retry() }) }
                    }
                }
            }
            if (state.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
