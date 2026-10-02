package com.hermes.client.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hermes.client.ui.chat.ChartPreferences
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DataPresentationSetting() {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val preferences = remember { ChartPreferences(context) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var current by remember { mutableStateOf(runCatching { preferences.preference() }.getOrElse { error = AppError(AppErrorCode.CHART_SETTINGS_FAILED, true); "table" }) }
    var pending by remember { mutableStateOf(current) }
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val choices = listOf("table" to localized(language, "表格优先", "Table first"), "chart" to localized(language, "图表优先", "Chart first"), "auto" to localized(language, "自动选择", "Automatic"))
    ListItem(headlineContent = { Text(localized(language, "数据展示方式", "Data presentation")) }, supportingContent = { Text(choices.first { it.first == current }.second) }, modifier = Modifier.clickable { pending = current; open = true })
    if (open) AlertDialog(onDismissRequest = { if (!saving) open = false }, title = { Text(localized(language, "数据展示方式", "Data presentation")) }, text = {
        Column {
            choices.forEach { (value, label) -> Row(Modifier.clickable(enabled = !saving) { pending = value }) {
                RadioButton(selected = pending == value, onClick = { pending = value }, enabled = !saving)
                Text(label)
            } }
            Text(localized(language, "本次明确要求优先；自动选择仅用于结构明确的数据。设置只保存在本机。", "Explicit requests take priority; Automatic charts only unambiguous data. Saved on this device."))
            error?.let { Text(it.localizedMessage(language)) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try { withContext(Dispatchers.IO) { preferences.setPreference(pending) }; current = pending; error = null; open = false }
            catch (_: Exception) { error = AppError(AppErrorCode.CHART_SETTINGS_FAILED, true) }
            finally { saving = false }
        }
    }) { Text(localized(language, if (error == null) "保存" else "重试", if (error == null) "Save" else "Retry")) } }, dismissButton = { TextButton(enabled = !saving, onClick = { open = false }) { Text(localized(language, "取消", "Cancel")) } })
}
