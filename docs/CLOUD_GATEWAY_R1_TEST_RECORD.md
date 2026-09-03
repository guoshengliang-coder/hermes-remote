# Cloud Gateway R1 本地测试记录

日期：2026-09-02

分支：`codex/cloud-gateway-r1`

基线：`0f0b0a6`（Cloud Gateway R0 结构基线）

## 本次范围

- 把 Legacy `/v1/connect` 认证与消息会话抽离为独立 Session Handler。
- 把 Account `/v2/connect` identify、challenge、proof、preflight、ready 状态机抽离为独立
  Session Handler。
- 把未认证 Account Connector 的全局/单 IP 容量状态封装为可测试的 admission 模块。
- 把生命周期消息、Peer 注册/替换/断连、App WebSocket 授权分别收敛到独立模块。
- 把 HTTP/WSS listener、upgrade 分流和统一关闭流程收敛到 `GatewayServer`。
- 新增 `GatewayRuntime` 作为依赖装配入口；`gateway/src/index.ts` 仅负责创建、启动和响应信号。
- 不修改 Protocol、Android、Desktop、Connector、数据库 schema 或公开 URL；不部署生产。

## 测试影响复核

| 变化边界 | 对应验证 |
| --- | --- |
| Legacy Control Session | 错误认证、hello ACK、在线状态、Connector 替换、command/event 所有权与断连错误 |
| Account Connector Session | PostgreSQL binding、challenge/proof、preflight、ready、容量限制与 V2 路由集成测试 |
| Admission | 全局上限、单 IP 上限、幂等释放单元测试 |
| Peer Coordinator | Legacy/Account 隔离、替换、HTTP/WS/command 断连清理回归测试 |
| Lifecycle Handler | Legacy durable ACK、Account 生命周期隔离与收件回归测试 |
| Gateway Server/Runtime | HTTP、REST streaming、WebSocket upgrade/frame/close、SIGTERM 测试进程关闭路径 |

## 已执行结果

| 命令 | 结果 |
| --- | --- |
| `npm run build && npm test` | 通过：Protocol、Connector、Gateway、Release Server 与发布脚本基线 |
| `npm test -w @hermes-remote/gateway` | 通过；需要网络/数据库的测试按环境门禁跳过 |
| `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | 通过：44 项；3 项 PostgreSQL 测试在无数据库时跳过 |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-pg18> RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | 通过：47 项；0 跳过 |

数据库门禁使用本机 PostgreSQL 18.6 和 `/private/tmp` 下的独立临时数据目录。测试完成后进程已
停止，临时目录已删除，随机端口不再监听；没有连接生产数据库。

`git diff --check`、最终差异与 Secret 扫描在提交前执行并记录在提交交接中。生产部署、服务重启和
公网流量切换不属于 R1，也未执行。
