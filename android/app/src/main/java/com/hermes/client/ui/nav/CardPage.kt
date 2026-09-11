package com.hermes.client.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.BuildConfig
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.data.repository.hasCustomName
import com.hermes.client.ui.components.ProfileAvatar
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.CardChip
import com.hermes.client.ui.theme.CardFooter
import com.hermes.client.ui.theme.CardIdentityName
import com.hermes.client.ui.theme.CardIdentitySub
import com.hermes.client.ui.theme.CardNodeTitle
import com.hermes.client.ui.theme.CardDotGood
import com.hermes.client.ui.theme.CardRowTitle
import com.hermes.client.ui.theme.CardRowValue
import com.hermes.client.ui.theme.CardWordmark
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.cardChipColor
import com.hermes.client.ui.theme.cardDividerColor
import com.hermes.client.ui.theme.cardDrawerColor
import com.hermes.client.ui.theme.cardInkMutedColor
import com.hermes.client.ui.theme.cardFooterRuleColor
import com.hermes.client.ui.theme.cardIconTileBorderColor
import com.hermes.client.ui.theme.cardIconTileColor
import com.hermes.client.ui.theme.cardTileBorderColor
import com.hermes.client.ui.theme.cardTileColor
import com.hermes.client.ui.theme.cardTileShadow
import com.hermes.client.ui.theme.isDarkSurface
import com.hermes.client.ui.theme.statusColor
import com.hermes.client.ui.theme.warnGraphicColor
import com.hermes.client.update.UpdateBadgeState

/**
 * The card page (modal drawer off the session list), v5 — conformed to the SECOND pull of the
 * Stitch baseline 基线-卡片页 / 暗夜 (docs/design/stitch/card.default.*.html, 2026-09-11;
 * docs/DESIGN.md §5.1): "Hermes GO" wordmark + build-type chip + a bare 36dp gear; an identity
 * card showing ONLY the current profile (tap → the profile picker); a ONE-ROW remote-node card
 * (the connected Mac and its round-trip time, tap → the remote devices page); bare shortcut rows
 * separated by hairlines — scheduled jobs, theme, default model, app updates, feedback; a ✦ rule
 * and an italic tagline pinned to the bottom.
 *
 * Gone with the second pull: the status capsule and its latency bands (优/普通/延迟), the online /
 * total device counts, and the card that used to wrap the shortcut rows. Gone with the first:
 * the 本周用量 half of the old stats card, whose entry now lives in Settings.
 *
 * The feedback row is absent when the build carries no MissionGo endpoint/token, which is a
 * supported configuration rather than an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardPage(
    onNavigate: (String) -> Unit,
    drawerState: androidx.compose.material3.DrawerState? = null,
    vm: CardPageViewModel = hiltViewModel(),
) {
    val language = LocalAppLanguage.current
    val active by vm.active.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsState()
    LaunchedEffect(Unit) { vm.refreshUpdateBadge() }
    var themeSheet by remember { mutableStateOf(false) }
    val launchFeedback = com.hermes.client.ui.feedback.rememberFeedbackLauncher(vm.feedbackReporter)

    ModalDrawerSheet(
        drawerState = drawerState ?: androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open),
        // The mock: `w-[84%] max-w-[340px] rounded-r-3xl`, top to bottom.
        modifier = Modifier.fillMaxWidth(0.84f).widthIn(max = 340.dp),
        drawerShape = RoundedCornerShape(topStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 0.dp),
        drawerContainerColor = cardDrawerColor(),
    ) {
        CardPageContent(
            activeProfile = active,
            state = state,
            health = health,
            themeMode = themeMode,
            updateState = updateState,
            buildBadge = buildBadgeFor(BuildConfig.BUILD_TYPE),
            onNavigate = onNavigate,
            onTheme = { themeSheet = true },
            onFeedback = if (vm.feedbackReporter.isAvailable) {
                {
                    launchFeedback(
                        com.hermes.client.data.feedback.FeedbackPrefill(context = mapOf("entry" to "card_page")),
                    )
                }
            } else null,
        )
    }

    if (themeSheet) {
        ModalBottomSheet(onDismissRequest = { themeSheet = false }, sheetState = com.hermes.client.ui.components.hermesSheetState()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    localized(language, "主题", "Theme"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
                listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
                    ListItem(
                        leadingContent = { RadioButton(selected = themeMode == mode, onClick = null) },
                        headlineContent = { Text(themeLabel(mode, language)) },
                        modifier = Modifier.clickable { vm.setThemeMode(mode); themeSheet = false },
                    )
                }
            }
        }
    }
}

/**
 * The sheet's content, stateless so the screenshot tests can render every state without a
 * ViewModel. Everything in [CardPage] that is not a data source lives here.
 */
@Composable
fun CardPageContent(
    activeProfile: String?,
    state: CardPageUiState,
    health: GatewayHealth,
    themeMode: ThemeMode,
    updateState: UpdateBadgeState,
    buildBadge: String?,
    onNavigate: (String) -> Unit,
    onTheme: () -> Unit,
    /** Null when this build has no feedback channel: the row is then absent, not disabled. */
    onFeedback: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val dark = isDarkSurface()

    // The card page renders at design scale regardless of the system font size — the user's
    // explicit call after outsized text on large-font devices. Scoped to this sheet only; every
    // reading surface (chat, lists, settings) still honours the system preference.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val cardDensity = remember(baseDensity) {
        androidx.compose.ui.unit.Density(baseDensity.density, fontScale = minOf(baseDensity.fontScale, 1.0f))
    }

    // The dark mock rings the sheet's right edge in white at 10% (`border-r border-white/10`).
    val edge = if (dark) Color.White.copy(alpha = 0.10f) else Color.Transparent

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides cardDensity,
    ) {
        Column(
            modifier
                .fillMaxHeight()
                .drawBehind {
                    if (edge.alpha > 0f) {
                        drawLine(edge, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), strokeWidth = 1f)
                    }
                }
                .statusBarsPadding(),
        ) {
            // `px-5 pt-3 pb-3`, sections `gap-4`, header `pt-1 pb-1`; the footer is pinned below.
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 12.dp),
            ) {
                CardHeader(buildBadge = buildBadge, onSettings = { onNavigate("settings") })

                Spacer(Modifier.height(16.dp))
                IdentityCard(activeProfile = activeProfile, onClick = { onNavigate("profiles") })

                Spacer(Modifier.height(16.dp))
                RemoteNodeCard(state = state, health = health, onClick = { onNavigate("remote_devices") })

                // The rows sit on the drawer itself since the second pull — no card, just
                // hairlines between them (`divide-y`, `pt-1`).
                Spacer(Modifier.height(20.dp))
                Column {
                    val jobs = state.cronJobCount
                    ShortcutRow(
                        icon = ClockIcon,
                        label = localized(language, "定时任务", "Scheduled jobs"),
                        value = jobs?.let { localized(language, "$it 个任务", if (it == 1) "$it job" else "$it jobs") },
                        // Failed or overdue jobs: the amber "something here" dot, in front of the
                        // count. The mock shows 「0 运行中」 instead; the count of jobs that will
                        // actually fire is the number we can stand behind (decision 2026-09-11).
                        dot = if (state.cronAlerts > 0) RowDot.WARN else RowDot.NONE,
                        onClick = { onNavigate("cron") },
                    )
                    CardRowDivider()
                    ShortcutRow(
                        icon = MoonIcon,
                        label = localized(language, "主题", "Theme"),
                        value = themeLabel(themeMode, language),
                        onClick = onTheme,
                    )
                    CardRowDivider()
                    ShortcutRow(
                        icon = CubeIcon,
                        label = localized(language, "默认模型", "Default model"),
                        value = state.defaultModel ?: "—",
                        onClick = { onNavigate("models") },
                    )
                    CardRowDivider()
                    ShortcutRow(
                        icon = DownloadBoxIcon,
                        label = localized(language, "检查更新", "App updates"),
                        // One dot, three meanings: green once a check confirms this is the newest
                        // build, amber when a newer one exists, nothing at all before the first
                        // successful check (§5.1).
                        value = when (updateState) {
                            is UpdateBadgeState.Available ->
                                localized(language, "新版本 ${updateState.versionName}", "New ${updateState.versionName}")
                            UpdateBadgeState.UpToDate ->
                                localized(language, "v${BuildConfig.VERSION_NAME}（最新）", "v${BuildConfig.VERSION_NAME} (latest)")
                            UpdateBadgeState.Unknown -> "v${BuildConfig.VERSION_NAME}"
                        },
                        dot = when (updateState) {
                            is UpdateBadgeState.Available -> RowDot.WARN
                            UpdateBadgeState.UpToDate -> RowDot.GOOD
                            UpdateBadgeState.Unknown -> RowDot.NONE
                        },
                        onClick = { onNavigate("app_update") },
                    )
                    if (onFeedback != null) {
                        CardRowDivider()
                        ShortcutRow(
                            icon = com.hermes.client.ui.components.FeedbackBubbleIcon,
                            label = localized(language, "反馈与建议", "Feedback"),
                            // No value: this row performs an action instead of leading somewhere
                            // with a current setting to show.
                            onClick = onFeedback,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            CardFooterMark()
        }
    }
}

/**
 * The bottom flourish the second pull added: two hairlines fading out from a ✦, then the brand
 * line in italic serif. Decorative — no `contentDescription`, nothing to read aloud.
 */
@Composable
private fun CardFooterMark() {
    val rule = cardFooterRuleColor()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.alpha(0.4f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(40.dp).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, rule))),
            )
            Text(
                "✦", // l10n-allow: decorative glyph
                style = CardFooter.copy(fontSize = 10.sp, fontStyle = null),
                color = rule,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Box(
                Modifier.width(40.dp).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(rule, Color.Transparent))),
            )
        }
        Text(
            "Your AI Agent, in Your Pocket", // l10n-allow: official English brand slogan
            style = CardFooter,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ── Sections ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CardHeader(buildBadge: String?, onSettings: () -> Unit) {
    val language = LocalAppLanguage.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Hermes GO", style = CardWordmark, color = MaterialTheme.colorScheme.onSurface) // l10n-allow: brand wordmark
        if (buildBadge != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = cardChipColor(),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    buildBadge,
                    style = CardChip,
                    color = cardInkMutedColor(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 36dp and NO container since the second pull: the gear is an icon on the paper, the way
        // the session list's top-bar buttons are (SessionsScreen's 36dp IconButtons). Under
        // Material's 48dp touch floor, which was dropped on 2026-09-11 (DESIGN.md §7 item 8).
        androidx.compose.material3.IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                GearIcon,
                contentDescription = localized(language, "设置", "Settings"),
                tint = cardInkMutedColor(),
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/** The two cards: `rounded-2xl` paper-subtle fill, a hairline border, a whisper of shadow. */
@Composable
private fun CardTile(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val border = BorderStroke(1.dp, cardTileBorderColor())
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = cardTileColor(),
            border = border,
            shadowElevation = cardTileShadow(),
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(
            shape = shape,
            color = cardTileColor(),
            border = border,
            shadowElevation = cardTileShadow(),
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun IdentityCard(activeProfile: String?, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    CardTile(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(activeProfile, size = 44.dp)
            // With a custom display name the big line is that name and the profile name moves to
            // the subline; otherwise the card reads exactly as before.
            val identity = com.hermes.client.ui.components.LocalProfileIdentities.current[activeProfile]
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    com.hermes.client.data.repository.displayNameFor(activeProfile, identity),
                    style = CardIdentityName,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (identity.hasCustomName()) activeProfile.orEmpty()
                    else localized(language, "当前身份", "Active profile"),
                    style = CardIdentitySub,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                ThinChevron, contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
    }
}

/**
 * The connected Mac, on one row since the second pull: icon tile, the static title 「远程节点」 with
 * the device name beneath it, and the round-trip time on the right. The capsule, its latency bands
 * and the online/total counts all went with that pull.
 */
@Composable
private fun RemoteNodeCard(state: CardPageUiState, health: GatewayHealth, onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    val latency = (health as? GatewayHealth.Healthy)?.latencyMs
    val offline = state.deviceId == null

    CardTile(onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(cardIconTileColor())
                    .border(1.dp, cardIconTileBorderColor(), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DesktopIcon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    localized(language, "远程节点", "Remote nodes"),
                    style = CardNodeTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Two lines before an ellipsis: on a 360dp phone the drawer is 302dp, and a long
                // Mac name beside the latency has nowhere to go on one (vivo V2166BA, 2026-09-11).
                Text(
                    if (offline) localized(language, "连接器离线", "Connector offline")
                    else localized(language, "${state.deviceId} (当前)", "${state.deviceId} (current)"),
                    style = CardIdentitySub,
                    color = if (offline) statusColor(StatusTone.BAD) else MaterialTheme.colorScheme.outline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                when {
                    offline -> localized(language, "离线", "Offline")
                    latency != null -> formatLatency(latency)
                    else -> localized(language, "已连接", "Connected")
                },
                style = CardRowValue,
                color = if (offline) statusColor(StatusTone.BAD) else cardInkMutedColor(),
                maxLines = 1, softWrap = false,
            )
            Icon(
                ThinChevron, contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
        }
    }
}

/** What the dot in front of a row's value means, if there is one. */
private enum class RowDot { NONE, GOOD, WARN }

/** A hairline between two shortcut rows — the mock's `divide-y`, inset by the row's own padding. */
@Composable
private fun CardRowDivider() {
    HorizontalDivider(color = cardDividerColor(), modifier = Modifier.padding(horizontal = 4.dp))
}

/**
 * One shortcut row: `h-[48px] px-1`, icon 20 + label, then (dot +) value + chevron on the right.
 * Bare on the drawer since the second pull — the card that used to wrap these is gone, so the
 * press ripple is clipped to the mock's own `rounded-lg` instead of the card's corner.
 *
 * Entry-row contract (DESIGN.md §5.1): tappable rows always carry the chevron.
 */
@Composable
private fun ShortcutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    dot: RowDot = RowDot.NONE,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = cardInkMutedColor(), modifier = Modifier.size(20.dp))
        Text(
            label,
            style = CardRowTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        if (dot != RowDot.NONE) {
            Box(
                Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape)
                    .background(if (dot == RowDot.GOOD) CardDotGood else warnGraphicColor()),
            )
        }
        value?.let {
            AutoShrinkText(
                it,
                style = CardRowValue.copy(color = cardInkMutedColor()),
                minFontSize = 12.sp,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        Icon(
            ThinChevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp).size(16.dp),
        )
    }
}

private fun themeLabel(mode: ThemeMode, language: com.hermes.client.ui.localization.AppLanguage): String = when (mode) {
    ThemeMode.SYSTEM -> localized(language, "随系统", "System")
    ThemeMode.LIGHT -> localized(language, "浅色", "Light")
    ThemeMode.DARK -> localized(language, "深色", "Dark")
}

// ── Text fitting ────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutoShrinkText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    // Re-measure from the top size whenever the text changes.
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    var lines by remember(text) { mutableStateOf(1) }
    Text(
        text,
        style = style.copy(
            fontSize = fontSize,
            // Keep the leading proportional once shrunk/wrapped; the original lineHeight
            // belongs to the full-size single-line form.
            lineHeight = (fontSize.value * 1.25f).sp,
        ),
        maxLines = lines,
        softWrap = lines > 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            val overflowed = result.didOverflowWidth ||
                result.isLineEllipsized(result.lineCount - 1)
            if (overflowed) {
                if (fontSize.value > minFontSize.value) {
                    fontSize = (fontSize.value - 1f).coerceAtLeast(minFontSize.value).sp
                } else if (lines == 1) {
                    lines = 2
                }
            }
        },
        modifier = modifier,
    )
}

// ── Hand-drawn thin-stroke icon set ─────────────────────────────────────────────────────────
// Material's outlined icons are cut for a 2dp stroke and read heavy against the reference,
// whose glyphs sit at ~1.7dp. These are drawn to the reference paths with the same brush:
// 1.7 stroke, round caps/joins, tinted by Icon like any vector. The Stitch mock draws its own
// 1.5-stroke set (a chip for the model, cycling arrows for updates); the repo's set stays —
// product decision 2026-09-11, DESIGN.md §4 remains the icon authority.

private fun strokeIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): androidx.compose.ui.graphics.vector.ImageVector =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/**
 * Proper toothed gear (stroke style): cog ring outline + centre hole. The earlier hub+ticks
 * simplification read as a brightness/sun glyph next to the theme row. Ring path per the
 * classic stroke-gear construction (Lucide-style), same 1.7 brush as the rest of the set.
 */
private val GearIcon by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "ThinGear",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.addPathNodes(
                "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08" +
                    "a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51" +
                    "a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08" +
                    "a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18" +
                    "a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39" +
                    "a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09" +
                    "a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25" +
                    "a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" +
                    "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
            ),
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    }.build()
}

private val ClockIcon by lazy {
    strokeIcon("ThinClock") {
        moveTo(3.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 20.5f, y1 = 12f)
        arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3.5f, y1 = 12f)
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
}

private val MoonIcon by lazy {
    strokeIcon("ThinMoon") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.5f, y1 = 4f)
        arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, dx1 = 10.5f, dy1 = 10.5f)
        close()
    }
}

/** Rounded box with a down arrow — the reference's update glyph. */
private val DownloadBoxIcon by lazy {
    strokeIcon("ThinDownloadBox") {
        moveTo(7f, 3f)
        lineTo(17f, 3f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 6f)
        lineTo(20f, 18f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 21f)
        lineTo(7f, 21f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 18f)
        lineTo(4f, 6f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 3f)
        close()
        moveTo(12f, 7.5f); lineTo(12f, 14.5f)
        moveTo(8.8f, 11.3f); lineTo(12f, 14.5f); lineTo(15.2f, 11.3f)
    }
}

/** Thin chevron for trailing arrows. */
private val ThinChevron by lazy {
    strokeIcon("ThinChevron") {
        moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
    }
}

/** A desktop monitor on a stand — the remote-node card's device tile, same brush as the set. */
private val DesktopIcon by lazy {
    strokeIcon("ThinDesktop") {
        moveTo(5f, 4f)
        lineTo(19f, 4f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 6f)
        lineTo(21f, 14f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 16f)
        lineTo(5f, 16f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 14f)
        lineTo(3f, 6f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 4f)
        close()
        moveTo(12f, 16f); lineTo(12f, 20f)
        moveTo(8f, 20f); lineTo(16f, 20f)
    }
}

/**
 * Plain outlined cube for the model row — drawn to match the sheet's stroke icon set (clock,
 * moon, boxed arrow): 1.75 stroke, round joins, no scan-frame corners like ViewInAr's.
 */
private val CubeIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "OutlinedCube",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            // Outer hexagonal silhouette.
            moveTo(12f, 3f)
            lineTo(20f, 7.5f)
            lineTo(20f, 16.5f)
            lineTo(12f, 21f)
            lineTo(4f, 16.5f)
            lineTo(4f, 7.5f)
            close()
            // Inner edges toward the front-facing corner.
            moveTo(12f, 12f); lineTo(4.3f, 7.6f)
            moveTo(12f, 12f); lineTo(19.7f, 7.6f)
            moveTo(12f, 12f); lineTo(12f, 20.8f)
        }
    }.build()
}
