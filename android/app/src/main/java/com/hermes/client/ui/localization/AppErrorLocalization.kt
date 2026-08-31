package com.hermes.client.ui.localization

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode

/** Product-safe error copy. Technical causes remain available only through diagnostics. */
fun AppError.localizedMessage(language: AppLanguage): String {
    val summary = when (code) {
        AppErrorCode.CONNECTION_FAILED ->
            localized(language, "无法连接 Relay，请重试。", "Couldn't connect to the Relay. Retry.")
        AppErrorCode.CONNECTION_INTERRUPTED ->
            localized(language, "连接已中断，请重试。", "The connection was interrupted. Retry.")
        AppErrorCode.RPC_FAILED ->
            localized(language, "Relay 请求失败，请重试。", "The Relay request failed. Retry.")
        AppErrorCode.MODEL_LIST_FAILED ->
            localized(language, "无法加载模型列表，请重试。", "Couldn't load the model list. Retry.")
        AppErrorCode.MODEL_SWITCH_FAILED ->
            localized(language, "无法切换本会话的模型，请重试。", "Couldn't switch this conversation's model. Retry.")
        AppErrorCode.MODEL_DEFAULT_FAILED ->
            localized(language, "无法设置默认模型，请重试。", "Couldn't set the default model. Retry.")
        AppErrorCode.CONFIG_READ_FAILED ->
            localized(language, "无法加载配置，请重试。", "Couldn't load the configuration. Retry.")
        AppErrorCode.CONFIG_WRITE_FAILED ->
            localized(language, "无法保存配置，请重试。", "Couldn't save the configuration. Retry.")
        AppErrorCode.CONFIG_INVALID_URL ->
            localized(language, "Relay 地址格式无效，请检查后重试。", "The Relay URL is invalid. Check it and retry.")
        AppErrorCode.UPDATE_FAILED ->
            localized(language, "更新操作失败，请重试。", "The update operation failed. Retry.")
        AppErrorCode.FILE_READ_FAILED ->
            localized(language, "无法读取所选文件，请重新选择。", "Couldn't read the selected file. Choose it again.")
        AppErrorCode.INSTALL_PERMISSION_REQUIRED ->
            localized(language, "需要允许安装未知应用，授权后请重试。", "Permission to install unknown apps is required. Grant it and retry.")
        AppErrorCode.UNKNOWN ->
            localized(language, "出现未知错误，请重试。", "An unknown error occurred. Retry.")
    }
    return "$summary (${code.value})"
}
