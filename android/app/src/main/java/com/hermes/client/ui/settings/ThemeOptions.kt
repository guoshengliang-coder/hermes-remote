package com.hermes.client.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.components.DesktopStrokeIcon
import com.hermes.client.ui.components.MoonStrokeIcon
import com.hermes.client.ui.components.SunStrokeIcon
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.CardIdentitySub
import com.hermes.client.ui.theme.CardThemeBadge
import com.hermes.client.ui.theme.CardThemeCta
import com.hermes.client.ui.theme.CardThemeOptionTitle
import com.hermes.client.ui.theme.CardThemeTitle
import com.hermes.client.ui.theme.cardIconTileColor
import com.hermes.client.ui.theme.cardThemeAccentColor
import com.hermes.client.ui.theme.cardThemeAccentInkColor
import com.hermes.client.ui.theme.cardThemeBadgeColor
import com.hermes.client.ui.theme.cardThemeBorderColor
import com.hermes.client.ui.theme.cardThemeIconTileColor
import com.hermes.client.ui.theme.cardThemeOptionColor
import com.hermes.client.ui.theme.isDarkSurface

// The theme picker, in the one shape both of its homes use (docs/DESIGN.md §5.1 主题弹层, Stitch
// 基线-卡片页/主题设置 / 暗夜).
//
// Two screens set the theme and they used to disagree about what the three options are even called
// — the card page said 「随系统」, 设置→外观 said 「跟随系统」 — and the segmented capsule the
// settings page drew them in could not have held the mock's longer names anyway (`maxLines = 1`,
// and English "Obsidian dark" overflows a third of the width at `fontScale` 1.3). One list, one
// set of names.
//
// What is NOT shared is WHEN the choice is written. The sheet holds it pending until 保存 (see
// [ThemeSheetContent]); this list writes on tap, because a page has no button to wait for and
// nothing to cancel back to.

private val THEME_MODES = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)

fun themeLabel(mode: ThemeMode, language: AppLanguage): String = when (mode) {
    ThemeMode.SYSTEM -> localized(language, "跟随系统", "Follow system")
    ThemeMode.LIGHT -> localized(language, "温润浅色", "Warm light")
    ThemeMode.DARK -> localized(language, "黑曜石深色", "Obsidian dark")
}

fun themeDescription(mode: ThemeMode, language: AppLanguage): String = when (mode) {
    ThemeMode.SYSTEM -> localized(
        language,
        "根据 Android 系统当前外观自动切换",
        "Follows whatever the Android system is set to",
    )
    ThemeMode.LIGHT -> localized(
        language,
        "手工纸质柔光，长文案阅读无眩光",
        "Soft handmade paper — no glare over long reads",
    )
    ThemeMode.DARK -> localized(
        language,
        "暗夜极客质感，OLED 省电高对比",
        "High-contrast obsidian, easy on an OLED panel",
    )
}

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> DesktopStrokeIcon
    ThemeMode.LIGHT -> SunStrokeIcon
    ThemeMode.DARK -> MoonStrokeIcon
}

/**
 * The three option cards.
 *
 * @param selected which option the radio marks. In the sheet this is the PENDING choice, not the
 *   theme currently in force.
 * @param inUse the theme actually in force, badged 「当前使用」. Pass `null` where selection and
 *   effect are the same thing — on 设置→外观 the badge would only restate the radio beside it.
 */
@Composable
fun ThemeOptionList(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    inUse: ThemeMode? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
) {
    val language = LocalAppLanguage.current
    Column(
        modifier.fillMaxWidth().selectableGroup().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        THEME_MODES.forEach { mode ->
            ThemeOptionCard(
                mode = mode,
                selected = mode == selected,
                inUse = mode == inUse,
                label = themeLabel(mode, language),
                description = themeDescription(mode, language),
                onSelect = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    mode: ThemeMode,
    selected: Boolean,
    inUse: Boolean,
    label: String,
    description: String,
    onSelect: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardThemeOptionColor(),
        border = BorderStroke(1.dp, cardThemeBorderColor()),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("theme-option-${mode.name}"),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardThemeIconTileColor())
                    .border(1.dp, cardThemeBorderColor(), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // The dark mock's rule, used for both tiers: the selected option's glyph steps up
                // to body ink, the rest stay muted. The light mock tints all three differently
                // (near-black, amber, ink) with no rule behind it — see the lock file's
                // specChanges.
                Icon(
                    themeIcon(mode),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = CardThemeOptionTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (inUse) {
                        Spacer(Modifier.size(8.dp))
                        InUseBadge(localized(language, "当前使用", "In use"))
                    }
                }
                Text(
                    description,
                    style = CardIdentitySub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            ThemeRadio(selected)
        }
    }
}

/** 「当前使用」: what the theme actually IS, as opposed to what the radio has been moved to. */
@Composable
private fun InUseBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = cardThemeBadgeColor(),
        border = BorderStroke(1.dp, cardThemeBorderColor()),
    ) {
        Text(
            text,
            style = CardThemeBadge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Ring plus dot, in both themes.
 *
 * The dark mock draws exactly this. The light mock fills the whole 20dp disc with the accent and
 * insets a 4px white ring — which, on a white card, renders as a bare dot with no ring at all. The
 * lock file records the ruling: that is a side effect of how the inset shadow lands on this
 * background, not a second way of drawing a radio, so both tiers take the dark mock's shape. It is
 * the one place in the repo where geometry does not come from the light mock.
 */
@Composable
private fun ThemeRadio(selected: Boolean) {
    val accent = cardThemeAccentColor()
    Box(
        Modifier
            .size(20.dp)
            .border(
                2.dp,
                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
    }
}

/**
 * Everything below the grab bar in the card page's theme sheet.
 *
 * Separate from the `ModalBottomSheet` that hosts it on purpose: a sheet renders in its own window,
 * where `onRoot()` cannot reach it, so this is what the Roborazzi goldens capture.
 *
 * @param inUse the theme in force when the sheet opened, and the one still in force until [onSave].
 */
@Composable
fun ThemeSheetContent(
    inUse: ThemeMode,
    pending: ThemeMode,
    onPendingChange: (ThemeMode) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                localized(language, "外观与主题", "Appearance & theme"),
                style = CardThemeTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(cardIconTileColor())
                    // The dark mock rings this button and the light one does not — the same
                    // "dark replaces shadow with a stroke" move the card page already makes.
                    .then(
                        if (isDarkSurface()) {
                            Modifier.border(1.dp, cardThemeBorderColor(), CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(onClick = onClose)
                    .testTag("theme-sheet-close"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = localized(language, "关闭", "Close"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        ThemeOptionList(selected = pending, onSelect = onPendingChange, inUse = inUse)

        Button(
            onClick = onSave,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = cardThemeAccentColor(),
                contentColor = cardThemeAccentInkColor(),
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)
                .height(48.dp)
                .testTag("theme-sheet-save"),
        ) {
            // 「保存」, where the mock writes 「完成」. Nothing has happened yet when this button is
            // shown, so "done" would be a lie — the one word this change takes off the mock.
            Text(localized(language, "保存", "Save"), style = CardThemeCta)
        }
    }
}
