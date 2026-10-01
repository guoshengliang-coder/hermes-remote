import { expect, it, vi } from "vitest";
import { VoiceHaptics } from "./voiceHaptics";
it("only cues actual recording and stable transitions, with a shorter send cue", () => {
  const vibrate = vi.fn((_duration: number) => true), h = new VoiceHaptics({ vibrate, visible: () => true });
  h.sync(false, true, "send"); h.sync(false, true, "cancel"); expect(vibrate).not.toHaveBeenCalled();
  h.sync(true, true, "send"); h.sync(true, true, "send");
  h.sync(true, true, "cancel"); h.sync(true, true, "cancel");
  h.sync(true, true, "edit"); h.sync(true, true, "send");
  expect(vibrate.mock.calls.map(([n]) => n)).toEqual([25, 30, 30, 10]);
  h.sync(false, true, "send"); h.reset();
  expect(vibrate.mock.calls.map(([n]) => n)).toEqual([25, 30, 30, 10, 0]);
});
it("cancels on disabling without replaying start when re-enabled, and permits a new recording", () => {
  const vibrate = vi.fn((_duration: number) => true), h = new VoiceHaptics({ vibrate, visible: () => true });
  h.sync(true, true, "send"); h.sync(true, false, "send"); h.sync(true, true, "send");
  expect(vibrate.mock.calls.map(([n]) => n)).toEqual([25, 0]);
  h.reset(); h.sync(true, true, "send"); expect(vibrate).toHaveBeenLastCalledWith(25);
});
it("never cues a hidden page or propagates browser rejection", () => {
  const vibrate = vi.fn((_duration: number) => false), h = new VoiceHaptics({ vibrate, visible: () => false });
  h.sync(true, true, "send"); expect(vibrate).not.toHaveBeenCalled();
  const refusing = new VoiceHaptics({ vibrate, visible: () => true });
  refusing.sync(true, true, "send"); refusing.sync(true, true, "cancel");
  vibrate.mockImplementation(() => { throw new Error("blocked"); });
  expect(() => { refusing.sync(true, true, "send"); refusing.reset(); }).not.toThrow();
});
