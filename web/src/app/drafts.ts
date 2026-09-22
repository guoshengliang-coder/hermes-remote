// Unsent composer text per Mac and conversation (Android DraftStore, HG-41): kept in this browser's
// localStorage so it survives closing the app (owner decision 2026-09-22), removed on send and on
// sign-out. Bounded like Android: 50 drafts, 8,000 characters each, oldest dropped first.

const KEY = "hermes-go.drafts";
const MAX_DRAFTS = 50;
const MAX_CHARS = 8000;

interface DraftRecord {
  text: string;
  at: number;
}

type Drafts = Record<string, DraftRecord>;

export function draftKey(deviceId: string, sessionId: string | null): string {
  return `${deviceId}\n${sessionId ?? "new"}`;
}

function read(): Drafts {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? "{}");
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    const out: Drafts = {};
    for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
      const rec = v as Partial<DraftRecord> | null;
      if (rec && typeof rec.text === "string" && typeof rec.at === "number") out[k] = { text: rec.text, at: rec.at };
    }
    return out;
  } catch {
    return {};
  }
}

function write(drafts: Drafts): void {
  try {
    const entries = Object.entries(drafts).sort((a, b) => b[1].at - a[1].at).slice(0, MAX_DRAFTS);
    if (entries.length) localStorage.setItem(KEY, JSON.stringify(Object.fromEntries(entries)));
    else localStorage.removeItem(KEY);
  } catch {
    /* private mode or quota: drafts last for this page only */
  }
}

export function loadDraft(key: string): string {
  return read()[key]?.text ?? "";
}

export function saveDraft(key: string, text: string, nowMs = Date.now()): void {
  const drafts = read();
  if (text.trim()) drafts[key] = { text: text.slice(0, MAX_CHARS), at: nowMs };
  else delete drafts[key];
  write(drafts);
}

export function hasDraft(key: string): boolean {
  return Boolean(read()[key]?.text.trim());
}

/** Every conversation of `deviceId` that has a draft (for the list's 草稿 marker). */
export function draftSessions(deviceId: string): Set<string> {
  const out = new Set<string>();
  for (const [k, v] of Object.entries(read())) {
    const [device, session] = k.split("\n");
    if (device === deviceId && session && session !== "new" && v.text.trim()) out.add(session);
  }
  return out;
}

export function clearAllDrafts(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* nothing stored */
  }
}
