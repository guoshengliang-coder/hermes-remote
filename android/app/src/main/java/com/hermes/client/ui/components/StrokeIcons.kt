package com.hermes.client.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Shared thin-stroke (1.7dp) icon set — the same brush as the card page's hand-drawn glyphs,
// for list surfaces that need matching outline icons. Tinted by Icon like any vector.

// `internal`, not private: ProjectIcons.kt draws the project glyph set with the same brush.
internal fun strokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/** Hollow folder for project rows — the filled Material glyph read as a solid colour block. */
val FolderStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeFolder") {
        // Tab-top folder silhouette.
        moveTo(3.5f, 7f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5.5f, y1 = 5f)
        lineTo(9.3f, 5f)
        lineTo(11.3f, 7.3f)
        lineTo(18.5f, 7.3f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 9.3f)
        lineTo(20.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 19f)
        lineTo(5.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.5f, y1 = 17f)
        close()
    }
}

/** Archive box (lid + body + handle) for archived rows, matching the folder's weight. */
val ArchiveBoxIcon: ImageVector by lazy {
    strokeIcon("StrokeArchiveBox") {
        // Lid.
        moveTo(4f, 5f)
        lineTo(20f, 5f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 6f)
        lineTo(21f, 8f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 9f)
        lineTo(4f, 9f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 8f)
        lineTo(3f, 6f)
        arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 5f)
        close()
        // Body.
        moveTo(4.5f, 9f)
        lineTo(4.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6.5f, y1 = 19f)
        lineTo(17.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19.5f, y1 = 17f)
        lineTo(19.5f, 9f)
        // Handle.
        moveTo(10f, 12.5f)
        lineTo(14f, 12.5f)
    }
}

/** Folder with a house mark: the DEFAULT project (the gateway's launch directory). */
val HomeFolderStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeHomeFolder") {
        // Same folder silhouette as [FolderStrokeIcon].
        moveTo(3.5f, 7f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5.5f, y1 = 5f)
        lineTo(9.3f, 5f)
        lineTo(11.3f, 7.3f)
        lineTo(18.5f, 7.3f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 9.3f)
        lineTo(20.5f, 17f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 19f)
        lineTo(5.5f, 19f)
        arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.5f, y1 = 17f)
        close()
        // House: roof apex + walls, centred in the folder body.
        moveTo(9.5f, 16f)
        lineTo(9.5f, 12.8f)
        lineTo(12f, 10.7f)
        lineTo(14.5f, 12.8f)
        lineTo(14.5f, 16f)
        close()
    }
}

/** Git branch glyph (trunk with two nodes and a merge curve) for branch sublines. */
val BranchStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeBranch") {
        moveTo(6f, 3f)
        lineTo(6f, 15f)
        // Lower node.
        moveTo(6f, 15f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 6.01f, y1 = 21f)
        // Upper-right node.
        moveTo(18f, 9f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 18.01f, y1 = 3f)
        // Merge curve from the right node into the trunk.
        moveTo(18f, 9f)
        curveTo(18f, 13f, 14f, 13f, 9f, 15f)
    }
}

/** Thin trailing chevron for tappable entry rows (icon + title + chevron paradigm). */
val ThinChevronIcon: ImageVector by lazy {
    strokeIcon("StrokeThinChevron") {
        moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
    }
}

// Small-icon compensation (docs/DESIGN.md §4.1): the 1.7dp stroke is tuned for 24dp glyphs. An
// icon embedded at 16–18dp scales that stroke to ~1.2dp — thinner than the text beside it —
// so glyphs meant for pills and rows are drawn at 2.4 (≈1.8dp at 18dp), matching labelLarge.
/**
 * The two segment glyphs for the Chats / Bots switch (docs/DESIGN.md §4.2, §5.2).
 *
 * Both use [smallStrokeIcon]: they render at 18dp inside the segment pill, where the 1.7dp brush
 * would thin out to ~1.2dp and read lighter than the label beside them (§4.1 small-size rule).
 *
 * Drawn as a pair on purpose — same 14x11 body box, same 2.5-unit corner radius, same optical
 * centre — so that the selected and unselected segments carry equal visual weight. Hollow, like
 * every glyph in this file: a filled bubble already means "a chat message" inside the transcript.
 */
val ChatBubbleStrokeIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeChatBubble") {
        // Rounded speech bubble, tail dropping from the lower left.
        moveTo(5f, 6.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
        horizontalLineTo(16.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
        verticalLineTo(13.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
        horizontalLineTo(10f)
        lineTo(6.5f, 19.5f)
        verticalLineTo(16f)
        horizontalLineTo(7.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
        close()
    }
}

/** Bot head: the same rounded body box as the bubble, plus an antenna and two eyes. */
val BotStrokeIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeBot") {
        // Antenna.
        moveTo(12f, 3f)
        verticalLineTo(6f)
        // Head.
        moveTo(7.5f, 6f)
        horizontalLineTo(16.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
        verticalLineTo(15.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
        horizontalLineTo(7.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
        verticalLineTo(8.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
        close()
        // Eyes — short strokes rather than dots, so they survive the 18dp downscale.
        moveTo(9.5f, 11f)
        verticalLineTo(12.5f)
        moveTo(14.5f, 11f)
        verticalLineTo(12.5f)
    }
}

private fun smallStrokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

/**
 * Plain up arrow: "back to the start of this turn" (turn-jump pill).
 *
 * It used to carry a line across the top — an "arrow to top" glyph. Stitch 基线-聊天页/滑动引导胶囊
 * draws a bare arrow, and the pill is this icon's only consumer, so the line came off here rather
 * than a second glyph being added beside it (docs/DESIGN.md §4.2, 2026-09-12).
 */
val ArrowToTopIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeArrowUp") {
        moveTo(12f, 19.5f)
        lineTo(12f, 4.5f)
        moveTo(6.75f, 9.75f)
        lineTo(12f, 4.5f)
        lineTo(17.25f, 9.75f)
    }
}

/**
 * Three plain bars: the prompt list (pill segment and top-bar menu).
 *
 * The leading dots came off with the same 2026-09-12 pull. Two entry points to one feature, one
 * glyph — changing it here changes both, which is the point.
 */
val PromptListIcon: ImageVector by lazy {
    smallStrokeIcon("StrokePromptList") {
        for (y in listOf(6f, 12f, 18f)) {
            moveTo(4.5f, y)
            lineTo(19.5f, y)
        }
    }
}

/** Two stacked chevrons: "back to the latest turn" (prompt sheet header). */
val ChevronsDownIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeChevronsDown") {
        moveTo(5f, 8f)
        lineTo(12f, 15f)
        lineTo(19f, 8f)
        moveTo(5f, 14f)
        lineTo(12f, 21f)
        lineTo(19f, 14f)
    }
}

/** Pencil — the "edit this identity" row action on the profile picker. */
val PencilStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokePencil") {
        moveTo(4f, 20f); lineTo(8.2f, 20f); lineTo(19f, 9.2f)
        arcTo(1.6f, 1.6f, 0f, false, false, 19f, 6.9f)
        lineTo(17.1f, 5f)
        arcTo(1.6f, 1.6f, 0f, false, false, 14.8f, 5f)
        lineTo(4f, 15.8f); close()
        moveTo(13.5f, 6.3f); lineTo(17.7f, 10.5f)
    }
}

/** Camera — the "change photo" badge on the identity settings avatar. */
val CameraStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeCamera") {
        moveTo(4f, 8.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 5.5f, 7f)
        lineTo(8f, 7f); lineTo(9.4f, 5f); lineTo(14.6f, 5f); lineTo(16f, 7f); lineTo(18.5f, 7f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20f, 8.5f)
        lineTo(20f, 17.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 19f)
        lineTo(5.5f, 19f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4f, 17.5f)
        close()
        moveTo(15.2f, 13f)
        arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 13f)
        arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 13f)
    }
}

/**
 * External-link glyph for inline markdown links: a frame with an arrow leaving its top-right
 * corner. Rendered next to 17sp body text at ~14dp, so it uses the small-stroke compensation
 * (DESIGN.md §4.1) — 1.7 would thin out to ~1.0 beside the body weight.
 */
val ExternalLinkIcon: ImageVector by lazy {
    smallStrokeIcon("StrokeExternalLink") {
        // Frame, open at the top-right where the arrow leaves.
        moveTo(13f, 4.6f)
        lineTo(6.4f, 4.6f)
        arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 4.6f, y1 = 6.4f)
        lineTo(4.6f, 17.6f)
        arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6.4f, y1 = 19.4f)
        lineTo(17.6f, 19.4f)
        arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19.4f, y1 = 17.6f)
        lineTo(19.4f, 11f)
        // Arrow out of the corner.
        moveTo(11.4f, 12.6f)
        lineTo(19.4f, 4.6f)
        moveTo(13.8f, 4.6f)
        lineTo(19.4f, 4.6f)
        lineTo(19.4f, 10.2f)
    }
}

/**
 * Rounded speech bubble with a tail — the feedback entry on the card page.
 *
 * Same construction as that row's neighbours (a rounded rectangle plus one small shape, exactly
 * like the update glyph's box-and-arrow), so the five icons read as one set at 22dp. The bubble is
 * left hollow: a chat app already spends filled bubbles on messages, and an outline at this weight
 * reads as "say something" rather than "a conversation".
 */
val FeedbackBubbleIcon: ImageVector by lazy {
    strokeIcon("StrokeFeedbackBubble") {
        moveTo(6.5f, 4.5f)
        lineTo(17.5f, 4.5f)
        arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.1f, y1 = 7.1f)
        lineTo(20.1f, 14.4f)
        arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.5f, y1 = 17f)
        lineTo(12.4f, 17f)
        lineTo(8.2f, 20.2f)
        lineTo(8.2f, 17f)
        lineTo(6.5f, 17f)
        arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.9f, y1 = 14.4f)
        lineTo(3.9f, 7.1f)
        arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6.5f, y1 = 4.5f)
        close()
    }
}

// ── Messaging-channel category glyphs ────────────────────────────────────────────────────────
// Hermes knows 33 platforms. Their brand marks are filled, multi-colour and trademarked, so the
// list identifies a channel by NAME and uses these to say what KIND of channel it is. Same stroke
// system as everything else here (docs/DESIGN.md §4.1); no Material glyph is fit for purpose.

private fun PathBuilder.roundedRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    arcTo(rad, rad, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r, y1 = t + rad)
    lineTo(r, b - rad)
    arcTo(rad, rad, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r - rad, y1 = b)
    lineTo(l + rad, b)
    arcTo(rad, rad, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l, y1 = b - rad)
    lineTo(l, t + rad)
    arcTo(rad, rad, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = l + rad, y1 = t)
    close()
}

/** Instant messaging: DingTalk, Slack, Telegram, Feishu, WeCom … */
val ChatChannelIcon: ImageVector by lazy {
    strokeIcon("StrokeChatChannel") {
        roundedRect(3.5f, 4.5f, 20.5f, 16f, 3f)
        // Tail, drawn as its own stroke so the bubble outline stays unbroken.
        moveTo(8.5f, 16f)
        lineTo(7.5f, 20f)
        lineTo(12.5f, 16f)
    }
}

/** Mail channels. */
val MailChannelIcon: ImageVector by lazy {
    strokeIcon("StrokeMailChannel") {
        roundedRect(3f, 5f, 21f, 19f, 2.5f)
        moveTo(3.8f, 6.6f)
        lineTo(12f, 12.6f)
        lineTo(20.2f, 6.6f)
    }
}

/** SMS and anything else that arrives on a phone number. */
val SmsChannelIcon: ImageVector by lazy {
    strokeIcon("StrokeSmsChannel") {
        roundedRect(6.5f, 2.5f, 17.5f, 21.5f, 2.5f)
        moveTo(10.5f, 18.6f)
        lineTo(13.5f, 18.6f)
    }
}

/** Push-only channels such as ntfy: they notify, they do not converse. */
val PushChannelIcon: ImageVector by lazy {
    strokeIcon("StrokePushChannel") {
        moveTo(6f, 17f)
        lineTo(18f, 17f)
        lineTo(16.4f, 14.6f)
        lineTo(16.4f, 10.5f)
        arcTo(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7.6f, y1 = 10.5f)
        lineTo(7.6f, 14.6f)
        close()
        moveTo(10.4f, 19.6f)
        arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 13.6f, y1 = 19.6f)
    }
}

/** Programmatic surfaces: webhooks, the API server, agent-to-agent. */
val ApiChannelIcon: ImageVector by lazy {
    strokeIcon("StrokeApiChannel") {
        moveTo(9f, 8f)
        lineTo(5f, 12f)
        lineTo(9f, 16f)
        moveTo(15f, 8f)
        lineTo(19f, 12f)
        lineTo(15f, 16f)
    }
}

// ── Theme options (docs/DESIGN.md §5.1 主题弹层) ────────────────────────────────────────────────
//
// The theme sheet's mock draws these as 2px feather glyphs; §4 keeps the repo's 1.7dp brush, the
// same ruling the card page took. They live here rather than in CardPage.kt because two screens
// need them now — the sheet and 设置→外观 — which is exactly §4.2's "new icons go in the shared
// file first".

/**
 * A desktop monitor on a stand — the card page's remote-node tile, and 「跟随系统」 in the theme
 * sheet.
 *
 * Moved here from CardPage.kt unchanged when the theme sheet needed the same glyph. Writing a
 * second monitor for the sheet is exactly the duplication §4.2 asks new icons to avoid: two
 * hand-drawn monitors one screen apart would have drifted the first time either was touched.
 */
val DesktopStrokeIcon: ImageVector by lazy {
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

/** A rayed sun — 「温润浅色」. */
val SunStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeSun") {
        moveTo(16.6f, 12f)
        arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7.4f, y1 = 12f)
        arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.6f, y1 = 12f)
        close()
        // Eight rays, radius 7.4 → 10.0, so the round caps stay inside the 24 viewport.
        moveTo(12f, 4.6f); lineTo(12f, 2f)
        moveTo(12f, 19.4f); lineTo(12f, 22f)
        moveTo(4.6f, 12f); lineTo(2f, 12f)
        moveTo(19.4f, 12f); lineTo(22f, 12f)
        moveTo(6.77f, 6.77f); lineTo(4.93f, 4.93f)
        moveTo(17.23f, 6.77f); lineTo(19.07f, 4.93f)
        moveTo(6.77f, 17.23f); lineTo(4.93f, 19.07f)
        moveTo(17.23f, 17.23f); lineTo(19.07f, 19.07f)
    }
}

/**
 * A crescent — 「黑曜石深色」, and the card page's 主题 row.
 *
 * Moved here from CardPage.kt unchanged when the theme sheet became its second consumer.
 */
val MoonStrokeIcon: ImageVector by lazy {
    strokeIcon("StrokeMoon") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.5f, y1 = 4f)
        arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, dx1 = 10.5f, dy1 = 10.5f)
        close()
    }
}
