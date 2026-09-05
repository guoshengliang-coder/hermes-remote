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
| `ACCOUNT` | Hermes GO account lifecycle | disabled account, account service unavailable |
| `BIND` | Account-to-installation/Connector binding | no Desktop, conflict, revoke, replacement |
| `MIGRATE` | Legacy-to-account Connector migration | preflight, duplicate process, rollback |
| `RPC` | Gateway RPC | remote error, readiness timeout, response timeout |
| `SESS` | Session/process lifecycle | stale live handle, resume failure, session missing |
| `SYNC` | State and history reconciliation | incomplete history, conflicting terminal state |
| `MEDIA` | Images and media | decode, preview, upload/download, size limit |
| `FILE` | General attachments and artifacts | unsupported file, save/open failure |
| `PERM` | Android or service permissions | camera, notifications, storage access |
| `NOTIF` | Notification delivery and actions | channel disabled, action/reply failure |
| `UPDATE` | APK update and installation | manifest, download, hash, certificate, installer |
| `RELEASE` | Server release packaging and candidate gates | build prerequisites, image identity, isolated smoke |
| `OPS` | Cloud host installation and diagnostics | preflight, artifact integrity, bootstrap, status, doctor |
| `CONFIG` | Local or deployment configuration | invalid URL, missing field, incompatible setting |
| `STORE` | Local persistence | DataStore/database/cache failure |
| `SEARCH` | Session and message search | gateway search request failed, search backend unavailable |
| `LINK` | Links the app opens out of its own content | no app can open the link, non-web scheme refused |
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
| `HR-AUTH-002` | Google identity proof is invalid, expired, for the wrong audience/issuer, or fails nonce verification | 无法验证 Google 登录，请重新登录。 | Couldn't verify the Google sign-in. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-003` | Hermes GO account session expired and cannot be refreshed | 登录已过期，请重新登录。 | Your session expired. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-004` | Hermes GO account session or refresh family was revoked | 这台设备的登录已被撤销，请重新登录。 | This device's session was revoked. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-005` | A rotated refresh credential was reused; its token family was revoked | 检测到登录凭据重复使用，为保护账号已退出这台设备。 | Reuse of a sign-in credential was detected, so this device was signed out for safety. | No (interactive sign-in) |
| `HR-AUTH-006` | A destructive account/binding operation requires recent Google reauthentication | 为确认是你本人，请重新验证 Google 账号。 | Verify your Google account again to confirm it's you. | No (interactive reauthentication) |
| `HR-AUTH-007` | Google proof exchange or session refresh exceeded the per-source request limit | 登录请求过于频繁，请稍候再试。 | Too many sign-in requests. Wait a moment and try again. | Yes |
| `HR-AUTH-008` | Desktop system-browser sign-in was cancelled, timed out, could not open, or returned to an invalid/unowned callback | Google 登录未完成，请重新尝试。 | Google sign-in did not finish. Try again. | Yes (interactive sign-in) |
| `HR-ACCOUNT-001` | Hermes GO account is disabled | 此 Hermes GO 账号当前不可用，请联系支持。 | This Hermes GO account is currently unavailable. Contact support. | No |
| `HR-ACCOUNT-002` | Hermes GO account service or transactional store is temporarily unavailable | 账号服务暂时不可用，请稍后重试。 | The account service is temporarily unavailable. Try again shortly. | Yes |
| `HR-ACCOUNT-003` | Account-mode endpoints are disabled by the Gateway feature flag | 此 Relay 尚未启用账号登录，可继续使用原有连接方式。 | Account sign-in is not enabled on this Gateway yet. Continue with the legacy connection. | No (continue legacy) |
| `HR-ACCOUNT-004` | Account-mode request path, method, content type, or bounded JSON input is invalid | 账号请求格式无效，请更新客户端或重试。 | The account request is invalid. Update the client or try again. | No |
| `HR-ACCOUNT-005` | An account-scoped idempotency key was reused for a different operation input | 此重试标识已用于另一项请求，请重新发起操作。 | That retry key was already used for a different account request. | No (start a new operation) |
| `HR-ACCOUNT-006` | An account-owned installation/binding target is absent or belongs to another account | 找不到这个账号下的目标设备。 | The requested account resource was not found. | No |
| `HR-ACCOUNT-007` | A phone attempted a Desktop-only management operation, or the Desktop identity did not match the authenticated installation | 此操作只能在当前登录的 Hermes Go Desktop 上完成。 | This operation is available only from Hermes Go Desktop. | No |
| `HR-BIND-001` | Account has no active Desktop Connector binding | 这个账号还没有连接 Desktop，请先在 Mac 上打开 Hermes Go Desktop。 | This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac. | Yes |
| `HR-BIND-002` | Account already has another active Desktop Connector binding | 这个账号已经连接另一台 Mac；确认替换前，原连接会继续工作。 | This account is already connected to another Mac. The existing connection will keep working until replacement is confirmed. | No (verify and replace) |
| `HR-BIND-003` | First-binding or replacement request expired, or a single-use confirmation was consumed | Desktop 绑定确认已失效，请重新开始。 | The Desktop binding confirmation expired. Start again. | Yes (restart binding/replacement) |
| `HR-BIND-004` | This phone installation was revoked | 这台手机的访问已被移除，请重新登录。 | Access for this phone was removed. Sign in again. | No (interactive sign-in) |
| `HR-BIND-005` | Connector challenge proof, key generation, or active binding validation failed | Desktop Connector 身份验证失败，请在 Mac 上检查账号与设备。 | Desktop Connector authentication failed. Check Account & Devices on the Mac. | Yes |
| `HR-BIND-006` | This Desktop Connector binding was replaced or explicitly revoked | 这台 Mac 已不再绑定当前账号，请重新绑定或使用现有 Mac。 | This Mac is no longer bound to the account. Bind it again or use the current Mac. | No |
| `HR-BIND-007` | Connector replacement failed before commit and the original binding remains active | 未能更换 Mac，原来的连接仍在工作。 | Couldn't replace the Mac. The original connection is still working. | Yes |
| `HR-BIND-008` | Account authentication is available but the binding control plane is still disabled | 此 Relay 尚未启用 Desktop 绑定，可继续使用原有连接。 | Desktop binding isn't enabled on this Gateway yet. Continue with the legacy connection. | No (continue legacy) |
| `HR-MIGRATE-001` | Legacy-to-account Connector migration preflight failed before mutation | 暂时无法升级连接，现有连接未被修改。 | The connection can't be upgraded yet. The existing connection was not changed. | Yes |
| `HR-MIGRATE-002` | Migration detected multiple, unknown, or mismatched Connector processes/ownership | 检测到异常的 Connector 运行状态，已停止升级以避免重复连接。 | An unexpected Connector state was found. Upgrade was stopped to prevent duplicate connections. | No (inspect diagnostics) |
| `HR-MIGRATE-003` | Account-mode candidate failed authentication/health before commit and automatic rollback restored legacy | 新连接验证失败，已恢复原来的连接。 | The new connection failed validation, so the original connection was restored. | Yes |
| `HR-MIGRATE-004` | Automatic rollback could not restore a known-good Connector and stopped to avoid a retry loop | 自动恢复未完成，请按诊断步骤修复 Connector；Hermes 未被修改。 | Automatic recovery did not finish. Follow the diagnostic steps to repair the Connector; Hermes was not changed. | No (manual recovery) |
| `HR-RPC-001` | Gateway RPC returned an unmapped remote error | Relay 请求失败，请查看详情后重试。 | The Relay request failed. Review the details and retry. | Depends |
| `HR-RPC-002` | Gateway RPC response timed out | Relay 响应超时，请稍后重试。 | The Relay response timed out. Try again shortly. | Yes |
| `HR-RPC-003` | Model catalog could not be loaded | 无法加载模型列表，请重试。 | Couldn't load the model list. Retry. | Yes |
| `HR-RPC-004` | Switching the conversation's session model failed | 无法切换本会话的模型，请重试。 | Couldn't switch this conversation's model. Retry. | Yes |
| `HR-RPC-005` | Setting the default model failed | 无法设置默认模型，请重试。 | Couldn't set the default model. Retry. | Yes |
| `HR-RPC-006` | Changing the conversation's reasoning effort failed | 无法调整推理强度，请重试。 | Couldn't change the reasoning effort. Retry. | Yes |
| `HR-CONFIG-001` | Configuration could not be loaded | 无法加载配置，请重试。 | Couldn't load the configuration. Retry. | Yes |
| `HR-CONFIG-002` | Configuration could not be saved | 无法保存配置，请重试。 | Couldn't save the configuration. Retry. | Yes |
| `HR-CONFIG-003` | Relay URL is invalid | Relay 地址格式无效，请检查后重试。 | The Relay URL is invalid. Check it and retry. | Yes |
| `HR-CONFIG-004` | Desktop pairing configuration is missing its local name, Relay URL, or App Token | 请填写配置名称、Relay 地址和 App Token。 | Enter a configuration name, Relay URL, and App Token. | Yes |
| `HR-CONFIG-005` | Relay URL and App Token exceed the reliable v1 QR payload limit | Relay 地址和 App Token 过长，无法生成可扫描的二维码。 | The Relay URL and App Token are too long to fit in a scannable QR code. | Yes |
| `HR-CONFIG-006` | Desktop account mode has no valid Google macOS OAuth client configuration | 此版本尚未配置 Google 登录，请继续使用原有连接。 | Google sign-in is not configured in this build. Continue with the legacy connection. | No (continue legacy) |
| `HR-STORE-001` | Per-profile identity settings (display name, avatar photo, colour, style) could not be written to DataStore | 无法保存身份设置，请重试。 | Couldn't save the profile settings. Retry. | Yes |
| `HR-UPDATE-001` | Unmapped update check, download, verification, or installer failure | 更新操作失败，请重试。 | The update operation failed. Retry. | Yes |
| `HR-UPDATE-002` | Update index could not be fetched or parsed | 无法检查更新，请检查网络后重试。 | Couldn't check for updates. Check your network and retry. | Yes |
| `HR-UPDATE-003` | DownloadManager job could not be enqueued or persisted | 无法开始下载更新，请重试。 | Couldn't start the update download. Retry. | Yes |
| `HR-UPDATE-004` | DownloadManager reported a failed download | 更新下载失败，请重试。 | The update download failed. Retry. | Yes |
| `HR-UPDATE-005` | Downloaded APK failed size/hash/identity/signature verification | 安装包校验未通过，已阻止安装，请重新下载。 | The package failed verification and was blocked. Download it again. | Yes (re-download only; installation stays blocked) |
| `HR-UPDATE-006` | Persisted download record or completed file is missing | 下载记录已丢失，请重新下载。 | The download record was lost. Download the update again. | Yes |
| `HR-UPDATE-007` | System package installer could not be opened | 无法打开系统安装器，请重试。 | Couldn't open the system installer. Retry. | Yes |
| `HR-UPDATE-008` | DownloadManager job, persisted metadata, or residual APK could not be cleaned up | 无法清理更新下载，请重试。 | Couldn't clean up the update download. Retry. | Yes |
| `HR-UPDATE-009` | A restored/downloaded APK is no longer the manifest's latest release | 已发布更新版本，请删除旧下载后获取最新版。 | A newer release is available. Delete the old download and get the latest version. | No (delete old download, then download latest) |
| `HR-RELEASE-001` | Gateway image prerequisites, source cleanliness, dependency build, or release packaging gate failed | 无法生成可验证的 Gateway 镜像，请检查构建环境和源码状态。 | Couldn't build a verifiable Gateway image. Check the build environment and source state. | Yes (inspect details, fix prerequisites, retry) |
| `HR-RELEASE-002` | Gateway candidate image identity, architecture, isolation, startup, readiness, or Connector attachment check failed | Gateway 候选镜像未通过身份、隔离或就绪检查。 | The Gateway candidate image failed its identity, isolation, or readiness checks. | Yes (inspect details and retry) |
| `HR-RELEASE-003` | Gateway candidate image REST, WebSocket, authentication, or release-contract smoke failed | Gateway 候选镜像的端到端验证失败，请检查诊断后重试。 | The Gateway candidate image failed end-to-end verification. Review diagnostics and retry. | Yes (inspect details and retry) |
| `HR-OPS-001` | Cloud Ops configuration, host platform, dependency, input-file safety, or preflight requirement is invalid | Cloud Ops 配置或主机前置条件无效，请修正后重试。 | The Cloud Ops configuration or host prerequisites are invalid. Fix them and retry. | Yes (fix configuration/prerequisite, retry) |
| `HR-OPS-002` | OCI bundle manifest, archive hash, image identity, or architecture verification failed | Gateway 制品身份或完整性校验失败，已阻止安装。 | Gateway artifact identity or integrity verification failed, so installation was blocked. | No (replace the artifact) |
| `HR-OPS-003` | Staging bootstrap, stage recovery, managed-file installation, service start, or smoke did not complete | Staging 初始化未完成，请检查阶段状态后安全重试。 | Staging bootstrap did not complete. Inspect its stage and retry safely. | Yes (inspect recorded stage, retry the same configuration) |
| `HR-OPS-004` | One or more systemd, Nginx, container, image, liveness, or readiness status layers are degraded | Staging 服务未全部就绪，请查看分层状态。 | Not all staging services are ready. Review the layered status. | Yes (inspect status and retry) |
| `HR-OPS-005` | A bounded allowlist-only diagnostic bundle could not be created safely | 无法生成安全的诊断包，请检查输出位置后重试。 | Couldn't create a safe diagnostic bundle. Check the output location and retry. | Yes (check output and retry) |
| `HR-OPS-006` | Source and target Gateway release versions, schemas, protocols, or rollback policy are incompatible | 源版本与目标 Gateway 发布合同不兼容，请选择可升级或可回滚的版本。 | The source and target Gateway release contracts are incompatible. Select a compatible upgrade or rollback version. | No (select a compatible release) |
| `HR-OPS-007` | Candidate slot preparation, deployment journal, or deployment lock did not complete before public routing changed | Gateway 候选版本准备未完成，旧服务保持不变。请检查部署阶段后重试。 | Gateway candidate preparation did not complete; the existing service was left unchanged. Inspect the deployment stage and retry. | Yes (inspect the deployment stage and retry) |
| `HR-OPS-008` | Lifecycle-state handoff, public route switch, observation, or automatic recovery did not complete | Gateway 路由切换未完成，已尝试恢复原服务。请检查恢复状态。 | The Gateway route switch did not complete. Recovery of the existing service was attempted. Inspect the recovery state. | Yes (inspect the recovery state and retry) |
| `HR-OPS-009` | PostgreSQL version validation, advisory migration lock, ordered migration, or exact schema verification did not complete | Gateway 数据库迁移或版本校验未完成，已阻止发布。请检查数据库状态后重试。 | The Gateway database migration or version check did not complete, so the release was blocked. Inspect the database state and retry. | Yes (inspect database state and retry; public routing remains unchanged) |
| `HR-OPS-010` | One or more read-only production-promotion gates for host identity, resources, artifact, legacy rollback, loopback routing, Docker, PostgreSQL, or off-host restore evidence are incomplete | 生产晋级前置门禁尚未全部通过，线上服务保持不变。请补齐阻断项后重新审计。 | Production promotion gates are incomplete; the live service was left unchanged. Resolve the blockers and audit again. | Yes (resolve the reported gates and rerun the read-only audit) |
| `HR-OPS-011` | Legacy Gateway capture, encrypted artifact validation, file restoration, or isolated service compatibility smoke did not complete | 旧 Gateway 恢复制品的捕获或隔离验证未完成，线上服务保持不变。请检查恢复阶段后重试。 | Legacy Gateway recovery capture or isolated verification did not complete; the live service was left unchanged. Inspect the recovery stage and retry. | Yes (inspect the reported recovery stage and retry; the live service remains unchanged) |
| `HR-OPS-012` | Production root disk is below its warning threshold, or the encrypted PostgreSQL backup status is missing, invalid, stale, not confirmed off-host, or mismatched | 生产主机磁盘或数据库备份监控发现异常，请检查告警项并尽快处理。 | Production disk or database-backup monitoring found a problem. Inspect the alert and resolve it promptly. | Yes (inspect the local high-priority alert, resolve its reported condition, and rerun the read-only monitor) |
| `HR-OPS-013` | PostgreSQL encrypted backup, immutable-artifact account smoke, or off-host restore verification did not complete; valid backup status remains unpublished | PostgreSQL 加密备份或异机恢复验证未完成，未更新有效备份状态。请检查失败阶段后重试。 | PostgreSQL encrypted backup or off-host restore verification did not complete, so no valid backup status was published. Inspect the failed stage and retry. | Yes (inspect the failed database-recovery stage and retry; do not enable the production monitor timer until a valid status is installed) |
| `HR-OPS-014` | Production managed-baseline admission, legacy identity binding, candidate adoption, route switch, or automatic legacy recovery did not complete | 生产 Gateway 受管基线接管未完成，已阻止切换或尝试恢复旧服务。请检查接管阶段后重试。 | The managed production Gateway baseline was not established. The switch was blocked or legacy recovery was attempted. Inspect the adoption stage and retry. | Yes (inspect the adoption journal and verified legacy rollback point before retrying) |
| `HR-FILE-001` | A selected attachment could not be read | 无法读取所选文件，请重新选择。 | Couldn't read the selected file. Choose it again. | Yes |
| `HR-FILE-002` | An exported transcript file could not be written or shared | 无法生成对话文件，请重试。 | Couldn't create the transcript file. Retry. | Yes |
| `HR-FILE-003` | A Hermes-delivered artifact resolved outside `FILES_ROOT`, or the Mac refused to open it (Connector 403) | 这个文件不在 Mac 允许访问的目录内，无法下载。请让 Hermes 把它放到允许的目录。 | The file sits outside the folder the Mac allows, so it can't be downloaded. Ask Hermes to place it inside that folder. | No (move the file, or widen `FILES_ROOT`) |
| `HR-FILE-004` | A Hermes-delivered artifact exceeds `MAX_FILE_BYTES` (Connector 413) | 文件超过传输上限，无法下载。请让 Hermes 压缩或拆分后再发。 | The file exceeds the transfer limit. Ask Hermes to compress or split it. | No (compress or split the artifact) |
| `HR-FILE-005` | A Hermes-delivered artifact is gone or is not a regular file (Connector 404 / `invalid_file` / `invalid_path`) | 这个文件在 Mac 上已不存在，请让 Hermes 重新生成。 | The file is no longer on the Mac. Ask Hermes to produce it again. | No (ask Hermes to regenerate it) |
| `HR-FILE-006` | Artifact transfer failed for any other reason (network, timeout, unexpected status) | 文件下载失败，请重试。 | The download failed. Retry. | Yes |
| `HR-FILE-007` | The artifact downloaded, but no installed app can open its MIME type | 手机上没有能打开这种文件的应用。文件已下载，请改用「分享」保存到其他应用。 | No app on this phone can open this file type. It downloaded fine — use Share to save it elsewhere. | No (use Share to hand the file to another app) |
| `HR-MEDIA-001` | Image save, preparation, or share operation failed | 图片操作失败，请重试。 | The image operation failed. Retry. | Yes |
| `HR-MEDIA-003` | The transcript image could not be rendered or shared | 无法生成对话长图，请重试或改用 Markdown 文件。 | Couldn't render the transcript image. Retry, or share it as a Markdown file. | Yes |
| `HR-MEDIA-002` | A picked avatar photo could not be decoded, cropped, or encoded (ImageDecoder/BitmapFactory failure, unreadable URI, empty image) | 无法读取所选照片，请换一张再试。 | Couldn't read the selected photo. Try a different one. | Yes |
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
| `HR-SYNC-002` | Run stopped without a confirmed terminal state (Relay observed `run.interrupted`/`run.unknown`, or the phone marked it interrupted) | 任务停止了，但没有确认完成，请打开会话检查。 | The task stopped without a confirmed completion. Open the conversation to check. | No (open the conversation) |
| `HR-PERM-001` | Camera permission denied | 相机权限未开启，请前往系统设置允许。 | Camera permission is disabled. Allow it in system settings. | Yes |
| `HR-PERM-002` | Notification permission denied | 通知权限未开启，后台任务可能无法及时提醒。 | Notifications are disabled, so background alerts may be delayed. | Yes |
| `HR-NOTIF-001` | A notification action (approve/deny/reply/choice) could not be delivered to the gateway | 通知操作未能发送，请重试。 | The notification action couldn't be sent. Try again. | Yes |
| `HR-LINK-001` | A link in app content (an assistant answer, a setup guide link) could not be handed to any app (no browser or handler installed, or the launch was refused). The link is copied to the clipboard so it can still be used | 没有能打开链接的应用，链接已复制。 | No app can open this link. It was copied to the clipboard. | No (paste the link elsewhere) |
| `HR-LINK-002` | A link in app content is not an openable web address: its scheme is outside the http/https/mailto/tel allowlist, or it has no scheme at all (a relative or anchor-only target). Refused before reaching the system, so a crafted `intent:`/`file:` target cannot launch anything | 这个链接无法打开。 | This link can't be opened. | No |
| `HR-SEARCH-001` | Gateway message search (`/api/sessions/search`) failed: transport error, non-2xx response, or unparseable body. The title matches on the search screen stay; only the message section shows the error with Retry | 消息搜索失败，请重试。 | Message search failed. Retry. | Yes |
| `HR-UNKNOWN-001` | Unmapped boundary failure | 出现未知错误，请复制诊断信息协助定位。 | An unknown error occurred. Copy diagnostics to help investigate. | Depends |


### Artifact download failures (decision 2026-09-05)

`HR-FILE-001` means an *outgoing* attachment the user picked could not be read. It must not be
reused for an *incoming* artifact Hermes delivered — that reversed the direction of the reported
problem. Downloads now map onto `HR-FILE-003`–`HR-FILE-007` at the boundary
(`data/error/ArtifactErrors.kt`), which keeps a permission problem distinguishable from a transfer
problem and from "this phone has no viewer".

The Connector logs every rejected `GET /api/files` with its status and reason. It deliberately logs
only the requested path's extension and length: a refused download previously left no trace on
either side, so diagnosing one meant reading `FILES_ROOT` by hand, while logging the path itself
would put the Mac's directory layout into shipped diagnostics.

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
