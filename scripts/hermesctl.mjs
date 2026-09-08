#!/usr/bin/env node
import { loadBundleManifest, loadDeployConfig, loadOpsConfig } from "../ops/lib/config.mjs";
import { executeDeployment } from "../ops/lib/deploy-command.mjs";
import { createStagingSmokeCallbacks } from "../ops/lib/deploy-smoke.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { auditProductionReadiness } from "../ops/lib/production-audit.mjs";
import { loadProductionAuditConfig } from "../ops/lib/production-config.mjs";
import { loadProductionMonitorConfig } from "../ops/lib/production-monitor-config.mjs";
import { monitorProduction } from "../ops/lib/production-monitor.mjs";
import { loadLegacyCaptureConfig, loadLegacyRestoreConfig } from "../ops/lib/legacy-recovery-config.mjs";
import { captureLegacyRecovery, verifyLegacyRecovery } from "../ops/lib/legacy-recovery.mjs";
import {
  bootstrapStaging,
  createDoctorBundle,
  getStatus,
  preflight,
} from "../ops/lib/hermesctl.mjs";

const command = process.argv[2];

try {
  const args = parseArguments(command, process.argv.slice(3));
  const deploymentCommand = command === "deploy" || command === "rollback";
  const productionAudit = command === "production-audit";
  const productionMonitor = command === "production-monitor";
  const legacyCapture = command === "legacy-capture";
  const legacyRestore = command === "legacy-restore";
  const config = legacyCapture
    ? await loadLegacyCaptureConfig(args.config)
    : legacyRestore
      ? await loadLegacyRestoreConfig(args.config)
      : productionMonitor
        ? await loadProductionMonitorConfig(args.config)
        : productionAudit
          ? await loadProductionAuditConfig(args.config)
          : deploymentCommand
            ? await loadDeployConfig(args.config)
            : await loadOpsConfig(args.config);
  const manifest = legacyCapture || legacyRestore || productionMonitor
    ? undefined
    : await loadBundleManifest(
      deploymentCommand || productionAudit ? config.targetArtifactManifest : config.artifactManifest,
      { verifyArchive: command === "preflight" || command === "bootstrap" || deploymentCommand || productionAudit },
    );

  if (legacyCapture) {
    print(await captureLegacyRecovery(config, { confirmation: args.confirm }));
  } else if (legacyRestore) {
    print(await verifyLegacyRecovery(config, { confirmation: args.confirm }));
  } else if (productionAudit) {
    const result = await auditProductionReadiness(config, manifest, { confirmation: args.confirm });
    print(result);
    if (!result.ok) process.exitCode = 1;
  } else if (productionMonitor) {
    const result = await monitorProduction(config, { confirmation: args.confirm });
    print(result);
    if (!result.ok) process.exitCode = 1;
  } else if (command === "preflight") {
    print(await preflight(config, manifest));
  } else if (command === "bootstrap") {
    print(await bootstrapStaging(config, manifest, { confirmation: args.confirm }));
  } else if (command === "status") {
    const status = await getStatus(config, manifest);
    print(status);
    if (!status.ok) process.exitCode = 1;
  } else if (command === "doctor") {
    print(await createDoctorBundle(config, manifest, args.output));
  } else if (deploymentCommand) {
    const smoke = await createStagingSmokeCallbacks(config);
    print(await executeDeployment(config, manifest, {
      operation: command,
      confirmation: args.confirm,
      ...smoke,
    }));
  }
} catch (error) {
  const kind = command === "doctor"
    ? "doctor"
    : command === "bootstrap"
      ? "bootstrap"
      : command === "deploy" || command === "rollback"
        ? "deployment"
      : command === "production-audit"
        ? "promotion"
      : command === "production-monitor"
        ? "monitoring"
      : command === "legacy-capture" || command === "legacy-restore"
        ? "recovery"
      : command === "status"
        ? "status"
        : "config";
  console.error(JSON.stringify(errorPayload(error, kind, `hermesctl_${command || "arguments"}`)));
  process.exitCode = 1;
}

function parseArguments(selectedCommand, values) {
  if (!new Set(["preflight", "bootstrap", "status", "doctor", "deploy", "rollback", "production-audit", "production-monitor", "legacy-capture", "legacy-restore"]).has(selectedCommand)) {
    throw new OpsError("config", "command_not_supported", "arguments_parse");
  }
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("config", "command_arguments_invalid", "arguments_parse");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm", "output"]).has(key) || key in parsed) {
      throw new OpsError("config", "command_argument_unknown_or_duplicate", "arguments_parse");
    }
    parsed[key] = value;
  }
  if (!parsed.config) throw new OpsError("config", "config_argument_required", "arguments_parse");
  if (new Set(["bootstrap", "deploy", "rollback"]).has(selectedCommand)) {
    if (parsed.confirm !== "staging" || parsed.output) {
      throw new OpsError("config", `${selectedCommand}_requires_confirm_staging`, "arguments_parse");
    }
  } else if (selectedCommand === "production-audit") {
    if (!/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "") || parsed.output) {
      throw new OpsError("config", "production_audit_requires_exact_confirmation", "arguments_parse");
    }
  } else if (selectedCommand === "production-monitor") {
    if (!/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "") || parsed.output) {
      throw new OpsError("config", "production_monitor_requires_exact_confirmation", "arguments_parse");
    }
  } else if (selectedCommand === "legacy-capture") {
    if (!/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "") || parsed.output) {
      throw new OpsError("config", "legacy_capture_requires_exact_confirmation", "arguments_parse");
    }
  } else if (selectedCommand === "legacy-restore") {
    if (!/^isolated:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "") || parsed.output) {
      throw new OpsError("config", "legacy_restore_requires_exact_confirmation", "arguments_parse");
    }
  } else if (parsed.confirm) {
    throw new OpsError("config", "confirm_not_supported_for_command", "arguments_parse");
  }
  if (selectedCommand === "doctor") {
    if (!parsed.output) throw new OpsError("config", "doctor_output_required", "arguments_parse");
  } else if (parsed.output) {
    throw new OpsError("config", "output_is_doctor_only", "arguments_parse");
  }
  return parsed;
}

function print(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}
