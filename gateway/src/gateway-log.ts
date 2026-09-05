/**
 * Structured, single-line JSON logging for the Gateway, written to stdout so journald owns
 * rotation and retention. Until 2026-09-05 the Gateway logged only its own start and stop; every
 * incident was reconstructed from Nginx byte counts and the inbox file instead. These lines answer
 * the two questions that reconstruction was for: was an app socket attached when something
 * happened, and when did the inbox receive, serve and get acknowledged for each lifecycle event.
 *
 * Nothing secret is ever a field: keys that look like credentials are dropped, strings are
 * truncated, and relayed frames are counted, never quoted.
 */

export type GatewayLogLevel = "off" | "error" | "info" | "debug";

const LEVEL_ORDER: Record<GatewayLogLevel, number> = { off: 0, error: 1, info: 2, debug: 3 };
const SECRET_KEY = /token|authorization|cookie|secret|password|ticket|credential/i;
const MAX_STRING = 200;

export type LogFields = Record<string, unknown>;

export interface GatewayLogger {
  readonly level: GatewayLogLevel;
  enabled(level: Exclude<GatewayLogLevel, "off">): boolean;
  error(kind: string, fields?: LogFields): void;
  info(kind: string, fields?: LogFields): void;
  debug(kind: string, fields?: LogFields): void;
}

export function parseGatewayLogLevel(raw: string | undefined): GatewayLogLevel {
  if (raw === undefined || raw.trim() === "") return "info";
  const value = raw.trim().toLowerCase();
  if (value in LEVEL_ORDER) return value as GatewayLogLevel;
  throw new Error("GATEWAY_LOG_LEVEL must be one of off, error, info, debug");
}

/** Drops credential-shaped keys and bounds every string so a log line can never leak or bloat. */
export function sanitizeLogFields(fields: LogFields): LogFields {
  const safe: LogFields = {};
  for (const [key, value] of Object.entries(fields)) {
    if (SECRET_KEY.test(key)) continue;
    if (value === undefined) continue;
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

export function formatGatewayLogLine(
  level: Exclude<GatewayLogLevel, "off">,
  kind: string,
  fields: LogFields,
  now: Date,
): string {
  return JSON.stringify({ ts: now.toISOString(), level, kind, ...sanitizeLogFields(fields) });
}

export function createGatewayLogger(
  level: GatewayLogLevel,
  sink: (line: string) => void = (line) => { process.stdout.write(`${line}\n`); },
  now: () => Date = () => new Date(),
): GatewayLogger {
  const threshold = LEVEL_ORDER[level];
  const write = (at: Exclude<GatewayLogLevel, "off">, kind: string, fields: LogFields = {}): void => {
    if (LEVEL_ORDER[at] > threshold) return;
    sink(formatGatewayLogLine(at, kind, fields, now()));
  };
  return {
    level,
    enabled: (at) => LEVEL_ORDER[at] <= threshold,
    error: (kind, fields) => write("error", kind, fields),
    info: (kind, fields) => write("info", kind, fields),
    debug: (kind, fields) => write("debug", kind, fields),
  };
}

/** For constructors that take an optional logger: the default that writes nothing. */
export const silentGatewayLogger: GatewayLogger = createGatewayLogger("off", () => undefined);
