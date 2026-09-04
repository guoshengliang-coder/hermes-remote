# Cloud Gateway R5-C4 生产磁盘与备份监控

R5-C4 提供一个独立、只读、失败关闭的生产监控命令，用来发现香港主机根分区空间不足，以及 PostgreSQL
加密逻辑备份没有按时完成异机复制的问题。它不会清理磁盘、执行备份、访问数据库、安装软件、重启服务、
修改 Nginx 或切换 Gateway 路由。

## 检查与告警合同

私密运行配置必须从 `ops/production.monitor.example.json` 创建，并满足
`ops/hermesctl-production-monitor-config.schema.json`。配置固定为 production、Linux amd64 主机和根挂载点
`/`；critical 空间阈值必须严格小于 warning 阈值。填入真实主机名和状态文件路径的配置不得提交。

命令只执行一次 `df -Pk -- /`，并以 no-follow 方式读取不允许 group/world 写入的 JSON 状态文件：

```bash
node scripts/production-monitor.mjs \
  --config /etc/hermes-remote/production-monitor.json \
  --confirm production:<configured-hostname>
```

检查项固定为 `host_identity`、`disk_capacity` 和 `database_backup`。磁盘低于 warning 或 critical
阈值、备份状态缺失/损坏/过期、时间线在未来、来源主机/PG18/schema/最小密文大小不匹配，都会令命令非零
退出并返回 `HR-OPS-012`。结果只包含稳定检查 ID、级别、受限原因和安全的容量/年龄指标，不输出配置路径、
备份哈希或异机存储标识。

## 备份状态不是备份本身

`ops/postgresql-backup-status.schema.json` 定义
`hermes-go-postgresql-backup-status-v1`。状态至少绑定来源主机、备份完成时间、异机复制完成时间、源端与
异机各自计算的密文 SHA-256/大小、PostgreSQL major、数据库 schema，以及一个不含凭据且不同于来源
主机名的异机存储 ID。监控要求两端哈希与大小完全一致。

该文件只能由 R5-E 的备份流程在下列步骤全部成功后原子替换：

1. `pg_dump` 逻辑备份完成并直接加密，香港主机不保留明文；
2. 密文复制到 Mac 或另一独立故障域；
3. 从异机完整读取的字节数和 SHA-256 与源端一致；
4. 使用最终时间和身份字段写入临时状态文件，再在同一文件系统原子改名为 `latest-status.json`。

手写示例状态、仅复制到香港主机另一目录、只检查文件存在或只更新复制时间，都不能表示备份成功。监控状态
也不替代 R5-A 所要求的独立 PostgreSQL 真实恢复证据；账号模式仍须等 R5-E 完成恢复和 account smoke。

## systemd 模板与通知边界

`deploy/hermes-go-production-monitor.service.template` 每次运行只读命令，失败通过 `OnFailure` 触发
`hermes-go-production-monitor-alert.service`；后者用 `daemon.alert` 写入 `HR-OPS-012` 本机高优先级 journal。
`hermes-go-production-monitor.timer.template` 每 15 分钟运行一次，启用持久补跑和最长 60 秒随机延迟。

安装时必须先把 `__PRODUCTION_HOSTNAME__` 替换为与私密配置完全相同的主机名，并让
`/opt/hermes-go-ops` 使用经审核提交的独立只读代码快照，避免覆盖旧 Gateway 所在的
`/opt/hermes-remote`。快照只需包含 `scripts/production-monitor.mjs`，以及 `ops/lib` 中的
`config.mjs`、`errors.mjs`、`production-monitor-config.mjs`、`production-monitor.mjs` 和 `system.mjs`。
该独立入口不加载部署、迁移或 Gateway 协议模块，也不需要 `node_modules`。`hermes-remote` 用户只能读取
所需代码、配置与状态文件。模板不包含外发网络能力，
因此当前告警可由
`systemctl --failed` 和 journal 发现，但不会主动发送到手机、邮件或第三方平台。若需要外部通知，应由受保护
的日志采集器消费 `daemon.alert`；不要把 webhook、邮箱、Token 或其他凭据写进 unit、仓库或命令输出。

部署 unit、启用 timer 或接入外部通知都会改变生产状态，必须另行取得明确授权。源码合并和测试通过不授权
这些操作。部署后至少验证一次全绿运行和一次使用测试状态文件触发的 `HR-OPS-012`，随后恢复真实状态并
确认 Gateway、Nginx、PostgreSQL、DERP 和发布服务没有被重启或降级。
