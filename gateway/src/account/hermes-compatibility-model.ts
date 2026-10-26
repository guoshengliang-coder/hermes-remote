import { createHash } from "node:crypto";
import { AccountModeError } from "./model.js";

/** Separate from the live REST health check: certification of an exact upgrade path. */
export const REQUIRED_COMPATIBILITY_CHECKS = [
  "installation", "authentication", "rest", "rpc", "history", "configuration", "connector",
  "serviceRecovery", "upgrade", "recovery", "maintenanceWindow", "processInterruption",
  "desktopLifecycle", "clientBusiness", "newDataProtection",
] as const;
export const COMPONENT_NAMES = ["desktop", "connector", "gateway", "android", "web"] as const;
export interface TestedComponent {
  name: typeof COMPONENT_NAMES[number];
  version: string;
  artifactSha256: string;
}
export const HERMES_MAINTENANCE_POLICY = "go-controlled-window-v1" as const;
export interface ValidationIdentity {
  schemaVersion: 1;
  maintenancePolicy: typeof HERMES_MAINTENANCE_POLICY;
  candidateId: string;
  version: string;
  sourceCommit: string;
  targetCommit: string;
  goCommit: string;
  suiteSha256: string;
  installerSha256: string;
  environment: {
    os: "macos";
    arch: "arm64";
    osVersion: string;
    installation: "legacy-venv-to-pm";
  };
  components: TestedComponent[];
}
export interface ValidationReport {
  schemaVersion: 1;
  runId: string;
  identity: ValidationIdentity;
  startedAt: string;
  finishedAt: string;
  outcome: "passed" | "failed" | "infrastructure_failed" | "cancelled";
  checks: Array<{
    name: typeof REQUIRED_COMPATIBILITY_CHECKS[number];
    status: "passed" | "failed" | "infrastructure_failed" | "not_run";
    evidenceSha256: string | null;
  }>;
  evidenceBundleSha256: string;
}

export class CompatibilityError extends AccountModeError {
  constructor(status: number, suffix: string, summaryZh: string, readonly summaryEn: string,
    retryable: boolean, readonly action: "refresh_account" | "refresh" | "review_report" | "retry" | "review_publication" | "review_job") {
    super(status, `HR-HERMESCOMPAT-${suffix}`, summaryZh, retryable, retryable ? "retry" : "none");
    this.name = "CompatibilityError";
  }
}
export const compatibilityErrors = {
  forbidden: () => new CompatibilityError(403, "001",
    "此账号没有 Hermes 兼容版本管理权限，请刷新账号状态。",
    "This account cannot manage Hermes compatibility. Refresh the account status.", false, "refresh_account"),
  invalid: () => new CompatibilityError(400, "002",
    "无法处理这条兼容记录，请刷新页面并检查输入。",
    "This compatibility record could not be processed. Refresh the page and check the input.", false, "refresh"),
  conflict: () => new CompatibilityError(409, "003",
    "兼容记录已变化，请刷新后重新确认。",
    "The compatibility record changed. Refresh it before confirming again.", false, "refresh"),
  evidenceIncomplete: () => new CompatibilityError(409, "004",
    "验证证据尚不满足推荐条件，请查看报告并完成验证。",
    "The evidence is not sufficient for a recommendation. Review the report and complete validation.", false, "review_report"),
  unavailable: () => new CompatibilityError(503, "005",
    "暂时无法连接兼容验证服务，请稍后重试。",
    "The compatibility validation service is unavailable. Try again later.", true, "retry"),
  publicationIncomplete: () => new CompatibilityError(503, "006",
    "推荐清单尚未发布完成，请查看发布记录后重试。",
    "The recommendation list has not finished publishing. Review the publication record and retry.", true, "review_publication"),
};

function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw compatibilityErrors.invalid();
  const actual = Object.keys(value);
  if (actual.length !== keys.length || actual.some(k => !keys.includes(k))) throw compatibilityErrors.invalid();
  return value as Record<string, unknown>;
}
function text(value: unknown, pattern: RegExp, max: number): string {
  if (typeof value !== "string" || value.length > max || /[\r\n\u0000]/.test(value) || !pattern.test(value)) {
    throw compatibilityErrors.invalid();
  }
  return value;
}
export const compatibilityUUID = (value: unknown): string =>
  text(value, /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/, 36);
export const compatibilityCommit = (value: unknown): string => text(value, /^[a-f0-9]{40}$/, 40);
export const compatibilityHash = (value: unknown): string => text(value, /^[a-f0-9]{64}$/, 64);
export const compatibilityVersion = (value: unknown): string =>
  text(value, /^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$/, 29);
export function compatibilityRevision(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw compatibilityErrors.invalid();
  return value as number;
}
function timestamp(value: unknown): string {
  const result = text(value, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/, 20);
  if (!Number.isFinite(Date.parse(result)) || new Date(result).toISOString() !== result.replace("Z", ".000Z")) {
    throw compatibilityErrors.invalid();
  }
  return result;
}
export function parseTestedComponents(value: unknown): TestedComponent[] {
  if (!Array.isArray(value) || value.length !== COMPONENT_NAMES.length) throw compatibilityErrors.invalid();
  const components = value.map((item): TestedComponent => {
    const v = object(item, ["name", "version", "artifactSha256"]);
    if (!COMPONENT_NAMES.includes(v.name as TestedComponent["name"])) throw compatibilityErrors.invalid();
    return { name: v.name as TestedComponent["name"], version: compatibilityVersion(v.version),
      artifactSha256: compatibilityHash(v.artifactSha256) };
  });
  if (new Set(components.map(c => c.name)).size !== COMPONENT_NAMES.length) throw compatibilityErrors.invalid();
  // Array order is part of the wire contract, preventing equivalent-but-different approval digests.
  if (components.some((c, i) => c.name !== COMPONENT_NAMES[i])) throw compatibilityErrors.invalid();
  return components;
}
export function parseValidationIdentity(value: unknown): ValidationIdentity {
  const v = object(value, ["schemaVersion", "maintenancePolicy", "candidateId", "version", "sourceCommit", "targetCommit",
    "goCommit", "suiteSha256", "installerSha256", "environment", "components"]);
  const env = object(v.environment, ["os", "arch", "osVersion", "installation"]);
  if (v.schemaVersion !== 1 || v.maintenancePolicy !== HERMES_MAINTENANCE_POLICY || env.os !== "macos" || env.arch !== "arm64"
      || env.installation !== "legacy-venv-to-pm" || v.sourceCommit === v.targetCommit) throw compatibilityErrors.invalid();
  return { schemaVersion: 1, maintenancePolicy: HERMES_MAINTENANCE_POLICY, candidateId: compatibilityUUID(v.candidateId), version: compatibilityVersion(v.version),
    sourceCommit: compatibilityCommit(v.sourceCommit), targetCommit: compatibilityCommit(v.targetCommit),
    goCommit: compatibilityCommit(v.goCommit), suiteSha256: compatibilityHash(v.suiteSha256),
    installerSha256: compatibilityHash(v.installerSha256),
    environment: { os: "macos", arch: "arm64", osVersion: compatibilityVersion(env.osVersion), installation: "legacy-venv-to-pm" },
    components: parseTestedComponents(v.components) };
}
export function parseValidationReport(value: unknown): ValidationReport {
  const v = object(value, ["schemaVersion", "runId", "identity", "startedAt", "finishedAt", "outcome", "checks", "evidenceBundleSha256"]);
  if (v.schemaVersion !== 1 || !["passed", "failed", "infrastructure_failed", "cancelled"].includes(v.outcome as string)
      || !Array.isArray(v.checks) || v.checks.length > REQUIRED_COMPATIBILITY_CHECKS.length) throw compatibilityErrors.invalid();
  const startedAt = timestamp(v.startedAt), finishedAt = timestamp(v.finishedAt);
  if (finishedAt < startedAt) throw compatibilityErrors.invalid();
  const checks = v.checks.map((item): ValidationReport["checks"][number] => {
    const check = object(item, ["name", "status", "evidenceSha256"]);
    if (!REQUIRED_COMPATIBILITY_CHECKS.includes(check.name as ValidationReport["checks"][number]["name"])
        || !["passed", "failed", "infrastructure_failed", "not_run"].includes(check.status as string)) throw compatibilityErrors.invalid();
    if (check.status === "passed" && check.evidenceSha256 === null) throw compatibilityErrors.invalid();
    return { name: check.name as ValidationReport["checks"][number]["name"],
      status: check.status as ValidationReport["checks"][number]["status"],
      evidenceSha256: check.evidenceSha256 === null ? null : compatibilityHash(check.evidenceSha256) };
  });
  if (new Set(checks.map(c => c.name)).size !== checks.length) throw compatibilityErrors.invalid();
  return { schemaVersion: 1, runId: compatibilityUUID(v.runId), identity: parseValidationIdentity(v.identity),
    startedAt, finishedAt, outcome: v.outcome as ValidationReport["outcome"], checks,
    evidenceBundleSha256: compatibilityHash(v.evidenceBundleSha256) };
}

/** Called only on parsed JSON records; unsupported values fail closed rather than disappearing. */
export function compatibilityDigest(value: unknown): string {
  function canonical(v: unknown): string {
    if (v === null || typeof v === "boolean" || typeof v === "string") return JSON.stringify(v);
    if (typeof v === "number" && Number.isSafeInteger(v)) return JSON.stringify(v);
    if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
    if (v && typeof v === "object" && Object.getPrototypeOf(v) === Object.prototype) {
      return "{" + Object.keys(v).sort().map(k => JSON.stringify(k) + ":" + canonical((v as Record<string, unknown>)[k])).join(",") + "}";
    }
    throw compatibilityErrors.invalid();
  }
  return createHash("sha256").update(canonical(value)).digest("hex");
}

/** Evidence policy only. Authorization/immutable run provenance are separate mandatory gates. */
export function requireApprovableReport(input: {
  runId: string; identity: ValidationIdentity; report: ValidationReport;
  releasedComponents: TestedComponent[];
}): string {
  try {
    const report = parseValidationReport(input.report);
    const expected = parseValidationIdentity(input.identity);
    if (report.runId !== compatibilityUUID(input.runId) || report.outcome !== "passed"
        || compatibilityDigest(report.identity) !== compatibilityDigest(expected)
        || report.checks.length !== REQUIRED_COMPATIBILITY_CHECKS.length
        || report.checks.some(c => c.status !== "passed")
        || compatibilityDigest(report.identity.components) !== compatibilityDigest(parseTestedComponents(input.releasedComponents))) {
      throw compatibilityErrors.evidenceIncomplete();
    }
    return compatibilityDigest(report);
  } catch {
    throw compatibilityErrors.evidenceIncomplete();
  }
}
