export type VoiceAction = "send" | "cancel" | "edit";
export interface VoiceTarget { x: number; y: number; radius: number }
// Coordinates and margins use the drawn DOM targets, never a guessed distance from the button.
export function voiceActionAt(x: number, y: number, cancel: VoiceTarget | null, edit: VoiceTarget | null, previous: VoiceAction = "send"): VoiceAction {
  const hit = (target: VoiceTarget | null, buffer = 0) => target && Math.abs(x - target.x) <= target.radius + 12 + buffer && y <= target.y + target.radius + 12 + buffer;
  // Keep the selected target until the finger crosses a wider exit edge. The same stable action
  // drives release, highlights, copy and feedback; jitter never produces contradictory cues.
  if (previous === "cancel" && hit(cancel, 12)) return "cancel";
  if (previous === "edit" && hit(edit, 12)) return "edit";
  return hit(cancel) ? "cancel" : hit(edit) ? "edit" : "send";
}
export function appendVoiceText(draft: string, transcript: string): string {
  const text = transcript.trim();
  if (!text) return draft;
  return draft.trim() ? `${draft.trimEnd()}\n${text}` : text;
}
