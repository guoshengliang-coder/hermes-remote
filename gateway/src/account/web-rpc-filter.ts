// The Web app's WebSocket reaches Hermes' whole JSON-RPC surface through the tunnel, so the browser
// route allowlist would be only cosmetic if frames went through unread. A browser tunnel forwards
// only the methods the chat client uses; configuration, slash commands, projects, cron and the rest
// stay with the native apps. Frames without a method are the client's answers to server requests
// (for example the -32601 refusal of a `sudo` request) and pass.
export const BROWSER_RPC_METHODS: ReadonlySet<string> = new Set([
  "client.capabilities",
  "session.create",
  "session.resume",
  "prompt.submit",
  "session.interrupt",
  "image.attach",
  "file.attach",
  "request.answer",
  "clarify.lock",
  // Older Hermes answer approvals and questions through these instead of request.answer.
  "approval.respond",
  "clarify.respond",
]);

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
    const { method, id } = frame as { method?: unknown; id?: unknown };
    if (method === undefined) continue;
    if (typeof method === "string" && BROWSER_RPC_METHODS.has(method)) continue;
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
