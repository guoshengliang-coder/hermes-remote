# HG-244 · 拥塞下的连接、聊天与本机健康

本条目作为一个交付单元推进，以下四阶段不是四个独立发布。新能力默认关闭。实现不改变上游 Hermes，也不替第三方网盘限速；完全占满外网或系统资源时不能保证远程请求成功。

## 1. 协议与状态基础

Gateway、Connector 两端均设置 `CONNECTOR_TRANSPORT_ISOLATION=1` 才协商新模式。Connector 在原 `/v2/connect` 的 HTTP Upgrade 中发送 `X-Hermes-Transport: 1;<business|control>;<instance UUID>;<lane generation>`。Gateway 只对显式请求且开关开启的连接，在 challenge/ready 中回显结构化 `transport`。旧 Gateway 忽略头、返回旧握手；新 Connector 退回单连接，不创建第二条连接。旧 Connector 不发头，新 Gateway 保持原握手、原心跳。

每条物理连接分别完成原有绑定签名认证；响应 nonce 必须属于当前 socket。头部声明在认证前不可信。业务和控制成员绑定同一 binding ID、绑定代次、进程 instance UUID；各角色独立递增连接代次。控制成员不能创建新设备会话。替换控制成员不替换业务 peer 身份；新实例只能通过业务角色接管，旧实例被短期隔离。断开全部成员后保留 10 分钟代次屏障，最多 4096 个会话/退役记录，避免迟到回调复活旧连接。

原独立下载继续使用临时 HTTP 返回凭证、请求 ID、文件引用及 ETag，不新建永久文件 WebSocket。文件错误仍归该次文件操作。

设备列表、单设备详情及绑定 GET 在完成原有账号/设备权限验证后，附加 `connection`：`version=1`、`business`、`control`、`local`、`localReason`、`healthAgeMs`。链路状态为 `normal/suspect/recovering/lost`；本机为 `healthy/degraded/unhealthy/unknown`。旧的 `connector/hermes/endToEnd` 字段保留，未知映射为 null，不伪造成失败。实时链路和新鲜度在 Gateway 内存会话中维护，原数据库摘要不充当新模式的瞬时健康真相；无数据库迁移。

## 2. 拥塞退让与本机探测

| 信号 | 动作 |
|---|---|
| 每 5 秒一次带 nonce 的 ping | 每条物理 socket 至多一个未完成探测 |
| ping 排队超过 2 秒；RTT 连续 3 次超过基线 3 倍/1 秒下限；或一次 RTT 达到 2 秒 | 进入 suspect，降低自有批量流量预算 |
| 15 秒没有匹配 pong | 保留 socket，suspect；不把任意入站消息当作恢复证据 |
| 距最后有效往返达到 45 秒 | 仅重建该物理连接；迟到/重复/错误 nonce 不续命 |
| 连续 3 个低于 2 秒且符合 RTT 基线的有效往返 | 恢复 normal，传输预算逐步回升，约 30 秒从一半恢复到上限 |

时长使用单调时钟。控制通道只接受握手、轻量健康请求/结果和 Gateway 下行取消命令，最大消息 16 KiB，不启用压缩。聊天、上传、历史等业务禁止进入控制通道。

同一 Connector 的独立 HTTP 下载、批量业务分片和上传 ACK 退让共享 `FILE_DOWNLOAD_BYTES_PER_SECOND` 预算。拥塞时预算减半，新文件 range 并发上限降为 1；已在途 range 逐块消耗同一个预算。业务 suspect 持续 10 秒暂停批量生产，恢复/取消可解除等待；控制通道单独 lost 不暂停健康业务链路。消息等待预算时不提前堆入 socket。

新模式对大业务消息使用 32 KiB 应用分片，按请求/隧道轮转；同一隧道消息保持顺序，其他小请求可穿插。队列和重组各限 24 MiB，单消息受原 wire payload 上限约束，最多 128 个流，75 秒没有新分片清理并结束受影响的业务连接。取消跨链路到达时清理对应生产、发送队列和重组；短期 tombstone 处理早到、重复和迟到取消。未知投递的聊天不自动重发。

本机健康监测由 Connector 进程持有，与远程 socket 无关：单飞、每次最多 10 秒；正常完成后 20 秒再查，异常完成后 5 秒复查。第一次普通失败为 degraded；连续至少 3 次且首末失败相隔至少 30 秒才 unhealthy。明确拒绝连接/认证失败单独分类，可立即 unhealthy。检测继续使用轻量 `/api/health` 和有界认证读，旧版本走已有兼容回退；不读取模型输出做健康判断。超过 60 秒没有新观察为 unknown。Gateway 写摘要失败不改写本机观察为失败。

## 3. 生命周期和权限

- 控制丢包、断开或替换：业务 peer、聊天隧道、独立下载及本机监测继续运行，控制单独有界退避重连。
- 业务故障：由业务自己的有效往返判定，不能被健康控制连接掩盖；只在确定 lost/明确 socket 关闭后走原业务清理。现有文件请求的业务请求归属不会跨业务重建偷偷续用。
- 撤权/解绑/代次失效：关闭该绑定的全部成员并取消相关工作；已有手机授权检查保留，绝不把认证失败当拥塞放行。
- 陈旧成员的关闭、pong、健康报告不能删除或更新新成员。接管后的旧进程停止重试，避免双实例来回争抢。
- Gateway/Connector 回滚到单连接时不再发送扩展字段或分片；退让状态复位。回滚期间既有业务断线按原连接恢复语义处理，不承诺聊天无缝续传。

## 4. 客户端、预算核对与发布准备

Android、Desktop、Web 展示拥塞/恢复中、聊天不可用、本机 Hermes 异常和未知。拥塞本身不关闭聊天入口。明确本机失败沿用 `HR-CONN-006`，其他既有认证、连接及文件错误码不变。

协商模式的上传/响应 chunk ACK 和文件返回空闲预算为 75 秒，覆盖 45 秒确认窗口；Android 独立 range 读空闲同为 75 秒，仍保留取消与文件归属校验。Web 文件请求受现有 AbortController/用户取消约束。Android 到 Gateway 的既有 45 秒 WebSocket ping 保留，它检查的是另一段网络。生产 binding Nginx 模板将 `/v2/connect` 的 send/read 统一为 75 秒；部署前必须核对实际生效配置，不能只看模板。上传总预算、普通 REST/聊天 RPC 各自超时继续存在；单请求失败不直接定义整台 Mac 离线。

Desktop 只允许保留 `CONNECTOR_TRANSPORT_ISOLATION` 的 `0/1` 字符串，与已有文件速率一起做严格白名单校验；修复/升级不引入任意 Node 环境变量。没有新 UI 开关。

### 统一验证

- 单元/边界：阈值、三次恢复、nonce 重放、单向活跃、本机失败分类/过期、单飞、预算/取消、业务分片公平性/边界/过期、同角色替换、实例和代次屏障、授权与明确本机失败继续拦截。
- 真实 Connector 回环集成：旧 Gateway 回退为一条连接；大文件期间关闭控制，业务连接与文件 hash 不变；单向丢弃控制 pong，15 秒不关闭、45 秒单独重建；小请求延迟统计不含模型响应。
- 30 分钟可重复本机负载：`HG244_SOAK_MS=1800000 node --test connector/dist/transport-isolation.integration.test.js`，循环传输合成文件、校验 SHA-256 并同时发小请求。这是本机回环/模拟 Gateway 验证，不能代替真实公网、Nginx、手机和百度网盘占满带宽的验收。
- 真实黑洞：`HG244_DROP_CONTROL_PONG=1 node --test connector/dist/transport-isolation.integration.test.js`。
- Android L1 浅色、深色大字号提示和可操作入口；本机无真机/模拟器时 L2/L3 记录为待验。Desktop/Web 跑各自构建与消费者测试。
- 发布后待验：生产真实 Gateway+PostgreSQL、旧/新混部及回滚、双手机竞争、断网/睡眠唤醒、跨端长会话、上传与独立下载同时负载、真实网盘饱和、30 分钟网络整形/外网实测。记录带宽/RTT/丢包、有效往返、排队、文件 hash、请求发送至传输确认 P95 和资源峰值。受控负载目标为新增 P95 ≤500ms；外部饱和单列，不承诺绝对可用。

### 发布与回滚门禁

先合并并发布默认关闭的新 Gateway/Connector/客户端；核对 Nginx、有效版本和权限后，再经授权对指定 Mac 打开两端开关。观察异常分支及上述真机指标后扩量。回滚先关闭 Connector 开关并仅重载 Connector，再关闭 Gateway 能力或回到兼容版本；不动上游 Hermes 的服务、目录与数据库。保留版本、开关、时间、前后状态及回滚验证证据。源码测试通过不等于发布完成或业务验收通过。
