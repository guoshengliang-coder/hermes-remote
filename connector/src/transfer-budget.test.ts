import assert from "node:assert/strict";
import test from "node:test";
import { TransferBudget } from "./transfer-budget.js";

test("one Mac budget halves and serializes bulk work, pauses production and recovers gradually", () => {
  let now = 0;
  const budget = new TransferBudget(1024, () => now);
  budget.state("business", "suspect");
  assert.equal(budget.rate(), 512); assert.equal(budget.concurrency, 1);
  now = 10_000; assert.equal(budget.paused, true);
  budget.state("business", "recovering"); assert.equal(budget.paused, false);
  budget.state("business", "normal"); assert.equal(budget.rate(), 512);
  now += 15_000; assert.equal(budget.rate(), 768);
  now += 15_000; assert.equal(budget.rate(), 1024);
  budget.state("control", "lost"); now += 60_000;
  assert.equal(budget.paused, false, "control loss cannot stall a healthy business path");
});

test("aborting a paused producer releases its wait immediately", async () => {
  let now = 0;
  const budget = new TransferBudget(1024, () => now);
  budget.state("business", "suspect"); now = 10_000;
  const controller = new AbortController();
  const waiting = budget.take(64, controller.signal); controller.abort();
  await assert.rejects(waiting, { name: "AbortError" });
});

test("the severe pause deadline belongs to continuous business suspicion only", () => {
  let now = 0;
  const budget = new TransferBudget(1024, () => now);
  budget.state("control", "suspect"); now = 60_000;
  budget.state("business", "suspect");
  assert.equal(budget.paused, false);
  now += 10_000; assert.equal(budget.paused, true);
  budget.state("business", "recovering");
  budget.state("business", "suspect");
  assert.equal(budget.paused, false);
});
