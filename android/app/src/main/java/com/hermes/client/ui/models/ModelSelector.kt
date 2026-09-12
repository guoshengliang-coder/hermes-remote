package com.hermes.client.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.favKey
import com.hermes.client.ui.components.HermesMark
import com.hermes.client.ui.components.RunSpinner
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import com.hermes.client.ui.localization.resolve
import com.hermes.client.ui.theme.ModelBadge
import com.hermes.client.ui.theme.ModelCardAction
import com.hermes.client.ui.theme.ModelCardName
import com.hermes.client.ui.theme.ModelCardProvider
import com.hermes.client.ui.theme.ModelEffortLabel
import com.hermes.client.ui.theme.ModelEffortValue
import com.hermes.client.ui.theme.ModelGroupCount
import com.hermes.client.ui.theme.ModelGroupTitle
import com.hermes.client.ui.theme.ModelQuickChip
import com.hermes.client.ui.theme.ModelQuickChipProvider
import com.hermes.client.ui.theme.ModelQuickLabel
import com.hermes.client.ui.theme.ModelQuickStatus
import com.hermes.client.ui.theme.ModelRowBadge
import com.hermes.client.ui.theme.ModelRowName
import com.hermes.client.ui.theme.ModelRowProvider
import com.hermes.client.ui.theme.ModelSheetTitle
import com.hermes.client.ui.theme.isDarkSurface
import com.hermes.client.ui.theme.modelAccentColor
import com.hermes.client.ui.theme.modelAccentInkColor
import com.hermes.client.ui.theme.modelBarColor
import com.hermes.client.ui.theme.modelCardBorderColor
import com.hermes.client.ui.theme.modelCardColor
import com.hermes.client.ui.theme.modelCardShadow
import com.hermes.client.ui.theme.modelCurrentBorderColor
import com.hermes.client.ui.theme.modelCurrentFillColor
import com.hermes.client.ui.theme.modelDividerColor
import com.hermes.client.ui.theme.modelInkFaintColor
import com.hermes.client.ui.theme.modelInkMutedColor
import com.hermes.client.ui.theme.modelInsetColor
import com.hermes.client.ui.theme.modelStarColor
import com.hermes.client.ui.theme.modelStarOffColor
import com.hermes.client.ui.theme.modelSwitchChipColor

data class ModelRow(
    val provider: String,       // real provider slug — what the slash command needs
    /** What the row's subline SHOWS: the provider's display name, falling back to its slug. */
    val providerLabel: String,
    val model: String,
    val isFavorite: Boolean,
    val isCurrent: Boolean,
    // Remembered per-model reasoning effort (device-local preset), shown as a row badge.
    val presetEffort: String? = null,
)

/**
 * One card in the directory: the pinned favourites section, or one provider.
 *
 * Replaces the flat header/row stream the list used before. The mock draws every group as a card
 * with its rows INSIDE it (docs/DESIGN.md §5.17), and a flat stream cannot express that — a card
 * has to know where it ends.
 */
data class ModelGroup(
    /**
     * Provider display name. Left empty for [isFavorites], which renders its own localized title —
     * the data layer must never carry UI copy, which is exactly how the old builder ended up with
     * a hardcoded English "Favorites" in it.
     */
    val title: String = "",
    /** Collapse key. `null` for the favourites card, which never collapses. */
    val slug: String?,
    val isFavorites: Boolean = false,
    /** The provider that owns the model in force — the mock gives it no marker, but the list opens on it. */
    val isCurrent: Boolean = false,
    /** Total models in this group, shown in the header even while collapsed. */
    val count: Int = 0,
    val expanded: Boolean = true,
    /** Empty when collapsed. */
    val rows: List<ModelRow> = emptyList(),
)

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
 * Pure: flattens providers into the directory's cards — a pinned favourites card first, then one
 * card per provider in input order. A collapsed group keeps its header and its total [count] but
 * carries no rows. `null` [expandedGroups] means "everything expanded" (the legacy default, still
 * what the settings screen starts from). [presets] (favKey → effort wire value) annotates rows with
 * their remembered reasoning effort. Deterministic: input order is preserved.
 *
 * There is no query parameter. Search was removed from this screen on 2026-09-12 (§5.17) — the mock
 * has no search box and the product decision was to follow it.
 */
fun modelSelectorGroups(
    providers: List<ModelProviderDto>,
    favorites: Set<String>,
    currentProvider: String?,
    currentModel: String?,
    expandedGroups: Set<String>? = null,
    presets: Map<String, String> = emptyMap(),
): List<ModelGroup> {
    fun rowOf(p: ModelProviderDto, model: String) = ModelRow(
        provider = p.slug,
        providerLabel = p.name ?: p.slug,
        model = model,
        isFavorite = favKey(p.slug, model) in favorites,
        isCurrent = p.slug == currentProvider && model == currentModel,
        presetEffort = presets[favKey(p.slug, model)],
    )

    val groups = mutableListOf<ModelGroup>()

    val favRows = providers
        .flatMap { p -> p.models.filter { m -> favKey(p.slug, m) in favorites }.map { p to it } }
        .map { (p, m) -> rowOf(p, m) }
    if (favRows.isNotEmpty()) {
        groups += ModelGroup(
            slug = null, isFavorites = true,
            count = favRows.size, expanded = true, rows = favRows,
        )
    }

    for (p in providers) {
        if (p.models.isEmpty()) continue
        val expanded = expandedGroups == null || p.slug in expandedGroups
        groups += ModelGroup(
            title = p.name ?: p.slug,
            slug = p.slug,
            isCurrent = p.isCurrent,
            count = p.models.size,
            expanded = expanded,
            rows = if (expanded) p.models.map { rowOf(p, it) } else emptyList(),
        )
    }
    return groups
}

/** "Which model am I on" strip above the list. [scopeText] is pre-localized by the caller. */
data class CurrentModelSummary(
    val model: String,
    val provider: String?,
    /**
     * The pill beside the name: 「当前使用」 in a chat, 「当前默认」 in settings. The mock draws
     * only the first and says nothing about scope, which is why [scopeText] exists separately —
     * dropping it would lose the one fact the sheet is there to answer.
     */
    val badgeText: String,
    /** Appended to the provider subline as ` · <scope>`. Null on the settings screen. */
    val scopeText: String? = null,
    val showRestore: Boolean = false,
)

/**
 * One entry in the 快捷切换 row: a model recently switched to, newest first.
 *
 * [providerLabel] is resolved against the live catalogue by the caller, not stored — a provider
 * can be renamed upstream, and a chip showing last month's name would be a small lie.
 */
data class ModelRecent(val provider: String, val model: String, val providerLabel: String = provider)

// ---- UI (stateless) ----

private val CardShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(12.dp)
private val BadgeShape = RoundedCornerShape(4.dp)

/**
 * The sheet's title bar: 「选择模型」, the refresh action, and a ✕.
 *
 * Split out of [ModelSelectorSheet] so a screenshot test can compose it — a sheet renders in its
 * own window where `onRoot()` cannot reach it, the same reason `ThemeSheetContent` is separate.
 */
@Composable
fun ModelSheetHeader(refreshing: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                l10n("选择模型", "Select model"),
                style = ModelSheetTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // The mock's refresh button carries an aria-label but no glyph, so it renders empty.
            // Read as a missing icon rather than a removed action (§5.17 有意偏离二).
            if (refreshing) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    RunSpinner(size = 18.dp, contentDescription = l10n("正在刷新", "Refreshing"))
                }
            } else {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = l10n("刷新模型列表", "Refresh model list"),
                        tint = modelInkMutedColor(),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // Same 32dp filled circle the card page's theme sheet uses, rung in dark only.
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(modelInsetColor())
                    .then(
                        if (isDarkSurface()) Modifier.border(1.dp, modelCardBorderColor(), CircleShape)
                        else Modifier,
                    )
                    .clickable(onClick = onDismiss)
                    .testTag("model-sheet-close"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = l10n("关闭", "Close"),
                    tint = modelInkMutedColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = modelDividerColor())
    }
}

@Composable
fun ModelSelectorContent(
    groups: List<ModelGroup>,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    onToggleGroup: (slug: String) -> Unit,
    pendingKey: String?,
    error: String?,
    modifier: Modifier = Modifier,
    currentSummary: CurrentModelSummary? = null,
    onRestoreDefault: (() -> Unit)? = null,
    // Recently switched-to models (device-local). Empty hides the 快捷切换 row entirely — which is
    // what the settings screen wants: it edits the default, so "recently switched to" means nothing.
    recents: List<ModelRecent> = emptyList(),
    // Session reasoning effort — the row renders only when a handler is provided
    // (the settings screen edits the default model only and hides it).
    reasoningEffort: String? = null,
    onSelectReasoning: ((String) -> Unit)? = null,
    reasoningPending: Boolean = false,
    listLoading: Boolean = false,
    listError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ModelStatusCard(
            summary = currentSummary,
            onRestoreDefault = if (currentSummary?.showRestore == true) onRestoreDefault else null,
            reasoningEffort = reasoningEffort,
            reasoningPending = reasoningPending,
            onSelectReasoning = onSelectReasoning,
        )

        if (recents.isNotEmpty()) {
            QuickSwitchRow(
                recents = recents,
                pendingKey = pendingKey,
                currentSummary = currentSummary,
                onSelect = onSelect,
            )
        }

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        // Empty-list states: the catalog fetch is lazy and can fail on a flaky link. Never show
        // a silent empty shell — say what's happening and offer a retry. The mock draws none of
        // these, so they keep their existing shape and only take the new colours (§5.17 偏离五).
        if (groups.isEmpty()) {
            val language = LocalAppLanguage.current
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    listLoading -> {
                        HermesMark(size = 32.dp)
                        Text(
                            localized(language, "正在加载模型列表…", "Loading models…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = modelInkMutedColor(),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    listError -> {
                        Text(
                            AppError(AppErrorCode.MODEL_LIST_FAILED, retryable = true).localizedMessage(language),
                            style = MaterialTheme.typography.bodyMedium,
                            color = modelInkMutedColor(),
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
                        color = modelInkMutedColor(),
                    )
                }
            }
        }

        // Open on the answer: the first composition that carries a current row scrolls its card
        // into view, so a long catalog never hides "what am I on".
        val listState = rememberLazyListState()
        var autoScrolled by remember { mutableStateOf(false) }
        LaunchedEffect(groups) {
            if (!autoScrolled) {
                // Only when the answer is NOT already on screen. The favourites card is pinned
                // first and usually holds the model in force; scrolling to its provider card
                // would push that pinned card — and the quick-switch row — out of view to show
                // the same row twice.
                val inFavorites = groups.firstOrNull()?.isFavorites == true &&
                    groups.first().rows.any { it.isCurrent }
                val idx = groups.indexOfFirst { g -> !g.isFavorites && g.rows.any { it.isCurrent } }
                if (!inFavorites && idx > 0) {
                    autoScrolled = true
                    listState.scrollToItem(idx)
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            items(groups, key = { it.slug ?: FAVORITES_KEY }) { group ->
                ModelGroupCard(
                    group = group,
                    pendingKey = pendingKey,
                    onToggleGroup = onToggleGroup,
                    onToggleFavorite = onToggleFavorite,
                    onSelect = onSelect,
                )
            }
        }
    }
}

private const val FAVORITES_KEY = " favorites"

/**
 * The one "current state" card: the session's model (with scope + restore action) on top and its
 * reasoning effort below, separated by a hairline — one unit, matching the mental model that effort
 * belongs to the model. Either half renders alone when the other is absent (the settings screen has
 * no session, so no effort half).
 */
@Composable
private fun ModelStatusCard(
    summary: CurrentModelSummary?,
    onRestoreDefault: (() -> Unit)?,
    reasoningEffort: String?,
    reasoningPending: Boolean,
    onSelectReasoning: ((String) -> Unit)?,
) {
    if (summary == null && onSelectReasoning == null) return
    Surface(
        color = modelCardColor(),
        shape = CardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, modelCardBorderColor()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .shadow(modelCardShadow(), CardShape, clip = false)
            .testTag("model-status-card"),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // The 6dp brand stripe. Inside the card rather than drawn over it, so it cannot
            // overlap the rounded corner the way the mock's absolute positioning does.
            Box(Modifier.width(6.dp).fillMaxHeight().background(modelAccentColor()))
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
                if (summary != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    summary.model,
                                    style = ModelCardName,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                ModelPill(
                                    text = summary.badgeText,
                                    fill = modelInsetColor(),
                                    ink = modelInkMutedColor(),
                                    border = modelCardBorderColor(),
                                )
                            }
                            listOfNotNull(summary.provider, summary.scopeText)
                                .takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let {
                                Text(
                                    it,
                                    style = ModelCardProvider,
                                    color = modelInkFaintColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (onRestoreDefault != null) {
                            Text(
                                l10n("恢复默认", "Use default"),
                                style = ModelCardAction,
                                color = modelAccentInkColor(),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onRestoreDefault)
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .testTag("model-restore-default"),
                            )
                        }
                    }
                }
                if (summary != null && onSelectReasoning != null) {
                    HorizontalDivider(
                        color = modelDividerColor(),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                if (onSelectReasoning != null) {
                    ReasoningEffortRow(reasoningEffort, reasoningPending, onSelectReasoning)
                }
            }
        }
    }
}

/**
 * Reasoning effort, as one row with a dropdown (docs/DESIGN.md §5.17).
 *
 * Replaces the inline 思考 switch plus seven `FilterChip`s. The switch is now the menu's first
 * entry — 「关」 is the upstream `none` level, so it always was one of the choices rather than a
 * separate control, and drawing it as a toggle beside chips said otherwise.
 */
@Composable
private fun ReasoningEffortRow(effort: String?, pending: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(modelInsetColor())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Psychology,
            contentDescription = null,
            tint = modelInkFaintColor(),
            modifier = Modifier.size(17.dp),
        )
        Text(
            l10n("推理强度", "Reasoning effort"),
            style = ModelEffortLabel,
            color = modelInkMutedColor(),
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(modelCardColor())
                    .border(1.dp, modelCardBorderColor(), RoundedCornerShape(8.dp))
                    .clickable(enabled = !pending) { open = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("model-effort-dropdown"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pending) {
                    RunSpinner(size = 10.dp, modifier = Modifier.padding(end = 4.dp))
                } else {
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(modelAccentColor()),
                    )
                }
                Text(
                    reasoningLabel(effort)?.resolve() ?: l10n("默认", "Default"),
                    style = ModelEffortValue,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = l10n("选择推理强度", "Choose reasoning effort"),
                    tint = modelInkFaintColor(),
                    modifier = Modifier.padding(start = 2.dp).size(16.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf(REASONING_OFF) + REASONING_LEVELS).forEach { level ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                reasoningLabel(level)?.resolve() ?: level,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (effort == level) modelAccentInkColor()
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { open = false; onSelect(level) },
                    )
                }
                // The scope note lives in the menu now that the row itself has no expanded area.
                Text(
                    l10n("仅当前对话；该模型的选择会被记住", "This chat only; remembered for this model"),
                    style = MaterialTheme.typography.bodySmall,
                    color = modelInkFaintColor(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 快捷切换: the models most recently switched to, as a horizontally scrolling chip row.
 *
 * While a switch is in flight the whole row goes inert — the target chip keeps the brand ring and
 * a spinner, everything else dims. Two switches cannot be in flight at once, and a chip that still
 * looked tappable would suggest otherwise.
 */
@Composable
private fun QuickSwitchRow(
    recents: List<ModelRecent>,
    pendingKey: String?,
    currentSummary: CurrentModelSummary?,
    onSelect: (provider: String, model: String) -> Unit,
) {
    val pendingModel = recents.firstOrNull { favKey(it.provider, it.model) == pendingKey }?.model
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(l10n("快捷切换", "Quick switch"), style = ModelQuickLabel, color = modelInkMutedColor())
            Spacer(Modifier.weight(1f))
            if (pendingModel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape).background(modelAccentColor()),
                    )
                    Text(
                        l10n("正在切换至 $pendingModel", "Switching to $pendingModel"),
                        style = ModelQuickStatus,
                        color = modelAccentInkColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recents.forEach { recent ->
                val key = favKey(recent.provider, recent.model)
                val isPending = pendingKey == key
                val inert = pendingKey != null && !isPending
                val isCurrent = currentSummary?.model == recent.model
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (isPending) modelCurrentFillColor() else modelCardColor())
                        .border(
                            1.dp,
                            if (isPending) modelCurrentBorderColor() else modelCardBorderColor(),
                            CircleShape,
                        )
                        .clickable(enabled = pendingKey == null && !isCurrent) {
                            onSelect(recent.provider, recent.model)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dim = if (inert || isCurrent) 0.6f else 1f
                    Text(
                        recent.model,
                        style = if (isPending) ModelQuickChip.copy(fontWeight = FontWeight.SemiBold)
                        else ModelQuickChip,
                        color = (if (isPending) modelAccentInkColor() else modelInkMutedColor())
                            .copy(alpha = dim),
                        maxLines = 1,
                    )
                    Text(
                        recent.providerLabel,
                        style = ModelQuickChipProvider,
                        color = (if (isPending) modelAccentColor() else modelInkFaintColor())
                            .copy(alpha = dim),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    if (isPending) {
                        RunSpinner(size = 13.dp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

/** One directory card: a title bar and, when expanded, its rows. */
@Composable
private fun ModelGroupCard(
    group: ModelGroup,
    pendingKey: String?,
    onToggleGroup: (String) -> Unit,
    onToggleFavorite: (String, String) -> Unit,
    onSelect: (String, String) -> Unit,
) {
    Surface(
        color = modelCardColor(),
        shape = CardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, modelCardBorderColor()),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(modelCardShadow(), CardShape, clip = false),
    ) {
        Column {
            ModelGroupHeader(group, onToggleGroup)
            if (group.rows.isNotEmpty()) {
                Column(Modifier.padding(6.dp)) {
                    group.rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = modelDividerColor(),
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        ModelRowItem(row, pendingKey, onToggleFavorite, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGroupHeader(group: ModelGroup, onToggleGroup: (String) -> Unit) {
    val slug = group.slug
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(modelBarColor())
                .then(if (slug != null) Modifier.clickable { onToggleGroup(slug) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (group.isFavorites) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    tint = modelStarColor(),
                    modifier = Modifier.size(16.dp).padding(end = 0.dp),
                )
                Text(
                    l10n("收藏模型 (${group.count})", "Favorites (${group.count})"),
                    style = ModelGroupTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                ModelPill(
                    text = l10n("固定顶部", "Pinned"),
                    fill = modelInsetColor(),
                    ink = modelInkFaintColor(),
                    border = modelCardBorderColor(),
                    shape = BadgeShape,
                )
            } else {
                // ONE weight, and it fills. The count and the chevron are a right-aligned pair
                // across every card, so the title has to absorb all the slack itself (HG-32).
                // `weight(1f, fill = false)` plus a second `Spacer(weight(1f))` — what this was —
                // splits the free space in half and then lets the title shrink inside its half,
                // so the leftover stayed on the RIGHT and dragged the count left by however much
                // shorter than half the title was. Cards then stepped: 「DeepSeek」 furthest left,
                // an ellipsised name furthest right.
                Text(
                    group.title,
                    style = ModelGroupTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    l10n("${group.count} 项", "${group.count}"),
                    style = ModelGroupCount,
                    color = modelInkFaintColor(),
                )
                Icon(
                    if (group.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (group.expanded) l10n("收起分组", "Collapse group")
                    else l10n("展开分组", "Expand group"),
                    tint = modelInkFaintColor(),
                    modifier = Modifier.padding(start = 6.dp).size(20.dp),
                )
            }
        }
        if (group.rows.isNotEmpty()) HorizontalDivider(color = modelDividerColor())
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
    // Three row states share one shape. While a switch is in flight the TARGET row carries the
    // highlight and the previously current one steps back to a neutral 「前次生效」 — the mock's
    // one drawn state. With nothing in flight the current row simply keeps the highlight.
    val switchingAway = row.isCurrent && pendingKey != null
    val highlighted = isPending || (row.isCurrent && pendingKey == null)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (highlighted) modelCurrentFillColor() else Color.Transparent)
            .then(
                if (highlighted) Modifier.border(1.dp, modelCurrentBorderColor(), RowShape)
                else Modifier,
            )
            .clickable(enabled = enabled) { onSelect(row.provider, row.model) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.model,
                    style = if (highlighted) ModelRowName.copy(fontWeight = FontWeight.SemiBold)
                    else ModelRowName,
                    color = if (highlighted) modelAccentInkColor() else modelInkMutedColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val badge: Triple<String, Color, Color>? = when {
                    isPending -> Triple(
                        localized(language, "切换中…", "Switching…"),
                        modelSwitchChipColor(),
                        modelAccentInkColor(),
                    )
                    switchingAway -> Triple(
                        localized(language, "前次生效", "Was in use"),
                        modelInsetColor(),
                        modelInkMutedColor(),
                    )
                    else -> reasoningLabel(row.presetEffort)?.let {
                        Triple(it.resolve(language), modelInsetColor(), modelInkMutedColor())
                    }
                }
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge.first,
                        style = if (isPending) ModelRowBadge.copy(fontWeight = FontWeight.SemiBold)
                        else ModelRowBadge,
                        color = badge.third,
                        maxLines = 1,
                        modifier = Modifier
                            .background(badge.second, BadgeShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                row.providerLabel,
                style = ModelRowProvider,
                color = if (highlighted) modelAccentColor() else modelInkFaintColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // The spinner sits BESIDE the star rather than replacing it: a row being switched to is
        // also a row you may want to star, and the old layout made the star vanish mid-switch.
        if (isPending) {
            RunSpinner(size = 17.dp, modifier = Modifier.padding(start = 8.dp))
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { onToggleFavorite(row.provider, row.model) },
            contentAlignment = Alignment.Center,
        ) {
            // 32dp touch target around a 19dp glyph. The mock gives the star `p-0.5` (≈23dp),
            // which is a mis-tap waiting to happen at the end of a fully tappable row; the
            // enlargement moves no pixels (§5.17).
            Icon(
                imageVector = if (row.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (row.isFavorite) localized(language, "取消收藏", "Unfavorite")
                else localized(language, "收藏", "Favorite"),
                tint = if (row.isFavorite) modelStarColor() else modelStarOffColor(),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** A small pill: 「当前使用」/「跟随默认」 on the status card, 「固定顶部」 on the favourites card. */
@Composable
private fun ModelPill(
    text: String,
    fill: Color,
    ink: Color,
    border: Color,
    shape: RoundedCornerShape = RoundedCornerShape(50),
) {
    Text(
        text,
        style = ModelBadge,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .background(fill, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    groups: List<ModelGroup>,
    onToggleFavorite: (provider: String, model: String) -> Unit,
    onSelect: (provider: String, model: String) -> Unit,
    onToggleGroup: (slug: String) -> Unit,
    pendingKey: String?,
    error: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    currentSummary: CurrentModelSummary? = null,
    onRestoreDefault: (() -> Unit)? = null,
    recents: List<ModelRecent> = emptyList(),
    reasoningEffort: String? = null,
    onSelectReasoning: ((String) -> Unit)? = null,
    reasoningPending: Boolean = false,
    listLoading: Boolean = false,
    listError: Boolean = false,
    onRetryLoad: (() -> Unit)? = null,
) {
    val sheetState = com.hermes.client.ui.components.hermesSheetState()
    // Sheet gestures are OFF: scrolling the model list can never accidentally collapse or
    // dismiss the sheet. Closing is deliberate only — the ✕, tap or short pull-down on the grab
    // handle, tap the scrim, or press back.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetGesturesEnabled = false,
        dragHandle = { com.hermes.client.ui.components.SheetCloseHandle(onDismiss) },
    ) {
        ModelSheetHeader(refreshing = refreshing, onRefresh = onRefresh, onDismiss = onDismiss)
        ModelSelectorContent(
            groups = groups,
            onToggleFavorite = onToggleFavorite, onSelect = onSelect,
            onToggleGroup = onToggleGroup,
            pendingKey = pendingKey, error = error,
            modifier = Modifier.padding(bottom = 24.dp),
            currentSummary = currentSummary, onRestoreDefault = onRestoreDefault,
            recents = recents,
            reasoningEffort = reasoningEffort, onSelectReasoning = onSelectReasoning,
            reasoningPending = reasoningPending,
            listLoading = listLoading, listError = listError, onRetryLoad = onRetryLoad,
        )
    }
}
