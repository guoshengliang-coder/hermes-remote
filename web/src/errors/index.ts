import { CATALOG, isKnownCode, type ErrorCode, type RecoveryAction } from "./catalog";
import { redact } from "./redact";

export { CATALOG, isKnownCode, type ErrorCode, type RecoveryAction } from "./catalog";
export { redact } from "./redact";

/** Structured user-visible error (docs/ERROR_HANDLING.md "Canonical structured error"). */
export interface AppError {
  /** A catalog code, or a registered HR-* code the Gateway sent that this build has no copy for. */
  code: ErrorCode | (string & {});
  zh: string;
  en: string;
  retryable: boolean;
  action?: RecoveryAction;
  /** Redacted technical cause; never the primary message. */
  details?: string;
}

export type Language = "zh" | "en";

export function appError(
  code: ErrorCode,
  details?: string | null,
  overrides: { retryable?: boolean; action?: RecoveryAction } = {},
): AppError {
  const entry = CATALOG[code];
  const error: AppError = {
    code,
    zh: entry.zh,
    en: entry.en,
    retryable: overrides.retryable ?? entry.retryable,
    action: overrides.action ?? entry.action,
  };
  if (details) error.details = redact(details);
  return error;
}

export function localized(error: AppError, language: Language): string {
  return language === "en" ? error.en : error.zh;
}

/** One line for inline surfaces: explanation plus code. */
export function display(error: AppError, language: Language): string {
  return language === "en" ? `${error.en} (Error code: ${error.code})` : `${error.zh}（错误码：${error.code}）`;
}

/** Copyable diagnostics: code, retryability and the redacted cause. */
export function diagnostics(error: AppError, extra: Record<string, string | number | boolean | null | undefined> = {}): string {
  const lines = [`code=${error.code}`, `retryable=${error.retryable}`];
  for (const [key, value] of Object.entries(extra)) if (value !== undefined && value !== null) lines.push(redact(`${key}=${value}`));
  if (error.details) lines.push(`details=${error.details}`);
  return lines.join("\n");
}

// --- JSON-RPC ------------------------------------------------------------------------------

/** Numbers upstream (and our Connector) classify on; see HERMES_CONTRACT.md §3 and ChatViewModel. */
export const RPC = {
  CLIENT_BUG: 4000,
  STALE_SESSION: 4001,
  SESSION_NOT_FOUND: 4007,
  SESSION_BUSY: 4009,
  OWNED_ELSEWHERE: 4090,
  /** Gateway: this WebSocket method is not open to browsers (data.code = HR-WEB-001). */
  WEB_METHOD_REFUSED: 4403,
  RESPONSE_TOO_LARGE: -32001,
  METHOD_NOT_FOUND: -32601,
  PDF_DEPENDENCY_MISSING: 5028,
} as const;

/** What the failed call was doing; decides the fallback code. */
export type RpcContext = "submit" | "resume" | "create" | "attach" | "answer" | "workspace" | "generic";

export function fromRpcError(
  code: number,
  message: string | null | undefined,
  context: RpcContext = "generic",
  data?: unknown,
): AppError {
  const details = `rpc ${code}${message ? ` ${message}` : ""}`;
  // A structured code the Gateway put in error.data wins (e.g. its in-band 4403 refusal).
  const dataCode = typeof data === "object" && data !== null ? (data as { code?: unknown }).code : undefined;
  if (isKnownCode(dataCode)) return appError(dataCode, details);
  switch (code) {
    case RPC.WEB_METHOD_REFUSED:
      return appError("HR-WEB-001", details);
    case RPC.STALE_SESSION:
      // A stale live handle: resuming fixes it.
      return appError("HR-SESS-002", details);
    case RPC.SESSION_NOT_FOUND:
      // The durable session is gone from the profile's state.db: terminal.
      return appError("HR-SESS-001", details);
    case RPC.OWNED_ELSEWHERE:
      // Neither stale nor terminal: the same send succeeds once the other surface lets go.
      return appError("HR-SESS-013", details);
    case RPC.SESSION_BUSY:
      return context === "workspace" ? appError("HR-SESS-004", details) : context === "submit"
        ? appError("HR-SESS-007", details)
        : appError("HR-RPC-001", details, { retryable: true });
    case RPC.RESPONSE_TOO_LARGE:
      return appError("HR-SESS-017", details);
    case RPC.PDF_DEPENDENCY_MISSING:
      return appError("HR-SESS-016", details);
    case RPC.CLIENT_BUG:
    case RPC.METHOD_NOT_FOUND:
      // 4000 is always a params bug here; -32601 a Hermes without the method. Retrying repeats it.
      return appError(context === "submit" ? "HR-SESS-007" : "HR-RPC-001", details, { retryable: false, action: "details" });
    default:
      return appError(context === "submit" ? "HR-SESS-007" : "HR-RPC-001", details);
  }
}

/** Anything thrown by HermesSocket (duck-typed so this module stays free of the client). */
export interface SocketErrorLike {
  kind: string;
  code?: number;
  message: string;
  method?: string;
  data?: unknown;
}

export function fromSocketError(error: SocketErrorLike, context: RpcContext = "generic"): AppError {
  const details = `${error.method ?? "rpc"}: ${error.message}`;
  switch (error.kind) {
    case "rpc":
      return fromRpcError(error.code ?? 0, `${error.method ?? ""} ${error.message}`.trim(), context, error.data);
    case "handshake-timeout":
      return appError("HR-CONN-003", details);
    case "timeout":
      return appError("HR-RPC-002", details);
    case "closed":
    case "not-connected":
      return appError("HR-CONN-004", details);
    case "unsupported":
      return appError("HR-WEB-002", details);
    default:
      return appError("HR-UNKNOWN-001", details);
  }
}

/** `{status:"expired"}` from request.answer / clarify.lock / clarify.respond. */
export function expiredAnswer(kind: "approval" | "clarify"): AppError {
  return appError(kind === "approval" ? "HR-APPROVAL-003" : "HR-CLARIFY-001");
}

// --- HTTP ----------------------------------------------------------------------------------

/** The Gateway's structured envelope: `{error:{code,message,retryable,recoveryAction,correlationId}}`. */
export interface GatewayErrorBody {
  error?: {
    code?: string;
    message?: string;
    retryable?: boolean;
    recoveryAction?: string;
    correlationId?: string;
  };
}

export type HttpContext = "account" | "device" | "history" | "download" | "generic";

const GATEWAY_ACTIONS: Record<string, RecoveryAction> = {
  retry: "retry",
  sign_in: "sign-in",
  select_device: "select-device",
  request_code: "none",
  reauthenticate: "sign-in",
  none: "none",
};

/**
 * Map a Gateway HTTP failure. A registered code in the body wins (the Gateway already classified
 * it); otherwise the status decides, per context. Call this only after the single refresh retry.
 */
export function fromHttp(status: number, body: unknown, context: HttpContext = "generic"): AppError {
  const envelope = (typeof body === "object" && body !== null ? body : {}) as GatewayErrorBody;
  const serverCode = envelope.error?.code;
  const correlation = envelope.error?.correlationId ? ` correlationId=${envelope.error.correlationId}` : "";
  const details = `http ${status}${serverCode ? ` ${serverCode}` : ""}${envelope.error?.message ? ` ${envelope.error.message}` : ""}${correlation}`;
  const action = envelope.error?.recoveryAction ? GATEWAY_ACTIONS[envelope.error.recoveryAction] : undefined;
  const serverRetryable = typeof envelope.error?.retryable === "boolean" ? envelope.error.retryable : undefined;
  if (isKnownCode(serverCode)) {
    return appError(serverCode, details, {
      ...(serverRetryable !== undefined ? { retryable: serverRetryable } : {}),
      ...(action ? { action } : {}),
    });
  }
  if (typeof serverCode === "string" && /^HR-[A-Z]+-\d{3}$/.test(serverCode)) {
    // Registered upstream of this build: keep the Gateway's code; its message is user copy in English.
    const fallback = CATALOG["HR-RPC-001"];
    return {
      code: serverCode,
      zh: fallback.zh,
      en: envelope.error?.message?.trim() || fallback.en,
      retryable: serverRetryable ?? false,
      action: action ?? "details",
      details: redact(details),
    };
  }
  if (status === 401) return appError("HR-AUTH-003", details);
  if (status === 403) return context === "download" ? appError("HR-FILE-003", details) : appError("HR-WEB-001", details);
  if (status === 404) {
    if (context === "history") return appError("HR-SESS-001", details);
    if (context === "download") return appError("HR-FILE-005", details);
    return appError("HR-WEB-005", details);
  }
  if (status === 413 && context === "download") return appError("HR-FILE-004", details);
  if (status === 429) return appError(context === "account" ? "HR-AUTH-007" : "HR-WEB-004", details);
  if (status >= 500) {
    if (context === "history") return appError("HR-SYNC-003", details);
    if (context === "account") return appError("HR-ACCOUNT-002", details);
    if (context === "download") return appError("HR-FILE-006", details);
    return appError("HR-WEB-004", details);
  }
  return context === "download" ? appError("HR-FILE-006", details) : appError("HR-WEB-005", details);
}

/**
 * `fetch` rejected (no HTTP answer at all). Offline → HR-CONN-001; online but unreachable →
 * HR-WEB-003.
 */
export function fromNetworkFailure(cause: unknown, online: boolean = typeof navigator === "undefined" ? true : navigator.onLine): AppError {
  const details = cause instanceof Error ? `${cause.name}: ${cause.message}` : String(cause);
  return appError(online ? "HR-WEB-003" : "HR-CONN-001", details);
}

/** A 2xx body this build cannot parse. */
export function unreadableResponse(context: HttpContext, cause: unknown): AppError {
  const details = cause instanceof Error ? cause.message : String(cause);
  return appError(context === "history" ? "HR-SYNC-004" : "HR-WEB-005", details);
}
