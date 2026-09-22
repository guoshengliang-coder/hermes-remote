// Diagnostics redaction (docs/ERROR_HANDLING.md "Diagnostics and security"): tokens, auth headers,
// cookies, signed query parameters, e-mail addresses and home-directory names never leave in details.

const RULES: Array<[RegExp, string | ((...m: string[]) => string)]> = [
  // Header lines first, so their whole value goes.
  [/\b(authorization|proxy-authorization|cookie|set-cookie|x-hermes-session-token|x-hermes-csrf)\s*[:=]\s*[^\r\n]*/gi, "$1: [redacted]"],
  [/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [redacted]"],
  [/\bBasic\s+[A-Za-z0-9+/=]{8,}/g, "Basic [redacted]"],
  // Hermes GO web/account tokens (access, refresh, csrf) and similar prefixed secrets.
  [/\bhg[a-z]_[A-Za-z0-9_-]{8,}/g, "[redacted-token]"],
  // Cookie pairs that escaped a header line.
  [/(__(?:Host|Secure)-[A-Za-z0-9_-]+)=[^;\s]*/g, "$1=[redacted]"],
  // Signed URLs / tickets / tokens in query strings or JSON-ish pairs.
  [/([?&](?:token|ticket|access_token|refresh_token|key|sig|signature|code)=)[^&#\s]*/gi, "$1[redacted]"],
  [/("(?:token|accessToken|refreshToken|access_token|refresh_token|csrfToken|password|secret|idToken|code)"\s*:\s*)"[^"]*"/gi, '$1"[redacted]"'],
  [/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[email]"],
  [/\/Users\/[^/\s"'`]+/g, "/Users/<user>"],
  [/\/home\/[^/\s"'`]+/g, "/home/<user>"],
];

export function redact(text: string): string {
  let out = text;
  for (const [pattern, replacement] of RULES) out = out.replace(pattern, replacement as string);
  // Bound the size: details are for copying, not for shipping whole bodies.
  return out.length > 2000 ? `${out.slice(0, 2000)}…` : out;
}
