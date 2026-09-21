import { CONTRACT_REPORT_PATH, type HermesContractReport } from "./hermes-contract.js";

/**
 * Who answers a tunnelled HTTP request. `contract` and `files` are the Connector's own routes and
 * are never forwarded to Hermes; everything else is.
 */
export type TunnelHttpRoute = "contract" | "files" | "hermes";

export function tunnelHttpRoute(path: string): TunnelHttpRoute {
  const pathname = path.split(/[?#]/, 1)[0];
  if (pathname === CONTRACT_REPORT_PATH) return "contract";
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
