import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import {
  exerciseEmailStaging,
  loadEmailStagingConfig,
  preflightEmailStaging,
} from "../../ops/lib/email-staging.mjs";

test("email staging config is strict, staging-only, and keeps internal metrics loopback-only", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-email-staging-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const secretDirectory = path.join(root, "secrets");
  await mkdir(secretDirectory);
  const tokenPath = path.join(secretDirectory, "internal-status-token");
  await writeFile(tokenPath, "test-internal-status-token\n", { mode: 0o600 });
  const configPath = path.join(root, "email-staging.json");
  const valid = {
    schemaVersion: 1,
    environment: "staging",
    accountApiUrl: "https://mail-staging.example.com/",
    internalStatusUrl: "http://127.0.0.1:8787/",
    internalStatusTokenSource: tokenPath,
  };
  await writeFile(configPath, JSON.stringify(valid));
  const config = await loadEmailStagingConfig(configPath);
  assert.equal(config.environment, "staging");
  assert.equal(config.accountApiUrl, valid.accountApiUrl);

  let fetched = false;
  await chmod(tokenPath, 0o644);
  await assert.rejects(() => preflightEmailStaging(config, {
    fetch: async () => { fetched = true; throw new Error("must not run"); },
  }), isOpsCode("HR-OPS-001"));
  assert.equal(fetched, false);

  for (const invalid of [
    { ...valid, environment: "production" },
    { ...valid, internalStatusUrl: "https://mail-staging.example.com/" },
    { ...valid, unexpected: true },
    { ...valid, accountApiUrl: "http://mail-staging.example.com/" },
  ]) {
    await writeFile(configPath, JSON.stringify(invalid));
    await assert.rejects(() => loadEmailStagingConfig(configPath), isOpsCode("HR-OPS-001"));
  }
});

test("email staging preflight verifies readiness, email-only capability, and protected metrics", async () => {
  const calls = [];
  const result = await preflightEmailStaging(config(), {
    readToken: async () => "private-status-token",
    fetch: async (url, init) => {
      calls.push({ path: url.pathname, authorization: init?.headers?.authorization });
      if (url.pathname === "/readyz") return json({ status: "ready" });
      if (url.pathname === "/v2/capabilities") {
        return json({ accountAuth: { enabled: true, providers: ["email_otp"] } });
      }
      if (url.pathname === "/internal/account-email-metrics") return json(metrics());
      throw new Error("unexpected request");
    },
  });
  assert.equal(result.ok, true);
  assert.deepEqual(result.checks, {
    gatewayReadiness: "pass",
    emailOtpOnly: "pass",
    protectedDeliveryMetrics: "pass",
  });
  assert.equal(calls.find((call) => call.path.includes("metrics")).authorization, "Bearer private-status-token");
  assert.equal(JSON.stringify(result).includes("private-status-token"), false);
});

test("email staging exercises only official Resend outcomes and observes one isolated final delta", async () => {
  for (const testCase of ["delivered", "bounced"]) {
    let metricsReads = 0;
    let recipient;
    const result = await exerciseEmailStaging(config(), {
      testCase,
      confirmation: "staging:127.0.0.1",
    }, {
      readToken: async () => "private-status-token",
      randomUUID: () => "fdaed25e-f143-4e3c-b92b-0d881df13630",
      now: () => 0,
      sleep: async () => {},
      fetch: async (url, init) => {
        if (url.pathname === "/readyz") return json({ status: "ready" });
        if (url.pathname === "/v2/capabilities") {
          return json({ accountAuth: { enabled: true, providers: ["email_otp"] } });
        }
        if (url.pathname === "/v2/auth/email/challenges") {
          recipient = JSON.parse(init.body).email;
          return json({ challenge: { challengeId: "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea" } }, 202);
        }
        if (url.pathname === "/internal/account-email-metrics") {
          metricsReads += 1;
          const final = metricsReads > 1
            ? {
              requested: 1,
              providerAccepted: 1,
              ...(testCase === "delivered" ? { finalDelivered: 1 } : { finalHardFailed: 1 }),
            }
            : {};
          return json(metrics(final));
        }
        throw new Error("unexpected request");
      },
    });
    assert.equal(result.ok, true);
    assert.equal(result.testCase, testCase);
    assert.equal(result.delta.requested, 1);
    assert.equal(result.delta.providerAccepted, 1);
    assert.equal(result.delta[testCase === "delivered" ? "finalDelivered" : "finalHardFailed"], 1);
    assert.equal(recipient, `${testCase}@resend.dev`);
    assert.equal(JSON.stringify(result).includes("@resend.dev"), false);
  }
});

test("email staging exercise fails closed before sending without exact host confirmation", async () => {
  let called = false;
  await assert.rejects(() => exerciseEmailStaging(config(), {
    testCase: "delivered",
    confirmation: "staging:wrong-host",
  }, {
    fetch: async () => { called = true; throw new Error("must not run"); },
  }), isOpsCode("HR-OPS-001"));
  assert.equal(called, false);
});

test("email staging exercise rejects concurrent aggregate activity instead of misattributing it", async () => {
  let metricsReads = 0;
  await assert.rejects(() => exerciseEmailStaging(config(), {
    testCase: "delivered",
    confirmation: "staging:127.0.0.1",
  }, {
    readToken: async () => "private-status-token",
    randomUUID: () => "fdaed25e-f143-4e3c-b92b-0d881df13630",
    fetch: async (url) => {
      if (url.pathname === "/readyz") return json({ status: "ready" });
      if (url.pathname === "/v2/capabilities") {
        return json({ accountAuth: { enabled: true, providers: ["email_otp"] } });
      }
      if (url.pathname === "/v2/auth/email/challenges") {
        return json({ challenge: { challengeId: "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea" } }, 202);
      }
      if (url.pathname === "/internal/account-email-metrics") {
        metricsReads += 1;
        return json(metrics(metricsReads > 1 ? {
          requested: 1,
          providerAccepted: 1,
          finalDelivered: 1,
          finalHardFailed: 1,
        } : {}));
      }
      throw new Error("unexpected request");
    },
  }), isOpsCode("HR-OPS-011"));
});

test("email acceptance error is localized, retryable, registered, and CLI-wired", async () => {
  const definition = OPS_ERROR_DEFINITIONS.emailAcceptance;
  assert.equal(definition.code, "HR-OPS-011");
  assert.match(definition.summaryZh, /邮件/);
  assert.match(definition.summaryEn, /email/i);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_email_delivery_and_retry");
  const [registry, cli, rootPackage] = await Promise.all([
    readFile("docs/ERROR_HANDLING.md", "utf8"),
    readFile("scripts/verify-email-staging.mjs", "utf8"),
    readFile("package.json", "utf8"),
  ]);
  assert.match(registry, /`HR-OPS-011`/);
  assert.match(cli, /exerciseEmailStaging/);
  assert.equal(JSON.parse(rootPackage).scripts["ops:email-staging"], "node scripts/verify-email-staging.mjs");
});

function config() {
  return {
    schemaVersion: 1,
    environment: "staging",
    accountApiUrl: "http://127.0.0.1:8787/",
    internalStatusUrl: "http://127.0.0.1:8787/",
    internalStatusTokenSource: "/secure-input/hermes-go/internal-status-token",
  };
}

function metrics(emailOtpOverrides = {}) {
  const common = {
    requested: 0,
    providerAccepted: 0,
    providerFailed: 0,
    pending: 0,
    finalDelivered: 0,
    finalHardFailed: 0,
    finalDelayed: 0,
  };
  return {
    emailOtp: { ...common, verified: 0, ...emailOtpOverrides },
    deviceShare: { ...common, invitationAccepted: 0 },
  };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function isOpsCode(code) {
  return (error) => error?.name === "OpsError"
    && OPS_ERROR_DEFINITIONS[error.kind]?.code === code;
}
