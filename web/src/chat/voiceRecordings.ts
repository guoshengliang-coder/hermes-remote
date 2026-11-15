// Local-only PCM journal. Separate records/chunks keep append writes bounded to one audio packet.
// No provider token, audio or transcript is ever logged. Logout invalidates mounted writers first.
export const VOICE_RETENTION_MS = 24 * 60 * 60 * 1000;
export const VOICE_STORAGE_LIMIT = 32 * 1024 * 1024;
export const VOICE_MAX_BYTES = 16000 * 2 * 300;
export const VOICE_WRITER_LEASE_MS = 15_000;
export interface VoiceRecording {
  id: string; scope: string; created: number; bytes: number; complete: boolean; limited: boolean;
  /** A final transcript is a durable handoff before the audio is deleted, including a failed send. */
  text?: string;
  baseText?: string;
  recording?: boolean;
  leaseUntil?: number;
}
export function voiceRecordingActive(record: VoiceRecording, now = Date.now()): boolean {
  return record.recording === true && (record.leaseUntil ?? 0) > now;
}
export type VoiceDeliveryReceipt = (() => void) & { onStored?: (sessionId: string) => void };
const DB = "hermes-go.voice-recordings";
let epoch = 0;
let connection: Promise<IDBDatabase> | undefined;
function open(): Promise<IDBDatabase> {
  return connection ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB, 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore("recordings", { keyPath: "id" });
      request.result.createObjectStore("chunks", { keyPath: ["id", "index"] });
    };
    request.onerror = () => { connection = undefined; reject(new Error("voice storage unavailable")); };
    request.onsuccess = () => { const db = request.result; db.onversionchange = () => { db.close(); connection = undefined; }; resolve(db); };
  });
}
function result<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(new Error("voice storage failed")); });
}
function done(t: IDBTransaction): Promise<void> {
  const completion = new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onabort = t.onerror = () => reject(new Error("voice storage failed")); });
  // A request may reject/throw before its caller reaches await completion. Observe both paths;
  // return the original promise so transaction failures still propagate to every normal caller.
  void completion.catch(() => {});
  return completion;
}
function erase(t: IDBTransaction, id: string): void {
  t.objectStore("recordings").delete(id);
  t.objectStore("chunks").delete(IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER]));
}
export class VoiceRecordingStore {
  private readonly owner = epoch;
  constructor(private readonly now = Date.now) {}
  private async transaction(mode: IDBTransactionMode): Promise<IDBTransaction> {
    const db = await open();
    if (this.owner !== epoch) throw new Error("voice owner expired");
    return db.transaction(["recordings", "chunks"], mode);
  }
  async list(scope: string): Promise<VoiceRecording[]> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const all = await result<VoiceRecording[]>(t.objectStore("recordings").getAll());
    const valid: VoiceRecording[] = [];
    for (const record of all) {
      if (record.created + VOICE_RETENTION_MS <= this.now() || (!record.bytes && !record.text && !voiceRecordingActive(record, this.now()))) erase(t, record.id);
      else if (record.scope === scope) valid.push(record);
    }
    await completed;
    return valid.sort((a, b) => b.created - a.created);
  }
  async begin(scope: string): Promise<VoiceRecording> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const all = await result<VoiceRecording[]>(t.objectStore("recordings").getAll());
    let total = 0;
    for (const record of all) {
      if (record.created + VOICE_RETENTION_MS <= this.now() || (!record.bytes && !record.text && !voiceRecordingActive(record, this.now()))) erase(t, record.id);
      else {
        if (record.scope === scope && !record.text) { await completed; throw new Error("voice recording already pending"); }
        total += record.recording !== false && !record.complete && !record.text ? VOICE_MAX_BYTES : record.bytes;
      }
    }
    // Reserve a full five minutes, rather than discovering capacity halfway through a sentence.
    if (total + VOICE_MAX_BYTES > VOICE_STORAGE_LIMIT) { await completed; throw new Error("voice storage full"); }
    const record: VoiceRecording = { id: crypto.randomUUID(), scope, created: this.now(), bytes: 0, complete: false, limited: false, recording: true, leaseUntil: this.now() + VOICE_WRITER_LEASE_MS };
    t.objectStore("recordings").add(record); await completed; return record;
  }
  async append(id: string, index: number, pcm: Uint8Array): Promise<void> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const record = await result<VoiceRecording | undefined>(t.objectStore("recordings").get(id));
    if (!record || record.recording === false || record.complete || record.text || record.created + VOICE_RETENTION_MS <= this.now() || record.bytes + pcm.length > VOICE_MAX_BYTES) {
      await completed; throw new Error("voice recording unavailable");
    }
    // A duplicate packet is an error, never an overwrite that inflates recorded duration.
    t.objectStore("chunks").add({ id, index, pcm });
    t.objectStore("recordings").put({ ...record, bytes: record.bytes + pcm.length, leaseUntil: this.now() + VOICE_WRITER_LEASE_MS }); await completed;
  }
  async seal(id: string, complete: boolean, limited = false): Promise<VoiceRecording> {
    return this.update(id, (record) => ({ ...record, complete, limited, recording: false, leaseUntil: 0 }));
  }
  async relocate(id: string, scope: string): Promise<void> { await this.update(id, record => ({ ...record, scope })); }
  private async update(id: string, change: (r: VoiceRecording) => VoiceRecording): Promise<VoiceRecording> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const record = await result<VoiceRecording | undefined>(t.objectStore("recordings").get(id));
    if (!record || record.created + VOICE_RETENTION_MS <= this.now()) { await completed; throw new Error("voice recording unavailable"); }
    const next = change(record); t.objectStore("recordings").put(next); await completed; return next;
  }
  async audio(id: string): Promise<Uint8Array[]> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const record = await result<VoiceRecording | undefined>(t.objectStore("recordings").get(id));
    if (!record || voiceRecordingActive(record, this.now()) || record.created + VOICE_RETENTION_MS <= this.now()) { await completed; throw new Error("voice recording unavailable"); }
    const chunks = await result<{ pcm: Uint8Array }[]>(t.objectStore("chunks").getAll(IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER])));
    if (chunks.reduce((sum, chunk) => sum + chunk.pcm.length, 0) !== record.bytes) { await completed; throw new Error("voice recording incomplete"); }
    // Atomically freeze a crashed/suspended writer before reading its prefix. A late append must fail.
    if (record.recording) t.objectStore("recordings").put({ ...record, complete: false, recording: false, leaseUntil: 0 });
    await completed; return chunks.map((chunk) => chunk.pcm);
  }
  async handoff(id: string, text: string, baseText?: string): Promise<VoiceRecording> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const record = await result<VoiceRecording | undefined>(t.objectStore("recordings").get(id));
    if (!record || record.recording === true) { await completed; throw new Error("voice recording unavailable"); }
    const next = { ...record, text, baseText, bytes: 0 };
    t.objectStore("recordings").put(next);
    t.objectStore("chunks").delete(IDBKeyRange.bound([id, 0], [id, Number.MAX_SAFE_INTEGER]));
    await completed; return next;
  }
  async remove(id: string): Promise<void> {
    const t = await this.transaction("readwrite"), completed = done(t);
    const record = await result<VoiceRecording | undefined>(t.objectStore("recordings").get(id));
    if (record && voiceRecordingActive(record, this.now())) { await completed; throw new Error("voice recording active"); }
    erase(t, id); await completed;
  }
}
export async function clearVoiceRecordings(): Promise<void> {
  epoch++;
  // An old open() completion cannot recreate content: every writer checks its epoch after await.
  if (typeof indexedDB === "undefined") return;
  const db = await open(), t = db.transaction(["recordings", "chunks"], "readwrite"), completed = done(t);
  t.objectStore("recordings").clear(); t.objectStore("chunks").clear(); await completed;
}

/** Serial writer with a bounded backlog; failure stops capture instead of claiming audio was saved. */
export class VoiceJournal {
  private readonly record: Promise<VoiceRecording>;
  private queue: Promise<void>;
  private index = 0;
  private queued = 0;
  failed = false;
  constructor(private readonly store: VoiceRecordingStore, private readonly scope: string, onFailure: () => void) {
    this.record = store.begin(scope);
    this.queue = this.record.then(() => {});
    void this.queue.catch(() => { this.failed = true; onFailure(); });
    this.failure = onFailure;
  }
  private readonly failure: () => void;
  append(pcm: Uint8Array): void {
    if (this.failed) return;
    if (this.queued + pcm.length > 512 * 1024) { this.failed = true; this.failure(); return; }
    const index = this.index++, copy = pcm.slice(); this.queued += copy.length;
    this.queue = this.queue.then(async () => { const record = await this.record; await this.store.append(record.id, index, copy); })
      .finally(() => { this.queued -= copy.length; });
    void this.queue.catch(() => { if (!this.failed) { this.failed = true; this.failure(); } });
  }
  async seal(complete: boolean, limited = false): Promise<VoiceRecording> {
    await this.queue.catch(() => {});
    const record = await this.record;
    return this.store.seal(record.id, complete && !this.failed, limited);
  }
  async discard(): Promise<void> {
    await this.queue.catch(() => {});
    const record = await this.record.catch(() => null);
    if (record) { await this.store.seal(record.id, false); await this.store.remove(record.id); }
  }
}
