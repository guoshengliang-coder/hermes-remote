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
        AppErrorCode.CONNECTOR_OFFLINE ->
            localized(language, "Mac 端当前离线，请启动 Hermes Go Desktop。", "The Mac is offline. Start Hermes Go Desktop.")
        AppErrorCode.RPC_FAILED ->
            localized(language, "Relay 请求失败，请重试。", "The Relay request failed. Retry.")
        AppErrorCode.RPC_TIMEOUT ->
            localized(language, "Relay 响应超时，请稍后重试。", "The Relay response timed out. Try again shortly.")
        AppErrorCode.MODEL_LIST_FAILED ->
            localized(language, "无法加载模型列表，请重试。", "Couldn't load the model list. Retry.")
        AppErrorCode.MODEL_SWITCH_FAILED ->
            localized(language, "无法切换本会话的模型，请重试。", "Couldn't switch this conversation's model. Retry.")
        AppErrorCode.MODEL_DEFAULT_FAILED ->
            localized(language, "无法设置默认模型，请重试。", "Couldn't set the default model. Retry.")
        AppErrorCode.MODEL_REASONING_FAILED ->
            localized(language, "无法调整推理强度，请重试。", "Couldn't change the reasoning effort. Retry.")
        AppErrorCode.CONFIG_READ_FAILED ->
            localized(language, "无法加载配置，请重试。", "Couldn't load the configuration. Retry.")
        AppErrorCode.CONFIG_WRITE_FAILED ->
            localized(language, "无法保存配置，请重试。", "Couldn't save the configuration. Retry.")
        AppErrorCode.CONFIG_INVALID_URL ->
            localized(language, "Relay 地址格式无效，请检查后重试。", "The Relay URL is invalid. Check it and retry.")
        AppErrorCode.AUTHENTICATION_FAILED ->
            localized(language, "App Token 无效或已失效，请重新配置。", "The App Token is invalid or expired. Configure it again.")
        AppErrorCode.UPDATE_FAILED ->
            localized(language, "更新操作失败，请重试。", "The update operation failed. Retry.")
        AppErrorCode.UPDATE_CHECK_FAILED ->
            localized(language, "无法检查更新，请检查网络后重试。", "Couldn't check for updates. Check your network and retry.")
        AppErrorCode.UPDATE_ENQUEUE_FAILED ->
            localized(language, "无法开始下载更新，请重试。", "Couldn't start the update download. Retry.")
        AppErrorCode.UPDATE_DOWNLOAD_FAILED ->
            localized(language, "更新下载失败，请重试。", "The update download failed. Retry.")
        AppErrorCode.UPDATE_VERIFICATION_FAILED ->
            localized(language, "安装包校验未通过，已阻止安装，请重新下载。", "The package failed verification and was blocked. Download it again.")
        AppErrorCode.UPDATE_FILE_MISSING ->
            localized(language, "下载记录已丢失，请重新下载。", "The download record was lost. Download the update again.")
        AppErrorCode.UPDATE_INSTALLER_FAILED ->
            localized(language, "无法打开系统安装器，请重试。", "Couldn't open the system installer. Retry.")
        AppErrorCode.UPDATE_CLEANUP_FAILED ->
            localized(language, "无法清理更新下载，请重试。", "Couldn't clean up the update download. Retry.")
        AppErrorCode.UPDATE_SUPERSEDED ->
            localized(language, "已发布更新版本，请删除旧下载后获取最新版。", "A newer release is available. Delete the old download and get the latest version.")
        AppErrorCode.FILE_READ_FAILED ->
            localized(language, "无法读取所选文件，请重新选择。", "Couldn't read the selected file. Choose it again.")
        AppErrorCode.TRANSCRIPT_FILE_FAILED ->
            localized(language, "无法生成对话文件，请重试。", "Couldn't create the transcript file. Retry.")
        AppErrorCode.ARTIFACT_FORBIDDEN ->
            localized(
                language,
                "这个文件不在 Mac 允许访问的目录内，无法下载。请让 Hermes 把它放到允许的目录。",
                "The file sits outside the folder the Mac allows, so it can't be downloaded. Ask Hermes to place it inside that folder.",
            )
        AppErrorCode.ARTIFACT_TOO_LARGE ->
            localized(
                language,
                "文件超过传输上限，无法下载。请让 Hermes 压缩或拆分后再发。",
                "The file exceeds the transfer limit. Ask Hermes to compress or split it.",
            )
        AppErrorCode.ARTIFACT_MISSING ->
            localized(
                language,
                "这个文件在 Mac 上已不存在，请让 Hermes 重新生成。",
                "The file is no longer on the Mac. Ask Hermes to produce it again.",
            )
        AppErrorCode.ARTIFACT_DOWNLOAD_FAILED ->
            localized(language, "文件下载失败，请重试。", "The download failed. Retry.")
        AppErrorCode.ATTACHMENT_NO_VIEWER ->
            localized(
                language,
                "手机上没有能打开这种文件的应用。文件已下载，请改用「分享」保存到其他应用。",
                "No app on this phone can open this file type. It downloaded fine — use Share to save it elsewhere.",
            )
        AppErrorCode.TRANSCRIPT_IMAGE_FAILED ->
            localized(language, "无法生成对话长图，请重试或改用 Markdown 文件。", "Couldn't render the transcript image. Retry, or share it as a Markdown file.")
        AppErrorCode.AVATAR_PHOTO_FAILED ->
            localized(language, "无法读取所选照片，请换一张再试。", "Couldn't read the selected photo. Try a different one.")
        AppErrorCode.PROFILE_IDENTITY_SAVE_FAILED ->
            localized(language, "无法保存身份设置，请重试。", "Couldn't save the profile settings. Retry.")
        AppErrorCode.SESSION_NOT_FOUND ->
            localized(language, "会话不存在或已被删除。", "The conversation no longer exists or was deleted.")
        AppErrorCode.PROJECT_FOLDER_MISSING ->
            localized(language, "项目文件夹在 Mac 上不存在，请重新加载项目后重试。", "The project folder no longer exists on the Mac. Reload projects and retry.")
        AppErrorCode.SESSION_BUSY ->
            localized(language, "会话正在运行，无法移动项目，请等待完成后重试。", "The conversation is running, so its project can't be changed. Wait for it to finish and retry.")
        AppErrorCode.PROJECT_MOVE_FAILED ->
            localized(language, "无法移动会话到该项目，请重试。", "Couldn't move the conversation to that project. Retry.")
        AppErrorCode.PROJECT_FELL_BACK_TO_DEFAULT ->
            localized(language, "项目文件夹在 Mac 上不存在，会话已建在默认项目。", "The project folder no longer exists on the Mac, so the conversation was created in the default project.")
        AppErrorCode.MESSAGE_SEND_FAILED ->
            localized(language, "消息未发送，点按气泡重试。", "The message was not sent. Tap the bubble to retry.")
        AppErrorCode.INSTALL_PERMISSION_REQUIRED ->
            localized(language, "需要允许安装未知应用，授权后请重试。", "Permission to install unknown apps is required. Grant it and retry.")
        AppErrorCode.RUN_UNCONFIRMED ->
            localized(language, "任务停止了，但没有确认完成，请打开会话检查。", "The task stopped without a confirmed completion. Open the conversation to check.")
        AppErrorCode.NOTIFICATION_ACTION_FAILED ->
            localized(language, "通知操作未能发送，请重试。", "The notification action couldn't be sent. Try again.")
        AppErrorCode.SEARCH_FAILED ->
            localized(language, "消息搜索失败，请重试。", "Message search failed. Retry.")
        AppErrorCode.LINK_NO_HANDLER ->
            localized(language, "没有能打开链接的应用，链接已复制。", "No app can open this link. It was copied to the clipboard.")
        AppErrorCode.LINK_NOT_OPENABLE ->
            localized(language, "这个链接无法打开。", "This link can't be opened.")
        AppErrorCode.UNKNOWN ->
            localized(language, "出现未知错误，请重试。", "An unknown error occurred. Retry.")
    }
    return "$summary (${code.value})"
}
