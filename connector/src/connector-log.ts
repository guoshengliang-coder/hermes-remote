/**
 * Structured, single-line JSON logging for the Connector — the same shape the Gateway writes, so
 * one grep by session id works across both. The Connector is the only component that sees Hermes'
 * own events on their way to a phone, so it is where "the run ended at T and the frame that said
 * so went to tunnel X (or to nobody)" can be recorded.
 *
 * Launchd captures stdout; lines carry their own timestamp. Credential-shaped keys are dropped,
 * strings bounded, and relayed payloads are described by their event type, never quoted.
 */

export type ConnectorLogLevel = "off" | "error" | "info" | "debug";

const LEVEL_ORDER: Record<ConnectorLogLevel, number> = { off: 0, error: 1, info: 2, debug: 3 };
const SECRET_KEY = /token|authorization|cookie|secret|password|ticket|credential/i;
const MAX_STRING = 200;

export type LogFields = Record<string, unknown>;

export interface ConnectorLogger {
  readonly level: ConnectorLogLevel;
  enabled(level: Exclude<ConnectorLogLevel, "off">): boolean;
  error(kind: string, fields?: LogFields): void;
  info(kind: string, fields?: LogFields): void;
  debug(kind: string, fields?: LogFields): void;
}

export function parseConnectorLogLevel(raw: string | undefined): ConnectorLogLevel {
  if (raw === undefined || raw.trim() === "") return "info";
  const value = raw.trim().toLowerCase();
  if (value in LEVEL_ORDER) return value as ConnectorLogLevel;
  throw new Error("CONNECTOR_LOG_LEVEL must be one of off, error, info, debug");
}

export function sanitizeLogFields(fields: LogFields): LogFields {
  const safe: LogFields = {};
  for (const [key, value] of Object.entries(fields)) {
    if (SECRET_KEY.test(key) || value === undefined) continue;
    if (typeof value === "string") {
      safe[key] = value.length > MAX_STRING ? `${value.slice(0, MAX_STRING)}…` : value;
    } else if (typeof value === "number" || typeof value === "boolean" || value === null) {
      safe[key] = value;
    } else if (value instanceof Error) {
      safe[key] = value.message.slice(0, MAX_STRING);
    } else {
      const encoded = JSON.stringify(value);
      safe[key] = encoded.length > MAX_STRING ? `${encoded.slice(0, MAX_STRING)}…` : encoded;
    }
  }
  return safe;
}

export function formatConnectorLogLine(
  level: Exclude<ConnectorLogLevel, "off">,
  kind: string,
  fields: LogFields,
  now: Date,
): string {
  return JSON.stringify({ ts: now.toISOString(), level, kind, ...sanitizeLogFields(fields) });
}

export function createConnectorLogger(
  level: ConnectorLogLevel,
  sink: (line: string) => void = (line) => { process.stdout.write(`${line}\n`); },
  now: () => Date = () => new Date(),
): ConnectorLogger {
  const threshold = LEVEL_ORDER[level];
  const write = (at: Exclude<ConnectorLogLevel, "off">, kind: string, fields: LogFields = {}): void => {
    if (LEVEL_ORDER[at] > threshold) return;
    sink(formatConnectorLogLine(at, kind, fields, now()));
  };
  return {
    level,
    enabled: (at) => LEVEL_ORDER[at] <= threshold,
    error: (kind, fields) => write("error", kind, fields),
    info: (kind, fields) => write("info", kind, fields),
    debug: (kind, fields) => write("debug", kind, fields),
  };
}

/**
 * What a Hermes → phone frame is, without quoting it: the event type and the session it names.
 * Text frames are Hermes' JSON-RPC envelopes (`{"type":"event","event":{...}}` or an RPC
 * result); anything else is described only by size. Terminal events are the ones an incident
 * reader needs at info level — the rest is debug.
 */
export interface FrameSummary {
  kind: "event" | "rpc" | "other";
  type?: string;
  sessionId?: string;
  terminal: boolean;
}

const TERMINAL_EVENTS = new Set([
  "message.complete", "error", "session.info", "approval.request", "clarify.request",
]);

export function summarizeHermesFrame(text: string): FrameSummary {
  if (!text.startsWith("{")) return { kind: "other", terminal: false };
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { kind: "other", terminal: false };
  }
  if (typeof parsed !== "object" || parsed === null) return { kind: "other", terminal: false };
  const envelope = parsed as Record<string, unknown>;
  const event = envelope.event;
  if (typeof event === "object" && event !== null) {
    const inner = event as Record<string, unknown>;
    const type = typeof inner.type === "string" ? inner.type : undefined;
    const payload = typeof inner.payload === "object" && inner.payload !== null
      ? inner.payload as Record<string, unknown>
      : undefined;
    const sessionId = typeof inner.session_id === "string"
      ? inner.session_id
      : typeof payload?.session_id === "string" ? payload.session_id : undefined;
    return { kind: "event", type, sessionId, terminal: type !== undefined && TERMINAL_EVENTS.has(type) };
  }
  if ("id" in envelope && ("result" in envelope || "error" in envelope)) {
    return { kind: "rpc", type: "error" in envelope ? "rpc.error" : "rpc.result", terminal: false };
  }
  return { kind: "other", terminal: false };
}
