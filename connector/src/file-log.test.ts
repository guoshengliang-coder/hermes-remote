import assert from "node:assert/strict";
import test from "node:test";
import { describeRejectedPath } from "./file-log.js";

// Mirrors the parser in index.ts so the test exercises the same shape of input.
const rawQueryParameter = (path: string, name: string): string | undefined => {
  const query = path.split("?", 2)[1]?.split("#", 1)[0];
  if (query === undefined) return undefined;
  for (const part of query.split("&")) {
    const separator = part.indexOf("=");
    const rawName = separator >= 0 ? part.slice(0, separator) : part;
    if (decodeURIComponent(rawName.replace(/\+/g, " ")) === name) {
      return separator >= 0 ? part.slice(separator + 1) : "";
    }
  }
  return undefined;
};

const describe = (path: string) => describeRejectedPath(path, rawQueryParameter);

test("a rejection log never carries the requested path or any directory name", () => {
  const line = describe("/api/files?path=%2FUsers%2Fbs%2FDocuments%2Fxiaomai-daily-report%2Freport.html");
  assert.ok(!line.includes("Users"), line);
  assert.ok(!line.includes("bs"), line);
  assert.ok(!line.includes("Documents"), line);
  assert.ok(!line.includes("xiaomai-daily-report"), line);
  assert.ok(!line.includes("report"), line);
});

test("the extension survives, so a whitelist problem is diagnosable", () => {
  assert.equal(describe("/api/files?path=%2FUsers%2Fbs%2Freport.html"), "ext=.html len=21");
  assert.equal(describe("/api/files?path=%2Fa%2Fb.tar.gz"), "ext=.gz len=11");
});

test("an extensionless path is reported as such rather than guessed", () => {
  assert.equal(describe("/api/files?path=%2Fusr%2Flocal%2Fbin%2Fhermes"), "ext=none len=21");
});

// A dot in a parent directory must not be mistaken for the file's extension.
test("only a dot after the last separator counts as an extension", () => {
  assert.equal(describe("/api/files?path=%2Fsrv%2Frelease.d%2Fartifact"), "ext=none len=23");
});

test("a missing or unusable path is still logged, not dropped", () => {
  assert.equal(describe("/api/files"), "path=absent");
  assert.equal(describe("/api/files?path="), "path=absent");
  assert.equal(describe("/api/files?path=%E0%A4%A"), "path=undecodable");
});

test("a very long extension cannot pad the log line", () => {
  const line = describe(`/api/files?path=%2Fa%2Fb.${"x".repeat(200)}`);
  assert.ok(line.startsWith("ext=."), line);
  assert.ok(line.length < 40, line);
});
