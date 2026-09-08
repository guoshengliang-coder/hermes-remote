import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { auditEmailDomain, loadEmailDomainConfig } from "../../ops/lib/email-domain.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("email-domain config is strict, staging-only, and constrains every owner name", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-email-domain-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const configPath = path.join(root, "domain.json");
  await writeFile(configPath, JSON.stringify(rawConfig()));
  const config = await loadEmailDomainConfig(configPath);
  assert.equal(config.sendingDomain, "auth.example.com");
  assert.equal(config.records.dkimTxt.name, "resend._domainkey.auth.example.com");

  for (const invalid of [
    { ...rawConfig(), environment: "production" },
    { ...rawConfig(), unexpected: true },
    { ...rawConfig(), sendingDomain: "localhost" },
    {
      ...rawConfig(),
      records: {
        ...rawConfig().records,
        dkimTxt: { ...rawConfig().records.dkimTxt, name: "resend._domainkey.other.example.com" },
      },
    },
    {
      ...rawConfig(),
      records: {
        ...rawConfig().records,
        dmarcTxt: { ...rawConfig().records.dmarcTxt, name: "policy.auth.example.com" },
      },
    },
  ]) {
    await writeFile(configPath, JSON.stringify(invalid));
    await assert.rejects(() => loadEmailDomainConfig(configPath), isOpsCode("HR-OPS-001"));
  }
});

test("email-domain audit joins split TXT chunks and passes the exact four-record contract", async () => {
  const result = await auditEmailDomain(await parsedConfig(), {
    now: () => new Date("2026-09-08T12:00:00.000Z"),
    resolver: {
      resolveTxt: async (name) => {
        if (name.startsWith("send.")) {
          return [
            ["v=spf1 include:", "amazonses.com ~all"],
            ["unrelated=public-record"],
          ];
        }
        if (name.startsWith("resend._domainkey.")) return [["p=public-", "dkim-key"]];
        if (name.startsWith("_dmarc.")) {
          return [["v=DMARC1; p=none; rua=mailto:reports@example.com;"]];
        }
        throw new Error("unexpected name");
      },
      resolveMx: async () => [{ exchange: "feedback-smtp.us-east-1.amazonses.com.", priority: 10 }],
    },
  });
  assert.equal(result.ok, true);
  assert.equal(result.observedAt, "2026-09-08T12:00:00.000Z");
  assert.deepEqual(result.checks, [
    { id: "spf_txt", status: "pass" },
    { id: "spf_mx", status: "pass" },
    { id: "dkim_txt", status: "pass" },
    { id: "dmarc_txt", status: "pass", policy: "none", aggregateReports: true },
  ]);
  const serialized = JSON.stringify(result);
  assert.equal(serialized.includes("public-dkim-key"), false);
  assert.equal(serialized.includes("reports@example.com"), false);
});

test("email-domain audit reports every mismatch without leaking DNS values or resolver errors", async () => {
  const raw = rawConfig();
  raw.records.dmarcTxt.minimumPolicy = "quarantine";
  const result = await auditEmailDomain(await parsedConfig(raw), {
    now: () => new Date("2026-09-08T12:00:00.000Z"),
    resolver: {
      resolveTxt: async (name) => {
        if (name.startsWith("send.")) {
          return [["v=spf1 include:wrong.example ~all"], ["v=spf1 include:second.example ~all"]];
        }
        if (name.startsWith("resend._domainkey.")) return [["p=wrong-public-key"]];
        if (name.startsWith("_dmarc.")) return [["v=DMARC1; p=none; p=reject"]];
        throw new Error("resolver-private-diagnostic");
      },
      resolveMx: async () => [
        { exchange: "feedback-smtp.us-east-1.amazonses.com", priority: 10 },
        { exchange: "feedback-smtp.eu-west-1.amazonses.com", priority: 10 },
      ],
    },
  });
  assert.equal(result.ok, false);
  assert.deepEqual(result.checks.map(({ id, status }) => ({ id, status })), [
    { id: "spf_txt", status: "blocked" },
    { id: "spf_mx", status: "blocked" },
    { id: "dkim_txt", status: "blocked" },
    { id: "dmarc_txt", status: "blocked" },
  ]);
  assert.equal(result.error.code, "HR-OPS-018");
  const serialized = JSON.stringify(result);
  for (const forbidden of ["wrong.example", "wrong-public-key", "eu-west-1", "resolver-private"]) {
    assert.equal(serialized.includes(forbidden), false);
  }
});

test("email-domain audit distinguishes lookup failure and a missing DMARC report destination", async () => {
  const result = await auditEmailDomain(await parsedConfig(), {
    resolver: {
      resolveTxt: async (name) => {
        if (name.startsWith("send.")) throw new Error("NXDOMAIN private detail");
        if (name.startsWith("resend._domainkey.")) return [["p=public-dkim-key"]];
        return [["v=DMARC1; p=reject"]];
      },
      resolveMx: async () => { throw new Error("SERVFAIL private detail"); },
    },
  });
  assert.deepEqual(result.checks, [
    { id: "spf_txt", status: "blocked", reason: "dns_lookup_failed" },
    { id: "spf_mx", status: "blocked", reason: "dns_lookup_failed" },
    { id: "dkim_txt", status: "pass" },
    { id: "dmarc_txt", status: "blocked", reason: "aggregate_reporting_missing" },
  ]);
  assert.equal(JSON.stringify(result).includes("private detail"), false);

  const partialPolicy = await auditEmailDomain(await parsedConfig(), {
    resolver: {
      resolveTxt: async (name) => {
        if (name.startsWith("send.")) return [["v=spf1 include:amazonses.com ~all"]];
        if (name.startsWith("resend._domainkey.")) return [["p=public-dkim-key"]];
        return [["v=DMARC1; p=reject; pct=10; rua=mailto:reports@example.com"]];
      },
      resolveMx: async () => [{ exchange: "feedback-smtp.us-east-1.amazonses.com", priority: 10 }],
    },
  });
  assert.deepEqual(partialPolicy.checks[3], {
    id: "dmarc_txt",
    status: "blocked",
    reason: "policy_percentage_too_low_or_invalid",
  });
});

test("email-domain error is bilingual, retryable, registered, and CLI-wired", async () => {
  const definition = OPS_ERROR_DEFINITIONS.emailDomain;
  assert.equal(definition.code, "HR-OPS-018");
  assert.match(definition.summaryZh, /域名/);
  assert.match(definition.summaryEn, /mail domain/i);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "fix_email_dns_and_retry");
  const [registry, cli, rootPackage] = await Promise.all([
    readFile("docs/ERROR_HANDLING.md", "utf8"),
    readFile("scripts/verify-email-domain.mjs", "utf8"),
    readFile("package.json", "utf8"),
  ]);
  assert.match(registry, /`HR-OPS-018`/);
  assert.match(cli, /auditEmailDomain/);
  assert.equal(JSON.parse(rootPackage).scripts["ops:email-domain"], "node scripts/verify-email-domain.mjs");
});

async function parsedConfig(raw = rawConfig()) {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-email-domain-parse-"));
  const configPath = path.join(root, "domain.json");
  try {
    await writeFile(configPath, JSON.stringify(raw));
    return await loadEmailDomainConfig(configPath);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

function rawConfig() {
  return {
    schemaVersion: 1,
    environment: "staging",
    sendingDomain: "auth.example.com",
    records: {
      spfTxt: {
        name: "send.auth.example.com",
        expectedValue: "v=spf1 include:amazonses.com ~all",
      },
      spfMx: {
        name: "send.auth.example.com",
        exchange: "feedback-smtp.us-east-1.amazonses.com",
        priority: 10,
      },
      dkimTxt: {
        name: "resend._domainkey.auth.example.com",
        expectedValue: "p=public-dkim-key",
      },
      dmarcTxt: {
        name: "_dmarc.auth.example.com",
        minimumPolicy: "none",
        requireAggregateReports: true,
      },
    },
  };
}

function isOpsCode(code) {
  return (error) => error?.name === "OpsError"
    && OPS_ERROR_DEFINITIONS[error.kind]?.code === code;
}
