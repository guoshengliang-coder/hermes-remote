/**
 * The upstream Hermes REST contract the Hermes GO app depends on, and the check that compares it
 * against what the local Hermes says it serves.
 *
 * Hermes GO no longer pins the Hermes version: on a Mac in local runtime mode the phone is served
 * by the owner's own `~/.hermes/hermes-agent`, which moves whenever they run `hermes update`
 * (docs/MANAGED_HERMES_STRATEGY.md, principle 2). An upstream rename used to surface only later, as
 * a vague failure on the phone. This module turns it into a registered `HR-COMPAT-*` code instead.
 *
 * [HERMES_REST_CONTRACT] is the single source of truth for the list. docs/HERMES_CONTRACT.md §2
 * renders the same list as a table, and `hermes-contract.test.ts` fails when the two disagree — so
 * the document cannot drift from the code, and nobody has to maintain the list twice by hand.
 */

/** Whether a missing path takes the phone down (`required`) or one feature (`optional`). */
export type ContractTier = "required" | "optional";

/** The feature a path belongs to; shown to the owner so "degraded" says *what* is degraded. */
export type ContractFeature =
  | "status"
  | "sessions"
  | "history"
  | "search"
  | "profiles"
  | "projects"
  | "config"
  | "cron"
  | "models"
  | "tools"
  | "skills"
  | "analytics"
  | "voice"
  | "messaging";

export type HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export interface ContractPath {
  method: HttpMethod;
  /** Upstream path with every parameter written as `{id}`; parameter names are not compared. */
  path: string;
  tier: ContractTier;
  feature: ContractFeature;
}

const required = (method: HttpMethod, path: string, feature: ContractFeature): ContractPath =>
  ({ method, path, tier: "required", feature });
const optional = (method: HttpMethod, path: string, feature: ContractFeature): ContractPath =>
  ({ method, path, tier: "optional", feature });

/**
 * Every upstream REST call the Android app makes (`HermesRestApi.kt`).
 *
 * **Required** is the phone's reason to exist: knowing the Mac is there, listing conversations and
 * reading one. Without any of these the app cannot show a conversation at all, so their absence is
 * `breaking`. Everything else is one feature and is `optional` — a missing cron route must not be
 * reported as if chat were down.
 *
 * Deliberately **not** listed, because `openapi.json` cannot vouch for them:
 * - `/api/files`, `/api/files/upload` — served by this Connector itself (`handleFileRequest`),
 *   never forwarded to Hermes;
 * - `/api/ws` — a WebSocket route, absent from every OpenAPI document; the session observer's own
 *   socket exercises it on every (re)connect;
 * - `/api/mobile/events*` — the account service's, not upstream's (docs/HERMES_CONTRACT.md §2).
 */
export const HERMES_REST_CONTRACT: readonly ContractPath[] = Object.freeze([
  required("GET", "/api/status", "status"),
  required("GET", "/api/sessions", "sessions"),
  required("GET", "/api/profiles/sessions", "sessions"),
  required("GET", "/api/sessions/{id}/messages", "history"),

  optional("PATCH", "/api/sessions/{id}", "sessions"),
  optional("DELETE", "/api/sessions/{id}", "sessions"),
  optional("GET", "/api/sessions/stats", "sessions"),
  optional("GET", "/api/sessions/search", "search"),
  optional("GET", "/api/profiles", "profiles"),
  optional("GET", "/api/profiles/active", "profiles"),
  optional("POST", "/api/profiles/active", "profiles"),
  optional("GET", "/api/fs/list", "projects"),
  optional("GET", "/api/fs/git-root", "projects"),
  optional("GET", "/api/fs/default-cwd", "projects"),
  optional("GET", "/api/config", "config"),
  optional("PUT", "/api/config", "config"),
  optional("GET", "/api/env", "config"),
  optional("PUT", "/api/env", "config"),
  optional("POST", "/api/env/reveal", "config"),
  optional("GET", "/api/cron/jobs", "cron"),
  optional("POST", "/api/cron/jobs", "cron"),
  optional("GET", "/api/cron/jobs/{id}", "cron"),
  optional("PUT", "/api/cron/jobs/{id}", "cron"),
  optional("DELETE", "/api/cron/jobs/{id}", "cron"),
  optional("GET", "/api/cron/jobs/{id}/runs", "cron"),
  optional("POST", "/api/cron/jobs/{id}/pause", "cron"),
  optional("POST", "/api/cron/jobs/{id}/resume", "cron"),
  optional("POST", "/api/cron/jobs/{id}/trigger", "cron"),
  optional("GET", "/api/cron/delivery-targets", "cron"),
  optional("GET", "/api/model/options", "models"),
  optional("GET", "/api/model/info", "models"),
  optional("POST", "/api/model/set", "models"),
  optional("PUT", "/api/profiles/{id}/model", "models"),
  optional("GET", "/api/tools/toolsets", "tools"),
  optional("GET", "/api/skills", "skills"),
  optional("PUT", "/api/skills/toggle", "skills"),
  optional("GET", "/api/analytics/usage", "analytics"),
  optional("POST", "/api/audio/transcribe", "voice"),
  optional("GET", "/api/messaging/platforms", "messaging"),
  optional("PUT", "/api/messaging/platforms/{id}", "messaging"),
  optional("POST", "/api/messaging/platforms/{id}/test", "messaging"),
  optional("POST", "/api/gateway/restart", "messaging"),
].map((entry) => Object.freeze(entry)));

/**
 * The oldest Hermes the contract above was verified against (docs/HERMES_CONTRACT.md, "Adapted
 * upstream version"). Older is not known to be broken — only unverified — so it is `degraded`,
 * never `breaking`.
 */
export const MINIMUM_HERMES_VERSION = "0.21.0";

/** Where upstream's FastAPI app publishes its schema (FastAPI's default `openapi_url`). */
export const HERMES_OPENAPI_PATH = "/openapi.json";

/** The Connector-owned route the phone reads the report from; never forwarded to Hermes. */
export const CONTRACT_REPORT_PATH = "/api/hermes-remote/contract";

export type ContractStatus = "compatible" | "degraded" | "breaking" | "unknown";

/** Registered in docs/ERROR_HANDLING.md. None is retryable: only updating Hermes or the app helps. */
export const CONTRACT_CODES = Object.freeze({
  breaking: "HR-COMPAT-001",
  missingOptional: "HR-COMPAT-002",
  belowMinimum: "HR-COMPAT-003",
} as const);

export type ContractCode = typeof CONTRACT_CODES[keyof typeof CONTRACT_CODES];

/** Why a check came back `unknown`. A fixed vocabulary: no upstream text, URL or token leaks out. */
export type UnknownReason =
  | "openapi_unreachable"
  | "openapi_http_status"
  | "openapi_too_large"
  | "openapi_invalid"
  | "openapi_unrecognized";

export interface MissingPath {
  method: HttpMethod;
  path: string;
  tier: ContractTier;
  feature: ContractFeature;
}

/** The payload served at [CONTRACT_REPORT_PATH]. `schema` moves only on an incompatible change. */
export interface HermesContractReport {
  schema: 1;
  status: ContractStatus;
  /** Present exactly when the phone should show something. */
  code?: ContractCode;
  retryable: false;
  hermesVersion?: string;
  minimumHermesVersion: string;
  versionBelowMinimum: boolean;
  /** Required paths first, then optional, each in contract order. */
  missing: MissingPath[];
  checkedPaths: number;
  reason?: UnknownReason;
  httpStatus?: number;
  checkedAt: string;
}

export type OpenApiFetchResult =
  | { kind: "ok"; body: string }
  | { kind: "http_status"; status: number }
  | { kind: "too_large" }
  | { kind: "unreachable" };

/**
 * Pure: compare one OpenAPI document with the contract. Anything that is not a readable OpenAPI
 * document is `unknown`, never `breaking` — a check that could not look must not claim it saw a
 * missing route, or the phone would tell the owner their Hermes is broken because a proxy
 * returned HTML.
 */
export function evaluateHermesContract(input: {
  openapi: OpenApiFetchResult;
  hermesVersion?: string;
  checkedAt: Date;
  contract?: readonly ContractPath[];
  minimumVersion?: string;
}): HermesContractReport {
  const contract = input.contract ?? HERMES_REST_CONTRACT;
  const minimumHermesVersion = input.minimumVersion ?? MINIMUM_HERMES_VERSION;
  const hermesVersion = displayVersion(input.hermesVersion);
  const versionBelowMinimum = hermesVersion !== undefined
    && isVersionBelow(hermesVersion, minimumHermesVersion);
  const base = {
    schema: 1 as const,
    retryable: false as const,
    ...(hermesVersion === undefined ? {} : { hermesVersion }),
    minimumHermesVersion,
    versionBelowMinimum,
    checkedAt: input.checkedAt.toISOString(),
  };
  const unknown = (reason: UnknownReason, httpStatus?: number): HermesContractReport => ({
    ...base,
    status: "unknown",
    missing: [],
    checkedPaths: 0,
    reason,
    ...(httpStatus === undefined ? {} : { httpStatus }),
  });

  switch (input.openapi.kind) {
    case "unreachable": return unknown("openapi_unreachable");
    case "too_large": return unknown("openapi_too_large");
    case "http_status": return unknown("openapi_http_status", input.openapi.status);
    case "ok": break;
  }

  const served = servedOperations(input.openapi.body);
  if (!served) return unknown("openapi_invalid");

  const missing: MissingPath[] = [];
  let matchedAny = false;
  for (const entry of contract) {
    const methods = served.get(normalizePath(entry.path));
    if (methods) matchedAny = true;
    if (!methods?.has(entry.method)) {
      missing.push({ method: entry.method, path: entry.path, tier: entry.tier, feature: entry.feature });
    }
  }
  // A document that describes none of Hermes' routes is describing some other server (a proxy's
  // own schema, an unrelated app on the port). Reporting every route missing would be a lie.
  if (!matchedAny) return unknown("openapi_unrecognized");

  missing.sort((left, right) => tierRank(left.tier) - tierRank(right.tier));
  const breaking = missing.some((entry) => entry.tier === "required");
  const status: ContractStatus = breaking
    ? "breaking"
    : missing.length > 0 || versionBelowMinimum ? "degraded" : "compatible";
  const code = breaking
    ? CONTRACT_CODES.breaking
    : missing.length > 0 ? CONTRACT_CODES.missingOptional
      : versionBelowMinimum ? CONTRACT_CODES.belowMinimum : undefined;
  return {
    ...base,
    status,
    ...(code === undefined ? {} : { code }),
    missing,
    checkedPaths: contract.length,
  };
}

/** `/api/sessions/{session_id}` and `/api/sessions/{id}` are the same route to a client. */
export function normalizePath(path: string): string {
  return path.replace(/\{[^}]*\}/g, "{}").replace(/\/+$/, "") || "/";
}

const HTTP_METHODS = new Set(["get", "post", "put", "patch", "delete", "head", "options", "trace"]);

/** path → upper-case methods, or null when the body is not an OpenAPI document with `paths`. */
function servedOperations(body: string): Map<string, Set<string>> | null {
  let document: unknown;
  try {
    document = JSON.parse(body);
  } catch {
    return null;
  }
  if (!isRecord(document) || !isRecord(document.paths)) return null;
  if (typeof document.openapi !== "string" && typeof document.swagger !== "string") return null;
  const served = new Map<string, Set<string>>();
  for (const [path, item] of Object.entries(document.paths)) {
    if (!isRecord(item)) continue;
    const key = normalizePath(path);
    const methods = served.get(key) ?? new Set<string>();
    for (const name of Object.keys(item)) {
      if (HTTP_METHODS.has(name.toLowerCase())) methods.add(name.toUpperCase());
    }
    served.set(key, methods);
  }
  return served;
}

/**
 * Numeric `major.minor.patch` comparison. A version this cannot read is never "below": the check
 * only claims what it can actually establish.
 */
export function isVersionBelow(version: string, minimum: string): boolean {
  const left = numericVersion(version);
  const right = numericVersion(minimum);
  if (!left || !right) return false;
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return left[index] < right[index];
  }
  return false;
}

function numericVersion(value: string): [number, number, number] | null {
  const match = /^v?(\d+)\.(\d+)(?:\.(\d+))?/.exec(value.trim());
  if (!match) return null;
  return [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)];
}

/** Keep only a short printable version string; anything else is dropped rather than relayed. */
export function displayVersion(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > 64 || /[\u0000-\u001f\u007f]/.test(trimmed)) return undefined;
  return trimmed;
}

function tierRank(tier: ContractTier): number {
  return tier === "required" ? 0 : 1;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
