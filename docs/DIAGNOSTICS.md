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
| `[lifecycle] run.completed s=<id> late=124s` | inbox 事件比发生时刻晚了多久（手机时钟 − Mac 时钟） | 26% 的完成 >30s |
| `[history] reconcile s=<id> rejected: assistantTurns 0<1` | 对账为何拒绝某次快照 | 阶梯每一档 |
| `[event] buffered … / replaying N buffered event(s)` | 别名未建立时事件被缓冲、随后重放 | Mac 端发起的运行 |
| `[session] probe <id> failed (n)` | 探测失败次数 | 网络差 / Mac 失联 |
| `[ws] opening socket (gen=N)` / `socket closed (gen=N): …` | socket 生死 | 每次重连 |

判读：
- 列表卡「思考中」但没有任何 `→COMPLETED_UNREAD` / `→IDLE` 行 → 终止信号一条都没到，去第 2 问。
- 有 `[lifecycle] … late=600s` → 手机睡着了，不是服务端慢；去看 `[ws] socket closed` 是否早于结束时刻。
- 有 `rejected: …` 连续四档 → REST 落后于本地，看第 1 问的 `finish_reason` 是否已经 `stop`。

## 第 2 问：事件丢在哪一层（HK 网关主机）

```bash
ssh kkk@mrlgs.net
```

**网关自己的 journal 只有起停行，没有事件日志**（截至 0.1.97；结构化日志在计划中）。真相在两处：

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
