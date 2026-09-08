# Permanent Cloud-account deletion review packet

Date: 2026-09-08

Status: engineering-complete locally; product, privacy, support, backup, and release approval pending

This packet is the approval surface for the default-off E9 deletion feature. It describes the
implemented behavior; it is not legal advice and does not authorize staging or production rollout.
The implementation evidence is in `ACCOUNT_MODE_E9_TEST_RECORD.md`.

## 1. User promise under review

The user permanently deletes the Hermes GO **Cloud account**, not any local Hermes installation or
data on a Mac.

- Cloud access, sessions, phone/browser installations, Connector bindings, owned shares, received
  shares, and pending invitations stop immediately when the deletion transaction commits.
- The operation cannot be cancelled and the old account cannot sign in again during the cleanup
  period. This is not a 30-day recovery window.
- Cloud identity and account-linked personal data become eligible for bounded cleanup at the fixed
  deadline exactly 30 days after commit. A healthy Gateway checks periodically and uses one-second
  bounded catch-up sweeps while work remains; outage, database unavailability, or backup policy can
  delay physical erasure and must be covered by the operational/privacy SLA.
- An email already accepted or in flight at the mail provider cannot be recalled, but every unused
  login code for the deleting identity becomes unusable immediately. Later code requests keep a
  neutral public response and do not submit another email.
- Hermes conversations, files, configuration, credentials, and models on every Mac remain local.
  The user must remove those separately if local deletion is desired.
- After final identity cleanup, signing up with the same email creates a new internal account ID; it
  does not restore the deleted account, bindings, or sharing relationships.

## 2. Confirmation contract

The destructive action remains absent unless `ACCOUNT_DELETION_ENABLED=1` and its authentication
prerequisites are enabled. Every client requires all of the following before the API mutation:

1. A currently authenticated account session.
2. Exact ASCII `DELETE` typed by the user.
3. A separate permanence checkbox selected by the user.
4. Fresh verification of a sign-in identity for the `account.delete` scope.
5. A stable UUID idempotency key so an ambiguous response can resolve only the exact request.

Typed text, checkbox state, and OTP are memory-only. Desktop persists only the scoped grant and
mutation key needed for exact retry. Android encrypts its challenge, grant, and mutation key, but
never persists the OTP or typed confirmation. Web keeps the confirmation state only in the dialog.

## 3. Canonical copy proposed for approval

Product and support should approve the meaning and exact terminology before release. Web, Desktop,
and Android now use this canonical candidate terminology consistently; approval may still revise it
before any rollout.

| Surface | Chinese | English |
| --- | --- | --- |
| Action | 永久删除云端账号 | Permanently delete Cloud account |
| Consequence | 提交后会立即退出所有设备、撤销 Connector 与共享权限。云端个人数据将在固定的 30 天期限后清理；Mac 上的 Hermes 数据仍保留在本机。原账号无法恢复。 | All devices are signed out and Connector and sharing access are revoked immediately. Cloud personal data is cleaned after the fixed 30-day period. Hermes data on each Mac remains local. The original account cannot be recovered. |
| Checkbox | 我理解这是永久操作，无法撤销 | I understand this is permanent and cannot be undone |
| Verification | 使用当前账号的登录方式完成最后验证 | Verify with a sign-in method already linked to this account |
| Submitted state | 云端账号删除已提交 | Cloud account deletion submitted |
| Submitted detail | 云端访问已立即停止，个人数据将在 30 天期限后清理。Mac 上的 Hermes 会话、文件、配置和模型仍然保留。 | Cloud access stopped immediately. Personal data will be cleaned after the 30-day period. Hermes conversations, files, configuration, and models on your Macs remain. |
| Submitted-state exit | 使用其他邮箱账号 | Use another email account |
| Blocked sign-in (`HR-ACCOUNT-012`) | 此 Hermes GO 账号正在永久删除，已无法再次登录。 | This Hermes GO account is being permanently deleted and can no longer sign in. |

The copy intentionally says “submitted” rather than “already erased” during the 30-day deletion
lane. It also distinguishes Cloud deletion from local Mac data and does not promise recoverability.
The submitted-state exit creates only an empty sign-in flow and must never be described as restoring
the deleted account.

## 4. Data disposition

| Data class | At commit | At/after the 30-day deadline | Final retained state |
| --- | --- | --- | --- |
| Access/refresh credentials and sessions | Revoked | Account-linked rows removed in dependency order | None |
| Phone, browser, and Desktop installations | Revoked | Removed after dependent rows clear | None |
| Connector bindings and device preference | Revoked / preference removed | Binding rows removed | Local Mac installation and Hermes data remain |
| Sharing grants and invitations | Revoked, left, or cancelled | Account-related rows and cross-account email hints removed | None |
| Verified identities and display email | Sign-in blocked | Identity tuple and display data removed | Same email may create a new account |
| Email OTP rows | Unused codes invalidated; new sends suppressed | Correlation rows and temporary fingerprints removed | None |
| Lifecycle events and receipts | No further access | Removed in bounded batches | None |
| Account audit events | Retained during deletion lane | Account-linked and related cross-account hints removed | One `account.deleted` event with empty metadata |
| Account row | `pending_deletion` with UUID and fixed timestamps | Marked `deleted` after dependencies clear | UUID, status, and creation/deletion timestamps only |
| Deletion completion receipt | Two keyed hashes plus creation time | Not account-linked; survives cleanup | Retained under the policy decision below |
| Provider-delivered email | Cannot be recalled | Governed by provider/mailbox retention | Outside Gateway erasure control |
| Database backups and replicas | No special behavior implemented | Governed by infrastructure retention and restore procedure | Approval required before rollout |

“Non-displayable” and “not account-linked” are engineering properties, not a legal conclusion about
whether a UUID or keyed hash is personal data in a particular jurisdiction.

## 5. Decisions requiring explicit sign-off

| ID | Decision | Recommended engineering default | Owner | Approval |
| --- | --- | --- | --- | --- |
| AD-01 | Is deletion immediately irreversible, with no 30-day restore endpoint? | Yes | Product / Support | Pending |
| AD-02 | Does “30 days” mean eligibility at the exact deadline plus bounded operational cleanup, including outage handling? | Yes; publish the operational SLA separately | Product / Privacy / Ops | Pending |
| AD-03 | May the final account tombstone retain UUID and deletion timestamps plus one empty-metadata audit event? | Yes, only if privacy approves its purpose and access controls | Privacy / Security | Pending |
| AD-04 | How long may the account-free keyed completion receipt remain? | Prefer a bounded period; choose and implement before enablement | Product / Privacy / Security | Pending |
| AD-05 | What is the backup/replica expiry and restore re-deletion procedure? | Define and exercise before staging sign-off | Privacy / Ops | Pending |
| AD-06 | Is the user responsible for separately deleting local Mac Hermes data? | Yes; state this at confirmation and completion | Product / Support | Pending |
| AD-07 | Can an already delivered provider email remain under provider/mailbox retention after its code is invalidated? | Yes; disclose internally and validate provider terms | Privacy / Security | Pending |
| AD-08 | Which exact Chinese/English copy above ships? | Use one approved terminology set across Web/Desktop/Android | Product / Support | Pending |

AD-04 is the only open decision that directly changes the current database behavior. The current
implementation retains the two keyed receipt hashes without an expiry so a very long-offline client
can still resolve an ambiguous committed request. A bounded policy improves data minimization but
means a retry after expiry can no longer receive deterministic completion proof.

AD-05 is a hard rollout blocker, not satisfied by the current generic PostgreSQL restore evidence.
That evidence proves an encrypted artifact hash, an off-host database restore, exact schema, and a
basic account smoke test. It does not preserve or replay a deletion committed after the restored
backup was created, so a pre-deletion snapshot could otherwise reintroduce active identity/session
rows. No staging or production review may infer deletion safety from that generic manifest.

An approved design must give restored databases an authoritative deletion-obligation source newer
than the backup. This can be continuous recovery through the deletion commit or a separately
protected append-only obligation journal with authenticated replay; a hand-written checklist or the
current account-free completion receipt is insufficient because the receipt cannot identify the
account rows that must be removed. The isolated restore drill must prove this exact sequence:

1. Create a backup while a disposable account is active.
2. Commit permanent deletion and record only the approved protected obligation evidence.
3. Restore the older backup on a different isolated host/database.
4. Replay through the deletion commit before the restored service can accept public traffic.
5. Prove old access/refresh credentials and sign-in are denied, then advance past the fixed deadline
   and prove the ordinary bounded cleanup reaches the same final tombstone.
6. Prove an unrelated account remains usable and no raw email, OTP, bearer, grant, or deletion key
   appears in the evidence artifact.

## 6. Support playbook

- If a user sees `HR-ACCOUNT-012`, explain that Cloud access is already revoked and the request cannot
  be cancelled; do not suggest retrying sign-in to restore the account.
- Confirm that local Mac Hermes data remains. Give separate local-removal steps only when the user
  explicitly wants to erase that Mac data.
- Never ask for an OTP, bearer token, deletion grant, idempotency key, or raw diagnostics containing
  personal data.
- A lost network response should be resolved by the client's built-in exact retry. Do not instruct
  the user to start a second deletion request.
- A new signup with the same email after cleanup is a new account and does not recover prior devices
  or shares.
- Escalate overdue cleanup using aggregate operations evidence and account-scoped authorized database
  procedures; do not expose internal identifiers in ordinary support chat.

## 7. Release evidence required after approval

- Record AD-01 through AD-08 approvals and implement any changed retention/copy decision.
- Exercise deletion using disposable accounts across secure Web, packaged Desktop, Android, two Macs,
  two phones, owned/shared access, Gateway restart, and a forced due-deletion sweep.
- Verify live mail suppression, aggregate metrics, database replicas, backup expiry, and the exact
  older-backup restore drill above. The generic `hermes-go-postgresql-restore-v1` evidence alone is
  not account-deletion recovery evidence.
- Inspect keyboard/VoiceOver and TalkBack flows, Chinese/English, light/dark mode, enlarged text,
  offline ambiguity, and process restart.
- Complete signed Desktop/Android release gates and separately authorize staging and production
  deployment. Keep `ACCOUNT_DELETION_ENABLED=0` until every gate is recorded.
