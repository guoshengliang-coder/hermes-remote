# Cloud Gateway R5-E PostgreSQL 加密备份与异机恢复

R5-E 把“备份文件存在”提升为可验证的恢复闭环：生产数据库通过 `pg_dump` 直接进入 OpenSSL CMS
AES-256-GCM 密文，密文复制到 Mac 或另一故障域后，必须恢复进一套空的临时 PostgreSQL，并由目标
Gateway 不可变镜像执行账号关系 smoke。四项证据全部通过后才生成 R5-A 恢复证据与 R5-C4 状态候选；
任一步失败都会删除本次证据和状态候选。

源码、测试或 PR 通过都不授权运行以下生产命令。数据库/角色创建、schema 迁移、生产备份、文件传输、
状态安装、timer 启用与 Gateway 切换分别属于生产动作；执行前须按 R5 顺序单独确认。当前工具也不会创建
数据库、账号或恢复目标，不会启动/停止 PostgreSQL，不会修改 Gateway、Nginx 或公开路由。

## R5-E6 已完成状态

2026-09-06 已完成首份真实生产密文的 Mac 异机 PostgreSQL 18 恢复、schema 7 精确验证、不可变 Gateway
镜像账号事务 smoke，以及证据/状态候选回传和原子激活。生产监控随后由 `main 69e25cfd8d39` 的受保护
operator bundle 安装并启用，15 分钟检查已通过真实状态和故障注入。Gateway、PostgreSQL、Nginx 与公开
路由未因此重启，两个账号功能开关仍为 `0`。

## R5-E7 每日闭环

R5-E7 在现有单次恢复原语之外增加两个互相独立的调度面：香港主机每天 03:15（`Asia/Hong_Kong`）创建
一个不可覆盖的加密代次；Mac LaunchAgent 每小时轮询，并对每个新完整代次只执行一次接收、一次性
PostgreSQL 18 真实恢复和账号 smoke。只有 Mac 完整校验密文、恢复成功并生成严格证据后，生产端才原子激活 R5-C4 状态并给该代次写入
ack。任何阶段失败都会保留上一份有效状态，监控在 36 小时边界内报 `HR-OPS-012`，不会把“捕获成功但
没有异机恢复”误报为有效备份。

生产端入口统一为 `scripts/postgresql-automation.mjs`：

- `capture` 创建 `YYYYMMDDTHHMMSSmmmZ-<12 hex>` 代次、CMS 密文和 manifest，最后才原子替换
  `latest-ready.json`；捕获时只清理超出保留数且已经 ack 的旧代次，Mac 断线时不会误删尚未接收的代次。
- `latest` / `export` 只允许导出当前完整代次。生产捕获和远程读取/激活共用一个 `flock`，避免两次读取
  之间被并发捕获拼成不一致的一对文件。
- `activate` 重新交叉验证生产 manifest、Mac 恢复证据和状态候选，原子更新活动状态后才写 ack；有一份
  更新的已验证代次后，才可按保留数删除更老的未接收代次。

Mac 入口使用 `offhost` 子命令和 `ops/postgresql.offhost.example.json`。SSH 强制已知主机校验、
`BatchMode` 和独立密钥；Mac 只可通过 root-owned 固定 wrapper 调用受限生产入口，sudoers 不授权直接运行
Node 或选择任意配置。密文先写随机临时文件，字节数和 SHA-256 同时匹配 descriptor/manifest 后才原子
进入本地代次目录。恢复容器使用 digest 固定的 PostgreSQL 18 镜像，随机密码通过只读文件挂载进入容器，
不出现在 Docker 参数；解密明文只经管道进入 `pg_restore`。无论成功失败，一次性容器、密码、URL、wrapper
和明文都会清理，恢复私钥始终只在 Mac。

生产默认保留 14 个代次、Mac 默认保留 30 个已验证代次。生产 timer、固定远程 wrapper/sudoers、Mac
LaunchAgent 的安装与首次真实自动闭环都是独立生产写入门禁；源码和一次性环境通过不等于已安装。

## R5-E1 生产只读预检结果

2026-09-05 的授权只读检查确认 PostgreSQL 18.6 active/enabled、零重启并仅监听
`127.0.0.1:5432`；HBA 无解析错误，生产实例尚无 Hermes 命名的数据库或角色。根盘约 22% 已用、
可用内存约 5.4 GiB、内存压力为零。运行中的 Gateway 仍为 `833859aa9afe`，Docker 29/containerd
运行身份与 schema v3 manifest 完全匹配，容器健康、只读且零重启，账号认证与绑定标志保持 `0`。
数据库 URL、恢复证书、备份、异机证据和监控状态路径均尚未创建。本次没有写文件、安装、数据库连接以外
的变更、重启或切流。

## R5-E2 数据库首次初始化

`scripts/postgresql-provision.mjs` 是角色/空数据库创建的唯一生产入口，配置示例为
`ops/postgresql.provision.example.json`；入口随同源提交的受哈希保护运维 bundle 交付，不得单独复制脚本。
它要求 root、Linux、精确主机确认、active PostgreSQL 18、
loopback-only 监听、关闭普通 SQL statement logging 且未加载 pgAudit。数据库名与角色名只能使用严格的
小写 SQL 标识符；密码只从既有 `0600` 文件读取，含密码 SQL 的会话把错误语句记录阈值提升到 PANIC，
连接 URL 以 `0600` 原子文件安装且不出现在输出、参数或错误中。

入口只接受全新状态：数据库、角色、URL 任一已存在即失败关闭，避免猜测接管未知对象。创建后验证数据库
owner 与角色的 NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS；数据库创建、验证或
URL 安装失败会按相反顺序删除本次新建数据库和角色。运行命令仍属于生产写入，必须在一次性 PostgreSQL 18
演练、PR 门禁和单独授权后执行：

```bash
sudo node scripts/postgresql-provision.mjs \
  --config /secure-input/hermes-go/postgresql-provision.json \
  --confirm production:<hostname>
```

R5-E2 只创建空数据库和最小权限角色，不迁移 schema、不修改 Gateway 配置或账号开关、不备份、不重启、
不切流。schema 7 迁移继续使用目标不可变 Gateway 镜像并作为下一项独立生产门禁。

## R5-E4A 生产捕获阻断与修复

2026-09-06 的首次生产捕获在生成 dump 前安全停止：Ubuntu PostgreSQL 18 的 libpq 客户端没有把完整 URI
形式的 `PGDATABASE` 环境变量展开为连接参数，因而退回默认 socket/root 连接；同时受保护运维 bundle
包含完整 R5-E 实现但漏装了薄 CLI 入口。失败路径删除了本次 `.cms` 和 manifest，schema 7、现网 Gateway、
PostgreSQL、Nginx 与账号开关均未改变。

R5-E4A 将已验证的 loopback URL 解码为独立的 `PGHOST`、`PGPORT`、`PGUSER`、`PGPASSWORD` 和
`PGDATABASE` 子进程环境，仍不把凭据放入参数、输出或日志，并拒绝远端主机、缺少用户/密码/数据库、
查询参数、fragment 和控制字符。一次性 PostgreSQL 18 演练的工具 wrapper 必须逐项验证这些字段，避免
再次用只识别旧错误形态的 mock 掩盖真实 libpq 行为。`scripts/postgresql-recovery.mjs` 同时纳入受哈希保护的
operator bundle。该修复通过 PR 门禁和一次性 R5-E 演练后，仍需使用新 main 制品单独授权重试生产捕获。

## R5-E5A 异机恢复阻断与修复

2026-09-06 使用 `main 7bc4f71472e7` 成功完成生产加密捕获，得到 PostgreSQL 18 / schema 7 的
51,565 字节 CMS AES-256-GCM 密文；生产服务、账号开关与 timer 均未改变。密文和 manifest 经 SHA-256
验证后复制到 Mac 的 `0700` 受保护目录，恢复私钥始终只在 Mac。

首次 Mac 恢复在数据库连接前安全停止：OpenSSL 解密成功，但真实 PostgreSQL 18 的 `pg_restore` 即使收到
`PGDATABASE`，仍要求显式的 `--dbname` 参数。一次性数据库、随机凭据、URL 和配置已删除，没有落地明文、
恢复 schema、账号 smoke、证据或状态候选。旧 E2E wrapper 会自行补入 `--dbname`，因而掩盖了生产入口的
缺失。

R5-E5A 从同一份严格验证并解码的 URL 中取得数据库名，以独立参数传给 `pg_restore`；用户名和密码仍只进入
libpq 子进程环境。真实 PostgreSQL 18 wrapper 不再替入口补参数，并在参数缺失或数据库目标不匹配时失败。
修复通过 PR 门禁和一次性完整演练后，才可单独授权重试 Mac 异机恢复；不得复用失败时已清理的临时数据库或
凭据。

## 安全与身份边界

- 恢复私钥只保存在异机 `0600` 文件中，绝不复制到香港主机；生产主机只接收公开证书。
- 数据库 URL 只从 `0600` 文件读取，通过子进程环境传给 PostgreSQL 工具，不进入参数、JSON 结果或日志。
- 生产捕获 URL 必须指向 `127.0.0.1`、`localhost` 或 `::1` 的 5432；隔离恢复库也只能使用 loopback，
  但可使用独立高位端口。镜像使用另一份仅容器可读的 URL 文件访问该隔离恢复库。
- 捕获前后都精确检查 PostgreSQL 18、schema 7 和服务 active；变化、版本不符、密文为空或超限均失败。
- 恢复目标必须是没有用户表的空数据库。解密输出通过管道直接进入 `pg_restore --single-transaction`，不在
  磁盘落地明文 dump。
- 账号 smoke 必须从 restore schema v2 指定的 Gateway bundle manifest 读取镜像身份。工具先校验同目录
  archive 的 SHA-256 和 schema v3 发布合同，再只接受其 classic config digest 或 OCI descriptor digest；
  随后用实际匹配的内容寻址 ID 启动只读、无 capability、限内存/CPU/PID 的一次性容器。容器在事务中写入
  账号、Google identity、Android installation 并做关联读取，最后始终 rollback。
- 证据严格包含 `encrypted_backup_hash`、`database_restore`、`schema_exact`、`account_smoke`；来源主机与
  恢复主机必须不同。
- 返回错误统一为 `HR-OPS-013`，数据库凭据、Token、私钥和用户路径会被脱敏。

## 受保护输入与三阶段命令

根据以下示例在不入库的受保护目录创建配置：

- `ops/postgresql.backup.example.json`
- `ops/postgresql.restore.example.json`
- `ops/postgresql.activate-status.example.json`

restore 配置必须使用 schema v2，并把与生产目标同一提交的 Gateway manifest 和 archive 一并复制到异机
受保护目录；不得手写 `targetImageId`、只复制 manifest，或混用不同提交的 manifest/archive。Docker classic
与 Docker 29/containerd 的 ID 表示差异由 schema v3 manifest 的两个受哈希保护 ID 处理。

第一阶段在香港主机执行加密捕获：

```bash
node scripts/postgresql-recovery.mjs backup \
  --config /secure-input/hermes-go/postgresql-backup.json \
  --confirm production:<source-hostname>
```

只有返回 `ok: true` 后，才把 `.cms` 与 `.manifest.json` 一起复制到异机，并完整保留命令返回的大小和
SHA-256。第二阶段在不同主机名的 Mac/恢复主机上，把密文放入受保护存储并针对预先创建的空临时数据库运行：

```bash
node scripts/postgresql-recovery.mjs restore \
  --config /secure-input/hermes-go/postgresql-restore.json \
  --confirm isolated:<source-hostname>
```

成功会产生 `hermes-go-postgresql-restore-v1` 证据和 `hermes-go-postgresql-backup-status-v1` 状态候选。
密文、manifest、证据、状态候选、URL 文件和私钥都属于私密运维数据，不得提交、上传到公开 CI artifact
或粘贴到聊天。

第三阶段把证据和状态候选复制回香港主机的受保护输入目录。工具会重新读取生产 manifest、证据与状态，
逐项核对来源主机、时间、密文哈希/大小、PG/schema 后，才以同文件系统临时文件加 `fsync`/rename 原子更新
R5-C4 读取的状态；目标目录必须已由单独授权的监控部署安全创建：

```bash
sudo node scripts/postgresql-recovery.mjs activate-status \
  --config /secure-input/hermes-go/postgresql-activate-status.json \
  --confirm production:<source-hostname>
```

激活只发布 `0640` 状态 JSON，不上传密文、不修改数据库、不重启或启用 timer。状态激活完成后仍要单独运行
R5-A 只读审计；只有恢复证据新鲜且所有门禁通过，才可讨论 R5-C4 timer 与下一阶段。

## 测试影响与未执行项

自动测试覆盖严格配置/manifest、未知字段、同机恢复拒绝、密文篡改、空库要求、PG/schema 精确匹配、
账号事务 rollback、目标镜像身份、失败时不发布状态、证据与状态交叉绑定、原子安装权限、错误双语/可重试
语义及数据库 URL 脱敏。可选的真实数据库测试仍应使用一次性 PostgreSQL 18 执行完整 `pg_dump` → 加密 →
异机复制 → `pg_restore` → 镜像 smoke 流程。

.github/workflows/gateway-r5e-recovery.yml` 在相关 PR 和手动触发时创建两套相互独立的临时 PostgreSQL 18
容器，构建当前提交的完整 Gateway bundle，并用其 schema v3 manifest 真实执行上述完整链路。测试账号、
数据库密码、恢复密钥、
密文和状态都只存在于一次性 runner，不读取 GitHub Secret、不连接生产域名/IP、不上传 artifact，也不生成
可用于 R5-A 的生产证据；任务结束后由 GitHub 销毁。

R5-E5A 本地修复阶段不连接生产主机，不再次读取生产数据或生成新备份，不安装状态、不部署代码，也不重启
或切换任何服务；现有密文与受保护恢复材料仅等待修复通过全部门禁后的独立恢复授权。

截至 2026-09-06，R5-E1 至 R5-E6 的首次生产闭环已完成，监控 timer 已启用；账号/数据库功能开关仍关闭。
R5-E7 源码、受保护 bundle、一次性 PostgreSQL 18 自动闭环和生产/Mac 安装演练全部通过后，才进入 timer
与 LaunchAgent 的正式安装门禁。至少观察一次自动捕获、异机接收、恢复、激活和下一轮新鲜度检查后，才可
开始 R5-F 账号功能正式晋级。
