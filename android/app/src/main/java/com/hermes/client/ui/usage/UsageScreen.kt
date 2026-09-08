package com.hermes.client.ui.usage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.data.repository.AnalyticsRepository
import com.hermes.client.data.repository.ConfigRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.USAGE_RANGE_CHOICES
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.localization.l10n
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

data class UsageUiState(
    val summary: UsageSummary? = null,
    val rangeDays: Int = 30,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: AppError? = null,
    /** Hermes hides its own cost surfaces when `dashboard.show_token_analytics` is off. */
    val costHidden: Boolean = false,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val configRepo: ConfigRepository,
    private val profileManager: ProfileManager,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // collectLatest so switching profile or range cancels the in-flight load instead of
            // letting a stale response land on top of the newer one.
            combine(profileManager.active, settings.usageRangeDays) { profile, days -> profile to days }
                .collectLatest { (profile, days) -> load(profile, days) }
        }
    }

    fun setRange(days: Int) = viewModelScope.launch { settings.setUsageRangeDays(days) }

    fun refresh() = viewModelScope.launch {
        load(profileManager.active.value, _state.value.rangeDays, refreshing = true)
    }

    fun retry() = viewModelScope.launch {
        load(profileManager.active.value, _state.value.rangeDays)
    }

    private suspend fun load(profile: String?, days: Int, refreshing: Boolean = false) {
        _state.value = _state.value.copy(
            rangeDays = days,
            loading = !refreshing && _state.value.summary == null,
            refreshing = refreshing,
            error = null,
        )
        val usage = try {
            analytics.usage(profile, days)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = UsageUiState(
                rangeDays = days,
                loading = false,
                error = error.toUsageError("usage_load"),
            )
            return
        }
        _state.value = UsageUiState(
            summary = summarize(usage),
            rangeDays = days,
            loading = false,
            costHidden = costHiddenUpstream(profile),
        )
    }

    /**
     * Follow Hermes's own switch, but only when it is explicitly off. Treating an absent key as
     * "off" would hide the block for anyone whose config never mentions it, which is most people.
     */
    private suspend fun costHiddenUpstream(profile: String?): Boolean = try {
        val dashboard = configRepo.get(profile)["dashboard"] as? JsonObject
        (dashboard?.get("show_token_analytics") as? JsonPrimitive)?.content == "false"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
}

internal fun Long.compact(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1fK".format(this / 1_000.0)
    else -> toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onMenu: () -> Unit,
    vm: UsageViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedDay by remember { mutableStateOf<UsageDayDto?>(null) }
    var costSheet by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = l10n("用量", "Usage"),
                navigationIcon = {
                    IconButton(onClick = onMenu) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = l10n("返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val summary = state.summary
            when {
                state.loading -> LoadingState()
                state.error != null -> Column(Modifier.fillMaxSize()) {
                    RangeSegments(state.rangeDays, vm::setRange)
                    ErrorState(error = state.error!!, onRetry = { vm.retry() })
                }
                summary == null || summary.isEmpty -> Column(Modifier.fillMaxSize()) {
                    RangeSegments(state.rangeDays, vm::setRange)
                    EmptyState(
                        title = l10n("这个身份还没有用量记录", "No usage recorded for this profile yet"),
                        subtitle = l10n(
                            "开始一次会话后，这里会显示 token 用量、模型分布和每日趋势。",
                            "Start a conversation and token usage, model mix and daily trend appear here.",
                        ),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { RangeSegments(state.rangeDays, vm::setRange) }
                    item { HeroCard(summary) }
                    item {
                        SectionLabel(
                            l10n("每日 TOKEN", "DAILY TOKENS"),
                            l10n("仅主对话", "main agent only"),
                        )
                        UsageCard {
                            DailyTokensChart(summary.daily, onSelectDay = { selectedDay = it })
                            Hairline()
                            ChartLegend(summary)
                        }
                    }
                    if (summary.models.isNotEmpty()) {
                        item {
                            SectionLabel(
                                l10n("按模型", "BY MODEL"),
                                l10n("含辅助调用", "includes auxiliary"),
                            )
                            ModelsCard(
                                models = summary.models,
                                total = summary.totalTokens,
                                expanded = modelsExpanded,
                                onToggle = { modelsExpanded = !modelsExpanded },
                            )
                        }
                    }
                    if (summary.auxTasks.isNotEmpty()) {
                        item {
                            SectionLabel(l10n("按辅助任务", "BY AUXILIARY TASK"))
                            AuxTasksCard(summary.auxTasks)
                        }
                    }
                    if (!state.costHidden) {
                        item {
                            SectionLabel(l10n("成本（估算）", "COST (ESTIMATE)"))
                            CostCard(summary, onExplain = { costSheet = true })
                        }
                    }
                    item { UsageFootnote(summary.periodDays) }
                }
            }
        }
    }

    selectedDay?.let { day -> DayDetailSheet(day) { selectedDay = null } }
    if (costSheet) CostInfoSheet { costSheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSegments(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            USAGE_RANGE_CHOICES.forEachIndexed { index, days ->
                SegmentedButton(
                    selected = days == selected,
                    onClick = { onSelect(days) },
                    shape = SegmentedButtonDefaults.itemShape(index, USAGE_RANGE_CHOICES.size),
                    // §5.2: selection is carried by the fill alone — the check mark shifts labels
                    // as it appears and disappears.
                    icon = {},
                    label = { Text(l10n("$days 天", "$days d"), style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
    Box(Modifier.height(8.dp))
}
