import type { VoiceAction } from "./voiceGesture";

interface VibrationPort { vibrate(duration: number): boolean; visible(): boolean }
const browserPort: VibrationPort = {
  vibrate: (duration) => globalThis.navigator?.vibrate?.(duration) ?? false,
  visible: () => document.visibilityState === "visible",
};

// No timers or queued pulses: every cue belongs to the currently committed recording/zone.
// Durations distinguish cues; the browser API cannot set vibration amplitude.
export class VoiceHaptics {
  private active = false;
  private zone: VoiceAction = "send";
  private running = false;
  constructor(private readonly port: VibrationPort = browserPort) {}
  sync(active: boolean, enabled: boolean, zone: VoiceAction): void {
    const starting = active && !this.active;
    const changed = zone !== this.zone;
    this.active = active; this.zone = zone;
    if (!active || !enabled) { this.stop(); return; }
    if (starting) this.pulse(25);
    else if (changed) this.pulse(zone === "send" ? 10 : 30);
  }
  reset(): void { this.active = false; this.zone = "send"; this.stop(); }
  private pulse(duration: number): void {
    if (!this.port.visible()) return;
    try { this.running = this.port.vibrate(duration); } catch { /* optional browser capability */ }
  }
  private stop(): void {
    if (!this.running) return;
    this.running = false;
    try { this.port.vibrate(0); } catch { /* rejection never changes voice input */ }
  }
}
