package com.hermes.client.ui.models
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.LocalAppLanguage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onMenu: () -> Unit,
    vm: ModelsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val language = LocalAppLanguage.current
    val stateMessage = state.message?.resolve(language)

    LaunchedEffect(stateMessage) {
        stateMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("模型", "Models"),
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back")) } },
                actions = {
                    // Manual force refresh — the top bar stays mounted through loading/error
                    // states, so the control is always reachable.
                    if (state.refreshing) {
                        Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            com.hermes.client.ui.components.HermesMark(size = 20.dp)
                        }
                    } else {
                        IconButton(onClick = vm::load) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Rounded.Refresh,
                                contentDescription = l10n("刷新模型列表", "Refresh model list"),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    error = state.error!!,
                    onRetry = vm::load,
                )
                else -> {
                    val favorites by vm.favorites.collectAsStateWithLifecycle()
                    // Collapsible groups, same defaults as the chat sheet: the group holding the
                    // current default opens, the rest collapse to a scannable line each.
                    var expandedGroups by remember { mutableStateOf<Set<String>?>(null) }
                    val effectiveExpanded = expandedGroups ?: setOfNotNull(state.defaultProvider)
                    val items = com.hermes.client.ui.models.modelSelectorRows(
                        providers = state.providers, favorites = favorites, query = state.query,
                        currentProvider = state.defaultProvider, currentModel = state.defaultModel,
                        expandedGroups = effectiveExpanded,
                    )
                    // Settings edits the profile default only; the session reasoning section is
                    // deliberately absent here (it belongs to a chat).
                    com.hermes.client.ui.models.ModelSelectorContent(
                        items = items,
                        query = state.query, onQueryChange = vm::onQuery,
                        onToggleFavorite = vm::toggleFavorite,
                        onSelect = { p, m -> vm.select(p, m) },
                        onToggleGroup = { slug ->
                            expandedGroups = if (slug in effectiveExpanded) effectiveExpanded - slug else effectiveExpanded + slug
                        },
                        pendingKey = state.pendingKey,
                        error = null,
                        modifier = Modifier.fillMaxSize(),
                        currentSummary = state.defaultModel?.let { model ->
                            com.hermes.client.ui.models.CurrentModelSummary(
                                model = model,
                                provider = state.defaultProvider,
                                scopeText = localized(language, "当前默认", "Current default"),
                            )
                        },
                    )
                }
            }
        }
    }
}
