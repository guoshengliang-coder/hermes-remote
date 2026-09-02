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
  if (p === "/api/profiles/sessions") {
    // Cross-profile list with workspace facts, so the app's project sublines, Projects segment
    // (default project + derived projects), archived rows and move-to-project are exercisable.
    const wantArchived = url.searchParams.get("archived") === "only";
    const rows = mockSessions()
      .filter((row) => (wantArchived ? row.archived : !row.archived));
    return json(response, { sessions: rows, total: rows.length, profile_totals: { default: rows.length }, errors: [] });
  }
  if (p === "/api/sessions" || p === "/api/sessions/search") {
    // List the stored session once it has content, so "reopen from the list" flows are testable.
    const sessions = promptCount > 0
      ? [{ id: STORED_ID, title: "Mock 会话", message_count: promptCount * 2, last_active: Math.floor(Date.now() / 1000) }]
      : [];
    return json(response, { sessions });
  }
  if (p === "/api/profiles") return json(response, { profiles: [{ name: "default", is_default: true }, { name: "Work" }, { name: "Personal" }] });
  if (p === "/api/profiles/active") return json(response, { active: "default" });
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
const pendingClarifyAnswers = [];
let clarifyForm = 0; // rotates: 0 single-choice, 1 multi-select, 2 batch
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
      // Clarify exercise: rotate through the three upstream forms so the decision card's
      // single-select, multi-select, and batch flows are all locally testable.
      const form = clarifyForm++ % 3;
      if (form === 0) {
        send("clarify.request", {
          request_id: "clr-" + clarifyForm,
          question: "要用哪种发布方式？",
          choices: ["滚动发布 (Recommended)", "蓝绿切换", "全量停机重发"],
        });
      } else if (form === 1) {
        send("clarify.request", {
          request_id: "clr-" + clarifyForm,
          question: "备份哪些内容？",
          choices: ["数据库全量 (Recommended)", "上传的用户文件", "环境配置与密钥清单"],
          multi_select: true,
        });
      } else {
        send("clarify.request", {
          request_id: "clr-" + clarifyForm,
          questions: [
            { qid: "q0", question: "数据库选型？", choices: ["PostgreSQL (Recommended)", "MySQL"] },
            { qid: "q1", question: "对象存储用哪个？", choices: ["本地 MinIO (Recommended)", "阿里云 OSS"] },
            { qid: "q2", question: "部署区域备注（自由填写）" },
          ],
        });
      }
      // Wait up to 60s for the user to answer (single respond for forms 0/1; three for batch).
      const needed = form === 2 ? 3 : 1;
      const before = pendingClarifyAnswers.length;
      for (let w = 0; w < 120 && pendingClarifyAnswers.length - before < needed; w++) await sleep(500);
    }
  }
  send("message.complete", { text: FULL_TEXT });
  console.log("stream complete:", FULL_TEXT.length, "chars in", parts.length, "deltas");
}

// ── Workspace fixtures (dev only) ─────────────────────────────────────────────────────────────
// The mock gateway's "launch directory" — sessions created without a cwd land here.
const LAUNCH_DIR = "/Users/me";
const HERMES_REMOTE = "/Users/me/CodeX project/hermes-remote";
const nowSec = () => Math.floor(Date.now() / 1000);
const fixtureSessions = [
  { id: "fx-1", title: "重构 gateway 路由中间件", model: "claude-opus-5", cwd: HERMES_REMOTE, git_repo_root: HERMES_REMOTE, git_branch: "codex/gateway-router", ago: 10 * 60 },
  { id: "fx-2", title: "周报汇总 · 上周提交记录", model: "claude-sonnet-5", cwd: null, git_repo_root: null, git_branch: null, ago: 60 * 60 },
  { id: "fx-3", title: "翻译 Android 文案", model: "claude-sonnet-5", cwd: "/Users/me/.hermes/nous-hermes-agent-playground", git_repo_root: "/Users/me/.hermes/nous-hermes-agent-playground", git_branch: "claude/l10n-pass", ago: 30 * 60 },
  { id: "fx-4", title: "调查 DERP 端口冲突", model: "claude-opus-5", cwd: "/Users/me/ops/hk", git_repo_root: "/Users/me/ops/hk", git_branch: "main", ago: 2 * 86400 },
  { id: "fx-5", title: "整理 docs/DEPLOYMENT", model: "claude-opus-5", cwd: HERMES_REMOTE, git_repo_root: HERMES_REMOTE, git_branch: "main", ago: 9 * 86400 },
  { id: "fx-6", title: "调试 debug 签名密钥缺失", model: "claude-opus-5", cwd: HERMES_REMOTE, git_repo_root: HERMES_REMOTE, git_branch: "main", ago: 5 * 86400, archived: true },
];
// Workspace of the dynamically created stored session (set by session.create / workspace.move).
let storedWorkspace = { cwd: LAUNCH_DIR, git_repo_root: null, git_branch: null };
function mockSessions() {
  const rows = fixtureSessions.map((f) => ({
    id: f.id, title: f.title, model: f.model, message_count: 4, last_active: nowSec() - f.ago,
    profile: "default", is_default_profile: true, archived: Boolean(f.archived),
    cwd: f.cwd, git_repo_root: f.git_repo_root, git_branch: f.git_branch, source: "tui",
  }));
  if (promptCount > 0) {
    rows.unshift({
      id: STORED_ID, title: "Mock 会话", model: "claude-opus-5", message_count: promptCount * 2, last_active: nowSec(),
      profile: "default", is_default_profile: true, archived: false, source: "tui", ...storedWorkspace,
    });
  }
  return rows;
}
function workspaceFor(sessionId) {
  const f = fixtureSessions.find((row) => row.id === sessionId);
  if (f) return { cwd: f.cwd ?? LAUNCH_DIR, branch: f.git_branch, git_repo_root: f.git_repo_root };
  return { cwd: storedWorkspace.cwd, branch: storedWorkspace.git_branch, git_repo_root: storedWorkspace.git_repo_root };
}
function setWorkspace(sessionId, cwd) {
  const branch = "main";
  const f = fixtureSessions.find((row) => row.id === sessionId);
  if (f) { f.cwd = cwd; f.git_repo_root = cwd; f.git_branch = branch; }
  else storedWorkspace = { cwd, git_repo_root: cwd, git_branch: branch };
  return { cwd, branch, git_repo_root: cwd };
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
    const replyError = (code, message) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: request.id, error: { code, message } }));
    const emit = (type, sessionId, payload) =>
      socket.send(JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type, session_id: sessionId, payload } }));
    switch (request.method) {
      case "session.create": {
        // Upstream persists only an explicit cwd; otherwise the session lands in the launch dir.
        // A cwd containing "missing" simulates a folder that no longer exists (silent fallback).
        const requested = String(request.params?.cwd ?? "").trim();
        const cwd = requested && !requested.includes("missing") ? requested : LAUNCH_DIR;
        storedWorkspace = { cwd, git_repo_root: requested === cwd ? cwd : null, git_branch: requested === cwd ? "main" : null };
        reply({ session_id: LIVE_ID, stored_session_id: STORED_ID, info: { model: "claude-opus-5", cwd, branch: storedWorkspace.git_branch } });
        break;
      }
      case "session.resume": {
        reply({ session_id: LIVE_ID });
        const ws = workspaceFor(String(request.params?.session_id ?? ""));
        emit("session.info", String(request.params?.session_id ?? STORED_ID), { running: false, cwd: ws.cwd, branch: ws.branch });
        break;
      }
      case "session.workspace.move": {
        const target = String(request.params?.session_key ?? "");
        const cwd = String(request.params?.cwd ?? "").trim();
        if (!cwd) { replyError(4016, "cwd required"); break; }
        if (cwd.includes("missing")) { replyError(4017, `working directory does not exist: ${cwd}`); break; }
        if (target === "fx-1") { replyError(4009, "session busy"); break; } // fixture: always mid-turn
        const moved = setWorkspace(target, cwd);
        reply(moved);
        emit("session.info", target, { running: false, cwd: moved.cwd, branch: moved.branch });
        break;
      }
      case "clarify.respond": {
        const qid = request.params?.question_id;
        pendingClarifyAnswers.push({ qid: qid ?? null, answer: request.params?.answer ?? "" });
        reply({ ok: true, remaining: [] });
        break;
      }
      case "prompt.submit": {
        // Delivery-state fixtures: "!fail…" is refused, "!slow…" is acknowledged after 6 s.
        const submitted = String(request.params?.text ?? request.params?.prompt ?? "t");
        if (submitted.startsWith("!fail")) { replyError(5000, "mock: submit refused"); break; }
        const ack = () => {
          promptCount += 1;
          promptTexts.push(submitted);
          reply({ ok: true });
          void streamRun(socket);
        };
        if (submitted.startsWith("!slow")) setTimeout(ack, 6000); else ack();
        break;
      }
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
