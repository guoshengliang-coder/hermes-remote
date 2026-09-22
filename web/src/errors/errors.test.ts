import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  appError,
  CATALOG,
  diagnostics,
  display,
  expiredAnswer,
  fromHttp,
  fromNetworkFailure,
  fromRpcError,
  fromSocketError,
  localized,
  redact,
  unreadableResponse,
} from "./index";

describe("catalog", () => {
  // Every entry must match docs/ERROR_HANDLING.md word for word.
  const registry = readFileSync(resolve(process.cwd(), "../docs/ERROR_HANDLING.md"), "utf8");
  const rows = new Map<string, string[]>();
  for (const line of registry.split("\n")) {
    const m = /^\| `(HR-[A-Z]+-\d+)` \|/.exec(line);
    if (m) rows.set(m[1]!, line.trim().replace(/^\||\|$/g, "").split(" | ").map((c) => c.trim()));
  }

  it.each(Object.entries(CATALOG))(
    "%s copies the registry text and retryability",
    (code, entry) => {
      const cells = rows.get(code);
      expect(cells, `${code} is not registered`).toBeDefined();
      const [zh, en, retry] = cells!.slice(-3);
      expect(entry.zh).toBe(zh);
      expect(entry.en).toBe(en);
      expect(entry.retryable).toBe(retry!.replace(/\*/g, "").toLowerCase().startsWith("yes") || retry!.startsWith("Depends"));
    },
  );

  it("every entry has zh and en text", () => {
    for (const [code, entry] of Object.entries(CATALOG)) {
      expect(entry.zh, code).toMatch(/[一-鿿]/);
      expect(entry.en, code).toMatch(/[A-Za-z]/);
    }
  });
});

describe("RPC mapping", () => {
  it.each([
    [4001, "submit", "HR-SESS-002", true],
    [4007, "submit", "HR-SESS-001", false],
    [4090, "submit", "HR-SESS-013", true],
    [4009, "submit", "HR-SESS-007", true],
    [4009, "workspace", "HR-SESS-004", true],
    [4009, "generic", "HR-RPC-001", true],
    [-32001, "resume", "HR-SESS-017", false],
    [5028, "attach", "HR-SESS-016", false],
    [4000, "generic", "HR-RPC-001", false],
    [4000, "submit", "HR-SESS-007", false],
    [-32601, "answer", "HR-RPC-001", false],
    [4403, "generic", "HR-WEB-001", false],
    [1234, "submit", "HR-SESS-007", true],
    [1234, "generic", "HR-RPC-001", true],
  ] as const)("%i in %s → %s (retryable %s)", (code, context, expected, retryable) => {
    const error = fromRpcError(code, "upstream prose", context);
    expect(error.code).toBe(expected);
    expect(error.retryable).toBe(retryable);
    expect(error.details).toContain(String(code));
  });

  it("prefers a registered code in error.data (Gateway 4403 refusal)", () => {
    expect(fromRpcError(4403, "HR-WEB-001 …", "generic", { code: "HR-WEB-001" })).toMatchObject({
      code: "HR-WEB-001",
      zh: "网页版不支持此功能，请使用 Android 应用。",
      en: "This feature isn't available in the Hermes GO web app. Use the Android app instead.",
      retryable: false,
    });
  });

  it("maps socket failures", () => {
    expect(fromSocketError({ kind: "handshake-timeout", message: "x" }).code).toBe("HR-CONN-003");
    expect(fromSocketError({ kind: "timeout", message: "x" }).code).toBe("HR-RPC-002");
    expect(fromSocketError({ kind: "closed", message: "x" }).code).toBe("HR-CONN-004");
    expect(fromSocketError({ kind: "unsupported", message: "x" }).code).toBe("HR-WEB-002");
    expect(fromSocketError({ kind: "rpc", code: 4090, message: "owned", method: "prompt.submit" }, "submit").code).toBe("HR-SESS-013");
    expect(fromSocketError({ kind: "rpc", code: 4403, message: "no", data: { code: "HR-WEB-001" } }).code).toBe("HR-WEB-001");
  });

  it("maps expired answers to the Android codes", () => {
    expect(expiredAnswer("approval")).toMatchObject({ code: "HR-APPROVAL-003", retryable: false, action: "composer" });
    expect(expiredAnswer("clarify")).toMatchObject({ code: "HR-CLARIFY-001", retryable: false });
  });
});

describe("HTTP mapping", () => {
  it("uses the Gateway's registered code, retryability and action", () => {
    const e = fromHttp(401, { error: { code: "HR-AUTH-004", message: "revoked", retryable: false, recoveryAction: "sign_in", correlationId: "c-1" } });
    expect(e).toMatchObject({ code: "HR-AUTH-004", retryable: false, action: "sign-in" });
    expect(e.details).toContain("correlationId=c-1");
    expect(fromHttp(403, { error: { code: "HR-WEB-001", message: "x" } }).code).toBe("HR-WEB-001");
    expect(fromHttp(503, { error: { code: "HR-CONN-005", retryable: true } }).code).toBe("HR-CONN-005");
    expect(fromHttp(409, { error: { code: "HR-BIND-009", recoveryAction: "select_device" } })).toMatchObject({ code: "HR-BIND-009", action: "select-device" });
  });

  it("keeps an HR code this build does not know, with the Gateway's English copy", () => {
    const e = fromHttp(403, { error: { code: "HR-SHARE-099", message: "Some new condition.", retryable: false } });
    expect(e).toMatchObject({ code: "HR-SHARE-099", en: "Some new condition.", retryable: false });
    expect(e.zh).toBe(CATALOG["HR-RPC-001"].zh);
  });

  it.each([
    [401, "generic", "HR-AUTH-003"],
    [403, "generic", "HR-WEB-001"],
    [403, "download", "HR-FILE-003"],
    [404, "history", "HR-SESS-001"],
    [404, "download", "HR-FILE-005"],
    [404, "generic", "HR-WEB-005"],
    [413, "download", "HR-FILE-004"],
    [429, "account", "HR-AUTH-007"],
    [429, "device", "HR-WEB-004"],
    [500, "history", "HR-SYNC-003"],
    [502, "account", "HR-ACCOUNT-002"],
    [503, "device", "HR-WEB-004"],
    [504, "download", "HR-FILE-006"],
    [418, "generic", "HR-WEB-005"],
  ] as const)("status %i in %s without a code → %s", (status, context, code) => {
    expect(fromHttp(status, "<html>proxy error</html>", context).code).toBe(code);
  });

  it("maps network failures by online state and unreadable bodies by context", () => {
    expect(fromNetworkFailure(new TypeError("Failed to fetch"), false).code).toBe("HR-CONN-001");
    expect(fromNetworkFailure(new TypeError("Failed to fetch"), true)).toMatchObject({ code: "HR-WEB-003", retryable: true });
    expect(unreadableResponse("history", new SyntaxError("bad")).code).toBe("HR-SYNC-004");
    expect(unreadableResponse("device", "bad").code).toBe("HR-WEB-005");
  });
});

describe("presentation", () => {
  it("localizes zh and en with the code", () => {
    const e = appError("HR-SESS-007");
    expect(localized(e, "zh")).toBe("消息未发送，点按气泡重试。");
    expect(localized(e, "en")).toBe("The message was not sent. Tap the bubble to retry.");
    expect(display(e, "zh")).toBe("消息未发送，点按气泡重试。（错误码：HR-SESS-007）");
    expect(display(e, "en")).toContain("(Error code: HR-SESS-007)");
    expect(e).toMatchObject({ retryable: true, action: "retry" });
    expect(JSON.parse(JSON.stringify(e))).toEqual(e);
  });

  it("diagnostics carry code, retryability and redacted details", () => {
    const e = fromHttp(401, { error: { code: "HR-AUTH-003", message: "cookie __Host-hermes_go_access=hga_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG" } });
    const text = diagnostics(e, { device: "/Users/alice/x", attempt: 2 });
    expect(text).toContain("code=HR-AUTH-003");
    expect(text).toContain("retryable=false");
    expect(text).not.toContain("hga_");
    expect(text).not.toContain("alice");
  });
});

describe("redact", () => {
  it.each([
    ["Authorization: Bearer abc.def.ghi", "abc.def"],
    ["token Bearer eyJhbGciOi.xyz", "eyJhbGciOi"],
    ["cookie: __Host-hermes_go_refresh=hgr_0123456789012345678901234567890123456789abc", "hgr_"],
    ["x-hermes-csrf: hgc_0123456789012345678901234567890123456789abc", "hgc_"],
    ["access hga_0123456789012345678901234567890123456789abc", "hga_"],
    ["Set-Cookie: a=b; HttpOnly", "a=b"],
    ["mail alice@example.com please", "alice@example.com"],
    ["open /Users/alice/Documents/x.pdf", "alice"],
    ["open /home/bob/x", "bob"],
    ["wss://gw/v2/ws?ticket=secret123&x=1", "secret123"],
    ['{"refreshToken":"r-secret","ok":1}', "r-secret"],
  ])("removes secrets from %s", (input, secret) => {
    const out = redact(input);
    expect(out).not.toContain(secret);
  });

  it("keeps useful context and bounds the size", () => {
    expect(redact("rpc 4090 prompt.submit")).toBe("rpc 4090 prompt.submit");
    expect(redact("/Users/alice/a.png")).toBe("/Users/<user>/a.png");
    expect(redact("x".repeat(5000)).length).toBeLessThanOrEqual(2001);
  });
});
