const DEFINITIONS = Object.freeze({
  config: Object.freeze({
    code: "HR-OPS-001",
    summaryZh: "Cloud Ops 配置或主机前置条件无效，请修正后重试。",
    summaryEn: "The Cloud Ops configuration or host prerequisites are invalid. Fix them and retry.",
    retryable: true,
    recoveryAction: "fix_configuration_and_retry",
  }),
  artifact: Object.freeze({
    code: "HR-OPS-002",
    summaryZh: "Gateway 制品身份或完整性校验失败，已阻止安装。",
    summaryEn: "Gateway artifact identity or integrity verification failed, so installation was blocked.",
    retryable: false,
    recoveryAction: "replace_artifact",
  }),
  bootstrap: Object.freeze({
    code: "HR-OPS-003",
    summaryZh: "Staging 初始化未完成，请检查阶段状态后安全重试。",
    summaryEn: "Staging bootstrap did not complete. Inspect its stage and retry safely.",
    retryable: true,
    recoveryAction: "inspect_stage_and_retry",
  }),
  status: Object.freeze({
    code: "HR-OPS-004",
    summaryZh: "Staging 服务未全部就绪，请查看分层状态。",
    summaryEn: "Not all staging services are ready. Review the layered status.",
    retryable: true,
    recoveryAction: "inspect_status_and_retry",
  }),
  doctor: Object.freeze({
    code: "HR-OPS-005",
    summaryZh: "无法生成安全的诊断包，请检查输出位置后重试。",
    summaryEn: "Couldn't create a safe diagnostic bundle. Check the output location and retry.",
    retryable: true,
    recoveryAction: "check_output_and_retry",
  }),
  compatibility: Object.freeze({
    code: "HR-OPS-006",
    summaryZh: "源版本与目标 Gateway 发布合同不兼容，请选择可升级或可回滚的版本。",
    summaryEn: "The source and target Gateway release contracts are incompatible. Select a compatible upgrade or rollback version.",
    retryable: false,
    recoveryAction: "select_compatible_release",
  }),
  deployment: Object.freeze({
    code: "HR-OPS-007",
    summaryZh: "Gateway 候选版本准备未完成，旧服务保持不变。请检查部署阶段后重试。",
    summaryEn: "Gateway candidate preparation did not complete; the existing service was left unchanged. Inspect the deployment stage and retry.",
    retryable: true,
    recoveryAction: "inspect_deployment_stage_and_retry",
  }),
});

export const OPS_ERROR_DEFINITIONS = DEFINITIONS;

export class OpsError extends Error {
  constructor(kind, technicalCause, stage, options = {}) {
    const definition = DEFINITIONS[kind] ?? DEFINITIONS.status;
    super(definition.summaryEn, options);
    this.name = "OpsError";
    this.kind = kind in DEFINITIONS ? kind : "status";
    this.technicalCause = redactOpsValue(technicalCause);
    this.stage = stage || `cloud_ops_${this.kind}`;
  }
}

export function createOpsError(kind, technicalCause, stage) {
  const normalizedKind = kind in DEFINITIONS ? kind : "status";
  return {
    ...DEFINITIONS[normalizedKind],
    technicalCause: redactOpsValue(technicalCause),
    stage: stage || `cloud_ops_${normalizedKind}`,
  };
}

export function serializeOpsError(kind, technicalCause, stage) {
  return JSON.stringify(createOpsError(kind, technicalCause, stage));
}

export function errorPayload(error, fallbackKind = "status", fallbackStage) {
  if (error instanceof OpsError) {
    return createOpsError(error.kind, error.technicalCause, error.stage);
  }
  return createOpsError(
    fallbackKind,
    error instanceof Error ? error.message : error,
    fallbackStage,
  );
}

export function redactOpsValue(value) {
  return String(value ?? "unknown_failure")
    .replace(/-----BEGIN [^-\r\n]*PRIVATE KEY-----[\s\S]*?-----END [^-\r\n]*PRIVATE KEY-----/gi, "[REDACTED_PRIVATE_KEY]")
    .replace(/-----BEGIN [^-\r\n]*PRIVATE KEY-----/gi, "[REDACTED_PRIVATE_KEY]")
    .replace(/\bauthorization\s*[=:]\s*(?:(?:bearer|basic)\s+)?[^\s,;]+/gi, "authorization=[REDACTED]")
    .replace(/\bcookie\s*[=:]\s*[^\s,]+/gi, "cookie=[REDACTED]")
    .replace(/\b(password|secret|access_?token|app_?token|connector_?token|token|ticket|signature)(\s*[=:]\s*)[^\s,;&]+/gi, "$1$2[REDACTED]")
    .replace(/([?&](?:access_?token|ticket|token|signature)=)[^&#\s]+/gi, "$1[REDACTED]")
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, "[REDACTED_EMAIL]")
    .replace(/\/(?:Users|home)\/[^\s"']+/g, "/[REDACTED_USER_PATH]")
    .replace(/\/root(?:\/[^\s"']*)?/g, "/[REDACTED_ROOT_PATH]")
    .slice(0, 1000);
}
