# Hermes Go Desktop test plan

## 2026-10-30：退出后的连接恢复与可见入口

测试影响：原故障是启动项 loaded 而 Connector 进程 stopped；安装检测不能替代进程检查。回归覆盖实际 launchctl 顶层状态/进程证据（包括资源 coalition 的嵌套状态）、运行/未知/未加载均不触发自动 kickstart、停止任务在两种安装布局下只启动原 Connector、凭据/账号/启动参数不匹配和本机认证失败时零变更、失败的启动和旧健康记录不报成功、进程在检查期间启动时不重复干预，以及 60 秒冷却/三次上限。

所有恢复夹具只写临时目录并使用假 launchctl 和账号；验证身份、Hermes/Connector plist 字节及本机服务保持不变。隔离 `desktop-operation-preview.swift` 验证更新刚开始时不误报取消、确认阶段收起进度面板、执行阶段重新显示。运行 assets、完整 Swift 测试及 app 构建。

本轮结果：73 项相关回归、完整 740 项 Swift 测试、assets、18 组生产 NSPanel 浅/深/窄界面夹具及隐藏/重新展示断言通过，Release 编译成功，`git diff --check` 通过。最初受限环境中的端口、子进程和图形测试失败；2026-10-30 发布前在沙箱外完成 740 项零失败复核，并确认同一 iconset 可正常生成 icns。无需修改图标或削弱测试。正式发布物仍须从干净的 main 工作树重新构建并验证完整 DMG，不复用先前失败打包的半成品，不将夹具当作实机自动恢复验收。

发布后的实机验收仍需：在授权测试 Mac 停止 Connector 后重新打开 Desktop，确认自动恢复显示执行中与新云端验证成功；改选另一台设备，确认概览/诊断重连始终作用于本机；失败达到上限后可手动重试；浅/深/窄窗口入口和信息不截断。不得把隔离测试或此前手动 kickstart 成功当成本轮自动恢复的实机证明。

## 2026-10-28：旧版 launchd AppleScript 回归

测试影响仅为本机启动项静态解析。覆盖旧上游生成的 PM/Python、空格、Unicode、单引号、双引号和反斜杠路径；附加语句、非官方转义、额外参数、错误执行器、shell 展开、重定向改写和截断保持不支持。检测层应确认版本、保留原 plist 字节，并继续拒绝外部安装和自定义 HERMES_HOME。HR-MIGRATE-008 双语、恢复操作及脱敏契约保持不变。

本轮本地结果：旧解析器在三组上游生成的合法命令样本上失败；修复后解析测试 6 项、全量 Swift 测试 728 项通过，assets 校验与本地 app 构建通过，`git diff --check` 通过。未发布、未安装到 M2，没有执行 Hermes 升级或改动用户启动项。

M2 只读实机验收（2026-10-28）：macOS 14.8.9，Desktop 0.2.48/build 51，Hermes 0.21.4/source c0d7294769a38c17ceae51d8f7995e66e1dcae27。真实 plist 是旧版三参数 AppleScript；旧解析器返回 nil，新解析器正确还原 venv/bin/python。将生产 Core 编译为仅调用 detect() 的探针，在 M2 返回 usable、dependenciesConsistent=true、venv/bin/hermes。真实参数仅归一化用户名后加入固定回归样本；不读取凭据、不执行启动脚本、不重启或升级 Hermes。探针运行后删除临时程序，并核对 plist SHA-256 保持不变。剩余验收：发布并安装修复版 Desktop 后刷新界面，确认版本和升级入口；本轮未替换 M2 上的应用。

## 2026-10-27：osascript 安装识别回归

测试影响：只改变本机安装检测和既有端口归属判断，不改变安装器、升级器或用户数据。旧检测器在标准安装包装和未知包装诊断两项回归中失败；修复后针对性测试 38 项、完整 Swift 测试 723 项通过，assets 校验与 app 构建通过；发布/更新工具测试 15 项通过。原有 HR-MIGRATE-008 双语、可重试性和脱敏契约不变。

- 固定夹具由上游 v0.22.5 的纯 `launchd_program_arguments` 函数生成，覆盖 PM/stderr_timestamp 和含空格、中文、单引号的 Python 启动路径；只解码，不执行输出。
- 临时 home/plist 验证标准安装版本识别、plist 字节不变、gateway 不占用 serve 端口，以及包装 serve 的 9119 归属。
- 保留外部 checkout、自定义 HERMES_HOME、Program 覆盖 ProgramArguments 的拒绝行为；未知脚本、追加 JavaScript、替换语言、shell 展开和非规范重定向不被接受，也不泄漏脚本内容。
- 设备待验：受影响 Mac mini 更新 Desktop 后刷新，确认版本号与实际安装一致、原有 gateway 继续运行。截图只确认首参数为 osascript；该机器完整启动参数尚未读取，不能把夹具测试当成该机验收。

## 2026-10-27：操作窗口回归与视觉验收

测试影响评审：这是只读呈现变更。回归覆盖操作名称、运行/等待确认/待清理/终态区分；时钟刷新不算进展，静默不产生失败；失败重试重置计时，关闭窗口保留任务；有实际输出才更新进展，日志限量脱敏；健康服务与不受支持安装并存时不可全绿，管理方式不可冒充版本。沿用已有错误码与中英文/恢复/脱敏测试，无新增错误类别。

执行 `npm run desktop:assets:test`、`npm run desktop:test`、`npm run desktop:app`。隔离原生验收脚本 `scripts/desktop-operation-preview.swift` 直接编译生产状态适配层和 NSPanel，不实例化真实 DesktopViewModel，不访问钥匙串、账号或 launchd。覆盖下载、等待确认、无新进展、失败、清理待处理、成功 × 浅色/深色/窄窗口；检查关闭/重新打开保持任务代次，适配层重试调用正确入口。

```sh
swiftc -parse-as-library -I desktop/.build/arm64-apple-macosx/debug/Modules desktop/.build/arm64-apple-macosx/debug/HermesGoDesktopCore.build/*.o desktop/Sources/HermesGoDesktop/DesktopOperationStatus.swift desktop/Sources/HermesGoDesktop/DesktopOperationWindow.swift scripts/desktop-operation-preview.swift -o /tmp/desktop-operation-preview
/tmp/desktop-operation-preview
```

本轮结果：710 项 Swift 测试全部通过，assets 检查与最终 `desktop:app` 构建通过（本地临时签名，未发布）。生产状态适配层和 18 个原生进度窗口夹具通过；关闭重开保留状态与计时，Desktop 更新待清理保持“需要处理”并调用清理入口。直接审阅浅色/深色/窄窗代表画面；另渲染本机服务健康但安装不受支持的 3 个布局，确认“服务可用，升级受限”“版本未识别”与本机验证已通过可以同时清楚显示。日志证据为 `/private/tmp/operation-tests-final.log`、`/private/tmp/operation-app-final.log` 和 `/private/tmp/operation-ui/result.log`。独立分支 `codex/desktop-operation-progress`，未修改 WebApp 工作树。

本机验收输出在 `/tmp/operation-ui`（macOS `/private/tmp/operation-ui`）。模拟原生窗口不是实际升级验收；仍需在发布后，用获准的测试 Mac 验证组件准备→确认→服务切换→Desktop 重启，以及真实网络中断后的重试。不要为验收升级或停止用户日常 Hermes。


## 2026-10-27：WebApp 登录设备回归（0.2.47 / build 50）

测试影响：覆盖 phone/browser 混合列表及 phone-only 旧属性、能力字段缺省/存在的解码、旧服务器只读降级、邮箱再验证、浏览器 DELETE 路由和授权体、丢失响应后复用 grant/idempotency key。Gateway 覆盖 Desktop-only、缺少 grant、功能关闭、目标类型限制；真实 PostgreSQL 验证账号隔离、撤销后 access/refresh 失效、其他浏览器/手机/Desktop 不受影响、精确重试。没有新增错误码，保留原鉴权/目标/幂等错误及其本地化映射。

本地结果：Swift 706 项通过，资源检查和 app 构建通过（临时签名，不是发布物）。Gateway 启用独立 PostgreSQL 后 253 项通过、14 项按各自外部条件跳过、0 失败。根 npm build 通过；根 npm test 的协议 18、Connector 156、Gateway 225（未启用数据库时）、release-server 41 项通过。脚本测试 442 项通过、1 项跳过、2 项旧 legacy-recovery 解密失败；已在未修改 main 快照复现相同两项，不能把根基线写为全绿。

原生卡片夹具：`scripts/desktop-login-devices-preview.swift`，与生产 `HermesDesign.swift`、`AccountLoginDevicesCard.swift` 一起链接 Swift debug Core 对象；输出混合、旧服务器、空、忙碌 × 浅色/深色/420pt 窄窗。结果位于 `/private/tmp/webapp-login-devices-ui/`。该夹具只用模拟数据，不发送验证码或操作真实登录。

上线后待验收：手机浏览器与主屏幕 WebApp 实际登录，在 Desktop 刷新后核对类型/活动；完成一次邮箱验证撤销，确认该浏览器下一次请求要求重新登录、其他登录不受影响；重新登录后记录恢复。同一手机的浏览器和独立 WebApp 可能是不同安装记录，应分别显示。未操作用户真实账号或部署 Gateway。


## 2026-10-27：下载与阶段重试回归（0.2.46 / build 49）

测试影响：覆盖真实 Git URL 重写且全局配置不变、手动/显式代理一致传递、Node 原生下载走回环测试代理、CR 行在进程退出前可见、Git 字节与对象计数分离、阶段失败/修改代理后重试、目标/脚本/环境改变、实际 PM 文件缺失，以及界面丢弃过期阶段事件。原先无凭据输出、目标版本检查、诊断失败和重复点击测试继续运行。结构化错误使用既有 HR-HERMESUPDATE-004/005，无新错误码。

本轮结果：资源检查通过，完整 Swift 基线 **701 项通过、0 失败**，本地 `desktop:app` 构建通过（临时签名，不是发布）。13 项下载/重试测试包含真实 Git 临时仓库克隆、带空格路径、原生 Node 经本地 HTTP CONNECT 代理请求、实时 CR 输出和 PM 环境删除；原有升级测试新增状态事件跨阶段隔离；代理地址省略端口按协议默认值处理，显式凭据或当前无法表达的代理协议会报错，不静默直连。另用拒绝型回环代理确认 URLSession 的官方脚本 HTTPS 请求确实发出 CONNECT 到指定代理；只读核对本机真实 0.22.5，repository/venv/python-deps/config 四项阶段证据均可识别。

在 macOS 原生 NSHostingView 渲染下载、失败、保留四阶段后重试、静默等待四状态 × 浅色/深色/490pt 窄窗，共 12 张夹具图；已直接审阅代表性浅/深/窄窗截图。48 秒静默截图确认旧速度隐藏、等待文案完整且未误报卡死。失败按钮命名当前阶段，保留目标版本。没有替换用户应用、改变全局 Git/系统代理、操作服务或再次执行真实 Hermes 升级。真实远端网络下的完整用户升级、实际按钮点击与退出重开仍未验收；本轮不支持跨 Desktop 进程阶段恢复。

本地证据：`/private/tmp/hermes-download-baseline.log`、`/private/tmp/hermes-download-package.log`、`/private/tmp/hermes-download-ui/`。源分支 `codex/hermes-upgrade-download` 已通过 PR #683 合并；0.2.46 的发布门禁和限制见 DESKTOP_RELEASE_CHANNEL.md。


## 2026-10-27：升级进度回归（0.2.45 / build 48）

测试影响：增加阶段计数/顺序、失败保留未完成步骤、重试重置、耗时格式及检测结束边界的回归；现有真实 POSIX 夹具同时断言阶段事件顺序和失败停止。不修改上游安装器调用参数，不新增错误码，既有 HR-HERMESUPDATE-004/005 及双语/脱敏测试仍需通过。

原生交互验收使用 scripts/hermes-simple-upgrade-preview.swift：复用生产卡片/管理器，模拟 products 阶段停留 50 秒，检查九步计数、耗时跳动、无输出提示、浅/深主题和检测异常。不得启动日常升级，也不把模拟操作记成真实安装升级。

本轮结果：16 项聚焦、687 项 Swift 全量、assets、release app 构建及严格 ad-hoc 验签均通过。隔离原生窗口实际点击升级，观察第 3/9→6/9→7/9 步及 5/9 完成进度，浅/深截图文字完整，耗时从 13 秒到 32 秒持续增加；最终检测通过，切换模拟异常并重新检测显示 HR-HERMESUPDATE-005。45 秒静默提示的短暂出现未单独截图确认，后续手工验收需在长阶段停留超过 45 秒核对。功能实现轮未操作日常 Desktop/Hermes，也未分配版本、打包发布或替换当前应用；后续 0.2.45 发布步骤见 DESKTOP_RELEASE_CHANNEL.md。


## 2026-10-27：稳定版点击升级首阶段

本节为当前首阶段验收范围，下方 HG-199 / B 方案记录属于历史，不是新交付条件。

自动回归覆盖：无需推荐认证即可升级；当前/目标同版、更高本机版本、未安装不启动；下载失败；退出成功但版本/commit 不符判失败；依赖中断后可重试；重复点击仅执行一次；新错误码双语/重试/脱敏/编码；目标 commit 贯穿所有官方阶段；失败后不执行后续阶段；真实 POSIX 子进程执行夹具脚本；升级后自动调用检测，失败/未知不误报全部通过，重新检测不重复安装。

本轮本地基线：`npm run desktop:assets:test` 通过；`npm run desktop:test` 684 项通过；`npm run desktop:app` 成功并生成 ad-hoc 本地应用。新增两项错误码仅由 Desktop 本地升级流程产生，Android/Web/Connector/Gateway 无接口或错误响应变更，无需调整消费者。

实际交互：`scripts/hermes-simple-upgrade-preview.swift` 仅为隔离 host，复用生产 HermesVersionCard 和 DesktopHermesVersionManager，安装及健康数据明确标为模拟；未初始化日常 DesktopViewModel。已点击升级，界面 0.21.3→0.22.5、自动检测通过；模拟检测失败后“重新检测”显示 HR-HERMESUPDATE-005，再恢复后通过；直接检查截图，无文本截断。这不是已安装日常 Desktop 的实机升级证明。

真实安装器独立冒烟：`scripts/hermes-simple-upgrade-smoke.swift` 接收带 ISOLATED_TEST_HOME 标记的 `/private/tmp/hermes-simple-smoke-*` 合成用户副本，写入限于副本，使用随机本机端口，不运行 GO 日常服务。实际结果：原始合成副本为 0.21.3 / `633dda6d7fdcac7e8340471c9f5ed08cdccbd70c`，官方查询目标为 0.22.5 / `bf0203a794a07aac3ff764972ab9087baa4e4f4e`。初次运行在 venv 阶段被测试写入限制拦截；修正 macOS mktemp/uv 缓存的隔离目录规则后，在同一副本重试，六个官方阶段均返回成功。随后发现旧检测器只能读取静态版本声明，复用 commit 绑定的 install-stamp 读取及四项回归后，真实版本识别通过。独立 `--verify` 运行启动随机 loopback 端口，认证 `/api/status` 返回目标版本、`/api/sessions` 返回 200，进程组取消并等待退出，输出 `HERMES_STABLE_SMOKE_OK`。这包含失败/重试和独立运行检查，不宣称一次点击完成全部真实七项 GO 端到端验收。

手工发布验收：发布后在授权测试 Mac 的本机 Hermes 页面检查真实版本/说明、点击升级；验证概述相同的七项诊断被自动刷新（未登录/无云端结果保持待确认），已运行 GO 服务通过现有运行时重启逻辑切到新代码；不把本机接口通过写成手机实测。日常安装替换、生产账号服务/手机链路、发布均不在这次隔离验收中执行。


## 0.2.42：发布与实际客户端检查（2026-10-25）

从干净发布提交 `e8e2559f` 构建，assets、660 Swift、15 发布/更新工具测试、release app/DMG、
严格 ad-hoc 签名、挂载后的版本/build/arm64/公开配置和镜像完整性均通过。
统一发布器受控重试一次后两个成功标记通过；完整公网两个 DMG 的大小/哈希一致，两个发现索引
均为 0.2.42/build 45，签名组件两个指针原字节不变。未放宽门禁或覆盖不可变文件。

实际 `/Applications/Hermes Go Desktop.app` 从 0.2.41/build44 经应用内“检查更新→更新 Desktop”
安装并自动重启为 0.2.42/build45，安装后严格验签通过。版本检查实际显示 0.21.3/`17b5df02f2a7`、
上游稳定 0.22.5、GO 推荐尚无验证结果及 2026-10-25 08:19 检查时间；条件检查明确显示
“自定义 Git 配置阻止安全检查，暂不能升级”，无待恢复操作、无进行中的其他更新，HR-HERMESUPDATE-002。
刷新记录显示无记录，页面只有三项只读操作，无升级或恢复执行按钮。

前后服务 PID（Hermes 78790、Connector 32106）、源码 commit、Git 配置与索引、Hermes 配置和
无升级记录状态均不变。数据库/WAL 元数据有变化；持续运行的服务未停，不据此证明数据库无写入
或归因于某个按钮，未读取真实历史内容。账号起初停在检查，用户本人报告处理系统确认后，
实际账号恢复，概览显示
Desktop 0.2.42 正常运行、本机 Hermes 0.21.3 就绪、云端连接正常、模型已配置；手机访问健康显示
通过，但不是手机实际业务操作。恢复后本机页再次读取到明确配置阻断与无记录状态。
真机不可用，手机历史/收发及 Android L1/L2/L3 本轮未运行；干净机器、重启和 Apple 公证未执行。HG-199 整体仍进行中。

## 只读条件检查尾项（2026-10-25）

测试影响：现用 Hermes 仓库仅检查配置键名，确认含 `core.sshcommand`，触发已有拒绝规则；
独立 Git 子命令成功不代表通过配置安全审核。此结果合理，原界面将已知拒绝混为 unknown 的说明不足。
新增明确配置阻断状态，沿用 HR-HERMESUPDATE-002、不可重试与查看诊断操作；诊断只含固定类别，
不含配置值、命令、私有路径或凭据。不改变沙箱、输出/期限、用户配置或升级关闭状态。

回归：自定义 SSH 命令在旧实现得到 unknown，新增断言先失败；修正后应明确配置阻断，命令标记
不产生、配置字节不变、Git index 不创建。filter/include 等拒绝用例同步期待明确结果，其他读取失败
仍为 unknown；所有状态均不允许执行升级，覆盖双语提示、固定脱敏诊断及零记录创建。
本地结果：12 项聚焦、660 项 Swift 全量、assets 和 ad-hoc app 构建通过。
用本次实际 Core 对现用源码执行只读检查，明确返回配置阻断；配置字节与 Git index mtime 不变。
复用原生卡片验收器生成 clean/dirty/unknown/配置阻断的 8 份浅色/深色紧凑图，已查看配置阻断
的两份画面，文字完整，stub model/服务回调为零（另有上述真实只读 Git 检查）。
此处为 #666 合并时的未发布源码核验；随后实际 0.2.42 安装/发布验证见本文件上一节。

手机尾项：本次能力探测仅发现未授权真机；用户明确回复“真机目前用不了”。实际手机打开历史、
发送及接收尚未执行，保持待验，不清数据/重装登录，不以模拟器或 Desktop 健康显示替代真机结果。
该设备条件也阻断完整升级功能的最终跨端验收。一键升级继续关闭，#560 保持草稿，不扩展实验。

## 0.2.41：发布与实际安装客户端验收（2026-10-25）

本轮仅记录已完成的只读功能发布与现场结果，不新增实验或执行器。文档改动无新的行为测试；
复用发布提交 `66f62ed7` 的 assets、659 项 Swift、15 项发布/更新工具测试、app/DMG 构建与校验。
PR/main 检查通过，两个公网 DMG 全量大小/哈希一致，网站和应用更新索引均为 0.2.41/build 44，
两个签名组件指针仍为原字节。包内不含 updater worker，一键升级/恢复执行持续关闭。

实际目标：LGS-MACMINI 的 `/Applications/Hermes Go Desktop.app`。通过原 0.2.39/build 42 的
“检查更新 → 更新 Desktop”操作更新，旧进程退出，新进程运行 0.2.41/build 44；安装后严格签名校验通过。
没有手工替换 app，也没有操作 Hermes 升级/安装/修复或服务重启。

| 现场项目 | 实际结果 |
| --- | --- |
| 检查 Hermes 版本 | 已点击；0.21.3 / `17b5df02f2a7`，上游稳定版 0.22.5，GO 推荐尚无验证结果，展示检查时间及未认证提示 |
| 刷新升级记录 | 已点击；无本机升级记录，不创建记录文件或目录 |
| 检查升级条件 | 已点击；本地改动无法确认，无待恢复操作、无进行中的官方更新；`HR-HERMESUPDATE-002` 保守阻断，无升级/恢复执行按钮 |
| 只读检查边界 | 前后 Hermes PID 78790、Connector PID 32106 未变；源码 commit、配置字节/元数据、升级记录仍缺失均不变 |
| 账号恢复 | 起初停在账号检查，系统安全窗口不能由计算机控制访问；用户报告本人已处理后，账号恢复为登录状态，本机 Hermes 0.21.3 服务就绪、本机验证通过，模型已配置 |
| 恢复后概览 | 实际界面显示本机与远程连接均正常、云端响应正常、Desktop 0.2.41 运行正常；后台运行、云端连接及手机访问健康状态均通过。这不替代实际手机业务操作 |
| 本地改动未知的诊断 | 另一次同参数只读沙箱 Git config/root/status 各自成功，但不能据此把实际 UI 的 unknown 改记为 clean/dirty；未放松沙箱或追加执行实验 |

用户处理系统确认后已实际读取概览与云端健康，并重试条件检查；本地改动仍 unknown，升级仍阻断。
恢复后再次核对，两个服务 PID、Hermes 源码/配置与升级记录缺失状态仍保持原样。
不将此次记录写成全部升级条件通过。数据库及 WAL 的元数据在
持续运行的 Hermes 观察期间有变化；未读取真实历史内容，不能据此证明数据库无写入或归因于某个按钮。
Android L1/L2/L3、手机业务、干净机器、重启和 Apple 公证未执行，均不能由上述自动测试替代。
HG-199 整体仍进行中，#560 保持草稿，维护/升级执行前置条件不在本轮扩展。

## Compact signed-in account footer (2026-10-24)

- Signed-in footer: avatar, nickname (or email prefix) and a single vertical-ellipsis menu; no persistent email row. Long names truncate on one line and retain their full-name tooltip.
- Account details and menu retain the full email and existing copy/manage actions. Signed-out and expired-login guidance remain visible.
- Test impact: presentation-only removal; existing account-name tests remain applicable. Verify light/dark and long-name native fixtures, account detail access, assets, Swift suite and app packaging. No new state-machine tests for removing a text row.

## HG-199：只读版本管理拆分验收

测试影响：从 #560 提取只读网络检查、沙箱 Git 条件检查、严格 journal 读器与展示；
不引入 updater/事务写入器或 PM/维护实验。已有服务动作仅增加待恢复记录阻断。

- 版本：固定 tag/commit、默认无推荐、凭据/cookie 隔离、地址/重定向、重复键、布尔类型、
  512 KiB 限制、完整期限、取消与结构化双语错误。
- 条件：真实 Git dirty/clean/unknown、配置/filter 不能执行、索引时间不变、根目录与有限输出/期限；
  既有更新或待恢复记录先于 Git 观察拒绝；即使干净也 executionAvailable=false。
  v2 owner/delegate 在旧标记过期后仍有效，PID 创建时间不同不算同一更新；该回归在旧实现先失败再修复。
- 记录：读取无记录不建目录，所有阶段/终态不冒充活跃/健康；重复/转义等价键、未知嵌套字段、
  不安全权限、链接/多链接、过大文件拒绝且保留原件，诊断不含私有路径或原始内容。
- 互斥：待恢复记录阻断 migration lease、runtime 自动拉起/disabled 清理和 token/path 修复；
  历史终态允许原有操作。完整 Desktop baseline、assets 和 app 编译仍为交付门禁。
- 原生检查：本机页与记录卡浅/深色、紧凑宽度、未登录、网络失败、待恢复与历史终态；
  夹具使用 stub model，不启动监控或现用服务。真实安装客户端升级/手机任务不在本次验证范围。

本地结果：27 项只读功能聚焦、最终 659 项 Desktop 全量、assets 与 ad-hoc app 打包通过。
v2 标记回归在旧实现复现两处失败，修正后纳入全量通过。复用现有原生验收器生成 22 份卡片/
banner 浅色与深色紧凑夹具，已查看待恢复和历史终态关键画面；回调计数为零。
未运行实际安装客户端/服务操作、Android L1/L2/L3、Node/Web 本地基线或分发发布。

## 2026-10-24：状态与安装体验细化

本轮本地验证：629 项 Swift 测试、资源一致性与应用打包通过。10 个原生 SwiftUI fixture 覆盖深浅色概览、紧凑窗口、长邮箱、已配置/未配置/未知模型、无 Hermes、设置及安装重试；预览不运行真实安装或修改本机服务。

概览和本机 Hermes 并列展示版本、本机验证、模型配置；正常打开按钮为普通样式，模型未知/过期保持中性。账号显示昵称或邮箱前缀、完整宽度邮箱与单一菜单入口；我的设备改电脑图标；深色侧栏辅助文字有独立颜色；设置版本及更新状态同行。

安装引导串联安装、连接、模型配置和手机任务验证。官方脚本下载与明确网络失败阶段最多 3 次尝试，等待 2/4 秒，可取消；仅重试当前阶段，不自动重跑成功阶段。记录并重新检查 checkout 身份，避免等待时被替换后继续写入。安装脚本下载报告实际字节/可用总量和速度，依赖仍由官方安装器负责，不生成虚假的整体百分比或 ETA。阶段完成数明确标为“步骤”。手动继续仍遵循原有受管目录身份校验和官方复用，未下载完的依赖可能重下。

签名组件原有 Range 续传、完整大小和 SHA-256 校验保留；服务器忽略 Range 返回 200 时，在长度验证后截断并重新下载，绝不拼接新旧文件。大组件没有新增多连接分片；Hermes 上游依赖的并发、缓存与续传由上游工具决定，不接管它们的下载地址或改变安装源。

回归覆盖邮箱命名、未知/过期模型状态、临时错误后成功与重试上限、不可重试的 401/404/证书/协议错误、取消等待、阶段局部重试、下载进度、206 续传及 200 回退。原生 fixture 检查深浅主题、紧凑宽度、长邮箱、三种模型状态、无 Hermes 和安装过程。真实新机器安装、网络故障下上游大依赖恢复和手机任务需物理环境另行验收；不会用 fixture 声称其已通过。


## 2026-10-24：本轮回归矩阵

| 场景 | 预期 |
| --- | --- |
| 未登录 / 会话过期 / 账号 API 失败 | 本机 Hermes、诊断、设置可达；云端操作需登录 |
| 新账号无设备 / 仅共享设备 | 可先管理设备和接受邀请，也可显式连接本机 |
| 本机未装 Hermes，旧 opt-out 为 false | 新安装仍走官方检测和确认；不创建第二份 Hermes |
| 现有 Hermes 含 profiles 或旧 bundled 安装 | 保留原数据与已有运行策略 |
| 服务就绪、已安装且接口明确未选模型 | 概览和本机 Hermes 页显示提示，旁边有“配置模型”；普通操作栏无该按钮 |
| 已选模型 | 无配置提示或常驻配置按钮；模型信息在展开详情中 |
| 模型接口不支持 / 无法确认 | 无配置提示或按钮，详情说明未知，不推断未配置 |
| 未装 Hermes / 安装修复中 | 未安装时无模型提示；操作锁定时配置按钮不可点 |
| 窄窗口 / 深色模式 | 模型提示与按钮自动换行、不截断；点击仍打开配置指引 |
| 分享 API / 设备列表 / 登录设备列表局部失败 | 对应区域提示，保留会话；设备列表失败不推断安装名额 |
| 可选 API 返回认证撤销 | 仍清理失效会话并要求登录 |
| 手机有历史登录记录 | 不自动完成引导；明确需要手机选择本机打开会话 |
| 更新完成 / 已是最新版 | 确定关闭；无升级前警告及重复状态 |
| 我的设备 / 接受邀请 / 邀请账号 | 列表按归属分组；权限说明与确认随操作显示 |

验证层次：Swift 状态机、控制器假 API 与固定 loopback 请求测试，以及原生 SwiftUI fixture 截图。真实新机器安装、真实邮箱首次注册、手机访问与实际模型推理必须单独验收，fixture 通过不能替代这些端到端结果。


## Overview visual hierarchy acceptance (2026-10-24)

Local validation: 606 Swift tests, 41 Desktop packaging/tooling tests and asset consistency passed.
Native SwiftUI fixtures reviewed: healthy light/dark/compact, expanded evidence, long device name,
signed-out, stale account evidence and failed repair. Native title hiding was asserted. These fixture
runs did not start monitoring, repair services or replace the installed app. The packaged release is
built again from clean merged main before publication.

- Automated: each indicator preserves its own evidence boundary. A running background can coexist
  with a disconnected cloud, a failed mobile check or an unverified account; stale/future/signed-out,
  manage-only and busy states cannot become green through aggregate card color.
- Native fixtures: healthy light/dark, compact, long Mac name, signed-out, failed/blocked repair,
  and stale evidence. Check three sections, wrapping controls, semantic contrast and Hermes artwork.
- Expand each details control and verify original evidence times remain visible. Healthy reconnect
  is reachable there; failed Desktop still shows repair outside. Refresh, update and navigation retain
  their handlers. Do not activate service-changing actions in fixture verification.
- Physical app upgrade and phone access remain separate acceptance; image rendering is not proof
  of installed-client behavior.


## Status and diagnostics polish acceptance — 2026-10-24

Test impact: add regression coverage for mixed local/cloud failures, stale/future timestamps, failed
account refresh, missing local binding with selected remote device, and direct cloud latency rather
than reported RTT. Log filtering must combine source/query/keyword predicates and preserve redaction.
Existing service/repair tests remain unchanged; this is presentation plus bounded read-only log access.

Native acceptance: inspect healthy and failed checks, compact/dark layouts, long Mac names and selected
Hermes icon. Header title text must be hidden while traffic lights/window dragging still work. Verify
all seven checks pass before a green summary, repair appears beside a failing background check, no
reconnect is suggested for healthy results, and preflight downloads are clearly historical plans.
Exercise log source/search/warnings filters, empty result, scroll, refresh, copy matching text and
open directory. Old log errors cannot change the current health conclusion. Verify Local Hermes has
one state card without roadmap prose; missing installations must not claim an existing install.

Local validation: 604 Swift tests and asset consistency passed after integrating #641. Native fixtures
covered healthy/failed diagnostics, the log viewer, Local Hermes and overview in light/dark/compact
layouts; a native window assertion verified hidden title text. App packaging passed. These fixtures
do not execute live repair/reconnect, replace the installed app, or establish physical interaction
acceptance. Interactive scrolling/filter/copy/open-directory and packaged window dragging remain
manual acceptance checks.

## Per-Mac transfer budget — unreleased

Test-impact review: reproduce old repair/reconnect refusing an otherwise-valid 8 MiB/s override.
Exercise repair and reconnect under bundled and component-store layouts; preserve exact plist
bytes and keep Hermes running. Both upgrade writers must retain only the bounded rate, not arbitrary
Node environment. Cover lower/upper boundaries, malformed/over-limit rates, rejection before mutation,
private permissions and exact upgrade snapshot rollback. Existing bilingual repair-error contracts
remain in force; no new UI/errors are added.

## HG-245 external read roots in the managed allowlist — unreleased

Test-impact review: `EXTRA_READ_ROOTS` joins `FILE_DOWNLOAD_BYTES_PER_SECOND` and
`CONNECTOR_TRANSPORT_ISOLATION` in `DesktopConnectorFileRate.overrides`, so the operator's
authorized external read-only attachment roots survive managed connector reinstalls and stay
acceptable to repair's exact-environment comparison. The value is validated but passed through
verbatim — normalizing it would make repair reject the operator's own plist over whitespace the
Connector tolerates. Relative entries, every spelling of the filesystem root (`/`, `//`) and
NUL bytes refuse the migration loudly; blank segments are ignored, matching the Connector's own
`resolveExternalReadRoots` parsing. Unit coverage in
`AccountConnectionHealthTests.testExternalReadRootsSurviveAllowlistVerbatimAndRejectBoundaryShifts`
(verbatim passthrough with spaces/CJK, blank value treated as unset, relative/root/NUL rejection);
the opt-in allowlist test now shares the same entry point.

Local validation on 2026-10-29: **732 Swift tests**, 0 failures. Live acceptance remains pending:
after the next managed Desktop release installs on the Mac mini, set
`EXTRA_READ_ROOTS=/Volumes/TiPlus-1T` in the managed connector agent, run one component
reinstall/update, and verify the key survives while the phone still downloads external-root
attachments; until that release ships, the key must be re-applied after each managed
connector regeneration.

Local validation on 2026-10-24: **596 Swift tests**, asset consistency and `desktop:app` passed.
The new repair regression failed on the prior implementation. The cold-start health-window test
now injects the fixture shutdown checker, so the owner's running service on 9119 cannot determine
its outcome. This changes test isolation only; no live service was stopped.

Live acceptance remains pending: install this support before applying the M4-only 8388608 override,
reload only Connector, prove loaded rate and fresh exact-binding health, then verify actual file
bytes/hash/throughput, interactive latency and Android preview/seek. See
[`CONNECTOR_TRANSFER_TUNING.md`](CONNECTOR_TRANSFER_TUNING.md). Unit fixtures and raw TCP measurements
do not establish these live outcomes.

## Existing profiles and repair regression — 2026-10-24 follow-up

Test-impact review: exercise real file-only detector + missing-both-startup-items repair with default,
writer and researcher data, including sticky writer selection; assert all data/config/identity bytes
survive both success and injected Connector startup failure. Pin launcher argv to default. Cover old
launcher replacement when stopped, retention when running and exact rollback. Keep account mismatch,
unknown services, occupied port, tampering, stale preparation and stale cloud-health refusal tests.
Freshness tests cover expired/future/missing report time and expired account reads.

Native fixtures: Overview light/dark compact (one repair action, within Desktop); blocked-repair reason;
Devices only management; Diagnostics reason + stale reports; expanded setup retains confirmation flow.
Local validation: 592 Swift tests and asset consistency passed. Native fixtures cover light/dark,
compact Overview, blocked repair, device management and Diagnostics. A file-only check of the reported
Mac recognizes Hermes 0.21.3 with consistent dependencies; no live service/data changes were made.

Physical acceptance: on the reported Mac, inspect a repair preparation, confirm existing default home
and retained binding/token, execute via app, require authenticated Hermes + fresh cloud health, then
verify mobile access and default/named profile histories. Do not claim this from fixture tests. Repeat
failed-start rollback in disposable homes, never by injecting failure into the owner's services.

## Overview/global account acceptance — 2026-10-24

Automated reducer coverage: healthy requires fresh Cloud/local background/authenticated Hermes and
local-binding end-to-end evidence; selected remote healthy devices cannot hide local failure; stale
or future timestamps, failed account refresh, unverified identity, signed-out/expired, manage-only,
active operations and local Hermes failure never produce a false overall healthy state.

Native view fixture checks cover light/dark, 980 pt window width, signed-out/expired login, local
repair, account management, and phone/new-Mac onboarding in the 769 pt content area beside the
sidebar. These do not run monitoring, install services or constitute physical device acceptance.

Manual: check the three summary links scroll to corresponding sections; verify each source’s timestamp,
refresh, repair confirmation, update, local-Hermes and phone-setup actions. Switch selected remote Mac:
overview must stay on this Mac. From every section open the footer account menu, manage account and
sign out; existing services keep running. Signed-out overview must show login actions; footer/Cloud-section
login open the same email-code form. Complete/reject/expire login, verify setup routing and retained
section. Account deletion must still land on its explicit receipt. During install/repair/deletion,
account actions and sheet dismissal remain locked. Check long email truncation/help and narrow/dark layouts.

## HG-235: Desktop product integration

Automated coverage: update sequencing (background before app, failure, cancellation, late completion,
cleanup pending, duplicate action, app-only/background-only); local Hermes usability (files are not
health, authenticated healthy probe required, unsupported vs absent, bundled runtime). Existing
account/installation/rollback/secret-redaction tests remain required. Ordinary bootstrap copy no longer
asserts internal versions, paths or journal-state names; behavior assertions remain intact.

Local evidence: 573 Swift tests passed; asset equality passed. Native NSHostingView renders of the
actual Overview, Local Hermes (missing and healthy), Settings and Diagnostics were inspected at a
770 pt content width in light/dark appearance. The Local Hermes status card was adjusted to full width.
The preview uses injected fixtures without monitoring, network, installers or service changes.
Final `desktop:app`, ad-hoc `codesign --verify --deep --strict`, and `git diff --check` passed. The build retains the pre-existing Sendable warning in DesktopServiceOperationLog.

Physical acceptance remains pending on an already managed Mac with an approved release:
1. Visit all five sidebar destinations; no separate Logs or old URL/Token/QR/observer entry exists.
2. Switch the selected remote Mac; Local Hermes must still describe this Mac. Missing/unsupported/
   installed-but-unverified/healthy/failing states must show the correct action and effect.
3. Trigger app-only, background-only and combined updates. Confirm interruption text; cancel before
   commit and confirm no app restart. Fail background verification or cleanup and confirm the app
   stage does not start. After background success/app failure, retry only the remaining app stage.
4. Open diagnostics from a failure, expand redacted logs and copy the report; verify no credentials.
5. Close/reopen the window and quit/reopen Desktop outside an operation; verify account binding and
   local Hermes configuration/task history are preserved. Verify sleep/logout effects separately.

## Retired Desktop connection mode — 2026-10-24

Current regression coverage replaces the removed legacy config/profile/token-transport tests:

- A retired startup item or loaded job blocks setup, including a running job with missing files and
  a service that appears after preflight. No account binding or service mutation occurs.
- An inert old data directory neither blocks account setup nor supplies endpoints; its bytes remain
  unchanged. The Hermes status endpoint is always the fixed loopback contract.
- Retired jobs never become a healthy fallback. Account-active installations still use their own
  health and retain exact-label duplicate suppression after Migration Assistant.
- Legacy-mode journals fail closed without rewriting their bytes. Clean install, interrupted clean
  rollback, exact-binding retries, account upgrades and rollback keep their existing transaction tests.
- Account capabilities decode with or without old token flags, and never encode/use those flags.
- `HR-MIGRATE-022` has bilingual guidance, a stable serialized code and nonretryable Details recovery.
- The QR generator round-trips an onboarding URL, without a credential-bearing v1 payload.

Run `npm run desktop:assets:test`, `npm run desktop:test`, `npm run desktop:app`, and `git diff --check`.
Local automated evidence: 562 Desktop tests passed; canonical icon equality and `git diff --check` passed. `npm run desktop:app` built the local ad-hoc app and `codesign --verify --deep --strict` passed. The default local package has managed-install/update publication gates disabled; this is build verification, not a release or live upgrade acceptance.

Packaged interactive acceptance remains pending: verify Account & Devices and Settings have no legacy
entry, empty managed logs show empty, account-disabled service offers no fallback, and an existing
account-managed Mac can launch/upgrade without changing binding or losing task state.

The historical matrix below records earlier releases. Rows concerning legacy profile/Token/QR,
legacy migration or restoration are retired, not current acceptance requirements.


## HG-226: progress and terminal sheet acceptance

Automated coverage in `DesktopSetupProgressTests` and the real migration-coordinator fixtures:
both schemas present confirmation only while awaiting confirmation/executing, including completed
cleanup-pending and failed-with-retained-handle cases; elapsed time advances without callbacks,
formats minutes, freezes at terminal state, and resets on the next run; old-run/invalid-ID and
post-terminal events cannot overwrite progress or inject log text; real successful upgrade service
order and journal transitions produce corresponding progress events; a failed candidate reports
rollback while restoring exact old files/services. Existing executor tests retain cleanup-only retry,
same-binding upgrade, preparation validation and rollback coverage.

Run `npm run desktop:assets:test`, `npm run desktop:test`, `npm run desktop:app`, and
`git diff --check`. Inspect the actual progress view locally in light/dark mode with an injected
read-only preview; that does not validate real service mutation or a public release.

Local evidence (2026-10-22): 569 Desktop tests passed, the canonical icon equality check passed,
and `desktop:app` built and verified the ad-hoc app. Native `NSHostingView` previews of the actual
progress view were visually inspected at 540 pt in light/dark appearance, including Cloud waiting,
rollback and a 1 min 25 s elapsed label; no truncation was observed. This was an inert injected
preview, not a real upgrade, terminal-sheet interaction test, signed distribution or device acceptance.

Physical acceptance remains required on a disposable, already managed Mac with an approved package:

1. Confirm a newer signed target on both schema-v1 and schema-v2. The action immediately disables
   duplicate confirmation and dismissal during the final preflight. Do not kill the running app.
2. Observe stopping, starting, local verification, and Cloud-health waiting as they occur. Elapsed
   time must keep advancing during a delayed response; no fake percentage or completion estimate.
3. On success the sheet closes without waiting for the overview/account refresh; the main card shows
   the target version. Verify the same binding and expected service topology.
4. Inject candidate health failure. The sheet says it is restoring the original connection and
   stays locked until recovery finishes. Then it closes and the main surface shows the existing
   structured error. Verify the prior services/version, or the existing manual-recovery guidance.
5. Inject temporary-workspace cleanup failure after a successful commit. The sheet closes, the
   connected result and `HR-MIGRATE-005` remain with “重试清理”, and no install confirmation reappears.
   Cleanup retry must not reinstall or restart either service.
6. Inspect unified logging and the private runtime log for run/stage/journal/UI transitions,
   durations and terminal outcome. Check that no credential, email, private path or account response
   is present. Network/proxy changes and a successful local preview are not evidence of this matrix.


## HG-212 PM runtime and recovery follow-up

Automated coverage adds: PM plus stale legacy venv (the three detector cases failed on old code),
PM-only install, unsafe PM file/parent refusal, PM agent recognition/PATH/no VIRTUAL_ENV/token
preservation/exact legacy rollback, actual command-loading success/import failure/bounded process
termination, completed-stage verification failure remaining pending and verification-only retry,
public health failing authenticated JSON proof, loopback route/token/redaction/redirect refusal,
account report time surviving a newer refresh, diagnostic button wiring/source labels, exact owned-binding
reconnect eligibility, Connector-only restart with unchanged identity/plists/Hermes, stale Cloud
health refusal, local authentication recheck before mutation, and missing-job refusal. Existing suites retain update lock/settle/backoff and
unknown-job, account, credential, concurrent-lease and interruption rollback gates.

Physical acceptance is still required before distribution:

1. Upgrade a Mac with old Python 3.11 venv and a valid published PM command: GO uses PM, one serve
   holds 9119, the same token/home/state.db remain, and private authenticated reads pass.
2. Run upstream's supported update while GO is active; prove no restart during its lock, correct
   post-update process/command, no duplicate 9119 service and no stale generation path in GO config.
3. Restore local Hermes while retaining an older negative account health report. “重新诊断” must
   fetch account state, show separate local success and report time, and retain Cloud failure until
   its newer report succeeds. Failed account refresh shows its code, not a false new timestamp.
4. Confirm “重新连接这台 Mac”: only the proven GO Connector PID changes; Hermes PID, SSH client,
   database and token remain unchanged. Fresh Cloud health is required; another/shared Mac and
   concurrent installation never offer/execute this action. Test interruption/rollback UI.
5. Keep the notebook SSH session open while iPhone Safari/PWA lists history and sends/receives a
   new message. Customer reported Desktop recovered after Connector restart; this end-to-end gate
   has not been reported or executed by this iteration.

Local verification on Macbook-M5: the full Desktop core suite passed (563 tests), canonical asset
comparison and ad-hoc app build passed. The actual Diagnostics and reconnect-confirmation SwiftUI
views were rendered with static fake health data and inspected in light/dark mode; the harness did
not read Keychain or start real services. This is visual layout evidence, not device/Cloud acceptance.

## HG-212 SSH/PWA startup-failure acceptance

`DesktopHermesReadinessTests` covers fresh Python/generic startup failures, separate port-conflict
evidence, fixed diagnostics without credentials or personal paths, existing bilingual runtime issue,
retryability/recovery, historical stderr, nonfatal warnings, incomplete lines, log rotation, symlinks
before/after checkpoint, and bounded growth. The Python/port regressions failed on the old checker.
The ready-marker + healthy-loopback requirement remains covered. Full Desktop tests also exercise
update waits, restart bounds, occupied-port refusal, exact managed labels and rollback paths.

Customer SSH/PWA simultaneous use, actual upstream/venv repair, packaged Desktop details, GO-only
restart preserving SSH and full Mac reboot remain **unverified**. Execute and record the acceptance
steps in [SSH_MOBILE_RECOVERY.md](SSH_MOBILE_RECOVERY.md) on an explicitly authorized customer/test Mac.

## HG-175 repair acceptance

Automated `DesktopManagedServiceRepairTests` covers the 16 combinations of file presence/job loading,
read-only preparation/cancellation, same-binding credential/token/journal preservation, stale handles,
changed release/reference/snapshot/account, unknown/duplicate jobs, occupied port, unsafe files,
operation lease, local launcher and v2 component reuse, readiness before Connector, fresh cloud health,
rollback and restart recovery. Error tests cover bilingual summaries, retryability, serialization and
private-key redaction. Tests use injected commands/probes; they do not modify this Mac's services.

Still requires an explicitly authorized test Mac and real account:

1. Back up its managed agents and installation. With the same committed binding, remove both agents,
   remove each one separately, and unload a task with its file retained. Verify the repair entry and
   confirmation name the original version/runtime and exact files; cancel and verify no changes.
2. Confirm repair. Check existing files remain identical, Hermes is ready before a new Connector,
   both managed tasks load and the original binding generation returns fresh end-to-end healthy.
   Verify the phone still reaches existing sessions/files and the Hermes database is untouched.
3. Repeat with another account, missing credentials, unsafe release evidence, unknown launchd
   arguments and an unrelated process on 9119. Each must stop before machine changes and explain
   the recovery step. Do not delete the cloud binding to work around the block.
4. Interrupt a confirmed repair and simulate a start/health failure. Verify snapshot retention when
   restoration fails, explicit “恢复上次操作” after reopening, unchanged identity and prior service/file
   state after successful restoration, then a separately confirmed repair. Check blocked recovery
   preserves externally changed files/jobs and the snapshot.
5. Inspect the real repair card/sheet in light/dark mode, long file paths, pending progress and blocked
   recovery. The isolated confirmation preview is a layout check, not this live acceptance.


## Automated baseline

Run on macOS:

```bash
npm run desktop:test
npm run desktop:build
npm run desktop:app
```

The current automated suite covers:

- overall status reduction and optional-capability degradation;
- read-only retired startup-item/job conflict detection through injected command execution;
- managed Connector and Desktop log discovery, bounded redaction, and symlink refusal;
- log/header/query/known-secret redaction;
- that the staged Hermes component is importable by a **child** process with no `PYTHONPATH`
  (`scripts/test/desktop-managed-python-path.test.mjs`) — upstream strips the repo root out of every
  child environment, so a bundle that relies on `PYTHONPATH` alone cannot run slash commands;
- account onboarding QR output decoded back to the exact URL with the native QR detector;
- authenticated local-Hermes probing and account-reported end-to-end failure classification;
- bilingual error code, retryability, recovery-action, and diagnostic redaction contracts;
- recent-log warning counting independent of current health;
- full SwiftUI target compilation.
- Desktop account capability discovery and default-off behavior;
- PKCE S256, cryptographic state/nonce, provider cancellation, state mismatch rejection, and a real
  ephemeral `127.0.0.1` loopback callback;
- account HTTP paths/headers, bounded responses, stable error mapping, and diagnostic redaction;
- email sign-in challenge/exchange paths, normalized email, stable installation identity and
  exchange idempotency, macOS session validation, and bilingual `HR-AUTH-009` through
  `HR-AUTH-011` recovery contracts;
- email-only gray-rollout capability gating: after a successful exchange, disabled installation and binding routes
  are not requested, the session remains stored, and the account summary reaches the signed-in state;
- separate account-session and Connector-machine identity stores, Ed25519 challenge signing, access
  refresh, and persisted idempotency-key reuse after a lost refresh response;
- signed-in dashboard reduction, two-phone listing, current-account-email reauthentication before
  one-phone removal, rejection of non-phone targets, and Desktop-only sign-out without deleting the
  machine identity;
- bounded phone-revocation request bodies, scoped `account.installation.revoke` grants,
  Keychain-preserved exact retry after a lost response, and bilingual `HR-ACCOUNT-009` mapping;
- capability-gated permanent Cloud deletion, exact `DELETE /v2/account` body/headers,
  `account.delete` email reauthentication, explicit acknowledgement, Keychain-preserved exact retry
  after a lost response, account-session removal on success, and Connector machine-identity
  preservation;
- capability-gated whole-device sharing routes and decoding, owned/operator device separation,
  owner-only share lookups, exact disclosure acknowledgement, invite-link validation, bilingual
  `HR-SHARE-001` through `HR-SHARE-008` mapping, and credential redaction;
- fresh-email-code `device.share` reauthentication plus Keychain-preserved idempotency/grant recovery
  after lost reauthentication or invitation responses, without persisting the six-digit code;
- strict signed Desktop manifest parsing, pinned Ed25519 keys, tamper/expiry/origin/architecture and
  unknown-field rejection;
- schema-v2 component manifests pin compressed and extracted identities, activation/reuse contracts,
  entrypoints and content-addressed dependencies; Swift and the offline verifier reject unknown or
  duplicate components, dependency identity mismatch, cycles, and invalid compatibility fallbacks;
- resumable v2 component downloads require exact 206/Content-Range semantics, retain only an
  owner-only partial file after interruption, re-read the whole completed file for signed size and
  SHA-256, and remove a poisoned full-size partial;
- offline managed-release publication with owner-only Ed25519 key custody, exact two-archive output,
  independent public-key verification, no-overwrite/partial-cleanup behavior, and redacted
  `HR-RELEASE-004` diagnostics;
- component-archive construction from exact clean Git identities, an allowlisted secret-free Hermes
  source/runtime boundary, production-only Connector JavaScript, bundled architecture-matched
  runtimes, relative launchers, bounded trees, and no-overwrite cleanup;
- schema-v2 component archive construction keeps CPython/bootstrap dependencies, Hermes core, Node,
  Connector JavaScript, and any prepared browser/speech/document roots in disjoint archives; validates
  clean source commits, runtime versions, architectures, executable optional health entrypoints, fixed
  optional dependency edges, and rejects Homebrew/unresolved dynamic-library dependencies; reports
  compressed and normalized content hashes plus separate bootstrap/deferred bytes; removes only artifacts created by
  a failed attempt; and preserves HG-28 child imports through the activation-time core-root `.pth`;
- the arm64 core-only composition starts the real Hermes 0.21.0 server, emits its readiness marker,
  serves `/api/status`, imports its core API modules, and imports `tui_gateway` from a child interpreter
  after `PYTHONPATH` is removed; the measured bootstrap set is 98.12 MiB compressed;
- schema-v2 activation rehashes all four bootstrap components and their owner-only receipts, requires
  executable owner-safe entrypoints and successful bounded health probes, and rejects missing
  components, unexpected bootstrap kinds, or any deviation from the exact runtime dependency graph;
- component LaunchAgents use content-addressed Hermes/Connector entrypoints and inject only the exact
  Python/Node managed roots; v1 writers reject v2 runtime injection and v2 writers reject paths from a
  different managed layout or content mutated after planning;
- the v2 component installer accepts only a strict-verifier installation token, orders fresh
  Python/Node/Hermes/Connector acquisition by dependencies, reuses a second exact install without
  network access, and publishes the release reference only after the final activation plan passes;
- v2 component preparation downloads and extracts only into its private workspace, leaves the managed
  store and release reference absent before commit, binds commit/cancel to the issuing installer,
  rehashes staged content after the confirmation boundary, and publishes no reference after tampering;
- extraction or health failure removes only the current UUID workspace and leaves no release
  reference; a transport interruption instead preserves its partial download and owner-only manifest
  identity marker, clears completed archives/extractions before retry, allows only the same signed
  manifest and run ID to resume, and lets the bounded cancel path remove that exact marked workspace;
- the component preflight coordinator reports all bootstrap downloads and deferred optional bytes on
  a clean store, reuses only rehashed healthy managed content, permits a compatible probed external
  browser while keeping observed Python non-reusable, and stops before external scanning for an
  unhealthy managed component or invalid signed bootstrap topology;
- the component preflight presentation maps managed/system reuse and bootstrap/on-demand downloads to
  distinct bilingual rows, reports stable binary byte totals, names every supported component kind,
  and compiles as a native SwiftUI card without exposing an unwired install action;
- offline component-release publication safely extracts every candidate and recomputes the same
  relative-path/file-byte/executable-bit identity used by the Desktop store before signing, then the
  public-key-only verifier repeats compressed and extracted identity checks;
- componentized-install preflight planning: exact content identity is required for mutable runtimes,
  a version-only external Python is rejected, an explicitly compatible healthy system browser may be
  reused, wrong architecture/digest/failed probes are rejected, optional capabilities are deferred,
  and only missing bootstrap components contribute to the initial download total;
- compatibility requirements retain an exact managed fallback identity, allowing a downloaded optional
  component to be reused after external compatibility disappears;
- shared-component inventory rehashes relative paths, bytes, and executable bits before reuse;
  stable trees produce stable identities, byte/mode changes invalidate them, and symbolic links,
  unsafe receipt permissions, receipt mismatches, and tampered trees fail closed;
- shared-component commit stages content plus its receipt under one private UUID, rehashes and probes
  the staged copy, atomically exposes both together, is idempotent when exact content already exists,
  records deterministic release references, and removes only the current workspace on probe failure;
- optional-component references can be recorded only for browser, speech, or document content that
  already exists under a valid base release; exact retries are idempotent and a conflicting identity
  for the same release/kind is rejected;
- first-use component installation requires a verifier-only v2 token and an exact signed trigger,
  validates the base reference and all bootstrap health before scanning or network access, installs
  optional dependencies in topological order, reuses managed content or a revalidated system browser,
  publishes capability references only for managed content, resumes only a manifest/trigger-bound
  transport interruption, and safely cleans extraction failures or explicitly discarded workspaces;
- optional runtime projection probes the exact managed Python ABI, sorts multiple signed Python
  component roots into one content-addressed read-only `.pth` target, is idempotent for identical
  input, injects the target and browser executable through the upstream Hermes environment contract,
  and rejects external Python roots, duplicates, symlinks, unsafe permissions, failed probes, or a
  mutated existing projection;
- first-use runtime activation shares the migration operation lock, requires the exact committed
  account release and healthy managed service topology, replaces only the Hermes LaunchAgent,
  restarts and proves the new Hermes runtime, restores the exact previous plist and re-proves the old
  runtime on failure, and invokes the capability retry exactly once without rolling back a healthy
  activation when that retry itself fails;
- active optional-component resolution preserves previously referenced managed capabilities across a
  later independent trigger, revalidates each identity, receipt, content tree, entrypoint, and health
  probe against the signed release, and retains a current external browser only when its exact
  owner-only LaunchAgent path passes a fresh signed-compatibility scan;
- the default-off capability coordinator maps only browser/speech/document kinds to exact triggers in
  the verifier-backed manifest, orders install → bootstrap-plan regeneration → locked activation →
  one retry, rejects unsupported kinds before work begins, stops after install failure, and admits
  only one concurrent request;
- the default-off preflight runtime accepts only a fixed unambiguous HTTPS manifest URL, orders bounded
  manifest fetch → strict schema-v2 signature verification → read-only environment scan, prevents
  download/signature failures from reaching the scanner, and rejects overlapping refreshes;
- the trusted preflight session retains the exact verifier-only schema-v2 token beside the matching
  scanned presentation result, while the compatibility load path exposes only the ordinary result;
- the component bootstrap state machine rejects mismatched trusted input before cache/network work,
  writes only the private preparation before exact release confirmation, rejects foreign preparation
  handles, and then passes the committed content-addressed activation plan with exact Python/Node roots
  to one migration boundary; cancellation and migration failure remove the UUID workspace, inactive
  immutable references remain reusable/collectable, and post-success cleanup failure returns a bounded
  retry handle without reclassifying the committed migration; a transport interruption before handle
  issuance is removable only by the UUID-and-private-marker checked cleanup path;
- migration-journal schema 2 binds each run to bundled-release or component-store layout across every
  state transition, rejects a same-run layout change and malformed/unknown schema-layout combinations,
  reads historical schema 1 strictly as bundled-release, and atomically upgrades that legacy record on
  its next valid transition;
- the concrete component migration adapter rejects manifest/activation-plan mismatch before binding or
  filesystem mutation, starts the content-addressed Hermes entrypoint before Connector, commits only
  after local and cloud health, persists `component_store`, injects the exact Python/Node roots, and
  neither creates a bundled version directory nor changes `current`; failure and interrupted recovery
  restore legacy service state while preserving an unrelated bundled `current`, and the existing bundled
  recovery test still proves that a v1 candidate deactivates its expected link;
- the composed schema-v2 bootstrap runtime binds the trusted preflight, component installer, migration
  journal/controller and content-addressed commit configuration to the same managed paths and account
  boundary; construction preserves an absent managed root, cache, LaunchAgents directory, and Hermes
  home, proving that merely enabling dependency composition cannot start a download or mutate the Mac;
- the component-preflight package gate is false with an empty v2 URL by default, loads only a complete
  HTTPS URL plus canonical pinned release trust inputs, keeps the schema-v1 URL separate, and rejects
  partial, malformed, credential-bearing, or ambiguous configuration;
- the read-only managed-entrypoint probe requires the signed entrypoint to stay inside the rehashed
  component root as an owned, non-writable, regular executable file, rejecting paths outside the root,
  symlinks, non-executable files, and group-writable files without launching a process;
- fixed-path external-environment scanning reports Python/Node without trusting version-only reuse,
  resolves Homebrew-style runtime symlinks for reporting only, admits a safe architecture-matched
  system browser only after an explicit compatibility probe, and rejects browser symlinks,
  group-writable executables, failed/oversized probes, and architecture mismatch;
- read-only component garbage-collection planning retains every bootstrap and optional capability
  identity, permits identical optional content to be shared across base releases, requires current and
  rollback reference sets supplied by the caller, validates component content again, and fails closed
  on orphaned/unsafe references, unknown kinds, missing components, symlinks, or tampered content;
- redirect-free bounded downloads, exact size and streaming SHA-256 artifact validation;
- tar member preflight, including traversal/link/special-file rejection before extraction, realistic
  dependency trees above the former 4,096-entry limit, and rejection beyond the new 65,536 bound;
- private credential/LaunchAgent writes, immutable release staging, atomic activation and rollback;
- a rolled-back, app-marked inactive release can be atomically replaced by a freshly verified
  same-version retry, while active, unmarked, mismatched, or unsafe directories remain immutable;
- a terminal rollback can resume an already committed Cloud binding only when its recorded ID,
  generation, and retained machine-key fingerprint all match; it performs no replacement or second
  remote confirmation, while every mismatch remains blocked;
- acquisition ordering and lifecycle: private roots exist before verifier pinning, both signed
  components are required, digest/extraction failures remove only the current UUID workspace, and
  explicit discard preserves the parent workspace;
- managed-bootstrap preparation performs acquisition only and returns the exact signed release plus
  confirmation; commit rejects wrong or foreign preparations, while cancel and terminal paths discard
  the private workspace;
- launch configuration is derived from verified manifest entrypoints, the managed layout, frozen
  runtime, and HTTPS account origin rather than caller-supplied executable paths;
- committed cleanup trouble retains a one-purpose retry handle, maps to `HR-MIGRATE-005`, and neither
  claims rollback nor offers a second install;
- exact legacy/account user LaunchAgent labels, duplicate-Connector prevention, health-gated binding
  confirmation, lost-response idempotency, automatic rollback, ambiguous-commit stop, and restart
  recovery;
- persistent legacy-label disablement before managed startup, matching re-enablement before rollback,
  and startup repair that suppresses a Migration Assistant-restored duplicate only when an exact
  `account_active` journal and both managed services prove the committed authority;
- bounded launchd convergence after every managed bootstrap/bootout, including delayed legacy
  removal before managed startup or rollback decisions;
- signed Hermes entrypoint-only plist generation, separate exact Hermes/Connector labels, Hermes-first
  startup, process-specific post-checkpoint ready evidence plus loopback health, bounded/symlink-safe
  log reads, Connector suppression on Hermes timeout, and reverse-order rollback;
- default-off packaged bootstrap configuration, strict HTTPS/key/channel/architecture validation,
  exact `hermes-serve-v1` loopback arguments, Desktop marker, private session-token file path and
  sentinels, absent/mismatched Gateway capability rejection, and readiness only when both gates match;
- private installation-local Hermes token creation/reuse, unsafe file rejection, no token value in
  either LaunchAgent, signed-wrapper file validation, and Connector file loading of both canonical
  43-character base64url and historical 64-character lowercase-hex values with no symlink or
  group/world-readable fallback;
- the managed Connector LaunchAgent sets `CONNECTOR_MODE=account` and `HERMES_MODE=live`; without
  the latter an older Connector falls back to mock, which turns the lifecycle observer off and
  leaves every task-completion event and push unsent (HG-101);
- committed pre-contract token migration preserving the existing 64-character local credential,
  removing both supported inline field names, writing only the canonical `0600` token file reference,
  and recording completion only after ordered service restart plus local health and an exact bound
  Cloud health timestamp strictly newer than the pre-Connector-start checkpoint;
- managed release 0.3.0 compatibility: startup returns before account refresh, file mutation, or
  service restart because its packaged Connector does not consume `HERMES_SESSION_TOKEN_FILE`; 0.3.1
  is the first immutable managed release admitted to the token-file migration;
- power-loss resume from a matching half-migrated plist pair, completed-state idempotency, rejection
  of mismatched inline values or account binding generation before mutation, and injected readiness
  or stale-Cloud-health failure that restores the exact old plist/token bytes and proves the restored
  running service pair with a fresh Cloud timestamp;
- an existing responder on reserved port 9119, including 401/403, blocks a clean install — unless it
  is this Mac's own usable checkout, which is adopted instead (see the adoption bullet below);
- packaged ATS configuration explicitly permits local-network health probes while leaving arbitrary
  public and WebView HTTP loads disabled;
- candidate Hermes readiness uses a dedicated ephemeral session that explicitly disables HTTP, HTTPS,
  SOCKS, FTP, PAC, and automatic proxy discovery, so configured proxies cannot intercept or stall the
  `127.0.0.1:9119` commit gate;
- the production migration coordinator passes a 75-poll window to candidate readiness, covering the
  measured 35-second physical-Mac cold start while tests can still inject shorter deterministic limits;
- an active managed release exposes upgrade only for a strictly newer signed target; preparation
  routes to upgrade for both schema-v1 and schema-v2, while same-version/downgrade inputs fail before
  service mutation;
- managed upgrade never invokes binding creation/confirmation, preserves the exact binding ID and
  generation, stops Connector before Hermes, waits for the old 9119 listener to disappear, then starts
  Hermes before Connector and requires a strictly newer Cloud health timestamp;
- candidate failure restores byte-identical LaunchAgents, the previous bundled `current` pointer,
  both old services and the old `account_active` journal; restart recovery uses the same durable
  snapshot and leaves manual attention only when that restoration cannot be proved;
- restart inspection recognizes only an `account_active` journal plus both exact managed LaunchAgents
  as active; intermediate journals recover before a second install and mismatches fail closed;
- overview Agent reduction prefers that exact active managed installation over a stopped legacy
  label, reports a transferred managed service as running-but-unverified until account sign-in, and
  fails closed when the signed-in binding ID or generation differs;
- a newly confirmed run atomically replaces only a terminal `legacy_active` or `clean_uninstalled`
  rollback journal; intermediate, active, and manual-attention journals reject a different run ID;
- a revoked pending binding is recreated only when its generation matches the same Desktop's terminal
  `legacy_active`/`clean_uninstalled` rollback journal; missing or mismatched proof fails closed with
  `HR-BIND-006`, remains visible through the migration presentation boundary, and does not call binding
  creation;
- existing-install observation/recovery remains available when new-install rollout is disabled, and
  active state must match the current account's exact binding ID/generation before it is claimed;
- a recognized running legacy Connector uses its own configured Hermes status URL for the final
  migration preflight and can enter the signed two-stage migration only while that health check and
  the complete managed-install capability agree; stopped/unhealthy or unsigned cases stay read-only;
- bilingual `HR-MIGRATE-001` through `HR-MIGRATE-005` terminal-state mapping.
- adoption of this Mac's own Hermes (2026-09-30): the planner returns `.readyToAdoptLocalHermes` only
  for the shape that has no connector to keep (`legacy.isInstalled == false`, a ready signed channel,
  and a `.usable` local checkout supplied — which the ViewModel does only with the local-runtime
  setting on), plans `.adoptLocalHermes` instead of `.installHermes`, and names the checkout in its
  detail; a running legacy Connector still plans the migration even with an adoptable
  checkout on disk; with the signed channel disabled the same inputs degrade to
  `.waitingForSignedRelease` with no adoption step. The detector names the LaunchAgent holding the
  loopback port from `ProgramArguments` (`com.hermes.dashboard` for the mini's holder) regardless of
  the label prefix, prefers `dashboard` when several match, and reports none for a server on another
  port. `DesktopIssue.migration` with that label puts the label, a pasteable
  `launchctl bootout gui/$(id -u)/<label>` and the plist move-aside into `technicalCause`, and
  degrades to an `lsof` hint with no `launchctl` command when no label is supplied.
  A coordinator refusal test asserts the stronger property for an occupied port on a fresh install:
  exactly one shutdown probe, no journal, no service mutation, no LaunchAgent, no health poll, and no
  managed `logs` directory created.
- local Hermes runtime (`DesktopLocalHermesTests`, `DesktopHermesRuntimeCoordinatorTests`):
  detection of the standard layout from git files alone (loose ref, `packed-refs`, detached `HEAD`)
  and of every surfaced shape — profiles, non-default `active_profile`, `HERMES_HOME` in the
  environment or an owner `ai.hermes.*` agent, an owner agent outside the checkout, a foreign
  entrypoint or pipx install, missing venv, group-writable entrypoint, version below 0.21.3,
  unreadable `HEAD`; upstream's update lock honoured only within its 20-minute ceiling; the planner's
  switch/keep/wait/restart/restore/surface table including the `hermes update` self-restart and the
  `git pack-refs` no-restart cases; the launcher **executed** against a stand-in Hermes (token handed
  over, `PYTHONPATH` cleared, unsafe token file, malformed token and foreign argv refused before
  exec); the exact local-mode `ProgramArguments`/environment and that `shlex.join` of them contains
  `hermes serve`; switch restarts only Hermes and keeps the exact bundled agent; a failed switch
  restores both files and proves the old server; restart on code change; no restart for a process
  started after the update; restore on removal and on turning the setting off; nothing mutated
  when disabled, unsupported or uninstalled; a managed upgrade keeps the local agent; the startup
  token/PATH reconcilers are inert in local mode; the retired schema-drift advisory is absent; the
  fresh-install gate; `HR-MIGRATE-008`/`009` bilingual contract and redaction; source wiring of the
  refresh call, both install gates, and the window card. Review fixes (each checked to fail when its
  fix is reverted): a failed return to bundled restores and proves the intact local Hermes, or keeps
  the bundled agent loaded when local is gone; a failed `bootstrap` during a switch or restart leaves
  the job loaded, and an unloaded local job is started again; an upgrade in local mode refreshes the
  kept bundled agent and a failed one restores the old one; a kept agent whose program is gone is no
  fallback (`HR-MIGRATE-010`); a loosened or older launcher is repaired while local mode stays
  recognised; the setting off does no detection on a bundled Mac (one `launchctl print` per refresh
  since 2026-09-21, to load an unloaded job); three restarts
  in 30 minutes pause and surface; launchd's loaded arguments that differ from the file reload the
  agent; adopting an update-started process; restarting only on a commit change, not a timestamp;
  waiting for reinstalled dependencies; a fresh install starting only the local Hermes; nothing
  shown mid-update; a separate non-retryable `HR-MIGRATE-010`; `/Users/<name>` redacted; an
  inline-token agent is never switched from; lease contention waits without an error. Re-review fixes (each checked to fail when reverted): two failed switches on one commit pause
  switching (`HR-MIGRATE-011`) until the commit changes or the setting is turned off; two failed
  setting-off rollbacks with one kept agent pause until it changes or the setting is turned on,
  leaving local Hermes running; a stopped local Hermes is restarted regardless of its venv; a
  persistent version mismatch and a missing or duplicated dist-info are shown; dist-info is read
  deterministically; setting off with an intact local Hermes and no kept agent is `HR-MIGRATE-012`;
  the once-per-launch running-agent check is wired.
- install-when-missing and the default-on setting (`DesktopHermesInstallerTests`,
  `DesktopHermesInstallOfferTests`, `DesktopSystemProxyTests`, `HermesInstallWiringTests`,
  `DesktopLocalHermesPresentationTests`). The driver runs **fake installer scripts with the real
  process runner** in a throwaway home — no test reaches the network, the real `~/.hermes`, launchd
  or `~/Library`: a successful run drives `--manifest` then every stage with `--stage <name>
  --non-interactive --json --dir … --hermes-home … --branch main` in manifest order, reports a
  skipped interactive stage as skipped, and returns what detection then sees; the child's `HOME` is
  the sandbox, its stdin is not a terminal, and `sudo` resolves to the refusing guard; the log is
  0600, carries the script's SHA-256 and the proxy summary, strips ANSI and redacts credentials and
  home names, and the private run directory is removed; a stage failing with "Could not resolve
  host" is retryable `HR-MIGRATE-015` and stops later stages; a compiler-style failure is
  retryable `016`; a stage without a result frame and a manifest with `protocol_version` 2 are
  non-retryable `017` before any stage runs; a captive-portal page instead of a script runs
  nothing; an unreachable download is `015`; an install detection does not accept is `018`;
  cancelling terminates the stage's process group through `SIGTERM` alone within four seconds (it
  failed through the five-second `SIGKILL` fallback before the child's signal mask was reset) and
  is not an error; root is refused before any download; a Hermes that appeared meanwhile is used
  without running anything, and an unusual one is not touched; an offer that was only shown runs
  nothing. Offers: only `.absent(hermesDataPresent: false)` on a fresh Mac with the setting on is
  offered; data without a checkout, profiles, custom home, pipx and too-old are not; an
  incomplete checkout is resumable only after Desktop's own attempt. The origin check accepts only
  HTTPS on the official host or exactly upstream's `main/scripts/install.sh` on GitHub; the manifest parser reads upstream's
  real 17b5df02 manifest line and refuses malformed ones; the stage result is the last frame; the
  run state folds progress; network signatures are recognised and a compiler error is not; output
  lines are cleaned like a terminal shows them; `HR-MIGRATE-015`–`018` are bilingual with the
  registered retryability and recovery and redact URL credentials and home names. Proxy: the
  System Settings dictionary is parsed; disabled, invalid and PAC-only settings export nothing
  (and PAC is named in the summary); each configured proxy is exported in both cases and only
  then, with IPv6 bracketed and SOCKS as `socks5h`; explicit variables win; loopback and the
  bypass list are always in `no_proxy`. Setting: on with nothing configured, off from the
  environment or after "use the bundled Hermes". Wiring (source assertions): exactly one
  `installer.install(` call site, inside `startHermesInstall()`; the card starts it only from the
  confirmation sheet and Retry; the refresh offers; both setup paths wait for the decision.
  Review fixes of 2026-09-22 (each checked to fail when its fix is reverted): a failure in
  `node-deps` after `python-deps` — detection already reads `.usable` — keeps Desktop's attempt
  pending (setup blocked, the half-installed Hermes still offered for resume) and Retry runs the
  stages again; an install that ends without upstream's `.hermes-bootstrap-complete` is `018`;
  `freshInstallProvider` refuses a Hermes whose Desktop-started install is unfinished and accepts
  it once the completion marker exists; a resume refuses a checkout replaced by the owner (inode and
  birth time differ) before downloading anything, and a first install refuses a checkout that
  appeared meanwhile; stale attempt records are dropped; the re-evaluation before running writes
  nothing, must match the shown offer and aborts without one (source assertion); `SSH_AUTH_SOCK`
  does not reach the installer; the prerequisites probe's "Could not reach https://duckduckgo.com/"
  and "Failed to download" do not turn a compiler failure into `015`; a manifest line over 4096
  bytes reaches the parser whole and log lines are split, not cut; a redirect hop off the origin is
  refused at the hop and the download stops past its limit; `*_API_KEY=`, `ghp_…`, `sk-…` and URL
  passwords containing `@` or `/` are redacted while `registry.npmjs.org/@scope` is not; quitting
  Desktop (`terminateAllProcessGroups`, wired to `applicationWillTerminate`) ends a running stage's
  process group. Re-review (both fail against d90fbcc): cancelling while the `repository` stage
  still runs after its clone landed keeps the checkout recorded, pending and resumable; after a
  quit, a record without a checkout claims one born after the install started and drops an older
  one. Owner decision on the `HR-MIGRATE-008` escape: `desktopOwnsCheckout` is true for Desktop's
  leftover checkout (after a failed stage, and when claimed after a quit) and false for a replaced
  checkout, no attempt at all, and a checkout older than the attempt; a finished-but-unusable
  checkout stays Desktop's until it is seen usable. Source assertions: both refusals classify the
  block; the built-in card is shown only for Desktop's checkout and the owner's-Hermes guidance card
  offers no built-in alternative.
- 2026-09-21 restart-probe incident (`DESKTOP_E4_TEST_RECORD.md`), each checked to fail when
  reverted where the boundary allows: the loopback shutdown decision is a pure function —
  `.waiting`/`.failed` with `ECONNREFUSED` is "stopped", `.ready` is "listening", every other error,
  `.setup`, `.preparing` and `.cancelled` stay undecided; a real loopback test on an ephemeral port
  proves a free port stopped in one probe (the old mapping took the full 15 s of five attempts and
  failed) and a bound, listening socket not stopped; a switch whose stop and restore waits both time
  out ends with the job loaded, the bundled agent restored, exactly one reload bootstrap, and a
  `DesktopServiceRecoveryFailure` naming `switch-to-local`, both `hermesStopTimedOut` causes, and an
  `HR-MIGRATE-009` diagnostic carrying them; a reload refused twice by launchd succeeds on the third
  attempt and the log keeps launchd's stderr; a reload refused every time is `hermesReloadFailed` →
  retryable `HR-MIGRATE-013`, also after a failed local restart; an upgrade whose rollback also fails
  carries both causes into `HR-MIGRATE-004`; a bundled job that is not loaded plans `.loadAgent` with
  the setting on or off and even mid-update, and is loaded with a readiness proof, while a stopped or
  unreadable bundled job is left to launchd; the setting-off path loads on every refresh and reloads
  only once per launch; the operation log is 0600, single-line per event, redacted, rotated once,
  never created without the managed logs directory, never follows a symlink, logs mutations with
  status and stderr and summarises `print` polls; `SystemCommandRunner` keeps a bounded stderr only
  when asked; the migration journal reads a whole-second or offset RFC 3339 `updatedAt`, rewrites it
  canonically on the next transition, and still rejects anything that is not RFC 3339.
  Review follow-up (both fail against the first version): loading an unloaded job while another
  process accepts on 9119 makes exactly one single-attempt probe, bootstraps nothing, never reloads,
  and surfaces retryable `HR-MIGRATE-014`, with the setting on or off; a port taken between that probe
  and the start gives the same answer with no bootstrap. `HR-MIGRATE-013` copy claims no restart.

- the Desktop entry route and first-run onboarding (`DESKTOP_ONBOARDING_REQUIREMENTS.md` §4.1): every
  row of the state table, `.checking` never rendering as the sign-in page, an unconfirmed account
  showing retry instead of spinning, an expired-session reason reaching the page while an explicit
  sign-out carries none, `.existingServiceNeedsAttention` and `.existingServicePreserved` both staying
  out of onboarding, a running setup keeping onboarding on screen, and the §6.3 resume rules;
- the new-Mac choice and its quota (§7): an account that already owns another Mac is asked first, a
  shared Mac or this Mac's own previous binding does not trigger it, manage-only is remembered, and
  "把这台 Mac 也连上" becomes unavailable only when the owned-Mac limit is reached;
- the phone step (§6.4, revised 2026-10-23): existing active non-current `phone` and `browser`
  installations count even with old last-seen times; revoked/current/Desktop entries do not. A
  restored local active binding clears a stale pending flag, upgrade intermediate checks cannot
  re-arm it, and another Mac's owned/shared binding cannot skip first setup. Fresh setup without
  clients still reaches the phone step. Completion is persisted and a Web App is never labelled an iPhone;
- removing an owned Mac through `DELETE /v2/devices/{id}`: request path, `connector.unbind` grant and
  idempotency key, grant reuse after a lost response, fail-closed behaviour without multi-device, and
  refusal of a shared or unknown device;
- the onboarding record: stored per account, and an empty record leaves nothing behind.

Remaining email-first release acceptance requires live-provider tests for resend/cooldown, expiry,
account-existence-neutral delivery behavior, packaged-UI inspection proving that an `email_otp`-only
Gateway exposes no Google action, and a packaged two-phone run proving the verification sheet and
selective revocation. The deterministic E7 transport/controller/error and retry cases are now
automated.

Every new phase-0 behavior requires a regression test when its boundary is deterministic. User-visible
error codes additionally require localization, retryability, recovery-action, and redaction tests under
the project-wide `ERROR_HANDLING.md` contract.

## Manual phase-0 matrix

| Case | Expected result | Status |
|---|---|---|
| Existing Connector running | Desktop observes it and does not launch a replacement | Verified on target Mac 2026-09-02; PID and launch count unchanged |
| Existing Connector migration gate | Healthy configured Hermes plus matching signed-release capability exposes preparation; stopped/unhealthy/unsigned cases preserve legacy | Automated; packaged target-Mac migration still pending |
| Migration Assistant preserves managed services | Overview reports the effective managed Agent rather than the stopped legacy label; missing this-device-only account credentials require sign-in; a restored legacy label is persistently suppressed only for a proven `account_active` installation | Desktop 0.2.4 packaged reboot passed on migrated Mac 2026-09-11: only managed labels recovered, legacy stayed disabled/unloaded, and post-reboot Android REST/WebSocket carried bidirectional traffic |
| Pre-contract managed token storage | A committed matching installation moves the existing token from both `0600` plists to the `0600` private file, restarts Hermes then Connector, and commits only after local readiness plus a same-binding Cloud health timestamp newer than Connector startup; injected failure restores the old configuration and requires fresh rollback health | Automated, including stale healthy snapshot regression; Desktop 0.2.9 with managed 0.3.4 completed the packaged migration and ordered service restart on 2026-09-12; physical Android account traffic and full Mac reboot remain pending |
| Existing Connector absent | UI reports not detected and offers no destructive action | Verified 2026-09-02 |
| Gateway available | Gateway layer is healthy with safe latency | Verified on target Mac 2026-09-02; 15–18 ms observed |
| Gateway offline/DNS failure | Only Gateway layer fails; Hermes wording remains accurate | Pending fault injection |
| Hermes returns 2xx | Local Hermes layer is healthy | Verified on target Mac 2026-09-02; 117–128 ms observed |
| Hermes returns 401/403 | Layer says reachable/authentication required, not “down” | Pending real-app check |
| Logs contain credentials | Display/export contains `<redacted>` only | Automated; target redacted UI preview inspected 2026-09-02, credential injection still pending |
| Window closes | Existing Connector remains running | Verified on target Mac 2026-09-02; menu-bar app remained and window reopened |
| Two phones use current Gateway | Existing phone configuration remains usable | Pending device check |
| Light/dark mode | Same cool-blue surface language and semantic states | Light verified 2026-09-02; dark pending |
| App icon and Dock presence | Desktop packaging copy equals the canonical Android icon; the packaged app declares `AppIcon`, appears in the Dock while running, and can be kept there with Dock’s native option | Icon copy verified by packaging gate 2026-09-02; Dock presence pending HG-55 physical check |
| Componentized v2 install | Default package remains absent/inert; valid local v2 configuration plus Gateway schema 2 and `hermes-serve-v1` retain one verifier-issued token beside the matching card. A safe machine adds “下载缺失组件”; preparation changes only private cache, an exact signed-version sheet gates commit, and v1/v2/account/refresh actions remain mutually exclusive. Capability loss or unsafe machine state before either stage blocks mutation; trust/scan failure cannot expose the v1 downloader; cleanup failure offers cleanup retry only | Core capability, manifest, token, prepare/commit/cancel/cleanup, component store, migration/rollback, structured error, and configuration boundaries automated; full SwiftUI compilation required. Enabled packaged UI, capability-withdrawal race, real signed archives, and clean/existing-Mac install remain pending |
| Managed Hermes search path (HG-58) | The written LaunchAgent carries `PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin`; an agent written before this change validates without it and is given one by the next optional-component activation; a malformed `PATH` on disk is refused | Automated (written plist, legacy-agent repair, malformed-`PATH` refusal, search-path shape). **Physically unverified**: that a PDF attachment from the phone now succeeds needs the managed service restarted with a rewritten agent, which no machine has done. Read-only check afterwards: the managed `hermes-server` process `PATH` contains `/opt/homebrew/bin` |
| Managed search-path startup repair (HG-58) | On a committed `account_active` installation whose agent predates the `PATH`, launching Desktop adds the key, restarts **only** Hermes, and proves it healthy; a second launch changes nothing; an existing well-formed `PATH` is left alone; a malformed one and an agent we did not write are refused before any service changes; a failed restart restores the exact file and the running server | Automated (6 cases in `DesktopMigrationCoordinatorTests`). **Physically unverified**: that the repair fires on a real migrated Mac, that the Connector survives the Hermes-only restart window, and that a PDF then sends |
| Managed startup reconciliation classification (HG-68) | A transferred-account Connector reconciliation failure remains a hard `HR-MIGRATE-002` block; token-storage or search-path repair failures surface as retryable `HR-MIGRATE-007` without changing an available managed-upgrade operation to failed | Automated issue/stage contract plus full Desktop compilation; packaged UI remains pending |
| Local Hermes runtime switch | With `HermesGoLocalHermesRuntimeEnabled` on, a committed Mac with a usable standard Hermes rewrites only `com.hermesgo.hermes-server` to the local launcher, restarts only Hermes, passes the readiness proof, and the phone keeps REST and WebSocket traffic; the Connector's PID is unchanged; with the owner's gateway running, `cron/executions.db` gains no rows from the serve's PID | Automated with in-memory launchctl; read-only dry run on LGS-MACMINI 2026-09-21 planned `switchToLocal` (0.21.3, `17b5df02`). **Physically verified once**: the Mac mini was switched by hand with Desktop 0.2.22 on 2026-09-21 (about 17 s of Hermes unavailability, Connector PID unchanged; `DESKTOP_E4_TEST_RECORD.md`, "2026-09-21 Mac mini switched to its own Hermes"). The automatic switch of a Mac by the default-on setting is not yet verified (next row) |
| Local Hermes restart on update | After `hermes update` on a local-mode Mac, the serve's start time is later than the checkout's ref time within one refresh, either because upstream kickstarted our job or because Desktop restarted it; no duplicate or detached serve holds 9119 | Planner and coordinator automated. **Physically unverified**, including upstream's kickstart of our job |
| Local Hermes rollback | Turning the setting off restores the exact bundled agent from `Managed/state/hermes-server.bundled.plist` and restarts only Hermes | Automated. **Physically unverified** |
| Local Hermes unsupported | A Mac with profiles, a custom `HERMES_HOME`, or a second install shows `HR-MIGRATE-008` and changes nothing; a fresh install on it is refused | Automated. Packaged UI pending |
| Local runtime on by default | Upgrading Desktop on a managed Mac with a usable standard Hermes and no `HermesGoLocalHermesRuntimeEnabled` key switches it on the first refresh exactly as the "Local Hermes runtime switch" row; a managed Mac without Hermes changes nothing and shows no install offer; `defaults write … -bool false` rolls back | Planner/coordinator automated; default value automated. **Physically unverified** on a Mac that has not been switched by hand |
| Install Hermes on a clean Mac | **Manual, on a clean macOS user account or VM** (no `~/.hermes`, no `~/.local/bin/hermes`, no Homebrew `hermes`, no pipx install; ideally one run with and one without a manual HTTP/HTTPS proxy in System Settings). Sign in to Desktop; the "这台 Mac 还没有 Hermes" card appears above the setup card and the setup download action is replaced by the decide-first line. "安装 Hermes…" opens the sheet; cancelling it runs nothing (no `~/.hermes`, no `hermes-install.log`). "开始安装" shows the manifest's stages in order; `setup` and `gateway` show as skipped; `ps -o pgid,command` shows the installer in its own process group, never as root, with no `sudo` process. `Managed/logs/hermes-install.log` is 0600, names the proxy, the script's SHA-256, every stage and no `/Users/<name>`. Afterwards `~/.hermes/hermes-agent/venv/bin/hermes --version` works, the card reads "Hermes 已安装", and continuing the normal setup writes `com.hermesgo.hermes-server` in local mode directly (`ProgramArguments` name the local launcher; `Managed/state/hermes-server.bundled.plist` exists; the bundled `hermes-server` never runs). Repeat with the network or proxy broken (for example a bogus manual proxy): `HR-MIGRATE-015` with Retry and "改用内置 Hermes"; fix the proxy and Retry resumes. Cancel during `python-deps`: no installer, `git`, `uv` or `curl` process remains within ~5 s; the card says cancelled, not failed; Retry resumes. "改用内置 Hermes" writes the setting off and the setup installs the bundled copy exactly as before | Driver, proxy composition, offer rules, cancellation and error codes automated with fake installer scripts. **Not run** against upstream's real installer or on a clean Mac |
| Adopt this Mac's own Hermes (2026-09-30) | **Manual, on a Mac that already runs its own Hermes and no Connector.** With 9119 answering from this Mac's own `~/.hermes/hermes-agent` but no `com.hermesremote.connector` installed, setup offers 接管 instead of a refusal: the plan's detail names the existing checkout, the confirm sheet says only what that path changes, and the button reads 「接管并连接」. Continuing writes `com.hermesgo.hermes-server` in local mode pointing at the existing checkout, installs the Connector and both user-level LaunchAgents, binds the account, and restarts those two services briefly. Afterwards `~/.hermes/.env`, `~/.hermes/state.db` and the checkout's mtime/content are unchanged, no second copy exists under `Managed/`, and the phone reaches Hermes. Then the refusal side: `launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.hermes.dashboard.plist` (the situation found on the new mini) makes setup fail with `HR-MIGRATE-014` before anything is written — 详情 names `com.hermes.dashboard` and gives the `launchctl bootout gui/$(id -u)/com.hermes.dashboard` line; nothing under `Managed/` is created; booting the job out and retrying proceeds to adoption | Planner, detector, issue mapping and the refusal's no-write property automated. **Not run**: needs a Mac in that shape, and the mini's own holder (`com.hermes.dashboard`) was only read, never booted out |
| Managed patch 020 — bounded inline images (HG-74) | A bounded read carries `[image]` in place of an inline `data:` image; the image still appears on the phone, from the Mac path in the text part; an `https://` image URL is untouched; the placeholder never reaches a bubble | Both client-side behaviours the patch depends on are pinned by Android tests (`TimelineNoteTest`, `MultimodalContentMappingTest`) — neither is new, both were unpinned, and the patch lives in another program. Measured against the real database: one message 27,479,595→6,374 characters, the whole HG-65 session 105.07 MiB→0.265 MiB. **Not yet live**: needs a managed release and a controlled activation, so no phone has read a bounded response |
| Managed Hermes restart proof (2026-09-21) | On the packaged app, a runtime switch or managed upgrade that boots out `com.hermesgo.hermes-server` bootstraps the replacement within a few seconds of the old process exiting (`launchd.log` shows the bootstrap; `Managed/logs/desktop-runtime.log` shows `wait-stopped result=stopped attempts=1` or close to it), not after ~112 s | Automated (probe, loopback, coordinator). **Physically unverified** |
| Managed Hermes left unloaded | With Desktop running on a committed installation, `launchctl bootout gui/$UID/com.hermesgo.hermes-server` by hand is undone within one refresh (setting on or off): the job is bootstrapped again and passes readiness; if launchd refuses three bootstraps the window shows retryable `HR-MIGRATE-013` and tries again after five minutes. If another process listens on `127.0.0.1:9119` at that moment, nothing is bootstrapped, the lease is released after one probe, and the window shows retryable `HR-MIGRATE-014` | Planner and coordinator automated. **Physically unverified** |
| Service operation log | `Managed/logs/desktop-runtime.log` exists at 0600 after the first service operation, carries launchctl mutations with status and stderr and each wait's outcome, contains no token and no home-directory name, and stays under ~512 KiB across rotations | Automated. Packaged check pending |
| Hand-edited journal | A journal whose `updatedAt` is `2026-09-20T11:48:09Z` loads (no `HR-MIGRATE-002`), and the next Desktop transition rewrites it as `…09.000Z`; a non-RFC 3339 value still fails closed | Automated |
| Keychain profile | App Token persists across restart and is never shown in visible UI | Verified locally and on target with disposable test Token 2026-09-02 |
| Invalid App Token | End-to-end check reports `HR-AUTH-001` with recovery guidance | Automated + local/target UI verified 2026-09-02 |
| Local Hermes unavailable behind an online Connector | End-to-end 502 from structured or legacy Connector reports retryable `HR-CONN-006`; raw body and request ID stay out of primary copy | Automated in `EndToEndProberTests`; live test stack pending |
| v1 QR payload | JSON contains only compatible `v`, `url`, and `token` fields | Automated 2026-09-02 |
| QR reveal | Real QR is hidden by default and carries an explicit long-lived-token warning | Local + target UI verified 2026-09-02; Android scan pending |
| End-to-end success | Saved App Token reaches Gateway → Connector → Hermes through `/api/status` | Pending target production-token check |
| Account-mode presentation | A signed-in account omits the legacy App-Token probe, and Overview uses the Desktop-selected Mac's authenticated Connector/Gateway/Hermes/end-to-end snapshot instead of a saved legacy profile; switching devices cannot rename or restart local services | Automated |
| Account mode disabled | Account & Devices reports unavailable and legacy connection remains usable | Automated core behavior; packaged UI inspection pending |
| Email account login | Email challenge and six-digit exchange create/restore only the Desktop management session | Controller/API automated; live delivery and packaged UI pending |
| Browser OAuth loopback | Listener binds an ephemeral `127.0.0.1` port and rejects mismatched state | Automated locally; live Google client pending |
| Account session restart | Keychain session refreshes without changing the Connector machine identity | In-memory/store contract automated; packaged Keychain run pending |
| Two account phones | Account & Devices lists both; owner-email verification removes only the selected phone | Controller/API/PostgreSQL automated; packaged UI and physical phones pending |
| Whole-device sharing off | No share controls appear and existing device selection remains unchanged | Automated core behavior; packaged UI pending |
| Owner invites account | Explicit sessions/files/config warning and fresh owner-email verification precede mail request | Controller/API automated; live mail/two-account run pending |
| Recipient accepts invite | Pasted link requires exact token + acknowledgement and creates operator access only | Parser/API automated; two-account physical run pending |
| Owner revokes / recipient leaves | Matching access and live stream end; owner, other grantees, Connector, and Hermes continue | Gateway automated; packaged Desktop and multi-node run pending |
| Account deletion off | No Desktop danger-zone action appears and the route is not called | Core/API automated; packaged UI pending |
| Permanent account deletion | Typed `DELETE`, acknowledgement, and fresh email code precede immediate Cloud logout; success and recovered ambiguous completion land on “deletion submitted”; the explicit other-email exit reaches an empty sign-in flow, while local Hermes remains intact | Core/API/PostgreSQL automated; disposable packaged-account and privacy review pending |
| Android account-mode cutover | Android signs in by email, selects the intended owned/shared Mac, proves REST and WebSocket traffic, then Desktop migrates; protected Connector status matches the exact binding/generation before and after Desktop/Mac restart | Primary single-phone 0.1.113 path accepted 2026-09-10; migrated-Mac reboot plus post-reboot account REST/WebSocket accepted 2026-09-11; activation-interruption and multi-phone/shared-Mac matrices remain pending |
| Sign-in gate with a live phone (HG-129) | **Manual.** On a Mac that is set up and serving a phone, invalidate only the Desktop session (sign out from another client, or revoke the session). The window shows the full-window sign-in page with the `HR-AUTH-*` banner; `launchctl print gui/$UID/com.hermesgo.connector` and `com.hermesgo.hermes-server` keep their PIDs and states, and the phone keeps answering for the whole gated period. Sign back in: the window returns to the section it was on. Sign out from Settings instead: no reason banner appears | The gate is a `RootView` presentation switch and the monitoring loop is untouched by it, but `HermesGoDesktopCoreTests` cannot assert either — the loop and the window live in the executable target. **Not run** |
| Upgrade/relaunch with existing binding (2026-10-23) | **Manual.** On the reported Mac, retain a stale pending-phone record, disconnect the phone, upgrade components, then quit/reopen Desktop: main opens without the phone page and the pending flag stays cleared. Repeat with a signed-in phone/Web client recorded before setup, then revoke it: the guide must not return. On a clean user account with no binding/client, first setup must still offer the optional phone step. Another owned/shared Mac must still show the new-Mac choice/local setup as appropriate | Core routing/record regressions automated; packaged M4 UI acceptance **not run** |
| First run on a clean Mac (HG-129) | **Manual, on a clean macOS user account or VM with no managed install** (the same host as the "Install Hermes on a clean Mac" row). Launch: the checking page, then a full-window sign-in page carrying the four-step hint. After the email code: no install and no other Mac → onboarding step 2; another owned Mac exists → the "把这台 Mac 也连上 / 只在这台 Mac 上管理" choice first. Step 3 shows the plan's rows and stops at the confirmation sheet; committing ends on the step 4 QR page when the account has no active remote client; an existing active phone/Web client goes to main (2026-10-23). Android: scanning `https://mrlgs.net/` downloads the current versioned APK; after signing in with the same email the step ticks, and the overview's "还没有手机连接" card disappears. iPhone/iPad: scanning `https://mrlgs.net/app/` opens the Web App in Safari; added to the home screen and signed in, step 4 ticks as 网页版 Hermes GO, never as an iPhone. Quit during step 3 and relaunch: onboarding resumes at the step the Mac is really on, not from the beginning. At a 3-Mac limit the connect option reads "先移除一台 Mac" and removing one through the email code frees the slot | Router, quota, phone-completion rule, and the removal API are automated. The two QR codes, real phone sign-ins, and the interrupted-install resume are **not run** |

The first real-app check verified that the ad-hoc app launches and remains running. The target Mac run
then verified the installed DMG against a live legacy Connector without changing its PID, launch count,
configuration, log size, or public Relay attachment. The diagnostics UI correctly kept the App-Token
end-to-end check unavailable. Physical two-phone traffic, injected fault cases, and takeover/rollback
remain pending; public Relay health alone is not evidence for a phone UI test.

The `0.2.0-dev` target upgrade additionally verified Keychain persistence across restart, explicit QR
reveal, and the invalid-token UI mapping without restarting the legacy Connector. The disposable Token
and temporary rollback package were removed after the run. A real production Token was deliberately
not retrieved as part of this test, so the successful end-to-end and Android scan rows remain pending.

## Android-account coordinated acceptance

Run this matrix only after the Android account branch has passed its package gate. Do not migrate the
Mac merely because email login or `/v2/devices` succeeds.

1. Keep the legacy Connector active. Install the versioned Android test artifact, sign in with email,
   and confirm the intended owned or shared Mac is listed without importing a legacy App Token.
2. Record the protected `/internal/account-connectors` snapshot and the authenticated Android device
   row. The former may show zero account connections before migration; `/relay-health` must still show
   the expected legacy Connector.
3. Complete the Desktop's two-confirmation migration. Require an `account_active` journal, both exact
   managed LaunchAgents, healthy local Hermes, and an internal snapshot row whose binding UUID and
   generation match Desktop state. Before any later token-storage repair, record the active managed
   release and require the signed runtime capability boundary; 0.3.0 must remain unchanged.
4. From Android, exercise one REST status request, one real WebSocket session, **and one slash
   command** through the selected device **before calling the Desktop candidate accepted or
   proceeding to a reboot**. An established Connector control socket and a loopback HTTP 200 do not
   satisfy this gate. For a shared Mac, repeat using the grantee account and confirm revocation
   closes only that grantee's live stream.

   The slash command is not padding. A prompt exercises the gateway in-process; a slash command is
   the only thing that makes Hermes spawn a **child** process, and a child gets a different
   environment from its parent. Managed release 0.3.0 served prompts perfectly and could not run a
   single slash command, because the child could not import `tui_gateway` — and nothing in this
   matrix would have caught it (HG-28). Switching the model in the Android model picker is the
   cheapest way to exercise it: it sends `/model … --session`. A failure appears as `slash.exec`
   error 5030 in the device diagnostics and `HR-RPC-007` on screen.
5. Restart Android and Desktop independently, then reboot the Mac. Require the same account binding,
   automatic managed-service recovery, a newer process-local `connectedAt`, and successful Android
   REST/WebSocket traffic without re-entering a legacy Token.
6. On any failed gate, capture redacted diagnostics and use the journaled Desktop rollback. Confirm
   the legacy Connector returns, `/relay-health` reports it online, and the old Android path remains
   usable. Do not call a mixed legacy/account state successful.

## Later takeover gate

Managed Agent takeover must not ship until automated and real-machine tests prove:

1. the new Agent does not start while the old Connector is active;
2. configuration validates before the old service stops;
3. the new Agent receives `hello_ack` and passes local/end-to-end checks;
4. failure stops the new Agent and restores the old service;
5. the phone retains its URL, token, sessions, and history after takeover and rollback.

Items 1–4 now have injected local core tests. They are not considered target-Mac verified: the
packaged UI path is compiled but hidden in the default-off build, and no real signed/notarized
artifact has exercised it. Item 5 and the physical clean-install/upgrade/interruption matrix remain
manual gates.

## Update checking

Automated (JVM-free Swift unit tests, `npm run desktop:test`):

1. `DesktopUpdateIndexTests` proves the app index resolves every field, rejects unknown fields,
   off-origin download URLs, a filename/version mismatch, bad notes, and a wrong channel, and that
   semantic comparison is correct in both directions.
2. `DesktopUpdateCheckerTests` proves a newer app and managed release are both reported with notes,
   equal versions report nothing, a managed update is not offered when nothing is installed, a
   malformed index fails closed, and no configured source reports “not configured”.
   `DesktopManagedBootstrapConfigurationTests` also pins that a validated discovered version can
   target an existing-install upgrade card only for an index URL; an exact manifest remains pinned.
3. `scripts/test/desktop-app-update-index.test.mjs` proves the generator writes an index matching the
   DMG bytes, refuses a misnamed DMG, rejects oversized/multi-line notes, and never overwrites an
   existing index.
4. Internal publisher tests verify both complete public DMG URLs before either index changes,
   reject cross-channel/downgrade/same-version conflicts, and restore both pointers after either
   failed readback while refusing concurrent rollback. Real isolated-filesystem tests exercise the
   exact remote Python transactions and Nginx route rollback. A shared publisher fixture is parsed
   by `DesktopUpdateCheckerTests`: 0.2.31 discovers app 0.2.33 while unchanged components stay current;
   installed 0.2.33 reports no app update. No client parser or installed app must be replaced first.

Manual, on a real Mac (not reproducible in CI):

1. Build an update-capable app with `HERMES_GO_APP_UPDATE_ENABLED=1` and an app index pointing at a
   prepared release; confirm Settings shows the current and component versions and the automatic-check
   switch, and that the menu bar offers “检查更新”.
2. With the published index one patch ahead, confirm a manual check opens the unified sheet with both
   update cards when both exist, that 立即更新 for the app downloads, verifies, quits, replaces
   `/Applications/Hermes Go Desktop.app`, and relaunches at the new version, and that 组件更新 opens the
   existing signed confirmation sheet.
3. Confirm an app-index download whose SHA-256 is deliberately wrong stops with `HR-DESKUPDATE-004` and
   leaves the installed app untouched.
4. Switch automatic checks off and confirm no index request is made across a launch; switch them on and
   confirm one check runs.
5. Confirm a build with no app index reports “此版本未配置更新检查” and shows no failure banner.
6. On an existing managed installation, check an index with a newer signed release: Account &
   Devices shows the discovered target and “下载并验证更新” after the check. An invalid index leaves
   the installed service and upgrade action untouched. This real-machine path remains unverified.

### HG-116 Dock icon

The asset equality gate checks the canonical Android image and Desktop packaging copy. On a
physical Mac, inspect the built app in the Dock in light and dark appearance; the faceted H should
retain its colors without an opaque white square. The local ad-hoc build is not a signed release.

### Update source/layout regression (2026-10-24)

Test impact: the defect is at discovery/executor routing, not semantic-version ordering. Automated
coverage selects the source for bundled 0.3.4 with both configurations available, discovers app
0.2.37 and backend 0.4.12, and asserts the bundled executor identity. The mirrored component-store
case accepts only the component index; requesting the other namespace fails. Missing matching
configuration and absent/inconsistent/attention-required installs cannot cross-fallback. Prepared
component hints retain their layout and cannot overwrite an existing offer. Both executors reject
the opposite layout without misreporting a non-newer version or mutating live services. Existing
same-version refusal and unified update failure/cancel/cleanup tests remain required.

Physical acceptance: on a bundled-layout Mac with server component capability enabled, check and
prepare the update; confirm the signed bundled-release confirmation appears, then complete the
explicitly confirmed upgrade and app relaunch. Repeat on a component-store Mac, including a newer
index published after startup preflight. Verify original Hermes home/profiles/data and account
binding, no duplicate background services, and mobile access afterwards. These service-changing
physical steps remain pending; unit/build gates alone do not establish successful on-device upgrade.

Read-only physical evidence on 2026-10-24: the installed 0.2.36/build 39 public configuration and
schema-1 active 0.3.4 journal resolve to the bundled index/executor with this fix. Public discovery
finds app 0.2.37 and backend 0.4.12; the backend envelope passes size/hash and Ed25519 verification
using the installed app's trusted public key. No credentials were printed and no app, service,
installation journal or Hermes data was changed. Commit/relaunch acceptance remains pending.

### Component layout migration release gate (2026-10-25)

See `DESKTOP_COMPONENT_MIGRATION.md` for the transaction and physical acceptance matrix. Automated coverage includes opted-in component discovery from an active bundled/local installation; disabled/missing configuration and invalid states stay on their existing paths. Real signed component fixture archives exercise exact preparation confirmation, one-way conversion, retained local Hermes agent/launcher/token and binding, health failure rollback, restart recovery, missing executable refusal before shutdown and refusal to restore bundled Hermes after commit. Publisher fault injection covers complete archive/index bytes, HTTP readback failure, rollback readback failure, current-main movement, downgrade, signed-version mismatch and independent channel signatures. GUI integration calls the explicit preparation only from the unified update flow and labels its final confirmation “迁移并重连”. Physical service-changing migration, fresh install and Android business acceptance remain pending and must be reported separately from package/publication verification.

## HG-244 connection health

Run `desktop:assets:test`, `desktop:test`, and `desktop:app`. Verify optional health DTO decoding, congestion/control-only loss as degraded, stale local report as unknown, and explicit local failure with HR-CONN-006. Test 0/1 opt-in preservation and rejection of arbitrary environment during repair/upgrade. Before release, exercise actual remote traffic, control-only reconnection, business-only failure, permission revocation and rollback; local Swift tests do not replace those checks.
