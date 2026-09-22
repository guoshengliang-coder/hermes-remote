import { appError, type AppError } from "../errors";

// Mac-side health for the list's strip (Android GatewayHealthMonitor + HermesContract.kt, DESIGN
// §5.20): the Connector's contract report on the Mac's Hermes. `unknown` and schemas this build does
// not know show nothing.

export interface ContractReport {
  schema?: number;
  status?: string;
  code?: string | null;
  hermesVersion?: string | null;
  minimumHermesVersion?: string | null;
  versionBelowMinimum?: boolean;
  missing?: { method?: string; path?: string; tier?: string; feature?: string }[];
}

export interface HealthNotice {
  severity: "breaking" | "degraded";
  error: AppError;
}

const COMPAT = new Set(["HR-COMPAT-001", "HR-COMPAT-002", "HR-COMPAT-003"]);

export function contractNotice(report: ContractReport | null | undefined): HealthNotice | null {
  if (!report || report.schema !== 1) return null;
  const severity = report.status === "breaking" ? "breaking" : report.status === "degraded" ? "degraded" : null;
  if (!severity) return null;
  const fallback = severity === "breaking" ? "HR-COMPAT-001" : "HR-COMPAT-002";
  const code = (report.code && COMPAT.has(report.code) ? report.code : fallback) as "HR-COMPAT-001" | "HR-COMPAT-002" | "HR-COMPAT-003";
  const missing = (report.missing ?? []).slice(0, 12).map((m) => `${m.method ?? ""} ${m.path ?? ""} (${m.tier ?? ""})`);
  const cause = [
    `hermes=${(report.hermesVersion ?? "?").slice(0, 64)}`,
    report.minimumHermesVersion ? `minimum=${report.minimumHermesVersion.slice(0, 64)}` : "",
    report.versionBelowMinimum ? "belowMinimum=true" : "",
    missing.length ? `missing=${missing.join("; ")}` : "",
  ]
    .filter(Boolean)
    .join(" ");
  return { severity, error: appError(code, cause) };
}
