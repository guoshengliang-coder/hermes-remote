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
| Account/database loopback | Pass | PostgreSQL `18.6` + schema `7` 下 Gateway `53/53`，无跳过；包含账号路由、绑定隔离和真实 `/readyz` |
| Repository baseline | Pass | `npm run build && npm test` 通过；Protocol 13、Connector 13、Gateway 本地基线 42、Release Server 30、脚本 13 个测试通过 |
| Dirty source release guard | Pass | `scripts/package-gateway-image.sh` 在当前开发 worktree 进入 Docker 前按预期拒绝打包 |
| OCI image build | Not run | 当前开发机没有 Docker CLI；镜像构建和 image ID 验证留给具备 Docker 的 clean CI/staging 主机 |
| Staging deployment | Not run | 需要同一 OCI image 的 staging 环境；这是 R2 最终退出门禁，不以本地测试冒充 |

因此 R2 的代码与本地门禁已完成，完整版本退出仍等待 clean commit 上的 OCI 构建及同一 image 的
staging 验证。生产部署、服务重启、Nginx 切换和 Android/Desktop 发布均未执行，也不在本轮授权范围。
