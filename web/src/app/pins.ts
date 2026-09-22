import type { SessionListItem } from "../hermes/types";

// Pinned sessions, stored in this browser only (DESIGN §5.2 "已置顶 · 仅此设备"): Hermes has no pin
// API, so pins live where they were made, exactly like Android's PinStore. A pin is keyed by the
// session's OWN profile ("<profile>/<sessionId>") because the list spans every profile. Pins are
// per Mac and are dropped on sign-out with the rest of this browser's state.

const PREFIX = "hermes-go.pins.";

/** The default profile is normalized to "default", as Android's mappers do. */
export function profileKeyOf(session: Pick<SessionListItem, "profile" | "is_default_profile">): string {
  const profile = session.profile?.trim();
  return profile && !session.is_default_profile ? profile : "default";
}

export function pinToken(session: Pick<SessionListItem, "id" | "profile" | "is_default_profile">): string {
  return `${profileKeyOf(session)}/${session.id}`;
}

function key(deviceId: string): string {
  return `${PREFIX}${deviceId}`;
}

export function loadPins(deviceId: string): Set<string> {
  try {
    const raw = localStorage.getItem(key(deviceId));
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === "string") : []);
  } catch {
    // Unreadable or private mode: no pins rather than a broken list.
    return new Set();
  }
}

export function savePins(deviceId: string, pins: ReadonlySet<string>): void {
  try {
    if (pins.size) localStorage.setItem(key(deviceId), JSON.stringify([...pins].sort()));
    else localStorage.removeItem(key(deviceId));
  } catch {
    /* private mode: pins last for this page only */
  }
}

/** Flip one pin and return the new set (the input is not modified). */
export function togglePin(pins: ReadonlySet<string>, token: string): Set<string> {
  const next = new Set(pins);
  if (!next.delete(token)) next.add(token);
  return next;
}

/** Sign-out: forget every Mac's pins in this browser. */
export function clearAllPins(): void {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k?.startsWith(PREFIX)) doomed.push(k);
    }
    for (const k of doomed) localStorage.removeItem(k);
  } catch {
    /* nothing stored */
  }
}
