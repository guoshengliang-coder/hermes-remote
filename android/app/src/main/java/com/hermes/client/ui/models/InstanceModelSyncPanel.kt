package com.hermes.client.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.client.data.repository.InstanceModelPreferences
import com.hermes.client.data.sync.AccountModelPreferences
import com.hermes.client.data.sync.LegacyModelImport
import com.hermes.client.data.sync.LegacyModelPreference
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.l10n
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun InstanceModelSyncPanel(sync: InstanceModelPreferences?, loadLegacy: suspend () -> LegacyModelImport, accountSync: AccountModelPreferences?, provider: String? = null, model: String? = null) {
    if (sync == null) { AccountModelSyncPanel(accountSync, loadLegacy, provider, model); return }
    val state by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val instanceLabel = sync.instanceLabel()
    val language = LocalAppLanguage.current
    var importing by remember(state.owner) { mutableStateOf(false) }
    var confirmation by remember(state.owner) { mutableStateOf<Pair<String, List<LegacyModelPreference>>?>(null) }
    LaunchedEffect(sync, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { sync.watchRoute() }
            while (true) { sync.refresh(); delay(5000) }
        }
    }
    if (!state.active) {
        Text(l10n("此 Hermes 不支持实例偏好同步，收藏仍独立管理。", "This Hermes does not support instance preference sync. Favorites remain independent."), style = MaterialTheme.typography.bodySmall)
        AccountModelSyncPanel(accountSync, loadLegacy, provider, model)
        return
    }
    Column {
        Text(if (state.snapshot == null) l10n("正在读取此 Mac 的模型偏好…", "Loading this Mac’s model preferences…")
            else l10n("收藏和隐藏与此 Hermes 实例同步", "Favorites and visibility sync with this Hermes instance"), style = MaterialTheme.typography.bodySmall)
        if (state.readOnly && state.snapshot != null) Text(l10n("共享访客只能查看，不能修改主人偏好。", "Shared guests can view, but cannot edit the owner’s preferences."), style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it.localizedMessage(language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { scope.launch { sync.refresh() } }) { Text(l10n("刷新模型偏好", "Refresh model preferences")) }
        TextButton(onClick = sync::toggleShowHidden) { Text(if (state.showHidden) l10n("收起已隐藏模型", "Hide hidden models") else l10n("管理隐藏模型", "Manage hidden models")) }
        TextButton(enabled = !state.blocked() && !importing, onClick = {
            val owner = state.owner
            scope.launch {
                try {
                val local = loadLegacy().candidates.filter { it.favorite }
                val cloud = accountSync?.state?.value?.projected()?.filter { it.favorite }?.map { LegacyModelPreference(it.provider,it.model,true,null) }.orEmpty()
                if (sync.state.value.owner == owner) confirmation = owner to (local + cloud).distinctBy { it.provider to it.model }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if(sync.state.value.owner == owner) sync.importFailed() }
            }
        }) { Text(l10n("导入旧收藏", "Import previous favorites")) }
    }
    AccountModelSyncPanel(accountSync, loadLegacy, provider, model, reasoningOnly = true)
    confirmation?.let { (owner, candidates) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(l10n("确认导入 $instanceLabel", "Import into $instanceLabel")) },
            text = { Text(l10n("导入 ${candidates.size} 项旧收藏？原数据保留，不覆盖此 Mac 已有的选择或取消。", "Import ${candidates.size} previous favorites? Originals remain; existing choices and cancellations are preserved.")) },
            confirmButton = { TextButton(onClick = {
                confirmation = null; importing = true
                scope.launch {
                    try { for (r in candidates) {
                        if (sync.state.value.owner != owner) break
                        if (sync.state.value.snapshot?.rows?.any { it.provider == r.provider && it.model == r.model && it.favoriteRevision != "0" } == true) continue
                        if (!sync.set(r.provider,r.model,"favorite",true,importing=true,expectedOwner=owner)) break
                    } } finally { importing = false }
                }
            }) { Text(l10n("确认导入", "Import")) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(l10n("取消", "Cancel")) } })
    }
}
