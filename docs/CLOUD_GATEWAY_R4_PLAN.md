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
/var/lib/hermes-go/ops/operations.jsonl
```

候选槽位使用另一个 loopback 端口、systemd unit 和容器名。候选验证不经过当前公开 upstream；
切换前必须验证 image identity、liveness、readiness、版本、认证、REST、WebSocket 和测试 Connector。
Nginx 新配置先写临时文件并通过 `nginx -t`，再原子替换 include 并 reload。

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
- `route_switched`：原子切换后，新连接必须落到候选版本；旧连接仍可在旧槽位短暂存活。
- `draining`：等待在途请求结束，随后停止旧槽位并确认 Connector 通过公开入口重连到新槽位。
- `committed`：最后才更新 `previous`、`current`、部署 journal 和成功审计。

每个阶段都以 `0600` journal 持久化。相同 run/目标可安全继续；不同目标、未知锁所有者、损坏
journal 或路由实际状态与记录不一致时必须停止并返回结构化 `HR-OPS-*` 错误。

## 自动恢复和 rollback

| 失败位置 | 自动动作 | 允许结果 |
| --- | --- | --- |
| 切换公开路由之前 | 停止并清理候选，旧服务不变 | current 继续服务 |
| Nginx 配置校验或 reload | 恢复旧 include，复核 Nginx 与旧健康 | 旧路由已确认恢复 |
| 切换后观察或 Connector 重连 | 切回旧 upstream，确认旧服务，停止候选 | 旧路由与旧程序恢复 |
| 旧槽位停止过程中 | 先恢复/重启旧槽位，再切回旧 upstream | 不接受“只切路由但旧服务未就绪” |
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

## 实施切片

| 切片 | 内容 | 退出条件 |
| --- | --- | --- |
| R4-A 合同 | manifest/config v2、兼容判定、CLI 参数、旧 R3 读取兼容 | 严格 parser 与版本矩阵测试通过 |
| R4-B 双槽位 | unit/upstream 模板、候选启动与私有 smoke、部署 journal/锁 | 切换前失败均保持旧服务 |
| R4-C 切换排空 | 原子 Nginx 切换、观察窗、在途排空、Connector 重连验证 | 切换后失败可恢复旧路由与旧程序 |
| R4-D rollback | previous 选择、数据库兼容门禁、反向双槽位流程 | 成功回滚；不兼容回滚 fail closed |
| R4-E staging 演练 | 一次性 Ubuntu + PostgreSQL 18，从 R3 基线升级到 R4 再回滚 | 每个注入点和完整往返均通过 |

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
