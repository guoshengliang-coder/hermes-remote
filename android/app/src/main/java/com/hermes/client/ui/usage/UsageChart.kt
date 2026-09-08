package com.hermes.client.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.UsageDayDto
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.theme.ChartBand
import com.hermes.client.ui.theme.chartBandColor
import com.hermes.client.ui.theme.hairlineColor
import kotlin.math.floor
import kotlin.math.pow

private val PLOT_HEIGHT = 132.dp
private val AXIS_WIDTH = 52.dp

/**
 * Round a maximum up to a readable gridline value: 1, 2 or 5 times a power of ten.
 *
 * A raw maximum makes the axis label a number nobody can hold in their head ("847,213"), and a
 * plain power-of-ten rounding wastes up to 90% of the plot height on an empty top half.
 */
fun niceMax(value: Long): Long {
    if (value <= 0) return 1
    val magnitude = 10.0.pow(floor(kotlin.math.log10(value.toDouble())))
    val normalized = value / magnitude
    val step = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return (step * magnitude).toLong()
}

/**
 * Axis tick without the decimal point `compact()` always prints. "500K" fits the gutter; "500.0K"
 * wraps onto two lines and drags the whole scale out of alignment.
 */
fun axisTick(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

/**
 * Sheet title date. Upstream sends `YYYY-MM-DD`; showing that raw is a database value, not a date
 * a person reads. Formatted here rather than with a locale formatter because the label must follow
 * the app's own language switch, not the device locale.
 */
fun sheetDateLabel(day: String, chinese: Boolean): String {
    val parts = day.split("-")
    if (parts.size != 3) return day
    val month = parts[1].toIntOrNull() ?: return day
    val date = parts[2].toIntOrNull() ?: return day
    if (chinese) return "$month 月 $date 日"
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val name = names.getOrNull(month - 1) ?: return day
    return "$name $date"
}

/** Short axis date, e.g. `9/03`, from the `YYYY-MM-DD` upstream emits. */
fun axisLabel(day: String): String {
    val parts = day.split("-")
    return if (parts.size == 3) "${parts[1].trimStart('0')}/${parts[2]}" else day
}

/**
 * Daily main-agent tokens, stacked input / output / cache read.
 *
 * Auxiliary usage is deliberately absent: upstream aggregates it per (model, task) with no day
 * dimension, so it can be totalled but never placed on a date (DESIGN.md §5.14).
 */
@Composable
fun DailyTokensChart(
    daily: List<UsageDayDto>,
    onSelectDay: (UsageDayDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (daily.isEmpty()) return
    val ceiling = niceMax(daily.maxOf { it.totalTokens })
    val input = chartBandColor(ChartBand.INPUT)
    val output = chartBandColor(ChartBand.OUTPUT)
    val cache = chartBandColor(ChartBand.CACHE)
    val grid = hairlineColor()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Row {
            // Laid out by the column, not by hand-computed padding: the first attempt pinned each
            // label with `padding(top = i * 58.dp)`, which drifts as soon as a label wraps.
            Column(
                Modifier.width(AXIS_WIDTH).height(PLOT_HEIGHT).padding(end = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                listOf(1f, 0.5f, 0f).forEach { fraction ->
                    Text(
                        axisTick((ceiling * fraction).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
            Box(Modifier.weight(1f).height(PLOT_HEIGHT)) {
                Canvas(
                    Modifier.fillMaxWidth().height(PLOT_HEIGHT).pointerInput(daily) {
                        detectTapGestures { offset ->
                            val slot = size.width.toFloat() / daily.size
                            val index = (offset.x / slot).toInt().coerceIn(0, daily.lastIndex)
                            daily.getOrNull(index)?.takeIf { it.totalTokens > 0 }?.let(onSelectDay)
                        }
                    },
                ) {
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        val y = (size.height - 1f) * fraction
                        drawRect(grid, topLeft = Offset(0f, y), size = Size(size.width, 1f))
                    }
                    val gap = 3.dp.toPx()
                    val barWidth = ((size.width - gap * (daily.size - 1)) / daily.size).coerceAtLeast(1f)
                    daily.forEachIndexed { i, d ->
                        val x = i * (barWidth + gap)
                        var top = size.height
                        // Bottom-up: input, output, cache — the legend below reads in the same
                        // order, so the strongest band anchors the baseline.
                        listOf(d.inputTokens to input, d.outputTokens to output, d.cacheReadTokens to cache)
                            .forEach { (tokens, colour) ->
                                if (tokens <= 0) return@forEach
                                val h = size.height * (tokens.toFloat() / ceiling)
                                top -= h
                                drawRect(colour, topLeft = Offset(x, top), size = Size(barWidth, h))
                            }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = AXIS_WIDTH, top = 6.dp),
        ) {
            val labels = listOf(daily.first(), daily[daily.size / 2], daily.last())
            labels.forEachIndexed { i, d ->
                Text(
                    axisLabel(d.day),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> androidx.compose.ui.text.style.TextAlign.Start
                        1 -> androidx.compose.ui.text.style.TextAlign.Center
                        else -> androidx.compose.ui.text.style.TextAlign.End
                    },
                )
            }
        }
    }
}

@Composable
fun ChartLegend(summary: UsageSummary, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        LegendSwatch(chartBandColor(ChartBand.INPUT), l10n("输入", "input"), summary.inputTokens)
        LegendSwatch(chartBandColor(ChartBand.OUTPUT), l10n("输出", "output"), summary.outputTokens)
        LegendSwatch(chartBandColor(ChartBand.CACHE), l10n("缓存读", "cache"), summary.cacheReadTokens)
    }
}

@Composable
private fun LegendSwatch(colour: Color, label: String, tokens: Long) {
    Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colour))
        Text(
            "  $label ${tokens.compact()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
