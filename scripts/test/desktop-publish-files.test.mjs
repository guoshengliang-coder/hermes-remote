import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { desktopPublishFiles } from "../lib/desktop-publish-files.mjs";

function envelope(entries, key = "artifacts") {
  return { payload: Buffer.from(JSON.stringify({ [key]: entries })).toString("base64url") };
}

test("publisher reads artifact names from signed v1 and v2 payloads", () => {
  assert.deepEqual(desktopPublishFiles(envelope([
    { fileName: "Hermes-Server-0.21.0-arm64.tar.gz" },
    { fileName: "Hermes-Connector-0.1.8-arm64.tar.gz" },
  ])), ["Hermes-Server-0.21.0-arm64.tar.gz", "Hermes-Connector-0.1.8-arm64.tar.gz"]);
  assert.deepEqual(desktopPublishFiles(envelope([
    { fileName: "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz" },
    { fileName: "Hermes-Component-connector-0.1.8-arm64.tar.gz" },
  ], "components")), [
    "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz",
    "Hermes-Component-connector-0.1.8-arm64.tar.gz",
  ]);
});

test("publisher rejects unsigned top-level names, unsafe names, and duplicates", () => {
  assert.throws(() => desktopPublishFiles({ artifacts: [{ fileName: "unsigned.tar.gz" }] }));
  assert.throws(() => desktopPublishFiles(envelope([{ fileName: "../unsafe.tar.gz" }])));
  assert.throws(() => desktopPublishFiles(envelope([
    { fileName: "same.tar.gz" }, { fileName: "same.tar.gz" },
  ])));
});

test("publisher file-list CLI reports a localized release code without a path", () => {
  const result = spawnSync(process.execPath, ["scripts/desktop-publish-files.mjs", "/missing/private/manifest"], {
    encoding: "utf8", shell: false,
  });
  assert.equal(result.status, 65);
  const error = JSON.parse(result.stderr);
  assert.equal(error.code, "HR-RELEASE-004");
  assert.match(error.summaryZh, /发布包/);
  assert.match(error.summaryEn, /release/);
  assert.equal(error.retryable, true);
  assert.equal(result.stderr.includes("/missing/private/manifest"), false);
});
