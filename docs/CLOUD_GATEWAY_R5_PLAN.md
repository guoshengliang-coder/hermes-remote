# Cloud Gateway R5 生产晋级计划

R4 已证明不可变 Gateway 制品可以在一次性 staging 中完成候选启动、状态交接、切流、回滚和
PostgreSQL 迁移，但所有命令仍明确拒绝 production。R5 的目标不是直接解除这个开关，而是先把香港
主机现存的旧 Node/systemd 服务接入一个可验证、可恢复的生产基线，再复用已经通过演练的 R4 状态机。

当前只有一台香港服务器不是阻断条件：Gateway、Nginx 和 PostgreSQL 可以同机运行；数据库必须只
监听 loopback。但同机数据库不能把同一块磁盘上的文件称为备份，所以加密逻辑备份必须复制到 Mac
或另一处独立存储，并在独立的临时 PostgreSQL 中真实恢复验证。

## 当前生产差距

2026-09-03 的已授权只读检查确认：线上 Gateway 仍是 `/opt/hermes-remote` 下的旧 Node 服务，缺少
受管 `current`/`previous` 和制品 manifest；Docker 与 PostgreSQL 18 尚未安装；Gateway 的 8444 端口
仍绑定公网地址；尚无旧服务恢复制品，也无异机数据库恢复证据。现有 443 路由、Gateway、发布服务、
DERP 和证书均健康，后续操作必须保留这些服务和既有 Android/Connector 的 URL、Token 与协议。

## 切片与授权边界

| 切片 | 内容 | 退出条件 | 是否触碰生产 |
| --- | --- | --- | --- |
| R5-A 只读审计 | 独立 production schema、精确确认值、聚合门禁、`HR-OPS-010` | 当前缺口一次完整报告；任何 staging 行为不变 | 代码开发否；在 HK 运行需只读授权 |
| R5-B 恢复基线 | 精确捕获旧运行时、unit、Nginx 与 lifecycle 状态；加密、异机复制并在隔离环境恢复 | 原旧服务能从制品启动并通过公开兼容 smoke | 是，创建快照前需授权；不切流 |
| R5-C 主机前置 | 安装 Docker 与 PostgreSQL 18；PG/8444 只监听 loopback；建立磁盘/备份告警 | R5-A 除候选发布外全绿，既有 443 持续健康 | 是，安装/配置需授权 |
| R5-D 受管基线 | 从已验证的旧服务进入可回滚的受管 release/slot 基线，账号标志保持关闭 | `current`/`previous`、journal、自动恢复和兼容 smoke 全绿 | 是，维护窗与切流需授权 |
| R5-E 数据库准备 | 用目标不可变镜像迁移 schema；加密导出、异机复制、独立恢复及账号 smoke | 30 天内的严格恢复证据，legacy 客户端仍正常 | 是，迁移/备份需授权 |
| R5-F 正式晋级 | 使用已在 GitHub 一次性 staging 验证的同一制品执行生产候选与切换 | 观察窗、Android/Desktop/Connector、回滚点和审计通过 | 是，最终 go/no-go |

任何源码合并、GitHub staging 成功或只读审计通过都不等于生产授权。安装软件、修改监听、创建数据库、
复制线上状态、重启服务、切换路由和启用账号功能分别是显式生产动作；执行前必须说明影响、回滚点和
预计维护窗并取得确认。

## R5-A 合同

`ops/hermesctl-production-audit-config.schema.json` 与 `ops/production.audit.example.json` 是独立于 R3/R4
staging config 的生产审计合同。填入真实主机名、制品路径、旧 Gateway 文件哈希或证据的文件属于私密
运维输入，不得提交。命令要求 `--confirm production:<serverName>` 与配置逐字相等。

审计只执行白名单化的读取：`which`、`df`、`systemctl is-active`、`nginx -t`、`ss -ltnH`、
`docker info`、PostgreSQL 客户端 `--version`、公开 health GET，以及 no-follow 的本地文件元数据和
SHA-256。输出只含稳定检查 ID、状态与受限原因，不包含文件路径、HTTP 正文、Secret 或数据库 URL。

检查项固定为：

1. `host_identity` 与 `host_resources`；
2. `dependencies` 与不可变 `target_artifact`；
3. 精确旧运行时 `legacy_identity`；
4. `public_routing`，其中旧 Gateway 端口只能监听 loopback；
5. `docker` 与 PostgreSQL 18 `postgresql`；
6. 30 天内的 `legacy_recovery` 与 `off_host_database_restore`。

任一项不满足都返回完整的 blocked 列表和 `HR-OPS-010`，不写 journal，也不会尝试自动修复。

## 测试影响与退出门禁

- 严格 parser 覆盖未知字段、staging 值、危险/重复路径、端口与主机约束。
- 回归测试固定当前 HK 缺口，并断言审计调用集中不存在安装、启动、停止、重启、reload 或容器运行。
- 正向测试要求两个服务端口均只在 loopback、Docker/PG18 可用、旧文件哈希完全一致、两份异机恢复
  证据完整且新鲜。
- staging 的 R3/R4 parser、CLI 和全量测试必须原样通过；R5-A 不能让 production config 进入现有
  `bootstrap`、`deploy` 或 `rollback`。
- 基线为 `npm run build`、`npm test`、`git diff --check`、PR CI/SAST；实际 HK 审计结果记录在下方，
  任何生产修改和 Android 真机复核均保持未执行，直到得到对应授权。

## 当前完成状态

2026-09-04，R5-A 的独立配置、只读聚合审计、严格证据读取、`HR-OPS-010` 和回归测试已通过 GitHub
PR 门禁。经授权的 HK 正式审计使用 `main` 提交 `a5aaf18eb3df5eae50eaeb0fa0bb2e0bd8613548`
产生并校验的 Gateway 0.4.0 linux/amd64 bundle。十项检查中，`host_identity`、`host_resources`、
`target_artifact` 与 `legacy_identity` 通过；`dependencies`、`public_routing`、`docker`、`postgresql`、
`legacy_recovery` 与 `off_host_database_restore` 返回预期的 `HR-OPS-010` no-go。

同一次白名单读取确认旧 Gateway 与 Nginx 活跃、公开 health 返回 HTTP 200，但 8444 仍在
`0.0.0.0` 监听，Docker 与 PostgreSQL 18 客户端不存在，旧服务和数据库的异机恢复证据尚未建立。
普通运维账户无法读取 TLS 私钥，所以 `nginx -t` 在正式审计中以 `public_route_unhealthy` 阻断；这不
改变 8444 必须改为 loopback 的独立阻断事实。审计后 Gateway/Nginx 仍为 active，临时上传内容已从
HK 与 Mac 删除，未修改 `/opt`、`/etc`、`/var/lib`、数据库、路由或运行中服务。

首次实机检查还发现 `Gateway OCI` 只在临时 runner 内生成 bundle，成功后没有保留可下载的候选制品。
工作流因此只对 `main` push 保留七天的精确 bundle，PR 仍仅构建验证；正式聚合审计必须使用匹配
`main` 提交的该制品。R5-B 及之后尚未开始，香港服务器保持不变；即使 R5-A 全部通过，也仍然是
production no-go。

R5-B 的代码阶段现已建立严格的 `legacy-capture` / `legacy-restore` 合同、AES-256-GCM CMS 流式加密、
文件级恢复校验、异机主机约束、loopback 临时启动兼容 smoke、`HR-OPS-011` 和可被 R5-A 直接读取的
证据输出。配置示例和生产门禁见 `CLOUD_GATEWAY_R5_RECOVERY.md`。这只表示工具进入 PR 验证阶段；尚未
读取或复制新的线上内容，尚未生成生产快照或异机恢复证据，香港服务器仍保持不变。实际捕获仍需单独
生产授权。
