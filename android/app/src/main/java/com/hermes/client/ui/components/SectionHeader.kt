package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
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
 * [YESTERDAY] and [RECENT] arrived with HG-52 (2026-09-14), which added a 昨天 bucket and ended
 * 前 7 天 and 更早 sharing one colour — [OLDER] used to serve both.
 *
 * [ACTIVE] and [PAUSED] are the scheduled-jobs list's two neutral groups (docs/DESIGN.md §5.18).
 * They take scheme roles rather than the literal pillar colours: 已启用 / 已暂停 are not time
 * buckets, and borrowing 今天's green would claim a health state the group does not carry.
 */
internal enum class SectionTone { NEEDS_YOU, PINNED, TODAY, YESTERDAY, RECENT, OLDER, ACTIVE, PAUSED }

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
    // Every group's label carries its own hue (decision 2026-09-13), reversing "only 需要你处理
    // colours its label". The 8th pull's two group-header mocks disagreed — light colours one
    // label, dark colours all four — and the product owner settled on all four in both themes.
    //
    // These are label grades, not the pillar values: a pillar is a mark, a label is text, and
    // light 更早's pillar reads at ~2.1:1 as text on the warm paper. See Tiles.kt.
    val accent = when (tone) {
        SectionTone.NEEDS_YOU -> statusColor(StatusTone.WARN)
        SectionTone.PINNED -> com.hermes.client.ui.theme.groupLabelPinnedColor()
        SectionTone.TODAY -> com.hermes.client.ui.theme.groupLabelTodayColor()
        SectionTone.YESTERDAY -> com.hermes.client.ui.theme.groupLabelYesterdayColor()
        // 前 7 天 and 更早 share ONE label colour even though HG-52 split their pillars. Three
        // readable greys as text are not three colours a reader can separate; the pillars carry the
        // distinction, which is what the item actually asked for. See Tiles.kt.
        SectionTone.RECENT, SectionTone.OLDER -> com.hermes.client.ui.theme.groupLabelOlderColor()
        // The scheduled-jobs pair stays neutral: 已启用 / 已暂停 are not categories competing for
        // attention, and they have no mock of their own to take a hue from.
        //
        // Both take `onSurfaceVariant` and NOT their own pillar role. 已暂停's pillar is
        // `outlineVariant`, which is `#C9C7C2` in light — about 1.5:1 as text on the warm paper,
        // i.e. unreadable. Caught by looking at the re-recorded cron golden: the label had all but
        // disappeared. A faint mark is the point of that pillar; a faint label is a bug.
        SectionTone.ACTIVE, SectionTone.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
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
        SectionTone.YESTERDAY -> tunedPillars.yesterday
        SectionTone.RECENT -> tunedPillars.recent
        SectionTone.OLDER -> tunedPillars.older
        // Not tuned: the session-list panel has one slot per session-list group. These two are
        // scheme roles and move with the palette, not with that panel.
        SectionTone.ACTIVE -> MaterialTheme.colorScheme.outline
        SectionTone.PAUSED -> MaterialTheme.colorScheme.outlineVariant
    }
    // 「微通透下沉胶囊底衬」 (decision 2026-09-13, 8th pull): the header sits in its own tinted
    // capsule — `mx-4 h-8 rounded-lg`, the group's own hue at a low alpha with a matching hairline.
    // The design note's words for it: the capsule does not tear the body's sightline, and the
    // margins at both ends keep the page feeling flat and open.
    //
    // Fill and stroke are DERIVED from the pillar rather than being new tokens. That is what lets
    // the scheduled-jobs list — which has no mock for this — get correct capsules for its two
    // scheme-role tones out of the same formula.
    val fillAlpha = if (com.hermes.client.ui.theme.isDarkSurface()) 0.10f else 0.08f
    val capsuleShape = RoundedCornerShape(com.hermes.client.ui.tuning.tunedHeaderCapsuleRadius()) // TUNING-TEMP
    Box(
        // The capsule's margins. Vertical is what sets how far apart the groups sit: the light
        // draft spends 24dp between groups plus a divider, which measured +191dp over four headers
        // and would have handed back most of the density pass landed the day before. The dark draft
        // of the same round is the compact one — 4dp, no divider — and that is what ships
        // (decision 2026-09-13). Recorded as a deliberate deviation from the light draft.
        Modifier.padding(
            horizontal = 16.dp,
            vertical = com.hermes.client.ui.tuning.tunedHeaderPaddingV(), // TUNING-TEMP
        ),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(capsuleShape)
                .background(pillar.copy(alpha = fillAlpha))
                .border(1.dp, pillar.copy(alpha = 0.20f), capsuleShape)
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .height(com.hermes.client.ui.tuning.tunedHeaderCapsuleHeight()) // TUNING-TEMP
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            // 4 × 14dp with a 2dp radius — `w-1 h-3.5 rounded-[2px]`. The light draft's radius
            // wins over the dark draft's `rounded-full`, per the geometry-from-light rule.
            Modifier
                .size(width = com.hermes.client.ui.tuning.tunedPillarWidth(), height = com.hermes.client.ui.tuning.tunedPillarHeight()) // TUNING-TEMP
                .background(pillar, RoundedCornerShape(com.hermes.client.ui.tuning.tunedPillarRadius())), // TUNING-TEMP
        )
        Spacer(Modifier.size(8.dp))
        Text(
            // Not uppercased any more: the mock sets the label in sans at 12px and leaves the case
            // alone. Chinese never saw the uppercase anyway — this is visible on the English build.
            label,
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
            // A full pill with its own hairline now (`rounded-full` plus a border in the mock), so
            // it still reads as a chip against the tinted capsule behind it instead of dissolving
            // into it.
            shape = CircleShape,
            color = if (hot) statusColor(StatusTone.WARN).copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (hot) statusColor(StatusTone.WARN).copy(alpha = 0.30f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f),
            ),
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
                // 16px glyph in the mock, against Material's 24dp default.
                modifier = Modifier.padding(start = 8.dp).size(16.dp),
            )
        }
        }
    }
}
