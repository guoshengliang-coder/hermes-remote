// Enhanced mock Hermes for jitter reproduction: implements just enough of the
// session RPC surface and streams an agent-run-shaped answer (prose, fences,
// raw JSON payloads, terminal output) at realistic delta cadence.
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { WebSocketServer } from "ws";

const port = Number(process.env.MOCK_HERMES_PORT ?? 9120);
const expectedUsername = process.env.MOCK_HERMES_USERNAME ?? "demo";
const expectedPassword = process.env.MOCK_HERMES_PASSWORD ?? "secret";
const sessionCookie = "mock-session";
const tickets = new Set();

const json = (response, body, status = 200) => {
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(body));
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (request.method === "POST" && url.pathname === "/auth/password-login") {
    const payload = JSON.parse((await readBody(request)).toString("utf8") || "{}");
    if (payload.provider !== "basic" || payload.username !== expectedUsername || payload.password !== expectedPassword) {
      response.writeHead(401).end();
      return;
    }
    response.writeHead(200, {
      "content-type": "application/json",
      "set-cookie": `hermes_session_at=${sessionCookie}; HttpOnly; Path=/`,
    });
    response.end(JSON.stringify({ ok: true }));
    return;
  }
  if (!request.headers.cookie?.includes(`hermes_session_at=${sessionCookie}`)) {
    json(response, { error: "unauthorized" }, 401);
    return;
  }
  if (request.method === "POST" && url.pathname === "/api/auth/ws-ticket") {
    const ticket = randomUUID();
    tickets.add(ticket);
    json(response, { ticket });
    return;
  }
  const p = url.pathname;
  if (p === "/api/status") return json(response, { status: "ok", version: "mock-hermes-stream" });
  if (p === "/api/sessions/stats") return json(response, { total: 0 });
  if (/^\/api\/sessions\/[^/]+\/messages$/.test(p)) {
    // Return a history that COVERS the locally observed turns so the app's reconciliation
    // acceptance passes — this is what swaps live ids (u-*/a-*) for history ids (h-*), the
    // suspected trigger for the anchor-jump bug. Content mirrors what streamRun produced.
    const out = [];
    for (let i = 0; i < promptCount; i++) {
      out.push({ id: i * 2 + 1, role: "user", content: promptTexts[i] ?? "t" });
      out.push({ id: i * 2 + 2, role: "assistant", content: FULL_TEXT });
    }
    return json(response, { messages: out });
  }
  if (/^\/api\/sessions\/[^/]+$/.test(p)) return json(response, { session: { id: p.split("/")[3], title: "Mock" } });
  if (p === "/api/sessions" || p === "/api/sessions/search") return json(response, { sessions: [] });
  if (p === "/api/profiles") return json(response, { profiles: [{ name: "default", is_default: true }, { name: "Work" }, { name: "Personal" }] });
  if (p === "/api/profiles/active") return json(response, { active: "default" });
  if (p === "/api/profiles/sessions") return json(response, { sessions: [] });
  if (p === "/api/model/options") return json(response, { providers: [] });
  if (p === "/api/config") return json(response, {});
  if (p === "/api/skills") return json(response, { skills: [] });
  if (p === "/api/tools/toolsets") return json(response, { toolsets: [] });
  if (p === "/api/analytics/usage" || p === "/api/analytics/models") return json(response, {});
  if (p === "/api/messaging/platforms") return json(response, { platforms: [] });
  if (p === "/api/cron/jobs") return json(response, { jobs: [] });
  json(response, { error: "not_found" }, 404);
});

// ---- the streamed "agent run" ---------------------------------------------
const PROSE_A = `好的，我来分析这个部署问题。先检查服务器上的 nginx 配置和证书链，然后逐一验证每个 upstream 的健康状态。

## 第一步：检查配置

需要先看几个关键文件，我用工具读取：

`;
const CODE_BLOCK = "```bash\nsudo nginx -t\nsystemctl status hermes-gateway --no-pager\ncurl -sS https://mrlgs.net/relay-health | jq .\n```\n\n";
const RAW_JSON = `{"output": "fun scan(text: String) { var depth = 0; for (c in text) { when (c) { '{' -> depth++ } } } \\n if (x) { y() } else { z() } \\n val s = \\"quoted { brace }\\" \\n data class A(val b: Int) { fun c() { d { e } } }", "exit_code": 0, "command": "cat Scanner.kt && grep -c '{' Scanner.kt", "matches": {"open": 42, "close": 42, "nested": {"deep": "{ } { } { }"}}, "note": "braces inside strings everywhere { } { } }"}`;
const PROSE_B = `

配置本身没有问题。接下来看看这次抖动的真正原因：

1. **第一种可能**：upstream 在 TLS 握手阶段超时
2. **第二种可能**：证书链不完整导致校验失败
3. **第三种可能**：keepalive 连接被过早回收

### 详细对比

| 项目 | 期望值 | 实际值 |
|------|--------|--------|
| 证书深度 | 4 | 2 |
| keepalive | 16 | 16 |
| 读超时 | 75s | 75s |

再跑一个验证脚本确认：

`;
const DIFF_BLOCK = "```diff\n--- a/deploy/hermes-edge.nginx.conf\n+++ b/deploy/hermes-edge.nginx.conf\n@@ -31,7 +31,7 @@\n   proxy_ssl_server_name on;\n-  proxy_ssl_verify_depth 2;\n+  proxy_ssl_verify_depth 4;\n   proxy_ssl_verify on;\n```\n\n";
const CODE_BLOCK_2 = "```kotlin\nval atBottom by remember(listState) {\n    derivedStateOf {\n        listState.firstVisibleItemIndex == 0 &&\n            listState.firstVisibleItemScrollOffset == 0\n    }\n}\n// 布局天然贴底，零程序化滚动\n```\n\n";
const PROSE_C = `## 结论

综合以上分析，问题定位在证书链深度配置。修复方式是把 \`proxy_ssl_verify_depth\` 调整为 4，并重新加载配置。这个修改是低风险的，不会影响现有连接。

最后确认一遍所有服务的健康状态，全部正常后本次排查结束。整体来看系统架构是健康的，只是这一处配置需要微调。`;

const FULL_TEXT = PROSE_A + CODE_BLOCK + RAW_JSON + PROSE_B + DIFF_BLOCK + CODE_BLOCK_2 + PROSE_C;
const REASONING = "用户报告了部署问题。我需要先检查 nginx 配置，然后验证证书链。可能的原因有三类：超时、证书、连接池。逐一排查是最稳妥的路径。先用只读命令收集信息，避免影响线上服务。";

function chunks(text, size) {
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

let promptCount = 0;
const promptTexts = [];
const LIVE_ID = "live-mock-1";
const STORED_ID = "stored-mock-1";

async function streamRun(socket) {
  const send = (type, payload) => {
    if (socket.readyState !== 1) return false;
    socket.send(JSON.stringify({
      jsonrpc: "2.0",
      method: "event",
      params: { type, session_id: LIVE_ID, stored_session_id: STORED_ID, payload },
    }));
    return true;
  };
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  for (const piece of chunks(REASONING, 24)) {
    if (!send("reasoning.delta", { text: piece })) return;
    await sleep(45);
  }
  send("message.start", {});
  let sentTool = false;
  const parts = chunks(FULL_TEXT, 28);
  for (let i = 0; i < parts.length; i++) {
    if (!sentTool && i > parts.length / 3) {
      sentTool = true;
      send("tool.start", { tool_id: "t-0", name: "Read" });
      await sleep(120);
      send("tool.complete", { tool_id: "t-0", result: JSON.stringify({ command: "cat /etc/nginx/conf.d/hermes-edge.conf", exit_code: 0, duration_ms: 95, output: "server { listen 443 ssl; ... }" }) });
      send("tool.start", { tool_id: "t-1", name: "Bash" });
      await sleep(3000);
      send("tool.complete", { tool_id: "t-1", result: RAW_JSON });
      send("tool.start", { tool_id: "t-2", name: "Bash" });
      await sleep(200);
      send("tool.complete", { tool_id: "t-2", result: JSON.stringify({ command: "systemctl restart hermes-gateway", exit_code: 1, duration_ms: 1200, output: "Job failed. See journalctl -xe\nport 8444 already in use" }) });
      send("tool.start", { tool_id: "t-3", name: "TodoWrite" });
      await sleep(150);
      send("tool.complete", { tool_id: "t-3", result: JSON.stringify({ todos: [
        { id: "1", content: "检查 nginx 与网关服务状态", status: "completed" },
        { id: "2", content: "定位证书链校验失败原因", status: "completed" },
        { id: "3", content: "更新配置并重载 nginx", status: "in_progress" },
        { id: "4", content: "验证公网健康检查恢复", status: "pending" }
      ] }) });
    }
    if (!send("message.delta", { text: parts[i] })) return;
    await sleep(110);
    if (i === Math.floor(parts.length / 2)) {
      // Approval window: phase -> WAITING_APPROVAL for ~6s so Home's "needs you" row is observable.
      send("approval.request", { command: "systemctl restart hermes-gateway", description: "重启网关服务以应用配置", allow_permanent: true });
      await sleep(2000);
    }
  }
  send("message.complete", { text: FULL_TEXT });
  console.log("stream complete:", FULL_TEXT.length, "chars in", parts.length, "deltas");
}

const wss = new WebSocketServer({ noServer: true });
server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  const ticket = url.searchParams.get("ticket");
  if (url.pathname !== "/api/ws" || !ticket || !tickets.delete(ticket)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(request, socket, head, (webSocket) => wss.emit("connection", webSocket));
});

wss.on("connection", (socket) => {
  socket.send(JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } }));
  socket.on("message", (raw) => {
    const request = JSON.parse(raw.toString());
    const reply = (result) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: request.id, result }));
    switch (request.method) {
      case "session.create":
        reply({ session_id: LIVE_ID, stored_session_id: STORED_ID });
        break;
      case "session.resume":
        reply({ session_id: LIVE_ID });
        break;
      case "prompt.submit":
        promptCount += 1;
        promptTexts.push(String(request.params?.text ?? request.params?.prompt ?? "t"));
        reply({ ok: true });
        void streamRun(socket);
        break;
      case "commands.catalog":
        reply({ commands: [] });
        break;
      case "complete.path":
        reply({ items: [] });
        break;
      case "process.list":
        reply({ processes: [] });
        break;
      default:
        reply({ ok: true, method: request.method });
    }
  });
});

function readBody(request) {
  return new Promise((resolve, reject) => {
    const parts = [];
    request.on("data", (chunk) => parts.push(chunk));
    request.on("end", () => resolve(Buffer.concat(parts)));
    request.on("error", reject);
  });
}

server.listen(port, "127.0.0.1", () => console.log(`Mock Hermes (stream) on 127.0.0.1:${port}`));
