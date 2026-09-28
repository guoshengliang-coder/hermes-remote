/** Only fixed categories cross the diagnostic boundary; fetch errors can contain local URLs. */
const CAUSE_CODES = new Set([
  "ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH", "ETIMEDOUT",
  "UND_ERR_CONNECT_TIMEOUT", "UND_ERR_HEADERS_TIMEOUT", "UND_ERR_SOCKET",
]);

export function localHttpFailureFields(error: unknown): { errorType: string; causeCode: string } {
  const value = error instanceof Error ? error : undefined;
  const cause = value?.cause;
  const rawCode = typeof cause === "object" && cause !== null && "code" in cause
    ? (cause as { code?: unknown }).code : undefined;
  return {
    errorType: value?.name === "TypeError" || value?.name === "TimeoutError"
      || value?.name === "AbortError" ? value.name : "Other",
    causeCode: typeof rawCode === "string" && CAUSE_CODES.has(rawCode) ? rawCode : "unknown",
  };
}

export function localHttpRoute(path: string): "history" | "other" {
  return /^\/api\/sessions\/[^/]+\/messages(?:\?|$)/.test(path) ? "history" : "other";
}

export function hermesUnavailableBody(requestId: string): string {
  return JSON.stringify({ error: {
    code: "HR-CONN-006",
    message: "Hermes service unavailable",
    retryable: true,
    recoveryAction: "retry",
    correlationId: requestId,
  } });
}
