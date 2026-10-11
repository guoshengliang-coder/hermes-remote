import { ACCOUNT_CONNECTOR_PROTOCOL_VERSION, type ConnectorPreflightRequest, type ConnectorPreflightResult } from "@hermes-remote/protocol";
import { displayVersion } from "./hermes-contract.js";
import { boundedResponseBody, type HermesAuth } from "./hermes-auth.js";

export const CONNECTOR_HEALTH_PROBE_TIMEOUT_MS = 10_000;
const MAX_HEALTH_BYTES = 64 * 1024;
export interface HermesHealth { reachable: boolean; version?: string }

/** Prefer process liveness plus a small protected read: /api/health itself is public upstream. */
export async function probeHermesHealth(auth: Pick<HermesAuth, "request" | "hasCredentials">, signal: AbortSignal): Promise<HermesHealth> {
  try {
    let response = await auth.request("/api/health", { method: "GET", signal });
    const lightweight = response.status !== 404 && response.status !== 405;
    if (!lightweight) {
      await response.body?.cancel();
      response = await auth.request("/api/status", { method: "GET", signal });
    }
    if (!response.ok) { await response.body?.cancel(); return { reachable: false }; }
    const value: unknown = JSON.parse(await boundedResponseBody(response, MAX_HEALTH_BYTES));
    if (!value || typeof value !== "object" || Array.isArray(value)) return { reachable: false };
    const status = value as Record<string, unknown>;
    if (lightweight && status.ok !== true) return { reachable: false };
    const version = displayVersion(status.version);
    if (auth.hasCredentials) {
      // Both endpoints are protected in the supported upstreams. No profile/session content leaves
      // the Mac. Older Hermes lacking the small profile read uses one bounded session row instead.
      let proof = await auth.request("/api/profiles/active", { method: "GET", signal });
      if (proof.status === 404 || proof.status === 405) {
        await proof.body?.cancel();
        proof = await auth.request("/api/sessions?limit=1", { method: "GET", signal });
      }
      if (!proof.ok) { await proof.body?.cancel(); return { reachable: false }; }
      const proofValue: unknown = JSON.parse(await boundedResponseBody(proof, MAX_HEALTH_BYTES));
      if (!proofValue || typeof proofValue !== "object") return { reachable: false };
    } else if (status.auth_required === true) {
      return { reachable: false };
    }
    signal.throwIfAborted();
    return { reachable: true, ...(version ? { version } : {}) };
  } catch {
    return { reachable: false };
  }
}

/** One responder belongs to one control socket. Closing/replacing it cancels all its local work. */
export class ConnectorHealthResponder {
  private active?: { requestId: string; controller: AbortController };
  private stopped = false;

  constructor(
    private readonly probe: (signal: AbortSignal) => Promise<HermesHealth>,
    private readonly send: (result: ConnectorPreflightResult) => void,
    private readonly timeoutMs = CONNECTOR_HEALTH_PROBE_TIMEOUT_MS,
  ) {}

  async respond(request: ConnectorPreflightRequest): Promise<void> {
    if (this.stopped || this.active?.requestId === request.requestId) return;
    this.active?.controller.abort();
    const current = { requestId: request.requestId, controller: new AbortController() };
    this.active = current;
    const timer = setTimeout(() => current.controller.abort(new DOMException("Health probe timed out", "TimeoutError")), this.timeoutMs);
    const aborted = new Promise<HermesHealth>((resolve) => {
      current.controller.signal.addEventListener("abort", () => resolve({ reachable: false }), { once: true });
    });
    try {
      const result = await Promise.race([this.probe(current.controller.signal).catch((): HermesHealth => ({ reachable: false })), aborted]);
      if (this.stopped || this.active !== current) return;
      this.send({
        type: "connector.preflight.result", version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        requestId: request.requestId, hermesReachable: result.reachable,
        ...(result.version ? { hermesVersion: result.version } : {}),
      });
    } finally {
      clearTimeout(timer);
      if (this.active === current) this.active = undefined;
    }
  }

  stop(): void {
    this.stopped = true;
    this.active?.controller.abort();
    this.active = undefined;
  }
}
