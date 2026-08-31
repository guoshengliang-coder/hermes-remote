package com.hermes.client.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.hermes.client.ui.chat.TypingIndicator

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
                title = "组件展廊 (debug)",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(gallerySections, key = { it.first }) { (title, content) ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

private const val SAMPLE_TABLE_NARROW = """| 项目 | 期望值 | 实际值 |
|------|--------|--------|
| 证书深度 | 4 | 2 |
| keepalive | 16 | 16 |
| 读超时 | 75s | 75s |"""

private const val SAMPLE_TABLE_WIDE = """| 排名 | 进程 | 占全部 CPU 时间 | 内存占用 | 状态 |
|------|------|-----------------|----------|------|
| 1 | Android 模拟器 (qemu-system-aarch64) | 约 54% | 9.0 GB | 🔴 |
| 2 | WorkBuddy 渲染进程 | 约 8% | 1.0 GB | 正常 |
| 5-10 | Claude、ChatGPT、WPS、Typeless、Hermes 等各占 1-3% | 合计约 20% | 各 0.3-1 GB | 正常 |"""

private val gallerySections: List<Pair<String, @Composable () -> Unit>> = listOf(
    "运行状态行 · 生成中" to { RunningStatusLine(streamingMsg(text = "已经有一段输出…")) },
    "运行状态行 · 思考预览" to {
        RunningStatusLine(streamingMsg(thinking = "先检查 nginx 配置，然后逐一验证每个 upstream 的证书链与握手参数"))
    },
    "运行状态行 · 工具运行" to {
        RunningStatusLine(
            streamingMsg(
                text = "some",
                tools = listOf(ToolCall("t", "Bash", ToolStatus.RUNNING, command = "npm run test -- --watchAll=false")),
            ),
        )
    },
    "打字指示器" to { TypingIndicator() },
    "工具卡 · 成功" to {
        SemanticToolCard(ToolCall("a", "Bash", ToolStatus.DONE, output = "ok", command = "nginx -t", exitCode = 0, durationMs = 412))
    },
    "工具卡 · 失败" to {
        SemanticToolCard(
            ToolCall(
                "b", "Bash", ToolStatus.DONE, command = "systemctl restart hermes-gateway",
                exitCode = 1, durationMs = 1200, output = "Job failed. See journalctl -xe\nport 8444 already in use",
            ),
        )
    },
    "工具时间线（≥3 连续）" to {
        ToolTimelineCard(
            listOf(
                ToolCall("c1", "Read", ToolStatus.DONE, command = "cat /etc/nginx/conf.d/edge.conf", durationMs = 95, exitCode = 0),
                ToolCall("c2", "Bash", ToolStatus.DONE, command = "curl -sS https://mrlgs.net/health", durationMs = 640, exitCode = 0),
                ToolCall("c3", "Bash", ToolStatus.RUNNING, command = "systemctl restart hermes-gateway"),
            ),
        )
    },
    "任务清单卡" to {
        TodoCard(
            ToolCall(
                "d", "TodoWrite", ToolStatus.DONE,
                todos = listOf(
                    TodoItem("检查 nginx 与网关服务状态", "completed"),
                    TodoItem("定位证书链校验失败原因", "completed"),
                    TodoItem("更新配置并重载 nginx", "in_progress"),
                    TodoItem("验证公网健康检查恢复", "pending"),
                ),
            ),
        )
    },
    "表格卡 · 窄（3 列一屏）" to { GalleryTableCard(SAMPLE_TABLE_NARROW) },
    "表格卡 · 宽（5 列横滚）" to { GalleryTableCard(SAMPLE_TABLE_WIDE) },
    "代码块 · 语言头部栏" to {
        CodeWithCopy(
            code = "val atBottom by remember(listState) {\n    derivedStateOf { listState.firstVisibleItemIndex == 0 }\n}",
            language = "kotlin",
            style = MaterialTheme.typography.bodySmall,
        )
    },
    "Diff 块" to {
        DiffBlock("--- a/deploy/edge.conf\n+++ b/deploy/edge.conf\n@@ -31,7 +31,7 @@\n   proxy_ssl_server_name on;\n-  proxy_ssl_verify_depth 2;\n+  proxy_ssl_verify_depth 4;\n   proxy_ssl_verify on;")
    },
)
