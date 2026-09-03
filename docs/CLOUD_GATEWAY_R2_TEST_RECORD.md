# Cloud Gateway R2 Server Release 记录

R2 冻结 Gateway `0.2.0` 的可部署制品合同，但不授权或执行生产部署。它在 R1 的模块化运行时之上
增加构建身份、配置 schema、数据库兼容矩阵、能力元数据以及分离的存活/就绪探针。

## 制品合同

- `gateway/release-contract.json` 是发布合同源，声明 manifest/config/database schema 版本、协议版本、
  最低客户端版本和已认证的 PostgreSQL 主版本。
- `npm run build -w @hermes-remote/gateway` 清理旧 `dist`，编译代码，把配置 schema、显式数据库迁移
  工具和 SQL 收入 `dist`，并生成 `dist/release-manifest.json`。Manifest 记录 Server 版本、源提交、
  dirty 状态、构建时间以及每个制品文件的 SHA-256。
- Gateway 启动时校验 manifest 结构和全部列出的文件哈希。正式镜像入口
  `scripts/package-gateway-image.sh` 拒绝 dirty worktree，将提交和提交时间注入 OCI label 与 manifest，
  并输出内容寻址的 image ID。
- 账号数据库迁移版本为 `7`；`007_gateway_schema_state.sql` 建立单例 schema 版本记录。迁移仍只能通过
  显式命令执行，Gateway 启动不会修改数据库。

## 运行时端点

| Endpoint | 合同 |
| --- | --- |
| `GET /healthz` | 只证明进程及 HTTP loop 存活，不访问下游。 |
| `GET /readyz` | 配置已在监听前通过；账号模式关闭时立即 ready，开启时要求数据库可连接、PostgreSQL 18 且 schema 精确为 7。只返回有界状态，不泄漏连接串或异常。 |
| `GET /v2/capabilities` | 保留原 capability 字段，增量公开 Server、协议和最低客户端版本；功能仍按 capability 判断。 |
| `GET /internal/version` | 仅配置 `INTERNAL_STATUS_TOKEN`/`_FILE` 后启用，并要求 Bearer Token；返回完整非敏感构建身份、兼容信息与 capability，不返回文件清单或 Secret。 |
| `GET /health` | 原有 Android/运维兼容响应保持不变。 |

## 兼容矩阵

| 项目 | R2 合同 |
| --- | --- |
| Server | `0.2.0` |
| Legacy protocol | `1` |
| Account Connector protocol | `2` |
| 最低 Android | `0.1.0`；现有 token 模式不变 |
| 最低 Desktop | `0.2.0` |
| 最低 Connector | `0.1.1` |
| PostgreSQL | 主版本 `18`（账号模式启用时） |
| Database schema | `7` |
| Node.js | 仓库及镜像合同保持 Node 20+；候选镜像使用 Node 22 Alpine |

## 测试影响与门禁

R2 新增的主要失败边界是制品被修改、内部版本鉴权失败、数据库不可达、PostgreSQL 版本不受支持和
schema 版本不匹配。它们由启动完整性测试、endpoint 单元/回环测试、readiness 状态测试和一次性
PostgreSQL 集成门禁覆盖。Readiness 只暴露固定枚举，因此不新增面向最终用户的 `HR-*` 错误码；
底层异常、数据库 URL 和凭证不会进入响应。

## 本地验证结果

| Gate | Result | Evidence |
| --- | --- | --- |
| Gateway release manifest/unit tests | Pass | 53 个 Gateway 测试中的 manifest 结构、文件篡改、内部鉴权、readiness、配置 schema 测试通过 |
| Legacy loopback | Pass | `RUN_NETWORK_TESTS=1` 下 REST、流式 HTTP、App WebSocket、控制通道、生命周期与新增 release endpoints 全部通过 |
| PostgreSQL migration replay | Pass | 一次性 PostgreSQL `18.6` 上 7 个迁移连续执行两次成功 |
| CI compatibility | Pass | GitHub Actions PostgreSQL service 已与 R2 认证矩阵统一为带固定 multi-platform digest 的 `postgres:18-alpine` |
| Account/database loopback | Pass | PostgreSQL `18.6` + schema `7` 下 Gateway `53/53`，无跳过；包含账号路由、绑定隔离和真实 `/readyz` |
| Repository baseline | Pass | `npm run build && npm test` 通过；Protocol 13、Connector 13、Gateway 本地基线 42、Release Server 30、脚本 15 个测试通过 |
| Dirty source release guard | Pass | `scripts/package-gateway-image.sh` 在当前开发 worktree 进入 Docker 前按预期拒绝打包 |
| OCI image build | Pass | GitHub Actions `Gateway OCI` run [33715957149](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/33715957149) 在 Ubuntu 24.04/x86_64 从 clean merge candidate 构建 Gateway `0.2.0`；镜像身份、版本、amd64 架构和 release manifest 校验通过，image ID 为 `sha256:c5700584d233bbaccbdb61e30d6c04d9df745de02ec95d96fc9699a7eb39e82f` |
| Isolated Node candidate | Pass | Mac mini 独立回环端口上的 clean `main` 制品通过真实 Hermes REST/WSS 冒烟，未切换公网路由 |
| OCI staging deployment | Pass | 同一 image 在隔离 Ubuntu Runner 上以只读、无 capabilities、有界资源配置启动；临时 Connector + Mock Hermes 的鉴权、健康、版本、REST、WSS/session 冒烟通过 |

因此 R2 的代码、本地门禁、clean Linux OCI 构建和同一 image 的隔离 staging 验证均已完成，达到
Server Release 退出条件。全新主机可重复安装属于 R3，候选实例切换、排空和回滚属于 R4。生产部署、
服务重启、Nginx 切换和 Android/Desktop 发布均未执行。

## 2026-09-03 隔离候选验证

- 候选源为 `main@c4909cc042d143d7133e934bb3da3f0d00ea38f5`；该提交包含 Cloud R2 合并提交
  `73bfb47`，且两者之间没有 Gateway、Protocol、Connector、deploy 或 scripts 变更。对应 `main`
  的 GitHub CI 与 SAST 均通过。
- 候选在 Mac mini 的私有临时目录构建，`npm ci --ignore-scripts` 报告 0 个依赖漏洞；Protocol、
  Gateway 和 Connector 编译通过。严格制品校验输出 Gateway `0.2.0`、上述 source commit、
  `dirty=false` 和 150 个受校验文件。
- Gateway 仅监听 `127.0.0.1:18787`，使用每次随机生成且未落盘的 App、Connector 和内部状态 Token；
  `ACCOUNT_AUTH_ENABLED=0`、`ACCOUNT_BINDING_ENABLED=0`。`/healthz`、`/readyz`、capability 与受保护
  version endpoint 均符合 R2 合同，错误 App Token 返回 401。
- 临时 Connector 使用现有 Mac 本地 Hermes 认证边界连接真实 Hermes `0.20.6`。通过候选 Gateway
  的 `/api/status` 返回 overall `ok`，`/api/ws` 收到 `gateway.ready`，`session.create` 返回成功结果。
- 验证前后 `https://mrlgs.net/relay-health` 均报告线上 `mac-mini` Connector 在线。测试完成后候选
  Gateway/Connector 已停止，`18787` 端口释放，临时源码、依赖、状态和随机 Token 均已删除。
- 这项证据验证 Node 制品和真实单机链路，但没有覆盖 Linux/x86_64、OCI image ID、候选实例切换、
  排空或回滚；不得据此把 R2 的 OCI staging 退出门禁标为通过。

## Linux OCI 自动门禁

`Gateway OCI` 工作流为 Gateway、Protocol、Connector、Dockerfile 或相关发布脚本的变更构建
Ubuntu/x86_64 候选镜像。基础 Node 22 Alpine image 以官方 multi-platform digest 固定；构建继续要求
clean source、显式 commit identity 和 release manifest 全文件校验。工作流不接收仓库 Secret，也不登录
或推送镜像仓库。

门禁以只读根文件系统、临时有界 `/tmp`、移除全部 capabilities、`no-new-privileges`、CPU/内存/PID
上限运行刚构建的同一 image。Runner 上的临时 Connector 通过 Mock Hermes 验证错误 App Token、
`healthz`/`readyz`、capability、受保护 version、REST 和 WebSocket `gateway.ready`/`session.create`。
失败使用 `HR-RELEASE-001` 至 `HR-RELEASE-003` 的双语、可重试、脱敏结构。

## 2026-09-03 Linux OCI 验证结果

- PR `#3` 的首次 `Gateway OCI` run [33715957149](https://github.com/guoshengliang-coder/hermes-remote/actions/runs/33715957149)
  在 GitHub 生成的 clean merge candidate
  `d42df804fa7d8109ed50991623dbdc73d85de566` 上通过；对应功能提交为
  `babc06c`。工作流没有接收 Secret、登录镜像仓库或推送镜像。
- Gateway `0.2.0` 候选 `hermes-remote-gateway:0.2.0-d42df804fa7d` 的 image ID 为
  `sha256:c5700584d233bbaccbdb61e30d6c04d9df745de02ec95d96fc9699a7eb39e82f`；构建和运行验证均指向
  同一 image，日志输出 `GATEWAY_OCI_SMOKE_OK` 与 `GATEWAY_OCI_RELEASE_OK`。
- 隔离运行验证了 `/healthz`、`/readyz`、`/v2/capabilities`、受保护 `/internal/version`、错误 App
  Token 的 401、经 Connector 转发的 `/api/status`，以及 `/api/ws` 的 `gateway.ready` 和
  `session.create` 成功结果。
- Runner 验证结束后临时容器、Connector、Mock Hermes、随机 Token 和状态目录均被清理；没有部署、
  重启或切换生产服务。
