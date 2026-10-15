import { GatewayHttpError, type GatewayClient, type AccountProfileSnapshot, type AccountProfileOperation } from "../api/gateway";
import { appError, type AppError } from "../errors";
import { toAppError } from "../app/failures";

const DATABASE = "hermes-go.account-profile-sync.v1";
export const PROFILE_SYNC_LOGOUT = "hermes-go.account-profile-sync.logout";
const LOGOUT = PROFILE_SYNC_LOGOUT;
interface Pending {
  generation: string;
  operation: AccountProfileOperation;
  /** Version-1 records remain readable; new records use bytes (WebKit may reject IDB Blob writes). */
  avatar?: Blob;
  avatarBytes?: ArrayBuffer;
  avatarType?: string;
  terminal?: AppError;
}
export interface ProfileSyncStatus { pending: boolean; error: AppError | null }
function revision(value: unknown): value is string {
  return typeof value === "string" && /^(0|[1-9][0-9]{0,19})$/.test(value) && BigInt(value) <= 18446744073709551615n;
}
function validSnapshot(value: AccountProfileSnapshot, owner: string): boolean {
  if (!value || typeof value !== "object") return false;
  const p = value.profile;
  if (p !== null && (!p || typeof p !== "object" || !p.account || typeof p.account !== "object")) return false;
  return value.version === 1 && !value.hasMore && revision(value.revision) && typeof value.cursor === "string" && value.cursor.length <= 512 &&
    (!p || (p.account.id === owner && typeof p.account.displayName === "string" && p.account.displayName.length <= 40 &&
      revision(p.nameRevision) && revision(p.avatarRevision) && BigInt(p.nameRevision) <= BigInt(value.revision) && BigInt(p.avatarRevision) <= BigInt(value.revision)));
}
function generation(): string {
  try { return localStorage.getItem(LOGOUT) ?? "initial"; } catch { return "initial"; }
}
function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 2);
    request.onupgradeneeded = () => {
      for (const name of ["profiles", "pending"]) {
        if (!request.result.objectStoreNames.contains(name)) request.result.createObjectStore(name);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("storage blocked"));
  });
}
async function read<T>(store: string, key: string): Promise<T | undefined> {
  const db = await open();
  try { return await new Promise<T | undefined>((resolve, reject) => {
    const request = db.transaction(store).objectStore(store).get(key);
    request.onsuccess = () => resolve(request.result as T | undefined);
    request.onerror = () => reject(request.error);
  }); } finally { db.close(); }
}
async function write(store: string, key: string, value: unknown, active: () => boolean, add = false): Promise<void> {
  const db = await open();
  try { await new Promise<void>((resolve, reject) => {
    if (!active()) { resolve(); return; }
    const tx = db.transaction(store, "readwrite");
    const records = tx.objectStore(store);
    if (value === undefined) records.delete(key);
    else if (add) records.add(value, key);
    else if (store === "profiles") {
      const incoming = value as { generation: string; snapshot: AccountProfileSnapshot };
      const previous = records.get(key);
      previous.onsuccess = () => {
        if (!active()) return;
        const saved = previous.result as typeof incoming | undefined;
        if (saved?.generation === incoming.generation && revision(saved.snapshot.revision) &&
            BigInt(saved.snapshot.revision) > BigInt(incoming.snapshot.revision)) return;
        records.put(value, key);
      };
    } else records.put(value, key);
    tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error);
  }); } finally { db.close(); }
}

async function replacePending(key: string, expected: string, value: Pending | undefined, active: () => boolean): Promise<void> {
  const db = await open();
  try { await new Promise<void>((resolve, reject) => {
    if (!active()) { resolve(); return; }
    const tx = db.transaction("pending", "readwrite");
    const store = tx.objectStore("pending");
    const get = store.get(key);
    get.onsuccess = () => {
      if (!active() || (get.result as Pending | undefined)?.operation.opId !== expected) return;
      if (value) store.put(value, key); else store.delete(key);
    };
    tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error);
  }); } finally { db.close(); }
}

/** Durable, bounded (one edit/account) queue. No credential, prompt, device or Hermes data is stored. */
export class AccountProfileSyncClient {
  private active = true;
  private readonly key: string;
  private readonly generation = generation();
  private isActive = () => this.active && generation() === this.generation;
  private snapshot?: AccountProfileSnapshot;
  private flight: Promise<void> | null = null;
  private status: ProfileSyncStatus = { pending: false, error: null };
  private timer: ReturnType<typeof setInterval>;
  private readonly wake = () => { if (document.visibilityState !== "hidden") void this.refresh(); };
  private readonly logout = (event: StorageEvent) => { if (event.key === LOGOUT) this.stop(); };
  constructor(private readonly client: GatewayClient, private readonly owner: string,
    private readonly publish: (snapshot: AccountProfileSnapshot, status: ProfileSyncStatus) => void,
    private readonly publishStatus: (status: ProfileSyncStatus) => void,
  ) {
    this.key = JSON.stringify([location.origin, owner]);
    window.addEventListener("online", this.wake);
    document.addEventListener("visibilitychange", this.wake);
    window.addEventListener("storage", this.logout);
    this.timer = setInterval(this.wake, 30_000);
  }
  stop(): void {
    this.active = false;
    clearInterval(this.timer);
    window.removeEventListener("online", this.wake);
    document.removeEventListener("visibilitychange", this.wake);
    window.removeEventListener("storage", this.logout);
  }
  async start(): Promise<void> {
    try {
      const cached = await read<{ generation: string; snapshot: AccountProfileSnapshot }>("profiles", this.key);
      const pending = await read<Pending>("pending", this.key);
      if (!this.isActive()) return;
      if (cached?.generation === this.generation && validSnapshot(cached.snapshot, this.owner) && cached.snapshot.profile) this.apply(cached.snapshot);
      this.report({ pending: pending?.generation === this.generation && !pending.terminal, error: pending?.generation === this.generation ? pending.terminal ?? null : null });
    } catch { /* Read-only sync can still work when storage is unavailable. Edits fail closed. */ }
    await this.refresh();
  }
  refresh(): Promise<void> {
    if (!this.isActive()) return Promise.resolve();
    this.flight ??= this.run().finally(() => { this.flight = null; });
    return this.flight;
  }
  async save(displayName?: string, avatar?: Blob, baseRevision?: string): Promise<boolean> {
    if (!this.isActive()) throw appError("HR-SYNC-007");
    await this.refresh();
    if (!this.isActive() || !this.snapshot?.profile) throw appError("HR-SYNC-007");
    let pending: Pending | undefined;
    try { pending = await read<Pending>("pending", this.key); } catch { throw appError("HR-SYNC-011"); }
    if (pending?.generation === this.generation && !pending.terminal) throw appError("HR-SYNC-010");
    if (pending) await replacePending(this.key, pending.operation.opId, undefined, this.isActive);
    const operation: AccountProfileOperation = { opId: crypto.randomUUID(), issuedAt: new Date().toISOString(),
      baseRevision: baseRevision ?? (avatar ? this.snapshot.profile.avatarRevision : this.snapshot.profile.nameRevision),
      ...(avatar ? {} : { displayName }) };
    try {
      const image = avatar ? { avatarBytes: await avatar.arrayBuffer(), avatarType: avatar.type } : {};
      await write("pending", this.key, { generation: this.generation, operation, ...image }, this.isActive, true);
    }
    catch (e) { throw appError(e instanceof DOMException && e.name === "ConstraintError" ? "HR-SYNC-010" : "HR-SYNC-011"); }
    if (!this.isActive()) throw appError("HR-SYNC-007");
    this.report({ pending: true, error: null });
    await this.refresh();
    if (this.status.error && !this.status.pending) throw this.status.error;
    return !this.status.pending;
  }
  private async run(): Promise<void> {
    try {
      let pending: Pending | undefined;
      try { pending = await read<Pending>("pending", this.key); } catch { /* Pull still works. */ }
      if (!this.isActive()) return;
      if (pending && pending.generation !== this.generation) {
        await replacePending(this.key, pending.operation.opId, undefined, this.isActive);
        pending = undefined;
      }
      if (pending && !pending.terminal) {
        this.report({ pending: true, error: null });
        try {
          // Server binds this expected owner to the cookie principal, even across tab/account races.
          const avatar = pending.avatar ?? (pending.avatarBytes ? new Blob([pending.avatarBytes], { type: pending.avatarType }) : undefined);
          const ack = await this.client.setAccountProfile(this.owner, pending.operation, avatar);
          if (!ack || ack.status !== "accepted" || !revision(ack.revision) || !revision(ack.nameRevision) || !revision(ack.avatarRevision) ||
              BigInt(ack.nameRevision) > BigInt(ack.revision) || BigInt(ack.avatarRevision) > BigInt(ack.revision)) throw appError("HR-WEB-005");
          if (!this.isActive()) return;
          await replacePending(this.key, pending.operation.opId, undefined, this.isActive);
          this.report({ pending: false, error: null });
        } catch (error) {
          if (!this.isActive()) return;
          if (error instanceof GatewayHttpError && error.status >= 400 && error.status < 500 && error.status !== 429) {
            const terminal = toAppError(error, "account");
            pending = { ...pending, terminal };
            await replacePending(this.key, pending.operation.opId, pending, this.isActive);
            this.report({ pending: false, error: terminal });
          } else { this.report({ pending: true, error: toAppError(error, "account") }); }
        }
      }
      let next: AccountProfileSnapshot;
      try { next = await this.client.accountProfile(this.owner, this.snapshot?.cursor); }
      catch (error) {
        if (error instanceof GatewayHttpError && error.code === "HR-SYNC-007") next = await this.client.accountProfile(this.owner);
        else throw error;
      }
      if (!this.isActive()) return;
      if (!validSnapshot(next, this.owner)) throw appError("HR-WEB-005");
      if (this.snapshot && BigInt(next.revision) < BigInt(this.snapshot.revision)) throw appError("HR-SYNC-007");
      if (next.profile) this.apply(next);
      else if (this.snapshot) this.snapshot = { ...this.snapshot, cursor: next.cursor };
      if (!this.status.pending) this.report({ pending: false, error: pending?.terminal ?? null });
      if (this.snapshot) await write("profiles", this.key, { generation: this.generation, snapshot: this.snapshot }, this.isActive).catch(() => undefined);
    } catch (error) { if (this.isActive()) this.report({ ...this.status, error: toAppError(error, "account") }); }
  }
  private apply(snapshot: AccountProfileSnapshot): void {
    if (!this.isActive()) return;
    this.snapshot = snapshot;
    this.publish(snapshot, this.status);
  }
  private report(status: ProfileSyncStatus): void { if (this.isActive()) { this.status = status; this.publishStatus(status); } }
}

/** Called after stopping the mounted writer, before sign-out network I/O; other tabs stop too. */
export async function clearAccountProfileSync(): Promise<void> {
  try { localStorage.setItem(LOGOUT, crypto.randomUUID()); } catch { /* IDB cleanup remains authoritative. */ }
  const db = await open();
  try { await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(["profiles", "pending"], "readwrite");
    tx.objectStore("profiles").clear(); tx.objectStore("pending").clear();
    tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error);
  }); } finally { db.close(); }
}
