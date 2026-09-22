import { beforeEach, describe, expect, it } from "vitest";
import { clearAllPins, loadPins, pinToken, profileKeyOf, savePins, togglePin } from "./pins";

describe("pins (browser-local, per Mac)", () => {
  beforeEach(() => localStorage.clear());

  it("keys a pin by the session's own profile, default normalized", () => {
    expect(pinToken({ id: "s1" })).toBe("default/s1");
    expect(pinToken({ id: "s1", profile: "  " })).toBe("default/s1");
    expect(pinToken({ id: "s1", profile: "work" })).toBe("work/s1");
    expect(profileKeyOf({ profile: "main", is_default_profile: true })).toBe("default");
  });

  it("round-trips per device and toggles without mutating", () => {
    const first = togglePin(new Set(), "default/a");
    expect([...first]).toEqual(["default/a"]);
    const off = togglePin(first, "default/a");
    expect(first.has("default/a")).toBe(true);
    expect(off.size).toBe(0);

    savePins("mac-1", first);
    expect([...loadPins("mac-1")]).toEqual(["default/a"]);
    expect(loadPins("mac-2").size).toBe(0);
    savePins("mac-1", off);
    expect(localStorage.getItem("hermes-go.pins.mac-1")).toBeNull();
  });

  it("reads corrupt storage as no pins", () => {
    localStorage.setItem("hermes-go.pins.mac-1", "{not json");
    expect(loadPins("mac-1").size).toBe(0);
    localStorage.setItem("hermes-go.pins.mac-1", JSON.stringify({ a: 1 }));
    expect(loadPins("mac-1").size).toBe(0);
    localStorage.setItem("hermes-go.pins.mac-1", JSON.stringify(["default/a", 3]));
    expect([...loadPins("mac-1")]).toEqual(["default/a"]);
  });

  it("sign-out clears every Mac's pins and nothing else", () => {
    savePins("mac-1", new Set(["default/a"]));
    savePins("mac-2", new Set(["work/b"]));
    localStorage.setItem("hermes-go.deviceId", "mac-1");
    clearAllPins();
    expect(loadPins("mac-1").size).toBe(0);
    expect(loadPins("mac-2").size).toBe(0);
    expect(localStorage.getItem("hermes-go.deviceId")).toBe("mac-1");
  });
});
