import { Resolver } from "node:dns/promises";
import { readFile } from "node:fs/promises";
import { OpsError, createOpsError } from "./errors.mjs";

const CONFIG_KEYS = ["schemaVersion", "environment", "sendingDomain", "records"];
const RECORD_KEYS = ["spfTxt", "spfMx", "dkimTxt", "dmarcTxt"];
const TXT_KEYS = ["name", "expectedValue"];
const MX_KEYS = ["name", "exchange", "priority"];
const DMARC_KEYS = ["name", "minimumPolicy", "requireAggregateReports"];
const POLICY_RANK = Object.freeze({ none: 0, quarantine: 1, reject: 2 });

export async function loadEmailDomainConfig(filePath) {
  let raw;
  try {
    const text = await readFile(filePath, "utf8");
    if (Buffer.byteLength(text) > 64 * 1024) invalid("email_domain_config_too_large");
    raw = JSON.parse(text);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    invalid("email_domain_config_unreadable");
  }
  if (!record(raw) || !exactKeys(raw, CONFIG_KEYS)
      || raw.schemaVersion !== 1 || raw.environment !== "staging"
      || !record(raw.records) || !exactKeys(raw.records, RECORD_KEYS)) {
    invalid("email_domain_config_invalid");
  }
  const sendingDomain = domain(raw.sendingDomain, "sending_domain_invalid");
  const spfTxt = txtExpectation(raw.records.spfTxt, sendingDomain, "spf_txt");
  const dkimTxt = txtExpectation(raw.records.dkimTxt, sendingDomain, "dkim_txt");
  if (!record(raw.records.spfMx) || !exactKeys(raw.records.spfMx, MX_KEYS)) {
    invalid("spf_mx_invalid");
  }
  const spfMx = {
    name: childDomain(raw.records.spfMx.name, sendingDomain, "spf_mx_name_invalid"),
    exchange: domain(raw.records.spfMx.exchange, "spf_mx_exchange_invalid"),
    priority: integer(raw.records.spfMx.priority, 0, 65535, "spf_mx_priority_invalid"),
  };
  if (!record(raw.records.dmarcTxt) || !exactKeys(raw.records.dmarcTxt, DMARC_KEYS)) {
    invalid("dmarc_txt_invalid");
  }
  const dmarcName = childDomain(raw.records.dmarcTxt.name, sendingDomain, "dmarc_name_invalid");
  if (dmarcName !== `_dmarc.${sendingDomain}`
      || !Object.hasOwn(POLICY_RANK, raw.records.dmarcTxt.minimumPolicy)
      || typeof raw.records.dmarcTxt.requireAggregateReports !== "boolean") {
    invalid("dmarc_policy_invalid");
  }
  return {
    schemaVersion: 1,
    environment: "staging",
    sendingDomain,
    records: {
      spfTxt,
      spfMx,
      dkimTxt,
      dmarcTxt: {
        name: dmarcName,
        minimumPolicy: raw.records.dmarcTxt.minimumPolicy,
        requireAggregateReports: raw.records.dmarcTxt.requireAggregateReports,
      },
    },
  };
}

export async function auditEmailDomain(config, dependencies = {}) {
  const resolver = dependencies.resolver ?? defaultResolver();
  const now = dependencies.now ?? (() => new Date());
  const checks = await Promise.all([
    checkSpfTxt(config.records.spfTxt, resolver),
    checkSpfMx(config.records.spfMx, resolver),
    checkDkim(config.records.dkimTxt, resolver),
    checkDmarc(config.records.dmarcTxt, resolver),
  ]);
  const ok = checks.every((check) => check.status === "pass");
  return {
    ok,
    environment: "staging",
    sendingDomain: config.sendingDomain,
    observedAt: now().toISOString(),
    checks,
    ...(ok ? {} : { error: createOpsError("emailDomain", "email_dns_incomplete", "email_domain_audit") }),
  };
}

async function checkSpfTxt(expectation, resolver) {
  const records = await safeTxt(resolver, expectation.name);
  if (!records) return blocked("spf_txt", "dns_lookup_failed");
  const spfRecords = records.filter((value) => /^v=spf1(?:\s|$)/i.test(value));
  return spfRecords.length === 1 && spfRecords[0] === expectation.expectedValue
    ? passed("spf_txt")
    : blocked("spf_txt", spfRecords.length === 0 ? "record_missing" : "record_mismatch_or_ambiguous");
}

async function checkSpfMx(expectation, resolver) {
  let records;
  try {
    records = await resolver.resolveMx(expectation.name);
  } catch {
    return blocked("spf_mx", "dns_lookup_failed");
  }
  const normalized = records.map((entry) => ({
    exchange: normalizeDomain(entry.exchange),
    priority: entry.priority,
  }));
  return normalized.length === 1
      && normalized[0].exchange === expectation.exchange
      && normalized[0].priority === expectation.priority
    ? passed("spf_mx")
    : blocked("spf_mx", normalized.length === 0 ? "record_missing" : "record_mismatch_or_ambiguous");
}

async function checkDkim(expectation, resolver) {
  const records = await safeTxt(resolver, expectation.name);
  if (!records) return blocked("dkim_txt", "dns_lookup_failed");
  const matches = records.filter((value) => value === expectation.expectedValue);
  return matches.length === 1
    ? passed("dkim_txt")
    : blocked("dkim_txt", records.length === 0 ? "record_missing" : "record_mismatch_or_ambiguous");
}

async function checkDmarc(expectation, resolver) {
  const records = await safeTxt(resolver, expectation.name);
  if (!records) return blocked("dmarc_txt", "dns_lookup_failed");
  const dmarc = records.filter((value) => /^v=DMARC1(?:\s*;|$)/i.test(value));
  if (dmarc.length !== 1) {
    return blocked("dmarc_txt", dmarc.length === 0 ? "record_missing" : "record_ambiguous");
  }
  const tags = dmarcTags(dmarc[0]);
  if (!tags) return blocked("dmarc_txt", "record_invalid_or_ambiguous");
  const policy = tags.get("p")?.toLowerCase();
  if (!policy || !Object.hasOwn(POLICY_RANK, policy)
      || POLICY_RANK[policy] < POLICY_RANK[expectation.minimumPolicy]) {
    return blocked("dmarc_txt", "policy_too_weak_or_invalid");
  }
  if (tags.has("pct") && tags.get("pct") !== "100") {
    return blocked("dmarc_txt", "policy_percentage_too_low_or_invalid");
  }
  if (expectation.requireAggregateReports && !validRua(tags.get("rua"))) {
    return blocked("dmarc_txt", "aggregate_reporting_missing");
  }
  return passed("dmarc_txt", { policy, aggregateReports: validRua(tags.get("rua")) });
}

async function safeTxt(resolver, name) {
  try {
    const records = await resolver.resolveTxt(name);
    if (!Array.isArray(records) || records.some((chunks) => !Array.isArray(chunks)
        || chunks.some((chunk) => typeof chunk !== "string"))) return undefined;
    return records.map((chunks) => chunks.join(""));
  } catch {
    return undefined;
  }
}

function defaultResolver() {
  const resolver = new Resolver({ timeout: 5_000, tries: 2 });
  return {
    resolveTxt: (name) => resolver.resolveTxt(name),
    resolveMx: (name) => resolver.resolveMx(name),
  };
}

function dmarcTags(value) {
  const tags = new Map();
  for (const field of value.split(";")) {
    const normalized = field.trim();
    if (!normalized) continue;
    const separator = normalized.indexOf("=");
    if (separator < 1) return undefined;
    const name = normalized.slice(0, separator).trim().toLowerCase();
    const content = normalized.slice(separator + 1).trim();
    if (!name || !content || tags.has(name)) return undefined;
    tags.set(name, content);
  }
  return tags;
}

function validRua(value) {
  return typeof value === "string"
    && value.split(",").some((uri) => /^mailto:[^\s@,]+@[^\s@,]+$/i.test(uri.trim()));
}

function txtExpectation(value, sendingDomain, label) {
  if (!record(value) || !exactKeys(value, TXT_KEYS)
      || typeof value.expectedValue !== "string" || value.expectedValue.length < 3
      || value.expectedValue.length > 4096 || /[\r\n]/.test(value.expectedValue)) {
    invalid(`${label}_invalid`);
  }
  return {
    name: childDomain(value.name, sendingDomain, `${label}_name_invalid`),
    expectedValue: value.expectedValue,
  };
}

function childDomain(value, parent, cause) {
  const parsed = recordName(value, cause);
  if (parsed === parent || !parsed.endsWith(`.${parent}`)) invalid(cause);
  return parsed;
}

function recordName(value, cause) {
  if (typeof value !== "string") invalid(cause);
  const normalized = normalizeDomain(value);
  if (normalized.length < 1 || normalized.length > 253 || !normalized.includes(".")
      || normalized.includes("..")
      || !normalized.split(".").every((label) => /^_?[a-z0-9](?:[a-z0-9_-]{0,61}[a-z0-9])?$/.test(label))) {
    invalid(cause);
  }
  return normalized;
}

function domain(value, cause) {
  if (typeof value !== "string") invalid(cause);
  const normalized = normalizeDomain(value);
  if (normalized.length < 1 || normalized.length > 253 || !normalized.includes(".")
      || normalized.includes("..")
      || !normalized.split(".").every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) {
    invalid(cause);
  }
  return normalized;
}

function normalizeDomain(value) {
  return String(value).toLowerCase().replace(/\.$/, "");
}

function integer(value, minimum, maximum, cause) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) invalid(cause);
  return value;
}

function passed(id, evidence = {}) {
  return { id, status: "pass", ...evidence };
}

function blocked(id, reason) {
  return { id, status: "blocked", reason };
}

function exactKeys(value, keys) {
  return Object.keys(value).sort().join("\0") === [...keys].sort().join("\0");
}

function record(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalid(cause) {
  throw new OpsError("config", cause, "email_domain_config");
}
