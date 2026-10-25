# Hermes 兼容管理实施记录

本记录对应 [迭代规划](HERMES_COMPATIBILITY_ITERATION_PLAN.md)。用户于 2026-10-25 授权按该规划持续实施。当前未向生产授予角色、未发布清单、未升级日常 Hermes。

## 进度

| 迭代 | 状态 | 尚缺的验收 |
|---|---|---|
| I0 | 升级/失败恢复演练通过，但发现真实锁协议缺口 | 旧 updater 在父进程死亡、子进程存活时错误放行；不能通过并发验收 |
| I1 | 本地实现与测试完成 | 真实账号启用及完整登录交互待验收 |
| I2 | 队列/工作流/报告已有本地实现 | 当前五项通过、并发一项失败，其余九项未验收；未运行真实 CI |
| I3 | 审批/签名发布协议/客户端校验已有本地实现 | 真实发布组件证据产出、受保护工作流及普通页面接线待完成 |
| I4–I5 | 未完成 | 锁协议缺口解决前不能开放一键升级；执行器、真实端到端和发布均未交付 |

## I0：固定输入与入口审计

- GO 基线：`4ba4505f66b34dca85215aa51bcbbef4a4f4654e`。
- 本机只读观察：macOS arm64，传统 `venv/bin/hermes`，源码 `633dda6d7fdcac7e8340471c9f5ed08cdccbd70c`，工作树干净。未运行任何更新命令。
- 官方稳定发布：[v0.22.5](https://github.com/NousResearch/hermes-agent/releases/tag/v0.22.5)，发布时间 `2026-10-23T05:28:45Z`；实际解引用 commit 为 `bf0203a794a07aac3ff764972ab9087baa4e4f4e`，不能把 annotated tag 对象 `56b6a31fededdf7364e1a0a79548d144e7fd81a3` 当代码 commit。
- 该 commit 的 `scripts/install.sh` SHA-256：`99cb3691d55019c6973a7acb775a915648666cc24e483ca827a1cdbb3531a674`。
- 旧版 `hermes update` 只有 branch 选择；v0.22.5 加入 channel，但仍无公开的指定 commit 更新参数。通道名不是不可变目标，不能直接用于 GO 的精确审批。
- v0.22.5 官方安装器公开支持 `--commit`、`--dir`、`--hermes-home` 和分阶段 JSON 协议。现有 checkout 的 repository 阶段会先移动至 branch，再 pin commit；pin 必须属于该 branch。运行依赖之前必须另行确认最终 HEAD。
- 安装器的 products 阶段调用上游自己的 source completion，负责 launcher、产品构建、配置迁移。完整更新锁在 completion 内部取得，**不能据此声称 repository 阶段已经停写或受全程锁保护**。
- v0.22.5 的 29 个现有 RPC params 模型与当前 `docs/hermes-rpc-params.json` 做 AST 字段比较：没有删除已有键；4 个模型增加共 7 个有默认值的字段。这只证明字段清单差异，不证明方法注册、字段语义、事件或完整业务兼容，因此暂不扩大参数 allowlist 或标记该版本受支持。

这些事实来自固定 commit 源码，不从旧 HG-199 草稿推断。第一步只在一次性实验目录演练；不复制日常数据库、凭据或 LaunchAgent，不把环境变量隔离当作进程隔离。

## I0 测试影响评审（实施前）

本轮新增开发者用的隔离演练工具，尚不改变 Desktop/Gateway 的线上行为。工具必须覆盖：固定 commit/安装器摘要校验，失败阶段区分，升级后的精确 HEAD 检查，恢复到原绝对路径，配置与 SQLite 文件的恢复校验，以及无法建立隔离时拒绝执行。单元测试使用合成仓库，只证明工具控制流；官方脚本真实运行证据单独记录。

现有 `HR-HERMESUPDATE-002/003` 分别用于实验前置拒绝和未完成恢复；本轮不复用错误码表示新的业务含义，不把异常原文作为主要提示。报告只记录闭合阶段名、commit、摘要、平台和结果；完整工具输出留在权限受限的实验目录。

安装器演练通过不等于兼容认证。真实依赖、Hermes REST/RPC、GO Connector、受管服务及中断恢复都需要各自证据；未执行项始终标为未执行。

### I0 发现的 Desktop 版本识别适配

v0.22.5 `hermes_cli/__init__.py` 不再声明固定 `__version__ = "x.y.z"`；`source_stamp.py` 在源码完成阶段生成 schema 2 的 `install-stamp.json`，包含完整 `commit` 和 `baseVersion`。当前 Desktop 正则只能读旧声明，因此新版正常安装仍会被识别为 `unreadableIdentity`。这属于首条升级路径的必要修正。

测试先固定新版 lazy identity 样本，要求不导入/执行 Python 即能识别；再覆盖旧版声明、stamp 与 HEAD 不匹配、未知 schema、包分发 stamp、损坏/软链接/可被其他用户写入的 stamp。保留现有 `unreadableIdentity` 错误含义与映射，不增加新码。stamp 只证明磁盘版本来源，不能代替依赖或服务健康验证。此改动需 Desktop 全量测试和 app 构建，暂不安装或发布构建结果。

## 可重跑的 I0 实验

工具：`scripts/hermes-compatibility-lab.py`；固定输入：`scripts/fixtures/hermes-compatibility/v0.21.3-to-v0.22.5.json`。
需要 macOS、Python 3 标准库、Git 和可用的系统 sandbox。先在**独立审计 clone** 中取得两端 commit 及对应 `scripts/install.sh` blob；不要在日常 Hermes checkout 拉取或运行实验。

```bash
python3 -B scripts/hermes-compatibility-lab.py \
  --cache /path/to/disposable-upstream-audit \
  --case scripts/fixtures/hermes-compatibility/v0.21.3-to-v0.22.5.json

# 完整依赖 + 真实旧版 SessionDB 合成会话 + 临时 HTTP 服务读取 + 旧环境恢复：
python3 -B scripts/hermes-compatibility-lab.py \
  --cache /path/to/disposable-upstream-audit \
  --case scripts/fixtures/hermes-compatibility/v0.21.3-to-v0.22.5.json --runtime

# 故障演练：新依赖落盘后主动失败，退出码应为 1；检查 restore/restoredHistory 必须通过。
python3 -B scripts/hermes-compatibility-lab.py \
  --cache /path/to/disposable-upstream-audit \
  --case scripts/fixtures/hermes-compatibility/v0.21.3-to-v0.22.5.json \
  --runtime --fault-after target_python-deps

node --test scripts/test/hermes-compatibility-lab.test.mjs
```

实验每次新建 owner-only 临时目录，实际安装直接克隆官方仓库。审计缓存只用来核实输入，不作为实验安装的 alternates 或硬链接依赖。上游子进程不能写实验目录以外的文件、读取日常 HOME 或向其他进程发送信号，也不能调用 launchctl/sudo。开始前用无敏感内容的外部临时哨兵验证写入隔离有效。

实验还拒绝候选程序写报告、实验脚本和备份，只允许其写合成用户目录、临时工作区和私有日志。旧安装器在 BSD `mktemp` 无参数失败时回退硬编码 `/tmp`，首次 runtime 实验被隔离规则正确拒绝。实验 PATH 中现在提供仅补充无模板形式的 `mktemp` 适配，把这些临时文件放入实验 TMPDIR；不改官方脚本、不开放全局 `/tmp` 写权限。这项环境差异不能冒充日常机器原样执行的证明。

默认模式只验证代码切换与文件恢复，报告为 `source_stage_passed`；`--runtime` 额外验证真实依赖和同一条合成会话在旧版、新版及恢复后的旧版 HTTP 中可读取，全部通过才为 `runtime_stage_passed`。两者都保留 `compatibilityApproved: false` 和未执行门禁。`--runtime` 首版只接受已审计的这两个 commit，不猜测其他版本的安装阶段。

完整实验还显式取得并校验官方目标 tag（上游新版从可达 release tag 生成 stamp 的 baseVersion），并比较 HTTP 实际版本与预期版本。`--fault-after` 只接受两个固定故障点，不执行任意用户命令；注入失败始终记录为失败，只有恢复检查另外记录通过。

报告保存 commit、安装器/实验脚本摘要、平台、逐阶段退出状态及恢复摘要。完整原始日志仅留在私有实验目录，不上传。实验中的 source/candidate/snapshot 是合成测试环境，不是该 Mac 的第二份日常 Hermes。快照保留整个实验用户目录，并恢复到原绝对路径；仅恢复 Git commit 不能验证旧解释器和依赖可用。

本实验尚不模拟应用被杀、断电或任意第三方进程同时写入；这些门禁一直保留在 `notExecuted`。失败现场与快照不会自动删除，排查后只清理由本实验生成的目录。

## 本地检查记录

- 新增工具单元测试已验证合成仓库下成功恢复、切换后失败恢复、错误目标拒绝、损坏快照拒绝、隔离不可用拒绝、输入/环境限制。它们不替代官方执行结果。
- Desktop 新 stamp 正向回归在修正前确实失败；修正后 30 项 detector、664 项 Swift 全量、assets 检查及本地 ad-hoc app 构建通过，未安装/发布。
- 初次脚本基线缺少工作区依赖；补齐锁定依赖并构建 protocol 后，`npm run test:scripts` 为 431 通过、1 失败、4 跳过。唯一失败是既有 `legacy-recovery` 的 CMS 解密，当前系统 `openssl` 是 LibreSSL 3.3.6。用临时自签测试证书独立复现：AES-256-GCM CMS 加密成功、解密退出 4（`Error decrypting CMS structure`），不依赖本次改动。需要在匹配的 OpenSSL 环境复核，不能据此声称基线全绿。该恢复代码未修改。
- 官方**源码阶段**实跑通过：固定目标 `bf0203a794a07aac3ff764972ab9087baa4e4f4e`，恢复至 `633dda6d7fdcac7e8340471c9f5ed08cdccbd70c`；恢复前快照与恢复后树摘要均为 `8e036d154486a43a30961851c7e0b01cd1a7bf29ebfbef1589257efe3f3757ec`。本地证据目录 `hermes-compat-lab-v3r1smq6`，结果 `source_stage_passed`、`compatibilityApproved=false`。这次实验早于 runtime/报告脚本摘要增强，不能作为 I2 的完整认证报告。
- 官方完整运行时中间实验 `hermes-compat-lab-i6ubtx9r` 已通过：旧版真实依赖、SessionDB 合成会话、目标 PM 依赖/产品完成、候选历史读取、整个合成用户目录恢复、旧版历史再次读取。快照/恢复摘要相同：`4dccdcf45fa4395834dbd5c67a3f32c66a004d98db97128837cdd5ec566961a5`。该次套件摘要 `f79df50e99a8532af31098e799a8510fedc25e3ac529a870fcc1d244637b427f`，早于显式 tag 和 HTTP version 校验，因此不能替代最终验收。当前正在固定最终脚本重跑。
- 该轮实际旧版/新版/恢复后 OpenAPI 路由清单分别有 248/263/248 条；用现有 Connector `evaluateHermesContract` 读取三个真实服务采集文件，42 条消费路由全部存在。这里只证明 REST 路由清单，没有调用所有路由或证明 RPC 语义。

## HG-199 原型取舍

审计基于草稿提交 `e434d63d`、`f06869f8`，不整体合入草稿。

| 模块 | 处理 | 理由与后续验收 |
|---|---|---|
| 已合入主线的发布查询、Git/marker 检查、只读 journal | 复用 | 保持不执行上游源码的被动检测；补上本轮新版 stamp 识别 |
| 固定 REST/RPC 清单、解析样本和合成历史测试思路 | 复用并重新跑 | 旧草稿静态/合成通过不能认证当前真实目标，必须绑定新的报告 |
| `DesktopHermesUpgradeMachineEvidenceProviding` 等注入式证据接口 | 作为验收清单参考，不当实际采集器 | 原型提交明确没有生产 collector；必须逐项取得实际来源 |
| 事务 journal、原服务清单和私有备份校验 | 有选择复用设计与测试 | 正式执行前仍需原绝对路径依赖恢复、服务归属和进程中断实证 |
| 整体 Python 私有更新 worker / takeover 流程 | 替换 | 优先使用固定 commit 官方 installer；不继续复制上游整个内部更新流程 |
| `executionAvailable=false` | 保留至 G2 门禁 | 不因管理页完成或注入测试变绿就打开升级按钮 |

已确认的官方接口缺口是 repository 阶段没有全程更新锁与写入排空。这要求 HC-07 提供有界且按版本验证的锁/服务协调，或补齐官方接口；不能把官方 products 阶段自己的锁等同于完整事务。首版排除无法识别或无法停写的外部服务，不能自动杀第三方进程。

HC-03 的固定合同另见 [控制面合同](HERMES_COMPATIBILITY_CONTROL.md)。它可在 HC-02 实验进行时整理；I1 尚未验收。

## I0 固定套件最终实跑

两次都使用套件 SHA-256 `84e0aeb232ef754dd94b07a2363fe6c64d938a326731e48de7909815d0f52090`，不是前述中间版本。

- [完整升级及恢复报告](evidence/hermes-compatibility/2026-10-25-runtime.json)：`runtime_stage_passed`。实际 HTTP 版本依次为 0.21.3 → 0.22.5 → 0.21.3，三次均读到原合成会话；精确 commit、官方 tag 及真实依赖/产品阶段通过。恢复摘要 `48f4d46bd9202391ec0a4887eeadb5f0466746ab56eacb76772574942ecf1514` 与升级前快照一致。本地目录 `hermes-compat-lab-jo28st1h`。
- [依赖落盘后注入失败报告](evidence/hermes-compatibility/2026-10-25-dependency-failure.json)：预期 `failed`，原因 `injected_failure_after_target_dependencies`；没有错误地显示升级成功。restore/restoredHistory 都通过，实际运行恢复为 0.21.3；摘要 `271707993b8d951e3869d44b105aac468cb3af815f571a940b03333c13ec0b25` 与升级前一致。本地目录 `hermes-compat-lab-um46lsn5`。
- [最终轮 REST 路由清单核对](evidence/hermes-compatibility/2026-10-25-rest-paths.json)：旧版、新版和恢复后都满足现有 42 条 Connector 路由清单。仍未证明全部端点行为或 RPC。

这证明恢复必须保留完整旧运行环境并恢复原路径，不能只切换 Git commit。两个报告仍为 `compatibilityApproved=false`，LaunchAgent、完整业务、并发与突然中断不在这两次实验的已通过范围。

## I1 实现与检查

新增 schema 21：独立管理员授予、候选、任务、报告、审批、发布快照、幂等操作和审计。`ACCOUNT_HERMES_COMPATIBILITY_ENABLED` 默认关闭。没有生产迁移、授予或部署。release-contract 的 schema 更新属于红灯集成范围，后续合并/发布按既有门禁；未升 app 或服务包版本。

管理请求使用既有 bearer session，事务内重新验证账号、session、installation 和稳定 account ID 的管理员角色。初始授予工具只接受明确 account ID 与匹配的已验证邮箱，没有登录时自动按邮箱提权。Desktop 根据服务端权限显示入口，换账号/签出/拒绝后清除旧数据。新增失败使用 HR-HERMESCOMPAT-001–006，本地摘要不直接显示服务器原始文本。

候选从固定官方 API 获取稳定 release、解引用 commit 和安装器摘要；移动 tag 保留旧身份并标异常。任务和审批有修订检查和幂等键。失败、缺项、未执行、跨 run/identity 报告及未发布组件不能审批。普通管理 HTTP 不接受报告上传。I2 验证配置和派发尚未接入，runtime 的 recipe 列表为空；页面不代表流水线已完成。

本机缺少 PostgreSQL，已按[官方构建方式](https://www.postgresql.org/docs/18/install-make.html)在本任务临时目录构建校验过摘要的 PostgreSQL 18.0，仅监听 owner-only Unix socket，不开放 TCP。真实数据库测试覆盖授予/撤销、伪造邮箱、并发幂等、陈旧修订、任务去重、冲突报告、失败证据拒绝、审批审计及已撤销 session。使用合成账号和独立 schema，测试结束后删 schema。

- Desktop assets、667 项 Swift 测试、本地 ad-hoc app 构建通过，未安装/发布。新增页深浅色原生合成窗口检查通过；完整登录交互仍待检查。离屏 bitmap 输出不完整，未当作视觉通过证据。原生工具失败后仅截取本任务预览窗口完成深浅色检查。
- Node 构建通过；Protocol 18/18、Connector 153/153、Gateway 259 通过/14 跳过/0 失败（真实 PostgreSQL 测试已运行）、release-server 41/41。
- 脚本基线提供测试数据库后为 434 通过、2 失败、0 跳过；两项失败均为既有 LibreSSL CMS 解密路径，第二项原先跳过，实际运行后暴露同一原因。未改旧恢复逻辑，仍需要 OpenSSL 环境复核。
- `git diff --check` 通过。只有隔离工作树里的本任务修改，没有 commit、push、PR、生产启用或日常 Hermes 变更。

## I2 进行中：固定工作流身份与队列

已实现 GitHub OIDC 签名/issuer/audience/repository ID/main workflow/精确 GO commit/run attempt 校验及 GitHub run metadata 的任务 UUID 交叉核验。准备/回收与实际执行上游代码分不同任务；尚未派发真实 CI。管理员 bearer token 不能回传报告。任务队列持久记录派发尝试，最多三次、间隔五分钟；超时转基础设施异常而不是通过。重跑 GitHub attempt 2 不能覆盖原 run，需管理员新建任务。

配置解析只允许固定项目仓库、固定环境和组件组合，不接受脚本或路径；无配置时仍保持空 recipe。新增代码尚需与实际 workflow/报告脚本完成联调和全量复验，不能记为 I2 完成。

### I2 本地联调进展

已加入三 job 工作流及领取/验证/报告脚本。验证 job 没有 OIDC 写权限，上传白名单证据；回收端重算 bundle 和逐项摘要。报告丢失或校验失败回传基础设施异常。已有真实 I0 两份报告经转换只证明安装、历史、升级、恢复四项，其余十一项未执行，审批仍拒绝。Desktop 已接发起验证菜单和逐项报告。

新增超时回归先在旧 90 分钟规则下失败，改为 120 分钟后真实 PostgreSQL 测试通过；避免最长 85 分钟验证加准备/回收尚未结束就超时。定向 Gateway 14/14（含真实 PG）、脚本 6/6（含 Python lab 包装）、Desktop 669/669、资源和本地 app 构建通过。新增菜单/15 项报告深浅色原生合成窗口已检查。未启动真实 GitHub workflow，未部署服务/授予角色，I2 线上闭环仍待实际启用验证。

I2 Node 基线复验：Protocol 18/18、Connector 153/153、Gateway 265 通过/14 跳过/0 失败、release-server 41/41；脚本 439 通过/2 失败，仍是已记录的本机 LibreSSL CMS 问题。

## I3 进行中：受保护签名发布与精确匹配

实现待发布任务、固定发布 workflow、`desktop-release` 环境 OIDC 核验、专用 purpose 的 Ed25519 快照、公开回读后提交和撤回。Gateway 只持有公钥与派发凭据，签名步骤使用现有发布环境中的私钥。被 GitHub 接受的任务等待环境审批，不反复派发；超时和派发失败可见。批准、发布请求和 published 状态分别表达。

发布前及公开回读后复核已发布组件证据；撤回立即停止提供受影响旧清单，并可发布更高修订的空清单。Swift 校验格式/签名/用途/origin/有效期/修订，按完整源版本、系统与五个组件身份匹配最高稳定目标。共享 TypeScript 签名 fixture 在 Swift 中验签通过，仅为合成样本，不含私钥或实际支持声明。

本阶段定向 Gateway 20/20（含真实 PostgreSQL）、worker 脚本 7/7、Swift API/签名 25/25 通过。审批/发布/撤回按钮及发布任务状态已接入；新增按钮的原生视觉检查、本阶段完整基线、真实受保护工作流、发布组件清单产出/续期、普通客户端联网获取与执行前新鲜度尚待完成。没有生产权限授予、私钥配置、CI 派发或发布。

### I3 后续本地验证

新增公开清单 HTTP 客户端：无账号凭据/cookie、拒绝重定向、512 KiB 上限、超时、禁用缓存；每次 refresh 都联网。验签后持久保存每个 origin 的最高修订和签名载荷摘要，跨客户端重启拒绝旧修订或同修订不同内容，持久化失败或断网不返回旧授权。它仍需接到普通页面及 I4 开始变更前的复核；单独的 refresh 返回值不是执行许可。

重复 JSON key 的签名载荷回归在修复前确实失败，修复后拒绝歧义载荷。公开 HTTP、持久重放与离线拒绝测试通过。最新本地基线：Desktop 679/679、assets、ad-hoc app 构建；Protocol 18/18、Connector 153/153、Gateway 271 通过/14 跳过、release-server 41/41。脚本 442 通过/2 失败，仍是本机 LibreSSL 对既有 legacy-recovery CMS 路径的失败，没有据此宣称全基线绿色。

管理页新增批准、发布和撤回状态已用单独合成窗口检查深浅主题、顶部和底部滚动布局。预览中的 15/15 是专用 UI 样本，**不是本版本通过证据**。没有操作真实管理员账号、发布清单或安装该 app。

## I0/I4 实际阻断：旧 updater 的锁不能覆盖存活子进程

新增 `scripts/hermes-compatibility-lock-lab.py` 仅在临时 HOME 使用固定两个 commit 的原始 `update_lock.py`，不运行安装器、不访问日常 HOME。复跑两次均得到 [失败证据](evidence/hermes-compatibility/2026-10-25-update-lock.json)：

- 新版持锁父进程存活时，旧/新版兄弟进程均拒绝进入。
- 强制结束持锁父进程，保留继承 checkout flock 的安装子进程；新版兄弟进程仍拒绝，旧版兄弟进程却取得锁。
- 整个自有进程组退出后，新版可以重新取得锁。

原因来自固定源码：旧版只检查 marker 的 owner PID 和 20 分钟期限，不识别新版继承的内核锁或存活 delegate。新版 marker 前两行与旧版兼容；问题不是格式改成了无法解析的新版前缀。旧版 acquire 本身也不是原子独占写，不能靠 GO 自己再加一个锁就证明外部 updater 遵守互斥。

CI 已复用同一探针：发现冲突时 concurrentUpdate 明确 failed；即使单一探针通过，也不能把完整并发矩阵直接标为 passed。对应回归先失败、实现后通过。该探针不会伪造 processInterruption、Desktop 生命周期或新数据保护通过。

下一步需要解决官方更新入口的全程锁/子进程托管接口，或由负责人明确修改首版支持范围；不能在原规划里悄悄降低并发验收门槛。可审阅的边界和验收条件见 [上游接口缺口](HERMES_UPGRADE_UPSTREAM_GAP.md)。当前未编写绕过该缺口的生产执行器，普通升级仍关闭。

## 当前审阅状态

本任务代码与证据保存在隔离分支 `codex/hermes-compatibility-plan`，首个本地提交为 `7afba960`。远程推送被自动审批拒绝，理由是该次外部代码披露尚无明确授权；已请求用户授权，没有改用其他通道推送，未创建 PR。

后续本地复核补齐服务端签名载荷的重复 JSON key 拒绝，避免与 Swift 对同一签名字节产生不同解释；新增回归修复前失败、修复后通过。受影响 Gateway 基线复验为 272 通过/14 跳过/0 失败（真实 PostgreSQL），兼容 worker 8/8 通过。此修复没有改变已记录的真实上游实验结论。

仍未完成：真实账号启用与 CI/受保护发布、发布组件证据产出和续期、普通页面的组件身份采集及接线、九项未验收的兼容检查、I4 执行器和 I5 设备端到端。不能仅凭获得推送授权或合并管理代码就越过这些门槛。未部署、未发布、未升级日常 Hermes。

## I2 真实运行接口补验

在已验证的合成安装实验中，使用原绝对路径分别启动旧版 0.21.3、新版 0.22.5 和恢复后的旧版。没有运行新的安装器，也没有访问日常 HOME、模型凭据或生产账号。`hermes-compatibility-contract-lab.py` 只接受临时目录中的成功 runtime lab，使用原隔离 profile；候选暂时激活后，无论探针成功与否都恢复原目录位置，保留候选数据。控制流测试验证拒绝日常 HOME、候选失败后的原路径恢复和候选新增合成文件保留；这不是生产恢复实现。

三次实际运行均通过 11 个局部检查，见 [运行接口证据](evidence/hermes-compatibility/2026-10-25-live-contracts.json)：HTTP 缺失/错误 token 被拒绝，正确 token 可读取；WebSocket 缺失/错误 token 被拒绝，正确 token 可调用 active_list 并由真实 Connector parser 解析；运行版本一致；会话列表、跨 profile 列表和历史读取保留合成会话；真实 Connector OpenAPI evaluator 返回 compatible。token 仅在内存和子进程 stdin 传递，报告不含 token、响应正文或原始日志。GET 可能触发上游自身维护写入，因此只在合成 HOME 中测试。

该证据只新增认证项通过，范围是 Desktop 托管的 loopback session token 模式。没有据一次 active_list 或 schema 检查把 RPC/Connector/客户端业务整体标为通过。CI 已串联该探针并绑定两个脚本摘要。验证 job 从 85 调整到 105 分钟，服务端运行期限从 120 到 150 分钟；125 分钟仍运行、151 分钟转超时的真实数据库回归在旧规则下失败后修复。一次数据库启动未带原专用 socket 参数导致连接失败，已停止并按 `-k <测试 socket> -p 55439 -h ''` 重启，确认 listen_addresses 为空后复验；该失败不算作通过证据。

本轮最终基线：Protocol 18/18、Connector 153/153、Gateway 272 通过/14 跳过/0 失败、release-server 41/41；脚本 444 通过/2 失败，仍为已记录的本机 LibreSSL CMS 问题。本轮没有 Desktop 源码改动，沿用上一轮 679 项 Swift 与原生页面检查，不声称重跑。最终探针字节再次实跑三阶段通过。

用户随后明确授权将本任务分支推送到 `guoshengliang-coder/hermes-remote` 并创建草稿 PR；该授权不包括合并、部署或升级日常 Hermes。此前推送拒绝已获得所需补充授权，后续仍按独立门禁执行。

## 草稿 CI 与发布组件证据

已创建 [草稿 PR #673](https://github.com/guoshengliang-coder/hermes-remote/pull/673)。首轮 gitleaks 将两个实验报告的五个 `openapiPathsSha256` 识别为 generic-api-key；逐一用原始仅含路径/方法的文件重新计算，五个摘要全部一致，确认为公开路由摘要。沿用仓库规则，仅排除这五个精确历史 commit/file/line 指纹。提交 `229143e7` 的 11 项 GitHub 检查全部通过，包括 Node、Desktop、Android、Web、密钥扫描及独立恢复流水线。`merge-when-green --no-merge` 拒绝草稿，随后使用只读 `gh pr checks --watch` 等待，未改变草稿、合并或发布。

I3 后续测试影响评审：现有 Web 发布只回读首页，不能证明兼容记录里的整个 Web 组件包已经可取得。本轮新增只读 Web 证据采集器，复用真实 package/manifest/tar 解析，绑定原始归档摘要、版本及公开文件全集；首尾两次回读首页以发现检查期间切换。它只生成未签名组件观察，尚未接入受保护 inventory 汇总，不冒充完整 I3 或 Web/Hermes 业务验证。其余四类发布证据来源、inventory 签名/续期仍待完成。

测试覆盖修改脚本但首页不变、缺文件、重定向、部分响应、过大响应、归档与清单不一致、部署中途切换、无凭据请求及中英错误序列化/脱敏。另用真实 package → 临时目录 publish → loopback HTTP → readback，证明整个合成 Web 发布可核验，修改已发布 JS 后确实拒绝；不是线上发布证据。首次 loopback 测试被执行沙箱禁止监听，获准测试执行后通过。没有修改 Web 产品代码、现有发布流程、Desktop UI 或部署任何服务。

本轮验证：Web 证据与既有 Web 发布定向测试 11/11；脚本基线 449 通过、2 失败、1 跳过。两项失败仍为已记录的本机 LibreSSL CMS 解密；跳过项因本轮误用了数据库变量名，改用 `ACCOUNT_TEST_DATABASE_URL` 后单独补跑数据库迁移套件 9/9、无跳过。未修改的 Swift/客户端组件沿用前述 CI 结果，不声称本轮本地重跑。

## I0/I2 实际配置保留补验

`b0bb528e` 的 11 项 GitHub 检查全部通过，草稿未合并。随后回到真实升级链路补配置证据：原 `synthetic-config.yaml` 只能证明文件恢复，不能证明 Hermes 会读取升级后的实际配置。本轮在全新合成 HOME 中通过旧版配置保存函数设置模型/provider、最大轮数、终端超时及 reasoning 显示项，随机生成带 synthetic 前缀的虚拟 provider 凭据。检查使用两端实际 `load_config_readonly` 和 dotenv 读取；新版通过官方 `--run-module unittest` 启动器取得 PM 运行环境，不假定存在新版 venv 路径。没有实际模型请求或日常配置/凭据参与。

实施前测试影响评审覆盖上述任一值丢失/变化时拒绝、同一配置在升级和失败恢复后重新读取、脚本身份固定及报告白名单。14 项 Python 工具单测、10 项 worker/lab 定向测试通过；脚本基线 451 通过、2 项既有 LibreSSL CMS 失败、无跳过。配置字段只作为 `seeded-default-profile-and-dotenv` 范围的观察，不能据此把多 profile、环境变量引用等全部配置场景标为通过，configuration 门禁仍保留未验收。

为给新演练留空间，核验旧中间实验 `hermes-compat-lab-i6ubtx9r` 为已结束的固定套件 `f79df50e…` 且无相关进程后，只清理其 user/candidate/snapshot/tmp 合成运行目录，保留原报告、路由清单和私有日志。最终历史证据实验 `jo28st1h` 与 `um46lsn5` 未清理。

固定套件 `b89bccdb4f18d97120f8817a104b2ad9effbd4e2bc4d11dfcddddfa6d184cf89` 的两次真实实验已结束：

- [正常升级/恢复与配置检查](evidence/hermes-compatibility/2026-10-25-runtime-configuration.json) 为 `runtime_stage_passed`；旧版、新版、恢复后旧版的实际配置读取均通过。原历史可读，恢复树摘要与备份相等。运行目录 `hermes-compat-lab-nnkfnvca`，耗时 1205 秒。
- [依赖落盘后失败与配置恢复](evidence/hermes-compatibility/2026-10-25-dependency-configuration.json) 如期记录 `injected_failure_after_target_dependencies`，整体不会标为升级成功。旧版配置、虚拟凭据及历史恢复检查通过，恢复树摘要与备份相等。候选在故障点后未启动，不能声称候选配置检查通过。运行目录 `hermes-compat-lab-ntjmmgcq`，耗时 866 秒。
- 正常实验的新环境再执行三阶段接口探针，33 项全部通过；输出与已有 `2026-10-25-live-contracts.json` 字节一致，报告 SHA-256 为 `ed01b9436cdb7ccc7abee565233cbffddd22a9bbbe0a9643e755a4513f9e7d2b`，因此未重复存同一份 JSON。

两份配置报告只保存阶段结果，不含虚拟凭据值、配置正文或个人目录；复制前与三份合成环境中的凭据值比对，确认未包含。该轮加强了已测默认 profile 的真实证据，仍没有解决并发锁、其他配置形态、I4 执行器或 I5 端到端；既有五项通过/并发失败/九项未验收的认证状态未因此扩大。

两份最终真实配置报告绑定后的 worker/lab 定向测试 11/11 通过；`git diff --check` 通过。此轮没有 Gateway/Connector/协议/客户端产品代码变更，未重复跑未受影响的本地 Swift/Android 构建。

## I3 安装身份与包身份的对应

`4334d74f` 的 11 项 GitHub 检查全部通过，PR 保持草稿，未合并或部署。

本轮检查发现：组件 receipt 的 contentSHA256 是解包后的内容身份，兼容记录的 artifactSha256 是原始归档身份，两者不能互换；Desktop app 更新器也没有可验证的已安装 DMG 身份记录。只凭版本号拼出五组件组合会产生错误匹配。

实施前测试影响评审针对内容/归档摘要混淆、同版本文件被修改、只剩 receipt、健康检查失败和普通展示对象重建。现有签名组件预检增加只读 installedComponents 观察：只有验签清单映射、磁盘完整重算和健康检查均通过的托管组件才返回两种摘要。它接在既有预检返回路径，未新增独立后台扫描器或改写安装记录。定向 14 项通过；第一次沙箱执行因 Swift 缓存不可写而未启动测试，获准正常编译执行后通过。

该观察仍不是正在运行的 Connector 身份。当前 bundle 布局、Desktop DMG 到已安装 app 的可信映射、其他客户端与 Gateway 的实际身份、普通推荐页接线仍待实现；不能据此标记 I3 完成或开放升级。没有新增用户可见错误或视觉变化，现有预检错误映射不变。

本轮 Desktop 基线：683 项 Swift 测试、资产一致性和本地 ad-hoc app 构建全部通过；`git diff --check` 通过。没有安装或发布构建产物，没有 UI 变化，因此没有新增原生视觉验收声明。Gateway/Connector/Android 产品源码未变，未重复其本地基线。

## I3 旧布局 Connector 身份补验

`6da23374` 的 11 项 GitHub 检查全部通过，PR 仍为草稿。本轮继续为 schema-1 旧布局补身份采集：签名清单绑定原始归档；归档复制到私有工作目录后再次验摘要并复用安全解包器；完整比对当前所选 Connector 树，前后核对 current 指向。返回值明确是所选安装文件的观察，不是运行进程或升级许可。未改写 receipt、安装目录或服务配置，未强制迁移用户布局。

实施前测试影响评审覆盖安装文件/归档被改、复制期间归档变化、签名与 JSON 歧义、检查期间切换发布、工作目录边界及清理失败；错误沿用 HR-HERMESCOMPAT-008，技术详情仅保留固定阶段及闭集枚举，不传递系统错误中的个人路径。10 项定向测试通过。初始编译修正了私有 Base64 helper 的调用，初始错误文案断言修正为分别检查 summary/detail，没有改变现有文案。

实机只读观察发现当前 selected release 已是 0.4.12，不能继续沿用旧记录的 0.3.4。从安装 app 的公开索引/公钥出发核验 0.4.12 清单和 37,087,174 字节 Connector 包，选中组件版本为 0.1.17。探针使用本轮实际 Core 代码，OS sandbox 禁止网络及自有临时目录外写入；独立的外部空目录写入尝试确实被拒绝。没有运行归档内程序、模型请求或 launchd 操作。

第一次实验的 sandbox 布尔条件误拒绝了自有临时写入，修正后保留目录外拒绝。随后实机发现已有工作目录的 URL directory hint 导致临时参考包被判为 outsideManagedRoot；新增回归在修复前失败，给新 run URL 明确 isDirectory 后通过。最终 [实机身份报告](evidence/hermes-compatibility/2026-10-25-installed-bundled-connector.json) 为 passed，安装树内容与签名包一致；报告绑定 inspector 源文件摘要，保留 runningProcessVerified=false 和 compatibilityApproved=false。临时探针、公开下载与诊断位于 `/private/tmp/hermes-bundled-identity-real-rxej62h7`，不含账号凭据。

仍需普通页面的原始清单/包发现接线、正在运行的组件身份、Desktop/Gateway/客户端身份及完整升级执行。已请求用户单独授权向上游提交已准备的锁缺陷报告；未收到授权前没有发送消息或创建上游 issue。本轮未合并、部署或升级日常 Hermes。

最终 Desktop 基线为 693 项 Swift 测试、资产一致性与本地 ad-hoc app 构建通过；10 项定向测试通过，`git diff --check` 通过。新增证据只涉及当前选中组件文件；没有重复未改动 Gateway/Connector/Android 的本地套件，没有 UI 变化或新增视觉验收声明。
