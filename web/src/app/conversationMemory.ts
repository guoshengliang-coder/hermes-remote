import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import type { PendingAttachment } from "../chat/attachments";

// Memory only: Blobs must never enter localStorage or another account's composer.
let epoch = 0;
interface AttachmentOwner { epoch: number; value: PendingAttachment[]; listeners: Set<(value: PendingAttachment[]) => void> }
const files = new Map<string, AttachmentOwner>();
const views = new Map<string, unknown>();
export function conversationScope(accountId: string | undefined, deviceId: string, profile: string | null, sessionId: string | null): string {
  return JSON.stringify([location.origin, accountId ?? "", deviceId, profile ?? "default", sessionId ?? "new"]);
}
export function clearConversationMemory(): void {
  epoch++;
  for (const owner of files.values()) for (const file of owner.value) if (file.previewUrl) URL.revokeObjectURL(file.previewUrl);
  files.clear(); views.clear();
}
export function readConversationView<T>(key: string): T | undefined { return views.get(key) as T | undefined; }
export function viewWriter<T>(key: string) {
  const opened = epoch;
  return (view: T) => { if (epoch === opened) views.set(key, view); };
}

/** A captured writer always updates its originating conversation, including async image work. */
export function useAttachmentDraft(key: string | null) {
  const owner = useMemo(() => {
    const saved = key ? files.get(key) : undefined;
    if (saved) return saved;
    const created: AttachmentOwner = { epoch, value: [], listeners: new Set() };
    if (key) files.set(key, created);
    return created;
  }, [key]);
  const current = useRef(owner);
  current.current = owner;
  const [attachments, update] = useState(owner.value);
  const write = (target: typeof owner, next: PendingAttachment[] | ((previous: PendingAttachment[]) => PendingAttachment[])) => {
    const value = typeof next === "function" ? next(target.value) : next;
    if (target.epoch !== epoch) {
      for (const file of value) if (file.previewUrl && !target.value.includes(file)) URL.revokeObjectURL(file.previewUrl);
      return;
    }
    target.value = value;
    target.listeners.forEach(listener => listener(value));
  };
  const set = useCallback((next: PendingAttachment[] | ((previous: PendingAttachment[]) => PendingAttachment[])) => write(current.current, next), []);
  useLayoutEffect(() => {
    update(owner.value);
    owner.listeners.add(update);
    return () => { owner.listeners.delete(update); };
  }, [owner]);
  return { attachments, set, capture: () => ({
    value: owner.value,
    isCurrent: () => current.current === owner && owner.epoch === epoch,
    write: (next: PendingAttachment[] | ((previous: PendingAttachment[]) => PendingAttachment[])) => write(owner, next),
  }) };
}
