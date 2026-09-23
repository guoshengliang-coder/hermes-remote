// Mac files (chat images) kept across reloads, in IndexedDB (HG-108).
//
// Before this, Media.tsx held 60 blobs in a plain Map, so every page refresh re-downloaded every
// image at full size — measured at 6.93 MB for one five-screenshot conversation.
//
// Why IndexedDB and not the HTTP cache or Cache Storage. The Gateway serves browser traffic with
// `private, no-store` on purpose ("Cookie-authenticated conversation data must not linger in the
// HTTP cache of a shared computer"), and the service worker has a standing rule never to cache
// `/api/*`, `/v2/*` or anything account-bound. Storing the bytes here keeps both of those exactly
// as they are: this is an explicit write by the app, not the browser caching a response. The cost
// is the same one that motivated `no-store` — the bytes do land on this computer's disk — so the
// whole store is dropped on sign-out, alongside Cache Storage and the localStorage state.
//
// No revalidation: a Mac file path addresses fixed bytes for the life of a conversation, so a hit
// is served without asking the Gateway. That is also why this needs no ETag/If-None-Match support
// anywhere in the relay.

const DB_NAME = "hermes-go-media";
const STORE = "blobs";
const DB_VERSION = 1;

/** Matched to the Android disk cache (`ChatMediaRepository`: 200 files / 200 MB). */
export const MAX_CACHED_FILES = 200;
export const MAX_CACHED_BYTES = 200 * 1024 * 1024;

export interface CachedMedia {
  key: string;
  blob: Blob;
  bytes: number;
  /** Last read or write, for LRU eviction. */
  at: number;
}

/** Device-scoped so two Macs bound to the same browser never share an entry. */
export function mediaKey(deviceId: string, path: string): string {
  return `${deviceId}\n${path}`;
}

/**
 * Which entries to drop so the store stays under both ceilings, newest kept. Pure so the policy
 * can be tested without IndexedDB, which neither happy-dom nor jsdom provides.
 */
export function entriesToEvict(
  entries: readonly Pick<CachedMedia, "key" | "bytes" | "at">[],
  maxFiles: number = MAX_CACHED_FILES,
  maxBytes: number = MAX_CACHED_BYTES,
): string[] {
  const newestFirst = [...entries].sort((a, b) => b.at - a.at || (a.key < b.key ? -1 : 1));
  const evicted: string[] = [];
  let kept = 0;
  let keptBytes = 0;
  for (const entry of newestFirst) {
    if (kept + 1 > maxFiles || keptBytes + entry.bytes > maxBytes) {
      evicted.push(entry.key);
      continue;
    }
    kept += 1;
    keptBytes += entry.bytes;
  }
  return evicted;
}

function hasIndexedDb(): boolean {
  return typeof indexedDB !== "undefined";
}

let open: Promise<IDBDatabase | null> | null = null;

function database(): Promise<IDBDatabase | null> {
  if (open) return open;
  if (!hasIndexedDb()) return (open = Promise.resolve(null));
  open = new Promise<IDBDatabase | null>((resolve) => {
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION);
    } catch {
      // Private mode, or storage disabled: the memory cache in Media.tsx still works.
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "key" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
  return open;
}

function settled<T>(request: IDBRequest<T>): Promise<T | null> {
  return new Promise((resolve) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
  });
}

/** The cached blob for this path, or null. A hit refreshes its LRU stamp. */
export async function readCachedMedia(deviceId: string, path: string): Promise<Blob | null> {
  const db = await database();
  if (!db) return null;
  const key = mediaKey(deviceId, path);
  let record: CachedMedia | null;
  try {
    record = (await settled(db.transaction(STORE, "readonly").objectStore(STORE).get(key))) as CachedMedia | null;
  } catch {
    return null;
  }
  if (!record?.blob) return null;
  // Touch in the background; a failed touch only costs this entry its place in the LRU order.
  void touch(db, { ...record, at: Date.now() });
  return record.blob;
}

async function touch(db: IDBDatabase, record: CachedMedia): Promise<void> {
  try {
    await settled(db.transaction(STORE, "readwrite").objectStore(STORE).put(record));
  } catch {
    /* the read already succeeded; the stamp is best effort */
  }
}

/** Store the blob and trim the store back under both ceilings. Never throws. */
export async function writeCachedMedia(deviceId: string, path: string, blob: Blob): Promise<void> {
  const db = await database();
  if (!db) return;
  const record: CachedMedia = { key: mediaKey(deviceId, path), blob, bytes: blob.size, at: Date.now() };
  try {
    await settled(db.transaction(STORE, "readwrite").objectStore(STORE).put(record));
  } catch {
    return;
  }
  await trim(db);
}

async function trim(db: IDBDatabase): Promise<void> {
  try {
    const store = db.transaction(STORE, "readonly").objectStore(STORE);
    const all = ((await settled(store.getAll())) ?? []) as CachedMedia[];
    const drop = entriesToEvict(all.map(({ key, bytes, at }) => ({ key, bytes, at })));
    if (!drop.length) return;
    const writable = db.transaction(STORE, "readwrite").objectStore(STORE);
    for (const key of drop) writable.delete(key);
  } catch {
    /* an over-full cache is better than a broken one */
  }
}

/** Drop every cached file. Called on sign-out together with Cache Storage and localStorage. */
export async function clearMediaCache(): Promise<void> {
  const db = await database();
  if (!db) return;
  try {
    await settled(db.transaction(STORE, "readwrite").objectStore(STORE).clear());
  } catch {
    /* nothing better to do than leave it */
  }
}
