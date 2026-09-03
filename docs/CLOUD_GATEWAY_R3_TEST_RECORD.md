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
| Repository build/test | Pass | 本地 `npm run build`、`npm test` 通过；PR #4 Node CI 使用一次性 PostgreSQL 和网络回环配置完成全仓测试 |
| Gateway loopback integration | Pass with DB skips | `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway`：50 通过、0 失败、3 项因未提供一次性 PostgreSQL 而跳过 |
| Security scan | Pass | PR #4 的 digest-pinned Semgrep 与 Gitleaks jobs 均通过，未向任务提供仓库 Secret |
| OCI bundle packaging | Pass | PR #4 的 Ubuntu/amd64 Gateway OCI job 从 clean commit 完成 image smoke、archive 与 strict manifest 打包 |
| Cross-component CI | Pass | PR #4 Android 与 Desktop jobs 均通过；R3 未修改移动端或桌面端代码 |
| Ephemeral staging workflow | Pass | [Run 33732504406](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/33732504406)：手动 Ubuntu 24.04 x86_64 任务在 1m32s 内完成，无仓库 Secret |
| Isolated staging preflight | Pass | commit `157a2d2047f5a9af168ea12939ec244305bd2afc`、Server `0.2.0`、OCI hash/image/架构、依赖、TLS 与端口检查全部通过；未使用香港生产服务器 |
| First bootstrap and smoke | Pass | 全新受管目录完成安装；真实 Nginx HTTPS/WSS、Connector、模拟 Hermes、鉴权 REST 与 session WebSocket 全链路通过 |
| Repeated bootstrap | Pass | 同一配置和制品第二次执行成功，`resumedFrom=complete`，证明完成状态幂等 |
| Status/doctor audit | Pass | status 就绪；doctor 创建成功且 `serviceReady=true`；doctor/audit 均为 `0600`，白名单策略、四条操作序列及三个 Token 不泄漏检查通过 |

首次静态检查发现并修复了 public smoke URL 的字符串闭合错误；修复后完整静态门禁与上述测试均
通过。第一次网络回环尝试被受限沙箱以 `listen EPERM` 阻止，允许仅监听本机 `127.0.0.1` 后原命令
通过，因此该次结果不属于产品失败。

首次临时 staging [Run 33731431885](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/33731431885)
已成功完成 preflight 和两次 bootstrap，但测试 harness 从嵌套打包输出中读到了两条相同版本号，
在候选比较阶段安全失败。PR #6 将版本与 commit 解析限定为最后一条规范记录并增加回归断言；随后
Run 33732504406 在 `main` 的合并提交上完成全部门禁。

至此 R3 的代码、本地/CI、不可变 bundle、全新隔离主机安装、重复 bootstrap、端到端 smoke、状态
和脱敏诊断门禁均已完成，达到 R3 退出条件。该结果只覆盖一次性主机上的同机真实 TLS/WSS 路径，
不代表公网 DNS、移动运营商网络或独立主机网络质量已经验证，也不构成香港生产部署授权。PR #4、
#5 和 #6 的相关检查在 2026-09-03 全部通过。
