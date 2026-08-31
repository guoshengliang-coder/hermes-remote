package com.hermes.client.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

/** Which model slot a selection applies to. */
enum class ModelScope { SESSION, DEFAULT }

data class ModelRow(
    val provider: String,       // real provider slug
    val model: String,
    val isFavorite: Boolean,
    val isCurrent: Boolean,
)

sealed interface ModelListItem {
    /**
     * Section header. [slug] identifies a collapsible provider group; the pinned Favorites
     * section (`isFavorites`) has no slug and never collapses. While a query is active,
     * [searchHits] is set and [count] is the number of hits (collapse is suspended);
     * otherwise [count] is the provider's total model count.
     */
    data class Header(
        val title: String,
        val isCurrent: Boolean = false,
        val isFavorites: Boolean = false,
        val slug: String? = null,
        val count: Int = 0,
        val expanded: Boolean = true,
        val searchHits: Boolean = false,
    ) : ModelListItem
    data class Row(val row: ModelRow) : ModelListItem
}

/**
 * Best-effort provider for [model] when the caller only knows the model name (old session
 * metadata can carry a model without its provider). Prefers a unique owning provider, then
 * the provider the gateway marks current.
 */
fun resolveModelProvider(
    providers: List<ModelProviderDto>,
    provider: String?,
    model: String?,
): String? {
    if (!provider.isNullOrBlank()) return provider
    if (model.isNullOrBlank()) return null
    val owners = providers.filter { model in it.models }
    return owners.singleOrNull()?.slug ?: owners.firstOrNull { it.isCurrent }?.slug
}

/**
 * Pure: flattens providers into a display list — a pinned Favorites section (only favorites that
 * survive the query), then one header per provider. With a blank query, groups honor
 * [expandedGroups]: a collapsed group contributes its header only (`null` means all expanded, the
 * legacy behavior). With a query, collapse is suspended — only providers with matches appear,
 * auto-expanded and flagged [ModelListItem.Header.searchHits]. The query matches the model name,
 * the provider slug, and the provider display name. Deterministic: input order is preserved.
 */
fun modelSelectorRows(
    providers: List<ModelProviderDto>,
    favorites: Set<String>,
    query: String,
    currentProvider: String?,
    currentModel: String?,
    expandedGroups: Set<String>? = null,
): List<ModelListItem> {
    val q = query.trim().lowercase()
    fun matches(p: ModelProviderDto, model: String) =
        q.isEmpty() || model.lowercase().contains(q) || p.slug.lowercase().contains(q) ||
            (p.name?.lowercase()?.contains(q) ?: false)
    fun rowOf(provider: String, model: String) = ModelRow(
        provider = provider,
        model = model,
        isFavorite = favKey(provider, model) in favorites,
        isCurrent = provider == currentProvider && model == currentModel,
    )

    val items = mutableListOf<ModelListItem>()

    val favRows = providers
        .flatMap { p -> p.models.filter { m -> favKey(p.slug, m) in favorites && matches(p, m) }.map { p.slug to it } }
        .map { (prov, m) -> rowOf(prov, m) }
    if (favRows.isNotEmpty()) {
        items += ModelListItem.Header("Favorites", isFavorites = true, count = favRows.size)
        favRows.forEach { items += ModelListItem.Row(it) }
    }

    for (p in providers) {
        val rows = p.models.filter { matches(p, it) }.map { rowOf(p.slug, it) }
        if (rows.isEmpty()) continue
        if (q.isNotEmpty()) {
            items += ModelListItem.Header(
                p.name ?: p.slug, isCurrent = p.isCurrent, slug = p.slug,
                count = rows.size, expanded = true, searchHits = true,
            )
            rows.forEach { items += ModelListItem.Row(it) }
        } else {
            val expanded = expandedGroups == null || p.slug in expandedGroups
            items += ModelListItem.Header(
                p.name ?: p.slug, isCurrent = p.isCurrent, slug = p.slug,
                count = p.models.size, expanded = expanded,
            )
            if (expanded) rows.forEach { items += ModelListItem.Row(it) }
        }
    }
    return items
}

/** "Which model am I on" strip above the list. [scopeText] is pre-localized by the caller. */
data class CurrentModelSummary(
    val model: String,
    val provider: String?,
    val scopeText: String,
    val showRestore: Boolean = false,
)

// ---- UI (stateless) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorContent(
    items: List<ModelListItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    scope: ModelScope?,
    onScopeChange: (ModelScope) -> Unit,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    onToggleGroup: (slug: String) -> Unit,
    pendingKey: String?,
    error: String?,
    modifier: Modifier = Modifier,
    currentSummary: CurrentModelSummary? = null,
    onRestoreDefault: (() -> Unit)? = null,
    scopeHint: String? = null,
    listLoading: Boolean = false,
    listError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (currentSummary != null) {
            CurrentModelStrip(currentSummary, if (currentSummary.showRestore) onRestoreDefault else null)
        }

        if (scope != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SegmentedButton(
                    selected = scope == ModelScope.SESSION,
                    onClick = { onScopeChange(ModelScope.SESSION) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(l10n("此对话", "This chat")) }
                SegmentedButton(
                    selected = scope == ModelScope.DEFAULT,
                    onClick = { onScopeChange(ModelScope.DEFAULT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(l10n("默认", "Default")) }
            }
            if (scopeHint != null) {
                Text(
                    scopeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(l10n("搜索模型或分组…", "Search models or groups…")) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = l10n("清除搜索", "Clear search"))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        // Empty-list states: the catalog fetch is lazy and can fail on a flaky link. Never show
        // a silent empty shell — say what's happening and offer a retry.
        if (items.isEmpty()) {
            val language = LocalAppLanguage.current
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    listLoading -> {
                        CircularProgressIndicator()
                        Text(
                            localized(language, "正在加载模型列表…", "Loading models…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    listError -> {
                        Text(
                            AppError(AppErrorCode.MODEL_LIST_FAILED, retryable = true).localizedMessage(language),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onRetryLoad != null) {
                            TextButton(onClick = onRetryLoad, modifier = Modifier.padding(top = 4.dp)) {
                                Text(localized(language, "重试", "Retry"))
                            }
                        }
                    }
                    else -> Text(
                        localized(language, "暂无可用模型", "No models available"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Open on the answer: the first composition that carries a current row scrolls it into
        // view, so a long catalog never hides "what am I on".
        val listState = rememberLazyListState()
        var autoScrolled by remember { mutableStateOf(false) }
        LaunchedEffect(items) {
            if (!autoScrolled && query.isEmpty()) {
                val idx = items.indexOfFirst { it is ModelListItem.Row && it.row.isCurrent }
                if (idx >= 0) {
                    autoScrolled = true
                    listState.scrollToItem((idx - 2).coerceAtLeast(0))
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            items(items) { item ->
                when (item) {
                    is ModelListItem.Header ->
                        if (item.isFavorites) FavoritesHeader()
                        else GroupHeader(item, onToggleGroup)
                    is ModelListItem.Row -> ModelRowItem(item.row, pendingKey, onToggleFavorite, onSelect)
                }
            }
        }
    }
}

@Composable
private fun CurrentModelStrip(summary: CurrentModelSummary, onRestoreDefault: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    summary.model,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(summary.provider, summary.scopeText).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRestoreDefault != null) {
                TextButton(onClick = onRestoreDefault) { Text(l10n("恢复默认", "Use default")) }
            }
        }
    }
}

@Composable
private fun FavoritesHeader() {
    Row(
        Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            l10n("收藏", "Favorites"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun GroupHeader(header: ModelListItem.Header, onToggleGroup: (String) -> Unit) {
    val slug = header.slug ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable(enabled = !header.searchHits) { onToggleGroup(slug) }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            header.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (header.searchHits) l10n("${header.count} 项命中", "${header.count} matches")
            else header.count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (header.isCurrent) {
            Text(
                l10n("当前", "Current"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Box(Modifier.weight(1f))
        if (header.searchHits) {
            Text(
                l10n("搜索时自动展开", "Expanded for search"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                if (header.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (header.expanded) l10n("收起分组", "Collapse group")
                else l10n("展开分组", "Expand group"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelRowItem(
    row: ModelRow,
    pendingKey: String?,
    onToggleFavorite: (String, String) -> Unit,
    onSelect: (String, String) -> Unit,
) {
    val language = LocalAppLanguage.current
    val key = favKey(row.provider, row.model)
    val isPending = pendingKey == key
    val enabled = pendingKey == null
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (row.isCurrent) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(enabled = enabled) { onSelect(row.provider, row.model) }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                row.model,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    row.isCurrent -> row.provider + localized(language, " · 当前模型", " · current model")
                    isPending -> row.provider + localized(language, " · 切换中…", " · switching…")
                    else -> row.provider
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isCurrent) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = localized(language, "当前模型", "Current model"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp).size(20.dp),
            )
        }
        if (isPending) {
            CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.5.dp)
        } else {
            IconButton(onClick = { onToggleFavorite(row.provider, row.model) }, enabled = enabled) {
                Icon(
                    imageVector = if (row.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (row.isFavorite) localized(language, "取消收藏", "Unfavorite") else localized(language, "收藏", "Favorite"),
                    tint = if (row.isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    items: List<ModelListItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    scope: ModelScope?,
    onScopeChange: (ModelScope) -> Unit,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    onToggleGroup: (slug: String) -> Unit,
    pendingKey: String?,
    error: String?,
    onDismiss: () -> Unit,
    currentSummary: CurrentModelSummary? = null,
    onRestoreDefault: (() -> Unit)? = null,
    scopeHint: String? = null,
    listLoading: Boolean = false,
    listError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            l10n("选择模型", "Select model"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
        )
        ModelSelectorContent(
            items = items, query = query, onQueryChange = onQueryChange,
            scope = scope, onScopeChange = onScopeChange,
            onToggleFavorite = onToggleFavorite, onSelect = onSelect,
            onToggleGroup = onToggleGroup,
            pendingKey = pendingKey, error = error,
            modifier = Modifier.padding(bottom = 24.dp),
            currentSummary = currentSummary, onRestoreDefault = onRestoreDefault,
            scopeHint = scopeHint,
            listLoading = listLoading, listError = listError, onRetryLoad = onRetryLoad,
        )
    }
}
