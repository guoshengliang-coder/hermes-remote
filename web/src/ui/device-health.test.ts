import { describe, it, expect } from "vitest";
import { deviceHealthText } from "./device-health";
import type { AccountDevice } from "../api/gateway";
const base = { connector: { online: true }, connection: {
  version: 1, business: "normal", control: "normal", local: "healthy", localReason: "ok", healthAgeMs: 0,
} } as AccountDevice;
describe("negotiated device health", () => {
  it("keeps congestion and control-only failure separate from local Hermes failure", () => {
    for (const control of ["suspect", "recovering", "lost"]) {
      const device = { ...base, connection: { ...base.connection!, control } };
      expect(deviceHealthText(device, (zh) => zh)).toContain("可继续聊天");
      expect(deviceHealthText(device, (_, en) => en)).toMatch(/chat/i);
    }
    expect(deviceHealthText({ ...base, connection: { ...base.connection!, local: "unhealthy" } }, (zh) => zh)).toContain("HR-CONN-006");
  });
  it("does not present stale health as offline and supports old device DTOs", () => {
    expect(deviceHealthText({ ...base, connection: { ...base.connection!, healthAgeMs: 60_000 } }, (zh) => zh)).toContain("待刷新");
    expect(deviceHealthText({ ...base, connection: undefined }, (zh) => zh)).toBe("在线");
  });
});
