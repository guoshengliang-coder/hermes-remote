import { afterEach, expect, it, vi } from "vitest";
import { readVoiceFeedback, saveVoiceFeedback, voiceVibrationSupported } from "./voiceFeedback";
import { clearLocalPrefs } from "./localPrefs";
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); });
it("defaults on, persists off, and recovers an invalid preference", () => {
  expect(readVoiceFeedback()).toBe("on");
  saveVoiceFeedback("off"); expect(readVoiceFeedback()).toBe("off");
  clearLocalPrefs(); expect(readVoiceFeedback()).toBe("off");
  saveVoiceFeedback("on"); expect(readVoiceFeedback()).toBe("on");
  localStorage.setItem("hermes-go.voiceFeedback", "invalid"); expect(readVoiceFeedback()).toBe("on");
});
it("keeps voice usable when storage is blocked", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
  expect(readVoiceFeedback()).toBe("on"); expect(() => saveVoiceFeedback("off")).not.toThrow();
});
it("detects an API, not actual hardware feedback", () => {
  vi.stubGlobal("navigator", {});
  expect(voiceVibrationSupported()).toBe(false);
});
