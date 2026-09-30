export type VoiceAction = "send" | "cancel" | "edit";
export interface VoiceTarget { x: number; y: number; radius: number }
// Coordinates and margins use the drawn DOM targets, never a guessed distance from the button.
export function voiceActionAt(x: number, y: number, cancel: VoiceTarget | null, edit: VoiceTarget | null): VoiceAction {
  const hit = (target: VoiceTarget | null) => target && Math.abs(x - target.x) <= target.radius + 12 && y <= target.y + target.radius + 12;
  return hit(cancel) ? "cancel" : hit(edit) ? "edit" : "send";
}
export function appendVoiceText(draft: string, transcript: string): string {
  const text = transcript.trim();
  if (!text) return draft;
  return draft.trim() ? `${draft.trimEnd()}\n${text}` : text;
}
