# Cloud Gateway R4 安全升级验证记录

R4 当前 `database: null` 发布路径的代码、回归、故障注入和一次性 staging 门禁均已完成。本文只记录
实际执行的结果；生产部署和未执行的数据库迁移不得写成通过。

## 测试影响

| Boundary | Planned evidence |
| --- | --- |
| R3 compatibility | 旧 manifest/config、preflight/bootstrap/status/doctor 全部无回归 |
| Release compatibility | 源/目标 Server、配置、数据库、协议和最低客户端矩阵严格判定 |
| Deploy lock/journal | 并发互斥、同目标恢复、异目标拒绝、损坏状态 fail closed |
| Candidate isolation | 双 unit/container/port、image identity、私有 REST/WSS/Connector smoke |
| Atomic routing | Nginx 临时配置校验、原子 include、reload 后版本确认 |
| Drain/reconnect | 在途请求有界完成，旧槽位停止，Connector 公开入口重连到新槽位 |
| Automatic recovery | 每个部署阶段注入失败后恢复旧路由与旧程序 |
| Rollback | previous 严格选择、旧版本验证、数据库兼容拒绝、完整反向切换 |
| Audit/errors | 操作阶段与结果完整；稳定双语错误码；Secret/路径/正文脱敏 |
| Ephemeral staging | R3 基线 → R4 升级 → smoke → R3 回滚 → smoke |

## 当前状态

| Gate | Result | Evidence |
| --- | --- | --- |
| R4 plan and boundaries | Complete | `CLOUD_GATEWAY_R4_PLAN.md` 已定义双槽位、状态机、恢复矩阵、实施切片与退出条件 |
| R4-A contract | Complete | PR #9 六项 checks 全部通过，合并提交 `c2f0600` 的 main CI、SAST、Gateway OCI 复核均通过 |
| R4-B candidate path | Complete | PR #10 六项 checks 全部通过，合并提交 `9c3c54a` 的 main CI、SAST、Gateway OCI 复核均通过 |
| R4-C switch/drain | Complete | PR #11 六项 checks 全部通过并合并为 `519a9ab`；合并后 main CI、SAST、Gateway OCI 复核全部通过 |
| R4-D rollback | Complete | PR #12 六项 checks 全部通过并合并为 `ed30206`；合并后 main CI、SAST、Gateway OCI 复核全部通过 |
| R4-E CLI/integration | Complete | PR #13 六项 checks 全部通过并合并为 `f78b234`；合并后 main CI、SAST、Gateway OCI 复核全部通过；手动演练也已通过 |
| Fault-injection suite | Complete | 14 点显式矩阵经 PR #15 六项 checks、合并及 main CI/SAST/Gateway OCI 复核全部通过 |
| Ephemeral upgrade/rollback | Complete | GitHub Actions run `33750219977` 在提交 `ae9855d` 完成真实 R3→R4→R3 往返，未访问香港服务器 |
| R4 completion | Complete | 当前 `database: null` 发布路径满足本文六项完成定义；生产部署仍是独立授权 |
| Production deployment | Not authorized | R4 计划与代码工作不构成生产授权 |

## 仓库门禁说明

2026-09-03 实测当前 GitHub Free 私有仓库无法启用 branch protection，GitHub API 返回需升级 Pro
或把仓库公开。项目保持私有且不购买套餐；合并门禁暂由 `AGENTS.md` 要求集成代理在合并前逐项确认
PR checks，并在合并后复核 `main`。这属于流程约束，不应被描述为平台强制保护。

## R4-A 本地证据

2026-09-03 在 `codex/cloud-gateway-r4-contract` 执行：

- `npm run build`：通过。
- `npm test`：通过；Gateway 常规套件 53 项中 42 项通过、11 项按环境门禁跳过；脚本套件 30 项
  全部通过，其中包含 R4 manifest/config 严格解析、旧 schema 1 兼容、版本矩阵和错误码测试。
- `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway`：在允许 loopback listener 的本机权限下
  通过；53 项中 50 项通过，仅 3 项需要一次性 PostgreSQL 的测试按既有门禁跳过。
- `git diff --check`：通过。

本切片未修改 Android、Desktop、Connector 协议或公开路由，未生成 APK，未运行 PostgreSQL
迁移，也未访问香港服务器。R4-A 只有在 PR checks 与合并后 `main` 复核均通过后才记为 Complete。

## R4-A CI 证据

- PR #9：Android、Desktop、Node、Secret、Semgrep、Gateway OCI 共六项检查全部通过后人工合并。
- main `c2f0600`：CI run `33739918210`、SAST run `33739918212`、Gateway OCI run
  `33739918405` 全部成功。

## R4-B 当前本地证据

聚焦测试已覆盖：blue/green unit/container/port/state 隔离、Nginx 主配置只引用受管 upstream、合法
状态顺序与越级拒绝、journal 冲突/篡改、并发与过期锁、锁 ownership fencing、候选成功准备、完整
smoke 失败后只停止候选、相同计划恢复，以及竞争锁存在时绝不停止其他运行中的候选。公开 Nginx
文件和 `current` symlink 在这些测试中保持逐字节不变。

2026-09-03 执行 `npm run build` 与 `npm test` 均通过；脚本套件增至 36 项并全部通过。Gateway
常规套件仍为 53 项（42 通过、11 项按网络/PostgreSQL 环境门禁跳过），既有 Protocol、Connector
和 release-server 套件无回归。PR #10 的 Android、Desktop、Node、Secret、Semgrep、Gateway OCI
六项检查全部通过；main `9c3c54a` 的 CI run `33742157027`、SAST run `33742156994`、Gateway OCI
run `33742157459` 也全部成功。真实 systemd/Docker/Nginx 候选验证仍属于后续一次性 staging，
不能据此部署香港服务器。

## R4-C 当前本地证据

聚焦测试已覆盖：最终 lifecycle snapshot 正常交接、空源覆盖候选 smoke 状态、损坏/重复/symlink
输入 fail closed、反向交接保留观察期新事件、成功切换并提交 symlink、公开 smoke 失败后恢复旧程序/
旧路由/最新状态、交接前操作进程中断恢复、Nginx 校验失败逐字恢复，以及 `route_switched` journal
在操作进程中断后的继续执行。双重中断测试还验证了反向交接一旦标记为 `restored`，下一次恢复不会
用候选旧快照覆盖重启后产生的新事件。新增失败统一映射为双语 `HR-OPS-008` 并复用既有脱敏边界。

当前实现按 release contract 明确要求 `maintenanceRequired: true`：旧 writer 停止后才复制最终快照，
因此会有短暂断连，但公开 URL、Token 和客户端协议不变。`switchCandidate` 尚未接入公开 CLI；完整
基线、PR CI、R4-D rollback 和一次性 staging 往返完成前，不构成部署授权或生产可用声明。

2026-09-03 执行 `npm run build`、`npm test` 与 `git diff --check` 均通过；脚本套件增至 46 项并
全部通过。Gateway 常规套件仍为 53 项（42 通过、11 项按网络/PostgreSQL 环境门禁跳过），Protocol
13 项、Connector 13 项和 release-server 30 项全部通过。随后启用 `RUN_NETWORK_TESTS=1` 复核
Gateway：初次在受限 sandbox 内因 `listen EPERM` 无法绑定 loopback；获准仅开放本机临时端口后
重跑为 50 项通过、3 项仅因未配置一次性 PostgreSQL 而跳过。未连接香港服务器或任何生产服务。

PR #11 的 Android、Desktop、Node、Secret、Semgrep、Gateway OCI 六项检查全部通过后合并；合并
提交为 `519a9ab`。main 的 CI run `33744825657`、SAST run `33744825420` 与 Gateway OCI run
`33744825444` 全部通过；其中 Android 完整复核耗时 8 分 16 秒并成功。

## R4-D 当前本地证据

聚焦往返测试覆盖 R3/schema 1 → R4/schema 2 deploy，再从 blue → green rollback 回到唯一 `previous`
版本。它验证：rollback 维护策略由当前 R4 contract 治理、错误 previous 在任何服务操作前拒绝、上一份
committed journal 内容一致后归档、目标旧 OCI identity 重新校验、lifecycle snapshot 反向槽位交接、
公开观察通过后原子交换 `current/previous`，以及旧 blue 停止、新 green 活动。CLI 仍未开放，未执行
真实 systemd/Docker/Nginx 或服务器测试。

2026-09-03 执行 `npm run build`、`npm test` 与 `git diff --check` 均通过；脚本套件增至 47 项并
全部通过。Protocol 13 项、Connector 13 项、release-server 30 项全部通过，Gateway 常规套件为
42 项通过、11 项按既有网络/PostgreSQL 门禁跳过。本切片没有 Android/Desktop 源码或 APK 变化。

PR #12 的 Android、Desktop、Node、Secret、Semgrep、Gateway OCI 六项检查全部通过后合并；合并
提交为 `ed30206`。main 的 CI run `33746348230`、SAST run `33746348140` 与 Gateway OCI run
`33746348103` 全部成功。未连接香港服务器或任何生产服务。

## R4-E 实现与验证证据

R4-E 将 Gateway Server 版本统一递增为 `0.3.0`，并开放仍严格限定 staging 的 `hermesctl deploy`
与 `hermesctl rollback`。命令不接收活动槽位参数：首次升级从受管 R3 `current` manifest 得到源身份，
后续操作只接受与当前 release 完整匹配的 committed journal。未确认 staging、非 root/非 Linux x86_64、
缺失独立 smoke Connector 环境时都在创建部署状态或更改服务前关闭失败。

候选 smoke 临时启动独立 Connector，只连接 loopback 候选槽位并在验证 REST/WSS 后退出；公开 smoke
则等待原 Connector 经固定 HTTPS/WSS 入口重连，再核对目标版本和提交。CLI 为 deploy/rollback 各写
started/success 或 failed 脱敏审计记录。单元/静态测试已覆盖 R3 源解析、命令调用顺序、授权前零写入、
缺失 smoke 环境关闭失败，以及 workflow 必须包含固定 R3 commit、PostgreSQL 18、deploy、rollback、
双版本 smoke、release links、最终服务/journal 与八条审计记录检查。

一次性 workflow 已在项目所有者确认后手动触发。它不会访问香港服务器。PostgreSQL 18 在 runner
中用于核对目标环境版本，但本轮账号能力关闭且 deploy config 为 `database: null`，所以这不构成
真实数据库迁移通过的证据。

2026-09-03 本地执行 `npm run build`、`npm test` 与 `git diff --check` 全部通过：Protocol 13 项、
Connector 13 项、release-server 30 项、脚本 50 项全部通过；Gateway 常规套件 42 项通过、11 项按
环境门禁跳过。随后以 `RUN_NETWORK_TESTS=1` 重跑 Gateway，50 项通过，只有 3 项因未配置一次性
PostgreSQL 测试数据库而跳过。上述执行未启动 systemd/Docker/Nginx 演练，也未连接香港服务器。

PR #13 的 Android、Desktop、Node、Secret、Semgrep、Gateway OCI 六项检查全部通过后合并；合并
提交为 `f78b234`。合并后的 CI run `33748570949`、SAST run `33748571254` 与 Gateway OCI run
`33748570919` 全部成功。

2026-09-03 手动触发 `Gateway Ephemeral Staging` run `33750219977`，在最新 main 提交
`ae9855de123f2590de293d2242bcf02db67fab3f` 的 Ubuntu 24.04 x86_64 runner 上用 PostgreSQL 18.6
完成 1 分 58 秒的隔离演练。它从固定历史提交 `e94d89dea9b4f416942a78e3120d14bb94500e5c`
构建 R3/schema 1 `0.2.0`，从待测提交构建 R4/schema 2 `0.3.0`；R3 bootstrap 重入、status、doctor、
初始公开 REST/WSS/Connector smoke、R4 deploy、R4 公开 smoke、R3 rollback 和回滚后公开 smoke
全部通过。deploy 与 rollback journal 均提交，最终 green 槽位运行 R3、blue 与 legacy 停止，
`current/previous` 指向正确版本，八条 started/success 审计记录及脱敏检查通过。workflow 使用临时
证书和随机 Token，无仓库 Secret、SSH、镜像推送或生产域名；未连接或更改香港服务器。

本次成功关闭 R4-E CLI/integration 与完整往返门禁。运行当时仍缺显式故障注入矩阵；该缺口由下一节
记录的后续切片处理。真实数据库迁移仍未运行，因为当前 release contract 声明本轮不需要数据库且
配置为 `database: null`。

## R4 故障注入矩阵本地证据

2026-09-03 在 `codex/cloud-gateway-r4-fault-matrix` 增加 14 点显式矩阵：候选私有 smoke、候选停止、
源停止、lifecycle 交接、候选重启、重启后身份复核、Nginx 校验、Nginx reload、第一次公开 smoke、
观察窗、观察后身份复核、第二次公开 smoke、候选 enable 和源 disable。每个注入点都要求返回稳定的
`HR-OPS-008`，并同时验证旧服务活动、候选不活动、开机启动状态恢复、Nginx 原文和 upstream 恢复、
`current/previous` 未误提交以及 lifecycle 事件未丢失。

矩阵暴露并修复了一个切换前恢复缺口：journal 身份已经验证、但旧服务尚未停止时，如果候选二次
私有 smoke 或停止动作失败，旧公开服务虽保持健康，候选 unit/容器此前不会被主动清理。现在只有在
持有正确部署锁并验证 journal 后才取得候选清理权；切换前失败会停止候选、确认 inactive 并尽力移除
容器，竞争锁和 committed 复核路径不会触碰活动服务。

聚焦 `deploy-state` 套件共 28 项全部通过。完整执行 `npm run build`、`npm test` 与
`git diff --check` 全部通过；Protocol 13 项、Connector 13 项、release-server 30 项、脚本 51 个
顶层测试（含子测试共 65 项）全部通过，Gateway 常规套件 42 项通过、11 项按既有环境门禁跳过。
随后启用 `RUN_NETWORK_TESTS=1` 复核 Gateway，50 项通过，3 项仅因未配置一次性 PostgreSQL 账号
测试库而跳过。本切片未修改 Android、Desktop、Connector 协议或 Gateway 公开 API，未生成 APK，
未连接香港服务器。

PR #15 的 Android、Desktop、Node、Secret、Semgrep、Gateway OCI 六项检查全部通过后合并；合并
提交为 `f4b09dc`。main 的 CI run `33753434445`、SAST run `33753434278` 与 Gateway OCI run
`33753434269` 全部成功。因此当前 `database: null` 发布路径的 R4 六项完成定义均已满足。

该状态不包含香港生产部署，也不宣称数据库迁移已经通过。当前 release contract 关闭账号能力且
部署配置为 `database: null`；任何未来启用 PostgreSQL 的 Gateway 发布必须另开实现与验证切片，
补齐 migration lock、并发/中断恢复和真实数据库兼容测试，不能沿用本轮 Complete 结论。
