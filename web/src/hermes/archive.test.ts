import { describe, expect, it, vi } from "vitest";
import cases from "../../../docs/hermes-archive-cases.json";
import { GatewayHttpError } from "../api/gateway";
import { appError, diagnostics, localized } from "../errors";
import { archiveMode, archiveWithBackend, type ArchiveOperations } from "./archive";
import { HermesSocketError } from "./client";

function operations(): ArchiveOperations {
  return { mode: "legacy", sessionId: "s", archived: true,
    rpc: vi.fn().mockResolvedValue({ archived: true, session_key: "s" }),
    rest: vi.fn().mockResolvedValue({ ok: true }), read: vi.fn().mockResolvedValue(null) };
}
describe("archive upgrade contract", () => {
  for (const row of cases.readyCases) it(row.name, () => expect(archiveMode(row.payload)).toBe(row.mode));
  it("uses the official legacy RPC and never the failing REST ownership path", async () => {
    const ops = operations(); await archiveWithBackend(ops);
    expect(ops.rpc).toHaveBeenCalledOnce(); expect(ops.rest).not.toHaveBeenCalled();
  });
  it("uses fenced REST for authority and does not downgrade a refusal", async () => {
    const ops = operations(); ops.mode = "authority";
    ops.rest = vi.fn().mockRejectedValue(new GatewayHttpError(409, { detail: "revision_conflict" }, "/api/sessions/s"));
    await expect(archiveWithBackend(ops)).rejects.toMatchObject({ code: "HR-SESS-026" });
    expect(ops.rpc).not.toHaveBeenCalled(); expect(ops.read).not.toHaveBeenCalled();
  });
  it("only method-not-found permits old REST fallback", async () => {
    const ops = operations(); ops.rpc = vi.fn().mockRejectedValue(new HermesSocketError("rpc", "unsupported", "session.archive", -32601));
    await archiveWithBackend(ops); expect(ops.rest).toHaveBeenCalledOnce();
  });
  for (const code of [4001, 4009, 4064, 4403, 4090]) it(`does not bypass refused RPC ${code}`, async () => {
    const ops = operations(); ops.rpc = vi.fn().mockRejectedValue(new HermesSocketError("rpc", "refused", "session.archive", code));
    await expect(archiveWithBackend(ops)).rejects.toMatchObject({ code: "HR-SESS-026", retryable: false });
    expect(ops.rest).not.toHaveBeenCalled(); expect(ops.read).not.toHaveBeenCalled();
  });
  for (const requested of [true, false]) it(`reconciles ambiguous ${requested} without replay`, async () => {
    const ops = operations(); ops.archived = requested;
    ops.rpc = vi.fn().mockRejectedValue(new HermesSocketError("timeout", "lost reply"));
    ops.read = vi.fn().mockResolvedValue(requested);
    await archiveWithBackend(ops); expect(ops.rpc).toHaveBeenCalledOnce(); expect(ops.rest).not.toHaveBeenCalled();
  });
  it("absence/opposite state does not prove an ambiguous write failed or authorize replay", async () => {
    for (const state of [null, false]) {
      const ops = operations(); ops.rpc = vi.fn().mockRejectedValue(new HermesSocketError("closed", "disconnected"));
      ops.read = vi.fn().mockResolvedValue(state);
      await expect(archiveWithBackend(ops)).rejects.toMatchObject({ code: "HR-SESS-025", retryable: false });
      expect(ops.rpc).toHaveBeenCalledOnce(); expect(ops.rest).not.toHaveBeenCalled();
    }
  });
  it("a malformed/wrong-target acknowledgement needs a fresh read", async () => {
    const ops = operations(); ops.rpc = vi.fn().mockResolvedValue({ archived: true, session_key: "other" });
    await expect(archiveWithBackend(ops)).rejects.toMatchObject({ code: "HR-SESS-025" });
    expect(ops.read).toHaveBeenCalledOnce();
  });
  it("unknown capability never writes", async () => {
    const ops = operations(); ops.mode = "unknown";
    await expect(archiveWithBackend(ops)).rejects.toMatchObject({ code: "HR-COMPAT-004" });
    expect(ops.rpc).not.toHaveBeenCalled(); expect(ops.rest).not.toHaveBeenCalled();
  });
  it("registered failures are bilingual, non-retryable and redact diagnostics", () => {
    for (const code of ["HR-SESS-025", "HR-SESS-026", "HR-COMPAT-004"] as const) {
      const error = appError(code, "Authorization: Bearer private-secret cookie=private-cookie");
      expect(localized(error, "zh")).not.toBe(localized(error, "en"));
      expect(error.retryable).toBe(false); expect(diagnostics(error)).not.toMatch(/private-secret|private-cookie/);
      expect(JSON.parse(JSON.stringify(error)).code).toBe(code);
    }
  });
});
