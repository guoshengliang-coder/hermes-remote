import { describe, expect, it } from "vitest";
import type { AccountDevice } from "../api/gateway";
import { autoSelectDevice } from "./store";
import { detectLanguage } from "./i18n";

function device(deviceId: string, online: boolean, access: "owner" | "operator" = "owner"): AccountDevice {
  return {
    id: `id-${deviceId}`,
    generation: 1,
    deviceId,
    desktopDisplayName: deviceId,
    publicKeyFingerprint: "fp",
    connector: { online },
    hermes: { reachable: online },
    gateway: {},
    endToEnd: { healthy: null },
    access,
    isDefault: false,
  };
}

describe("autoSelectDevice", () => {
  it("keeps the remembered Mac while it is still listed, even offline", () => {
    expect(autoSelectDevice([device("a", true), device("b", false)], "b")?.deviceId).toBe("b");
  });

  it("picks the only owned online Mac", () => {
    expect(autoSelectDevice([device("a", true), device("b", false), device("s", true, "operator")], null)?.deviceId).toBe("a");
  });

  it("asks when there is no single owned online Mac", () => {
    expect(autoSelectDevice([device("a", true), device("b", true)], null)).toBeNull();
    expect(autoSelectDevice([device("a", false)], null)).toBeNull();
    expect(autoSelectDevice([], "gone")).toBeNull();
  });
});

describe("detectLanguage", () => {
  it("follows DESIGN.md §6: zh codes draw Chinese, every other language English", () => {
    expect(detectLanguage({ language: "zh-CN" })).toBe("zh");
    expect(detectLanguage({ language: "en-US" })).toBe("en");
    expect(detectLanguage({ language: "ja-JP" })).toBe("en");
    expect(detectLanguage({ language: "zh-TW" })).toBe("zh");
    expect(detectLanguage({ language: "zh" })).toBe("zh");
    expect(detectLanguage({ language: "" })).toBe("zh");
    expect(detectLanguage({})).toBe("zh");
  });
});
