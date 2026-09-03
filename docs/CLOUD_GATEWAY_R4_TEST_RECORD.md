# Cloud Gateway R4 安全升级验证记录

R4 当前处于合同与实现阶段。本文只记录实际执行的结果；未执行的生产或 staging 项不得写成通过。

## 测试影响

| Boundary | Planned evidence |
| --- | --- |
| R3 compatibility | 旧 manifest/config、preflight/bootstrap/status/doctor 全部无回归 |
| Release compatibility | 源/目标 Server、配置、数据库、协议和最低客户端矩阵严格判定 |
| Deploy lock/journal | 并发互斥、同目标恢复、异目标拒绝、损坏状态 fail closed |
| Candidate isolation | 双 unit/container/port、image identity、私有 REST/WSS/Connector smoke |
| Atomic routing | Nginx 临时配置校验、原子 include、reload 后版本确认 |
| Drain/reconnect | 在途请求有界完成，旧槽位停止，Connector 公开入口重连到新槽位 |
| Automatic recovery | 每个部署阶段注入失败后恢复旧路由与旧程序 |
| Rollback | previous 严格选择、旧版本验证、数据库兼容拒绝、完整反向切换 |
| Audit/errors | 操作阶段与结果完整；稳定双语错误码；Secret/路径/正文脱敏 |
| Ephemeral staging | R3 基线 → R4 升级 → smoke → R3 回滚 → smoke |

## 当前状态

| Gate | Result | Evidence |
| --- | --- | --- |
| R4 plan and boundaries | Complete | `CLOUD_GATEWAY_R4_PLAN.md` 已定义双槽位、状态机、恢复矩阵、实施切片与退出条件 |
| R4-A contract | Local pass / PR pending | manifest/config v2、旧 manifest v1 读取、版本兼容矩阵和 `HR-OPS-006` 已实现；本地基线通过，等待 PR CI |
| R4-B candidate path | Not run | 尚未实现 |
| R4-C switch/drain | Not run | 尚未实现 |
| R4-D rollback | Not run | 尚未实现 |
| Fault-injection suite | Not run | 尚未实现 |
| Ephemeral upgrade/rollback | Not run | 尚未实现；执行前不得触碰香港生产服务器 |
| Production deployment | Not authorized | R4 计划与代码工作不构成生产授权 |

## 仓库门禁说明

2026-09-03 实测当前 GitHub Free 私有仓库无法启用 branch protection，GitHub API 返回需升级 Pro
或把仓库公开。项目保持私有且不购买套餐；合并门禁暂由 `AGENTS.md` 要求集成代理在合并前逐项确认
PR checks，并在合并后复核 `main`。这属于流程约束，不应被描述为平台强制保护。

## R4-A 本地证据

2026-09-03 在 `codex/cloud-gateway-r4-contract` 执行：

- `npm run build`：通过。
- `npm test`：通过；Gateway 常规套件 53 项中 42 项通过、11 项按环境门禁跳过；脚本套件 30 项
  全部通过，其中包含 R4 manifest/config 严格解析、旧 schema 1 兼容、版本矩阵和错误码测试。
- `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway`：在允许 loopback listener 的本机权限下
  通过；53 项中 50 项通过，仅 3 项需要一次性 PostgreSQL 的测试按既有门禁跳过。
- `git diff --check`：通过。

本切片未修改 Android、Desktop、Connector 协议或公开路由，未生成 APK，未运行 PostgreSQL
迁移，也未访问香港服务器。R4-A 只有在 PR checks 与合并后 `main` 复核均通过后才记为 Complete。
