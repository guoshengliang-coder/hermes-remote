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
R5-C4 timer 和首次 R5-E6 异机恢复/状态激活已完成。2026-09-06 又完成 R5-E7 每日捕获、Mac 每小时
异机恢复调度的生产安装和一次手动完整闭环。2026-09-07 首个 scheduler-triggered 捕获成功，但 Mac 自动
恢复因 LaunchAgent PATH 无法解析 wrapper 的 `node` 而失败关闭；R5-E7A 随后用 `main 4d2cc6561826`
受保护制品修复并重放同一代次，异机恢复、schema 7、账号 smoke、状态激活、ack 和后续监控全部通过。
2026-09-09 又以 `main 787bdc917190` 完成 Gateway 0.4.9 常规发布、邮箱 OTP 生产灰度、schema 15 迁移，
以及迁移后的新一轮加密捕获、Mac PostgreSQL 18 真实恢复、0.4.9 镜像账号 smoke、状态激活和监控复查。
R5-E 数据库恢复自动化门禁与 R5-F2 邮箱登录灰度均已完成；Google、绑定、多设备分享、身份管理、Web
账号中心和 Desktop 托管安装仍未启用。
邮箱登录验收后的只读绑定预检发现，0.4.9 镜像内置 Docker healthcheck 把开发端口 `8787` 写死，而
受管 green 槽实际监听 `PORT=18788`；因此公开业务/readiness 正常，但容器被 Docker 持续标记为
`unhealthy`。后续绑定灰度在新版本镜像使用运行时 `PORT`、非默认容器端口 OCI 门禁通过并完成常规
蓝绿发布前保持阻断；不得忽略该状态或直接打开绑定开关。
常规蓝绿发布也已收紧为只接受“账号全关”或当前精确的“仅邮箱 OTP”环境。邮箱模式下只允许候选槽
端口变化，要求源/目标数据库 schema 相同，并在停旧槽前复核环境摘要；候选与公网 smoke 都必须确认
仅 `email_otp` 可用且绑定、多设备、分享、Google、身份管理、Web/删除和 Desktop 托管安装仍关闭。
任何未知字段、权限或配置漂移均在切流前失败，schema 变化必须回到专用迁移/恢复流程。
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
| R5-F1 常规发版 | 受管基线内的 blue↔green 常规 deploy/rollback 路径（`scripts/production-release.mjs`），账号与数据库标志继续关闭；只改 upstream include，不动站点文件 | 一次性演练完成接管→发版→回滚且站点文件不变；单测覆盖授权矩阵 | 是，每次发版单独授权 |
| R5-F2 邮箱登录灰度 | 只启用邮箱 OTP、schema 15、邮件回执与账号会话，其他账号能力关闭 | 实际投递、恢复证据、Legacy 兼容与两轮 smoke 全绿 | 是，已按独立授权执行并记录 |
| R5-F3 单终端绑定灰度 | 只启用一台自有 Mac 的绑定、V2 Connector 与 Desktop 托管安装 | 精确 binding/generation、端到端流量、回滚点和单 Connector 不变量通过 | 是，每次执行单独授权 |
| R5-F4 多自有终端灰度 | 独立启用 plural devices、显式设备路由与最多三台自有 Mac；分享仍关闭 | 2026-09-13 生产 flag 与路由已 committed，原 Mac 在线且未创建第二个 binding；第二台 canary Mac 的选择/会话亲和、容量竞争和恢复策略仍待实机验收 | 是，生产执行已授权完成；实机绑定仍需所有者操作 |
| R5-F5 整机共享灰度 | F5-A 先启用身份/Web 接受面，F5-B 再启用账号 B→账号 A 的邀请、使用、撤销与退出 | 邮件接受、operator 权限、跨账号隔离、五秒内断流和无孤儿 grant 通过 | 是，两个子阶段分别授权 |
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

2026-09-07，R5-E 调度门禁完成后复盘发现：受管基线只有 R5-D 这一条首次接管路径，`hermesctl deploy/rollback`
仍是 staging-only，因此 0.4.0 之后的任何网关版本（首先是带结构化日志的 0.4.1，PR #55/#58 已在 `main`）都没有
合法上线通道。R5-F1 代码阶段补上受管基线内的常规 blue↔green 发版与回滚入口 `scripts/production-release.mjs`
（运维 bundle manifest v3、`production-release` capability、边缘预检、只改 upstream 不动站点文件、
`HR-OPS-016`），并把一次性 R5-D 演练延长为"接管→发版→回滚"。账号与数据库标志继续固定关闭，R5-F 的账号
模式晋级不受影响。细节见 `CLOUD_GATEWAY_R5_MANAGED_BASELINE.md`
"常规生产发版"与 `DEPLOYMENT.md` "Routine production release"。

同日 PR #78 合入 `main` 80225d8，一次性演练 run 34104753984 通过后，经授权用同一提交的制品完成了
R5-F1 的首次生产运行：Gateway 0.4.1 进入 green 槽（run `590d6014-87a9-403a-a2b3-101d3bf4b701`，49 秒，
站点文件不变，Connector 在线，容器零重启），`previous` 回滚点为 0.4.0-833859aa9afe。生产网关从此有了
结构化日志；账号、数据库、监控 timer 与 R5-E 自动化均未触碰。R5-F 账号模式晋级仍是独立的 go/no-go。

2026-09-08，R5-F2 代码阶段新增生产邮箱登录灰度入口：Gateway 升至 0.4.2，运维 bundle manifest v4
显式携带 `scripts/production-account-rollout.mjs`。入口只允许 email OTP + Resend webhook，Google、设备
绑定、多设备、分享、身份管理、Web session、删除和 Desktop 托管安装全部保持关闭；它从不可变镜像执行
schema 15 迁移，安装 `_FILE` 密钥，并只向公网增加 email challenge/exchange、refresh、sign-out 与账号读取
路由。启用后做两轮完整 smoke；任一 live 阶段失败都会恢复原 Gateway 环境和 Nginx 站点、重启并验证
`accountAuth.enabled=false`，统一返回 `HR-OPS-020`。正式执行仍要求同一 main 提交的 CI/OCI、schema-7
迁移前异机恢复和明确生产授权；迁移后必须把加密备份循环提升到 schema 15 并再次通过异机恢复。
现场关闭态验证发现既有生产站点未公开 capability discovery；后续热修复将该路由纳入窄 include 且避免
与已存在的精确 location 重复。首次 0.4.3 生产尝试现场发现旧 App Token smoke header、缺失的 Webhook
location 和原始 404 capability 回滚判定三处阻塞并安全恢复关闭态；0.4.4 修正后，重试预检又发现真实
Hermes 使用 `overall=ok` / `gateway_running=true` 而非旧 fixture 的 `status=ok`；0.4.5 现场重试又证明错误
账号 Bearer 并不是 legacy 拒绝探针，且原站点对未定义的 email POST 会由发布服务返回 405。0.4.6 因此改为
验证错误 legacy header，并按上线前是否存在精确 email location 验证 404/405 关闭态，不扩大灰度功能面。
0.4.6 真实灰度随后暴露最后一个基础设施阻塞：受管服务通过 Docker bridge 发布 loopback 端口，容器内的
`127.0.0.1:5432` 无法到达主机上仅监听 loopback 的 PostgreSQL，readiness 因此正确返回 database unavailable。
0.4.7 将受管 blue/green 容器改为 host network，同时让 Gateway 自身只监听各槽配置的 `127.0.0.1` 端口；
这既保留 Nginx-only 公网边界，又让运行时与迁移容器使用同一条本机 PostgreSQL 安全路径。
0.4.7 现场确认 readiness 已恢复，但容器重启后 `relay-health` 的第一个 HTTP 200 仍可能报告 Connector 尚未
重连，而紧随其后的 legacy `/api/status` 已重试成功。0.4.8 将强语义的 legacy status 等待放在 relay 快照前，
避免把瞬时 `connectors: 0` 固化为失败，同时仍要求最终 relay 至少有一个 Connector。
0.4.8 真实灰度的所有网络探针均通过，但操作器把 Gateway 的正式 readiness 值
`checks.migrations=ok` 误写成 `current`，因此在一秒内安全回滚。0.4.9 对齐这个既有公开契约，并以真实
Gateway 返回形状作为回归 fixture；不改变账号 API、数据库或灰度范围。

PR #111 的全部门禁、合并后 CI/SAST/OCI 与手动 R5-D 演练通过后，2026-09-09 的授权生产发布 run
`438fdbcc-72c0-420d-923d-eac42f57bd29` 将 0.4.9 提升至 green，并保留 0.4.8 blue 回滚点。账号灰度 run
`0065f70a-a3f9-4348-918a-d22eaad135b5` 随后 committed：公开能力只有 `email_otp`，Resend 隔离地址达到
final-delivered，Connector 与 legacy token 通道持续健康，green 零重启。迁移后的 schema-15 代次
`20260908T191154059Z-cecbfc922361` 已在 Mac 用 PostgreSQL 18 和精确 0.4.9 镜像真实恢复、回传证据并激活
ack；HK 捕获/监控 timer 与 Mac 小时级 LaunchAgent 均已按 schema 15 和新 manifest 更新并通过复查。

后续 0.4.10 常规发布在切流前被旧的“账号关闭”readiness 断言拒绝；实际生产已是仅邮箱 OTP，
候选正确返回 `database=ok`、`migrations=ok`、`postgresql=supported`，因此线上 0.4.9、Nginx、release links
和流量均未变化。0.4.11 修正候选及公网 smoke，使其按受管槽中保留的精确邮箱环境验证。由于失败记录
按设计停在 `candidate_started` 并锁定原计划，0.4.12 增加同提交制品携带的窄恢复入口：只有失败审计、
候选停止且端口空闲、原槽和 release links 未变、Nginx 检查点逐字节一致、并且历史中恰有一个与当前
0.4.9 对应的 committed journal 时，才会在部署锁内归档失败 journal 并恢复该 committed journal；
恢复完成后仍须重新执行完整常规发布，不能借此启用绑定或扩大邮箱灰度范围。
0.4.12 首次重试随后在私有候选邮箱面校验中保持切流前失败：Gateway 内部对已关闭的 binding control
返回 `503`，公网 Nginx 则按预期隐藏该路由并返回 `404`。0.4.13 将两条边界分别固定为私有 `503`、
公网 `404`；其余 capability、readiness、账号和部署恢复合同不变。

PR #117 合并提交 `1c73f010d831` 的 main CI/SAST/OCI（OCI run `34302971090`）全部通过后，0.4.12
操作器先归档 0.4.10 失败 run `dd5af69c-d410-436b-9200-385cceec4704` 并恢复唯一的 0.4.9 committed
journal。0.4.12 发布 run `07ac7b95-3c5d-46ab-b437-70832f8a3bcb` 复现上述私有 `503` 差异且在切流前
停止；同一恢复入口再次归档该 run，0.4.9、release links 与 Nginx 两份文件保持原哈希。

PR #118 合并提交 `bdc66f58a8c9` 的 main CI/SAST/OCI（OCI run `34304405260`）全部通过，Gateway archive
SHA-256 为 `53176188f59ac271c3d2fa574d109eadeb70839e85b482aeb7d95303d606a5d4`，operator archive 为
`ed78eded1f62902109e383f93a11edc1d477000ec6e514655d50a4ed48c7d04e`。生产 run
`348f8a3f-fa25-4bc3-be45-ae210458be5f` 随后 committed：0.4.13 blue active/enabled、容器 health `healthy`、
零重启，`current=0.4.13-bdc66f58a8c9`，`previous=0.4.9-787bdc917190`。站点文件 SHA-256 仍是
`237e8546a0f5e5f4a35ef90cbdc53a58e1604b03cbbeb35938726b5cb04b9173`，只有 upstream 改到 loopback
`18787`。readiness 保持 schema 15/PostgreSQL 18 全绿；公网仍只有 `email_otp`，Connector `mac-mini`
在线，账号 guard 为 `401`，binding 路由为 `404`，Google、绑定、多设备、分享、身份/Web/删除和
Desktop 托管安装均未启用。生产监控复跑通过，最近异机加密备份的本地/异机哈希与 87,632 字节一致。

0.4.14 代码阶段增加 R5-F3 单 Mac 绑定灰度入口。运维 bundle schema v5 固定
`scripts/production-binding-rollout.mjs`，入口只接受首次 committed 邮箱 checkpoint、当前 schema-15
活动制品和由常规发版逐代保留的现场精确邮箱态，安装独立的
binding/V2 WebSocket Nginx include，并仅把 `ACCOUNT_BINDING_ENABLED` 与
`ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED` 切为 `1`。两轮门禁要求 schema 15/PostgreSQL 18 readiness、
邮箱-only provider、单 Mac 上限、`hermes-serve-v1`、未认证 binding 为 401、公开 WebSocket 101、legacy
Hermes 与 release identity 持续健康。失败时逐字节恢复环境和站点并验证公开 binding 回到 404、私有回到
503，统一返回 `HR-OPS-021`。多设备、分享、身份管理、Web、删除与 Google 仍关闭；本段是代码门禁，尚未
构成生产执行结果。

## R5-F4 / R5-F5 后续多终端与共享晋级

R5-F4 是 R5-F3 之后的独立操作器能力，不能通过手改活动槽环境启用
`ACCOUNT_MULTI_DEVICE_ENABLED`。默认关闭的源码操作器、配置 schema、manifest 固定入口、故障测试和
稳定 `HR-OPS-022` 失败码已经完成。2026-09-13 已通过独立授权在生产提交 flag 与 plural routes，活动
Gateway 保持 `0.4.15-6b7d60fa6bbf`，数据库仍只有一条 active/online binding，未创建第二台设备。操作器只允许从精确 committed 的单终端状态开始，安装 plural device
和显式 device-scoped REST/WebSocket 路由，保持邮箱 provider、数据库 schema、Legacy 路由、分享、
Google、删除以及其他环境字节不变。候选与公网 smoke 必须同时证明原 Mac 仍在线、第二台 canary Mac
拥有不同的 binding/generation/device ID、选择不会移动旧会话、任一 Connector 重启不影响另一台，且
并发第四台只能得到一个稳定容量拒绝；这些第二台 Mac 的实机门禁仍待设备可访问时完成。

账号托管接管会按设计停用 Legacy Connector，因此 F4/F5 操作器在变更前捕获 Legacy App-Token 路径的
精确状态：仍有旧 Connector 时必须是健康响应；已完成托管接管时允许精确的
`503 {"error":"device_offline"}`。重启后和观察窗口后的两轮检查必须保持同一状态；认证失败、其他 5xx
或格式漂移仍然失败关闭。2026-09-13 的第一次 F4 生产尝试正是在旧门禁上于预检阶段停止，未创建 journal
且未修改环境或 Nginx，随后补上了这项迁移态回归覆盖。

R5-F4 的回滚边界取决于是否已经产生第二个 committed binding。在此之前，操作器可以逐字节恢复单终端
环境和 Nginx 路由。此后关闭 multi-device 会让合法状态失去可达入口，因此自动回滚必须失败关闭：只能
向前修复，或先由所有者通过正常、已确认的 unbind 流程移除 canary binding，再恢复单终端模式。操作器
不得直接删除数据库行、撤销第一台 Mac，或用显示名猜测要删除的设备。

R5-F5 分为两个独立 journal 和授权。F5-A 启用共享所需的身份管理与 HTTPS 账户接受面，验证邮箱 provider、
Secure/HttpOnly/SameSite Cookie、CSRF、Origin、CSP、邮件域和投递监控；此时
`ACCOUNT_DEVICE_SHARING_ENABLED` 仍为 `0`。F5-B 只能从 committed F5-A 加上 committed R5-F4 开始，
再启用整机共享及其邀请/接受/撤销/退出路由，保持 Google、删除和自有终端上限不变。

F5-A 的默认关闭源码操作器、严格配置 schema、schema-7 不可变 bundle 入口、精确 Nginx allowlist、
故障注入和稳定 `HR-OPS-023` 已完成。操作器要求 committed R5-F4，并独立固定当前 release identity，
只把 identity management、Web account center 和 Web session 三个标志一同打开；Google、删除和 sharing
继续关闭。公开 smoke 检查账号 shell 的 CSP/no-store、安全 Cookie、CSRF/Origin 拒绝、未认证身份与安装
管理 guard，并证明 Google、删除和分享探针仍由边缘以 404 absent 或 405 method rejected 拒绝。2026-09-13
第一次生产尝试因旧操作器只接受 404 而在变更前停止；未创建 journal、路由或修改标志，随后增加 405
生产边缘合同回归。失败时逐字节恢复多终端环境与站点文件、
删除独立 identity-Web include 并再次验证多终端状态。代码合并和 bundle 生成不授权生产执行。

第二次生产尝试进入启用阶段后发现生成环境漏掉 Gateway Web Session 强制要求的
`ACCOUNT_WEB_ORIGIN`，readiness 因进程拒绝启动而失败；操作器完整恢复 F4，journal 记录为
`rolled_back`，Connector 随后重连。规范环境现同时固定 `ACCOUNT_WEB_ORIGIN` 与 F5-B 将使用的
`ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN`，并只对尚未启用身份/Web/分享的旧 F4 环境提供一次兼容升级。

F5-B 的默认关闭源码操作器、严格配置 schema、schema-8 不可变 bundle 入口、独立 sharing Nginx
allowlist、故障注入和稳定 `HR-OPS-024` 已完成。操作器只接受 committed F5-A、committed R5-F4、
完全匹配的 identity-Web 环境/路由与当前 release identity，只打开 sharing 标志；Google、删除、三台
自有终端上限及其他能力保持不变。公开与 loopback smoke 固定五名 grantee、十台已接受共享终端，检查
native/Web 未认证 guard、Web CSRF 拒绝、原有账号中心安全边界、Legacy 和 release identity。即时失败会
逐字节恢复 F5-A 环境与站点文件并删除独立 sharing include；操作器自身不创建邀请或 grant，代码合并
和 bundle 生成仍不授权生产执行。

F5-B canary 使用两个独立账号和一台由账号 B 拥有的 Mac。B 完成 `device.share` 邮箱复核与整机披露后
邀请 A；A 通过自己的已验证邮箱接受 72 小时邀请，以 `operator` 身份执行 REST、WebSocket、普通 prompt、
`/model`、`/compact` 和文件流量，但不能分享、绑定、替换、解绑、旋转或管理 B 的资源。B 撤销和 A 退出
分别验证只删除匹配 grant、新请求立即失败、活动 WebSocket 最迟五秒关闭，而 B 与其他授权客户端继续。

F5-B 在邀请接受前回滚时必须取消 canary invitation。已有 grant 后，操作器必须先走正常撤销并证明断流，
才能关闭 sharing flag；不得把活跃 grant 隐藏在关闭的 capability 后面。容量门禁继续固定每台终端五个
grantee、每个账号十台已接受共享终端。R5-F4、F5-A、F5-B 的实现、合并和 staging 只完成各自代码门禁，
任何生产执行仍逐次适用本文件的明确授权要求。
