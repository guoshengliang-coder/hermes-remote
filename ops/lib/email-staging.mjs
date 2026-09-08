import { randomUUID } from "node:crypto";
import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const CONFIG_KEYS = [
  "schemaVersion",
  "environment",
  "accountApiUrl",
  "internalStatusUrl",
  "internalStatusTokenSource",
];
const TEST_RECIPIENTS = Object.freeze({
  delivered: "delivered@resend.dev",
  bounced: "bounced@resend.dev",
});
const COUNTER_KEYS = [
  "requested",
  "providerAccepted",
  "providerFailed",
  "pending",
  "finalDelivered",
  "finalHardFailed",
  "finalDelayed",
  "verified",
];

export async function loadEmailStagingConfig(filePath) {
  let raw;
  try {
    const text = await readFile(filePath, "utf8");
    if (Buffer.byteLength(text) > 64 * 1024) invalid("email_staging_config_too_large");
    raw = JSON.parse(text);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    invalid("email_staging_config_unreadable");
  }
  if (!record(raw) || !exactKeys(raw, CONFIG_KEYS)
      || raw.schemaVersion !== 1 || raw.environment !== "staging") {
    invalid("email_staging_config_invalid");
  }
  if (typeof raw.internalStatusTokenSource !== "string"
      || !path.isAbsolute(raw.internalStatusTokenSource)) {
    invalid("email_staging_token_source_invalid");
  }
  return {
    schemaVersion: 1,
    environment: "staging",
    accountApiUrl: baseUrl(raw.accountApiUrl, false, "account_api_url_invalid"),
    internalStatusUrl: baseUrl(raw.internalStatusUrl, true, "internal_status_url_invalid"),
    internalStatusTokenSource: path.resolve(raw.internalStatusTokenSource),
  };
}

export async function preflightEmailStaging(config, dependencies = {}) {
  const fetchImpl = dependencies.fetch ?? fetch;
  const token = await (dependencies.readToken ?? readProtectedToken)(config.internalStatusTokenSource);
  const [readiness, capabilities, metrics] = await Promise.all([
    requestJson(fetchImpl, new URL("/readyz", config.accountApiUrl)),
    requestJson(fetchImpl, new URL("/v2/capabilities", config.accountApiUrl)),
    requestJson(fetchImpl, new URL("/internal/account-email-metrics", config.internalStatusUrl), {
      headers: { authorization: `Bearer ${token}` },
    }),
  ]);
  if (!record(readiness) || readiness.status !== "ready") fail("gateway_not_ready");
  const accountAuth = record(capabilities) && record(capabilities.accountAuth)
    ? capabilities.accountAuth
    : undefined;
  if (!accountAuth || accountAuth.enabled !== true
      || !Array.isArray(accountAuth.providers)
      || accountAuth.providers.length !== 1
      || accountAuth.providers[0] !== "email_otp") {
    fail("email_first_capability_not_ready");
  }
  const parsedMetrics = parseMetrics(metrics);
  return {
    ok: true,
    environment: "staging",
    accountHost: new URL(config.accountApiUrl).host,
    checks: {
      gatewayReadiness: "pass",
      emailOtpOnly: "pass",
      protectedDeliveryMetrics: "pass",
    },
    metrics: parsedMetrics,
  };
}

export async function exerciseEmailStaging(config, options, dependencies = {}) {
  const expectedConfirmation = `staging:${new URL(config.accountApiUrl).hostname}`;
  if (!options || !Object.hasOwn(TEST_RECIPIENTS, options.testCase)
      || options.confirmation !== expectedConfirmation) {
    invalid("email_staging_exact_confirmation_required");
  }
  const fetchImpl = dependencies.fetch ?? fetch;
  const token = await (dependencies.readToken ?? readProtectedToken)(config.internalStatusTokenSource);
  const before = await preflightEmailStaging(config, {
    ...dependencies,
    readToken: async () => token,
  });
  const clientInstallationId = (dependencies.randomUUID ?? randomUUID)();
  const response = await requestJson(
    fetchImpl,
    new URL("/v2/auth/email/challenges", config.accountApiUrl),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        email: TEST_RECIPIENTS[options.testCase],
        platform: "macos",
        clientInstallationId,
      }),
    },
    202,
  );
  if (!record(response) || !record(response.challenge)
      || !uuid(response.challenge.challengeId)) {
    fail("email_challenge_response_invalid");
  }

  const startedAt = (dependencies.now ?? Date.now)();
  const timeoutMs = dependencies.timeoutMs ?? 120_000;
  const pollMs = dependencies.pollMs ?? 2_000;
  const sleep = dependencies.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const expectedFinalCounter = options.testCase === "delivered"
    ? "finalDelivered"
    : "finalHardFailed";

  while ((dependencies.now ?? Date.now)() - startedAt <= timeoutMs) {
    const current = parseMetrics(await requestJson(
      fetchImpl,
      new URL("/internal/account-email-metrics", config.internalStatusUrl),
      { headers: { authorization: `Bearer ${token}` } },
    ));
    const delta = counterDelta(before.metrics.emailOtp, current.emailOtp);
    if (exactExpectedDelta(delta, expectedFinalCounter)) {
      return {
        ok: true,
        environment: "staging",
        testCase: options.testCase,
        recipientClass: `resend_${options.testCase}_test_address`,
        observedAfterMs: (dependencies.now ?? Date.now)() - startedAt,
        delta,
      };
    }
    if (Object.entries(delta).some(([key, value]) => value < 0 || value > 1
        || (value > 0 && !new Set(["requested", "providerAccepted", expectedFinalCounter]).has(key)))) {
      fail("email_staging_not_isolated");
    }
    await sleep(pollMs);
  }
  fail(`email_${options.testCase}_final_event_timeout`);
}

async function readProtectedToken(filePath) {
  try {
    const stats = await lstat(filePath);
    if (!stats.isFile() || stats.isSymbolicLink() || (stats.mode & 0o077) !== 0 || stats.size > 4096) {
      invalid("email_staging_token_file_not_protected");
    }
    const value = (await readFile(filePath, "utf8")).trim();
    if (value.length < 16 || value.length > 2048 || /[\r\n]/.test(value)) {
      invalid("email_staging_token_invalid");
    }
    return value;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    invalid("email_staging_token_unreadable");
  }
}

async function requestJson(fetchImpl, url, init = {}, expectedStatus = 200) {
  let response;
  try {
    response = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(8_000) });
  } catch {
    fail(`email_staging_request_failed_${url.pathname}`);
  }
  if (response.status !== expectedStatus) {
    fail(`email_staging_status_${url.pathname}_${response.status}`);
  }
  try {
    return await response.json();
  } catch {
    fail(`email_staging_json_invalid_${url.pathname}`);
  }
}

function parseMetrics(value) {
  if (!record(value) || !record(value.emailOtp) || !record(value.deviceShare)) {
    fail("email_staging_metrics_invalid");
  }
  return {
    emailOtp: counters(value.emailOtp, true),
    deviceShare: counters(value.deviceShare, false),
  };
}

function counters(value, otp) {
  const result = {};
  for (const key of COUNTER_KEYS) {
    if ((!otp && key === "verified") || (otp && key === "invitationAccepted")) continue;
    if (key in value) {
      if (!Number.isSafeInteger(value[key]) || value[key] < 0) fail("email_staging_metrics_invalid");
      result[key] = value[key];
    }
  }
  const required = otp
    ? [...COUNTER_KEYS]
    : [...COUNTER_KEYS.filter((key) => key !== "verified"), "invitationAccepted"];
  if (!required.every((key) => Number.isSafeInteger(value[key]) && value[key] >= 0)) {
    fail("email_staging_metrics_invalid");
  }
  if (!otp) result.invitationAccepted = value.invitationAccepted;
  return result;
}

function counterDelta(before, after) {
  return Object.fromEntries(Object.keys(before).map((key) => [key, after[key] - before[key]]));
}

function exactExpectedDelta(delta, expectedFinalCounter) {
  return Object.entries(delta).every(([key, value]) => {
    const expected = key === "requested" || key === "providerAccepted" || key === expectedFinalCounter
      ? 1
      : 0;
    return value === expected;
  });
}

function baseUrl(value, loopbackOnly, cause) {
  let url;
  try {
    url = new URL(value);
  } catch {
    invalid(cause);
  }
  const loopback = new Set(["127.0.0.1", "localhost", "[::1]"]).has(url.hostname);
  if ((url.protocol !== "https:" && !(url.protocol === "http:" && loopback))
      || (loopbackOnly && !loopback) || url.username || url.password
      || url.search || url.hash || (url.pathname !== "/" && url.pathname !== "")) {
    invalid(cause);
  }
  return `${url.origin}/`;
}

function exactKeys(value, keys) {
  return Object.keys(value).sort().join("\0") === [...keys].sort().join("\0");
}

function record(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function uuid(value) {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function invalid(cause) {
  throw new OpsError("config", cause, "email_staging_config");
}

function fail(cause) {
  throw new OpsError("emailAcceptance", cause, "email_staging_acceptance");
}
