# Cloud Gateway R4 安全升级与回滚计划

R4 对应 Cloud C3，在 R3 已验证的安装基线上增加 `deploy`、`rollback`、迁移锁、双槽位切换、
有界排空和故障恢复。R4 开发与验证仍然只允许 staging；本文不授权香港生产服务器部署、重启、
端口切换或数据库迁移。

## 不变边界

- Android、Desktop 和 Connector 继续使用现有 HTTPS/WSS URL 与协议；R4 不要求发布新客户端。
- Mac 继续主动建立出站连接；Hermes 本地凭证不得进入 Gateway、制品、诊断或 CI。
- 目标必须是 R2/R3 同一套不可变 OCI bundle，主机不执行 `git pull` 或临时源码构建。
- 新版本就绪前不改变公开路由；切换失败或观察失败必须恢复旧路由和旧程序。
- 数据库迁移只显式执行一次并持有互斥锁。故障恢复不得盲目执行破坏性 down migration。
- `current`、`previous` 和审计记录只在对应阶段原子更新；不能出现两个“已提交”版本。
- R4 不实现 off-host 备份/恢复、长期指标告警或生产制品签名；这些仍属于后续阶段或生产门禁。

## 目标拓扑

每个受管环境保留两个私有 Gateway 槽位。Nginx 公开入口只引用一个受管 upstream 文件：

```text
public HTTPS/WSS -> Nginx -> active upstream -> blue or green Gateway
                                  |
                                  +-> atomic include file

/opt/hermes-go/releases/<version-commit>/
/opt/hermes-go/current  -> releases/<active>
/opt/hermes-go/previous -> releases/<rollback>
/var/lib/hermes-go/ops/deploy-state.json
/var/lib/hermes-go/ops/switch-checkpoint.<plan-digest>.json
/var/lib/hermes-go/ops/lifecycle-handoff.<plan-digest>.json
/var/lib/hermes-go/ops/operations.jsonl
```

候选槽位使用另一个 loopback 端口、systemd unit 和容器名。候选验证不经过当前公开 upstream；
切换前必须验证 image identity、liveness、readiness、版本、认证、REST、WebSocket 和测试 Connector。
Nginx 新配置以临时文件加原子 rename 安装；运行中的旧路由不受文件变化影响，只有 `nginx -t`
通过后才允许 reload。现有 lifecycle store 是
单写者整文件快照，不能由两个 Gateway 并发写入；因此当前 release contract 明确声明
`maintenanceRequired: true`，切换会产生一个短暂维护窗口，Android/Desktop/Connector 按现有重连
机制恢复，不改变公开 URL、Token 或协议。

## 部署状态机

```text
authorized
  -> artifact_verified
  -> lock_acquired
  -> checkpoint_created
  -> migration_verified
  -> candidate_started
  -> candidate_verified
  -> route_switched
  -> draining
  -> committed
```

- `authorized`：要求 staging 环境、明确确认值、root、目标不是 current，并校验源/目标兼容关系。
- `checkpoint_created`：记录旧 upstream、旧 image、配置摘要、数据库 schema 和 current/previous 指向；
  这里只是本机发布检查点，不宣称达到 C5 的备份能力。
- `migration_verified`：在 PostgreSQL advisory lock 下显式检查/执行允许的向前兼容迁移；账号功能关闭
  时仍检查目标 release 声明，不伪造数据库成功。
- `candidate_verified`：候选必须通过私有端口全链路 smoke，不借用旧进程的健康结果。
- `route_switched`：先停止候选与旧 writer，严格校验并复制最终 lifecycle snapshot，持久化交接标记，
  再重启候选、原子切换 Nginx；切换后新连接必须落到候选版本。
- `draining`：保留阶段名以兼容既定 journal 顺序；当前单写者实现中旧服务已在交接前停止，本阶段实际
  执行公开全链路 smoke、观察窗和 Connector 重连复核，不宣称旧连接仍在后台排空。
- `committed`：最后才更新 `previous`、`current`、部署 journal 和成功审计。

每个阶段都以 `0600` journal 持久化。相同 run/目标可安全继续；不同目标、未知锁所有者、损坏
journal 或路由实际状态与记录不一致时必须停止并返回结构化 `HR-OPS-*` 错误。

## 自动恢复和 rollback

| 失败位置 | 自动动作 | 允许结果 |
| --- | --- | --- |
| 切换公开路由之前 | 停止并清理候选，旧服务不变 | current 继续服务 |
| 最终状态交接前中断 | 停止候选，重启旧 writer，复核旧公开路径 | 旧 snapshot 保持权威 |
| Nginx 配置校验或 reload | 停止候选，将已交接的新状态反向复制，重启旧服务并逐字恢复旧 Nginx 文件 | 旧路由、旧程序与最新状态恢复 |
| 切换后观察或 Connector 重连 | 停止候选，反向交接最新 snapshot，重启旧服务并切回旧 upstream | 旧路由与旧程序恢复，观察期事件不丢失 |
| 操作进程在 `route_switched` 后中断 | 依据严格 journal、检查点和交接标记继续公开验证与提交 | 不重复状态交接，不并发启动旧 writer |
| 自动恢复本身失败 | 停止重试循环，保留 journal 和脱敏诊断 | 明确标记需人工恢复 |

`rollback` 只能选择已记录的 `previous`，并再次校验 bundle、配置和数据库兼容性。它复用相同的
候选验证、路由切换、排空和审计状态机，不通过修改 symlink 冒充回滚。若数据库 schema 不再被
旧程序支持，rollback 必须拒绝并保留当前健康版本。

## 合同变化

1. bundle manifest 新版本增加配置 schema、数据库 schema、协议版本、最低客户端版本、
   `minimumSourceVersion`、`maintenanceRequired` 和 `rollbackSupported`；R3 manifest 继续只读兼容。
2. Cloud Ops 配置新版本增加 blue/green unit、容器、私有端口、受管 upstream include、排空与观察
   时限，以及可选的 PostgreSQL Secret 文件引用；production 值仍不被 R4 staging CLI 接受。
3. CLI 增加：

   ```text
   hermesctl deploy --config <file> --confirm staging
   hermesctl rollback --config <file> --confirm staging
   ```

4. 新失败先在 `docs/ERROR_HANDLING.md` 分配不可变错误码，再用于 CLI；所有技术原因必须脱敏，
   并声明能否重试和建议恢复动作。

### R4-A 已确定格式

- bundle manifest schema 2 在不可变制品身份字段之外嵌入完整 `releaseContract`；打包器和读取器都
  严格拒绝缺失、额外或类型错误字段。历史 schema 1 只保留读取兼容，不能作为新 deploy 目标。
- `ops/hermesctl-deploy-config.schema.json` 是双槽位配置合同，示例为
  `ops/staging.deploy.example.json`。它只接受 staging、互不重复的 blue/green unit、容器和端口、
  明确的 R3 legacy source unit/container/loopback port/state directory、独立的 Nginx upstream include、1–600 秒
  排空窗、1–300 秒观察窗，以及可选数据库 URL Secret 文件与 PostgreSQL advisory lock ID。
- `assessReleaseTransition` 在任何服务或路由操作之前判定源/目标版本方向、最低源版本、协议连续性、
  数据库 schema 倒退和 rollback policy。失败统一为不可重试的 `HR-OPS-006`。
- CLI 的 `deploy`/`rollback` 入口只在 R4-B 状态机具备“切换前失败不影响旧服务”保证后开放；
  R4-A 不暴露一个只有参数、没有安全执行语义的半成品命令。

### R4-B 候选边界

- blue/green 使用不同 unit、容器、loopback 端口、slot 环境文件和本地状态目录；候选不能与当前
  Gateway 并发写同一个 lifecycle snapshot。
- 部署 journal 只允许按既定状态顺序前进，`0600` 写入；部署锁包含 run ID 与随机 ownership
  nonce，旧进程不能删除后继锁，未知主机持有者必须 fail closed。
- 候选准备会复核 current symlink、制品哈希、image identity、私有 liveness/readiness/version，
  并强制调用完整 Connector/REST/WebSocket smoke。完整 smoke 未提供或失败时，不得写入
  `candidate_verified`。
- R4-B 不写 Nginx 配置/upstream，不改 `current`/`previous`，候选 unit 也不启用开机启动。任何
  切换前失败只停止候选容器，当前服务与公开路由保持原样；相同计划可从 journal 安全重试。
- 本地 lifecycle snapshot 的最终交接属于 R4-C 的切换门禁：必须先让旧 writer 静止，再复制并
  校验最终状态，或改用支持并发的一致性存储。完成“切换期间事件不丢失”的故障注入测试之前，
  CLI 不开放公开路由切换。

### R4-C 切换边界

- 切换前再次校验 bundle/image、计划摘要、current symlink、源服务、候选服务和私有全链路 smoke；
  Nginx 原文件以内容、哈希和存在性写入 `0600` 持久检查点，任何摘要漂移都 fail closed。
- 只有在部署锁与 journal 身份验证通过后，切换器才拥有候选清理权。此后若在停止旧服务前发生任何
  失败，必须停止候选 unit、确认候选不活动并尽力移除其容器；竞争锁或已提交版本的复核失败绝不能
  停止另一个部署或当前活动服务。
- 两个 writer 均确认停止后，lifecycle snapshot 通过 no-follow 文件句柄读取，严格验证字段、协议事件、
  唯一 event ID/sequence、游标、权限和 16 MiB 上限，再以 `0600` 原子写入候选槽位。空源会显式覆盖
  候选 smoke 数据，不能误把测试事件带入正式状态。
- 只有状态交接标记落盘后才重启候选；随后原子安装 upstream/主配置，执行 `nginx -t` 和 reload，
  两次公开全链路 smoke 中间必须走完整观察窗，最后才 enable/disable unit 并更新 symlink。
- 交接后任一步失败都会停止候选、把候选最新 snapshot 反向交给旧目录、重启旧服务、恢复 Nginx 与
  release links，并通过公开 smoke 确认；成功恢复的 journal 会归档，自动恢复失败则保留现场并停止循环。
- `switchCandidate` 仍是内部执行边界。R4-D rollback 共用安全切换语义并完成前，不向操作者开放
  `deploy`/`rollback` CLI，也不允许据此触碰香港生产服务器。

### R4-D 回滚边界

- 候选准备与流量切换接受显式 `deploy`/`rollback` 操作，默认仍为 deploy；rollback 的兼容策略由
  当前 schema 2 release contract 治理，因此即使目标是历史 schema 1，仍必须执行当前版本声明的
  维护窗口、rollback policy 和数据库门禁。
- rollback 目标必须与受管 `previous` symlink 的完整版本与 12 位提交身份一致；任意更老版本、手工
  指定版本或伪造 symlink 都在停止服务前以 `HR-OPS-006` 拒绝。
- 下一次操作只能在上一份 journal 已到 `committed`、其目标身份等于当前 source、活动槽位一致时开始。
  原 journal 以 `0600` 归档到 history；既有同名归档内容不同会 fail closed，不能覆盖审计证据。
- rollback 候选使用当前活动槽位的另一个 blue/green 槽位，复用 R4-C 的私有 smoke、最终 snapshot
  交接、Nginx 检查点、公开观察和自动恢复。成功后 `current` 指向回滚版本，`previous` 指向回滚前版本。
- R4-D 仍只提供内部执行边界；CLI 和一次性 staging 往返在 R4-E 接线与验证完成前保持关闭。

### R4-E 集成与演练边界

- Gateway Server 集成版本统一递增为 `0.3.0`；最低可升级与唯一 legacy rollback 基线保持为
  `0.2.0`，不改变 Android、Desktop、Connector 的最低版本或公开协议。
- `hermesctl deploy/rollback --config <file> --confirm staging` 只接受 R4 staging 配置、root、Linux
  x86_64 和完整 smoke 环境。命令从受管 `current` manifest 读取源身份；R4 源必须有匹配的 committed
  journal 才能推导活动槽位，不能用参数猜测或手工选择槽位。
- 每次私有候选校验由 CLI 临时启动独立测试 Connector，验证私有端口的身份、REST、WebSocket 和
  Connector 后立即停止；公开切换后再等待原公开 Connector 重连并复核 HTTPS/WSS，两个结果不能
  相互替代。测试 Connector 的入口与 mock Hermes 环境仅由一次性 staging runner 提供，缺失时在
  任何服务变更前以 `HR-OPS-001` 关闭失败。
- 手动 workflow 固定从提交 `e94d89dea9b4f416942a78e3120d14bb94500e5c` 构建真实 R3/schema 1
  `0.2.0` bundle，并从待测 clean commit 构建 R4/schema 2 `0.3.0` bundle；完整执行 bootstrap、
  deploy、公开 smoke、rollback、公开 smoke、release links、systemd 状态、journal、审计与脱敏检查。
- workflow 运行于一次性 Ubuntu 24.04 runner，并启动固定 digest 的 PostgreSQL 18 服务验证目标
  运行环境。由于本轮账号能力保持关闭，deploy config 明确为 `database: null`，数据库迁移门禁仍按
  release contract 判定为不需要；不得把这一项描述为生产数据库迁移演练。
- workflow 仍为 `workflow_dispatch`，没有 push/PR/schedule 触发器、仓库 Secret、镜像推送、SSH
  或生产域名。代码合并和生产部署都不会自动触发它。

### R4-F 数据库启用边界

- Gateway Server 递增为 `0.4.0`，release manifest version 2 表示制品内含可由 Cloud Ops 调用的受控
  迁移器。数据库模式只允许目标制品使用 version 2；向旧 version 1 制品执行数据库模式 rollback 会在
  候选启动和公开切流前以 `HR-OPS-006` 拒绝。
- 数据库 URL 只从权限受限的外部 Secret 文件读取，校验后复制到不向 Gateway 服务挂载的受管
  `database-secrets` 目录。迁移容器通过只读 bind mount 读取它；连接串不进入命令参数、journal、审计、
  诊断或错误正文。
- 迁移器使用目标不可变 OCI image，校验 PostgreSQL 主版本，取得 session-level advisory lock，按连续
  编号只执行尚未应用的迁移，再精确验证 `gateway_schema_state`。锁竞争、连接/SQL/版本/结果异常统一
  返回 `HR-OPS-009`；容器只输出不含凭证的结构化成功标记。
- 候选启动前执行迁移，切流前在新的部署锁内再次取得数据库锁并复核 exact schema。第二次复核失败时
  清理候选并保持旧服务、旧 Nginx 和 release links 不变。
- 当前发布合同只声明一个 exact schema，无法证明旧程序兼容更高 schema。因此数据库模式暂时只允许
  源/目标 `databaseSchemaVersion` 相等；跨 schema 发布在引入明确兼容区间前 fail closed。
- 本切片只准备 schema，`ACCOUNT_AUTH_ENABLED` 和 `ACCOUNT_BINDING_ENABLED` 继续固定为 `0`。因此可以
  在回退到 `0.3.0` 时把数据库配置改回 `null` 并保留未使用的 schema；一旦账号数据成为权威状态，禁止
  采用该回退方式，必须只回滚到具备数据库迁移合同且兼容同一 schema 的版本。

## 实施切片

| 切片 | 内容 | 退出条件 |
| --- | --- | --- |
| R4-A 合同 | manifest/config v2、兼容判定、CLI 参数、旧 R3 读取兼容 | 严格 parser 与版本矩阵测试通过 |
| R4-B 双槽位 | unit/upstream 模板、候选启动与私有 smoke、部署 journal/锁 | 切换前失败均保持旧服务 |
| R4-C 切换排空 | 原子 Nginx 切换、观察窗、在途排空、Connector 重连验证 | 切换后失败可恢复旧路由与旧程序 |
| R4-D rollback | previous 选择、数据库兼容门禁、反向双槽位流程 | 成功回滚；不兼容回滚 fail closed |
| R4-E staging 演练 | 一次性 Ubuntu + PostgreSQL 18，从 R3 基线升级到 R4 再回滚 | 每个注入点和完整往返均通过 |
| R4-F 数据库启用 | advisory lock、目标镜像迁移、exact schema 复核、数据库回退门禁 | 真实 PostgreSQL 迁移/重入/锁竞争及应用回退演练通过 |

Server 版本只由集成步骤在 R4 功能合并、准备打包两个真实版本时统一递增，避免并行切片重复改版。

## 测试影响与门禁

- R3 的 preflight/bootstrap/status/doctor 测试必须全部保留，旧 manifest/config 的读取不能回归。
- 纯单元测试覆盖所有合法状态迁移、重复执行、锁竞争、损坏 journal、symlink/权限与路径攻击。
- 模板测试覆盖两个槽位的隔离、内容寻址 image、loopback 端口和 Nginx include 原子性。
- PostgreSQL 18 集成测试覆盖 migration lock、并发部署、迁移中断和旧版本兼容拒绝。
- 故障注入必须覆盖每个阶段；测试不仅检查命令报错，还要检查公开路由、旧进程和 symlink 最终状态。
- 一次性 staging 使用两个真实 commit 的 bundle，完成 R3 基线安装、R4 deploy、REST/WSS/Connector
  smoke、R4 rollback、再次 smoke、status/doctor/audit 脱敏检查。
- 基线门禁为 `npm run build`、`npm test`、网络 Gateway 测试、`git diff --check`、PR CI/SAST 和
  手动触发的 ephemeral upgrade/rollback workflow。

由于当前仓库是 GitHub Free 私有仓库，GitHub 不提供私有仓库 branch protection/ruleset 强制；
在套餐不变的情况下，由 `AGENTS.md` 的人工门禁要求所有 PR 检查完成且成功后才能合并。不得通过
公开仓库来换取免费保护，也不需要为了 R4 立即购买套餐。

## R4 完成定义

只有以下条件全部满足才能把 R4 标为完成：

1. R4-A 至 R4-D 代码与回归测试合并；
2. 每个故障注入点均恢复到一个已验证的服务状态；
3. 一次性 Ubuntu/PostgreSQL staging 完成真实升级与回滚往返；
4. Android、Desktop、Connector 与 Legacy 路径 CI 无回归；
5. 验证记录包含目标版本、源提交、run、阶段、恢复结果和未执行项；
6. 香港生产服务器保持未变，任何正式发布仍需项目所有者单独明确授权。

## 完成状态

2026-09-03，当前 `database: null` 的 Gateway `0.3.0` 发布路径已满足上述六项条件：R4-A 至 R4-E
和 14 点故障注入矩阵均合并，PR 与 main 回归无失败，一次性 Ubuntu 24.04/PostgreSQL 18 runner
完成真实 R3 `0.2.0` → R4 `0.3.0` → R3 `0.2.0` 往返。详细 commit、run、恢复结果和未执行项见
`CLOUD_GATEWAY_R4_TEST_RECORD.md`。

该完成状态不授权香港生产部署。R4-F 已开始实现 PostgreSQL schema 准备能力，但在一次性数据库
staging、PR/main 门禁和生产前置检查全部通过前，不能把数据库发布路径标记为 Complete。账号能力仍
保持关闭；真实账号启用还需要 Google OAuth 配置、备份/恢复演练和独立 go/no-go。
