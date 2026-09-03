const DEFINITIONS = Object.freeze({
  prerequisite: Object.freeze({
    code: "HR-RELEASE-001",
    summaryZh: "无法生成可验证的 Gateway 镜像，请检查构建环境和源码状态。",
    summaryEn: "Couldn't build a verifiable Gateway image. Check the build environment and source state.",
    retryable: true,
    recoveryAction: "inspect_details_and_retry",
  }),
  candidate: Object.freeze({
    code: "HR-RELEASE-002",
    summaryZh: "Gateway 候选镜像未通过身份、隔离或就绪检查。",
    summaryEn: "The Gateway candidate image failed its identity, isolation, or readiness checks.",
    retryable: true,
    recoveryAction: "inspect_details_and_retry",
  }),
  smoke: Object.freeze({
    code: "HR-RELEASE-003",
    summaryZh: "Gateway 候选镜像的端到端验证失败，请检查诊断后重试。",
    summaryEn: "The Gateway candidate image failed end-to-end verification. Review diagnostics and retry.",
    retryable: true,
    recoveryAction: "inspect_details_and_retry",
  }),
});

export const RELEASE_ERROR_DEFINITIONS = DEFINITIONS;

export function createReleaseError(kind, technicalCause) {
  const definition = DEFINITIONS[kind] ?? DEFINITIONS.smoke;
  return {
    ...definition,
    technicalCause: redactTechnicalCause(technicalCause),
    stage: `gateway_oci_${kind in DEFINITIONS ? kind : "smoke"}`,
  };
}

export function serializeReleaseError(kind, technicalCause) {
  return JSON.stringify(createReleaseError(kind, technicalCause));
}

export function redactTechnicalCause(value) {
  return String(value ?? "unknown_failure")
    .replace(
      /\bauthorization\s*[=:]\s*(?:(?:bearer|basic)\s+)?[^\s,;]+/gi,
      "authorization=[REDACTED]",
    )
    .replace(/\bcookie\s*[=:]\s*[^\s,]+/gi, "cookie=[REDACTED]")
    .replace(
      /\b(password|access_?token|token|ticket|signature)(\s*[=:]\s*)[^\s,;&]+/gi,
      "$1$2[REDACTED]",
    )
    .replace(/([?&](?:access_?token|ticket|token|signature)=)[^&#\s]+/gi, "$1[REDACTED]")
    .slice(0, 500);
}
