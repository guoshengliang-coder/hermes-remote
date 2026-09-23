# Desktop 首次启动：登录门禁与装机新人引导

> 状态：2026-09-23 产品已确认方向（§10），待实现。
> 范围：macOS 菜单栏应用 `desktop/`（Hermes GO Desktop）的首次启动、未登录、登录后未装机三种
> 状态的界面去向。中文为默认语言。
> 本文**不授权**改变 Gateway 的任何 REST 契约，也不新增 `HR-*` 错误码；需要新增时先按
> `docs/ERROR_HANDLING.md` 登记。视觉约束以 `docs/DESKTOP_DESIGN.md` 为准，行为约束以
> `docs/DESKTOP_PHASE0.md` 为准；本文与两者冲突时，以它们和代码为准。
> Android 侧的对应需求是 `docs/LOGIN_REQUIREMENTS.md`，本文沿用它「从未登录 / 登录失效」的分界。
> 设计稿：`docs/design/desktop-onboarding/`（17 个画面，索引见该目录 `README.md`）。

## 1. 问题

全新安装的 Desktop 打开后，没有任何账号门禁：

- `RootView` 无条件显示「概览 / 诊断 / 日志 / 账号与设备 / 设置」五项侧边栏，默认停在概览。
  账号状态只是概览右上角和菜单栏里的一行文字。
- 概览页的主按钮是「运行诊断」，「账号与设备」是次级按钮。首屏把用户推向诊断，而唯一正确的
  动作是登录。
- 未登录时 `presentedHealth` 原样返回本机探测结果（`ConnectionModels.swift` 的
  `presented(accountModeActive:)`），诊断页于是报「需要处理 / Gateway 未配置 / Hermes 不可访问」，
  却不提真正原因是没登录。用户会以为自己的环境坏了。
- 登录卡片（`signedOutCard`）藏在第四项「账号与设备」里。
- 登录之后仍停在「账号与设备」，这一页同时堆出账号、绑定、Hermes 安装、组件预检、本机安装预检、
  设备、共享、手机等八类卡片。一台刚登录的新 Mac 真正要做的只有一件事：装好本机服务。
- 所有能改变机器状态的操作（安装托管服务、安装 Hermes、绑定账号）只存在于
  `signedInContent(dashboard)` 分支里。**不登录，Desktop 什么都做不了。**

## 2. 目标

一句话：**先登录；登录后这台 Mac 没装过就走新人引导，装过就直接进主界面。**

明确不做：

- 旧版 Connector、旧版 Relay 地址 / App Token、二维码配对这条路径**不再保留入口**。目前没有旧版
  用户，门禁和引导都不为它设计出口。移除这些旧代码是另一项任务（§9），不与本需求混在同一个 PR。
- 不改动账号登录方式（仍是邮箱六位验证码）。
- 不改动安装执行器、回滚、迁移日志等服务层状态机。引导只是给现有的
  `bootstrapPlan` / 准备→确认→提交流程换一种呈现方式。

## 3. 三道判据

界面去向由三个**相互独立**的事实决定，实现时不能把它们合并成一个布尔值：

| 判据 | 来源 | 含义 |
|---|---|---|
| 账号状态 | `DesktopViewModel.accountState`（`DesktopAccountState`） | 这台 Desktop 有没有可用的 Hermes GO 会话 |
| 本机装机状态 | `DesktopViewModel.bootstrapPlan.readiness`（`DesktopBootstrapReadiness`，来自本机 launchd 与安装记录） | **这台 Mac** 有没有装好托管的 Hermes + Connector |
| 账号下的 Mac | `AccountDashboard.ownedDevices` / `maxOwnedDevices`（服务端下发） | 这个账号在**别的** Mac 上装过没有、还有没有名额 |

「有没有设备」和「这台装没装」是两件事：一个账号可以已经在 Mac mini 上装好，然后在笔记本上
登录——此时账号下有设备，但这台 Mac 什么都没装。

## 4. 界面去向

### 4.1 顶层状态表

| # | 账号状态 | 本机装机 | 账号下已有 Mac | 去向 |
|---|---|---|---|---|
| 0 | `.checking`（且无 `accountIssue`） | 任意 | 任意 | 启动检查页（§5.1） |
| 1 | `.signedOut` | 任意 | — | **整窗登录页**（§5） |
| 2 | `.needsSignIn` | 任意 | — | **整窗登录页，带失效原因横幅**（§5.3） |
| 3 | `.signedIn` | 已装：`.managedInstallActive` / `.managedUpgradeAvailable` | 任意 | **主界面**（现有五项侧边栏） |
| 4 | `.signedIn` | 没装：`.readyForManagedInstall` / `.waitingForSignedRelease` | 没有 | **新人引导**，从第 2 步开始（§6） |
| 5 | `.signedIn` | 没装 | **已有** | **新人引导**，先经过「这台 Mac 怎么用」选择页（§7） |
| 6 | `.signedIn` | 装了一半 / 不一致：`.existingServiceNeedsAttention` | 任意 | **主界面**，问题卡片置顶（§8） |
| 7 | `.signedIn` | `.checking` | 任意 | 启动检查页，等待本机检查完成 |
| 8 | `.unavailable`（Gateway 未开放账号能力） | 任意 | — | 整窗说明页 + 重试（§5.4） |
| 9 | `.accountDeletionSubmitted` | 任意 | — | 保持现有「云端账号删除已提交」页（`DESKTOP_DESIGN.md`） |
| — | `.signingIn` | — | — | 仍在登录页内，显示验证中 |

判定顺序：先看账号状态，只有 `.signedIn` 才看本机装机状态，只有本机没装才看账号下的 Mac。

### 4.2 门禁挡的是界面，不是服务

这一条最容易做错，必须写进测试：

- 门禁只在 `RootView` 这一层切换**呈现**。`startMonitoring()` 的循环不受账号状态影响，继续执行
  `recoverManagedBootstrapAfterRestart()` 和 `refreshHermesRuntime()`。
- 场景：一台已经装好、手机正在用的 Mac，账号会话失效（状态 2）。Desktop 整窗挡住要求重新登录，
  但 launchd 里的托管 Connector 与 Hermes **必须照常运行**，否则手机会在用户本人不知情时断线。
- 反过来，任何门禁状态下都不能自动启动安装、绑定或迁移。能改变机器的操作只由用户在引导页里的
  明确确认触发，与今天相同。

### 4.3 状态变化时的去向

| 变化 | 行为 |
|---|---|
| 主界面中会话失效（→ 状态 2） | 整窗切到登录页并显示原因；重新登录成功后**回到失效前的那一项侧边栏** |
| 用户在设置里主动退出 | 切到登录页，不显示原因横幅；再次登录后按 §4.1 重新判定 |
| 引导中途关闭窗口、退出或重启 | 下次打开按 §4.1 重新判定，引导停在与实际状态对应的那一步（§6.3），不从头开始 |
| 引导完成（本机进入 `.managedInstallActive`） | 进入主界面，默认停在概览 |

## 5. 登录页

### 5.1 启动检查页

冷启动时 `accountState` 的初值就是 `.checking`，`refreshAccount(bootstrap: true)` 完成后才落定。
这一段**不能渲染成登录页**，否则每次启动都会先闪一下登录页再跳走。显示品牌 Logo 和「正在检查」
即可。

如果 `.checking` 同时带着 `accountIssue`（Gateway 连不上，账号状态无法确认），不能一直转圈：
改为显示错误卡片（`HR-*` 码 + 中文说明）以及「重试」「复制诊断」两个按钮。

### 5.2 整窗登录页

- 替换整个窗口内容，**不显示侧边栏**，没有任何可以绕开登录的入口。
- 内容沿用现有 `signedOutCard` 的邮箱验证码流程（输入邮箱 → 六位验证码 → 验证并登录，包括「更换
  邮箱」「重新发送」），把它从「账号与设备」页移出来单独成页。
- 删除卡片里「本阶段不会停止或替换旧 Connector」这一条好处说明。
- 顶部加一行步骤提示「1 登录 · 2 准备 Hermes · 3 连接这台 Mac · 4 连上手机」，让首次用户知道
  登录之后还有什么。已装机的老用户重新登录时不显示这行（登录后直接进主界面）。
- 登录失败的错误照旧走 `accountIssue` → `DesktopIssue`，显示在输入框下方，附「复制诊断」。

### 5.3 登录失效（状态 2）

与 5.2 同一个页面，额外在顶部显示原因横幅（例如「登录已过期，请重新登录」及对应 `HR-AUTH-*`）。
横幅只在意外失效时出现，主动退出不出现，与 `LOGIN_REQUIREMENTS.md` §2 的分界一致。

### 5.4 账号能力未开放（状态 8）

没有旧版路径可以退回，所以这是一个纯说明页：「Hermes GO 服务暂不可用」+ 错误码 + 「重试」
「复制诊断」。不显示侧边栏。

## 6. 新人引导

### 6.1 形态

- 整窗、无侧边栏，顶部是四步进度条，每一步一屏，底部一个主按钮。
- 任何一步都可以「复制诊断」；可以关窗，但没有「跳过装机、直接进主界面」的按钮——没装机的主界面
  什么都做不了（§1），跳过没有意义。例外只有第 4 步（连上手机）可以「稍后再说」。
- 步骤文案给用户看的是**结果**，不是内部步骤名。内部的 `DesktopBootstrapStepKind` 可以在「查看
  详情」里展开。

### 6.2 四步

| 步 | 用户看到的 | 内部对应 | 完成判据 |
|---|---|---|---|
| 1 登录 | 邮箱验证码（即 §5.2） | `accountController.signIn` / 邮箱交换 | `accountState == .signedIn` |
| 2 准备 Hermes | 「检测到你已安装 Hermes，将直接使用」或「这台 Mac 还没有 Hermes，一键安装」 | `hermesInstallPhase`（`HermesInstallCard` 的全部分支，包括本机 Hermes 损坏时的指引 `isFreshInstallBlockedByOwnersHermes`，以及 `bundledHermesChoiceCard`） | 本机 Hermes 可达，或安装成功 |
| 3 连接这台 Mac | 一个进度条：安装后台服务 → 绑定账号 → 开机自启 → 验证连通 | `inspectExisting` → `verifySignedRelease` → `installHermes`/`configureLocalProvider` → `installConnector` → `bindAccount` → `enableAutomaticStartup` → `verifyEndToEnd`，由现有的准备 → 确认 sheet → 提交流程执行 | `bootstrapPlan.readiness == .managedInstallActive` |
| 4 连上手机 | 先选手机类型：**Android** 扫码下载 App；**iPhone / iPad** 扫码打开 Web App 并添加到主屏幕（§6.4）。两者都用同一个邮箱登录，检测到后自动打勾 | `AccountDashboard.installations` | 进入第 4 步之后出现新的 `active` 设备，类型为 `phone` 或 `browser`（§6.4）；或用户点「稍后再说」 |

说明：

- 第 2 步遵守 `docs/MANAGED_HERMES_STRATEGY.md` 的「一台 Mac 只有一个 Hermes」：本机已有 Hermes 就用
  它，只有没有时才按上游的标准方式安装；引导不提供「另装一份」的选项。
- 第 4 步分 Android 与 iPhone / iPad 两种情况，见 §6.4。
- 整个引导里用户唯一需要输入的是邮箱和验证码。`configureLocalProvider` 只是「使用本机 Hermes
  配置目录」，不要求填写 API Key。
- 第 3 步中会改变机器的操作，仍然先弹出现有的确认 sheet（`managedBootstrapConfirmationSheet` /
  `componentBootstrapConfirmationSheet`），引导不绕过确认。
- 第 3 步走哪条安装路径（schema-v1 托管发布，或 schema-v2 组件发布），沿用 `DesktopViewModel` 今天
  的可用性判定，引导不新增选择。
- `.waitingForSignedRelease`（服务端还没有可用的签名发布包）时，第 3 步显示「暂时无法安装」及原因，
  主按钮变为「重试」；不允许进入主界面假装已完成。
- 第 4 步完成或跳过后进入主界面。「稍后再说」之后，概览页应有一个「还没有手机连接」的提示卡，
  而不是再次弹出引导。

### 6.3 断点续装

引导不保存自己的进度，每次打开都从真实状态反推停在哪一步：

- 未登录 → 第 1 步
- 已登录，本机 Hermes 不可达且 `hermesInstallPhase` 不是 `.succeeded` → 第 2 步
  （`.running` 时显示进行中的安装，`.failed` / `.cancelled` 时显示对应卡片与重试）
- Hermes 就绪，`bootstrapPlan.readiness` 仍是待安装 → 第 3 步
  （安装被中断时，服务层先由 `recoverManagedBootstrapAfterRestart()` 恢复到已知安全状态，引导只呈现
  恢复后的结果）
- `.managedInstallActive`，且从未完成或跳过第 4 步 → 第 4 步
- 其余 → 主界面

「第 4 步已完成或跳过」是引导唯一需要本地记住的标记，按账号 ID 存，换账号登录后重新判断。

### 6.4 第 4 步：按手机类型分两条路

第 4 步顶部是一个分段控件「Android ｜ iPhone / iPad」，默认选中 Android。Desktop 无法预知用户
拿的是哪种手机，所以由用户选；切换只改变本页的说明和二维码，不影响完成判据。两个二维码都在本机
离线生成（与现有 `PairingQRCodeGenerator` 同一方式），不经过 Gateway 鉴权客户端，下方都附可复制的
地址。

**Android：下载 App**

- 二维码内容：公开发布服务的根地址 `https://mrlgs.net/`。`release-server` 把 `/` 302 跳转到当前最新
  的带版本号 APK（`docs/APP_UPDATE.md`；2026-09-23 线上核对：跳转到
  `/releases/Hermes-Remote-0.1.141-debug.apk`），所以二维码固定，不随发版变化，Desktop 也不需要
  请求 `index.json`。
- 步骤：① 用相机扫码下载并安装（可能提示允许安装未知来源应用）② 用同一个邮箱登录 ③ 选择这台 Mac。

**iPhone / iPad：添加 Web App 到主屏幕**

目前没有 iOS App。iPhone 与 iPad 使用 Gateway 托管的 Web App（`https://mrlgs.net/app/`，2026-09-23
线上核对：`/app` 308 跳转到 `/app/`，页面声明了 `manifest.webmanifest`、`apple-touch-icon` 与
`apple-mobile-web-app-capable`），添加到主屏幕后以独立窗口全屏运行。

- 二维码内容：`https://mrlgs.net/app/`。
- 步骤：
  1. 用相机扫码，在 **Safari** 中打开。
  2. 点「分享」按钮：iPhone 在屏幕底部，iPad 在地址栏右侧。
  3. 在菜单中选「添加到主屏幕」，点「添加」。
  4. 从主屏幕上的 Hermes GO 图标打开，用同一个邮箱登录，选择这台 Mac。
- 提示一行：「请从主屏幕图标打开。直接在 Safari 标签页里用也可以，但会显示浏览器的地址栏和工具栏。」
- 文案只写 Safari。其他 iOS 浏览器的菜单名称各不相同，本页不逐一说明。

**完成判据与展示**

- Android App 登录后，设备列表（`AccountDashboard.installations`）出现 `kind == "phone"` 的记录；
  Web App 登录后出现的是 `kind == "browser"`、`platform == "web"` 的记录。今天 Desktop 的
  `AccountDashboard.phones` 只筛选 `phone`，**必须把 `browser` 也纳入第 4 步的完成判据**，否则
  iPhone 用户登录后第 4 步永远不会打勾。这只改 Desktop 的筛选，Gateway 已经返回 `browser` 记录，
  `ManagedAccountInstallation.kind` 也按字符串解码，不需要改契约。
- 只认进入第 4 步**之后**新出现的设备，避免把账号里早已存在的设备误判为「刚连上」。实现时以进入
  第 4 步时的设备 ID 集合为基线，出现不在基线中的 `phone` / `browser` 即完成。
- Web App 登录时不上报设备名，Gateway 默认记为 `Web browser`，无法区分 iPhone 还是电脑上的浏览器。
  Desktop 把 `browser` 类设备显示为「网页版 Hermes GO」，不写成「iPhone」。在电脑浏览器里登录 Web App
  也会让第 4 步打勾，这是可以接受的：它同样是一个能访问这台 Mac 的远程入口。
- 后续可选（不在本需求内，属于 Web 包的改动）：Web App 登录时根据 UA 上报「iPhone · Safari」这类
  设备名，Desktop 就能显示得更具体。

## 7. 老账号、新 Mac（状态 5）

用户已经在别的 Mac 上装好，现在这台 Mac 上登录。意图不明确，所以登录后先问一句，再进引导。

### 7.1 选择页

标题：「你的账号已连接 N 台 Mac」，列出已有 Mac 的名称与在线状态，然后给两个选择：

- **把这台 Mac 也连上**：进入新人引导第 2 步，这台 Mac 作为账号下的又一台自有设备。
- **只在这台 Mac 上管理**：不在本机安装任何东西，直接进主界面，默认选中一台已有的 Mac
  （`selectDevice`），概览显示那台 Mac 的状态。这台 Mac 相当于一个管理端。

选择「只管理」之后，概览需要一个入口「把这台 Mac 也连上」，用户改主意时可以回到引导。

### 7.2 名额

服务端的自有 Mac 上限由 `ACCOUNT_MULTI_DEVICE_ENABLED` 决定：开启时为 3 台，关闭时为 1 台
（`gateway/src/account/account-runtime.ts`）。设备共享功能要求多设备开启，所以只要生产环境开了
设备共享，上限就是 3。Desktop 客户端只在设备列表读取失败时才回退到 1。

- `ownedDevices.count < maxOwnedDevices`：直接加，**不存在替换**。两个选项都可用。
- 名额已满：「把这台 Mac 也连上」变为「先移除一台 Mac」，说明「一个账号最多连接 N 台 Mac」（对应
  `HR-BIND-010`），点击进入 §7.3 的移除流程；移除成功、名额空出后回到本页，「把这台 Mac 也连上」
  恢复可用。「只管理」始终可用。

本需求**不做替换**。Gateway 的替换接口（`/v2/connector-binding/replacement-requests`）不接入：
「先移除、再添加」已经覆盖满额场景，两步各自可确认，不需要第二套机制。

### 7.3 移除一台 Mac

服务端能力已经齐全，只缺客户端：

- 接口：`DELETE /v2/devices/{deviceId}`（仅在 `multiDeviceEnabled` 时开放），请求体带 `grant`，头带
  `Idempotency-Key`。
- `grant` 来自邮箱重新验证，用途（scope）为 `connector.unbind`——Gateway 的
  `reauthenticationScope` 已接受该值。
- 流程与 Desktop 现有的「移除手机」相同：确认 sheet → 发送邮箱验证码 → 输入验证码换取 grant →
  调用删除。实现时复用 `phoneRevocationSheet` 与 `account.installation.revoke` 的写法，只换 scope
  和接口，**不改 Gateway 契约**。
- 目前 Desktop、Android、Web 均未调用这个接口，Desktop 是第一个客户端。

界面：

- 名额满时，选择页（§7.1）列出已有的 Mac，每台带「移除」按钮。
- 主界面「账号与设备」的设备卡片也加「移除」（今天只有「切换」「设为默认」），供平时管理使用，
  不只在满额时出现。
- 只能移除 `access == "owner"` 的 Mac；别人共享给我的 Mac 仍是现有的「退出共享」。
- 确认文案必须写清后果：被移除的 Mac 立即断开，用这台 Mac 的手机将无法再访问它；那台 Mac 上的
  Hermes 数据**不会**被删除。
- 被移除的那台 Mac 下次刷新会收到 `HR-BIND-006`（绑定已撤销），它的 Desktop 按 §4.1 视为「本机装了
  但未绑定」，在概览中显示「这台 Mac 已从账号移除」并提供重新连接的入口。那一侧的具体呈现在实现时
  补进 `DESKTOP_DESIGN.md`。

需要的测试：删除接口的请求构造（路径、grant、幂等键）、`connector.unbind` 验证码换 grant、
`not_found` / `reauthentication_failed` 的错误映射、移除后 dashboard 刷新使名额恢复。

## 8. 装了一半或状态不一致（状态 6）

`.existingServiceNeedsAttention` 包括安装中断、需要人工检查、launchd 与安装记录不一致三种情况。
`DesktopBootstrapPlanner.plan()` 在这些情况下本来就把 `canBegin` 设为 false，目的是防止装出第二份
服务。所以：

- **不进新人引导**，引导不能再提供一个安装入口。
- 进主界面，并在概览页顶部放一张问题卡片，内容取自 `bootstrapPlan` 的标题与说明，附「复制诊断」
  和「查看详情」（跳到「账号与设备」）。

## 9. 主界面与菜单栏的配套调整

- **概览页**：已装机的正常用户才会看到它，所以兼容观察横幅（`compatibilityBanner`）改为显示托管
  服务状态；「运行诊断」不再是主按钮。
- **诊断页**：未登录时不可达，无需再为「未登录」解释检查结果。
- **菜单栏**：保留。未登录时，「Hermes GO 账号」一行变成可点击的「登录」，点击后打开主窗口的登录页；
  未装机时显示「完成设置」，打开主窗口的引导页。选择了「只在这台 Mac 上管理」时，本机没有
  Gateway / Hermes 可查，菜单栏的状态行改为显示所选那台 Mac 的状态（名称、Connector 在线、Hermes
  可达），数据来自 `selectedAccountDevice`，与概览一致。
- **旧版路径移除**（单独 PR）：`LegacyConnectorInspector` / `LegacyConnectorConfig`、
  `ConnectionProfile(Store)`、`PairingQRCodeView` / `PairingQRCodeGenerator`、「高级：旧版 URL、Token
  与二维码连接」折叠区、`DesktopAgentPresentation` 的 `.legacy` 模式、`DesktopBootstrapPlanner` 的
  `legacy:` 参数，以及 `LogsView` 的日志来源。Android 侧有对称的旧版入口（`ui/setup/SetupScreen.kt`、
  `PairingPayload.kt`、`ConnectionSettingsScreen.kt`、登录页和选 Mac 页上的「旧版 Relay / App Token」），
  Gateway 的 App Token 鉴权是否一并退役属于 `docs/INTEGRATION.md` 的契约面，需要另行决定。

## 10. 已定决策与实现时核对项

2026-09-23 产品确认：

- 老账号、新 Mac 时先问「把这台 Mac 也连上 / 只在这台 Mac 上管理」（§7.1）。
- 未满额直接添加，不做替换；满额时先移除一台（§7.2、§7.3）。
- 第 4 步分 Android（二维码下载 App）与 iPhone / iPad（二维码打开 Web App 并添加到主屏幕）两种情况（§6.4）。
- 「只管理」模式下菜单栏显示所选 Mac 的状态（§9）。

实现时核对：`DELETE /v2/devices/{id}` 的 `not_found` / `reauthentication_failed` 等结果在 Gateway 上
映射成哪些 `HR-*` 码，逐一对照 `docs/ERROR_HANDLING.md`；如无现成码，先登记再用。

## 11. 验证要求

实现时按 `AGENTS.md` 的 Desktop 基线执行（`npm run desktop:assets:test`、`npm run desktop:test`、
`npm run desktop:app`），并同步更新 `docs/DESKTOP_PHASE0.md`、`docs/DESKTOP_DESIGN.md`、
`docs/DESKTOP_TEST_PLAN.md`。至少需要以下单元测试：

- §4.1 状态表的每一行，由一个纯函数（例如 `DesktopEntryRoute.resolve(account:readiness:dashboard:)`）
  决定去向，逐行覆盖。
- `.checking` 不会被判定为登录页；`.checking` 加 `accountIssue` 判定为可重试的错误页。
- 会话失效时 `startMonitoring()` 循环继续运行，`refreshHermesRuntime()` 仍被调用（§4.2）。
- 断点续装：§6.3 的每个反推分支。
- 名额满 / 未满时，§7.1 两个选项的可用性。
- 第 4 步完成判据：基线之后新出现的 `phone` 与 `browser` 设备都判定为完成；基线中已有的设备不判定。
- `.existingServiceNeedsAttention` 不会进入引导（§8）。

干净 Mac 上的完整首装流程无法自动化，需要加入 `docs/DESKTOP_TEST_PLAN.md` 的人工步骤。
