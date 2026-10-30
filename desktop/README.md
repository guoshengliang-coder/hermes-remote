# Hermes Go Desktop

## 2026-10-30：连接恢复（0.2.52 / build 55）

概览和诊断页固定提供重新连接入口，始终核验本机原绑定。启动及监测发现原 Connector 已停止时，校验原凭据、文件、启动参数和本机认证后自动启动原任务，以新的云端健康上报确认成功；每会话最多三次、间隔至少 60 秒。修正启动项仍加载就显示“正在运行”的误报；自动恢复内联反馈，确认框与更新进度面板互斥。保留人工重连与结构修复，沿用既有内测渠道和公开更新信任配置。发布不自动安装到用户 Mac；实际发布时间及包哈希以发布回执为准。

## 2026-10-29：HG-245 外置盘授权的托管保留（0.2.51 / build 54）

`EXTRA_READ_ROOTS` 加入托管 Connector 重装与修复的环境迁移白名单：校验后原样透传（绝对路径段，拒绝相对路径与一切根文件系统写法及 NUL，空白段忽略），外置盘只读附件授权在受管修复与升级后保留；空白值迁移后视为未设置。与 Connector 的 `resolveExternalReadRoots` 启动契约一致。沿用既有临时签名内测渠道和公共信任配置。发布不自动安装到用户 Mac。

## 2026-10-29：HG-244 拥塞与本机健康分层（0.2.50 / build 53）

区分拥塞、恢复中、业务连接不可用和本机 Hermes 健康状态；保留受控独立传输开关的严格配置白名单。沿用既有临时签名内测渠道和公共信任配置。发布不自动安装到用户 Mac，也不启用独立传输灰度。


## 2026-10-28：旧版官方 AppleScript 启动识别（0.2.49 / build 52）

补齐官方旧版 `/usr/bin/osascript -e 'do shell script …'` 启动项的静态识别，与新版 JXA 共用真实命令/目录检查。既有启动项无需转换；不执行脚本，不改动 Hermes、数据或 LaunchAgent。已读取 M2 的真实启动项并确认此根因：Desktop 0.2.48/build 51 无法识别，修复解析器能还原原安装路径；修复后的完整只读检测器在 M2/macOS 14.8.9 返回 Hermes 0.21.4 可用且依赖一致。尚未发布或替换机器上的 Desktop，更新后的界面验收仍待进行。

## 2026-10-27：官方 osascript 启动识别（0.2.48 / build 51）

修复官方 launchd 包装导致本机 Hermes 安装路径误判、版本无法识别的问题。静态解析实际启动命令后复用安装检测；支持 PM/Python 启动器及带空格的路径，未知包装继续显示检测原因。识别过程不执行脚本，不修改现有 Hermes 安装、启动项或数据。

723 项 Swift 测试、assets、app 构建和 15 项发布工具测试通过。按现有临时签名内测渠道发布，官网和应用更新索引同步；受影响 Mac mini 安装新版后刷新状态仍需设备验收。

## 2026-10-27：操作进度窗口与本机状态（0.2.47 / build 50）

安装、Hermes 升级、Desktop 更新和连接修复开始时展示独立的原生进度窗口。窗口可收起，主窗口底部保留“查看进度/查看操作结果”。显示具体操作、真实阶段、耗时、最近进展、已有下载统计和脱敏记录；45 秒无进展只提示等待，不推断失败。确认弹窗只负责执行前确认，执行移到进度窗口；成功、失败、待清理、取消及交接重启分开显示，重试复用原有入口。尚无字节回调的 Desktop 包/组件准备阶段明确说明，不生成百分比或 ETA。

本机服务可用但安装识别失败时显示“服务可用，升级受限”，本机验证仍为已通过；安装原因保留 HR-MIGRATE-008 并折叠。无法读取版本时显示“版本未识别”，管理方式单列。本轮只改状态呈现，不改变升级执行器、服务互斥或回滚策略，不涉及 WebApp 登录设备。


## 2026-10-27：WebApp 登录设备补齐（0.2.47 / build 50）

“我的账号”的“登录设备”同时展示手机 App 与 WebApp/浏览器登录，保留服务端名称、状态和最近活动；Mac 继续在“我的设备”管理。记录按 App/浏览器安装标识区分，不推断物理手机，也不自动合并同一手机的不同浏览器。沿用现有账号安装记录，无数据库迁移。

撤销登录需当前账号邮箱验证码，只撤销选中安装的会话。手机沿用原 DELETE 接口；浏览器使用新的 Desktop 专用接口和 `browserInstallationRevocation` 能力位。旧服务器仍显示浏览器记录，撤销按钮禁用并提示服务器需要更新；不调用 Web Cookie/CSRF 接口。先发布 Gateway、再发布 Desktop；Android 和 Web 无需更新，既有接口保持不变。


## 2026-10-27：升级下载、实际进度和阶段重试（0.2.46 / build 49）

本轮只改稳定版升级的下载、进度、手动重试。升级程序下载与子进程统一读取本机手动 HTTP/HTTPS 代理；显式环境代理优先且大小写归一，回环地址始终绕过。Git 对官方仓库使用进程内 HTTPS 重写覆盖，Node 原生 fetch 启用环境代理，旧 Electron 下载器也接收代理参数；不改用户 Git、系统网络或上游源码。PAC 仍只由 URLSession 处理，SOCKS-only 不保证 Node 下载代理，页面明确说明，未添加代理服务/镜像。

升级卡片显示下载网络、脚本真实字节/总量/平均速度、Git 实际接收字节/报告速度，以及识别出的 Python、Node、Electron、构建与打包子步骤。Git 对象百分比不当成字节百分比；管道中的回车进度即时交付。速度超过 5 秒未更新即隐藏；45 秒无输出仅提示无法据此判断卡住。没有数据的依赖阶段继续显示步骤和耗时，不生成百分比或 ETA。

同一 Desktop 会话内记录官方成功阶段。手动重试重新获取同一提交的安装器、读取当前代理，并核对脚本摘要、目标、checkout 身份和各阶段文件结果；只跳过连续有效的成功阶段，从首个失效/失败阶段继续。失败按钮直接指明重试阶段。文件证据不足时保守重跑该阶段；不把命令退出码单独作为成功证据。PM 的依赖选择记录、解释器和环境文件用于核对，配置凭据内容不读取。退出应用后不保留这些内存记录；本轮不包含后台任务恢复、兼容管理、自动回滚或服务端上报。错误继续使用 HR-HERMESUPDATE-004，检测失败沿用 HR-HERMESUPDATE-005。


## 2026-10-27：升级步骤进度（0.2.45 / build 48）

升级页新增九步进度：下载升级程序、六个官方阶段、确认实际版本、运行 GO 检测。步骤由执行器的类型化事件串行推进；进度条表示已完成步骤数，阶段内部不插值、不预测剩余时间。总耗时、本步骤耗时和最近输出时间每秒更新；45 秒无输出提供中性等待提示，既有脱敏输出仍可展开。失败不显示完成，重试重新计数；运行检测未通过仍显示既有 HR-HERMESUPDATE-005，不增加错误码或改变升级执行/兼容策略。


## 0.2.44 / build 47：Hermes 稳定版升级首阶段（已发布，2026-10-27）

本机 Hermes 页支持版本检测、官方稳定版及关键更新展示、点击升级、实际版本确认和升级后自动运行现有 GO 诊断。旧的兼容推荐/条件检查不再作为按钮前置条件。官方安装器按目标提交更新已有安装，运行期间保持 Desktop 开启；不包含自建停写、自动回滚、独立后台执行或问题上报。发布说明缺少中文摘要时显示官方原文。官网与 Desktop 更新入口已同步，公开完整安装包校验通过；这是临时签名内测版。日常 Desktop/Hermes 未由发布操作替换，实际客户端升级及账号/手机验收仍需单独完成。


下载：[Hermes Go Desktop 0.2.44](https://mrlgs.net/desktop/apps/0.2.44/Hermes-Go-Desktop-0.2.44-dev.dmg)。

## Previous distribution: 0.2.42

Published **0.2.42 / build 45** on 2026-10-25 on the owner-authorized ad-hoc internal channel.
Hermes conditions now explain custom Git configuration refusal (#666), preserving the existing
configuration and safety boundary. Website/app discovery and both complete public DMGs passed
size/hash verification. The installed 0.2.41 client updated and relaunched as 0.2.42/build 45.
Actual clicks showed the explicit configuration-blocked reason, version discovery and no upgrade
record. One-click Hermes upgrade/recovery remains disabled. Hermes/Connector PIDs, source, Git
config/index and Hermes config stayed unchanged. After the owner handled the system confirmation,
account restoration completed and Overview showed local Hermes ready and Cloud connected. Physical
phone acceptance remains blocked by the unavailable device. See
`../docs/DESKTOP_RELEASE_CHANNEL.md` and `../docs/DESKTOP_TEST_PLAN.md` for the exact evidence/limits.

## Previous distribution: 0.2.41

Published **0.2.41 / build 44** on 2026-10-24 (owner-authorized internal distribution):
the Local Hermes page includes read-only version discovery, upgrade-condition checks and upgrade
records (#659), plus the compact account footer (#660). One-click Hermes upgrade/recovery remains
disabled; no updater or journal writer is packaged. Both public DMGs and website/app-update discovery
were verified. The installed 0.2.39 client updated and relaunched as 0.2.41/build 44 through its own
updater, preserving Hermes/Connector PIDs, Hermes source/config and the absent upgrade record.

Actual clicks found Hermes 0.21.3, upstream stable 0.22.5, no GO-certified recommendation and no upgrade
record. Conditions returned unknown local changes and correctly blocked execution. After the owner handled the system confirmation, account restoration completed and Overview
reported local Hermes ready and Cloud connected. Local-change checking remains unknown; phone
business tasks and clean-machine acceptance are still unverified. This is ad-hoc internal distribution,
not Developer ID/notarized distribution. Details: `../docs/DESKTOP_RELEASE_CHANNEL.md` and
`../docs/DESKTOP_TEST_PLAN.md`.

## Previous distribution: 0.2.40

Published **0.2.40 / build 43** (owner confirmed 2026-10-24): structured Hermes/model status,
account/sidebar/settings polish, installation guidance and bounded download recovery (#654/#656).

Publication completed on 2026-10-24 after PRs #656/#657 and their main checks passed,
from clean main `935f42a901b5e254fe7186354d9d4461979f7a76`. Both unified-publisher success markers and independent
complete public downloads passed. The internal `-dev.dmg` and compatible `.dmg` alias match
**3,899,546 bytes**, SHA-256 `896f068982a096ff5bea7b4d1439298c086aaef149f72f3cac95ba76683febc1`.
Website and app-update indexes both report 0.2.40/build 43; signed managed-component indexes remain
byte-identical at 0.4.12. Assets, 629 Swift tests, 15 publication/update tooling tests, release app/DMG
build, strict ad-hoc signature, mounted identity/version/build/arm64 and image integrity passed.
Native fixtures cover light/dark/compact layout, long email, model states and installation guidance.
No user-installed app, service, Hermes profile or data was replaced. Real clean-machine installation,
upstream dependency outage recovery, upgrade/relaunch and phone/model tasks remain separate acceptance.
This is temporary-signed internal distribution, not Apple notarization.

Download: `https://mrlgs.net/desktop/apps/0.2.40/Hermes-Go-Desktop-0.2.40-dev.dmg`.

## Previous distribution: 0.2.39

Published **0.2.39 / build 42** (owner confirmed 2026-10-24): first-run recovery,
local inspection without login, unified device invitations and model setup guidance.

Publication completed on 2026-10-24 after PRs #650/#651 and their main checks passed,
from clean main `c431b041a1b5f9c46329a0047858b4f137c833c7`. Both unified-publisher success markers and
independent complete public downloads passed. The internal `-dev.dmg` and compatible `.dmg`
alias match **3,830,604 bytes**, SHA-256 `cf3b8527bfd5ba8e2908a7b43adcefffff32cbbd6573095040ef9c5af272f3df`.
Website and app-update indexes both report 0.2.39/build 42; signed managed-component indexes
remain byte-identical at 0.4.12. Assets, 620 Swift tests, 12 update/publication tooling tests,
release app/DMG build, strict ad-hoc signature, mounted identity/version/build/arm64 and image
integrity passed. Native fixtures cover light/dark devices, empty-account onboarding, signed-out
states, settings and model setup. No user-installed app, local services or Hermes data were replaced.
Physical first registration/install, upgrade/relaunch, phone access and model inference still need
separate acceptance. This remains temporary-signed internal distribution, not Apple notarization.

Download: `https://mrlgs.net/desktop/apps/0.2.39/Hermes-Go-Desktop-0.2.39-dev.dmg`.

## Previous distribution: 0.2.38


Published **0.2.38 / build 41** (owner confirmed 2026-10-24): fixes updates blocked
on existing bundled installations (#646). Discovery and execution now follow the installed layout;
component updates refresh signed preflight, while stale hints cannot override index discovery.
Existing signing, confirmation, rollback and background-before-app sequencing remain unchanged.
Affected 0.2.36/0.2.37 clients may need one direct DMG app replacement to acquire this updater fix.
Do not reset Hermes, profiles, account binding or installation records. Continue temporary ad-hoc
internal distribution and preserve the signed backend pointers. Publication completed on 2026-10-24 after PR #647 and main checks passed, from clean main
`0db655edd022cdae3e9db477c5b175e3f3b1876b`. Both unified-publisher success markers and independent full public downloads
passed. The `-dev.dmg` and compatible `.dmg` alias match **3,796,718 bytes**, SHA-256
`0f43335e63654dc46162c0bf17dab1fa29812c1b93a6029c306621d0cc6dd352`. Website and app-update discovery report 0.2.38/build 41; signed managed-component
indexes remain byte-identical at 0.4.12. Assets, 612 Swift tests, 41 Desktop packaging/tooling tests,
app/DMG packaging, strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image
integrity passed. The affected Mac's read-only source/manifest verification is recorded under the
update-routing regression in DESKTOP_TEST_PLAN.md. No installed user app, service, account binding
or Hermes data was replaced. Physical background commit/relaunch and mobile acceptance remain
pending; this is a temporary-signed internal build, not an Apple-notarized release.

Download: `https://mrlgs.net/desktop/apps/0.2.38/Hermes-Go-Desktop-0.2.38-dev.dmg`.


Published **0.2.37 / build 40** (owner confirmed 2026-10-24): overview now emphasizes
semantic icons, direct cloud latency, Hermes version and independently checked background/cloud/mobile
status. Healthy explanations and check times expand on demand; recovery remains visible for failures.
Includes the diagnostics/log viewer, native title cleanup, Mac naming, shared Hermes portrait icon and
Local Hermes placeholder removal previously merged in #642. Also includes #641's validated per-Mac
transfer-budget override; its default remains unchanged. See `CONNECTOR_TRANSFER_TUNING.md`.
Publication completed on 2026-10-24 after PR #643 and main checks passed, from clean main
`a9a812198cf4913fc2e7c86731f7a062731cb7ed`. Both unified-publisher success markers and independent full public downloads
passed. The `-dev.dmg` and compatible `.dmg` alias match **3,794,849 bytes**, SHA-256
`79ae595664f76ccb38c9a74f664ef33b88d4c1c86af59aed7b60f5ec61a26a44`. Website and app-update discovery report 0.2.37/build 40; signed managed-component
indexes remain byte-identical at 0.4.12. Assets, 606 Swift tests, 41 Desktop packaging/tooling tests,
app/DMG packaging, strict ad-hoc signature, mounted bundle identity/version/build/arm64 and image
integrity passed. Native light/dark/compact, long-name, expanded, signed-out, stale and failed-state
fixtures were reviewed. No installed user app or local service was replaced. Physical upgrade and
mobile/data-preservation acceptance remain pending; this does not establish Apple notarization.

Download: `https://mrlgs.net/desktop/apps/0.2.37/Hermes-Go-Desktop-0.2.37-dev.dmg`.

Published **0.2.36 / build 39** (2026-10-24): repair existing Hermes installations with named profiles without changing their data,
pin the managed backend to its original default home, show concrete repair blockers, and remove
local setup/repair panels from Devices and the Overview headline. Diagnostics and device rows now
expire old reports and keep local/remote identity separate. 592 Swift tests and asset checks passed;
native light/dark/compact fixtures were reviewed. Live repair and mobile acceptance remain pending.

Overview/global-account update (0.2.35 / build 38): three independently checked local status sections and
an impact headline replace mixed connection tables. The sidebar footer owns login, identity and
account management throughout the app; Devices and Settings keep their own responsibilities.

Previous published internal test version: **0.2.36 / build 39**, published on 2026-10-24.
PR #639 and its main checks passed. Assets, 592 Swift tests, app/DMG packaging, strict ad-hoc
signature, mounted bundle identity/version/build/arm64 and image integrity passed. The unified
publisher synchronized website and app-update discovery; independent full public downloads match
3,681,884 bytes and SHA-256 `fd3add77b6524cd5507160ef00d778d7935232c3229ec7ca9bb0244193dfac8d`.
Source: `4ee653e488dd96a8d6d9ea0b6efba3d0746a2227`.
Download: `https://mrlgs.net/desktop/apps/0.2.36/Hermes-Go-Desktop-0.2.36-dev.dmg`.
Temporary ad-hoc signing continues. No installed user app, local service or Hermes data was changed;
live repair and mobile acceptance remain pending. Public trust/settings are retained, and signed
managed-component indexes remain byte-identical at 0.4.12.

Previous published internal test version: **0.2.35 / build 38**, published on 2026-10-24.
PR #636 adds the overview/global-account redesign and adapts onboarding to the persistent sidebar.
Assets, 586 Swift tests, app/DMG packaging and native light/dark fixture checks passed. The unified
publisher synchronized website and app-update discovery; both full public downloads match
3,694,300 bytes and SHA-256 `ccb22051b8b333c1e0fff901344fb6dc7b83221aedaf60bd56df0951cab8cf1e`.
Download: `https://mrlgs.net/desktop/apps/0.2.35/Hermes-Go-Desktop-0.2.35-dev.dmg`.
Temporary ad-hoc signing continues; physical upgrade, live login and task/data-preservation
acceptance remain pending. Public trust/settings and signed managed-component indexes are retained.

Previous published internal test version: **0.2.34 / build 37**, published on 2026-10-24.
It includes HG-234 and HG-235 below. The unified publisher verified the full public DMG and
synchronized both the website and Desktop app-update indexes. Download:
`https://mrlgs.net/desktop/apps/0.2.34/Hermes-Go-Desktop-0.2.34-dev.dmg`.
This uses temporary ad-hoc signing; Apple notarization and physical upgrade/data-preservation
acceptance remain pending. Existing public managed-component trust/settings are retained.

## HG-235: one Desktop product (0.2.34)

Navigation now contains Overview, Local Hermes, Account & Devices, Diagnostics and Settings.
Local Hermes distinguishes installation from authenticated usability on this Mac. Diagnostics owns
component details, bounded redacted logs and the copyable report; errors can open their diagnostic
context. Settings explains login startup, closing the window, quitting and task interruption.

A single Desktop update action coordinates background updates before app replacement. Existing
signed prepare/confirm/commit and rollback remain in use. Failure, cancellation or pending cleanup
stops the sequence; an app-only retry cannot repeat a successful background update. Upstream Hermes
version management remains future scope. This behavior is included in 0.2.34.

## Account-only Desktop (0.2.34)

Desktop no longer supports compatibility-observation mode or the URL/App Token/v1 pairing flow.
The editor, credential store, token probe, config/log parser, observer health and legacy service
migration/restore paths have been removed. Account sign-in is the only connection entry.
Account capabilities no longer require the server's legacy-token flags. Phone download/onboarding
QR links remain available; they contain no App Token.

Only conflict detection for `com.hermesremote.connector` remains: an installed startup item or loaded
job blocks new setup with `HR-MIGRATE-022`. An inert old data directory does not. The app never reads
its config/logs, starts that job, or restores it after rollback. Existing, proven account-active
installations retain exact-label suppression of a duplicate restored by Migration Assistant.
Historical `legacy_active` / `lastKnownGoodMode=legacy` journals fail closed without being rewritten.
Account installation, upgrade rollback and recovery remain supported. Old Keychain items and user
files are not deleted. The historical release notes below describe prior versions, not current support.


HG-234 fixes the phone onboarding page returning after an existing Mac upgrades or relaunches.
Restoring this Desktop's valid binding retires stale onboarding flags, and component-upgrade
intermediate checks cannot re-arm them. Existing active phone/Web sign-ins satisfy first setup
without another login; offline clients still count. Local validation passed 578 tests, asset
consistency and app packaging. This change is included in 0.2.34; the reported
M4 upgrade/relaunch acceptance remains pending.

HG-226 adds actual managed-setup stages, per-second elapsed time and per-run system/runtime
telemetry to both installer confirmation sheets. Confirmed execution and rollback remain
non-cancellable; success/failure closes the sheet, while a cleanup retry handle remains available
without reopening installation. The original permanent-loading hypothesis remains unproven.
See the Desktop design and test plan for the behavior and pending physical upgrade acceptance.
This change is included in the **0.2.33 / build 36** internal test release.

Previous internal test version: **0.2.33** (build 36). On 2026-10-22 the owner
confirmed that distribution continues with temporary ad-hoc signing. The versioned package is
`https://mrlgs.net/desktop/apps/0.2.33/Hermes-Go-Desktop-0.2.33-dev.dmg`; publication must use
the unified internal publisher, verify the full public file, and synchronize the website's
internal download index. Publication and full public readback completed on 2026-10-22;
the website now offers this version. It retains the existing managed-component trust configuration.
The owner subsequently requested connecting this temporary-signing channel to Desktop's existing
update checker. The unified internal publisher now synchronizes the compatible app index as well
as the website, using the same verified DMG bytes. This does not imply Apple notarization;
see the release-channel document for the repair command and physical-update acceptance gate.

Previous internal test version: **0.2.32** (build 35), containing HG-212's startup-failure
diagnostics and PM/runtime recovery follow-up (#582, #583). It selects the published PM launcher,
verifies the actual command and authenticated local service, refreshes local/account evidence with
their own times, and offers an identity-checked Connector-only reconnect. The candidate does not
change the managed Node/Connector component release. Developer ID signing, notarization, stapling,
clean-Mac acceptance and public app-update publication are pending. The owner explicitly approved
internal ad-hoc test distribution on 2026-10-12. Publication targets
`https://mrlgs.net/desktop/apps/0.2.32/Hermes-Go-Desktop-0.2.32-dev.dmg` and requires full public
size/hash readback; it does not switch `/desktop/apps/index.json`. This is an internal test artifact.
iPhone/PWA messaging alongside notebook SSH still needs physical acceptance.

HG-212's PM follow-up selects the installation-bound `.hermes/bin/hermes` before the historical
`venv/bin/hermes`, validates the actual serve command after installation, and health-gates managed
starts with the private-token API as well as public status. Diagnostics refresh both local and
account evidence and retain the account report time. “重新连接这台 Mac” offers a confirmed, exact-binding
Connector restart through the existing repair/rollback transaction. No upstream files or PM facts
are rewritten. This source iteration is included in the 0.2.32 internal test version; customer device and
iPhone/PWA/SSH acceptance remain separate gates.

HG-212 preserves fresh backend startup failures in readiness diagnostics instead of reporting only a
timeout: Python import failure and a port-conflict marker remain distinct, old stderr does not poison
a new start, and raw Hermes logs are never copied into the diagnostic. SSH/PWA coexistence and the
customer-Mac recovery checklist are in [`../docs/SSH_MOBILE_RECOVERY.md`](../docs/SSH_MOBILE_RECOVERY.md).
This source change is included in the 0.2.32 internal test version. The customer reported Desktop recovered
after PM entrypoint repair and restarting Connector; iPhone/PWA and SSH coexistence remain unverified.

HG-175 adds an explicit **检查并修复受管服务** recovery action for committed installations whose
LaunchAgent files or loaded tasks are missing. It preserves the existing binding and installation
identity, validates the retained release/credentials and same signed-in account, and uses confirmation,
fresh readiness/health, rollback and interruption recovery. Untrusted records, unknown jobs and an
occupied 9119 remain blocked with recovery guidance. This source change is included in the 0.2.32 internal
test version; see [`../docs/DESKTOP_TEST_PLAN.md`](../docs/DESKTOP_TEST_PLAN.md) for device acceptance.


Release discovery, two-component topology, signing-key rotation, and protected-environment setup
are documented in [`../docs/DESKTOP_RELEASE_CHANNEL.md`](../docs/DESKTOP_RELEASE_CHANNEL.md).

Previous internal test release candidate: **0.2.31** (build 34). It lets Desktop **adopt the
Hermes this Mac already runs** on 9119 instead of installing a second copy. A new planner
readiness (`.readyToAdoptLocalHermes`) is offered ahead of the read-only "unmanaged Hermes"
card when this Mac's own checkout is usable, the confirmation sheet names the job to unload and
its action reads 「接管并连接」, and a one-probe port preflight makes a fresh install refuse an
occupied 9119 with `HR-MIGRATE-014` — naming the launchd label and a pasteable `launchctl
bootout` in 详情 — rather than ever stopping a job Desktop did not create. Connector **0.1.12**
and managed component release **0.4.7** are unchanged; these versions remain candidates until
the signed Desktop release channel publishes them.

0.2.30 (build 33) carried HG-151 (the health probe names the failing link, and Connector
reports local Hermes HTTP failures with a request correlation ID) and HG-150 (a Connector
prewarm no longer marks a session unread). It is an ad-hoc internal test DMG
(`https://mrlgs.net/desktop/apps/0.2.30/Hermes-Go-Desktop-0.2.30-dev.dmg`, published
2026-09-28, SHA-256 `9d0cd20a…`), without Developer ID signing, notarization, stapling, or an
app-update index entry — `/desktop/apps/index.json` still answers 404, so the in-app update
check has no index to read yet.

0.2.29 (build 32) added the HG-117 existing-install update target and the HG-116
shared Dock icon. Connector 0.1.11 added bounded 50 MiB phone uploads for HG-145
and reconnect backoff recovery for HG-147; managed release 0.4.6 carried it.

0.2.28 (build 31) carried the sign-in gate and first-run onboarding for HG-129.

0.2.27 (build 30) carried foreground-request correlation and diagnostic logging for HG-120–122.
0.2.26 (build 29) had three changes (HG-97, HG-98, HG-99).

Desktop release discovery now starts from stable, cache-disabled indexes while retaining strict
Ed25519 verification of the immutable manifest they reference. Signed manifests no longer stop being
usable merely because wall-clock time passed; their creation and expiry fields remain authenticated
compatibility metadata. Release signing and upload are isolated behind the protected Desktop release
environment, with paired release/component pointer switching and rollback. New component releases
contain only the Node runtime and Connector; Desktop uses this Mac's standard Hermes installation and
does not package Python, Hermes core, optional tools, patches, or a schema baseline.

0.2.25 (build 28) had one change.

Its pinned schema-v1 manifest moves from 0.3.9 to **0.3.10**
(`https://mrlgs.net/desktop/releases/0.3.10/Hermes-Desktop-0.3.10-arm64.manifest.json`), which carries
Connector **0.1.7** and the HG-90 request-cancellation path. When Android abandons or times out a
tunnel request, the Gateway can now stop its matching local fetch, response read, and chunk-ACK wait
instead of leaving stale work behind the next reconnect. The Hermes Server artifact is reused byte for
byte from 0.3.9; no Android, Gateway, protocol, or Hermes runtime contract changes are part of this
Desktop release.

0.2.24 (build 27) had one change (#365).

This Mac's own Hermes is used **by default** —
`HermesGoLocalHermesRuntimeEnabled` now defaults to on, so a managed Mac with a usable standard
Hermes switches to it on its next refresh, and a fresh install writes the local agent directly.
A fresh Mac with no Hermes is offered upstream's official installer (confirmation required, system
proxy honoured, `HR-MIGRATE-015`–`018` on failure, "改用内置 Hermes" as the way out). Opt a Mac out
with `defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool false`. See
`docs/DESKTOP_PHASE0.md` ("Installing Hermes when the Mac has none").

0.2.23 (build 26) had one change. Its pinned schema-v1 manifest moves from 0.3.8 to **0.3.9**
(`https://mrlgs.net/desktop/releases/0.3.9/Hermes-Desktop-0.3.9-arm64.manifest.json`), which carries
**Connector 0.1.6** with the upstream contract check (#359): the Connector compares the local
Hermes' `openapi.json` with the routes the app depends on and serves the verdict at
`/api/hermes-remote/contract`, so an incompatible `hermes update` reaches the phone as
`HR-COMPAT-001/002/003` instead of later, vaguer failures. Hermes Server is 0.3.8's exact artifact.
Nothing else in the app changes; the rebuild is required because the manifest URL is written into
`Info.plist` at build time.

0.2.22 (build 25) had one fix (#356).

Every managed Hermes restart — switching to or from this Mac's own Hermes, reloading, and the
in-app managed upgrade and its rollback — waited for a proof that the old Hermes had released
127.0.0.1:9119 that could never succeed on macOS: a refused loopback connection stays in
`NWConnection`'s `.waiting(ECONNREFUSED)` and was read as "still listening". On 2026-09-21 this
left the Mac mini's Hermes unloaded for about three minutes after a runtime switch (HG-68's "needs a
manual activation" is the same bug). The probe now treats a refused connection as proof, a failed
restore re-bootstraps its agent and reports `HR-MIGRATE-013` instead of staying silent, an unloaded
bundled job is loaded again unless another process holds the port (`HR-MIGRATE-014`), diagnostics
keep the original and recovery errors, service operations are logged to
`Managed/logs/desktop-runtime.log`, and the migration journal reads timestamps without fractional
seconds.

0.2.21 (build 24) had one change.

It can run this Mac's own Hermes instead of a second, pinned copy (`docs/MANAGED_HERMES_STRATEGY.md`,
one Hermes per Mac). When the owner's standard install (`~/.hermes/hermes-agent`, 0.21.3 or newer,
default profile only) is present and the setting is on, the `com.hermesgo.hermes-server` LaunchAgent
starts that Hermes through a private launcher that hands over the session token, restarts it when
the checkout's commit changes, and keeps the bundled agent as the rollback. In 0.2.21 the setting
was off by default and turned on per Mac with
`defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool true` (on by default
since 2026-09-22, above); turning it off restores the bundled agent byte for byte. Repeated failures pause with `HR-MIGRATE-011` instead of
looping. The pinned manifest stays at **0.3.8**.

0.2.20 (build 23) had two changes.

Startup recovery now keeps a healthy managed upgrade actionable when only the token-storage or
LaunchAgent search-path repair fails. Those advisory failures are reported as retryable
`HR-MIGRATE-007`; only a real Connector ownership mismatch retains the blocking `HR-MIGRATE-002`
(HG-68).

Its pinned schema-v1 manifest moves from 0.3.7 to **0.3.8**. That managed release carries the
read-only `session.access` projection and bounded inline-image history required by Android's
cross-client ownership, authoritative run-state recovery, and oversized-history fixes (HG-66,
HG-67, HG-69). The patches do not change Hermes writes, the database schema, migrations, or session
ownership, and are applied to the actual packaged source allowlist before the archive is signed.

0.2.19 (build 22) had two changes.

It notices when this Mac's `state.db` has grown columns the managed Hermes was not built to read, and
says so as `HR-MIGRATE-006` instead of letting a read fail later with a traceback that names only the
web framework (HG-71). The comparison is by column, not by `schema_version`: upstream added
`display_identity` and `display_order` while leaving that number at 30 on both sides, so a version
gate would have missed the very incident it exists for.

Its pinned schema-v1 manifest moves from 0.3.6 to **0.3.7**, which carries the first managed-Hermes
patch. The rebuild is required, not cosmetic: the manifest URL is written into `Info.plist` at build
time, so a Mac cannot be pointed at a new release by publishing one.

0.2.18 (build 21) was 0.2.17 with its manifest moved to **0.3.6**, which carries Connector **0.1.5** —
the release that stops an oversized Hermes answer from destroying the tunnel (HG-65).

0.2.17 (build 20) added the managed in-app upgrade
transaction for an already active installation. When the pinned signed release is strictly newer,
Desktop now offers “下载并验证更新 → 升级并重连”, preserves the existing account credential, binding,
session token and Hermes data, waits for the old loopback listener to stop before starting the new
Hermes, and commits only after fresh local and Cloud health. Failure or app restart restores the exact
previous LaunchAgents, bundled pointer, services and `account_active` journal. Same-version and
downgrade targets remain read-only.

0.2.17 kept the schema-v1 manifest pinned to **0.3.5** and component preflight off, so a Mac already
on 0.3.5 correctly stayed connected with no upgrade action — which is exactly why the Connector fix
needed 0.2.18 as well as 0.3.6.

0.2.16 (build 19) carried the same single change 0.2.15 carried — the pinned managed-release manifest
points at **0.3.5**, which carries Connector **0.1.4** — and was republished because the 0.2.15 DMG did
not contain that configuration.

`build-dmg.sh` runs `build-app.sh` itself, so a DMG built in a separate shell invocation from the
configured `desktop:app` run silently rebuilt the app with the repository defaults: empty release URL,
managed bootstrap off. The published 0.2.15 DMG wrapped that build and would have replaced a working
0.2.14 with one that could not manage anything. It was withdrawn rather than replaced in place, because
these artifacts are served with a one-year `immutable` cache behind a CDN and rewriting bytes under a
version string cannot be relied on to reach anyone. **Build the DMG and the app in one invocation, with
the configuration in the same environment, and read the packaged `Info.plist` back out of the mounted
DMG before publishing.**

That one pinned value is the whole reason this version exists. The Connector fix from PR #313 — an
oversized frame from the local Hermes now closes the tunnel with 1009 and a reason naming the limit,
instead of the anonymous 1006 the relay refuses to forward, which cost one conversation the entire
transport for twenty-four minutes (HG-65, HG-64) — reaches a Mac only through a managed release, and the
release URL is baked into `Info.plist` at build time. A Mac running 0.2.14 asks for 0.3.4 and nothing
else, which is why publishing 0.3.5 alone changed nothing on the machine that filed those reports.

Component release **0.4.1** carries the same Connector on the schema-v2 channel and is also published.
This build still leaves `HermesGoDesktopComponentManifestURL` empty and component preflight off, exactly
as 0.2.14 did, so which channel a Mac uses does not change here.

0.2.14 (build 17) gave the managed Hermes server a `PATH`. launchd starts an agent with
`/usr/bin:/bin:/usr/sbin:/sbin` and nothing else, so anything the user had installed was invisible to
it: a PDF attachment from the phone was refused with `pdf.attach 5028 "pdftoppm not installed"` on a
Mac where `pdftoppm` had been installed four and a half hours earlier, in `/opt/homebrew/bin` (HG-58).
The written agent now carries both Homebrew prefixes ahead of launchd's four, an agent written before
this validates without one and is repaired by the next optional-component activation, and a malformed
`PATH` on disk is refused.

0.2.13 would have fixed nothing on a Mac that had already migrated, because the only writers are a
migration and an optional-component activation and installing a newer Desktop is neither. 0.2.14 adds
the startup repair that closes that: `reconcileCommittedHermesSearchPath()` runs on the same startup
reconciliation as the account and token-file ones, adds the key to an agent it recognises, leaves an
existing well-formed `PATH` alone, refuses a malformed one, restarts **only** Hermes, and restores the
exact previous file if that restart cannot prove a healthy server. It happens at most once per machine
— the second launch finds the key present and spends nothing. **The restart is not announced and
cannot be declined, and nothing can tell whether a turn is in flight.**

0.2.12 (build 15) carried the production-enabled schema-v2 component bootstrap path, kept the app
visible in the Dock, and made Overview follow the Mac selected in Account & Devices. Desktop verifies
the signed component manifest, reuses exact compatible local runtimes, downloads only missing bootstrap
components into a private cache, and requires a second explicit confirmation before it changes the
managed component store, account binding, LaunchAgents, or services.

Desktop 0.2.10 (build 13) remains installed with managed release 0.3.4 on the historical test Mac. It
packages the post-restart Cloud health freshness correction. Immediately before starting an
already-bound Connector, Desktop records the exact binding's server-provided `endToEnd.checkedAt`;
acceptance requires the same binding ID and generation to become healthy with a strictly newer
timestamp. The same rule protects token-file migration rollback, preventing a cached healthy snapshot
from masking a failed Connector restart.

The ordinary 0.2.9-to-0.2.10 app replacement preserved both managed service PIDs. A subsequent
Connector-only restart kept the exact account binding and generation, advanced the Cloud health
timestamp, and restored its Gateway TLS connection while Hermes kept running. The 0.3.4 artifacts
remain published and unchanged. Physical Android account traffic and a full Mac reboot remain deferred.

Physical upgrade on the historical Mac found that managed release 0.3.3 corrected only the packaged
Hermes reader; its Connector reader still rejected the existing valid 64-character lowercase-hex
token. Connector exited before account connection, and Desktop restored both exact inline
LaunchAgents, removed the uncommitted token file, and restarted healthy 0.3.3 services.

Desktop 0.2.8 (build 11) was the temporary recovery release used with managed 0.3.3 in restored
inline-token mode before the corrected 0.2.9/0.3.4 pair was installed.

Desktop 0.2.6 (build 9) skips the startup token-file migration for managed releases older than 0.3.1,
preserving their inline session token and running services.
Desktop 0.2.5/build 8 remains withdrawn after a physical target found that it moved the active
managed 0.3.0 Connector to a token-file contract that release did not support. Its Gateway control
connection remained online while Android WebSocket tunnels failed local authentication with
`HR-CONN-002`. Desktop 0.2.6 has since passed installation and pre-reboot physical Android REST and
WebSocket checks on that target. Reboot recovery remains deliberately deferred while the Mac is in
use and is still required before full acceptance.

Desktop 0.2.5 carried the managed-migration recovery and
private loopback session-token handoff required by Hermes Server 0.21.0, and reports the effective
managed Agent instead of treating the intentionally stopped legacy Connector as a failure. A managed
installation whose this-device-only account session is absent remains visible as running but awaiting
account verification. A committed account migration now persistently disables the legacy LaunchAgent,
re-enables it on pre-commit rollback, and reasserts that single-Connector state after Migration
Assistant restores both labels. While the account is signed in, legacy App-Token health is also
removed from Overview, Diagnostics, and aggregate status. Public distribution still requires
Developer ID signing, notarization, stapling, and clean-Mac acceptance.

The withdrawn release also attempted to repair committed managed installations created before the private
session-token file contract. On startup it preserves the existing high-entropy local token, removes
that value from both owner-only LaunchAgent plists, writes it to the owner-only managed secrets file,
then restarts Hermes before Connector and requires both local readiness and the exact bound account
health before recording completion. A partial write or failed health proof restores the exact prior
plists/token state and restarts that configuration; mismatched plist values or account bindings fail
closed with the existing migration diagnostic. The corrective implementation first requires managed
release 0.3.1 or newer, the immutable release boundary at which both packaged components support the
file contract; an older committed release remains untouched.

Hermes Go Desktop is the native macOS companion for Hermes GO account connections. When the Gateway advertises macOS `email_otp` account
support, it can send a six-digit code to the user's email, exchange it for a management session,
persist/refresh that session, show the binding and phone installations, remove one phone only after
re-verifying the current account email, and sign out only this Desktop management session. When the
Gateway additionally advertises
multi-device selection, it lists up to three owned Macs, remembers this Desktop's selection, and can
set the account default. When whole-device sharing is separately advertised, Account & Devices also
separates owned and operator-access Macs, shows masked invitations/grants, requires an explicit
whole-Hermes disclosure, reauthenticates by email code before inviting, and supports cancel, revoke, leave, and
pasted-link acceptance. The local E4-D path now implements the default-off signed download,
artifact/archive validation, account binding, atomic managed install, user LaunchAgent single-instance
control, health-gated commit, rollback, and restart recovery path. The packaged UI exposes a
two-stage “download and verify → exact release confirmation” action only when complete pinned release
configuration and the frozen `hermes-serve-v1` contract match a separate Gateway capability. Default
builds without enablement keep installation unavailable.
The migration core now owns separate exact-label Hermes Server and Connector LaunchAgents. It starts
Hermes first and requires a new process-specific ready marker plus healthy loopback probe before the
Connector may start; failed new setup stops both managed services and returns to the uninstalled state.
Each launchd mutation waits for the exact label to converge, so a successful `bootout` whose removal
finishes asynchronously cannot be mistaken for a failed migration or a completed rollback.

The signed path also handles an already active managed installation when the verified target is a
strictly newer semantic version. Upgrade preserves the account credential, binding ID/generation,
Hermes data, and local session token. Before rewriting either LaunchAgent it stores an owner-only exact
snapshot; it stops Connector before Hermes, waits until the old loopback listener on port 9119 has
actually disappeared, then starts Hermes before Connector and requires fresh local plus Cloud health.
Failure or app restart restores the exact prior LaunchAgents and bundled `current` pointer and proves
the old service pair healthy before returning the journal to `account_active`.

When the Gateway separately advertises `accountDeletion`, Desktop exposes a danger-zone flow that
requires typed `DELETE`, an explicit permanence acknowledgement, and a fresh email code. It revokes
the Cloud account and then clears only the Desktop management session; the Connector machine identity
and all local Hermes data remain on the Mac. This capability and its production route remain
default-off.

Email-first rollout note (2026-09-08): the local E7 Desktop slice now implements email challenge/code
login and scoped email recent reauthentication for device invitations and managed-phone revocation.
Google is not shown on the
first-release account surface; its existing implementation remains dormant compatibility code.
Production account mode remains default-off until the live transactional-mail and packaged-Mac gates
pass (historical rollout record; legacy Desktop connections are now retired). Local evidence and remaining release gates are in
`../docs/ACCOUNT_MODE_E7_DESKTOP_TEST_RECORD.md`.

## Current boundaries

- Managed service operations preserve Hermes data and require the existing explicit setup/repair confirmation.
- The existing Gateway protocol and Android configuration remain unchanged.
- The GUI never starts a second Connector with the same device ID.
- Retired startup items block new setup with `HR-MIGRATE-022`; unknown services on loopback port 9119 remain read-only. A clean
  Mac can reach managed Bootstrap only in an explicitly configured build against a matching Gateway;
  the default packaged app cannot download or install anything.
- The app declares macOS local-network ATS access so its `URLSession` health probes can reach the
  pinned loopback Hermes endpoint on macOS 14 and later. Public HTTP remains disallowed; the exception
  does not enable arbitrary network or WebView loads.
- Managed-candidate Hermes health uses a dedicated ephemeral URL session with system HTTP/PAC proxies
  disabled. The proof must reach `127.0.0.1:9119` on this Mac; public Relay traffic continues to use
  the normal system networking configuration.
- Candidate Hermes and Connector health each receive up to 75 one-second polls. This covers a measured
  35-second cold start on the physical Mac mini without weakening the exact marker and HTTP proof.
- Managed setup requires exact user confirmation. Retired startup items must be removed first;
  failed new setup returns to `clean_uninstalled`. A proven account-active installation may suppress
  a duplicate retired job restored by Migration Assistant, but cannot start or restore it.
- An exact committed installation without the session-token contract marker is reconciled once at
  Desktop startup. Both managed LaunchAgents must be owner-only, point to the current managed
  executables and exact log paths, and either agree on the same valid inline token or already agree on
  the canonical private token file. The current account binding ID and generation must match before
  any file or service mutation. Immediately before an already-bound Connector starts, Desktop records
  the Cloud `endToEnd.checkedAt` value and accepts the restart only after the same binding reports a
  strictly newer healthy value. A cached healthy snapshot cannot commit the candidate or its rollback.
- Download/verification occurs before the exact version confirmation and cannot mutate installation,
  credentials, LaunchAgents, processes, or bindings. Closing the confirmation removes the private
  workspace. A committed install that cannot clean temporary files exposes only a cleanup retry and
  never offers a second install.
- An active managed release offers “升级并重连” only for a strictly newer pinned or signed component
  release. Same-version and downgrade requests remain read-only. Upgrade never calls binding creation
  or confirmation and never rewrites the account credential or local session token.
- On a later Desktop launch, an `account_active` journal plus both exact managed LaunchAgents is shown
  as the active installation. An intermediate journal is recovered before any new install is allowed;
  mismatched journal/service state fails closed with a registered migration issue. A completed rollback
  to `clean_uninstalled` admits a newly confirmed migration run and atomically replaces
  the terminal journal; when that run's pending cloud binding has since expired, only its recorded
  generation may be recreated. Active, intermediate, unrelated revoked, and manual-attention states
  remain non-replaceable.
- Existing-install observation/recovery does not depend on the new-install flag. The active journal's
  binding ID/generation must match the signed-in account, so account B cannot claim or replace account
  A's already managed service on the same Mac.
- Account-session bearer material and the future Connector Ed25519 machine identity use separate
  this-device-only Keychain items.
- Refresh/removal/sign-out idempotency keys are persisted before their request so a lost response can
  be retried without minting or revoking the wrong credential.
- Sign-in and reauthentication codes stay in UI memory only and are never written to Keychain or
  diagnostics. Email exchange and reauthentication retries reuse their original idempotency keys.
- A pending `device.share`, `account.installation.revoke`, or `account.delete` grant and its idempotency key are stored
  only in the account-session Keychain record until an ambiguous mutation completes; invite tokens
  remain in memory and
  all `hga_`/`hgr_`/`hgg_`/`hsi_` values are redacted from diagnostics.
- Sharing remains hidden unless the Gateway advertises it. Operators can use/select/leave a shared
  Mac but cannot invite others or manage its binding.

## Build and test

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
npm run desktop:dmg
```

Default builds contain no Google client ID. Email login needs only an `email_otp`-enabled Gateway at
`HERMES_GO_ACCOUNT_GATEWAY_URL`; no provider client secret is embedded in the app. The following
command is retained only for later Google-provider development, using an approved Google **Desktop
app** OAuth client:

```bash
HERMES_GO_ACCOUNT_GATEWAY_URL=https://relay.example \
HERMES_GO_GOOGLE_MACOS_CLIENT_ID=example.apps.googleusercontent.com \
npm run desktop:app
```

The app opens Google's account chooser in the system browser with PKCE S256, state, nonce, and a
temporary `127.0.0.1` callback. It never reads Chrome/Safari profiles or stores a Google access token.

Overview and Diagnostics use the selected account device's authenticated health. Local probes use
only the configured account Gateway and the fixed loopback Hermes contract. No saved App Token,
retired config, or retired log can influence the displayed health.

`desktop:app` and `desktop:dmg` use ad-hoc signing when `SIGNING_IDENTITY` is unset. A public build
requires a Developer ID Application identity and Apple notarization credentials; see
`docs/DESKTOP_PHASE0.md`.

The desktop icon is copied from the Android app's canonical
`android/app/src/main/ic_launcher-playstore.png`. Run `desktop/scripts/sync-app-icon.sh` after that
source changes. Packaging fails if the two files differ.

## Structure

- `Sources/HermesGoDesktopCore/` — status, retired-service conflict detection, OAuth/account transport,
  multi-device selection, signed release acquisition, managed installation/migration, Bootstrap
  planning, Keychain session/machine identity, probing, and redaction.
- `Sources/HermesGoDesktop/` — SwiftUI window, menu bar, account/devices, and diagnostics.
- `Tests/` — account setup/recovery, state-reduction, and security tests.
- `Packaging/` — static bundle metadata and the synchronized app icon.
- `scripts/` — local `.app` and `.dmg` packaging.

0.2.43/build 46 adds an explicitly confirmed migration from the historical bundled Connector layout to the signed Node/Connector component channel. It preserves the Mac’s own Hermes, its home, private token and account binding, and retains durable health-gated rollback. Component-only releases no longer require a Hermes archive. Physical migration acceptance is separate from publication.
