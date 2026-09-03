# Cloud Gateway R0 本地测试记录

日期：2026-09-02

分支：`codex/cloud-gateway-r0`

基线：`fb253f4`（Account mode 与 Desktop checkpoint）

## 本次范围

- 仅重构 Gateway 内部结构，不改变 Protocol、公开 URL、鉴权模式或错误语义。
- 抽离配置、HTTP Router、ConnectorRegistry 接口与内存实现、Command/HTTP/WebSocket Broker、
  生命周期事件 HTTP handler 和传输工具。
- 新增 Legacy REST、流式响应、WebSocket、控制通道、容量、超时和断连行为刻画。
- 未修改 Android、Desktop、Connector 或数据库 schema；未生成客户端制品；未部署或重启服务。

## 测试影响复核

| 变化边界 | 对应验证 |
| --- | --- |
| 配置解析与 Secret 文件 | 默认值、文件去空白、非法端口/Secret/TLS 配置单元测试 |
| Connector 查找与替换 | Legacy/Account 隔离、条件删除、routing key 单元测试 |
| HTTP Router 与 REST Broker | 鉴权、离线、请求/响应 header allowlist、完整与流式响应、ACK、容量、超时、断连回环测试 |
| WebSocket Broker | 未授权 upgrade、双向帧、Connector close code/reason、传输工具测试 |
| Command Broker | command/event 所有权、连续事件与 Connector 断连错误回环测试 |
| Account 路由 | PostgreSQL 并发/恢复测试与 V2 Connector 真实网络路由测试 |

## 已执行结果

| 命令 | 结果 |
| --- | --- |
| `npm run build` | 通过：Protocol、Gateway、Connector TypeScript 构建 |
| `npm test` | 通过：102 项；10 项环境门禁测试跳过 |
| `RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | 通过：42 项；3 项 PostgreSQL 测试在无数据库的首轮跳过 |
| `ACCOUNT_TEST_DATABASE_URL=<temporary-local-pg18> RUN_NETWORK_TESTS=1 npm test -w @hermes-remote/gateway` | 通过：45 项；0 跳过 |
| `git diff --check` | 通过 |
| 新文件尾随空白与常见 Secret 模式扫描 | 通过 |

## 数据库门禁与清理

数据库门禁使用本机 PostgreSQL 18.6 和 `/private/tmp` 下的独立临时数据目录。测试完成后 PostgreSQL
进程已停止，临时数据目录已删除，随机测试端口不再监听。没有连接或修改生产数据库。

R0 本地合并前门禁已全部通过。生产部署、服务重启和公网流量切换不属于 R0 本地测试，也未获得
授权。
