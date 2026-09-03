package com.hermes.client.ui.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.AuxTaskUsageDto
import com.hermes.client.data.network.ModelUsageDto
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.ui.components.SheetCloseHandle
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.theme.ChartBand
import com.hermes.client.ui.theme.chartBandColor
import com.hermes.client.ui.theme.hairlineColor
import com.hermes.client.ui.theme.tileColor
import com.hermes.client.ui.theme.tileShadow

/** Faint-card container shared by every block on the page (DESIGN.md §2.3). */
@Composable
fun UsageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = tileColor(),
        shadowElevation = tileShadow(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Hairline(vertical: Int = 12) {
    Box(Modifier.fillMaxWidth().padding(vertical = vertical.dp).height(1.dp).background(hairlineColor()))
}

@Composable
fun HeroCard(summary: UsageSummary) {
    UsageCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                l10n("TOKEN 总量", "TOTAL TOKENS"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                l10n("最近 ${summary.periodDays} 天", "last ${summary.periodDays} days"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            summary.totalTokens.compact(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        // The split that keeps the page honest: `totals` covers the main agent, auxiliary calls
        // are a separate account that `by_model` folds in but the daily rows never see.
        Text(
            l10n(
                "主对话 ${summary.mainTokens.compact()} · 辅助 ${summary.auxTokens.compact()}",
                "main ${summary.mainTokens.compact()} · auxiliary ${summary.auxTokens.compact()}",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Hairline()
        Text(
            l10n(
                "${summary.sessions} 次会话 · ${summary.apiCalls} 次调用",
                "${summary.sessions} sessions · ${summary.apiCalls} calls",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One row of a proportional breakdown: name, value, share bar. */
@Composable
fun ProportionRow(name: String, sub: String, value: Long, share: Float, band: ChartBand) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(value.compact(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(share * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 7.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(chartBandColor(band)),
            )
        }
    }
}

@Composable
fun ModelsCard(models: List<ModelUsageDto>, total: Long, expanded: Boolean, onToggle: () -> Unit) {
    val shown = if (expanded) models else models.take(4)
    UsageCard {
        shown.forEach { m ->
            val tokens = m.inputTokens + m.outputTokens
            ProportionRow(
                name = m.model,
                sub = m.provider?.takeIf { it.isNotBlank() }
                    ?.let { p -> l10n("$p · ${m.apiCalls} 次调用", "$p · ${m.apiCalls} calls") }
                    ?: l10n("${m.apiCalls} 次调用", "${m.apiCalls} calls"),
                value = tokens,
                share = if (total > 0) tokens.toFloat() / total else 0f,
                band = ChartBand.OUTPUT,
            )
        }
        if (models.size > 4) {
            Hairline(vertical = 2)
            Text(
                if (expanded) l10n("收起", "Show less")
                else l10n("查看全部 ${models.size} 个模型", "Show all ${models.size} models"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
fun AuxTasksCard(tasks: List<AuxTaskUsageDto>) {
    val leader = tasks.maxOfOrNull { it.totalTokens } ?: 0L
    UsageCard {
        Text(
            l10n(
                "主对话之外的模型调用 · 不计入每日趋势 · 占比为辅助内部占比",
                "Model calls outside the main conversation · absent from the daily trend · share within auxiliary",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        tasks.forEach { t ->
            ProportionRow(
                name = auxTaskLabel(t.task),
                sub = l10n("${t.apiCalls} 次调用", "${t.apiCalls} calls"),
                value = t.totalTokens,
                share = if (leader > 0) t.totalTokens.toFloat() / leader else 0f,
                band = ChartBand.CACHE,
            )
        }
    }
}

/** Upstream task ids are snake_case English; the known ones get product copy. */
@Composable
fun auxTaskLabel(task: String): String = when (task) {
    "compression" -> l10n("上下文压缩", "Context compression")
    "title_generation" -> l10n("标题生成", "Title generation")
    "vision" -> l10n("视觉识别", "Vision")
    "session_search" -> l10n("会话搜索", "Session search")
    "web_extract" -> l10n("网页提取", "Web extract")
    "smart_approval" -> l10n("智能审批", "Smart approval")
    "" -> l10n("其他", "Other")
    else -> task
}

@Composable
fun CostCard(summary: UsageSummary, onExplain: () -> Unit) {
    UsageCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // One decimal on purpose. Two implied a billing figure the source cannot support.
            Text(
                "≈ $" + "%.1f".format(summary.estimatedCost),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                l10n("口径说明", "How it's counted"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onExplain).padding(8.dp),
            )
        }
        Text(
            l10n(
                "本机估算，不含辅助调用与重试，通常低于账单",
                "Local estimate; excludes auxiliary calls and retries, usually below the bill",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun UsageFootnote(periodDays: Int) {
    Text(
        l10n(
            "统计窗口 $periodDays 天 · 按会话开始日归集 · 日界为 UTC",
            "$periodDays-day window · attributed to the session start day · UTC day boundary",
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostInfoSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState(), dragHandle = null) {
        SheetCloseHandle(onDismiss)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(l10n("成本估算说明", "How the cost is counted"), style = MaterialTheme.typography.titleMedium)
            listOf(
                l10n(
                    "金额由 Mac 端 Hermes 本地估算，只统计成功返回用量数据的主对话调用。",
                    "Hermes estimates this locally, counting only main-agent calls that returned usage data.",
                ),
                l10n(
                    "不包含上下文压缩、标题生成、视觉识别等辅助调用，也不包含服务商侧的重试与回退。",
                    "It excludes auxiliary calls such as compression, title generation and vision, and excludes provider-side retries and fallbacks.",
                ),
                l10n("缓存写入不计入统计。", "Cache writes are not counted."),
                l10n(
                    "实际账单通常高于这个数字。需要准确金额时，请以模型服务商的账单为准。",
                    "The real bill is usually higher. For an exact figure, use your provider's billing.",
                ),
            ).forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(day: UsageDayDto, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState(), dragHandle = null) {
        SheetCloseHandle(onDismiss)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(
                sheetDateLabel(day.day, chinese = LocalAppLanguage.current == AppLanguage.ZH),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                day.totalTokens.compact(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                l10n(
                    "${day.sessions} 次会话 · ${day.apiCalls} 次调用",
                    "${day.sessions} sessions · ${day.apiCalls} calls",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Hairline(vertical = 16)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DaySplit(l10n("输入", "input"), day.inputTokens, ChartBand.INPUT, Modifier.weight(1f))
                DaySplit(l10n("输出", "output"), day.outputTokens, ChartBand.OUTPUT, Modifier.weight(1f))
                DaySplit(l10n("缓存读", "cache"), day.cacheReadTokens, ChartBand.CACHE, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DaySplit(label: String, tokens: Long, band: ChartBand, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(8.dp).width(8.dp).clip(RoundedCornerShape(2.dp)).background(chartBandColor(band)))
            Text(
                "  $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            tokens.compact(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
