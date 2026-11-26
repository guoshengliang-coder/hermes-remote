import type { PendingAttachment } from "./attachments";

const DATABASE = "hermes-go.prompt-sends.v1";
const STORE = "sends";
const ROWS = "rows";
const MAX_PENDING_BYTES = 100 * 1024 * 1024;

export type SendPhase = "prepared" | "active" | "creating" | "submitting" | "failed" | "unknown";
export interface SendOwner { account: string; device: string; profile: string }
type SavedAttachment = Omit<PendingAttachment, "file" | "previewUrl"> & { bytes: ArrayBuffer };
export interface SendRecord extends SendOwner {
  id: string;
  sessionId: string | null;
  cwd: string | null;
  text: string;
  createdAt: number;
  phase: SendPhase;
  attachments: SavedAttachment[];
}

export type SendListRow = Pick<SendRecord, "id" | "account" | "device" | "profile" | "sessionId" | "createdAt" | "text" | "cwd" | "phase">;
type SendMetadata = SendListRow & { size: number };

function metadata({ id, account, device, profile, sessionId, createdAt, text, cwd, phase, attachments }: SendRecord): SendMetadata {
  return { id, account, device, profile, sessionId, createdAt, text: text.slice(0, 180), cwd, phase,
    size: attachments.reduce((size, attachment) => size + attachment.bytes.byteLength, 0) };
}

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 2);
    request.onupgradeneeded = event => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "id" });
      const rows = db.createObjectStore(ROWS, { keyPath: "id" });
      if (event.oldVersion === 1) {
        const cursor = request.transaction!.objectStore(STORE).openCursor();
        cursor.onsuccess = () => {
          if (!cursor.result) return;
          rows.put(metadata(cursor.result.value as SendRecord));
          cursor.result.continue();
        };
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("message storage blocked"));
  });
}

async function transaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore, setResult: (value: T) => void, rows: IDBObjectStore) => void): Promise<T> {
  const db = await open();
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction([STORE, ROWS], mode);
      let result: T;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(tx.error ?? new Error("message storage transaction aborted"));
      operation(tx.objectStore(STORE), value => { result = value; }, tx.objectStore(ROWS));
    });
  } finally { db.close(); }
}

let epoch = 0;
const changed = new Set<() => void>();
export function observeSendJournal(listener: () => void): () => void {
  changed.add(listener);
  return () => { changed.delete(listener); };
}

function notify() {
  for (const listener of changed) listener();
}

export async function stageSend(record: Omit<SendRecord, "phase" | "attachments">, attachments: readonly PendingAttachment[]): Promise<void> {
  const ownerEpoch = epoch;
  if (attachments.reduce((sum, attachment) => sum + attachment.file.size, 0) > MAX_PENDING_BYTES) {
    throw new Error("message exceeds attachment recovery capacity");
  }
  // WebKit storage can reject Blobs. Store bytes together with the complete message in one commit.
  const saved = await Promise.all(attachments.map(async ({ id, name, mimeType, kind, file }) => ({
    id, name, mimeType, kind, bytes: await file.arrayBuffer(),
  })));
  await transaction<void>("readwrite", (store, _, rows) => {
    if (ownerEpoch !== epoch) throw new Error("message storage owner changed");
    const request = rows.getAll() as IDBRequest<SendMetadata[]>;
    request.onsuccess = () => {
      const bytes = request.result.reduce((sum, entry) => sum + entry.size, 0);
      const entry = { ...record, phase: "prepared", attachments: saved } satisfies SendRecord;
      const row = metadata(entry);
      if (ownerEpoch !== epoch || bytes + row.size > MAX_PENDING_BYTES) {
        store.transaction.abort();
        return;
      }
      store.add(entry);
      rows.add(row);
    };
  });
  notify();
}

/** Atomic phase claim prevents another tab from retrying the same recovered operation. */
export function updateSend(id: string, expected: readonly SendPhase[], changes: Partial<Pick<SendRecord, "phase" | "sessionId">>): Promise<boolean> {
  return transaction<boolean>("readwrite", (store, result, rows) => {
    const request = store.get(id) as IDBRequest<SendRecord | undefined>;
    request.onsuccess = () => {
      const entry = request.result;
      const matches = entry !== undefined && expected.includes(entry.phase);
      if (matches) {
        const updated = { ...entry, ...changes };
        store.put(updated);
        rows.put(metadata(updated));
      }
      result(matches);
    };
  }).then(updated => { if (updated) notify(); return updated; });
}

export function finishSend(id: string): Promise<void> {
  return transaction<void>("readwrite", (store, _, rows) => { store.delete(id); rows.delete(id); }).then(notify);
}

export function listSends(owner: SendOwner, sessionId: string | null): Promise<SendRecord[]> {
  return transaction("readonly", (store, result) => {
    const request = store.getAll() as IDBRequest<SendRecord[]>;
    request.onsuccess = () => result(request.result.filter(record =>
      record.account === owner.account && record.device === owner.device && record.profile === owner.profile && record.sessionId === sessionId,
    ).sort((a, b) => a.createdAt - b.createdAt));
  });
}

export function listSendRows(account: string, device: string): Promise<SendListRow[]> {
  return transaction("readonly", (_, result, rows) => {
    const request = rows.getAll() as IDBRequest<SendMetadata[]>;
    request.onsuccess = () => result(request.result
      .filter(record => record.account === account && record.device === device)
      .sort((a, b) => b.createdAt - a.createdAt));
  });
}

export function restoreSendAttachments(record: SendRecord): PendingAttachment[] {
  return record.attachments.map(({ bytes, ...attachment }) => ({
    ...attachment, file: new Blob([bytes], { type: attachment.mimeType }),
  }));
}

export function clearSendJournal(): Promise<void> {
  ++epoch;
  return transaction<void>("readwrite", (store, _, rows) => { store.clear(); rows.clear(); }).then(notify);
}
