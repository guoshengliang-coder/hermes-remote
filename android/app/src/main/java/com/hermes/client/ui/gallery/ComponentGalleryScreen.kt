package com.hermes.client.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.TodoItem
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import com.hermes.client.ui.chat.ChatTableCard
import com.hermes.client.ui.chat.CodeWithCopy
import com.hermes.client.ui.chat.DiffBlock
import com.hermes.client.ui.chat.RunningStatusLine
import com.hermes.client.ui.chat.SemanticToolCard
import com.hermes.client.ui.chat.StyledMarkdownTableSample
import com.hermes.client.ui.chat.TodoCard
import com.hermes.client.ui.chat.ToolTimelineCard
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText
import com.hermes.client.ui.localization.resolve

/**
 * Component gallery: every chat component rendered from FIXED FAKE DATA, one scroll away.
 * Exists so visual verification does not require driving the full stack (session -> message ->
 * stream) — open this screen and eyeball each state, on any device. Fake data only; nothing
 * here reads or writes real sessions.
 */
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("组件展廊（调试）", "Component gallery (debug)"),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(gallerySections, key = { it.first.zh }) { (title, content) ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(title.resolve(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
                    content()
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun GalleryTableCard(raw: String) {
    val fullscreenState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var fullscreen = fullscreenState.value
    ChatTableCard(raw, onOpenFullscreen = { fullscreenState.value = true }) { StyledMarkdownTableSample(raw) }
    if (fullscreenState.value) {
        com.hermes.client.ui.chat.TableFullscreenDialog(raw) { fullscreenState.value = false }
    }
}

private fun streamingMsg(text: String = "", thinking: String = "", tools: List<ToolCall> = emptyList()) =
    ChatMessage(
        id = "g", role = Role.ASSISTANT, text = text, thinking = thinking, tools = tools,
        isStreaming = true, timestamp = System.currentTimeMillis() - 84_000,
    )

@Composable
private fun sampleTableNarrow() = l10n(
    """| 项目 | 期望值 | 实际值 |
|------|--------|--------|
| 证书深度 | 4 | 2 |
| keepalive | 16 | 16 |
| 读超时 | 75s | 75s |""",
    """| Item | Expected | Actual |
|------|----------|--------|
| Certificate depth | 4 | 2 |
| keepalive | 16 | 16 |
| Read timeout | 75s | 75s |""",
)

@Composable
private fun sampleTableWide() = l10n(
    """| 排名 | 进程 | 占全部 CPU 时间 | 内存占用 | 状态 |
|------|------|-----------------|----------|------|
| 1 | Android 模拟器 (qemu-system-aarch64) | 约 54% | 9.0 GB | 🔴 |
| 2 | WorkBuddy 渲染进程 | 约 8% | 1.0 GB | 正常 |
| 5-10 | Claude、ChatGPT、WPS、Typeless、Hermes 等各占 1-3% | 合计约 20% | 各 0.3-1 GB | 正常 |""",
    """| Rank | Process | Total CPU time | Memory | Status |
|------|---------|----------------|--------|--------|
| 1 | Android emulator (qemu-system-aarch64) | about 54% | 9.0 GB | 🔴 |
| 2 | WorkBuddy renderer | about 8% | 1.0 GB | Normal |
| 5-10 | Claude, ChatGPT, WPS, Typeless, and Hermes at 1-3% each | about 20% total | 0.3-1 GB each | Normal |""",
)

private fun galleryTitle(zh: String, en: String): LocalizedText = localizedText(zh, en)

private val gallerySections: List<Pair<LocalizedText, @Composable () -> Unit>> = listOf(
    galleryTitle("运行状态行 · 生成中", "Running status · generating") to {
        RunningStatusLine(streamingMsg(text = l10n("已经有一段输出…", "There is already some output…")))
    },
    galleryTitle("运行状态行 · 思考预览", "Running status · reasoning preview") to {
        RunningStatusLine(streamingMsg(thinking = l10n(
            "先检查 nginx 配置，然后逐一验证每个 upstream 的证书链与握手参数",
            "Check the nginx configuration, then verify each upstream certificate chain and handshake setting.",
        )))
    },
    galleryTitle("运行状态行 · 工具运行", "Running status · active tool") to {
        RunningStatusLine(
            streamingMsg(
                text = "some",
                tools = listOf(ToolCall("t", "Bash", ToolStatus.RUNNING, command = "npm run test -- --watchAll=false")),
            ),
        )
    },
    galleryTitle("运行状态行 · 尚无内容", "Running status · nothing yet") to {
        RunningStatusLine(streamingMsg())
    },
    galleryTitle("品牌加载标记 32 / 20 / 14dp", "Brand loading mark 32 / 20 / 14dp") to {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.hermes.client.ui.components.HermesMark(size = 32.dp)
            Spacer(Modifier.width(20.dp))
            com.hermes.client.ui.components.HermesMark(size = 20.dp)
            Spacer(Modifier.width(20.dp))
            com.hermes.client.ui.components.HermesMark(size = 14.dp)
        }
    },
    galleryTitle("品牌加载标记 · 关闭动画", "Brand loading mark · animations off") to {
        CompositionLocalProvider(com.hermes.client.ui.components.LocalReduceMotion provides true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.hermes.client.ui.components.HermesMark(size = 32.dp)
                Spacer(Modifier.width(20.dp))
                com.hermes.client.ui.components.HermesMark(size = 14.dp)
            }
        }
    },
    galleryTitle("列表骨架", "List skeleton") to { com.hermes.client.ui.components.SkeletonRows() },
    galleryTitle("顶部刷新细线", "Top refresh line") to { com.hermes.client.ui.components.TopProgressLine() },
    galleryTitle("决策卡 · 单选", "Clarify · single") to {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "g1",
                listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "要用哪种发布方式？", listOf("滚动发布 (Recommended)", "蓝绿切换", "全量停机重发"))),
            ),
            onAnswer = {}, onSkip = {},
        )
    },
    galleryTitle("决策卡 · 多选", "Clarify · multi") to {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "g2",
                listOf(com.hermes.client.ui.chat.ClarifyQuestion("", "备份哪些内容？", listOf("数据库全量 (Recommended)", "上传的用户文件", "环境配置"), multiSelect = true)),
            ),
            onAnswer = {}, onSkip = {},
        )
    },
    galleryTitle("决策卡 · 批量 2/3", "Clarify · batch 2/3") to {
        com.hermes.client.ui.chat.ClarifySheetContent(
            com.hermes.client.ui.chat.ClarifyRequest(
                "g3",
                listOf(
                    com.hermes.client.ui.chat.ClarifyQuestion("q0", "数据库选型？", listOf("PostgreSQL")),
                    com.hermes.client.ui.chat.ClarifyQuestion("q1", "对象存储用哪个？", listOf("本地 MinIO (Recommended)", "阿里云 OSS")),
                    com.hermes.client.ui.chat.ClarifyQuestion("q2", "部署区域备注"),
                ),
                lockedAnswers = mapOf("q0" to "PostgreSQL"),
            ),
            onAnswer = {}, onSkip = {},
        )
    },
    galleryTitle("工具卡 · 成功", "Tool card · success") to {
        SemanticToolCard(ToolCall("a", "Bash", ToolStatus.DONE, output = "ok", command = "nginx -t", exitCode = 0, durationMs = 412))
    },
    galleryTitle("工具卡 · 失败", "Tool card · failure") to {
        SemanticToolCard(
            ToolCall(
                "b", "Bash", ToolStatus.DONE, command = "systemctl restart hermes-gateway",
                exitCode = 1, durationMs = 1200, output = "Job failed. See journalctl -xe\nport 8444 already in use",
            ),
        )
    },
    galleryTitle("工具时间线（≥3 连续）", "Tool timeline (≥3 consecutive)") to {
        ToolTimelineCard(
            listOf(
                ToolCall("c1", "Read", ToolStatus.DONE, command = "cat /etc/nginx/conf.d/edge.conf", durationMs = 95, exitCode = 0),
                ToolCall("c2", "Bash", ToolStatus.DONE, command = "curl -sS https://mrlgs.net/health", durationMs = 640, exitCode = 0),
                ToolCall("c3", "Bash", ToolStatus.RUNNING, command = "systemctl restart hermes-gateway"),
            ),
        )
    },
    galleryTitle("任务清单卡", "Task-list card") to {
        TodoCard(
            ToolCall(
                "d", "TodoWrite", ToolStatus.DONE,
                todos = listOf(
                    TodoItem(l10n("检查 nginx 与网关服务状态", "Check nginx and gateway status"), "completed"),
                    TodoItem(l10n("定位证书链校验失败原因", "Find the certificate-chain failure"), "completed"),
                    TodoItem(l10n("更新配置并重载 nginx", "Update configuration and reload nginx"), "in_progress"),
                    TodoItem(l10n("验证公网健康检查恢复", "Verify the public health check"), "pending"),
                ),
            ),
        )
    },
    galleryTitle("表格卡 · 窄（3 列一屏）", "Table card · narrow (3 columns)") to { GalleryTableCard(sampleTableNarrow()) },
    galleryTitle("表格卡 · 宽（5 列横滚）", "Table card · wide (5 columns)") to { GalleryTableCard(sampleTableWide()) },
    galleryTitle("代码块 · 语言头部栏", "Code block · language header") to {
        CodeWithCopy(
            code = "val atBottom by remember(listState) {\n    derivedStateOf { listState.firstVisibleItemIndex == 0 }\n}",
            language = "kotlin",
            style = MaterialTheme.typography.bodySmall,
        )
    },
    galleryTitle("Diff 块", "Diff block") to {
        DiffBlock("--- a/deploy/edge.conf\n+++ b/deploy/edge.conf\n@@ -31,7 +31,7 @@\n   proxy_ssl_server_name on;\n-  proxy_ssl_verify_depth 2;\n+  proxy_ssl_verify_depth 4;\n   proxy_ssl_verify on;")
    },
)
