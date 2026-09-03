# Cloud Gateway R3 Cloud Ops 验证记录

R3 实现受控 Ubuntu/x86_64 staging 的 `preflight/bootstrap/status/doctor` 基线。生产部署、现有服务
升级、Nginx 生产路由切换、数据库迁移、备份/恢复和回滚均不属于本轮。

## 测试影响

| Boundary | Planned automated evidence |
| --- | --- |
| Strict configuration | schema/version、未知字段、staging-only、路径/名称/端口约束、输入与受管目录隔离 |
| Immutable artifact | archive SHA-256、文件名/版本/commit/tag 一致、image ID 与 amd64 架构一致 |
| Secret and TLS input | 普通文件、拒绝 symlink、私有权限、长度/格式、三个 Token 不同、证书不含私钥 |
| Host preflight | Linux x86_64、Docker/systemd/Nginx/curl/ss、端口和已有 release 冲突 |
| Bootstrap safety | staging 双确认、root、无 shell、内容寻址 image、硬化 unit、原子文件、互斥锁 |
| Resume/idempotency | 相同摘要重复执行、中断阶段继续、不同配置摘要和已有 release 拒绝 |
| Operations audit | 操作人 ID、环境、版本、commit、时间、阶段、结果；无 Secret |
| Status | systemd、Nginx、container/image、liveness/readiness 分层和 `HR-OPS-004` degraded |
| Doctor | `0600`、排他创建、大小/hash、白名单字段、无原始日志/正文/环境/Secret/路径 |
| Error contract | `HR-OPS-001` 至 `HR-OPS-005`、中英文、重试与恢复动作、诊断脱敏 |

## 当前状态

| Gate | Result | Evidence |
| --- | --- | --- |
| Static/syntax checks | Pass | 2026-09-03：`git diff --check`、新增 `.mjs` 的 `node --check` 与 bundle 脚本 `sh -n` 通过 |
| Script unit tests | Pass | `node --test scripts/test/hermesctl.test.mjs`：9/9 通过；`npm test` 中同组 9/9 再次通过 |
| Repository build/test | Pass with declared skips | `npm run build` 通过；`npm test` 全部已执行项目通过，Gateway 默认门禁按配置跳过 11 项可选集成测试 |
| Gateway loopback integration | Pass with DB skips | `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway`：50 通过、0 失败、3 项因未提供一次性 PostgreSQL 而跳过 |
| Security scan | Pending CI | 当前 macOS 环境没有固定 Semgrep/Gitleaks 运行时；推送后由 digest-pinned CI 执行 |
| OCI bundle packaging | Pending CI | 当前环境没有 Docker；推送后由 Ubuntu/amd64 Gateway OCI job 从 clean commit 生成 archive + manifest |
| Isolated staging preflight | Not run | 需要一次性 Ubuntu/x86_64 staging 主机 |
| First bootstrap and smoke | Not run | 属于部署/测试动作，执行前需要明确确认 |
| Repeated bootstrap | Not run | 首次成功后以同一配置和制品重跑，验证幂等 |
| Status/doctor audit | Not run | 需要检查分层状态、文件权限、hash 与诊断内容 |

首次静态检查发现并修复了 public smoke URL 的字符串闭合错误；修复后完整静态门禁与上述测试均
通过。第一次网络回环尝试被受限沙箱以 `listen EPERM` 阻止，允许仅监听本机 `127.0.0.1` 后原命令
通过，因此该次结果不属于产品失败。

代码准备与本地门禁通过不代表 R3 退出。只有 CI 与隔离 staging 同路径验证均通过后，才能将 R3
标记完成。无论结果如何，都不得据此推断生产部署授权。
