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
    // `timestamp` (Unix SECONDS, float) is Hermes' own column name — the app reads it to show
    // times in 我的提问. Mirroring it here is what makes HG-4 reproducible locally; a mock that
    // omitted it looked identical to the bug. Spread the rows a few minutes apart so the list has
    // something to show.
    const base = Math.floor(Date.now() / 1000) - promptCount * 300;
    const out = [];
    for (let i = 0; i < promptCount; i++) {
      out.push({ id: i * 2 + 1, role: "user", content: promptTexts[i] ?? "t", timestamp: base + i * 300 });
      out.push({ id: i * 2 + 2, role: "assistant", content: FULL_TEXT, timestamp: base + i * 300 + 60 });
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
  if (p === "/api/fs/default-cwd") return json(response, { cwd: LAUNCH_DIR, branch: "main" });
  if (p === "/api/fs/git-root") {
    const target = url.searchParams.get("path") ?? "";
    return json(response, { root: FAKE_REPOS.has(target) ? target : null });
  }
  if (p === "/api/fs/list") {
    // Upstream never throws here: a bad path comes back as an empty list plus an `error` string,
    // which is what the client maps to HR-SESS-012.
    const target = url.searchParams.get("path") ?? "";
    const children = FAKE_FS[target];
    if (!children) return json(response, { entries: [], error: "ENOENT" });
    return json(response, {
      entries: children.map((name) => ({
        name,
        path: target === "/" ? `/${name}` : `${target}/${name}`,
        isDirectory: true,
      })),
    });
  }
  // Scheduled jobs. A fixture rather than an empty list so the cron screens can actually be
  // looked at on a device (docs/DESIGN.md §5.18): one failed job, six healthy, one paused —
  // every row state the list draws, and the group split that goes with them.
  // A bare array, and a bare array for the runs too: that is what the client parses
  // (HermesRestApi.cronJobs / cronRuns). The old `{ jobs: [] }` never parsed — nothing had
  // ever looked at this screen against the mock.
  if (p === "/api/cron/jobs") return json(response, CRON_JOBS);
  if (p.startsWith("/api/cron/jobs/") && p.endsWith("/runs")) {
    const id = p.slice("/api/cron/jobs/".length, -"/runs".length);
    return json(response, { runs: CRON_RUNS[id] ?? [] });
  }
  if (p.startsWith("/api/cron/jobs/")) {
    const id = p.slice("/api/cron/jobs/".length);
    const job = CRON_JOBS.find((j) => j.id === id);
    return job ? json(response, job) : json(response, { error: "not_found" }, 404);
  }
  if (p === "/api/cron/delivery-targets") {
    return json(response, { targets: [{ id: "dingtalk", name: "钉钉", home_target_set: true }] });
  }
  json(response, { error: "not_found" }, 404);
});

// ---- scheduled jobs (see the /api/cron routes above) -----------------------
const CRON_NOW = Date.now();
const cronIso = (offsetMs) => new Date(CRON_NOW + offsetMs).toISOString();
const cronJob = (id, name, display, extra = {}) => ({
  id,
  name,
  schedule_display: display,
  enabled: true,
  next_run_at: cronIso(3 * 3600_000),
  last_run_at: cronIso(-21 * 3600_000),
  last_status: "ok",
  deliver: "local",
  profile: "default",
  ...extra,
});
const CRON_JOBS = [
  cronJob("j1", "钉钉连接健康检测（自动重连）", "每 2 分钟", {
    deliver: "origin",
    last_status: "error",
    last_error: "connect ECONNREFUSED 127.0.0.1:7001",
    last_run_at: cronIso(-9 * 60_000),
  }),
  cronJob("j2", "小迈公司经营日报 | 钉钉 AI Card", "每天 18:15", {
    prompt:
      "你是小迈网络科技有限公司 CEO 的经营日报生产任务。严格顺序：日期与幂等 → BI 查询/稳定分页 → " +
      "源明细对账 → 报告范围过滤 → 数据完整性门禁（含国内+海外） → 经营分析 → 同轮 JSON → 同轮 HTML → " +
      "验收 → 钉钉上传 → 钉钉 AI Card 发送。所有 Python 脚本运行一律使用 terminal 工具，禁止使用 execute_code。",
  }),
  cronJob("j3", "芯芯 | 每日钉钉行程与待办 AI Card", "每天 08:00", { deliver: "origin" }),
  cronJob("j4", "芯芯 | 每日钉钉邮箱总结 AI Card", "每天 08:00"),
  cronJob("j5", "芯芯 | 钉钉日志每日检测与周报汇总", "每天 08:00"),
  cronJob("j6", "小迈公司市场推广日报 | 钉钉 AI Card", "每天 18:15"),
  cronJob("j7", "周深长沙站开票监控", "每 30 分钟"),
  cronJob("j8", "网关重启丢失消息监控", "每 2 分钟", {
    paused_at: cronIso(-2 * 86_400_000),
    last_status: null,
  }),
];
const CRON_RUNS = {
  j2: [0, 1, 2, 3].map((i) => ({
    id: `r${i}`,
    started_at: (CRON_NOW - (i + 1) * 86_400_000) / 1000,
    ended_at: (CRON_NOW - (i + 1) * 86_400_000 + (38 + i * 3) * 1000) / 1000,
    end_reason: "cron_complete",
  })),
};

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

// Ordered/bulleted lists and links are the most common shape of a real answer and were the one
// thing this fixture never produced, so body-typography changes could not be seen on a device.
const PROSE_LIST = `### 三个可能原因

1. **证书链深度不足** \u2014 \`proxy_ssl_verify_depth\` 默认是 1，中间 CA 校验会直接失败，这是最常见的一种。
2. **upstream 超时** \u2014 网关重启期间连接池没有排空，旧连接还在被复用。
3. **端口占用** \u2014 \`8444\` 已被占用，服务起不来。

排查顺序建议：

- 先看证书链，成本最低
- 再看端口占用
  - \`lsof -i :8444\`
  - \`systemctl status\`
- 最后才动连接池配置

参考 [nginx SSL 模块文档](https://nginx.org/en/docs/http/ngx_http_ssl_module.html) 与内部记录 https://mrlgs.net/relay-health 。

`;

const FULL_TEXT = PROSE_A + CODE_BLOCK + RAW_JSON + PROSE_B + PROSE_LIST + DIFF_BLOCK + CODE_BLOCK_2 + PROSE_C;
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
// ---- projects + a fake Mac filesystem --------------------------------------
// Upstream's projects.* are server-authoritative and take no profile param, so the app only
// manages them for the default profile — which is what this mock reports. Kept in memory so a
// restart resets to the fixtures, and deliberately NOT backed by the real disk: the folder
// picker must be exercisable without letting a dev tool walk the developer's home directory.

let projectSeq = 1;
const projects = [
  { id: "p_seed01", name: "赫尔墨斯远程", slug: "hermes-remote", icon: "repo", color: "hsl(210 68% 58%)",
    folders: [{ path: "/Users/me/CodeX project/hermes-remote", is_primary: true }] },
];

// path -> child directory names.
const FAKE_FS = {
  "/": ["Users"],
  "/Users": ["me"],
  "/Users/me": ["CodeX project", "ops", "notes", ".hermes"],
  "/Users/me/CodeX project": ["hermes-remote", "hermes-ops", "scratch"],
  "/Users/me/CodeX project/hermes-remote": ["android", "gateway", "docs"],
  "/Users/me/CodeX project/hermes-ops": [],
  "/Users/me/CodeX project/scratch": [],
  "/Users/me/ops": ["hk"],
  "/Users/me/ops/hk": [],
  "/Users/me/notes": [],
  "/Users/me/.hermes": ["nous-hermes-agent-playground"],
  "/Users/me/.hermes/nous-hermes-agent-playground": [],
};
// Which of those are git repos, for the picker's hint.
const FAKE_REPOS = new Set([
  "/Users/me/CodeX project/hermes-remote",
  "/Users/me/CodeX project/hermes-ops",
  "/Users/me/ops/hk",
  "/Users/me/.hermes/nous-hermes-agent-playground",
]);

const projectDict = (p) => ({
  id: p.id, slug: p.slug, name: p.name, description: p.description ?? null,
  icon: p.icon ?? null, color: p.color ?? null, board_slug: null,
  primary_path: (p.folders.find((f) => f.is_primary) ?? p.folders[0])?.path ?? null,
  archived: false, created_at: nowSec(),
  folders: p.folders.map((f) => ({ path: f.path, label: null, is_primary: !!f.is_primary, added_at: nowSec() })),
});

const findProject = (id) => projects.find((p) => p.id === String(id ?? ""));

/** Upstream's `_project_node` shape: camelCase, repos carry lanes under `groups`. */
function projectTreeNodes() {
  const sessions = mockSessions().filter((r) => !r.archived);
  const claimed = new Set();
  const nodes = projects.map((p) => {
    const paths = p.folders.map((f) => f.path);
    const mine = sessions.filter((row) => paths.some((dir) => (row.cwd ?? "").startsWith(dir)));
    mine.forEach((row) => claimed.add(row.id));
    const primary = projectDict(p).primary_path;
    return {
      id: p.id, label: p.name, path: primary, color: p.color ?? null, icon: p.icon ?? null,
      isAuto: false, isNoProject: false, sessionCount: mine.length,
      lastActive: mine.length ? Math.max(...mine.map((r) => r.last_active)) : null,
      repos: paths.map((dir) => ({
        id: dir, label: dir.split("/").pop(), path: dir, sessionCount: mine.filter((r) => (r.cwd ?? "").startsWith(dir)).length,
        groups: [{ id: "all", label: "", path: dir, isMain: true, sessions: [] }],
      })),
      previewSessions: mine.slice(0, 3),
    };
  });
  // Everything a project did not claim lands in upstream's no-project bucket.
  const rest = sessions.filter((row) => !claimed.has(row.id));
  nodes.push({
    id: "__no_project__", label: "Home", path: LAUNCH_DIR, color: null, icon: null,
    isAuto: false, isNoProject: true, sessionCount: rest.length,
    lastActive: rest.length ? Math.max(...rest.map((r) => r.last_active)) : null,
    repos: [{ id: LAUNCH_DIR, label: "home", path: LAUNCH_DIR, sessionCount: rest.length,
              groups: [{ id: "all", label: "", path: LAUNCH_DIR, isMain: true, sessions: [] }] }],
    previewSessions: rest.slice(0, 3),
  });
  return nodes;
}

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
// Opt-in padding (default 0, so every existing flow is untouched). A five-row list never fills a
// phone viewport, which is exactly the condition LazyColumn scroll-anchoring bugs need in order to
// show up — HG-11 hid the 已置顶 section above the fold and could not be reproduced without a list
// long enough to scroll. MOCK_HERMES_EXTRA_SESSIONS=40 gives you one.
const extraSessions = Number(process.env.MOCK_HERMES_EXTRA_SESSIONS ?? 0);

function mockSessions() {
  const rows = fixtureSessions.map((f) => ({
    id: f.id, title: f.title, model: f.model, message_count: 4, last_active: nowSec() - f.ago,
    profile: "default", is_default_profile: true, archived: Boolean(f.archived),
    cwd: f.cwd, git_repo_root: f.git_repo_root, git_branch: f.git_branch, source: "tui",
  }));
  for (let i = 0; i < extraSessions; i += 1) {
    rows.push({
      id: `filler-${i}`, title: `填充会话 ${i + 1} · 让列表长到需要滚动`, model: "claude-sonnet-5",
      message_count: 4, last_active: nowSec() - 60 * (i + 1),
      profile: "default", is_default_profile: true, archived: false, source: "tui",
      cwd: LAUNCH_DIR, git_repo_root: LAUNCH_DIR, git_branch: "main",
    });
  }
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
      case "projects.tree": {
        reply({ projects: projectTreeNodes(), active_id: null });
        break;
      }
      case "projects.project_sessions": {
        const node = projectTreeNodes().find((n) => n.id === String(request.params?.project_id ?? ""));
        if (!node) { replyError(5062, "no such project"); break; }
        const all = mockSessions().filter((r) => !r.archived);
        reply({ project: { ...node, repos: node.repos.map((repo) => ({
          ...repo,
          groups: [{ id: "all", label: "", path: repo.path, isMain: true,
                     sessions: all.filter((r) => (r.cwd ?? "").startsWith(repo.path)) }],
        })) } });
        break;
      }
      case "projects.create": {
        const name = String(request.params?.name ?? "").trim();
        if (!name) { replyError(5063, "name is required"); break; }
        const folders = (request.params?.folders ?? []).map((path) => ({ path, is_primary: false }));
        if (folders.length) folders[0].is_primary = true;
        const created = {
          id: `p_new${projectSeq++}`, name,
          slug: name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "project",
          icon: request.params?.icon ?? null, color: request.params?.color ?? null, folders,
        };
        projects.push(created);
        reply({ project: projectDict(created) });
        break;
      }
      case "projects.update": {
        const target = findProject(request.params?.id);
        if (!target) { replyError(5062, "no such project"); break; }
        if (request.params?.name !== undefined) {
          const name = String(request.params.name ?? "").trim();
          if (!name) { replyError(5063, "name is required"); break; }
          target.name = name;
        }
        if (request.params?.icon !== undefined) target.icon = request.params.icon;
        if (request.params?.color !== undefined) target.color = request.params.color;
        reply({ project: projectDict(target) });
        break;
      }
      case "projects.add_folder": {
        const target = findProject(request.params?.id);
        if (!target) { replyError(5062, "no such project"); break; }
        const path = String(request.params?.path ?? "").trim();
        if (!path) { replyError(5063, "path is required"); break; }
        if (!target.folders.some((f) => f.path === path)) {
          target.folders.push({ path, is_primary: target.folders.length === 0 });
        }
        reply({ project: projectDict(target) });
        break;
      }
      case "projects.remove_folder": {
        const target = findProject(request.params?.id);
        if (!target) { replyError(5062, "no such project"); break; }
        target.folders = target.folders.filter((f) => f.path !== String(request.params?.path ?? ""));
        if (target.folders.length && !target.folders.some((f) => f.is_primary)) target.folders[0].is_primary = true;
        reply({ project: projectDict(target) });
        break;
      }
      case "projects.set_primary": {
        const target = findProject(request.params?.id);
        if (!target) { replyError(5062, "no such project"); break; }
        const path = String(request.params?.path ?? "");
        if (!target.folders.some((f) => f.path === path)) { replyError(5063, "folder not in project"); break; }
        target.folders.forEach((f) => { f.is_primary = f.path === path; });
        reply({ project: projectDict(target) });
        break;
      }
      case "projects.delete": {
        const target = findProject(request.params?.id);
        if (!target) { replyError(5062, "no such project"); break; }
        projects.splice(projects.indexOf(target), 1);
        reply({ projects: projects.map(projectDict), active_id: null });
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
      case "file.attach":
      case "image.attach":
      case "pdf.attach": {
        // Without this, EVERY attachment send fails locally: the catch-all `default` below
        // answers `{ ok: true }`, and `ChatRepository.attachFilePath` raises on a reply with no
        // `ref_text`, so the bubble lands on 未发送 · SESS-007 and looks like an app bug. The
        // three fields are the ones the client actually reads; the ref_text WORDING is a mock
        // stand-in, not a claim about what upstream writes (docs/HERMES_CONTRACT.md lists the
        // method but not its response shape).
        const attachName = String(request.params?.name ?? "attachment");
        const attachPath = String(request.params?.path ?? `/tmp/${attachName}`);
        reply({ name: attachName, path: attachPath, ref_text: `\n\n[attached ${attachName}: ${attachPath}]` });
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
