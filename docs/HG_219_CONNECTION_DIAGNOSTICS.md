# HG-219：M4 Mac mini 连接中断排查（2026-10-20）

## 已确认与未确认

下午这批手机 1013 / HTTP 503 的直接原因是 **M4 Connector 与 Gateway 的控制连接因双向心跳
超时被拆除**。这不证明最底层是网络丢包、压缩/发送队列阻塞或事件循环延迟；现有生产日志没有
区分这些原因的字段。本次补齐诊断，**没有修复或验证消除底层中断，也没有调整心跳、重连预算**。

用户在本次会话指定 M4 Mac mini，并反馈现在感觉仍会发生。条目环境为 HONOR MBH-AN10、Android 16、
Hermes GO 0.1.173 (174)。两份日志完整读取（500 / 5249 条记录），图片显示“服务没正常响应”。
上午另有 `net=none`、DNS 失败，不应与下午 Connector 心跳失联合成一个根因。

## 只读核对结果

- Gateway 活动版本为 0.4.33，来源提交 `5438b4ef8cc498a6cda228efb9fd2273de8d0b72`；活动容器
  自 2026-10-11 启动，核对时重启次数为 0。历史部署文档中的 0.4.0 不代表本次线上版本。
- M4 当前托管目录为 `~/Library/Application Support/Hermes Go/Managed/`；运行托管发布 0.4.9 内的
  Connector 0.1.14。Connector 进程自 2026-10-16 启动，故障窗口没有进程重启证据。
  旧 `Hermes Remote/connector.log` 已停止更新，不能用于本次判断。
- 13:40–14:15 的 Gateway 记录包含同一 M4 绑定的 10 次离线和 10 次上线，上线全部 `replaced=false`。
  Gateway 有 7 次 `connector.heartbeat_timeout`；M4 有 4 次本地 heartbeat timeout，其中首轮两端
  都记录超时，因此不能把两个计数相加当作 11 次独立中断，也不能只凭跨主机时间戳断言哪端先触发。
- Gateway 的 7 次超时记录 `silentMs=16243–19685`，配置 `timeoutMs=15000`。
  15:07 又有一次同绑定的 Gateway 心跳超时，随后重连，说明问题不止出现在反馈截取的窗口。
- M4 在 14:12:30.823 仍记录收到 `http.cancelled`，14:12:32.408 自己触发心跳超时；
  其后的重连记录显示 `lastPongAgoMs≈19386`。入站消息还在到达，不能据此推断出站 ping/pong 或
  整条链路已经健康。
- 核对时 Gateway CPU 约 0.06%、内存约 53.6 MiB；M4 负载约 11.8，存在高 CPU 进程。
  一次从 M4 读取公共 `/relay-health` 耗时约 306 ms。这些是当前采样，不能证明故障时网络或 CPU 状态。
  代理进程/系统代理的存在本身同样不能证明 Connector 走了哪条路径，或代理就是根因。

最近两次手机记录可通过 `conn` 对应 Gateway `app.tunnel.open.clientConnectionId`：

| 手机时间（北京时间） | 手机连接 | M4 日志 | Gateway 日志 |
|---|---|---|---|
| 14:10:47.803 | `70b946de-6345-4b36-a92e-65a7733e4516` | 14:10:47.914 本地心跳超时；随后 1006 | 14:10:48.196 离线，手机隧道关闭为 1013 |
| 14:12:32.858 | `cc2cb861-e840-4334-bd3e-81d9caa0d621` | 14:12:32.408 本地心跳超时；随后 1006 | 14:12:33.245 离线，手机隧道关闭为 1013 |

证据来自 HG-219 两份附件，以及只读获取的活动 Gateway 容器日志、M4 托管 Connector 日志和进程元数据。
跨主机时间与回调执行时间不完全一致，表中是各端原始时间，不用于计算精确单向延迟。未读取 Hermes
数据库、会话正文或私密环境文件，未重启、部署或改动任何生产服务。

## 本次诊断改动

Connector 增加结构化 `relay.heartbeat_timeout`；现有 `relay.disconnected` 带同一次本地连接的
`controlConnectionId` 和同样的活动快照。Gateway 扩充现有 `connector.heartbeat_timeout`。
这些记录只包含固定枚举、数值和本地随机连接编号，不包含端点、认证材料、帧正文、ping 数据或异常原文。
快照只在超时/关闭时输出，不逐帧写日志。

| 字段 | 能回答什么 | 不能推出什么 |
|---|---|---|
| `pingWriteState` (`none/pending/written/failed`) | 最近一次 ping 的本地写回调状态 | `written` 不等于已经到达对端 |
| `lastPingQueuedAgoMs` / `lastPingWrittenAgoMs` | 最近 ping 排队/本地写出距今多久 | 不等于远端收到 ping 的时间 |
| `bufferedBytes` | ws 报告仍在本地排队的字节数（含压缩队列） | 单凭此值不能区分压缩、socket 与网络问题 |
| `lastMessageAgoMs` / `lastPingReceivedAgoMs` / `lastPongAgoMs` | 最近不同入站活动距今多久 | 收到普通消息/ping 不会替代 pong 或改变超时策略 |
| Connector `tickDelayMs` / Gateway `sweepDelayMs` | 本次定时回调比配置周期晚了多久 | 单个延迟值不能证明是 CPU、睡眠或其他调度原因 |

未发生过的事件字段省略，而不是伪造为 0。Connector 的相对时间使用单调时钟；Gateway 保留原有
时钟和超时判定，避免以诊断改动悄悄改变恢复行为。异步 ping 写回调只更新对应探针，旧回调不能覆盖
较新的探针。没有新增用户可见错误或 HR 错误码，也没有改变线上协议或客户端要求。

## 后续验证边界

自动化覆盖排队但未写出、已写出但没有 pong、仍有入站流量、计时器延迟、旧回调隔离和敏感信息不入日志。
真实 Connector 子进程对接 loopback Relay：Relay 继续发送合法消息和 ping，却不回复 Connector 的 ping；
必须记录 `written`、最近入站活动以及相同连接编号的断线事件，并沿原有路径重连。

生产尚未安装本次诊断代码。需分别授权 Gateway 和 Connector 发布后，在 M4 自然复发时收集新字段，
再决定修复方向；不能把本 PR、单测通过或一次健康检查当作“连接已修好”。使用独立测试绑定执行故障注入，
不要为诊断而暂停用户当前的 Connector、Hermes 或调整其网络设置。具体步骤见 `SMOKE_TEST.md` 的 HG-219 段。
