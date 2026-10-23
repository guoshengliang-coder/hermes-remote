package com.hermes.client.ui.cron

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.network.CronJobDto
import com.hermes.client.data.network.CronRunDto
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the scheduled-jobs screens (docs/DESIGN.md §5.18, Stitch 基线-定时任务列表 /
 * 任务详情, both themes).
 *
 * Named after the lock-file keys, so `docs/design/stitch/<key>.roborazzi.png` and
 * `app/screenshots/<key>.png` are the same size and overlay directly (README in that directory).
 * The fixture below mirrors the mock's content for the same reason.
 *
 * The delete confirmation is NOT captured: an `AlertDialog` renders in its own window where
 * `onRoot()` cannot reach it, the same limitation `ModelSelectorScreenshotTest` documents.
 *
 * Goldens live under app/screenshots/. Not part of the release gate — pixel noise must never
 * block a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CronScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    // A fixed "now" so 逾期 is a property of the fixture, not of the day the test runs.
    private val nowMs = 1_789_000_000_000L      // 2026-09-12T05:46:40Z
    private val soon = "2026-09-12T18:15:00Z"   // comfortably in the future of [nowMs]
    private val past = "2026-09-11T18:32:00Z"

    private fun job(
        id: String,
        name: String,
        display: String,
        deliver: String? = null,
        lastStatus: String? = "ok",
        paused: Boolean = false,
        enabled: Boolean = true,
        lastError: String? = null,
        prompt: String? = null,
    ) = CronJobDto(
        id = id,
        name = name,
        scheduleDisplay = display,
        enabled = enabled,
        pausedAt = if (paused) past else null,
        nextRunAt = soon,
        lastRunAt = past,
        lastStatus = lastStatus,
        lastError = lastError,
        deliver = deliver,
        profile = "default",
        prompt = prompt,
    )

    private val jobs = listOf(
        job("j1", "钉钉连接健康检测（自动重连）", "每 2 分钟", deliver = "origin", lastStatus = "error",
            lastError = "connect ECONNREFUSED 127.0.0.1:7001"),
        job("j2", "小迈公司经营日报 | 钉钉 AI Card", "每天 18:15"),
        job("j3", "芯芯 | 每日钉钉行程与待办 AI Card", "每天 08:00", deliver = "origin"),
        job("j4", "芯芯 | 每日钉钉邮箱总结 AI Card", "每天 08:00"),
        job("j5", "芯芯 | 钉钉日志每日检测与周报汇总", "每天 08:00"),
        job("j6", "小迈公司市场推广日报 | 钉钉 AI Card", "每天 18:15"),
        job("j7", "周深长沙站开票监控", "每 30 分钟"),
        job("j8", "网关重启丢失消息监控", "每 2 分钟", paused = true, lastStatus = null),
    )

    private val detailJob = job(
        "j2", "小迈公司经营日报", "每天 18:15",
        prompt = "你是小迈网络科技有限公司 CEO 的经营日报生产任务。严格顺序：日期与幂等 → BI 查询/稳定分页 " +
            "→ 源明细对账 → 报告范围过滤 → 数据完整性门禁（含国内+海外） → 经营分析 → 同轮 JSON → 同轮 HTML " +
            "→ 验收 → 钉钉上传 → 钉钉 AI Card 发送。所有 Python 脚本运行一律使用 terminal 工具，" +
            "禁止使用 execute_code。",
    )

    private val runs = listOf(
        CronRunDto(id = "r1", startedAt = 1_788_900_900.0, endedAt = 1_788_900_942.0, endReason = "cron_complete"),
        CronRunDto(id = "r2", startedAt = 1_788_814_860.0, endedAt = 1_788_814_898.0, endReason = "cron_complete"),
        CronRunDto(id = "r3", startedAt = 1_788_728_100.0, endedAt = 1_788_728_145.0, endReason = "cron_complete"),
        CronRunDto(id = "r4", startedAt = 1_788_641_700.0, endedAt = 1_788_641_740.0, endReason = "cron_complete"),
    )

    private fun snap(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        language: AppLanguage = AppLanguage.ZH,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            HermesTheme(darkTheme = darkTheme) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                ) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = options)
    }

    private fun list(state: CronUiState) = @Composable {
        CronScreenContent(state = state, nowMs = nowMs)
    }

    private val listState = CronUiState(jobs = jobs, profile = "default", loading = false)

    @Test fun cronListLight() = snap("cron.list.default.light", content = list(listState))

    @Test fun cronListDark() =
        snap("cron.list.default.dark", darkTheme = true, content = list(listState))

    /** 360dp and fontScale 1.3 together: the narrow-screen truncation §5.18 promises. */
    @Test fun cronListLargeType() =
        snap("cron.list.default.light-fs13", fontScale = 1.3f, content = list(listState))

    /** A 保留项 the mocks do not draw — captured so it cannot rot unnoticed. */
    @Test fun cronListEmpty() = snap(
        "cron.list.default.empty",
        content = list(CronUiState(jobs = emptyList(), profile = "default", loading = false)),
    )

    @Test fun cronListError() = snap(
        "cron.list.default.error",
        content = list(
            CronUiState(
                jobs = emptyList(), profile = "default", loading = false,
                error = AppError(AppErrorCode.RPC_FAILED, retryable = true),
            ),
        ),
    )

    private fun detail(state: CronDetailUiState) = @Composable {
        CronDetailContent(state = state)
    }

    private val detailState = CronDetailUiState(job = detailJob, runs = runs, loading = false)

    @Test fun cronDetailLight() = snap("cron.detail.default.light", content = detail(detailState))

    @Test fun cronDetailDark() =
        snap("cron.detail.default.dark", darkTheme = true, content = detail(detailState))

    /** The failed job, so the error block and the red pill are in a golden too. */
    @Test fun cronDetailFailed() = snap(
        "cron.detail.default.failed",
        content = detail(
            CronDetailUiState(
                job = jobs.first(), runs = emptyList(), loading = false,
            ),
        ),
    )
}

/** 360dp narrow screen — the other half of the fontScale 1.3 promise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-420dpi")
class CronNarrowScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    @Test
    fun cronListNarrowLargeType() {
        val nowMs = 1_789_000_000_000L
        val jobs = listOf(
            CronJobDto(
                id = "j1", name = "钉钉连接健康检测（自动重连）", scheduleDisplay = "每 2 分钟",
                nextRunAt = "2026-09-12T18:15:00Z", lastRunAt = "2026-09-11T18:32:00Z",
                lastStatus = "error", deliver = "origin", profile = "default",
            ),
            CronJobDto(
                id = "j2", name = "小迈公司经营日报 | 钉钉 AI Card", scheduleDisplay = "每天 18:15",
                nextRunAt = "2026-09-12T18:15:00Z", lastRunAt = "2026-09-11T18:32:00Z",
                lastStatus = "ok", profile = "default",
            ),
        )
        compose.setContent {
            HermesTheme(darkTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.EN,
                    LocalDensity provides Density(density.density, 1.3f),
                ) {
                    CronScreenContent(
                        state = CronUiState(jobs = jobs, profile = "default", loading = false),
                        nowMs = nowMs,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "screenshots/cron.list.default.en-fs13-360.png",
            roborazziOptions = options,
        )
    }
}
