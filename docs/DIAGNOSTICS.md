# 排查手册（DIAGNOSTICS.md）

> For agents: the incident runbook for Hermes GO session-state problems, in Chinese (the product
> owner's working language, like `docs/DESIGN.md`). It turns the 2026-09-05 HG-6/7/8 reconstruction
> — a day of cross-referencing four data sources — into a ten-minute procedure. Read it before
> touching any log or database on the production hosts; every step here is read-only.

「列表说思考中、聊天页空白」「跑完了气泡还在转」「重连后等待态变成思考中」——这一类问题永远归结为
**三个问题**。按顺序回答，每一步都写明去哪里看、看什么。

## 三个问题，三个数据源

| # | 问题 | 谁知道答案 | 在哪 |
|---|---|---|---|
| 1 | T 时刻 Mac 上这一轮**到底在不在跑**？ | Hermes 自己的数据库 | Mac mini `~/.hermes/state.db` |
| 2 | 事件 X **手机收到了吗**？没收到丢在哪一层？ | 网关的 inbox 记录 + 边缘 Nginx | HK `lifecycle-events.json`、`hermes-edge.access.log` |
| 3 | T 时刻**手机自己认为**的状态是什么？ | 设备诊断日志 | App 设置 → 诊断（0.1.97 起有 `[phase]` 行） |

**先 3，再 2，最后 1。** 设备日志能直接给出"App 何时、因为什么把状态改成了什么"；答不上再往上游走。

## 第 0 步：拿到会话 id

会话 id 形如 `20260905_102612_6d5fd4`（创建时间 + 6 位散列）。来源：诊断页的会话筛选芯片；
或 Mac mini 上按标题查：

```bash
sqlite3 "file:$HOME/.hermes/state.db?mode=ro" \
  "SELECT id, title, datetime(started_at,'unixepoch','+8 hours') FROM sessions WHERE title LIKE '%关键词%' ORDER BY started_at DESC LIMIT 3;"
```

## 第 3 问：手机认为什么（设备日志）

前提：用户在 设置 → 诊断 打开了「诊断日志」并复现了问题（日志跨进程死亡保留 7 天；默认关闭，
关着时零开销）。让用户在诊断页选中该会话的芯片、点「分享」，得到的就是这一条会话的全部行。

要看的行：

| 行 | 含义 | 该出现的时机 |
|---|---|---|
| `[phase] s=<id> A→B gen=… streaming=N cause=…` | 状态机每次可见变化及**原因** | 每轮十几到几十条 |
| `cause=event:message.complete` | 完成信号走了实时 socket | 正常路径 |
| `cause=lifecycle:run.completed` | 完成信号走了 inbox 补投 | socket 没听到时 |
| `cause=probe:gave-up` / `cause=reconnect` | 兜底探测 / 重连恢复 | 见 §5.4 |
| `[phase] restored N runtime(s) from disk (skipped M already live)` | 冷启动从磁盘恢复了多少条运行状态，以及多少条被先到的实时事件让位 | 每次冷启动一行 |
| `cause=restore` | 这个相位来自磁盘快照，不是来自事件 | 只在冷启动；**之后应当紧跟一条 `cause=reconnect` 或 `[session] probe`** |
| `[lifecycle] run.completed s=<id> late=124s` | inbox 事件比发生时刻晚了多久（手机时钟 − Mac 时钟） | 26% 的完成 >30s |
| `[history] reconcile s=<id>: N messages, accepted=false` | 对账为何拒绝某次快照 | 阶梯每一档 |
| `[event] buffered … / replaying N buffered event(s)` | 别名未建立时事件被缓冲、随后重放 | Mac 端发起的运行 |
| `[session] probe s=<id> failed (n)` | 探测失败次数 | 网络差 / Mac 失联 |
| `[ws] opening socket (gen=N)` / `socket closed (gen=N): …` | socket 生死 | 每次重连 |
| `[ws] socket upgraded (gen=N)` | HTTP 升级完成，此后在等 `gateway.ready` | 每次连接 |
| `[ws] state A → B` | 连接状态每一次转换，**含恢复方向** | 每次变化 |
| `[ws] snapshot state=… gen=… manuallyClosed=… readyGate=… watchdog=… socket=… connectingFor=… sinceReady=…` | 横幅升起或提交报告时，socket 内部状态的全量读数 | 只在异常时 |
| `[error] handshake timeout (gen=N): no gateway.ready in 20000ms` | socket 接上了但网关始终没发 `gateway.ready`，看门狗把它拆掉重连 | 见下 |
| `[ws] handshake watchdog skipped (gen=N): <原因>` | 看门狗到点却没动手，以及是哪条 guard 拦下的 | 罕见；出现即异常 |
| `[ws] ws ticket minted` / `ws ticket refused: HTTP …` / `ws ticket failed: …` | 网关票据环节（票面本身永不入日志） | gated 模式每次连接 |
| `[ws] ws endpoint discarded (gen=N): <原因>` | 票据拿到时这一代已经作废 | 竞态 |
| `[ws] reconnect scheduled in Nms (gen=N, attempt=N)` | 退避已排期 | 每次断开 |
| `[ws] reconnect dropped (gen=N): <原因>` | **排期的重连没有执行，以及为什么** | 见下 |
| `[ws] close() requested: <理由>` / `cancelNow()` | App 主动关闭，以及是哪一个调用方 | 退后台 / 关通知 |
| `[ws] opening socket refused: closed by the app` | 已关闭的客户端拒绝重新进入 `Connecting`（HG-42 的入口守卫） | 竞态；出现即说明守卫拦住了一次 |
| `[ws] connect() forcing a fresh socket — stalled: <snapshot>` | 一个超过握手超时仍 `socket=none` 的 `Connecting` 被强行换掉 | 罕见；出现即异常，见下 |
| `[health] <上一档> → <这一档>` | `/api/status` 探测的结论变化（`healthy` / `unreachable` / `device-offline`），红条就由它驱动 | 只在换档时 |
| `[session] upstream reclaimed <id>; next send will recover` | 上游把这个会话回收了（`event session.reclaimed`），它之后的任何 prompt 都会失败 | 掉线超过 120s 后重连 |
| `[session] recreated <旧id> as <新id> → handle=…` | 被回收的空会话已被静默换成新会话，消息照常送达 | 承接上一行 |
| `[ws] rpc#N session.create ← ok (…ms)` | 会话确实建出来了。**只有 `session.create` 记回包**，别的方法成功时不记 | 每次新建 |
| `[lifecycle] app foregrounded` / `app backgrounded` | 前后台切换 | 每次 |
| `[lifecycle] monitoring mode <MODE>` | 保活策略每次选定的模式 | 每次变化 |

**只有 `cause=restore` 而始终没有后续的 `cause=reconnect` / `[session] probe` 行**，说明恢复出来的状态
从未被对账过——手机整段时间离线，或该会话的 deviceId 不在当前传输路由上。此时行内显示的是**最后已知**
状态，不是此刻的真相；判断前先确认这一点（HG-31）。

**握手停滞（HG-19）**：`Connecting` 只有两个出口——收到 `gateway.ready`，或 socket 死掉。曾经有
第三种情形无人处理：socket 建立了、既不完成握手也不关闭。表现是横幅一直「正在连接 Relay…」、每个
动作各自在 15 秒后报 `gateway readiness timeout`、而 `/api/status` 全程 200（REST 走 HTTP 隧道，
与 WS 控制通道不同路），只有强杀 App 能脱身。0.1.105 起有 20 秒握手看门狗自动拆掉重连。

排查时的判据：找 `opening socket (gen=N)` 之后**既没有 `gateway.ready` 也没有 `socket closed`** 的
那个 gen——那就是停滞的 socket。它后面有没有 `socket upgraded` 决定了责任方：有，是网关接了升级
然后不出声；没有，是这一端根本没拨通（再往前看 `ws ticket …` 那几行，票据环节卡住是同一种沉默）。

看到 `handshake timeout` 说明看门狗接管了这一次。**这不等于问题结束**：看门狗只是让 `Connecting`
循环起来，网关一直不出声时横幅仍然一直是「正在连接 Relay…」。

**看门狗自己没出声怎么办（HG-27）**：如果那个 gen 后面连 `handshake timeout` 都没有，说明看门狗
没有动手，去找 `handshake watchdog skipped (gen=N)` —— 它会写明是哪条 guard 拦下的。两条都没有，
就是协程体没跑到，这时 `[ws] snapshot` 那一行（横幅升起时写的）给出当时的全部内部状态。
HG-27 就停在这里：那一版还没有这些行，四处缺陷叠加，只能靠杀进程脱身。

**红条与 socket 是两件事**：顶部粉色的「Relay 暂时无法连接」由 `[health]` 驱动（`/api/status` 探测），
聊天页的「正在连接 Relay…」由 `[ws]` 驱动（WebSocket 状态）。两者可以互相矛盾，而且矛盾本身就是线索：
REST 一路 200 而 socket 卡死，是 HG-19 那一类；socket 已经 `gateway.ready` 而红条还挂着，是探测结果
过期。探测在前台每 30 秒一次、退后台完全停止，所以红条**必须**在 socket 恢复时立刻重探一次，否则它
描述的是上一个坏时刻而不是现在（HG-42）。排查时按时间对齐这两类行：`[health] … → healthy` 应当紧跟在
`gateway.ready` 之后，而不是落后半分钟。

**Connecting 但根本没有 socket（HG-42）**：上面几种停滞里，至少还有一个 socket 或一个看门狗在场。
最后一种什么都没有：`[ws] snapshot` 读作 `state=Connecting … manuallyClosed=true … watchdog=finished
socket=none`，而 `connectingFor` 一路涨到几百秒。这是**已被关闭**的客户端却停在 `Connecting`——
`onSocketClosed()` 需要一个 socket 才会触发，看门狗早已按「closed by the app」退场，于是之后每一次
`connect()` 都被打印成 `connect() no-op — already Connecting`，每一个 RPC 都以
`rpc … blocked: no gateway.ready in 15000ms` 失败。判据就是这三者同时出现：`Connecting`、
`manuallyClosed=true`、`socket=none`。

0.1.124 起这个组合不应再出现：`openSocket()` 会拒绝一个已关闭的客户端（日志写
`opening socket refused: closed by the app`），而一个超过握手超时仍然 `socket=none` 的 `Connecting`
会被下一次 `connect()` 强行换掉，写作 `connect() forcing a fresh socket — stalled: …`。看到后面这行，
说明兜底生效了、而某条路径仍然制造了停滞的 `Connecting`——把那一行连同它前面的 `close() requested`
一起带走，那是定位入口的全部线索。

**连接停下来了但没人说为什么**：`reconnect scheduled in Nms` 之后应当出现下一个
`opening socket`。若换来的是 `reconnect dropped`，那一行会说明是 App 主动关闭（对应前面的
`close() requested: …`，多半是退到后台，属正常省电）还是被更新的一代顶掉。两者都没有、日志就此
停住，才是真的异常。

**消息发不出去、点重试也没用（HG-29）**：先找 `event session.reclaimed session=<id>`。有这一行，
答案就结束了 —— 上游的孤儿回收器（掉线 120 秒后触发，见第 1 问的 `ws_orphan_reap`）已经把这个会话
收走，之后 `prompt.submit` 必答 4001、跟着的 `session.resume` 必答 4007。两者在日志里都写作
"session not found"，但含义不同：**4001 是 live handle 过期（resume 一次就好），4007 是持久会话在
该 profile 的 state.db 里根本不存在（终态，重试永远不会成功）**。0.1.119 起客户端会处理这个事件：
空会话静默重建（看 `recreated … as …`），有历史的会话报终态 `SESS-001` 且不再给重试。

顺带一个**不是**故障的现象：新会话在首条消息落库前，`GET /api/sessions/<id>/messages` 一直返回
404 `{"detail":"Session not found"}`，首条消息发出后立刻变 200。这不代表 create 失败。0.1.119 起
这种 404 记在 `[history]` 而不是 `[error]`，也不再弹「无法加载历史消息」。

判读：
- 列表卡「思考中」但没有任何 `→COMPLETED_UNREAD` / `→IDLE` 行 → 终止信号一条都没到，去第 2 问。
- 有 `[lifecycle] … late=600s` → 手机睡着了，不是服务端慢；去看 `[ws] socket closed` 是否早于结束时刻。
- 有 `rejected: …` 连续四档 → REST 落后于本地，看第 1 问的 `finish_reason` 是否已经 `stop`。

## 第 2 问：事件丢在哪一层（HK 网关主机）

```bash
ssh kkk@mrlgs.net
```

网关 0.4.1 起写结构化 JSON 行（`GATEWAY_LOG_LEVEL`，默认 info）。**先看它**，答不上再看 2a/2b。
注意两点：① R5-D 接管后网关是容器，日志跟着**活动槽**走（`readlink /opt/hermes-go/current` 加
committed journal 能告诉你哪个槽在线；下面以 blue 为例，绿槽把容器名换掉即可；同名 systemd 单元的
journal 是同一份），旧的 `hermes-remote-gateway` 单元已停，只剩接管前的历史；② 线上跑的 0.4.0 镜像
还没有这些行，要等 0.4.1 经 R5-F1 常规发版路径上线（见 docs/DEPLOYMENT.md）。

```bash
sudo docker logs --since 2026-09-05T10:20:00Z hermes-go-gateway-blue 2>&1 | grep <会话id>
```

> **先确认版本，否则你会对着空日志找原因。** 结构化日志是 Gateway **0.4.1** 才有的，而 HK 生产
> 至今仍跑 **0.4.0**（`docs/DEPLOYMENT.md` 记着 0.4.1 需要一轮 ops 才能上）。2026-09-07 实测：容器
> 运行 42 小时，全部输出只有一行启动日志——**这一节在 0.4.1 部署前拿不到任何证据**，不是查得不够。
> 版本看 `sudo docker inspect hermes-go-gateway-blue --format '{{index .Config.Labels "org.opencontainers.image.version"}}'`。
>
> 单元名也变了：本文早先写的 `hermes-remote-gateway.service` 今天是 **inactive**，真正在跑的是
> Docker 容器 `hermes-go-gateway-blue`（`journalctl -u hermes-go-gateway-blue` 只有启动行，日志在
> 容器里）。`docs/DEPLOYMENT.md` 的对应命令尚未更新。

| kind | 回答什么 |
|---|---|
| `app.tunnel.open` / `app.tunnel.close` | 手机 socket 何时开、何时关、关时连接器是否在线、双向各跑了多少帧——"终止事件发出时有没有人在听"就看这两行 |
| `lifecycle.received`（`lagMs`） | 连接器→网关的延迟（实测恒 ≤1s） |
| `lifecycle.served` / `lifecycle.acked` | 手机何时来取、取到了哪几条、何时确认——`received` 到 `served` 的间隔就是手机没来取的时间 |
| `http.tunnel` | 每次 REST 隧道：路径、状态、耗时；同一秒多次 `/messages` = 对账阶梯 |
| `connector.online` / `connector.offline` | Mac 侧连接器上下线 |

连接器侧（Mac，`~/Library/Application Support/Hermes Remote/connector.log`，`CONNECTOR_LOG_LEVEL`）
是**唯一能看到 Hermes 事件类型**的地方：`tunnel.frame` 在每个终止事件（`message.complete` /
`session.info` / `error` / `approval.request` / `clarify.request`）经过时记一行，带它走的 tunnel id；
`tunnel.close` 带 `lastTerminal`。"运行 10:31:08 结束、承载它的 tunnel 10:31:02 已关"这句话，
从这两行直接读出。

部署此版本之前的时间段仍然只有起停行，退回 2a/2b：

### 2a · inbox 记录：服务端延迟 vs 手机没来取

```bash
sudo -n python3 - <<'PY'
import json,datetime
d=json.load(open("/var/lib/hermes-remote/lifecycle-events.json"))["events"]
SID="20260905_102612_6d5fd4"
for r in d:
    e=r["event"]
    if SID not in str(e.get("storedSessionId")): continue
    print(r["sequence"], e["event"], "occurred", e["occurredAt"][11:19], "recv", r["receivedAt"][11:19], "deliv", (r.get("deliveredAt") or "-")[11:19])
PY
```

时间全是 **UTC**（+8 = 北京）。`receivedAt − occurredAt` 恒 ≤1s 说明连接器→网关没有延迟；
`deliveredAt − receivedAt` 大就是**手机没来取**（Doze），不是服务端慢。`deliveredAt` 为空 = 至今没投递。

### 2b · Nginx：socket 生死与轮询节律

```bash
sudo -n grep "GET /api/ws" /var/log/nginx/hermes-edge.access.log | awk '{print $4, $9, $10" bytes"}'
```

WS 行是**连接关闭时**记录的，第 10 列是该连接累计字节。一条 868 KB、关闭于 10:31:02 的连接，
就是"承载了整轮、在结束前 6 秒断掉"的证据。

```bash
sudo -n grep "GET /api/mobile/events" /var/log/nginx/hermes-edge.access.log | grep -v ack \
  | grep -oE "05/Sep/2026:1[0-2]:[0-9]{2}" | cut -d: -f2,3 | uniq -c
```

每分钟轮询计数：活跃 1–2 分钟、静默 9–16 分钟的形态 = Android Doze。静默期内发生的完成，
手机只能在下一个活跃窗口知道。

```bash
sudo -n grep "sessions/<id>/messages" /var/log/nginx/hermes-edge.access.log | awk '{print $4, $9}'
```

历史拉取时刻。同一秒内 4–5 次 = 对账阶梯（250/1000/3000/10000 ms）或前台恢复（0/250/750/1500 ms）。

`error.log` 里若有 `upstream timed out` 才是 Nginx 掐了 socket（`/api/` 的 `proxy_read_timeout` 75s）；
2026-09-05 那次没有。

## 第 1 问：Mac 上的真相（Mac mini）

```bash
ssh -i ~/.ssh/hermes_macmini_ed25519 bs@100.119.73.80
```

只读打开（**不要**碰 WAL、不要写）：

```bash
sqlite3 -header "file:$HOME/.hermes/state.db?mode=ro" \
 "SELECT id, role, datetime(timestamp,'unixepoch','+8 hours') ts, coalesce(finish_reason,'') fin, length(coalesce(content,'')) clen, length(coalesce(reasoning_content,'')) rlen
  FROM messages WHERE session_id='<id>' AND role IN ('user','assistant') AND active=1 ORDER BY id;"
```

- `finish_reason='stop'` 的那一行的 `ts` 就是**这一轮真正结束的时刻**；`tool_calls` 行是中间步。
- `sessions.end_reason`：`ws_orphan_reap` = 被 120s 孤儿回收器收走（目前是移动端会话的主导终止方式，
  不算异常）。
- `reasoning_content` / `tool_calls` 列有值 → REST 会原样返回，App 端若看不到是 DTO 没建模
  （`Dtos.kt`，`ignoreUnknownKeys` 会静默吞字段）。
- `~/.hermes/sessions/sessions.db` 是 0 字节空壳，别看。

## 把三方对齐

按同一时间轴列表：DB 的 `stop` 时刻、inbox 的 `occurred/recv/deliv`、Nginx 的 socket 关闭时刻、
设备 `[phase]` 行。2026-09-05 的对齐结果作为范例：

| CST | DB | inbox | 手机 |
|---|---|---|---|
| 10:31:02 | | | Nginx：承载该轮的 socket 关闭 |
| 10:31:08 | `stop` | `run.completed` occurred 10:31:09、recv 10:31:08 | |
| 10:33:13 | | deliv 10:33:13 | ack + 对账突发；气泡此时仍「生成中」→ HG-6 |

对齐后如果**每一层都对、只有手机显示错**，是状态机问题（0.1.93–0.1.96 修的那一类）；
如果**手机根本没收到**，是传输问题（Doze / FCM 那条线）。

## 不要做的事

- 不要用带鉴权的方式打 dashboard 端点，也不要在会话里处理任何凭据；DB 只读 + 读源码足够。
- 不要在集成 worktree 里改任何东西；排查是只读的。
- 不要把服务端时间和手机时间直接相减当作服务端延迟：`late=` 已经包含了两台设备的时钟差，
  只能作量级判断。

相关：`docs/SMOKE_TEST.md`「Session state consistency」一节、`docs/DESIGN.md` §5.4 / §5.15。
