import {
  evaluateHermesContract,
  type HermesContractReport,
  type OpenApiFetchResult,
} from "./hermes-contract.js";

/** How the monitor reaches the local Hermes. Injected so tests never need a server. */
export interface HermesContractProbe {
  /** `GET /openapi.json`, authenticated like every other Connector→Hermes call. Never throws. */
  fetchOpenApi(): Promise<OpenApiFetchResult>;
  /**
   * The `version` from `GET /api/status`. `undefined` when Hermes answered without one; throws when
   * Hermes could not be reached at all.
   */
  fetchStatusVersion(): Promise<string | undefined>;
}

export interface HermesContractMonitorOptions {
  now?: () => Date;
  /** How long an `unknown` result is trusted before the next caller triggers another look. */
  unknownRetryMs?: number;
  /** One line per *change* of result; an unchanged result is not re-logged. */
  log?: (level: "info" | "error", kind: string, fields: Record<string, unknown>) => void;
}

/**
 * Runs the contract check at most once per Hermes process and version.
 *
 * `openapi.json` is ~250 KB on 0.21.3 and Hermes regenerates nothing between restarts, so the
 * result is cached and a check runs only when something can have changed it:
 * - Connector startup and every (re)connect of the session observer's socket to Hermes — a
 *   reconnect is what a Hermes restart (`hermes update` kickstarts the job) looks like from here;
 * - `/api/status` reporting a different `version` than the cached result was taken against, which
 *   callers learn cheaply through [ensureFresh] and [observeVersion];
 * - a cached `unknown` older than [HermesContractMonitorOptions.unknownRetryMs] — Hermes may simply
 *   have been starting up.
 *
 * Concurrent triggers share one check. Nothing here ever blocks or refuses relaying: the result is
 * a report, not a gate.
 */
export class HermesContractMonitor {
  private report?: HermesContractReport;
  private inFlight?: Promise<HermesContractReport>;
  private lastFingerprint?: string;
  private readonly now: () => Date;
  private readonly unknownRetryMs: number;

  constructor(
    private readonly probe: HermesContractProbe,
    private readonly options: HermesContractMonitorOptions = {},
  ) {
    this.now = options.now ?? (() => new Date());
    this.unknownRetryMs = options.unknownRetryMs ?? 60_000;
  }

  current(): HermesContractReport | undefined {
    return this.report;
  }

  /** Re-check unconditionally (startup, Hermes reconnect). Shares a check already running. */
  refresh(trigger: string): Promise<HermesContractReport> {
    if (!this.inFlight) {
      this.inFlight = this.check(trigger).finally(() => {
        this.inFlight = undefined;
      });
    }
    return this.inFlight;
  }

  /**
   * The report to serve now. Reads `/api/status` (small, public) and re-checks only when the
   * version moved, nothing is cached yet, or a cached `unknown` has gone stale.
   */
  async ensureFresh(trigger: string): Promise<HermesContractReport> {
    if (this.inFlight) return this.inFlight;
    const cached = this.report;
    if (!cached) return this.refresh(trigger);
    let version: string | undefined;
    try {
      version = await this.probe.fetchStatusVersion();
    } catch {
      // Hermes is not answering at all. That is HR-CONN-006's to report, not a contract finding;
      // keep serving the last result rather than replacing it with a guess.
      return cached;
    }
    if (this.isStale(cached, version)) return this.refresh(trigger);
    return cached;
  }

  /** A version learned elsewhere (the account preflight's `/api/status`). Re-checks if it moved. */
  observeVersion(version: string | undefined, trigger: string): void {
    const cached = this.report;
    if (cached && !this.isStale(cached, version)) return;
    void this.refresh(trigger);
  }

  private isStale(cached: HermesContractReport, version: string | undefined): boolean {
    if (version !== undefined && version !== cached.hermesVersion) return true;
    if (cached.status !== "unknown") return false;
    return this.now().getTime() - Date.parse(cached.checkedAt) >= this.unknownRetryMs;
  }

  private async check(trigger: string): Promise<HermesContractReport> {
    let hermesVersion: string | undefined;
    try {
      hermesVersion = await this.probe.fetchStatusVersion();
    } catch {
      hermesVersion = undefined;
    }
    let openapi: OpenApiFetchResult;
    try {
      openapi = await this.probe.fetchOpenApi();
    } catch {
      openapi = { kind: "unreachable" };
    }
    const report = evaluateHermesContract({ openapi, hermesVersion, checkedAt: this.now() });
    this.report = report;
    this.logIfChanged(report, trigger);
    return report;
  }

  private logIfChanged(report: HermesContractReport, trigger: string): void {
    const missing = report.missing.map((entry) => `${entry.method} ${entry.path}`).join(", ");
    const fingerprint = [report.status, report.code, report.hermesVersion, report.reason, missing].join("|");
    if (fingerprint === this.lastFingerprint) return;
    this.lastFingerprint = fingerprint;
    const fields: Record<string, unknown> = {
      status: report.status,
      code: report.code,
      trigger,
      version: report.hermesVersion,
      minimum: report.minimumHermesVersion,
      checked: report.checkedPaths,
      reason: report.reason,
      httpStatus: report.httpStatus,
      missingRequired: report.missing.filter((entry) => entry.tier === "required").length,
      missingOptional: report.missing.filter((entry) => entry.tier === "optional").length,
      missing: missing || undefined,
    };
    const level = report.status === "breaking" || report.status === "degraded" ? "error" : "info";
    this.options.log?.(level, "hermes.contract", fields);
  }
}
