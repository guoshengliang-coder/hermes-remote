// Unsent composer text per Mac and conversation (Android DraftStore, HG-41): kept in this browser's
// localStorage so it survives closing the app (owner decision 2026-09-22), removed on send and on
// sign-out. Bounded like Android: 50 drafts, 8,000 characters each, oldest dropped first.

const KEY = "hermes-go.drafts";
const MAX_DRAFTS = 50;
const MAX_CHARS = 8000;
export const DRAFT_CHANGED = "hermes-go:draft-changed";
let epoch = 0;

/** Invalidates mounted writers on sign-out so cleanup cannot restore cleared drafts. */
export function draftEpoch(): number { return epoch; }

interface DraftRecord {
  text: string;
  at: number;
}

type Drafts = Record<string, DraftRecord>;

export function draftKey(deviceId: string, sessionId: string | null, scope?: string): string {
  return `${deviceId}\n${sessionId ?? "new"}${scope ? `\nscope:${scope}` : ""}`;
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
  const drafts = read();
  if (drafts[key]) return drafts[key]!.text;
  // Upgrade assigns a legacy draft once, to the account/profile opening it now.
  const legacy = key.split("\n").slice(0, 2).join("\n");
  if (legacy !== key && drafts[legacy]) {
    drafts[key] = drafts[legacy]!;
    delete drafts[legacy];
    write(drafts);
    return drafts[key]!.text;
  }
  return "";
}

export function saveDraft(key: string, text: string, nowMs = Date.now()): void {
  const drafts = read();
  if (text.trim()) drafts[key] = { text: text.slice(0, MAX_CHARS), at: nowMs };
  else delete drafts[key];
  write(drafts);
  window.dispatchEvent(new Event(DRAFT_CHANGED));
}

export function hasDraft(key: string): boolean {
  return Boolean(read()[key]?.text.trim());
}

/** Every conversation of `deviceId` that has a draft (for the list's 草稿 marker). */
export function draftSessions(deviceId: string, accountId?: string): Set<string> {
  const out = new Set<string>();
  for (const [k, v] of Object.entries(read())) {
    const [device, session] = k.split("\n");
    if (accountId !== undefined && k.includes("\nscope:")) {
      try {
        const scope = JSON.parse(k.slice(k.indexOf("\nscope:") + 7)) as unknown;
        if (!Array.isArray(scope) || scope[0] !== location.origin || scope[1] !== accountId) continue;
      } catch { continue; }
    }
    if (device === deviceId && session && session !== "new" && v.text.trim()) out.add(session);
  }
  return out;
}

export function clearAllDrafts(): void {
  epoch++;
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* nothing stored */
  }
}
