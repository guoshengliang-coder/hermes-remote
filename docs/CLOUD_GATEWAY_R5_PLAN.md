# Cloud Gateway R5 生产晋级计划

R4 已证明不可变 Gateway 制品可以在一次性 staging 中完成候选启动、状态交接、切流、回滚和
PostgreSQL 迁移，但所有命令仍明确拒绝 production。R5 的目标不是直接解除这个开关，而是先把香港
主机现存的旧 Node/systemd 服务接入一个可验证、可恢复的生产基线，再复用已经通过演练的 R4 状态机。

当前只有一台香港服务器不是阻断条件：Gateway、Nginx 和 PostgreSQL 可以同机运行；数据库必须只
监听 loopback。但同机数据库不能把同一块磁盘上的文件称为备份，所以加密逻辑备份必须复制到 Mac
或另一处独立存储，并在独立的临时 PostgreSQL 中真实恢复验证。

## 当前生产差距

2026-09-03 的首次只读检查确认线上 Gateway 仍是 `/opt/hermes-remote` 下的旧 Node 服务，缺少受管
`current`/`previous` 和制品 manifest。到 2026-09-05，R5-B、R5-C1～C3 与 R5-D 已经补齐旧服务异机恢复、
Docker、PostgreSQL 18、loopback 监听和受管 blue 槽基线；数据库创建/schema 迁移、数据库恢复证据、
R5-C4 timer 与账号模式正式晋级仍未完成。
现有 443 路由、Gateway、发布服务、DERP 和证书必须继续保持健康，后续操作不得改变既有
Android/Connector 的 URL、Token 与协议。

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
`main` 提交的该制品。

随后经单独授权，R5-B 已使用严格的 `legacy-capture` / `legacy-restore` 合同、AES-256-GCM CMS 流式加密、
文件级恢复校验、异机主机约束、loopback 临时启动兼容 smoke、`HR-OPS-011` 和可被 R5-A 直接读取的
证据输出，在 Mac 完成加密制品校验与异机恢复测试。受保护恢复制品保存在 Mac 运维目录，没有提交到
仓库；生产捕获没有停止、重启或切换 Gateway。配置示例和生产门禁见
`CLOUD_GATEWAY_R5_RECOVERY.md`。

R5-C1 已把旧 Gateway 8444 从公网监听收口到 `127.0.0.1`，重启后公开 health 与正确/错误 Token 路由
保持预期；R5-C2 已安装 Ubuntu 官方 Docker/containerd/runc，未创建业务容器；R5-C3 已安装并初始化空的
PostgreSQL 18 集群，显式只监听 `127.0.0.1:5432`，尚未创建 Hermes 数据库、账号或迁移数据。每一步均在
授权范围内单独验证，未重启 Nginx，也未改变公开 443 路由。

R5-C4 代码阶段新增严格的根磁盘/加密异机备份新鲜度监控、`HR-OPS-012`、15 分钟 systemd timer 和
本机 `daemon.alert` 模板。它只读取 `df` 和由未来 R5-E 备份流程原子更新的状态文件；状态文件不是数据库
恢复证据。当前源码尚未部署到香港主机，timer 尚未启用，外部手机/邮件告警渠道也尚未接入。安装与启用
仍需单独生产授权，详见 `CLOUD_GATEWAY_R5_MONITORING.md`。在真实数据库备份、异机恢复证据、受管基线
和最终切流完成前，生产晋级仍是 no-go。

R5-E 代码阶段新增独立 PostgreSQL 恢复入口、严格配置/manifest、CMS AES-256-GCM 流式备份、无明文
`pg_restore`、目标不可变 Gateway 镜像内账号事务 smoke、R5-A 证据、R5-C4 状态候选与生产端原子状态
激活，并以 `HR-OPS-013` 失败关闭。实现与操作边界见 `CLOUD_GATEWAY_R5_DATABASE_RECOVERY.md`。
当前仅完成本地代码和自动测试；生产数据库/角色/schema 迁移、捕获、异机真实恢复、状态激活、timer 启用
和服务切换均未执行，R5-E 生产门禁仍为 no-go。

R5-D8 的 R5-E 生产准备复审发现，原 restore schema v1 只接受一个手工填写的 Docker image ID，无法同时
绑定 R5-D5 后 Gateway manifest schema v3 中的 classic config digest 与 OCI descriptor digest。restore
schema v2 改为接收 Gateway bundle manifest 路径，先校验同目录 archive SHA-256、发布合同、PG18/schema 7，
再只接受 manifest 绑定的两个 runtime ID 之一；一次性 R5-E workflow 也改为打包并使用完整 bundle。该修复
不创建生产数据库、不读取生产数据，也不授权后续生产步骤。

R5-D 代码阶段新增独立 `production-baseline` 入口、严格 production-only 配置、R5-B evidence 与旧运行时
identity 绑定、候选 Nginx 文件哈希门禁，以及仅限首次 `activeSlot: null` 的 R4 蓝绿状态机 capability。
账号认证、账号绑定与数据库均固定关闭；切换失败使用 legacy 专用兼容 smoke 复核自动恢复。手动
`Gateway R5-D Managed Baseline` workflow 只在无 Secret、无 SSH、无生产地址的一次性 Ubuntu 主机运行。
生产接管的最终结果与操作细节见
`CLOUD_GATEWAY_R5_MANAGED_BASELINE.md`。

PR #37 合并并通过 `main` CI/SAST/OCI 后执行的生产前只读复审确认资源、旧 identity、R5-B 证据、
loopback 监听、Docker/PG18 与公开服务健康；同时发现实际 Nginx basename、默认 8788 槽位冲突、缺少
与 `main` 绑定的运维执行 bundle，以及 Token 输入权限/内部状态 Token 不满足严格门禁。R5-D1 在不连接
或修改生产的独立分支中补齐精确 Nginx 兼容与可哈希运维 bundle；私密配置改用 18787/18788，受保护
输入的复制/生成仍等待单独生产写入授权。正式接管继续保持 no-go。

2026-09-05 的 R5-D2 写入前复核发现，schema v1 运维 bundle 虽包含测试 Connector，却仍要求操作者提供
外部 Hermes smoke 服务；在单机生产环境中只能复制 Mac Hermes 凭据或临时拼接未绑定脚本，两者都违反
既有安全边界，因此在创建任何生产文件前 fail-closed。R5-D3 改为由生产入口自动建立随机
`127.0.0.1` 端口、一次性凭据、白名单子进程环境和自动清理的模拟 Hermes runtime，并用 schema v2
运维 manifest 固定该入口。R5-D2 必须等待 R5-D3 合并及 `main` 新制品全部门禁通过后重新开始。

2026-09-05 重新执行 R5-D2 时，schema v2 制品、受保护输入、恢复证据、loopback runtime 与现网只读
兼容检查均通过，但生产准备期间 `main` 前移到未触发 Gateway OCI 的新提交；同时确认运维 bundle 没有
携带文档规定的传输后独立校验入口。两项均保持 fail-closed，旧 Gateway、Nginx 与流量未改变。R5-D4
把 `scripts/verify-production-baseline-bundle.mjs` 纳入运维 bundle，并要求从最新 `main` 重新生成和保留
同提交制品；R5-D2 必须使用新制品重新准备，旧 `cbe1285c1028` 输入不得用于接管。

首次正式接管使用 `7b5eb9bf1c38` 制品时，入口在切流前识别出两项兼容缺口并保持旧生产可用：最初的
内部状态 Token 使用了不被严格白名单接受的字符；修正为独立的 64 位十六进制 Token 后，Docker 29 默认
containerd image store 把已验证 OCI manifest descriptor digest 作为运行时镜像 ID，而 schema v2 Gateway
manifest 只记录经典 config digest，因此在 `artifact_image_inspect` fail-closed。R5-D5 用 Gateway manifest
schema v3 同时绑定 archive 内的 config/OCI 两个 digest，运行时仍只接受精确清单值；同时只允许有失败
审计、停在 `checkpoint_created`、候选未启动/未监听且 release/Nginx/upstream 检查点未漂移的 journal
原子归档后续跑。该修复本地与一次性测试完成前不得再次生产接管。

使用合并提交 `25345666167a` 的第二次正式接管成功加载 Docker 29/containerd 镜像并启动 blue 候选，但
完整 smoke 在切流前返回 `gateway_smoke_failed=1`。入口停止并删除候选，旧 Gateway 保持 active/enabled，
8444、5432 仍只监听 loopback，Nginx/current/upstream 均与检查点一致；随后公网 Token、REST、WebSocket
与 APK 发布 health 复核通过。复盘确认运维 bundle 漏装候选验证器依赖的
`scripts/lib/release-errors.mjs`，而一次性演练从 Git checkout 运行，未覆盖解压 bundle 自包含性；被忽略的
子进程 stderr 又把模块加载错误压缩成无细节退出码。R5-D6 将 release error 与就绪 helper 一并纳入 bundle，
在打包时从 staging root 实际启动验证器，加入 Connector 挂接后的有界 REST 转发就绪等待，并只上送稳定、
脱敏的 allowlist smoke 子阶段。生产 journal 保持 `candidate_started`，只允许同一计划重做候选验证；
R5-D6 合并、最新 main OCI 制品和一次性演练全部通过前不得再次生产接管。

使用 R5-D6 运维 bundle 续跑原 `25345666167a` 计划时，blue 私有候选和完整转发 smoke 已通过；Nginx
完成切换后，公网验证却请求生产边缘未公开的 `/healthz`，收到 404 并触发
`HR-RELEASE-003:smoke_check=liveness`。状态机在观察前恢复旧 Nginx、旧 Gateway、release links 和
lifecycle 状态，归档 `route_switched` journal；随后确认旧 8444、PostgreSQL 5432 仍仅监听 loopback，
blue/green 停止且禁用，公网 `/relay-health`、发布 `/health`、认证 REST 与 WebSocket 全部正常。R5-D7
把私有镜像/就绪验证与公网路由验证拆开，并要求一次性演练显式覆盖生产公网接口集合；全部门禁通过前不再
执行生产接管。

R5-D7 合并提交 `833859aa9afe55f09d2fe8663ab0fd1528447ba4` 的 PR、CI、SAST、Gateway OCI、加密恢复和
两次一次性 R5-D 演练全部通过后，获授权的正式接管在 2026-09-05 成功提交 run
`5403064b-c220-42ab-91e0-d3b605e8c674`。blue 槽运行 Gateway 0.4.0，Nginx 上游切换到
`127.0.0.1:18787`，旧 Node Gateway 停止并禁用；`current` 指向 `0.4.0-833859aa9afe`，`previous`
保留 `0.2.0-54f7aed61172`。私有版本/就绪、公开 Connector、认证 REST/WebSocket、错误 Token、发布服务、
镜像身份与 Nginx 均复核通过，容器无重启或告警。PostgreSQL 仍只监听 loopback，数据库与两个账号标志
保持关闭，R5-C4 timer 未启用。R5-D 至此完成，R5-E/F 仍保持独立生产授权边界。

2026-09-05 的 R5-E1 授权只读预检确认 PostgreSQL 18.6、loopback 监听、资源、HBA、Gateway
`833859aa9afe` manifest/containerd 身份与关闭的账号标志均符合预期，生产仍无 Hermes 数据库、角色、
连接 URL、恢复证书或备份状态。R5-E2 因而新增严格的首次数据库初始化入口：只接收全新状态、从 `0600`
文件读取凭据、避免 SQL 日志捕获、验证最小权限并在失败时清理本次对象。合并和一次性门禁完成前不得运行生产初始化。
