// Registered HR-* codes the web client can surface (docs/ERROR_HANDLING.md). Text is copied from
// the registry's default explanations; do not reword a released code here without the registry.

export type RecoveryAction =
  | "retry"
  | "reconnect"
  | "sign-in"
  | "reload"
  | "select-device"
  | "composer" // say it again in the composer
  | "open-mac" // something must be fixed on the Mac
  | "new-session"
  | "details"
  | "none";

export interface CatalogEntry {
  zh: string;
  en: string;
  retryable: boolean;
  action: RecoveryAction;
}

export const CATALOG = {
  "HR-CONN-001": { zh: "当前网络不可用，请检查网络连接。", en: "No usable network is available. Check your connection.", retryable: true, action: "retry" },
  "HR-CONN-002": { zh: "无法连接 Relay，将自动重试。", en: "Couldn't connect to the Relay. Retrying automatically.", retryable: true, action: "reconnect" },
  "HR-CONN-003": { zh: "Relay 已连接，但会话握手超时。", en: "The Relay connected, but the session handshake timed out.", retryable: true, action: "reconnect" },
  "HR-CONN-004": { zh: "连接中断，正在恢复会话。", en: "The connection was interrupted. Restoring the conversation.", retryable: true, action: "reconnect" },
  "HR-CONN-005": { zh: "Mac 端当前离线，请启动 Hermes Go Desktop。", en: "The Mac is offline. Start Hermes Go Desktop.", retryable: true, action: "retry" },
  "HR-CONN-006": { zh: "Hermes 当前不可访问，请检查这台 Mac 上的 Hermes 服务。", en: "Hermes is unavailable. Check the Hermes service on this Mac.", retryable: true, action: "retry" },
  "HR-AUTH-003": { zh: "登录已过期，请重新登录。", en: "Your session expired. Sign in again.", retryable: false, action: "sign-in" },
  "HR-AUTH-004": { zh: "这台设备的登录已被撤销，请重新登录。", en: "This device's session was revoked. Sign in again.", retryable: false, action: "sign-in" },
  "HR-AUTH-005": { zh: "检测到登录凭据重复使用，为保护账号已退出这台设备。", en: "Reuse of a sign-in credential was detected, so this device was signed out for safety.", retryable: false, action: "sign-in" },
  "HR-AUTH-007": { zh: "登录请求过于频繁，请稍候再试。", en: "Too many sign-in requests. Wait a moment and try again.", retryable: true, action: "retry" },
  "HR-AUTH-009": { zh: "邮箱验证码无效或已过期，请重新获取验证码。", en: "The email code is invalid or expired. Request a new code.", retryable: false, action: "none" },
  "HR-AUTH-010": { zh: "登录邮件发送失败，请稍后重试。", en: "The sign-in email couldn't be sent. Try again shortly.", retryable: true, action: "retry" },
  "HR-AUTH-011": { zh: "此 Relay 尚未启用邮箱登录，请使用其他可用登录方式或原有连接方式。", en: "Email sign-in isn't enabled on this Gateway yet. Use another available method or the legacy connection.", retryable: false, action: "none" },
  "HR-AUTH-012": { zh: "无法验证此浏览器请求，请重新加载账号页面后重试。", en: "This browser request couldn't be verified. Reload the account page and try again.", retryable: false, action: "reload" },
  "HR-ACCOUNT-001": { zh: "此 Hermes GO 账号当前不可用，请联系支持。", en: "This Hermes GO account is currently unavailable. Contact support.", retryable: false, action: "none" },
  "HR-ACCOUNT-002": { zh: "账号服务暂时不可用，请稍后重试。", en: "The account service is temporarily unavailable. Try again shortly.", retryable: true, action: "retry" },
  "HR-ACCOUNT-003": { zh: "此 Relay 尚未启用账号登录，可继续使用原有连接方式。", en: "Account sign-in is not enabled on this Gateway yet. Continue with the legacy connection.", retryable: false, action: "none" },
  "HR-ACCOUNT-004": { zh: "账号请求格式无效，请更新客户端或重试。", en: "The account request is invalid. Update the client or try again.", retryable: false, action: "reload" },
  "HR-ACCOUNT-006": { zh: "找不到这个账号下的目标设备。", en: "The requested account resource was not found.", retryable: false, action: "none" },
  "HR-ACCOUNT-010": { zh: "此 Relay 尚未启用安全网页账号会话。", en: "Secure Web account sessions aren't enabled on this Gateway yet.", retryable: false, action: "none" },
  "HR-ACCOUNT-012": { zh: "此 Hermes GO 账号正在永久删除，已无法再次登录。", en: "This Hermes GO account is being permanently deleted and can no longer sign in.", retryable: false, action: "none" },
  "HR-BIND-001": { zh: "这个账号还没有连接 Desktop，请先在 Mac 上打开 Hermes Go Desktop。", en: "This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac.", retryable: true, action: "open-mac" },
  "HR-BIND-008": { zh: "此 Relay 尚未启用 Desktop 绑定，可继续使用原有连接。", en: "Desktop binding isn't enabled on this Gateway yet. Continue with the legacy connection.", retryable: false, action: "none" },
  "HR-BIND-009": { zh: "请先选择要使用的 Mac，再打开此内容。", en: "Choose which Mac to use before opening this content.", retryable: false, action: "select-device" },
  "HR-BIND-011": { zh: "此 Mac 已无法由当前账号使用，请选择其他设备。", en: "That Mac is no longer available to this account. Choose another device.", retryable: false, action: "select-device" },
  "HR-RPC-003": { zh: "无法加载模型列表，请重试。", en: "Couldn't load the model list. Retry.", retryable: true, action: "retry" },
  "HR-RPC-004": { zh: "无法切换本会话的模型，请重试。", en: "Couldn't switch this conversation's model. Retry.", retryable: true, action: "retry" },
  "HR-RPC-006": { zh: "无法调整推理强度，请重试。", en: "Couldn't change the reasoning effort. Retry.", retryable: true, action: "retry" },
  "HR-RPC-007": { zh: "Mac 上的 Hermes 无法执行命令，请查看详情。", en: "The Hermes on your Mac can't run commands. See the details.", retryable: false, action: "details" },
  "HR-RPC-001": { zh: "Relay 请求失败，请查看详情后重试。", en: "The Relay request failed. Review the details and retry.", retryable: true, action: "details" },
  "HR-RPC-002": { zh: "Relay 响应超时，请稍后重试。", en: "The Relay response timed out. Try again shortly.", retryable: true, action: "retry" },
  "HR-SESS-001": { zh: "会话不存在或已被删除。", en: "The conversation no longer exists or was deleted.", retryable: false, action: "new-session" },
  "HR-SESS-002": { zh: "会话连接已失效，正在重新挂接。", en: "The live conversation handle expired. Reattaching now.", retryable: true, action: "reconnect" },
  "HR-SESS-003": { zh: "项目文件夹在 Mac 上不存在，请重新加载项目后重试。", en: "The project folder no longer exists on the Mac. Reload projects and retry.", retryable: true, action: "retry" },
  "HR-SESS-005": { zh: "无法移动会话到该项目，请重试。", en: "Couldn't move the conversation to that project. Retry.", retryable: true, action: "retry" },
  "HR-SESS-004": { zh: "会话正在运行，无法移动项目，请等待完成后重试。", en: "The conversation is running, so its project can't be changed. Wait for it to finish and retry.", retryable: true, action: "retry" },
  "HR-SESS-007": { zh: "消息未发送，点按气泡重试。", en: "The message was not sent. Tap the bubble to retry.", retryable: true, action: "retry" },
  "HR-SESS-013": { zh: "该会话正在另一个客户端上运行，请在那边结束后重试。", en: "This conversation is running on another client. Finish it there, then retry.", retryable: true, action: "retry" },
  "HR-SESS-016": { zh: "Mac 上的 Hermes 找不到 PDF 渲染依赖，无法附加 PDF。", en: "Hermes on the Mac can't find its PDF rendering dependency, so the PDF can't be attached.", retryable: false, action: "open-mac" },
  "HR-SESS-017": { zh: "这个会话的内容太大，Mac 无法把它传过来，请开新会话继续。", en: "This conversation is too large for the Mac to send. Start a new one to continue.", retryable: false, action: "new-session" },
  "HR-CLARIFY-001": { zh: "这个提问已失效，agent 没有收到这次回答，请在输入框直接说明你的选择。", en: "The clarify question expired before the answer arrived; tell the agent your choice in the composer.", retryable: false, action: "composer" },
  "HR-APPROVAL-003": { zh: "这次审批没有送达：这条审批已不再等待回答（可能已超时、运行已停止，或已在其他设备上处理）。如仍需要，请在输入框重新说明。", en: "This approval didn't reach the agent: the request was no longer waiting (it may have timed out, the run stopped, or it was answered on another device). If you still want it, say so in the composer.", retryable: false, action: "composer" },
  "HR-COMPAT-001": { zh: "这台 Mac 上的 Hermes 与 Hermes GO 不兼容，会话或历史记录可能无法打开。请更新 Hermes GO，或把 Hermes 恢复到兼容版本。", en: "The Hermes on this Mac isn't compatible with Hermes GO, so conversations or history may not open. Update Hermes GO, or return Hermes to a compatible version.", retryable: false, action: "none" },
  "HR-COMPAT-002": { zh: "这台 Mac 上的 Hermes 缺少部分接口，定时任务、技能等部分功能可能无法使用；聊天不受影响。", en: "The Hermes on this Mac is missing some interfaces, so features such as scheduled tasks or skills may not work. Chat is unaffected.", retryable: false, action: "none" },
  "HR-COMPAT-003": { zh: "这台 Mac 上的 Hermes 版本低于 Hermes GO 已验证的最低版本，部分功能可能异常。请更新 Hermes。", en: "The Hermes on this Mac is older than the oldest version Hermes GO was verified with, so some features may misbehave. Update Hermes.", retryable: false, action: "none" },
  "HR-SEARCH-001": { zh: "消息搜索失败，请重试。", en: "Message search failed. Retry.", retryable: true, action: "retry" },
  "HR-SYNC-001": { zh: "无法同步完整会话内容，请重试。", en: "Couldn't synchronize the complete conversation. Retry.", retryable: true, action: "retry" },
  "HR-SYNC-003": { zh: "Mac 上的 Hermes 返回了错误，请检查 Mac 端。", en: "Hermes on the Mac returned an error. Check the Mac.", retryable: false, action: "open-mac" },
  "HR-SYNC-004": { zh: "无法解析会话内容，请更新 App。", en: "This conversation could not be read. Update the app.", retryable: false, action: "reload" },
  "HR-FILE-001": { zh: "无法读取所选文件，请重新选择。", en: "Couldn't read the selected file. Choose it again.", retryable: true, action: "retry" },
  "HR-FILE-003": { zh: "这个文件不在 Mac 允许访问的目录内，无法下载。请让 Hermes 把它放到允许的目录。", en: "The file sits outside the folder the Mac allows, so it can't be downloaded. Ask Hermes to place it inside that folder.", retryable: false, action: "none" },
  "HR-FILE-004": { zh: "文件超过传输上限，无法下载。请让 Hermes 压缩或拆分后再发。", en: "The file exceeds the transfer limit. Ask Hermes to compress or split it.", retryable: false, action: "none" },
  "HR-FILE-005": { zh: "这个文件在 Mac 上已不存在，请让 Hermes 重新生成。", en: "The file is no longer on the Mac. Ask Hermes to produce it again.", retryable: false, action: "none" },
  "HR-FILE-006": { zh: "文件下载失败，请重试。", en: "The download failed. Retry.", retryable: true, action: "retry" },
  // Other codes the Gateway emits (account/model.ts accountErrors), copied from the registry.
  "HR-AUTH-002": { zh: "无法验证 Google 登录，请重新登录。", en: "Couldn't verify the Google sign-in. Sign in again.", retryable: false, action: "sign-in" },
  "HR-AUTH-006": { zh: "为确认是你本人，请重新验证当前账号的登录方式。", en: "Verify your sign-in identity again to confirm it's you.", retryable: false, action: "sign-in" },
  "HR-ACCOUNT-005": { zh: "此重试标识已用于另一项请求，请重新发起操作。", en: "That retry key was already used for a different account request.", retryable: false, action: "none" },
  "HR-ACCOUNT-007": { zh: "此操作只能在当前登录的 Hermes Go Desktop 上完成。", en: "This operation is available only from Hermes Go Desktop.", retryable: false, action: "none" },
  "HR-ACCOUNT-008": { zh: "此登录方式已属于另一个 Hermes GO 账号。", en: "That sign-in identity already belongs to another Hermes GO account.", retryable: false, action: "none" },
  "HR-ACCOUNT-009": { zh: "此 Relay 尚未启用登录方式管理。", en: "Identity management isn't enabled on this Gateway yet.", retryable: false, action: "none" },
  "HR-ACCOUNT-011": { zh: "此账号必须至少保留一种登录方式，请先绑定其他登录方式。", en: "Keep at least one sign-in identity on this account. Link another sign-in method first.", retryable: false, action: "none" },
  "HR-BIND-002": { zh: "这个账号已经连接另一台 Mac；确认替换前，原连接会继续工作。", en: "This account is already connected to another Mac. The existing connection will keep working until replacement is confirmed.", retryable: false, action: "none" },
  "HR-BIND-003": { zh: "Desktop 绑定确认已失效，请重新开始。", en: "The Desktop binding confirmation expired. Start again.", retryable: true, action: "retry" },
  "HR-BIND-005": { zh: "Desktop Connector 身份验证失败，请在 Mac 上检查账号与设备。", en: "Desktop Connector authentication failed. Check Account & Devices on the Mac.", retryable: true, action: "retry" },
  "HR-BIND-006": { zh: "这台 Mac 已不再绑定当前账号，请重新绑定或使用现有 Mac。", en: "This Mac is no longer bound to the account. Bind it again or use the current Mac.", retryable: false, action: "none" },
  "HR-BIND-007": { zh: "未能更换 Mac，原来的连接仍在工作。", en: "Couldn't replace the Mac. The original connection is still working.", retryable: true, action: "retry" },
  "HR-BIND-010": { zh: "此账号已达到三台自有 Mac 的上限，请先移除一台。", en: "This account already owns the maximum of three Macs. Remove one before adding another.", retryable: false, action: "none" },
  "HR-SHARE-001": { zh: "此 Relay 尚未启用设备共享。", en: "Device sharing isn't enabled on this Gateway yet.", retryable: false, action: "none" },
  "HR-SHARE-002": { zh: "此 Mac 已共享给五个账号，请先撤销一个共享。", en: "This Mac is already shared with five accounts. Revoke one share first.", retryable: false, action: "none" },
  "HR-SHARE-003": { zh: "你的账号已接受十台共享 Mac，请先退出一台。", en: "Your account already has ten shared Macs. Leave one first.", retryable: false, action: "none" },
  "HR-SHARE-004": { zh: "此共享邀请无效、已过期或已被取消，请让设备所有者重新邀请。", en: "This invitation is invalid, expired, or cancelled. Ask the device owner to invite you again.", retryable: false, action: "none" },
  "HR-SHARE-005": { zh: "请使用收到邀请的已验证邮箱登录此账号。", en: "Sign in with the verified email address that received the invitation.", retryable: false, action: "none" },
  "HR-SHARE-006": { zh: "请先确认被邀请者可访问这台 Hermes 暴露的会话、文件和配置。", en: "Confirm that the invited account may access sessions, files, and configuration exposed by this Hermes.", retryable: false, action: "none" },
  "HR-SHARE-007": { zh: "此邀请或共享已存在，或目标账号不符合共享条件。", en: "This invitation or share already exists, or the target account isn't eligible.", retryable: false, action: "none" },
  "HR-SHARE-008": { zh: "邀请邮件发送失败；邀请已安全保留，请稍后使用相同邮箱重试。", en: "The invitation email wasn't sent; the invitation was safely retained. Retry with the same email shortly.", retryable: true, action: "retry" },
  "HR-UNKNOWN-001": { zh: "出现未知错误，请复制诊断信息协助定位。", en: "An unknown error occurred. Copy diagnostics to help investigate.", retryable: true, action: "details" },
  // REST route (403) or WebSocket method (RPC 4403) not open to browsers.
  "HR-WEB-001": { zh: "网页版不支持此功能，请使用 Android 应用。", en: "This feature isn't available in the Hermes GO web app. Use the Android app instead.", retryable: false, action: "none" },
  // Web-only conditions (registered 2026-09-21).
  "HR-WEB-002": { zh: "当前浏览器不支持实时连接，请升级 Safari 或更换浏览器。", en: "This browser doesn't support live connections. Update Safari or use another browser.", retryable: false, action: "none" },
  "HR-WEB-003": { zh: "无法连接 Hermes GO 服务器，请稍后重试。", en: "Couldn't reach the Hermes GO server. Try again shortly.", retryable: true, action: "retry" },
  "HR-WEB-004": { zh: "服务器暂时不可用，请稍后重试。", en: "The server is temporarily unavailable. Try again shortly.", retryable: true, action: "retry" },
  "HR-WEB-005": { zh: "服务器返回了无法处理的响应，请刷新页面后重试。", en: "The server sent a response this page can't handle. Reload and try again.", retryable: false, action: "reload" },
  "HR-WEB-006": { zh: "附件不符合要求：每条消息最多 9 个，单个不超过 6 MB，且不能是空文件或可执行文件。", en: "This attachment isn't allowed: up to 9 per message, 6 MB each, and no empty or executable files.", retryable: false, action: "none" },
  "HR-WEB-007": { zh: "无法复制到剪贴板，请长按文字手动选择复制。", en: "Couldn't copy to the clipboard. Press and hold the text to select and copy it.", retryable: false, action: "none" },
} satisfies Record<string, CatalogEntry>;

export type ErrorCode = keyof typeof CATALOG;

export function isKnownCode(code: unknown): code is ErrorCode {
  return typeof code === "string" && Object.prototype.hasOwnProperty.call(CATALOG, code);
}
