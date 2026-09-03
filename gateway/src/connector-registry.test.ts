import assert from "node:assert/strict";
import test from "node:test";
import {
  accountRoutingKey,
  InMemoryConnectorRegistry,
  legacyRoutingKey,
} from "./connector-registry.js";

test("ConnectorRegistry keeps legacy device routing isolated", () => {
  const registry = new InMemoryConnectorRegistry<object>();
  const first = {};
  const replacement = {};
  registry.setLegacy("mac-mini", first);
  assert.equal(registry.legacyCount, 1);
  assert.deepEqual([...registry.legacyDeviceIds()], ["mac-mini"]);
  assert.equal(registry.getLegacy("mac-mini"), first);
  assert.equal(registry.getByRoutingKey(legacyRoutingKey("mac-mini")), first);

  registry.setLegacy("mac-mini", replacement);
  assert.equal(registry.deleteLegacyIfCurrent("mac-mini", first), false);
  assert.equal(registry.getLegacy("mac-mini"), replacement);
  assert.equal(registry.deleteLegacyIfCurrent("mac-mini", replacement), true);
  assert.equal(registry.legacyCount, 0);
});

test("ConnectorRegistry replaces and conditionally removes account bindings", () => {
  const registry = new InMemoryConnectorRegistry<object>();
  const first = {};
  const replacement = {};
  assert.equal(registry.replaceAccount("binding-1", first), undefined);
  assert.equal(registry.replaceAccount("binding-1", replacement), first);
  assert.equal(registry.getAccount("binding-1"), replacement);
  assert.equal(registry.getByRoutingKey(accountRoutingKey("binding-1")), replacement);
  assert.equal(registry.getByRoutingKey("unknown:binding-1"), undefined);
  assert.equal(registry.deleteAccountIfCurrent("binding-1", first), false);
  assert.equal(registry.deleteAccountIfCurrent("binding-1", replacement), true);
});
