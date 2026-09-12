package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor

/**
 * What a group's leading pillar says about it (docs/DESIGN.md §5.2, decision 2026-09-10).
 *
 * One per group header. TIME split into TODAY and OLDER on 2026-09-11: the design source gives
 * 今天 its own green and puts 前 7 天 / 更早 on a slate, so a single "time" tone could not express it.
 *
 * [ACTIVE] and [PAUSED] are the scheduled-jobs list's two neutral groups (docs/DESIGN.md §5.18).
 * They take scheme roles rather than the literal pillar colours: 已启用 / 已暂停 are not time
 * buckets, and borrowing 今天's green would claim a health state the group does not carry.
 */
internal enum class SectionTone { NEEDS_YOU, PINNED, TODAY, OLDER, ACTIVE, PAUSED }

/**
 * The group header shared by every grouped list — sessions, and now scheduled jobs
 * (docs/DESIGN.md §5.2 / §5.18). It lived inside `SessionsScreen.kt` until the cron list needed
 * the same header; copying it was how the four lists drifted apart in the first place.
 */
@Composable
internal fun SectionHeader(
    label: String,
    count: Int,
    tone: SectionTone,
    note: String? = null,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    // The pillar carries the group's weight so the four headers stop reading as one texture.
    // Time buckets get a neutral bar on purpose — a time range is not a state, and colouring it
    // would spend the reader's attention on "when" instead of "what needs me".
    // Header text and pillar share one colour per group, so the two never disagree about how
    // urgent the group is. Only the group that needs action carries a hue (DESIGN.md §1 原则3,
    // amended 2026-09-10: the group header is no longer unconditionally the brand colour).
    val accent = when (tone) {
        // Only 需要你处理 colours its LABEL. The other groups colour the pillar and leave the words
        // neutral — four coloured labels would put four things in competition.
        SectionTone.NEEDS_YOU -> statusColor(StatusTone.WARN)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // TUNING-TEMP
    val tunedPillars = com.hermes.client.ui.tuning.pillarsOf(
        com.hermes.client.ui.tuning.LocalSessionListTuning.current,
        com.hermes.client.ui.theme.isDarkSurface(),
    )
    val pillar = when (tone) {
        // One colour per group, from the design source (Tiles.kt). The bright graphic amber here,
        // not the deep text one the label uses — the mock draws the mark and the word in two
        // different ambers (StatusColors.kt).
        // TUNING-TEMP: routed through the tuning panel so the four colours can be tried on a
        // device. Its defaults are the Tiles.kt / StatusColors.kt values, so an untouched panel
        // renders exactly what those files say.
        SectionTone.NEEDS_YOU -> tunedPillars.needsYou
        SectionTone.PINNED -> tunedPillars.pinned
        SectionTone.TODAY -> tunedPillars.today
        SectionTone.OLDER -> tunedPillars.older
        // Not tuned: the session-list panel has four slots because the session list has four
        // groups. These two are scheme roles and move with the palette, not with that panel.
        SectionTone.ACTIVE -> MaterialTheme.colorScheme.outline
        SectionTone.PAUSED -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        // px-4 py-2 in the mock: 16dp either side, 8dp above and below. The old 16/4 split
        // predates the mock being read as dp (docs/DESIGN.md §3.4).
        Modifier.fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = com.hermes.client.ui.tuning.tunedHeaderPaddingV()), // TUNING-TEMP
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // 3 × 12dp, fully rounded — `w-[3px] h-3 rounded-full`. Recorded as 3 × 14dp with a
            // 2dp radius on 2026-09-10; the mock says otherwise and the mock now wins.
            Modifier
                .size(width = com.hermes.client.ui.tuning.tunedPillarWidth(), height = com.hermes.client.ui.tuning.tunedPillarHeight()) // TUNING-TEMP
                .background(pillar, CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            label.uppercase(),
            style = com.hermes.client.ui.tuning.tunedGroupHeader(), // TUNING-TEMP
            color = accent,
        )
        note?.let {
            Text(
                "  ·  $it",
                style = com.hermes.client.ui.theme.SessionGroupNote,
                color = com.hermes.client.ui.theme.sublineFaintColor(),
            )
        }
        Spacer(Modifier.weight(1f))
        // The count is a chip, and the one group that needs action wears a tinted one. A bare
        // number gave all four groups the same visual weight.
        val hot = tone == SectionTone.NEEDS_YOU
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (hot) statusColor(StatusTone.WARN).copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                count.toString(),
                // px-2 py-0.5 in the mock, and 600 on the hot group against 500 elsewhere.
                style = com.hermes.client.ui.theme.SessionGroupCount
                    .let { if (hot) it.copy(fontWeight = FontWeight.SemiBold) else it },
                color = if (hot) statusColor(StatusTone.WARN)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (onToggle != null) {
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (collapsed) localized(language, "展开 $label", "Expand $label")
                else localized(language, "收起 $label", "Collapse $label"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 17px glyph in the mock, against Material's 24dp default.
                modifier = Modifier.padding(start = 8.dp).size(17.dp),
            )
        }
    }
}
