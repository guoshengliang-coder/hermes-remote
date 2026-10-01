export type VoiceFeedback = "on" | "off";
const KEY = "hermes-go.voiceFeedback";

// Like appearance choices, this browser preference survives sign-out, without account sync.
export function readVoiceFeedback(): VoiceFeedback {
  try { return localStorage.getItem(KEY) === "off" ? "off" : "on"; }
  catch { return "on"; }
}
export function saveVoiceFeedback(value: VoiceFeedback): void {
  try { localStorage.setItem(KEY, value); } catch { /* keep the current page's choice */ }
}
export function voiceVibrationSupported(): boolean {
  return typeof globalThis.navigator?.vibrate === "function";
}
