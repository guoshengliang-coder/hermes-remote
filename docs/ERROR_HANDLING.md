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
| `CLARIFY` | The agent's structured questions | answer landed on an expired request |
| `APPROVAL` | The agent's permission requests | answer landed on a timed-out request, request lost with the process |
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
| `FEEDBACK` | In-app feedback reporting to MissionGo | not configured, submission failed, token rejected, rate limited |
| `CRON` | Scheduled jobs | run delivered nowhere, schedule/trigger failure |
| `MSG` | Messaging channels (DingTalk, Slack, …) | list/save failure, profile conflict, platform not connected, gateway restart |
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
- A non-retryable failure must not carry a retry affordance. `retryable = false` means the tap is
  withheld, not merely discouraged: the bubble shows no "点按重试" copy, takes no click, and the
  ViewModel refuses to re-dispatch it. An offer that cannot work is worse than none, because the
  user keeps paying for it (HG-29: five taps, five identical 4001/4007 pairs).
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
| `HR-CONN-001` | Device has no usable network **and the gateway probe also failed** (a capability read alone never decides — see docs/DESIGN.md) | 当前网络不可用，请检查网络连接。 | No usable network is available. Check your connection. | Yes |
| `HR-CONN-002` | WebSocket connection failed | 无法连接 Relay，将自动重试。 | Couldn't connect to the Relay. Retrying automatically. | Yes |
| `HR-CONN-003` | `gateway.ready` handshake timed out | Relay 已连接，但会话握手超时。 | The Relay connected, but the session handshake timed out. | Yes |
| `HR-CONN-004` | Connection was interrupted during an operation | 连接中断，正在恢复会话。 | The connection was interrupted. Restoring the conversation. | Yes |
| `HR-CONN-005` | Relay is reachable but the Mac Connector is offline | Mac 端当前离线，请启动 Hermes Go Desktop。 | The Mac is offline. Start Hermes Go Desktop. | Yes |
| `HR-AUTH-001` | App Token rejected | App Token 无效或已失效，请重新配置。 | The App Token is invalid or expired. Configure it again. | No |
| `HR-AUTH-002` | Google identity proof is invalid, expired, for the wrong audience/issuer, or fails nonce verification | 无法验证 Google 登录，请重新登录。 | Couldn't verify the Google sign-in. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-003` | Hermes GO account session expired and cannot be refreshed | 登录已过期，请重新登录。 | Your session expired. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-004` | Hermes GO account session or refresh family was revoked | 这台设备的登录已被撤销，请重新登录。 | This device's session was revoked. Sign in again. | No (interactive sign-in) |
| `HR-AUTH-005` | A rotated refresh credential was reused; its token family was revoked | 检测到登录凭据重复使用，为保护账号已退出这台设备。 | Reuse of a sign-in credential was detected, so this device was signed out for safety. | No (interactive sign-in) |
| `HR-AUTH-006` | A destructive account/binding operation requires recent reauthentication of an identity already linked to the current account | 为确认是你本人，请重新验证当前账号的登录方式。 | Verify your sign-in identity again to confirm it's you. | No (interactive reauthentication) |
| `HR-AUTH-007` | Google proof exchange or session refresh exceeded the per-source request limit | 登录请求过于频繁，请稍候再试。 | Too many sign-in requests. Wait a moment and try again. | Yes |
| `HR-AUTH-008` | Desktop system-browser sign-in was cancelled, timed out, could not open, or returned to an invalid/unowned callback | Google 登录未完成，请重新尝试。 | Google sign-in did not finish. Try again. | Yes (interactive sign-in) |
| `HR-AUTH-009` | Email OTP is malformed, mismatched, expired, consumed, delivery-invalidated, or has exhausted its attempts | 邮箱验证码无效或已过期，请重新获取验证码。 | The email code is invalid or expired. Request a new code. | No (request a new code) |
| `HR-AUTH-010` | Transactional provider did not accept the email OTP message; the challenge was invalidated | 登录邮件发送失败，请稍后重试。 | The sign-in email couldn't be sent. Try again shortly. | Yes |
| `HR-AUTH-011` | Email OTP endpoints are disabled by their independent Gateway feature flag | 此 Relay 尚未启用邮箱登录，请使用其他可用登录方式或原有连接方式。 | Email sign-in isn't enabled on this Gateway yet. Use another available method or the legacy connection. | No (choose another sign-in method) |
| `HR-AUTH-012` | A Web mutation has a missing/foreign Origin, cross-site Fetch Metadata, malformed/duplicate cookie, or missing/mismatched CSRF token | 无法验证此浏览器请求，请重新加载账号页面后重试。 | This browser request couldn't be verified. Reload the account page and try again. | No (reload/sign in) |
| `HR-AUTH-013` | The Google Identity Services browser library is unavailable or did not initialize | Google 登录暂不可用，请使用邮箱或刷新页面重试。 | Google sign-in is unavailable. Use email or reload the page. | Yes (reload or use email) |
| `HR-ACCOUNT-001` | Hermes GO account is disabled | 此 Hermes GO 账号当前不可用，请联系支持。 | This Hermes GO account is currently unavailable. Contact support. | No |
| `HR-ACCOUNT-002` | Hermes GO account service or transactional store is temporarily unavailable | 账号服务暂时不可用，请稍后重试。 | The account service is temporarily unavailable. Try again shortly. | Yes |
| `HR-ACCOUNT-003` | Account-mode endpoints are disabled by the Gateway feature flag | 此 Relay 尚未启用账号登录，可继续使用原有连接方式。 | Account sign-in is not enabled on this Gateway yet. Continue with the legacy connection. | No (continue legacy) |
| `HR-ACCOUNT-004` | Account-mode request path, method, content type, or bounded JSON input is invalid | 账号请求格式无效，请更新客户端或重试。 | The account request is invalid. Update the client or try again. | No |
| `HR-ACCOUNT-005` | An account-scoped idempotency key was reused for a different operation input | 此重试标识已用于另一项请求，请重新发起操作。 | That retry key was already used for a different account request. | No (start a new operation) |
| `HR-ACCOUNT-006` | An account-owned installation/binding target is absent or belongs to another account | 找不到这个账号下的目标设备。 | The requested account resource was not found. | No |
| `HR-ACCOUNT-007` | A phone attempted a Desktop-only management operation, or the Desktop identity did not match the authenticated installation | 此操作只能在当前登录的 Hermes Go Desktop 上完成。 | This operation is available only from Hermes Go Desktop. | No |
| `HR-ACCOUNT-008` | A freshly verified Google/email identity is already attached to another internal account | 此登录方式已属于另一个 Hermes GO 账号。 | That sign-in identity already belongs to another Hermes GO account. | No |
| `HR-ACCOUNT-009` | Identity-management endpoints or the Web account shell are disabled by their independent rollout flag | 此 Relay 尚未启用登录方式管理。 | Identity management isn't enabled on this Gateway yet. | No |
| `HR-ACCOUNT-010` | Secure Web account-session endpoints are disabled by their independent rollout flag | 此 Relay 尚未启用安全网页账号会话。 | Secure Web account sessions aren't enabled on this Gateway yet. | No |
| `HR-ACCOUNT-011` | Identity removal would leave the account without any usable sign-in identity | 此账号必须至少保留一种登录方式，请先绑定其他登录方式。 | Keep at least one sign-in identity on this account. Link another sign-in method first. | No (link another identity) |
| `HR-ACCOUNT-012` | A verified identity or retained session belongs to an account already in permanent-deletion state | 此 Hermes GO 账号正在永久删除，已无法再次登录。 | This Hermes GO account is being permanently deleted and can no longer sign in. | No |
| `HR-BIND-001` | Account has no active Desktop Connector binding | 这个账号还没有连接 Desktop，请先在 Mac 上打开 Hermes Go Desktop。 | This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac. | Yes |
| `HR-BIND-002` | Account already has another active Desktop Connector binding | 这个账号已经连接另一台 Mac；确认替换前，原连接会继续工作。 | This account is already connected to another Mac. The existing connection will keep working until replacement is confirmed. | No (verify and replace) |
| `HR-BIND-003` | First-binding or replacement request expired, or a single-use confirmation was consumed | Desktop 绑定确认已失效，请重新开始。 | The Desktop binding confirmation expired. Start again. | Yes (restart binding/replacement) |
| `HR-BIND-004` | This phone installation was revoked | 这台手机的访问已被移除，请重新登录。 | Access for this phone was removed. Sign in again. | No (interactive sign-in) |
| `HR-BIND-005` | Connector challenge proof, key generation, or active binding validation failed | Desktop Connector 身份验证失败，请在 Mac 上检查账号与设备。 | Desktop Connector authentication failed. Check Account & Devices on the Mac. | Yes |
| `HR-BIND-006` | This Desktop Connector binding was replaced or explicitly revoked | 这台 Mac 已不再绑定当前账号，请重新绑定或使用现有 Mac。 | This Mac is no longer bound to the account. Bind it again or use the current Mac. | No |
| `HR-BIND-007` | Connector replacement failed before commit and the original binding remains active | 未能更换 Mac，原来的连接仍在工作。 | Couldn't replace the Mac. The original connection is still working. | Yes |
| `HR-BIND-008` | Account authentication is available but the binding control plane is still disabled | 此 Relay 尚未启用 Desktop 绑定，可继续使用原有连接。 | Desktop binding isn't enabled on this Gateway yet. Continue with the legacy connection. | No (continue legacy) |
| `HR-BIND-009` | More than one accessible Mac exists but a device-scoped request did not identify one | 请先选择要使用的 Mac，再打开此内容。 | Choose which Mac to use before opening this content. | No (select a device) |
| `HR-BIND-010` | An atomic bind attempt would exceed the three-owned-Mac limit | 此账号已达到三台自有 Mac 的上限，请先移除一台。 | This account already owns the maximum of three Macs. Remove one before adding another. | No |
| `HR-BIND-011` | The requested opaque device ID is inactive, absent, or not owned by the authenticated account | 此 Mac 已无法由当前账号使用，请选择其他设备。 | That Mac is no longer available to this account. Choose another device. | No (select another device) |
| `HR-SHARE-001` | Whole-device sharing endpoints are disabled by their independent rollout flag | 此 Relay 尚未启用设备共享。 | Device sharing isn't enabled on this Gateway yet. | No |
| `HR-SHARE-002` | Accepting another grant would exceed the five-active-grantees-per-device limit | 此 Mac 已共享给五个账号，请先撤销一个共享。 | This Mac is already shared with five accounts. Revoke one share first. | No (manage sharing) |
| `HR-SHARE-003` | Accepting another device would exceed the ten-active-shared-devices-per-account limit | 你的账号已接受十台共享 Mac，请先退出一台。 | Your account already has ten shared Macs. Leave one first. | No (manage sharing) |
| `HR-SHARE-004` | A share invitation token is malformed, expired, cancelled, consumed, or no longer targets an active binding | 此共享邀请无效、已过期或已被取消，请让设备所有者重新邀请。 | This invitation is invalid, expired, or cancelled. Ask the device owner to invite you again. | No (request another invitation) |
| `HR-SHARE-005` | The authenticated account has no verified identity matching the invitation mailbox | 请使用收到邀请的已验证邮箱登录此账号。 | Sign in with the verified email address that received the invitation. | No (use the invited identity) |
| `HR-SHARE-006` | A share creation or acceptance omitted the exact whole-device disclosure acknowledgement | 请先确认被邀请者可访问这台 Hermes 暴露的会话、文件和配置。 | Confirm that the invited account may access sessions, files, and configuration exposed by this Hermes. | No (review sharing disclosure) |
| `HR-SHARE-007` | The same owner/recipient invitation or grant already exists, is a self-share, or conflicts with current state | 此邀请或共享已存在，或目标账号不符合共享条件。 | This invitation or share already exists, or the target account isn't eligible. | No (manage sharing) |
| `HR-SHARE-008` | The transactional provider did not accept the device-sharing invitation email; the pending invitation remains retryable | 邀请邮件发送失败；邀请已安全保留，请稍后使用相同邮箱重试。 | The invitation email wasn't sent; the invitation was safely retained. Retry with the same email shortly. | Yes (retry the same invitation) |
| `HR-MIGRATE-001` | Legacy-to-account Connector migration preflight failed before mutation | 暂时无法升级连接，现有连接未被修改。 | The connection can't be upgraded yet. The existing connection was not changed. | Yes |
| `HR-MIGRATE-002` | Migration detected multiple, unknown, or mismatched Connector processes/ownership | 检测到异常的 Connector 运行状态，已停止升级以避免重复连接。 | An unexpected Connector state was found. Upgrade was stopped to prevent duplicate connections. | No (inspect diagnostics) |
| `HR-MIGRATE-003` | Account-mode candidate failed authentication/health before commit and automatic rollback restored legacy | 新连接验证失败，已恢复原来的连接。 | The new connection failed validation, so the original connection was restored. | Yes |
| `HR-MIGRATE-004` | Automatic rollback could not restore a known-good Connector and stopped to avoid a retry loop | 自动恢复未完成，请按诊断步骤修复 Connector；Hermes 未被修改。 | Automatic recovery did not finish. Follow the diagnostic steps to repair the Connector; Hermes was not changed. | No (manual recovery) |
| `HR-MIGRATE-005` | Migration committed, but its private temporary download workspace could not be removed | 新连接已生效，但下载临时文件尚未清理。请重试清理；不要重复安装。 | The new connection is active, but temporary download files still need cleanup. Retry cleanup; do not install again. | Yes (retry cleanup only) |
| `HR-RPC-001` | Gateway RPC returned an unmapped remote error | Relay 请求失败，请查看详情后重试。 | The Relay request failed. Review the details and retry. | Depends |
| `HR-RPC-002` | Gateway RPC response timed out | Relay 响应超时，请稍后重试。 | The Relay response timed out. Try again shortly. | Yes |
| `HR-RPC-003` | Model catalog could not be loaded | 无法加载模型列表，请重试。 | Couldn't load the model list. Retry. | Yes |
| `HR-RPC-004` | Switching the conversation's session model failed — the switch itself was refused (bad credentials, unknown model). Does **not** cover a slash worker that never started; that is `HR-RPC-007` | 无法切换本会话的模型，请重试。 | Couldn't switch this conversation's model. Retry. | Yes |
| `HR-RPC-005` | Setting the default model failed | 无法设置默认模型，请重试。 | Couldn't set the default model. Retry. | Yes |
| `HR-RPC-006` | Changing the conversation's reasoning effort failed | 无法调整推理强度，请重试。 | Couldn't change the reasoning effort. Retry. | Yes |
| `HR-RPC-007` | The Mac's Hermes could not run a slash command at all — its slash worker died on spawn (`slash.exec` 5030). Every slash command is affected, the model switch among them, so this is a broken Hermes install rather than a refused switch, and retrying cannot help | Mac 上的 Hermes 无法执行命令，请查看详情。 | The Hermes on your Mac can't run commands. See the details. | No |
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
| `HR-RELEASE-004` | Desktop managed release archive packaging, Ed25519 signing, or local artifact integrity verification failed | Desktop 受管发布包未通过生成、签名或完整性校验。 | The Desktop managed release failed packaging, signing, or integrity verification. | Yes (inspect details, fix the release input, and retry) |
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
| `HR-OPS-015` | Production PostgreSQL role/database initialization, least-privilege verification, or atomic URL installation did not complete | PostgreSQL 生产数据库初始化未完成，账号功能保持关闭。请检查初始化阶段后重试。 | Production PostgreSQL initialization did not complete; account features remain disabled. Inspect the initialization stage and retry. | Yes (inspect the initialization stage and retry; account features remain disabled) |
| `HR-OPS-016` | Routine production Gateway release (R5-F1) admission, slot-to-slot candidate, route switch, or automatic restore of the current release did not complete | 生产 Gateway 常规发版未完成，已阻止切换或已恢复当前版本。请检查发版阶段后重试。 | The routine production Gateway release did not complete. The switch was blocked or the current release was restored. Inspect the release stage and retry. | Yes (inspect the deployment journal and the `previous` rollback point before retrying) |
| `HR-OPS-017` | Staging transactional-email submission, signed webhook receipt, or expected final-delivery aggregate did not complete | Staging 邮件发送或最终投递验收未完成，请检查邮件配置、Webhook 和聚合指标后重试。 | Staging email submission or final-delivery acceptance did not complete. Check mail configuration, the webhook, and aggregate metrics before retrying. | Yes (inspect email delivery and retry in isolated staging) |
| `HR-OPS-018` | Public SPF TXT, Return-Path MX, DKIM TXT, or DMARC TXT records do not match the reviewed mail-domain contract | 邮件域名的 SPF、DKIM、DMARC 公共记录尚未通过验收，请修正 DNS 后重试。 | The mail domain's public SPF, DKIM, and DMARC records did not pass acceptance. Fix DNS and rerun the read-only audit. | Yes (fix DNS and rerun the read-only audit) |
| `HR-OPS-019` | The bounded account-retention sweep could not complete; the login service remains available and the next scheduled sweep will retry | 账号数据定期清理未完成，登录服务仍可使用，系统将在下一周期重试。 | Account-data maintenance did not complete. Login remains available, and the system will retry on the next cycle. | Yes (inspect the private retention snapshot and database health) |
| `HR-OPS-020` | Production email-login migration, secret installation, restart, smoke verification, or restoration of the disabled state did not complete | 生产邮箱登录灰度启用未完成，已阻止启用或恢复为账号关闭。请检查灰度阶段后重试。 | The production email-login rollout did not complete. Enablement was blocked or account mode was restored to disabled. Inspect the rollout stage and retry. | Yes (inspect the protected rollout journal and retry only after confirming account mode is disabled) |
| `HR-OPS-021` | Production single-Mac binding/Desktop-bootstrap enablement, route installation, restart, smoke verification, or restoration of email-only mode did not complete | 生产 Desktop 绑定灰度未完成，已阻止启用或恢复为邮箱登录状态。请检查灰度阶段后重试。 | The production Desktop-binding rollout did not complete. Enablement was blocked or email-only mode was restored. Inspect the rollout stage and retry. | Yes (inspect the protected binding-rollout journal and retry only after confirming email-only mode is restored) |
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
| `HR-MEDIA-004` | An image could not be decoded for viewing or editing: pixel count over budget, `BitmapFactory` failure, or `OutOfMemoryError` while decoding | 无法打开这张图片，可能已损坏或过大。请换一张再试。 | Couldn't open this image — it may be damaged or too large. Try a different one. | Yes |
| `HR-MEDIA-005` | An edited image could not be composited or encoded, or stayed above the 6 MB attachment cap after the quality ladder | 编辑结果保存失败，请重试；你的修改仍在屏幕上。 | Couldn't save the edited image. Retry — your edits are still on screen. | Yes |
| `HR-PERM-003` | Android blocks installation from this source | 需要允许安装未知应用，授权后请重试。 | Permission to install unknown apps is required. Grant it and retry. | Yes |
| `HR-SESS-001` | Session no longer exists. Also the send path's terminal outcome: upstream reclaimed the conversation (`session.reclaimed`, or `session.resume` → 4007) and it held history, so it could not be silently replaced. The bubble reads 未发送 with this code and offers **no** retry | 会话不存在或已被删除。 | The conversation no longer exists or was deleted. | No |
| `HR-SESS-002` | Live session handle is stale | 会话连接已失效，正在重新挂接。 | The live conversation handle expired. Reattaching now. | Yes |
| `HR-SESS-003` | Project folder for a move/create no longer exists on the Mac (`session.workspace.move` 4017, or a derived project without a known path) | 项目文件夹在 Mac 上不存在，请重新加载项目后重试。 | The project folder no longer exists on the Mac. Reload projects and retry. | Yes |
| `HR-SESS-004` | Session is mid-turn, so its project cannot be changed (`session.workspace.move` 4009) | 会话正在运行，无法移动项目，请等待完成后重试。 | The conversation is running, so its project can't be changed. Wait for it to finish and retry. | Yes |
| `HR-SESS-005` | Unmapped failure moving a session to another project | 无法移动会话到该项目，请重试。 | Couldn't move the conversation to that project. Retry. | Yes |
| `HR-SESS-007` | A user message could not be submitted (`prompt.submit`/attachment upload raised, or the live-handle wait timed out); the bubble stays on screen as 未发送 with tap-to-retry. Does **not** cover "the conversation is gone upstream" — that is `HR-SESS-001`, and offering a retry for it would be a lie — nor "another client is running it", which is `HR-SESS-013`: that retry does work, just not yet | 消息未发送，点按气泡重试。 | The message was not sent. Tap the bubble to retry. | Yes |
| `HR-SESS-006` | New session was requested in a project folder the Mac no longer has; the gateway created it in the default project instead | 项目文件夹在 Mac 上不存在，会话已建在默认项目。 | The project folder no longer exists on the Mac, so the conversation was created in the default project. | No |
| `HR-SESS-008` | Archiving a conversation from the chat screen failed (`PATCH /api/sessions/{id}` raised); the chat stays open and nothing was archived | 无法归档会话，请重试。 | Couldn't archive the conversation. Retry. | Yes |
| `HR-SESS-009` | A project edit named an id the gateway no longer has (`projects.*` 5062) — usually deleted from the Mac since the list was fetched | 项目已不存在，请重新加载。 | That project no longer exists. Reload the list. | No |
| `HR-SESS-010` | The gateway rejected a project name or folder as invalid (`projects.*` 5063), e.g. an empty name | 项目名称无效，请换一个。 | That project name isn't valid. Try another. | No |
| `HR-SESS-011` | Unmapped failure creating, renaming or removing a project (`projects.*` 5061) | 无法保存项目改动，请重试。 | Couldn't save the project change. Retry. | Yes |
| `HR-SESS-012` | The Mac could not list a folder while picking a project directory (`GET /api/fs/list` returned an error, or the request failed) | 无法读取该文件夹，请换一个位置。 | Couldn't read that folder. Try another location. | Yes |
| `HR-SESS-013` | Another client already owns the conversation, so upstream refused the prompt (`prompt.submit` 4090). The conversation still exists and the conflict clears as soon as the other side finishes, so the bubble keeps its tap — it just says why instead of offering a bare retry | 该会话正在另一个客户端上运行，请在那边结束后重试。 | This conversation is running on another client. Finish it there, then retry. | Yes |
| `HR-SESS-014` | Fetching another conversation's transcript failed while turning it into a Markdown attachment (「添加会话」, HG-38). Not `HR-SYNC-001`: nothing is out of sync and the open conversation is untouched — one conversation the user asked to reference could not be read. The conversations that did load still become attachments | 无法读取所选会话的内容，请重试。 | Couldn't read the selected conversation. Retry. | Yes |
| `HR-CLARIFY-001` | Clarify answer arrived after the request expired server-side | 这个提问已失效，agent 没有收到这次回答，请在输入框直接说明你的选择。 | The clarify question expired before the answer arrived; tell the agent your choice in the composer. | No |
| `HR-APPROVAL-001` | An approve/deny reached a request Hermes had already timed out and decided for itself. `approval.respond` returns nothing, so this is inferred: the run was confirmed over and its terminal predates the answer | 这次审批没有送达，Hermes 已按超时自行处置了这条命令，请在输入框重新说明。 | The approval didn't reach the agent — Hermes had already timed out and decided on its own. Ask again in the composer. | No |
| `HR-APPROVAL-002` | The conversation is waiting on an approval whose request did not survive the app restart. An approval carries no id and `approval.respond` addresses only the session, so a card rebuilt from a local snapshot could approve a command the user never saw; it is deliberately not restored | 这条会话在等你确认，但那次审批请求没能在 App 重启后保留下来，这里无法再批准它，请在输入框直接说明你的决定。 | This conversation is waiting for your approval, but the request didn't survive the app restart, so it can't be approved from here. Tell the agent your decision in the composer. | No |
| `HR-SYNC-001` | Final history reconciliation failed | 无法同步完整会话内容，请重试。 | Couldn't synchronize the complete conversation. Retry. | Yes |
| `HR-SYNC-002` | Run stopped without a confirmed terminal state (Relay observed `run.interrupted`/`run.unknown`, or the phone marked it interrupted) | 任务停止了，但没有确认完成，请打开会话检查。 | The task stopped without a confirmed completion. Open the conversation to check. | No (open the conversation) |
| `HR-PERM-001` | Camera permission denied | 相机权限未开启，请前往系统设置允许。 | Camera permission is disabled. Allow it in system settings. | Yes |
| `HR-PERM-002` | Notification permission denied | 通知权限未开启，后台任务可能无法及时提醒。 | Notifications are disabled, so background alerts may be delayed. | Yes |
| `HR-NOTIF-001` | A notification action (approve/deny/reply/choice) could not be delivered to the gateway | 通知操作未能发送，请重试。 | The notification action couldn't be sent. Try again. | Yes |
| `HR-LINK-001` | A link in app content (an assistant answer, a setup guide link) could not be handed to any app (no browser or handler installed, or the launch was refused). The link is copied to the clipboard so it can still be used | 没有能打开链接的应用，链接已复制。 | No app can open this link. It was copied to the clipboard. | No (paste the link elsewhere) |
| `HR-LINK-002` | A link in app content is not an openable web address: its scheme is outside the http/https/mailto/tel allowlist, or it has no scheme at all (a relative or anchor-only target). Refused before reaching the system, so a crafted `intent:`/`file:` target cannot launch anything | 这个链接无法打开。 | This link can't be opened. | No |
| `HR-SEARCH-001` | Gateway message search (`/api/sessions/search`) failed: transport error, non-2xx response, or unparseable body. The title matches on the search screen stay; only the message section shows the error with Retry | 消息搜索失败，请重试。 | Message search failed. Retry. | Yes |
| `HR-CRON-001` | A scheduled job ran successfully but its output never reached the target channel (Hermes reports `last_status = delivery_failed`; the cause is in `last_delivery_error` and `last_error` is null). The job itself did not fail, so the recovery is on the channel, not the job | 任务运行成功，但结果没能送到目标渠道。 | The task ran successfully, but its result could not be delivered to the target channel. | Yes |
| `HR-CRON-002` | A scheduled job's own last run failed (Hermes reports `last_status = error`/`failed` and puts the cause in `last_error`). The opposite of `HR-CRON-001`: the job is what broke, so the fix is the job — its prompt, its schedule, or whatever it calls — not the channel. The detail screen labelled this case `HR-RPC-001` until 2026-09-12, a transport code that said nothing about a schedule | 任务上次运行失败，请查看详情。 | The task's last run failed. Check the details. | Yes |
| `HR-MSG-001` | The messaging channel list could not be loaded (`GET /api/messaging/platforms` raised or returned non-2xx) | 无法加载消息渠道，请重试。 | Couldn't load messaging channels. Retry. | Yes |
| `HR-MSG-002` | Saving a channel's credentials or enabled flag failed (`PUT /api/messaging/platforms/{id}`) | 渠道设置未能保存，请重试。 | The channel settings couldn't be saved. Retry. | Yes |
| `HR-MSG-003` | Enabling the channel would break a multiplexed gateway because another profile already owns its listener (server returns 409) | 该渠道已被另一个身份占用，同一个渠道不能同时启用两次。 | Another profile already owns this channel; it can't be enabled twice at once. | No |
| `HR-MSG-004` | Hermes reports the platform as `startup_failed`: it is configured and enabled, but its adapter did not come up. The technical cause is the server's `error_message`, kept behind a details toggle | 这个渠道没能连上，请检查设置。 | This channel didn't connect. Check its setup. | No (fix the setup) |
| `HR-MSG-005` | Restarting the gateway failed (`POST /api/gateway/restart`), so channels saved as `pending_restart` stay disconnected | 网关重启失败，请重试。 | The gateway restart failed. Retry. | Yes |
| `HR-FEEDBACK-001` | The build carries no MissionGo endpoint/token, so the SDK was never initialized (a fresh clone, another machine, ordinary CI). Entry points are hidden in this state; the code exists for the boundary that is reached anyway | 这个版本没有开启反馈功能。 | Feedback is not enabled in this build. | No |
| `HR-FEEDBACK-002` | Submitting a report failed for any other reason — network, an unparseable response, an expired local draft, or a server code we do not special-case. Retryability comes from the SDK, which reports what it used for its own retries, rather than from a local table of codes | 反馈没有提交成功，请重试。 | The feedback wasn't submitted. Retry. | Depends (as reported) |
| `HR-FEEDBACK-003` | The feedback service refused the report's credentials (`http_401` / `http_403`): the SDK token was revoked, mistyped, or belongs to another product. Retrying cannot help; the build has to be fixed | 反馈服务拒绝了这次提交，请联系开发者。 | The feedback service rejected this report. Contact the developer. | No |
| `HR-FEEDBACK-004` | The feedback service applied its per-token rate limit (`http_429`) | 反馈提交过于频繁，请稍后再试。 | Too many reports just now. Try again shortly. | Yes |
| `HR-MSG-006` | **RETIRED (HG-34, 2026-09-12): the 转到消息渠道 handoff feature was deleted, so nothing in this repo emits this code any more. The number stays allocated and must never be reused.** Handoff refused because the conversation is mid-turn (gateway 4009). The move is queued only between turns, so the fix is to wait rather than retry immediately | 会话正在运行，等这一轮结束再转。 | The conversation is mid-turn. Wait for it to finish, then move it. | Yes (after the turn) |
| `HR-MSG-007` | **RETIRED (HG-34, 2026-09-12): the 转到消息渠道 handoff feature was deleted, so nothing in this repo emits this code any more. The number stays allocated and must never be reused.** Handoff refused because the destination channel is not enabled in the gateway (4025) | 这个渠道没有启用，先在消息渠道里开启。 | That channel isn't enabled. Turn it on under Messaging first. | No |
| `HR-MSG-008` | **RETIRED (HG-34, 2026-09-12): the 转到消息渠道 handoff feature was deleted, so nothing in this repo emits this code any more. The number stays allocated and must never be reused.** Handoff refused because the destination channel has no home channel (4026); scheduled delivery to it fails for the same reason | 这个渠道还没设默认投递落点，要先在目标聊天里用 /sethome 设置。 | That channel has no delivery target yet. Set one with /sethome in the destination chat. | No |
| `HR-MSG-009` | **RETIRED (HG-34, 2026-09-12): the 转到消息渠道 handoff feature was deleted, so nothing in this repo emits this code any more. The number stays allocated and must never be reused.** Handoff refused because one is already in flight for this conversation (4027) | 已经有一次转移在进行，稍后再试。 | A move is already in flight. Try again shortly. | Yes |
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
