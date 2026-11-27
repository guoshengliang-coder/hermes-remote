import type { SessionListItem } from "../hermes/types";
import { explicitProfile } from "../app/profile";
import { lastActiveMs } from "../app/grouping";

export interface SendListFeedback {
  id: string;
  account: string;
  device: string;
  profile: string;
  sessionId: string | null;
  cwd: string | null;
  text: string;
  createdAt: number;
  accepted?: boolean;
  newRow?: boolean;
  baselineCount?: number;
  synchronized?: boolean;
}

const entries = new Map<string, SendListFeedback>();
const listeners = new Set<() => void>();
const changed = () => { for (const listener of listeners) listener(); };

export function publishSendFeedback(entry: SendListFeedback): void {
  entries.set(entry.id, entry);
  const accepted = [...entries.values()].filter(row => row.accepted).sort((a, b) => b.createdAt - a.createdAt);
  for (const old of accepted.slice(64)) entries.delete(old.id);
  changed();
}

export function changeSendFeedback(id: string, change: Partial<Pick<SendListFeedback, "sessionId" | "accepted" | "synchronized">> | null): void {
  const entry = entries.get(id);
  if (!entry) return;
  if (change) entries.set(id, { ...entry, ...change, text: change.synchronized ? "" : entry.text });
  else entries.delete(id);
  changed();
}

export function discardSendFeedback(account?: string, device?: string): void {
  for (const entry of entries.values()) {
    if (entry.account !== account || entry.device !== device) entries.delete(entry.id);
  }
  changed();
}

export function readSendFeedback(account: string | undefined, device: string | undefined): SendListFeedback[] {
  return [...entries.values()].filter(entry => entry.account === account && entry.device === device);
}

export function observeSendFeedback(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function mergeSendFeedback(rows: readonly SessionListItem[], feedback: readonly SendListFeedback[]) {
  const sessions = [...rows];
  const byRow = new Map<SessionListItem, SendListFeedback>();
  const caughtUp = new Set<string>();
  const seen = new Set<string>();
  for (const entry of [...feedback].sort((a, b) => b.createdAt - a.createdAt)) {
    const id = entry.sessionId ?? entry.id;
    const key = JSON.stringify([entry.profile, id]);
    const index = rows.findIndex(row => row.id === id && explicitProfile(row) === entry.profile);
    const row = rows[index];
    if (entry.accepted && row && (entry.synchronized || entry.newRow || (lastActiveMs(row) ?? 0) >= entry.createdAt ||
        (entry.baselineCount !== undefined && (row.message_count ?? 0) > entry.baselineCount))) {
      if (!entry.synchronized) caughtUp.add(entry.id);
      if (seen.has(key)) continue;
      seen.add(key);
      const merged = (lastActiveMs(row) ?? 0) < entry.createdAt
        ? { ...row, last_active: entry.createdAt / 1000 } : row;
      sessions[index] = merged;
      if (entry.newRow) byRow.set(merged, entry);
      continue;
    }
    if (entry.synchronized || seen.has(key)) continue;
    seen.add(key);
    const local = { ...(row ?? { id, profile: entry.profile, title: entry.text, cwd: entry.cwd }),
      last_active: Math.max(entry.createdAt, row ? lastActiveMs(row) ?? 0 : 0) / 1000 };
    if (index < 0) sessions.push(local);
    else sessions[index] = local;
    byRow.set(local, entry);
  }
  return { sessions, byRow, caughtUp };
}
