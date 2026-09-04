package com.hermes.client.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.error.AppError
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.data.repository.AnalyticsRepository
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsageUiState(
    val summary: UsageSummary? = null,
    /** Store-wide session stats (from the deleted session-admin screen); best-effort. */
    val statsTotal: Int? = null,
    val statsArchived: Int? = null,
    val statsMessages: Int? = null,
    /** The stats call failed. Kept apart from "no stats" so the row can say so instead of vanishing. */
    val statsFailed: Boolean = false,
    val loading: Boolean = true,
    val error: AppError? = null,
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val sessions: SessionRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val profile = profileManager.active.value
        _state.value = _state.value.copy(loading = true, error = null)

        // One request now carries everything the page shows. The screen used to also call
        // /api/analytics/models, whose rows include auxiliary usage while the daily rows do not —
        // so the model list added up to more than the headline. by_model rides along in this same
        // response, which removes both the contradiction and a tunnel round trip.
        val usage = try {
            analytics.usage(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = UsageUiState(loading = false, error = error.toUsageError("usage_load"))
            return@launch
        }

        // Store stats are supplementary; a failure here must not blank the whole page.
        val stats = try {
            sessions.stats(profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

        _state.value = UsageUiState(
            summary = summarize(usage),
            statsTotal = stats?.total,
            statsArchived = stats?.archived,
            statsMessages = stats?.messages,
            statsFailed = stats == null,
            loading = false,
        )
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            val summary = state.summary
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(error = state.error!!, onRetry = { vm.load() })
                summary == null || summary.isEmpty -> EmptyState(
                    title = l10n("这个身份还没有用量记录", "No usage recorded for this profile yet"),
                    subtitle = l10n(
                        "开始一次会话后，这里会显示 token 用量、模型分布和每日趋势。",
                        "Start a conversation and token usage, model mix and daily trend appear here.",
                    ),
                )
                else -> UsageContent(state, summary)
            }
        }
    }
}

@Composable
private fun UsageContent(state: UsageUiState, summary: UsageSummary) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Stat(l10n("TOKEN 总量", "Total tokens"), summary.totalTokens.compact(), Modifier.weight(1f))
                    Stat(
                        l10n("预估费用", "Est. cost"),
                        "≈ $" + "%.1f".format(summary.estimatedCost),
                        Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    // The one split that keeps the page honest: totals cover the main agent, the
                    // auxiliary calls (compression, titles, vision) are counted separately.
                    Stat(
                        l10n("主对话 / 辅助", "Main / auxiliary"),
                        "${summary.mainTokens.compact()} / ${summary.auxTokens.compact()}",
                        Modifier.weight(1f),
                    )
                    Stat(l10n("其中缓存读", "of which cache reads"), summary.cacheReadTokens.compact(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Stat(l10n("会话数", "Sessions"), summary.sessions.toString(), Modifier.weight(1f))
                    Stat(l10n("API 调用", "API calls"), summary.apiCalls.toString(), Modifier.weight(1f))
                }
                if (state.statsTotal != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Stat(l10n("会话总数", "All sessions"), state.statsTotal.toString(), Modifier.weight(1f))
                        Stat(
                            l10n("已归档 / 消息", "Archived / msgs"),
                            "${state.statsArchived ?: 0} / ${state.statsMessages ?: 0}",
                            Modifier.weight(1f),
                        )
                    }
                } else if (state.statsFailed) {
                    Text(
                        l10n("会话统计暂时取不到", "Session stats are unavailable right now"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (summary.daily.isNotEmpty()) {
                    Text(
                        l10n("每日 TOKEN · 主对话输入/输出", "DAILY TOKENS · MAIN AGENT IN/OUT"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    DailyTokensChart(summary.daily)
                }
            }
            Text(
                l10n("常用模型 · 含辅助调用", "TOP MODELS · INCLUDES AUXILIARY"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        // No key: model names can repeat across providers (e.g. two "gemma"),
        // and duplicate keys crash LazyColumn.
        items(summary.models.take(8)) { m ->
            ListItem(
                headlineContent = { Text(m.model) },
                supportingContent = {
                    Text(
                        l10n(
                            "${m.sessions} 次会话 · ${m.apiCalls} 次调用",
                            "${m.sessions} sessions · ${m.apiCalls} calls",
                        ),
                    )
                },
                trailingContent = { Text((m.inputTokens + m.outputTokens).compact() + " tok") },
            )
            HorizontalDivider()
        }
        item {
            // Every number above carries these three caveats; saying so once beats implying
            // precision the source data does not have.
            Text(
                l10n(
                    "统计窗口 ${summary.periodDays} 天 · 按会话开始日归集 · 日界为 UTC",
                    "${summary.periodDays}-day window · attributed to the session start day · UTC day boundary",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun DailyTokensChart(daily: List<UsageDayDto>) {
    // Already one entry per calendar day, zeros included, so equal spacing is now truthful.
    val maxTok = (daily.maxOfOrNull { it.inputTokens + it.outputTokens } ?: 1L).coerceAtLeast(1L)
    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 4.dp)) {
        if (daily.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (daily.size - 1)) / daily.size).coerceAtLeast(1f)
        daily.forEachIndexed { i, d ->
            val x = i * (barW + gap)
            val inH = size.height * (d.inputTokens.toFloat() / maxTok)
            val outH = size.height * (d.outputTokens.toFloat() / maxTok)
            // output stacked on top of input
            drawRect(inputColor, topLeft = Offset(x, size.height - inH), size = Size(barW, inH))
            drawRect(
                outputColor,
                topLeft = Offset(x, size.height - inH - outH),
                size = Size(barW, outH),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(l10n("输入", "input"), style = MaterialTheme.typography.labelSmall, color = inputColor)
        Spacer(Modifier.width(12.dp))
        Text(l10n("输出", "output"), style = MaterialTheme.typography.labelSmall, color = outputColor)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
