# Cloud Gateway R5-E PostgreSQL 加密备份与异机恢复

R5-E 把“备份文件存在”提升为可验证的恢复闭环：生产数据库通过 `pg_dump` 直接进入 OpenSSL CMS
AES-256-GCM 密文，密文复制到 Mac 或另一故障域后，必须恢复进一套空的临时 PostgreSQL，并由目标
Gateway 不可变镜像执行账号关系 smoke。四项证据全部通过后才生成 R5-A 恢复证据与 R5-C4 状态候选；
任一步失败都会删除本次证据和状态候选。

源码、测试或 PR 通过都不授权运行以下生产命令。数据库/角色创建、schema 迁移、生产备份、文件传输、
状态安装、timer 启用与 Gateway 切换分别属于生产动作；执行前须按 R5 顺序单独确认。当前工具也不会创建
数据库、账号或恢复目标，不会启动/停止 PostgreSQL，不会修改 Gateway、Nginx 或公开路由。

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

本代码阶段没有创建生产数据库/账号、没有迁移或读取生产数据、没有生成生产备份、没有传输运维文件、没有
安装状态、没有部署代码，也没有重启或切换任何服务。

截至 R5-D8，生产 Gateway 已受管运行于 `833859aa9afe`，但生产 PostgreSQL 仍为空且账号/数据库功能关闭。
R5-E 下一生产步骤仍是只读预检，随后才可分别授权数据库/角色创建、schema 迁移、加密捕获、异机恢复和
状态激活；本次 manifest 身份修复不扩大这些权限。
