# Cloud Gateway 重构迭代计划

本文把 `CLOUD_PRODUCT_REQUIREMENTS_AND_ARCHITECTURE.md` 的 Cloud 路线落实为可独立验证、可回滚的
Gateway 代码迭代。总体方向保持模块化单体；在真实容量或多区域需求出现前，不引入微服务、Redis
或 Kubernetes。

## 不变边界

- Android、Desktop 和 Connector 的公开协议与现有 URL 在结构重构期间保持兼容。
- Mac 始终主动建立出站连接；Hermes 本地凭证只留在 Mac。
- Gateway 只保留完成转发所需的有界内存状态，不持久化聊天正文、流式输出或文件。
- 账号、绑定和生命周期控制面继续以 PostgreSQL 为权威来源；支付不进入 Relay 热路径。
- 每个版本先通过本地及 staging 门禁；代码合并不代表获得生产部署授权。

## 迭代顺序

| 版本 | 范围 | 主要交付物 | 退出条件 |
| --- | --- | --- | --- |
| R0 结构基线 | 拆分当前 Gateway 单文件职责，不改变行为 | 配置加载、HTTP Router、ConnectorRegistry 接口与内存实现、Command/HTTP/WS Broker、生命周期路由模块、Legacy 回归测试 | Gateway 构建、单元测试、真实回环 REST/WSS/控制通道测试通过；Account 数据库测试无回归 |
| R1 运行时组合 | 把进程装配与连接会话状态机分离 | `GatewayRuntime`、Legacy Control Session、Account Connector Session、生命周期消息处理器、统一关闭流程 | 握手超时、替换、撤销、断连、重启恢复和背压测试通过；`index.ts` 只负责装配与启动 |
| R2 Server Release | 对应 Cloud C1，冻结可部署制品合同 | Server 版本、配置 schema、manifest、capability、liveness/readiness、不可变构建制品 | 同一制品能以配置启动在开发与 staging；兼容矩阵和制品校验通过 |
| R3 Cloud Ops 基线 | 对应 Cloud C2 | `preflight/bootstrap/status/doctor`、脱敏诊断、staging 安装流程 | 全新 staging 主机可重复安装并完成端到端 smoke |
| R4 安全升级 | 对应 Cloud C3 | 显式迁移锁、候选实例、蓝绿切换、排空和回滚 | 每个注入失败点都能恢复旧路由与旧程序，生产同路径演练通过 |
| R5 数据收敛 | 对应 Cloud C4 | Legacy 生命周期 JSON 迁 PostgreSQL、资料/偏好存储接口 | PostgreSQL 成为控制面唯一权威存储，迁移可重复且可回滚 |
| R6 可观测与恢复 | 对应 Cloud C5 | 指标、告警、off-host 备份、恢复和诊断包 | 达到内测 RPO/RTO，完成新环境恢复演练与脱敏审计 |
| R7 灰度启用 | 对应 Cloud C6 | account/binding capability 灰度和兼容门禁 | 两手机、第二 Mac、旧客户端、重启及回滚验收全部通过 |

产品统计、官网/商业化、CDN 与区域化继续按 Cloud C7-C9 单独立项，不能提前耦合到 R0-R7 的
实时 Relay 热路径。

## R0 交付边界

R0 只建立可测试的模块边界：

- `ConnectorRegistry` 是业务依赖接口，R0 使用单进程 `InMemoryConnectorRegistry`；多节点归属实现
  留到有明确容量和区域需求时替换。
- Command、REST 和 WebSocket Broker 分别拥有自己的请求/隧道生命周期、超时、断连和背压状态。
- HTTP Router 只负责公开路由、Legacy/Account 鉴权选择和 Relay-owned endpoint 分流。
- 配置解析在创建监听器之前完成并独立测试，Secret 仍只从环境变量或受保护文件读取。
- R0 不改 Protocol，不改 Android/Desktop，不迁移数据库，不生成 APK/DMG，也不部署生产环境。

## 测试与发布策略

每次重构先补行为刻画，再移动代码。R0 至少运行：

```bash
npm run build
npm test
RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway
git diff --check
```

Account PostgreSQL/网络集成测试必须在提交合并前使用一次性数据库执行；没有测试数据库时只能标为
未执行，不能写成通过。R0 完成后先评审和提交分支；若需要部署验证，应部署到 staging 或独立候选
端口。生产升级、服务重启和公网切换仍需用户单独明确授权。

R0 当前的本地验证证据记录在 `CLOUD_GATEWAY_R0_TEST_RECORD.md`。
R1 的运行时与连接会话拆分证据记录在 `CLOUD_GATEWAY_R1_TEST_RECORD.md`。
R2 的 Server Release 合同和本地验证证据记录在 `CLOUD_GATEWAY_R2_TEST_RECORD.md`。
R3 的 Cloud Ops 合同和已完成门禁记录在 `CLOUD_GATEWAY_R3_OPS.md` 与
`CLOUD_GATEWAY_R3_TEST_RECORD.md`。
R4 的安全升级与回滚实施合同、故障注入矩阵和待执行门禁记录在
`CLOUD_GATEWAY_R4_PLAN.md` 与 `CLOUD_GATEWAY_R4_TEST_RECORD.md`。
