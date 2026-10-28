package com.hermes.client.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.sync.*
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import com.hermes.client.ui.localization.resolve

/** Compact entry in the existing picker. Large queues live in a bounded, paged dialog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountModelSyncPanel(sync: AccountModelPreferences?, loadLegacy: suspend () -> LegacyModelImport, provider: String? = null, model: String? = null, reasoningOnly: Boolean = false) {
    if (sync == null) return
    val state by sync.state.collectAsStateWithLifecycle()
    if (!state.useCloud) return
    val pending = state.pending.filter { !reasoningOnly || it.operation.field == "reasoningPreset" }
    val scope = rememberCoroutineScope()
    val language = LocalAppLanguage.current
    var legacy by remember { mutableStateOf<Pair<String, LegacyModelImport>?>(null) }
    var importError by remember { mutableStateOf<AppError?>(null) }
    var review by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.ownerKey) { legacy = null; importError = null; review = false; page = 0 }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(if (!state.enabled) l10n("账号模型同步已暂停", "Account model sync paused")
            else if (!state.ready) l10n("正在读取账号偏好…", "Loading account preferences…")
            else if(reasoningOnly) l10n("账号推理偏好 · 待处理 ${pending.size}", "Account reasoning preferences · ${pending.size} pending")
            else l10n("账号模型偏好 · 待处理 ${pending.size}", "Account model preferences · ${pending.size} pending"),
            style = MaterialTheme.typography.bodySmall)
        (importError ?: state.error)?.let { Text(it.localizedMessage(language), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        if (provider != null && model != null && state.projected().any { it.provider == provider && it.model == model && it.reasoningPreset != null }) {
            TextButton(onClick = { scope.launch { sync.setReasoning(provider, model, null) } }, enabled = !state.blocked(provider, model, "reasoningPreset")) {
                Text(l10n("清除记住的推理偏好", "Clear remembered reasoning preference"))
            }
        }
        FlowRow {
            TextButton(onClick = { scope.launch { sync.refresh() } }) { Text(l10n("刷新", "Refresh")) }
            TextButton(onClick = {
                val expected = state.ownerKey
                scope.launch {
                    try {
                        val data = loadLegacy().let { old -> if (reasoningOnly) old.copy(candidates = old.candidates.filter { it.reasoning != null }.map { it.copy(favorite = false) }) else old }
                        if (sync.state.value.ownerKey == expected) { legacy = expected to data; importError = null }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (sync.state.value.ownerKey == expected) importError = AppError(AppErrorCode.MODEL_SYNC_STORAGE, true) }
                }
            }, enabled = state.enabled && state.ready) {
                Text(l10n("导入本机偏好", "Import local preferences"))
            }
            if (pending.isNotEmpty()) TextButton(onClick = { review = true }) { Text(l10n("查看队列", "Review queue")) }
        }
    }
    legacy?.let { (confirmedOwner, data) ->
        AlertDialog(onDismissRequest = { legacy = null }, title = { Text(l10n("确认导入", "Confirm import")) },
            text = { Text(l10n("导入到账号 ${state.accountLabel}：${data.candidates.size} 个模型组合，${data.unresolved} 项无法识别。保留本机原数据；已有云端修改将进入冲突确认，不自动覆盖。",
                "Import into ${state.accountLabel}: ${data.candidates.size} model combinations, ${data.unresolved} unresolved. Local originals stay. Existing cloud changes require conflict review.")) },
            confirmButton = { TextButton(onClick = { legacy = null; scope.launch { sync.importLegacy(data, "android-local-models-v1", confirmedOwner) } }) { Text(l10n("确认导入", "Import")) } },
            dismissButton = { TextButton(onClick = { legacy = null }) { Text(l10n("取消", "Cancel")) } })
    }
    if (review) {
        val pages = maxOf(1, (pending.size + 4) / 5)
        val currentPage = page.coerceIn(0, pages - 1)
        AlertDialog(onDismissRequest = { review = false }, title = { Text(l10n("模型偏好队列", "Model preference queue")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).then(Modifier)) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(pending.drop(currentPage * 5).take(5).size) { index ->
                            val item = pending[currentPage * 5 + index]
                            val op = item.operation
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("${op.provider} / ${op.model}", style = MaterialTheme.typography.bodyMedium) // l10n-allow: opaque provider/model identifiers
                                Text(if (op.field == "favorite") l10n("收藏", "Favorite") else l10n("推理偏好", "Reasoning preference"), style = MaterialTheme.typography.bodySmall)
                                val localChoice = if (op.field == "favorite") {
                                    if (op.value == JsonPrimitive(true)) l10n("收藏", "Favorite") else l10n("取消收藏", "Unfavorite")
                                } else if (op.value == JsonNull) l10n("未记住", "Not saved")
                                else reasoningLabel(op.value.jsonPrimitive.content)?.resolve(language).orEmpty()
                                val cloudRow = state.rows.find { it.provider == op.provider && it.model == op.model }
                                val cloudChoice = if (op.field == "favorite") {
                                    if (cloudRow?.favorite == true) l10n("收藏", "Favorite") else l10n("取消收藏", "Unfavorite")
                                } else cloudRow?.reasoningPreset?.let { reasoningLabel(it)?.resolve(language) } ?: l10n("未记住", "Not saved")
                                Text(l10n("本机：$localChoice；云端：$cloudChoice", "Local: $localChoice; cloud: $cloudChoice"), style = MaterialTheme.typography.bodySmall)
                                val code = item.errorCode?.let(AppErrorCode::fromValue)
                                Text(if (code != null) AppError(code, false).localizedMessage(language) else l10n("等待同步", "Waiting to sync"), style = MaterialTheme.typography.bodySmall)
                                if (item.errorCode != null) Row {
                                    TextButton(onClick = { scope.launch { sync.discard(op.opId) } }) { Text(l10n("保留云端", "Keep cloud")) }
                                    TextButton(onClick = { scope.launch { sync.reapply(op.opId) } }, enabled = state.enabled && state.ready) { Text(l10n("应用本机", "Apply local")) }
                                }
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0) { Text(l10n("上一页", "Previous")) }
                        Text("${currentPage + 1}/$pages", Modifier.padding(top = 12.dp)) // l10n-allow: numeric page counter
                        TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage + 1 < pages) { Text(l10n("下一页", "Next")) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { review = false }) { Text(l10n("关闭", "Close")) } })
    }
}
