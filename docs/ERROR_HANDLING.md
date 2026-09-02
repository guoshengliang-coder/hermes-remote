# Hermes Remote error handling standard

This document is the source of truth for errors produced by Hermes Remote. It applies to every
human or coding agent contributing to Android, Gateway, Connector, protocol, deployment, and update
tooling. It does not require changes to Hermes itself.

## Product contract

Every failure visible to a user must present all of the following:

1. A stable error code in the form `HR-<AREA>-<NNN>`.
2. A short explanation in the language selected inside the app (Chinese is the default).
3. A recovery action when recovery is possible.
4. Optional technical details that can be expanded or copied for diagnosis.

The primary UI must never expose only a raw exception such as `client closing`, `timeout`, a JSON
payload, an HTTP response body, or a stack trace. Server-provided text may be included in technical
details after redaction, but it does not replace the localized explanation.

Example:

```text
无法恢复连接
Relay 已连接，但没有按时完成会话握手。
错误码：HR-CONN-003

[重试] [查看详情] [复制诊断]
```

English mode uses the same code:

```text
Couldn't restore the connection
The Relay connected, but the session handshake did not finish in time.
Error code: HR-CONN-003
```

## Code format and ownership

Codes use `HR-<AREA>-<NNN>`, where the numeric suffix is a zero-padded, monotonically allocated
number within the area. Once a code has shipped, its meaning is immutable and the code must never be
reassigned.

| Area | Ownership | Typical failures |
|---|---|---|
| `CONN` | Transport and WebSocket lifecycle | offline, handshake, reconnect, socket closed |
| `AUTH` | Authentication and authorization | invalid App Token, expired session, forbidden |
| `RPC` | Gateway RPC | remote error, readiness timeout, response timeout |
| `SESS` | Session/process lifecycle | stale live handle, resume failure, session missing |
| `SYNC` | State and history reconciliation | incomplete history, conflicting terminal state |
| `MEDIA` | Images and media | decode, preview, upload/download, size limit |
| `FILE` | General attachments and artifacts | unsupported file, save/open failure |
| `PERM` | Android or service permissions | camera, notifications, storage access |
| `NOTIF` | Notification delivery and actions | channel disabled, action/reply failure |
| `UPDATE` | APK update and installation | manifest, download, hash, certificate, installer |
| `CONFIG` | Local or deployment configuration | invalid URL, missing field, incompatible setting |
| `STORE` | Local persistence | DataStore/database/cache failure |
| `UNKNOWN` | Truly unmapped failures | last-resort boundary only; must be investigated |

## Canonical structured error

Components should map their native exceptions into one shared conceptual shape at the boundary:

```text
code             stable HR-* identifier
summaryKey       localized, user-facing summary key
detailKey        localized, user-facing explanation key
retryable        whether Retry should be offered
recoveryAction   retry / reconnect / settings / details / none
technicalCause   sanitized developer detail; never primary UI
stage            operation stage such as ws_ticket, gateway_ready, history_sync
correlationId    optional request/run identifier safe to share
occurredAt       timestamp
```

Android should use a central typed error model rather than passing arbitrary strings through
ViewModels. Gateway/Connector/protocol responses should use a structured error envelope carrying at
least `code`, `message`, and `retryable`; additional technical detail must remain optional and
backward compatible.

## Presentation rules

- Inline banners and dialogs show the localized summary, short explanation, and code.
- Tight inline surfaces (a message bubble's status line, a badge) may show the **compact form**:
  the code without its `HR-` prefix (`SESS-007`), rendered in the neutral text colour after the
  localized action copy. The compact form is display-only; the full code stays the identity in
  toasts, pages, diagnostics, notifications and this registry (`AppErrorCode.compact`).
- Recoverable connection transitions use neutral progress states such as “正在重新连接…” rather
  than an error until retry policy is exhausted.
- A recovered connection briefly shows success and then dismisses itself.
- Persistent failures remain visible and offer the appropriate action.
- Failure notifications use the app-selected language, include the code, and deep-link to the
  affected screen. Notification channel names and action labels are localized as well.
- Debug/technical mode may show more detail, but product mode still exposes the error code and a
  copy-diagnostics action.
- Accessibility descriptions must communicate the same state and recovery action.

## Diagnostics and security

Copyable diagnostics may include the code, app version, component, stage, timestamp, connection
state, socket generation, retry count, safe HTTP/RPC status, session/profile identifiers when
appropriately shortened, and correlation ID.

They must redact App Tokens, connector tokens, passwords, cookies, authorization headers, private
keys, signed URLs/query parameters, message bodies, local file contents, and other personal or secret
data. Never log a secret merely because the error path is exceptional.

## Registry

Add a row before introducing a code. Keep the explanation stable after release; clarification may be
expanded without changing the underlying meaning.

| Code | Condition | Default Chinese explanation | Default English explanation | Retryable |
|---|---|---|---|---|
| `HR-CONN-001` | Device has no usable network | 当前网络不可用，请检查网络连接。 | No usable network is available. Check your connection. | Yes |
| `HR-CONN-002` | WebSocket connection failed | 无法连接 Relay，将自动重试。 | Couldn't connect to the Relay. Retrying automatically. | Yes |
| `HR-CONN-003` | `gateway.ready` handshake timed out | Relay 已连接，但会话握手超时。 | The Relay connected, but the session handshake timed out. | Yes |
| `HR-CONN-004` | Connection was interrupted during an operation | 连接中断，正在恢复会话。 | The connection was interrupted. Restoring the conversation. | Yes |
| `HR-CONN-005` | Relay is reachable but the Mac Connector is offline | Mac 端当前离线，请启动 Hermes Go Desktop。 | The Mac is offline. Start Hermes Go Desktop. | Yes |
| `HR-AUTH-001` | App Token rejected | App Token 无效或已失效，请重新配置。 | The App Token is invalid or expired. Configure it again. | No |
| `HR-RPC-001` | Gateway RPC returned an unmapped remote error | Relay 请求失败，请查看详情后重试。 | The Relay request failed. Review the details and retry. | Depends |
| `HR-RPC-002` | Gateway RPC response timed out | Relay 响应超时，请稍后重试。 | The Relay response timed out. Try again shortly. | Yes |
| `HR-RPC-003` | Model catalog could not be loaded | 无法加载模型列表，请重试。 | Couldn't load the model list. Retry. | Yes |
| `HR-RPC-004` | Switching the conversation's session model failed | 无法切换本会话的模型，请重试。 | Couldn't switch this conversation's model. Retry. | Yes |
| `HR-RPC-005` | Setting the default model failed | 无法设置默认模型，请重试。 | Couldn't set the default model. Retry. | Yes |
| `HR-RPC-006` | Changing the conversation's reasoning effort failed | 无法调整推理强度，请重试。 | Couldn't change the reasoning effort. Retry. | Yes |
| `HR-CONFIG-001` | Configuration could not be loaded | 无法加载配置，请重试。 | Couldn't load the configuration. Retry. | Yes |
| `HR-CONFIG-002` | Configuration could not be saved | 无法保存配置，请重试。 | Couldn't save the configuration. Retry. | Yes |
| `HR-CONFIG-003` | Relay URL is invalid | Relay 地址格式无效，请检查后重试。 | The Relay URL is invalid. Check it and retry. | Yes |
| `HR-UPDATE-001` | Unmapped update check, download, verification, or installer failure | 更新操作失败，请重试。 | The update operation failed. Retry. | Yes |
| `HR-UPDATE-002` | Update index could not be fetched or parsed | 无法检查更新，请检查网络后重试。 | Couldn't check for updates. Check your network and retry. | Yes |
| `HR-UPDATE-003` | DownloadManager job could not be enqueued or persisted | 无法开始下载更新，请重试。 | Couldn't start the update download. Retry. | Yes |
| `HR-UPDATE-004` | DownloadManager reported a failed download | 更新下载失败，请重试。 | The update download failed. Retry. | Yes |
| `HR-UPDATE-005` | Downloaded APK failed size/hash/identity/signature verification | 安装包校验未通过，已阻止安装，请重新下载。 | The package failed verification and was blocked. Download it again. | Yes (re-download only; installation stays blocked) |
| `HR-UPDATE-006` | Persisted download record or completed file is missing | 下载记录已丢失，请重新下载。 | The download record was lost. Download the update again. | Yes |
| `HR-UPDATE-007` | System package installer could not be opened | 无法打开系统安装器，请重试。 | Couldn't open the system installer. Retry. | Yes |
| `HR-UPDATE-008` | DownloadManager job, persisted metadata, or residual APK could not be cleaned up | 无法清理更新下载，请重试。 | Couldn't clean up the update download. Retry. | Yes |
| `HR-UPDATE-009` | A restored/downloaded APK is no longer the manifest's latest release | 已发布更新版本，请删除旧下载后获取最新版。 | A newer release is available. Delete the old download and get the latest version. | No (delete old download, then download latest) |
| `HR-FILE-001` | A selected attachment could not be read | 无法读取所选文件，请重新选择。 | Couldn't read the selected file. Choose it again. | Yes |
| `HR-MEDIA-001` | Image save, preparation, or share operation failed | 图片操作失败，请重试。 | The image operation failed. Retry. | Yes |
| `HR-PERM-003` | Android blocks installation from this source | 需要允许安装未知应用，授权后请重试。 | Permission to install unknown apps is required. Grant it and retry. | Yes |
| `HR-SESS-001` | Session no longer exists | 会话不存在或已被删除。 | The conversation no longer exists or was deleted. | No |
| `HR-SESS-002` | Live session handle is stale | 会话连接已失效，正在重新挂接。 | The live conversation handle expired. Reattaching now. | Yes |
| `HR-SESS-003` | Project folder for a move/create no longer exists on the Mac (`session.workspace.move` 4017, or a derived project without a known path) | 项目文件夹在 Mac 上不存在，请重新加载项目后重试。 | The project folder no longer exists on the Mac. Reload projects and retry. | Yes |
| `HR-SESS-004` | Session is mid-turn, so its project cannot be changed (`session.workspace.move` 4009) | 会话正在运行，无法移动项目，请等待完成后重试。 | The conversation is running, so its project can't be changed. Wait for it to finish and retry. | Yes |
| `HR-SESS-005` | Unmapped failure moving a session to another project | 无法移动会话到该项目，请重试。 | Couldn't move the conversation to that project. Retry. | Yes |
| `HR-SESS-007` | A user message could not be submitted (`prompt.submit`/attachment upload raised, or the live-handle wait timed out); the bubble stays on screen as 未发送 with tap-to-retry | 消息未发送，点按气泡重试。 | The message was not sent. Tap the bubble to retry. | Yes |
| `HR-SESS-006` | New session was requested in a project folder the Mac no longer has; the gateway created it in the default project instead | 项目文件夹在 Mac 上不存在，会话已建在默认项目。 | The project folder no longer exists on the Mac, so the conversation was created in the default project. | No |
| `HR-CLARIFY-001` | Clarify answer arrived after the request expired server-side | 这个提问已失效，agent 没有收到这次回答，请在输入框直接说明你的选择。 | The clarify question expired before the answer arrived; tell the agent your choice in the composer. | No |
| `HR-SYNC-001` | Final history reconciliation failed | 无法同步完整会话内容，请重试。 | Couldn't synchronize the complete conversation. Retry. | Yes |
| `HR-PERM-001` | Camera permission denied | 相机权限未开启，请前往系统设置允许。 | Camera permission is disabled. Allow it in system settings. | Yes |
| `HR-PERM-002` | Notification permission denied | 通知权限未开启，后台任务可能无法及时提醒。 | Notifications are disabled, so background alerts may be delayed. | Yes |
| `HR-UNKNOWN-001` | Unmapped boundary failure | 出现未知错误，请复制诊断信息协助定位。 | An unknown error occurred. Copy diagnostics to help investigate. | Depends |

## Implementation and review checklist

For every new or changed failure path:

- Reuse the correct registered code or allocate and document a new one.
- Provide Chinese and English summaries and explanations.
- Decide retryability and recovery action explicitly.
- Preserve a sanitized technical cause and operation stage.
- Render the same semantics consistently in UI and notifications.
- Test mapping, localization, actions, serialization where relevant, and redaction.
- Verify that reconnect/retry progress is not incorrectly presented as a terminal error.
- Include the affected error codes in the change summary and release notes when user behavior changes.

Legacy unstructured errors should be migrated by subsystem. Do not perform blind global string
replacement: map each failure at its owning boundary so codes remain meaningful and testable.
