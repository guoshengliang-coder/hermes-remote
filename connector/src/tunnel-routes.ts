import { CONTRACT_REPORT_PATH, type HermesContractReport } from "./hermes-contract.js";
import { boundedResponseBody } from "./hermes-auth.js";

export const DEFAULT_MODEL_PATH = "/api/hermes-remote/default-model";
const MODEL_INFO_PATH = "/api/model/info";
const PROFILE = /^[\p{L}\p{N}_. -]{1,64}$/u;
const MAX_MODEL_INFO_BYTES = 16 * 1024;
const CONTROL = /[\u0000-\u001f\u007f]/;

/**
 * Who answers a tunnelled HTTP request. `contract` and `files` are the Connector's own routes and
 * are never forwarded to Hermes; everything else is.
 */
export type TunnelHttpRoute = "contract" | "default-model" | "files" | "hermes";

export function tunnelHttpRoute(path: string): TunnelHttpRoute {
  const pathname = path.split(/[?#]/, 1)[0];
  if (pathname === CONTRACT_REPORT_PATH) return "contract";
  if (pathname === DEFAULT_MODEL_PATH) return "default-model";
  if (path.startsWith("/api/files")) return "files";
  return "hermes";
}

export interface LocalJsonResponse {
  status: number;
  body: Record<string, unknown>;
  headers: Record<string, string>;
}

/**
 * The Connector-owned contract report (docs/HERMES_CONTRACT.md §2, "Connector contract check").
 * GET only; the report comes from the monitor's cache, re-checked only when it is stale.
 */
export async function contractReportResponse(
  method: string,
  report: () => Promise<HermesContractReport>,
): Promise<LocalJsonResponse> {
  if (method.toUpperCase() !== "GET") {
    return { status: 405, body: { error: "method_not_allowed" }, headers: { allow: "GET" } };
  }
  return { status: 200, body: { ...await report() }, headers: { "cache-control": "no-store" } };
}

/** A Connector-owned browser read. Upstream metadata never crosses the tunnel. */
export async function defaultModelResponse(
  method: string,
  path: string,
  hasBody: boolean,
  fetchModelInfo: (path: string) => Promise<Response>,
): Promise<LocalJsonResponse> {
  const headers = { "cache-control": "private, no-store" };
  const fail = (status: number): LocalJsonResponse => ({
    status,
    headers,
    body: { error: { code: "HR-WEB-009", message: "Default model unavailable", retryable: true } },
  });
  if (method !== "GET" && method !== "HEAD") return fail(405);
  if (hasBody) return fail(400);
  let profile: string | null = null;
  try {
    const url = new URL(path, "http://connector.local");
    if (url.pathname !== DEFAULT_MODEL_PATH || url.hash || url.searchParams.size > 1) return fail(400);
    if (url.searchParams.size === 1) {
      if ([...url.searchParams.keys()][0] !== "profile") return fail(400);
      profile = url.searchParams.get("profile");
      if (!profile || !PROFILE.test(profile)) return fail(400);
    }
  } catch {
    return fail(400);
  }
  let upstream: Response | undefined;
  try {
    const sourcePath = profile ? `${MODEL_INFO_PATH}?profile=${encodeURIComponent(profile)}` : MODEL_INFO_PATH;
    upstream = await fetchModelInfo(sourcePath);
    if (!upstream.ok) return fail(502);
    const raw = await boundedResponseBody(upstream, MAX_MODEL_INFO_BYTES);
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null || Array.isArray(value)) return fail(502);
    const { model, provider } = value as Record<string, unknown>;
    if (typeof model !== "string" || !model.trim() || model.length > 128 || CONTROL.test(model)
        || typeof provider !== "string" || !provider.trim() || provider.length > 64 || CONTROL.test(provider)) return fail(502);
    return { status: 200, headers, body: { model, provider } };
  } catch {
    return fail(502);
  } finally {
    await upstream?.body?.cancel().catch(() => undefined);
  }
}
