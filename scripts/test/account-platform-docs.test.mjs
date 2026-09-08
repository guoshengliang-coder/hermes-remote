import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(path) {
  return readFile(path, "utf8");
}

function section(markdown, start, end) {
  const startIndex = markdown.indexOf(start);
  const endIndex = markdown.indexOf(end, startIndex + start.length);
  assert.notEqual(startIndex, -1, `missing section start: ${start}`);
  assert.notEqual(endIndex, -1, `missing section end: ${end}`);
  return markdown.slice(startIndex, endIndex);
}

test("current account product documents stay email-first and multi-device", async () => {
  const [api, architecture, design, product] = await Promise.all([
    read("docs/ACCOUNT_MODE_API.md"),
    read("docs/ARCHITECTURE.md"),
    read("docs/ACCOUNT_MODE_DESIGN.md"),
    read("docs/CLOUD_PRODUCT_REQUIREMENTS_AND_ARCHITECTURE.md"),
  ]);

  for (const required of [
    "verified email OTP",
    "owned Desktop Connectors (0..3)",
    "shared Desktop Connectors (0..10)",
    "future Google/Apple identity",
  ]) {
    assert.equal(architecture.includes(required), true, `architecture missing: ${required}`);
  }
  for (const stale of [
    "Google identity -> Hermes GO account -> one active Desktop Connector",
    "enforces one active Connector binding per account",
  ]) {
    assert.equal(architecture.includes(stale), false, `architecture restored stale rule: ${stale}`);
  }

  assert.match(api, /with E3 on,[\s\S]{0,140}at most three active\/live-pending owned device slots/);
  assert.doesNotMatch(api, /Required transaction invariants\s+\n\s*- exactly zero or one active Connector binding per account/);

  for (const required of [
    /verified email OTP/,
    /up to three owned Macs/,
    /Google\/Apple remain future providers/,
    /whole-device sharing grants fixed[\s\S]{0,20}operator[\s\S]{0,20}access/i,
  ]) {
    assert.match(design, required, `design missing: ${required}`);
  }
  for (const stale of [
    "Continue with Google",
    "Google-backed owner",
    "same Google account",
    "One account has at most one active",
  ]) {
    assert.equal(design.includes(stale), false, `design restored stale rule: ${stale}`);
  }

  for (const required of [
    /六位验证码/,
    /`HC-BIND-001`[\s\S]{0,120}最多有三台自有/,
    /`HC-SHARE-001`/,
    /同一已验证邮箱账号/,
  ]) {
    assert.match(product, required, `product requirements missing: ${required}`);
  }
  for (const stale of [
    "同一 Google 账号",
    "一个账号最多有一个活跃 Connector",
    "一账号最多一台活跃 Connector",
    "第二台 Mac 不得静默覆盖",
  ]) {
    assert.equal(product.includes(stale), false, `product requirements restored stale rule: ${stale}`);
  }
});

test("the current implementation-plan outcome does not inherit historical release rules", async () => {
  const plan = await read("docs/ACCOUNT_MODE_IMPLEMENTATION_PLAN.md");
  const outcome = section(plan, "## 1. Outcome and fixed boundaries", "## 2. Iteration map");

  for (const required of [
    /same verified email account/,
    /up to three owned Macs/,
    /shared by other accounts/,
    /Google and Apple are not\s+part of this release gate/,
  ]) {
    assert.match(outcome, required, `current outcome missing: ${required}`);
  }
  for (const stale of [
    "same Google account",
    "one active Connector per account",
    "entry to the single Hermes",
  ]) {
    assert.equal(outcome.includes(stale), false, `current outcome restored stale rule: ${stale}`);
  }
});
