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
| `HR-AUTH-001` | App Token rejected | App Token 无效或已失效，请重新配置。 | The App Token is invalid or expired. Configure it again. | No |
| `HR-RPC-001` | Gateway RPC returned an unmapped remote error | Relay 请求失败，请查看详情后重试。 | The Relay request failed. Review the details and retry. | Depends |
| `HR-RPC-002` | Gateway RPC response timed out | Relay 响应超时，请稍后重试。 | The Relay response timed out. Try again shortly. | Yes |
| `HR-SESS-001` | Session no longer exists | 会话不存在或已被删除。 | The conversation no longer exists or was deleted. | No |
| `HR-SESS-002` | Live session handle is stale | 会话连接已失效，正在重新挂接。 | The live conversation handle expired. Reattaching now. | Yes |
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
