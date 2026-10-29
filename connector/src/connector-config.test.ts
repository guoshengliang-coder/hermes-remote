import assert from "node:assert/strict";
import test from "node:test";
import { resolveExternalReadRoots, resolveHermesMode } from "./connector-config.js";

test("an account Connector without HERMES_MODE runs live (HG-101)", () => {
  assert.equal(resolveHermesMode({}, "account"), "live");
});

test("a legacy Connector without HERMES_MODE keeps the mock default", () => {
  assert.equal(resolveHermesMode({}, "legacy"), "mock");
});

test("an explicit HERMES_MODE wins in either mode", () => {
  assert.equal(resolveHermesMode({ HERMES_MODE: "mock" }, "account"), "mock");
  assert.equal(resolveHermesMode({ HERMES_MODE: "live" }, "legacy"), "live");
  assert.equal(resolveHermesMode({ HERMES_MODE: "hermes" }, "account"), "hermes");
});

test("a blank HERMES_MODE counts as unset", () => {
  assert.equal(resolveHermesMode({ HERMES_MODE: "" }, "account"), "live");
  assert.equal(resolveHermesMode({ HERMES_MODE: "  " }, "legacy"), "mock");
});

test("EXTRA_READ_ROOTS is empty when unset or blank (HG-245)", () => {
  assert.deepEqual(resolveExternalReadRoots({}), []);
  assert.deepEqual(resolveExternalReadRoots({ EXTRA_READ_ROOTS: "" }), []);
  assert.deepEqual(resolveExternalReadRoots({ EXTRA_READ_ROOTS: "   " }), []);
});

test("EXTRA_READ_ROOTS splits on semicolons, trims, drops empty segments and dedupes", () => {
  assert.deepEqual(
    resolveExternalReadRoots({ EXTRA_READ_ROOTS: "/Volumes/TiPlus-1T" }),
    ["/Volumes/TiPlus-1T"],
  );
  assert.deepEqual(
    resolveExternalReadRoots({ EXTRA_READ_ROOTS: " /Volumes/A ; ;/Volumes/B;; /Volumes/A " }),
    ["/Volumes/A", "/Volumes/B"],
  );
});

test("EXTRA_READ_ROOTS keeps path content intact, including spaces inside a segment", () => {
  assert.deepEqual(
    resolveExternalReadRoots({ EXTRA_READ_ROOTS: "/Volumes/My Disk;/Volumes/Other" }),
    ["/Volumes/My Disk", "/Volumes/Other"],
  );
});

test("a relative EXTRA_READ_ROOTS entry aborts startup instead of shifting the boundary", () => {
  assert.throws(() => resolveExternalReadRoots({ EXTRA_READ_ROOTS: "/Volumes/Ok;relative/root" }),
    /EXTRA_READ_ROOTS entries must be absolute paths/);
});
