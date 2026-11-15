import { beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayClient } from "../api/gateway";
import { HermesSocket, HermesSocketError } from "../hermes/client";
import { archiveSession } from "./oneShot";

vi.mock("../hermes/client", async (original) => {
  const module = await original<typeof import("../hermes/client")>();
  return { ...module, HermesSocket: vi.fn() };
});

describe("device-scoped archive adapter", () => {
  let socket: { connect: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn>; archiveBackend: ReturnType<typeof vi.fn>; call: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    vi.mocked(HermesSocket).mockClear();
    socket = { connect: vi.fn(), close: vi.fn(), archiveBackend: vi.fn().mockResolvedValue("legacy"),
      call: vi.fn().mockResolvedValue({ archived: true, session_key: "s" }) };
    vi.mocked(HermesSocket).mockImplementation(function () { return socket as unknown as HermesSocket; });
  });
  function client() {
    const value = new GatewayClient();
    vi.spyOn(value, "settled").mockResolvedValue();
    vi.spyOn(value, "updateSession").mockResolvedValue({ ok: true });
    vi.spyOn(value, "deviceApi").mockResolvedValue({ sessions: [] });
    return value;
  }
  it("pins both the socket's Mac and RPC's original profile without resume", async () => {
    const api = client();
    await archiveSession(api, "mac-a", "s", true, "work", 7);
    expect(HermesSocket).toHaveBeenCalledWith({ url: expect.stringContaining("mac-a/ws") });
    expect(socket.call).toHaveBeenCalledExactlyOnceWith("session.archive", { session_id: "s", archived: true, profile: "work" });
    expect(api.updateSession).not.toHaveBeenCalled(); expect(socket.close).toHaveBeenCalled();
  });
  it("does not queue a stale inverse intent while one request is pending", async () => {
    const api = client(); let resolve!: (value: unknown) => void;
    socket.call.mockImplementation(() => new Promise((r) => { resolve = r; }));
    const first = archiveSession(api, "mac-a", "s", true, "work");
    await vi.waitFor(() => expect(socket.call).toHaveBeenCalledOnce());
    await expect(archiveSession(api, "mac-a", "s", false, "work")).rejects.toMatchObject({ code: "HR-SESS-026" });
    resolve({ archived: true, session_key: "s" }); await first;
    expect(socket.call).toHaveBeenCalledOnce();
  });
  it("a lost reply reads only the same Mac/profile and never writes REST", async () => {
    const api = client(); socket.call.mockRejectedValue(new HermesSocketError("timeout", "lost"));
    vi.mocked(api.deviceApi).mockResolvedValue({ sessions: [{ id: "s", profile: "work", archived: true }] });
    await archiveSession(api, "mac-a", "s", true, "work");
    expect(api.deviceApi).toHaveBeenCalledWith("mac-a", "GET", expect.stringContaining("profile=work"), expect.anything());
    expect(api.updateSession).not.toHaveBeenCalled();
  });
  it("a route cancellation before handshake cannot issue a mutation", async () => {
    const api = client(); const abort = new AbortController(); abort.abort();
    await expect(archiveSession(api, "mac-a", "s", true, "work", 0, true, abort.signal)).rejects.toBeDefined();
    expect(socket.call).not.toHaveBeenCalled(); expect(api.updateSession).not.toHaveBeenCalled();
  });
  it("keeps the old Gateway REST contract without probing a withheld RPC", async () => {
    const api = client(); await archiveSession(api, "mac-a", "s", true, "work", 7, false);
    expect(api.updateSession).toHaveBeenCalledExactlyOnceWith("mac-a", "s", { archived: true }, "work", 7);
    expect(HermesSocket).not.toHaveBeenCalled();
  });
});
