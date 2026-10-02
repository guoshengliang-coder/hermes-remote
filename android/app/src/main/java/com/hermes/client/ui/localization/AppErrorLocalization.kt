package com.hermes.client.ui.localization

import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode

/** Product-safe error copy. Technical causes remain available only through diagnostics. */
fun AppError.localizedSummary(language: AppLanguage): String = when (code) {
        AppErrorCode.DEVICE_OFFLINE ->
            localized(
                language,
                "手机当前没有可用网络，请检查 Wi-Fi 或移动网络，恢复后会自动重连。",
                "Your phone has no network. Check Wi-Fi or mobile data; the app reconnects once it's back.",
            )
        AppErrorCode.CONNECTION_FAILED ->
            localized(language, "连不上服务，自动检测也没能确定原因，请重试。", "Couldn't reach the service, and automatic checks couldn't find the cause. Retry.")
        AppErrorCode.HANDSHAKE_TIMEOUT ->
            localized(language, "Relay 已连上，但会话握手超时，请重试。", "The Relay connected, but the session handshake timed out. Retry.")
        AppErrorCode.CONNECTION_INTERRUPTED ->
            localized(language, "连接中断，正在恢复会话。", "The connection was interrupted. Restoring the conversation.")
        AppErrorCode.CONNECTOR_OFFLINE ->
            localized(
                language,
                "手机能连上服务，但你电脑上的 Hermes Go 没有连接，请在电脑上打开它。",
                "The phone reached the service, but Hermes Go isn't connected on your computer. Open it there.",
            )
        AppErrorCode.HERMES_UNREACHABLE ->
            localized(
                language,
                "已连上这台 Mac，但上面的 Hermes 没有响应，请在 Mac 上检查 Hermes。",
                "Reached the Mac, but Hermes on it isn't responding. Check Hermes on the Mac.",
            )
        AppErrorCode.CONNECTION_UNSTABLE ->
            localized(
                language,
                "连接反复中断，这次操作没能完成，请稍后重试或检查 Mac 端。",
                "The connection keeps dropping, so this didn't go through. Try again later or check the Mac.",
            )
        AppErrorCode.ADDRESS_NOT_FOUND ->
            localized(language, "手机解析不了服务地址，请切换 Wi-Fi 或移动网络后重试。", "Your phone can't resolve the service address. Switch Wi-Fi or mobile data and retry.")
        AppErrorCode.CONNECTION_TIMEOUT ->
            localized(language, "暂时连不上服务，请切换 Wi-Fi 或移动网络后重试。", "Couldn't reach the service. Switch Wi-Fi or mobile data and retry.")
        AppErrorCode.SERVICE_UNAVAILABLE ->
            localized(language, "服务地址能访问，但 Relay 没有正常响应，请稍后重试。", "The service address responds, but the Relay isn't responding properly. Try again later.")
        AppErrorCode.CONNECTION_FLAPPING ->
            localized(
                language,
                "连接时好时坏，暂时无法判断是哪一端的问题。应用已自动重试，请稍后再试。",
                "The connection keeps changing, so the app can't tell which side is at fault yet. It retried automatically; try again later.",
            )
        AppErrorCode.RPC_FAILED ->
            localized(language, "Relay 请求失败，请重试。", "The Relay request failed. Retry.")
        AppErrorCode.RPC_TIMEOUT ->
            localized(language, "Relay 响应超时，请稍后重试。", "The Relay response timed out. Try again shortly.")
        AppErrorCode.MODEL_LIST_FAILED ->
            localized(language, "无法加载模型列表，请重试。", "Couldn't load the model list. Retry.")
        AppErrorCode.MODEL_SWITCH_FAILED ->
            localized(language, "无法切换本会话的模型，请重试。", "Couldn't switch this conversation's model. Retry.")
        AppErrorCode.MODEL_SWITCH_UNCONFIRMED ->
            localized(language, "模型切换未收到确认，请查看当前模型后再操作。", "The model switch was not confirmed. Check the current model before trying again.")
        AppErrorCode.MODEL_DEFAULT_FAILED ->
            localized(language, "无法设置默认模型，请重试。", "Couldn't set the default model. Retry.")
        AppErrorCode.MODEL_REASONING_FAILED ->
            localized(language, "无法调整推理强度，请重试。", "Couldn't change the reasoning effort. Retry.")
        AppErrorCode.SLASH_WORKER_UNAVAILABLE ->
            localized(
                language,
                "Mac 上的 Hermes 无法执行命令，请查看详情。",
                "The Hermes on your Mac can't run commands. See the details.",
            )
        AppErrorCode.SESSION_CREATE_UNCONFIRMED ->
            localized(language, "新建会话未收到确认，连接正在恢复，请稍后重试。", "New conversation was not confirmed. The connection is recovering; try again shortly.")
        AppErrorCode.SUBAGENT_UNFINISHED ->
            localized(language, "子代理未完成，可在输入框重新说明任务。", "Subagent did not finish. Ask again in the composer.")
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
        AppErrorCode.UPLOAD_TOO_LARGE ->
            localized(language, "文件超过 50 MiB 上传上限，请选择较小的文件。", "The file exceeds the 50 MiB upload limit. Choose a smaller file.")
        AppErrorCode.UPLOAD_BUSY ->
            localized(language, "这台 Mac 正在接收另一个大文件，请稍后重试。", "This Mac is receiving another large file. Retry shortly.")
        AppErrorCode.UPLOAD_SERVER_LIMIT ->
            localized(language, "文件在 50 MiB 范围内，但服务端拒绝了上传。请检查 Relay 和 Mac 端版本或配置。", "The file is within 50 MiB, but the service refused it. Check the Relay and Mac versions or settings.")
        AppErrorCode.TRANSCRIPT_IMAGE_FAILED ->
            localized(language, "无法生成对话长图，请重试或改用 Markdown 文件。", "Couldn't render the transcript image. Retry, or share it as a Markdown file.")
        AppErrorCode.AVATAR_PHOTO_FAILED ->
            localized(language, "无法读取所选照片，请换一张再试。", "Couldn't read the selected photo. Try a different one.")
        AppErrorCode.IMAGE_DECODE_FAILED ->
            localized(
                language,
                "无法打开这张图片，可能已损坏或过大。请换一张再试。",
                "Couldn't open this image — it may be damaged or too large. Try a different one.",
            )
        // Kept separate from the decode failure on purpose: "your edits are still on screen" is the
        // recovery, and collapsing the two codes would lose it.
        AppErrorCode.IMAGE_EDIT_SAVE_FAILED ->
            localized(
                language,
                "编辑结果保存失败，请重试；你的修改仍在屏幕上。",
                "Couldn't save the edited image. Retry — your edits are still on screen.",
            )
        AppErrorCode.GALLERY_READ_FAILED ->
            localized(language, "无法读取手机图库，请重试。", "Couldn't read the photo gallery. Retry.")
        AppErrorCode.PROFILE_IDENTITY_SAVE_FAILED ->
            localized(language, "无法保存身份设置，请重试。", "Couldn't save the profile settings. Retry.")
        AppErrorCode.OUTPUT_HAPTICS_SETTINGS_FAILED ->
            localized(language, "无法读取或保存输出触感设置，请重试。", "Couldn't read or save output haptics settings. Retry.")
        AppErrorCode.OUTPUT_HAPTIC_PREVIEW_FAILED ->
            localized(language, "无法播放触觉试听，请检查系统触觉设置或更换反馈类型。", "Couldn't play the haptics preview. Check system haptics settings or choose another effect.")
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
        AppErrorCode.SESSION_OWNED_ELSEWHERE ->
            localized(language, "该会话正在另一个客户端上运行，请在那边结束后重试。", "This conversation is running on another client. Finish it there, then retry.")
        AppErrorCode.UNSENT_ATTACHMENTS_LOST ->
            localized(language, "附件已丢失，请重新选择附件后发送。", "The attachments are gone. Pick them again and send.")
        AppErrorCode.PDF_RENDER_DEPENDENCY_MISSING ->
            localized(
                language,
                "Mac 上的 Hermes 找不到 PDF 渲染依赖，无法附加 PDF。",
                "Hermes on the Mac can't find its PDF rendering dependency, so the PDF can't be attached.",
            )
        AppErrorCode.SESSION_TOO_LARGE ->
            localized(
                language,
                "这个会话的内容太大，Mac 无法把它传过来，请开新会话继续。",
                "This conversation is too large for the Mac to send. Start a new one to continue.",
            )
        AppErrorCode.SESSION_TRANSCRIPT_UNAVAILABLE ->
            localized(language, "无法读取所选会话的内容，请重试。", "Couldn't read the selected conversation. Retry.")
        AppErrorCode.SESSION_ARCHIVE_FAILED ->
            localized(language, "无法归档会话，请重试。", "Couldn't archive the conversation. Retry.")
        AppErrorCode.PROJECT_NOT_FOUND ->
            localized(language, "项目已不存在，请重新加载。", "That project no longer exists. Reload the list.")
        AppErrorCode.PROJECT_NAME_INVALID ->
            localized(language, "项目名称无效，请换一个。", "That project name isn't valid. Try another.")
        AppErrorCode.PROJECT_SAVE_FAILED ->
            localized(language, "无法保存项目改动，请重试。", "Couldn't save the project change. Retry.")
        AppErrorCode.FOLDER_BROWSE_FAILED ->
            localized(language, "无法读取该文件夹，请换一个位置。", "Couldn't read that folder. Try another location.")
        AppErrorCode.INSTALL_PERMISSION_REQUIRED ->
            localized(language, "需要允许安装未知应用，授权后请重试。", "Permission to install unknown apps is required. Grant it and retry.")
        AppErrorCode.GALLERY_PERMISSION_REQUIRED ->
            localized(language, "需要照片访问权限，请允许全部或部分照片。", "Photo access is required. Allow all or selected photos.")
        AppErrorCode.HISTORY_INCOMPLETE ->
            localized(language, "无法同步完整会话内容，请重试。", "Couldn't synchronize the complete conversation. Retry.")
        AppErrorCode.RUN_UNCONFIRMED ->
            localized(language, "任务停止了，但没有确认完成，请打开会话检查。", "The task stopped without a confirmed completion. Open the conversation to check.")
        AppErrorCode.HISTORY_UPSTREAM_FAILED ->
            localized(language, "Mac 上的 Hermes 返回了错误，请检查 Mac 端。", "Hermes on the Mac returned an error. Check the Mac.")
        AppErrorCode.HISTORY_UNREADABLE ->
            localized(language, "无法解析会话内容，请更新 App。", "This conversation could not be read. Update the app.")
        AppErrorCode.HISTORY_PREVIEW_FAILED ->
            localized(language, "无法读取折叠内容，请重试。", "Couldn't load the folded content. Retry.")
        AppErrorCode.NOTIFICATION_ACTION_FAILED ->
            localized(language, "通知操作未能发送，请重试。", "The notification action couldn't be sent. Try again.")
        AppErrorCode.PUSH_REGISTRATION_FAILED ->
            localized(
                language,
                "实时推送注册失败，暂用定时同步，请重试。",
                "Real-time push registration failed; using periodic sync for now. Retry.",
            )
        AppErrorCode.FEEDBACK_UNAVAILABLE ->
            localized(language, "这个版本没有开启反馈功能。", "Feedback is not enabled in this build.")
        AppErrorCode.FEEDBACK_SUBMIT_FAILED ->
            localized(language, "反馈没有提交成功，请重试。", "The feedback wasn't submitted. Retry.")
        AppErrorCode.FEEDBACK_REJECTED ->
            localized(language, "反馈服务拒绝了这次提交，请联系开发者。", "The feedback service rejected this report. Contact the developer.")
        AppErrorCode.FEEDBACK_RATE_LIMITED ->
            localized(language, "反馈提交过于频繁，请稍后再试。", "Too many reports just now. Try again shortly.")
        AppErrorCode.SEARCH_FAILED ->
            localized(language, "消息搜索失败，请重试。", "Message search failed. Retry.")
        AppErrorCode.CRON_DELIVERY_FAILED ->
            localized(
                language,
                "任务运行成功，但结果没能送到目标渠道。",
                "The task ran successfully, but its result could not be delivered to the target channel.",
            )
        AppErrorCode.CRON_RUN_FAILED ->
            localized(
                language,
                "任务上次运行失败，请查看详情。",
                "The task's last run failed. Check the details.",
            )
        AppErrorCode.CRON_ACTION_FAILED ->
            localized(
                language,
                "操作没有成功，请查看详情后重试。",
                "The action didn't go through. Review the details and retry.",
            )
        AppErrorCode.MESSAGING_LIST_FAILED ->
            localized(language, "无法加载消息渠道，请重试。", "Couldn't load messaging channels. Retry.")
        AppErrorCode.MESSAGING_SAVE_FAILED ->
            localized(language, "渠道设置未能保存，请重试。", "The channel settings couldn't be saved. Retry.")
        AppErrorCode.MESSAGING_PROFILE_CONFLICT ->
            localized(
                language,
                "该渠道已被另一个身份占用，同一个渠道不能同时启用两次。",
                "Another profile already owns this channel; it can't be enabled twice at once.",
            )
        AppErrorCode.MESSAGING_PLATFORM_FAILED ->
            localized(language, "这个渠道没能连上，请检查设置。", "This channel didn't connect. Check its setup.")
        AppErrorCode.MESSAGING_RESTART_FAILED ->
            localized(language, "网关重启失败，请重试。", "The gateway restart failed. Retry.")
        AppErrorCode.HERMES_INCOMPATIBLE ->
            localized(
                language,
                "这台 Mac 上的 Hermes 与 Hermes GO 不兼容，会话或历史记录可能无法打开。请更新 Hermes GO，或把 Hermes 恢复到兼容版本。",
                "The Hermes on this Mac isn't compatible with Hermes GO, so conversations or history may not open. Update Hermes GO, or return Hermes to a compatible version.",
            )
        AppErrorCode.HERMES_FEATURES_MISSING ->
            localized(
                language,
                "这台 Mac 上的 Hermes 缺少部分接口，定时任务、技能等部分功能可能无法使用；聊天不受影响。",
                "The Hermes on this Mac is missing some interfaces, so features such as scheduled tasks or skills may not work. Chat is unaffected.",
            )
        AppErrorCode.HERMES_BELOW_MINIMUM ->
            localized(
                language,
                "这台 Mac 上的 Hermes 版本低于 Hermes GO 已验证的最低版本，部分功能可能异常。请更新 Hermes。",
                "The Hermes on this Mac is older than the oldest version Hermes GO was verified with, so some features may misbehave. Update Hermes.",
            )
        AppErrorCode.LINK_NO_HANDLER ->
            localized(language, "没有能打开链接的应用，链接已复制。", "No app can open this link. It was copied to the clipboard.")
        AppErrorCode.LINK_NOT_OPENABLE ->
            localized(language, "这个链接无法打开。", "This link can't be opened.")
        AppErrorCode.MICROPHONE_PERMISSION_REQUIRED ->
            localized(language, "需要麦克风权限，请在设置中允许后重试。", "Microphone access is required. Allow it in Settings and retry.")
        AppErrorCode.VOICE_UNAVAILABLE ->
            localized(language, "语音识别暂不可用，请稍后重试或改用键盘。", "Voice recognition is unavailable. Retry later or use the keyboard.")
        AppErrorCode.VOICE_RECOGNITION_FAILED ->
            localized(language, "语音识别未完成。如有临时文字，已放入草稿供检查。", "Voice recognition did not finish. Any partial text was placed in the draft for review.")
        AppErrorCode.UNKNOWN ->
            localized(language, "出现未知错误，请重试。", "An unknown error occurred. Retry.")
}

/** Product-safe error copy including its stable code exactly once. */
fun AppError.localizedMessage(language: AppLanguage): String =
    "${localizedSummary(language)} (${code.value})"

/**
 * The same copy as [localizedMessage], in the language-independent form a ViewModel can hold.
 *
 * Both languages come from the one catalogue above rather than being hand-written at the call site
 * — which is how the cron screens ended up printing 「操作失败（HR-RPC-001）」 for every failure:
 * once the string is typed inline, its code is a literal nobody rechecks.
 */
fun AppError.asLocalizedText(): LocalizedText = LocalizedText(
    zh = localizedMessage(AppLanguage.ZH),
    en = localizedMessage(AppLanguage.EN),
)

/**
 * The registered explanation for a code, independent of any particular failure instance.
 *
 * Lets a surface that only holds a code render the catalogue sentence instead of re-typing it.
 */
fun AppErrorCode.localizedSummary(language: AppLanguage): String =
    AppError(this, retryable = false).localizedSummary(language)

/**
 * Summary for a failure named only by its wire code, or null when this build does not know it.
 *
 * Code-string boundaries — a gateway health detail, a server error code — used to re-type the
 * registered sentence next to the code, which is how one code's copy ended up in four files
 * (docs/ERROR_HANDLING.md, this catalogue, HealthStrip, StartupScreen) and drifted. They resolve
 * it here instead. Null means "not one of ours", and the caller keeps whatever fallback it has:
 * never invent a meaning for a string this build cannot read.
 */
fun localizedSummaryForValue(value: String, language: AppLanguage): String? =
    AppErrorCode.fromValue(value)?.localizedSummary(language)

/**
 * Short form for the strip and the chat banner, which have room for a phrase, not a sentence.
 *
 * Only the connection family has one: those codes are the ones a user meets while the app still
 * looks usable, and the strip must say *which link* looks wrong (phone network, Relay, the Mac,
 * Hermes on the Mac) without pretending to know more than the diagnosis does. Every other surface
 * shows [localizedSummary] itself. Null means "no short form — render the summary".
 */
fun AppErrorCode.localizedShortLabel(language: AppLanguage): String? = when (this) {
    AppErrorCode.DEVICE_OFFLINE ->
        localized(language, "手机没有网络", "Phone has no network")
    AppErrorCode.CONNECTION_FAILED ->
        localized(language, "连不上服务", "Can't reach the service")
    AppErrorCode.HANDSHAKE_TIMEOUT ->
        localized(language, "会话握手超时", "Session handshake timed out")
    AppErrorCode.CONNECTION_INTERRUPTED ->
        localized(language, "连接中断", "Connection interrupted")
    AppErrorCode.CONNECTOR_OFFLINE ->
        localized(language, "电脑未连接", "Computer not connected")
    AppErrorCode.HERMES_UNREACHABLE ->
        localized(language, "Mac 上的 Hermes 没响应", "Hermes on the Mac isn't responding")
    AppErrorCode.CONNECTION_UNSTABLE ->
        localized(language, "连接反复中断", "Connection keeps dropping")
    AppErrorCode.ADDRESS_NOT_FOUND ->
        localized(language, "找不到服务地址", "Service address not found")
    AppErrorCode.CONNECTION_TIMEOUT ->
        localized(language, "连不上服务", "Can't reach the service")
    AppErrorCode.SERVICE_UNAVAILABLE ->
        localized(language, "服务没正常响应", "Service isn't responding properly")
    AppErrorCode.CONNECTION_FLAPPING ->
        localized(language, "连接时好时坏", "Connection keeps changing")
    else -> null
}
