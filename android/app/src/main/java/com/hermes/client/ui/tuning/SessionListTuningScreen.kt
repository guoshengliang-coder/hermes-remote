package com.hermes.client.ui.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.isDarkSurface
import kotlin.math.roundToInt

// TUNING-TEMP — see SessionListTuning.kt. Delete with `grep -rn TUNING-TEMP android/app/src`.

/**
 * Steppers rather than sliders: the question being answered is "is 15 or 15.5 better", and a
 * slider cannot reliably hit either. Every row shows its current value and its shipped default,
 * so "how far have I wandered" is answerable without leaving the screen.
 *
 * The panel edits the pillar colours of whichever theme is currently showing, and says which —
 * two sets of four swatches side by side would be four too many to judge at once.
 */
@Composable
fun SessionListTuningScreen(
    tuning: SessionListTuning,
    onChange: (SessionListTuning) -> Unit,
    onBack: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    val dark = isDarkSurface()
    val d = SessionListTuning()

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = localized(language, "会话列表调参（临时）", "Session list tuning (temporary)"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localized(language, "返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = {
                    Text(localized(language, "改完告诉我数值，我写进代码后删掉这一页", "Tell me the values; they go into the code and this page goes away"))
                },
                supportingContent = {
                    Text(
                        if (tuning.isDefault) {
                            localized(language, "当前全部为默认值", "Everything is at its default")
                        } else {
                            localized(language, "已有改动，用下面的「复制全部参数」发给我", "Changed — use 复制全部参数 below to send them over")
                        },
                    )
                },
            )
            HorizontalDivider()

            Section(localized(language, "字体 · 标题", "Type · title"))
            Stepper("titleSizeSp", tuning.titleSizeSp, d.titleSizeSp, 0.5f) { onChange(tuning.copy(titleSizeSp = it)) }
            IntStepper("titleWeightUnread", tuning.titleWeightUnread, d.titleWeightUnread, 50, 100, 900) { onChange(tuning.copy(titleWeightUnread = it)) }
            IntStepper("titleWeightRead", tuning.titleWeightRead, d.titleWeightRead, 50, 100, 900) { onChange(tuning.copy(titleWeightRead = it)) }
            Stepper("titleLineHeightSp", tuning.titleLineHeightSp, d.titleLineHeightSp, 0.5f) { onChange(tuning.copy(titleLineHeightSp = it)) }
            Stepper("titleTrackingSp", tuning.titleTrackingSp, d.titleTrackingSp, 0.05f, min = -2f, max = 2f) { onChange(tuning.copy(titleTrackingSp = it)) }

            Section(localized(language, "字体 · 副行与状态行", "Type · subline and status"))
            Stepper("sublineSizeSp", tuning.sublineSizeSp, d.sublineSizeSp, 0.5f) { onChange(tuning.copy(sublineSizeSp = it)) }
            Stepper("sublineLineHeightSp", tuning.sublineLineHeightSp, d.sublineLineHeightSp, 0.5f) { onChange(tuning.copy(sublineLineHeightSp = it)) }
            Stepper("statusSizeSp", tuning.statusSizeSp, d.statusSizeSp, 0.5f) { onChange(tuning.copy(statusSizeSp = it)) }
            IntStepper("statusWeight", tuning.statusWeight, d.statusWeight, 50, 100, 900) { onChange(tuning.copy(statusWeight = it)) }
            Stepper("statusLineHeightSp", tuning.statusLineHeightSp, d.statusLineHeightSp, 0.5f) { onChange(tuning.copy(statusLineHeightSp = it)) }

            Section(localized(language, "字体 · 分组头", "Type · group header"))
            Stepper("headerSizeSp", tuning.headerSizeSp, d.headerSizeSp, 0.5f) { onChange(tuning.copy(headerSizeSp = it)) }
            IntStepper("headerWeight", tuning.headerWeight, d.headerWeight, 50, 100, 900) { onChange(tuning.copy(headerWeight = it)) }
            Stepper("headerTrackingSp", tuning.headerTrackingSp, d.headerTrackingSp, 0.05f, min = -2f, max = 3f) { onChange(tuning.copy(headerTrackingSp = it)) }

            Section(localized(language, "间距", "Spacing"))
            Stepper("rowHeightDp（行高，决定行间疏密）", tuning.rowHeightDp, d.rowHeightDp, 2f, min = 40f, max = 140f) { onChange(tuning.copy(rowHeightDp = it)) }
            Stepper("sublineGapDp", tuning.sublineGapDp, d.sublineGapDp, 1f, min = 0f, max = 24f) { onChange(tuning.copy(sublineGapDp = it)) }
            Stepper("statusGapDp", tuning.statusGapDp, d.statusGapDp, 1f, min = 0f, max = 24f) { onChange(tuning.copy(statusGapDp = it)) }
            Stepper("headerPaddingVDp", tuning.headerPaddingVDp, d.headerPaddingVDp, 1f, min = 0f, max = 32f) { onChange(tuning.copy(headerPaddingVDp = it)) }

            Section(localized(language, "立柱", "Pillars"))
            Stepper("pillarWidthDp", tuning.pillarWidthDp, d.pillarWidthDp, 1f, min = 1f, max = 12f) { onChange(tuning.copy(pillarWidthDp = it)) }
            Stepper("pillarHeightDp", tuning.pillarHeightDp, d.pillarHeightDp, 1f, min = 4f, max = 40f) { onChange(tuning.copy(pillarHeightDp = it)) }
            ListItem(
                headlineContent = {
                    Text(
                        if (dark) localized(language, "下面改的是深色档", "Editing the DARK tier")
                        else localized(language, "下面改的是浅色档", "Editing the LIGHT tier"),
                    )
                },
                supportingContent = {
                    Text(localized(language, "切换外观里的主题就能改另一档", "Switch the theme in Appearance to edit the other one"))
                },
            )
            if (dark) {
                HexRow("需要你处理", tuning.pillarNeedsYouDark) { onChange(tuning.copy(pillarNeedsYouDark = it)) }
                HexRow("已置顶", tuning.pillarPinnedDark) { onChange(tuning.copy(pillarPinnedDark = it)) }
                HexRow("今天", tuning.pillarTodayDark) { onChange(tuning.copy(pillarTodayDark = it)) }
                HexRow("前 7 天 / 更早", tuning.pillarOlderDark) { onChange(tuning.copy(pillarOlderDark = it)) }
            } else {
                HexRow("需要你处理", tuning.pillarNeedsYouLight) { onChange(tuning.copy(pillarNeedsYouLight = it)) }
                HexRow("已置顶", tuning.pillarPinnedLight) { onChange(tuning.copy(pillarPinnedLight = it)) }
                HexRow("今天", tuning.pillarTodayLight) { onChange(tuning.copy(pillarTodayLight = it)) }
                HexRow("前 7 天 / 更早", tuning.pillarOlderLight) { onChange(tuning.copy(pillarOlderLight = it)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(tuning.asReport())) },
                    modifier = Modifier.weight(1f),
                ) { Text(localized(language, "复制全部参数", "Copy all values")) }
                OutlinedButton(
                    onClick = { onChange(SessionListTuning()) },
                    modifier = Modifier.weight(1f),
                ) { Text(localized(language, "恢复默认", "Restore defaults")) }
            }
            Text(
                tuning.asReport(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Stepper(
    name: String,
    value: Float,
    default: Float,
    step: Float,
    min: Float = 0f,
    max: Float = 200f,
    onChange: (Float) -> Unit,
) {
    // Round to 3 decimals: repeated += 0.05f otherwise drifts to 0.15000001 and the copied report
    // becomes unreadable.
    fun snap(v: Float) = ((v * 1000).roundToInt() / 1000f).coerceIn(min, max)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium) // l10n-allow: parameter identifier
            Text(
                // l10n-allow: numeric readout
                "${trim(value)}   ·   默认 ${trim(default)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (value == default) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(onClick = { onChange(snap(value - step)) }) { Text("−") } // l10n-allow: symbol
        Box(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(snap(value + step)) }) { Text("+") } // l10n-allow: symbol
    }
}

@Composable
private fun IntStepper(
    name: String,
    value: Int,
    default: Int,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium) // l10n-allow: parameter identifier
            Text(
                "$value   ·   默认 $default", // l10n-allow: numeric readout
                style = MaterialTheme.typography.bodySmall,
                color = if (value == default) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(onClick = { onChange((value - step).coerceIn(min, max)) }) { Text("−") } // l10n-allow: symbol
        Box(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange((value + step).coerceIn(min, max)) }) { Text("+") } // l10n-allow: symbol
    }
}

@Composable
private fun HexRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(parseHex(value), RoundedCornerShape(6.dp)),
        )
        Box(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)) // l10n-allow: group name, mirrors the list
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(7)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.width(140.dp),
        )
    }
}

private fun trim(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
