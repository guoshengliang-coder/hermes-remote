// The Web app's WebSocket reaches Hermes' whole JSON-RPC surface through the tunnel, so the browser
// route allowlist would be only cosmetic if frames went through unread. A browser tunnel forwards
// only the methods the Web client uses; configuration, other slash commands, projects, cron and the
// rest stay with the native apps. Frames without a method are the client's answers to server
// requests (for example the -32601 refusal of a `sudo` request) and pass.
//
// Methods that can do more than the Web app needs are admitted in ONE shape only, checked here
// before anything reaches the Mac (docs/ACCOUNT_MODE_SECURITY.md §4, Web batch 4): `slash.exec`
// only switches the current session's model, `config.get` / `config.set` only touch the current
// session's reasoning effort. An extra key or an out-of-range value refuses the whole request.

type ParamsCheck = (params: Record<string, unknown>) => boolean;

const ANY: ParamsCheck = () => true;
const SESSION_ID = /^[A-Za-z0-9_.:-]{1,128}$/;
// Hermes profile names are folder names; letters of any script are allowed, separators are not.
const PROFILE = /^[\p{L}\p{N}_. -]{1,64}$/u;
// Model and provider ids (claude-opus-5, gpt-5.6-sol, openrouter/anthropic/x, qwen2.5:7b). No
// whitespace, quotes or separators, so nothing can be appended to the command.
const SESSION_MODEL_COMMAND = /^\/model [A-Za-z0-9._:\/@+-]{1,128} --provider [A-Za-z0-9._-]{1,64} --session$/;
// Android ui/models/ReasoningEffort.kt: REASONING_OFF plus REASONING_LEVELS.
const REASONING_VALUES: ReadonlySet<string> = new Set(["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]);

function onlyKeys(params: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(params).every((key) => allowed.includes(key));
}

function sessionId(value: unknown): boolean {
  return typeof value === "string" && SESSION_ID.test(value);
}

function optionalProfile(value: unknown): boolean {
  return value === undefined || (typeof value === "string" && PROFILE.test(value));
}

function absolutePath(value: unknown): boolean {
  return typeof value === "string" && value.startsWith("/") && value.length <= 1024 && !/[\u0000-\u001f\u007f]/.test(value);
}

export const BROWSER_RPC_CHECKS: ReadonlyMap<string, ParamsCheck> = new Map<string, ParamsCheck>([
  ["client.capabilities", ANY],
  ["session.create", ANY],
  ["session.resume", ANY],
  ["prompt.submit", ANY],
  ["session.interrupt", ANY],
  ["image.attach", ANY],
  ["file.attach", ANY],
  ["request.answer", ANY],
  ["clarify.lock", ANY],
  // Older Hermes answer approvals and questions through these instead of request.answer.
  ["approval.respond", ANY],
  ["clarify.respond", ANY],
  // Move a conversation to another project folder.
  ["session.workspace.move", (p) =>
    onlyKeys(p, ["session_key", "cwd", "profile"]) && sessionId(p.session_key) && absolutePath(p.cwd) && optionalProfile(p.profile)],
  // Switch THIS session's model; nothing else a slash command can do.
  ["slash.exec", (p) =>
    onlyKeys(p, ["session_id", "command", "profile"]) && sessionId(p.session_id) &&
    typeof p.command === "string" && SESSION_MODEL_COMMAND.test(p.command) && optionalProfile(p.profile)],
  // Read / write THIS session's reasoning effort; never another key, never another scope.
  ["config.get", (p) =>
    onlyKeys(p, ["key", "session_id", "profile"]) && p.key === "reasoning" && sessionId(p.session_id) && optionalProfile(p.profile)],
  ["config.set", (p) =>
    onlyKeys(p, ["key", "session_id", "value", "profile"]) && p.key === "reasoning" && sessionId(p.session_id) &&
    typeof p.value === "string" && REASONING_VALUES.has(p.value) && optionalProfile(p.profile)],
  // Read-only: the session's background processes, and who owns a session right now.
  ["process.list", (p) => onlyKeys(p, ["session_id", "profile"]) && sessionId(p.session_id) && optionalProfile(p.profile)],
  ["session.access", (p) =>
    onlyKeys(p, ["session_id", "profile", "live_session_id"]) && sessionId(p.session_id) && optionalProfile(p.profile) &&
    (p.live_session_id === undefined || sessionId(p.live_session_id))],
]);

export const BROWSER_RPC_METHODS: ReadonlySet<string> = new Set(BROWSER_RPC_CHECKS.keys());

function admitted(method: string, params: unknown): boolean {
  const check = BROWSER_RPC_CHECKS.get(method);
  if (!check) return false;
  if (check === ANY) return true;
  if (typeof params !== "object" || params === null || Array.isArray(params)) return false;
  return check(params as Record<string, unknown>);
}

const MAX_SCREENED_BYTES = 8 * 1024 * 1024;

export interface BrowserFrameScreen {
  forward: boolean;
  /** JSON-RPC error responses for refused requests that carried an id. */
  replies: string[];
  /** Set when the frame is not JSON-RPC at all; the tunnel is closed with 1008. */
  violation?: string;
}

export function screenBrowserFrame(data: Buffer, isBinary: boolean): BrowserFrameScreen {
  if (isBinary) return { forward: false, replies: [], violation: "binary frames are not accepted" };
  if (data.length > MAX_SCREENED_BYTES) {
    return { forward: false, replies: [], violation: "frame too large" };
  }
  const replies: string[] = [];
  let refused = false;
  for (const line of data.toString("utf8").split("\n")) {
    if (line.trim() === "") continue;
    let frame: unknown;
    try {
      frame = JSON.parse(line);
    } catch {
      return { forward: false, replies: [], violation: "frame is not JSON" };
    }
    if (typeof frame !== "object" || frame === null || Array.isArray(frame)) {
      return { forward: false, replies: [], violation: "frame is not a JSON-RPC object" };
    }
    const { method, id, params } = frame as { method?: unknown; id?: unknown; params?: unknown };
    if (method === undefined) continue;
    if (typeof method === "string" && admitted(method, params)) continue;
    refused = true;
    if (typeof id === "string" || typeof id === "number") {
      replies.push(JSON.stringify({
        jsonrpc: "2.0",
        id,
        error: {
          code: 4403,
          message: "HR-WEB-001 This feature isn't available in the Hermes GO web app.",
          data: { code: "HR-WEB-001" },
        },
      }));
    }
  }
  return { forward: !refused, replies };
}
