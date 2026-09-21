import { encodeRequest, encodeServerError, parseMessage, serverEventFrom } from "./jsonrpc";
import { clientCapabilities, WEB_TUNNEL_METHODS } from "./params";
import type {
  HandledServerRequestMethod,
  JsonObject,
  JsonValue,
  OpenRequestsSnapshot,
  ServerEvent,
  ServerRequest,
} from "./types";

// One Hermes WebSocket (through the Gateway device route). Port of the per-socket parts of
// android data/network/HermesGatewayClient.kt; reconnection and backoff belong to the UI layer.

/** Structural subset of the browser WebSocket, so tests can inject a fake. */
export interface WebSocketLike {
  readonly readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: { code: number; reason: string; wasClean?: boolean }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
}

export type WebSocketFactory = (url: string) => WebSocketLike;

export type HermesSocketErrorKind =
  | "rpc" // the far end answered with a JSON-RPC error object; `code` is set
  | "handshake-timeout" // `gateway.ready` never arrived, so the call was never sent (HR-CONN-003)
  | "timeout" // sent, but unanswered within the per-call timeout (HR-RPC-002)
  | "closed" // the socket closed before an answer (HR-CONN-004)
  | "not-connected" // call() after close, or send() failed
  | "unsupported"; // this browser has no WebSocket

export class HermesSocketError extends Error {
  constructor(
    readonly kind: HermesSocketErrorKind,
    message: string,
    readonly method?: string,
    /** JSON-RPC error code when kind === "rpc" (e.g. 4000, 4001, 4007, 4009, 4090, -32001, -32601, 5028). */
    readonly code?: number,
    readonly data?: JsonValue,
  ) {
    super(message);
    this.name = "HermesSocketError";
  }
}

export type ServerRequestsState = "pending" | "advertised" | "unsupported";

export interface ClosedInfo {
  code: number;
  reason: string;
  /** Who ended it: our close(), the handshake watchdog, or the far end / network. */
  cause: "client" | "handshake-timeout" | "remote";
}

export interface HermesSocketEvents {
  ready: () => void;
  event: (event: ServerEvent) => void;
  "server-request": (request: ServerRequest) => void;
  "open-requests": (snapshot: OpenRequestsSnapshot) => void;
  capabilities: (state: ServerRequestsState) => void;
  closed: (info: ClosedInfo) => void;
}

export interface HermesSocketOptions {
  url: string;
  factory?: WebSocketFactory;
  /** How long a call waits for `gateway.ready` before failing with handshake-timeout. */
  readyTimeoutMs?: number;
  /** Per-call answer timeout. */
  rpcTimeoutMs?: number;
  /** A socket still without `gateway.ready` after this long is closed. 0 disables. Keep above readyTimeoutMs. */
  handshakeTimeoutMs?: number;
  /** Methods call() may send; others fail locally like the Gateway's 4403. null = no restriction. */
  allowedMethods?: ReadonlySet<string> | null;
}

/** The Gateway's in-band refusal of a method not open to browsers. */
export const WEB_METHOD_REFUSED = 4403;

const HANDLED: ReadonlySet<string> = new Set<HandledServerRequestMethod>(["approval", "clarify"]);
export const METHOD_NOT_FOUND = -32601;
const CAPABILITIES_METHOD = "client.capabilities";
const RESUME_METHOD = "session.resume";
const WS_OPEN = 1;

interface PendingCall {
  method: string;
  resolve: (value: JsonValue) => void;
  reject: (error: HermesSocketError) => void;
  timer: ReturnType<typeof setTimeout> | null;
}

export function defaultWebSocketFactory(url: string): WebSocketLike {
  if (typeof WebSocket === "undefined") {
    throw new HermesSocketError("unsupported", "WebSocket is not available in this browser");
  }
  return new WebSocket(url) as unknown as WebSocketLike;
}

export class HermesSocket {
  private ws: WebSocketLike | null = null;
  private state: "idle" | "connecting" | "ready" | "closed" = "idle";
  private nextId = 1;
  private readonly pending = new Map<number, PendingCall>();
  private readyWaiters: Array<{ resolve: () => void; reject: (e: HermesSocketError) => void }> = [];
  /**
   * Server requests received while a `session.resume` is in flight, per resume call id. Upstream
   * snapshots `open_requests` before its worker writes the answer and registering a request does
   * not take the resume lock, so a question can arrive BEFORE the answer that omits it — it is open
   * all the same and the snapshot must not prune it (Android `resumeWindows`).
   */
  private readonly resumeWindows = new Map<number, Set<string>>();
  private handshakeTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly listeners: { [K in keyof HermesSocketEvents]: Set<HermesSocketEvents[K]> } = {
    ready: new Set(),
    event: new Set(),
    "server-request": new Set(),
    "open-requests": new Set(),
    capabilities: new Set(),
    closed: new Set(),
  };
  private closedInfo: ClosedInfo | null = null;
  private _serverRequests: ServerRequestsState = "pending";

  private readonly factory: WebSocketFactory;
  private readonly readyTimeoutMs: number;
  private readonly rpcTimeoutMs: number;
  private readonly handshakeTimeoutMs: number;
  private readonly allowedMethods: ReadonlySet<string> | null;

  constructor(private readonly options: HermesSocketOptions) {
    this.factory = options.factory ?? defaultWebSocketFactory;
    this.readyTimeoutMs = options.readyTimeoutMs ?? 15_000;
    this.rpcTimeoutMs = options.rpcTimeoutMs ?? 60_000;
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 20_000;
    this.allowedMethods = options.allowedMethods === undefined ? WEB_TUNNEL_METHODS : options.allowedMethods;
  }

  get isReady(): boolean {
    return this.state === "ready";
  }

  get isClosed(): boolean {
    return this.state === "closed";
  }

  /** What this socket's Hermes said to `client.capabilities`. Diagnostics; cards decide per arrival. */
  get serverRequests(): ServerRequestsState {
    return this._serverRequests;
  }

  on<K extends keyof HermesSocketEvents>(type: K, listener: HermesSocketEvents[K]): () => void {
    this.listeners[type].add(listener);
    return () => this.listeners[type].delete(listener);
  }

  /** Opens the socket. Throws HermesSocketError("unsupported") when the browser has no WebSocket. */
  connect(): void {
    if (this.state !== "idle") throw new HermesSocketError("not-connected", "socket already used; create a new one");
    this.state = "connecting";
    const ws = this.factory(this.options.url);
    this.ws = ws;
    ws.onmessage = (ev) => {
      if (typeof ev.data === "string") this.onText(ev.data);
    };
    ws.onclose = (ev) => this.onClosed(ev.code, ev.reason ?? "", "remote");
    ws.onerror = () => {
      /* onclose follows */
    };
    if (this.handshakeTimeoutMs > 0) {
      this.handshakeTimer = setTimeout(() => {
        if (this.state !== "connecting") return;
        try {
          ws.close(4000, "gateway handshake timeout");
        } catch {
          /* already closing */
        }
        this.onClosed(4000, "gateway handshake timeout", "handshake-timeout");
      }, this.handshakeTimeoutMs);
    }
  }

  /** Shut the socket on purpose. Every waiting call fails with kind "closed". */
  close(code = 1000, reason = "client closing"): void {
    if (this.state === "closed") return;
    const ws = this.ws;
    this.onClosed(code, reason, "client");
    try {
      ws?.close(code, reason);
    } catch {
      /* ignore */
    }
  }

  /**
   * Send one RPC. Waits for `gateway.ready` first (up to readyTimeoutMs), so `client.capabilities`
   * is always the first frame Hermes reads on this socket.
   */
  async call<T = JsonValue>(method: string, params: JsonObject, opts: { timeoutMs?: number } = {}): Promise<T> {
    if (this.allowedMethods && !this.allowedMethods.has(method)) {
      throw new HermesSocketError("rpc", `HR-WEB-001 ${method} is not available to the web app`, method, WEB_METHOD_REFUSED, { code: "HR-WEB-001" });
    }
    await this.awaitReady(method);
    return (await this.send(method, params, opts.timeoutMs ?? this.rpcTimeoutMs)) as T;
  }

  private awaitReady(method: string): Promise<void> {
    if (this.state === "ready") return Promise.resolve();
    if (this.state === "closed" || this.state === "idle") {
      return Promise.reject(new HermesSocketError("not-connected", "not connected", method));
    }
    return new Promise<void>((resolve, reject) => {
      const waiter = {
        resolve: () => {
          clearTimeout(timer);
          resolve();
        },
        reject: (e: HermesSocketError) => {
          clearTimeout(timer);
          reject(e);
        },
      };
      const timer = setTimeout(() => {
        this.readyWaiters = this.readyWaiters.filter((w) => w !== waiter);
        reject(new HermesSocketError("handshake-timeout", "gateway readiness timeout", method));
      }, this.readyTimeoutMs);
      this.readyWaiters.push(waiter);
    });
  }

  private send(method: string, params: JsonObject, timeoutMs: number): Promise<JsonValue> {
    const ws = this.ws;
    if (!ws || this.state === "closed" || ws.readyState !== WS_OPEN) {
      return Promise.reject(new HermesSocketError("not-connected", "not connected", method));
    }
    const id = this.nextId++;
    return new Promise<JsonValue>((resolve, reject) => {
      const call: PendingCall = { method, resolve, reject, timer: null };
      // Opened before the request leaves, so no server request can slip in unrecorded.
      if (method === RESUME_METHOD) this.resumeWindows.set(id, new Set());
      this.pending.set(id, call);
      try {
        ws.send(encodeRequest(id, method, params));
      } catch {
        this.settle(id);
        reject(new HermesSocketError("not-connected", "not connected", method));
        return;
      }
      if (timeoutMs > 0) {
        call.timer = setTimeout(() => {
          if (!this.settle(id)) return;
          reject(new HermesSocketError("timeout", "gateway response timeout", method));
        }, timeoutMs);
      }
    });
  }

  /** Remove a pending call and its resume window. Returns the call if it was still pending. */
  private settle(id: number): PendingCall | undefined {
    const call = this.pending.get(id);
    if (!call) return undefined;
    this.pending.delete(id);
    this.resumeWindows.delete(id);
    if (call.timer) clearTimeout(call.timer);
    return call;
  }

  private onText(text: string): void {
    if (this.state === "closed") return;
    for (const msg of parseMessage(text)) {
      switch (msg.kind) {
        case "result": {
          const window = this.resumeWindows.get(msg.id);
          const call = this.settle(msg.id);
          if (call?.method === CAPABILITIES_METHOD) this.setServerRequests("advertised");
          // Before resolving: the snapshot must sit between the frames before and after the answer.
          if (call?.method === RESUME_METHOD) this.onResumeAnswered(msg.result, window ?? new Set());
          call?.resolve(msg.result);
          break;
        }
        case "error": {
          const call = this.settle(msg.id);
          if (!call) break;
          // An older Hermes answers -32601 here: expected, it still asks through events.
          if (call.method === CAPABILITIES_METHOD) this.setServerRequests("unsupported");
          call.reject(new HermesSocketError("rpc", msg.error.message, call.method, msg.error.code, msg.error.data));
          break;
        }
        case "event":
          if (msg.event.type === "gateway.ready") this.onReady();
          this.emit("event", msg.event);
          break;
        case "request":
          this.onServerRequest(msg.id, msg.method, msg.params);
          break;
        case "unreadable":
          break;
      }
    }
  }

  private onReady(): void {
    if (this.handshakeTimer) {
      clearTimeout(this.handshakeTimer);
      this.handshakeTimer = null;
    }
    // Sent before the readiness gate opens, so it is the first frame Hermes reads: a session this
    // socket resumes before advertising would have its questions withdrawn.
    const { method, params } = clientCapabilities();
    this.send(method, params, this.rpcTimeoutMs).catch(() => {
      /* state already recorded on the reader; a timeout leaves it pending */
    });
    const firstReady = this.state !== "ready";
    this.state = "ready";
    const waiters = this.readyWaiters;
    this.readyWaiters = [];
    for (const w of waiters) w.resolve();
    if (firstReady) this.emit("ready");
  }

  /**
   * Approval and clarify become cards; anything else (sudo, secret, vault.*, terminal.read,
   * preview.*, window.read, tour, …) is refused at once with -32601 — upstream reads that as "no
   * handler" and moves on instead of waiting out the deadline (300 s for a prompt).
   */
  private onServerRequest(id: string | number, method: string, params: JsonObject): void {
    if (!HANDLED.has(method)) {
      try {
        this.ws?.send(encodeServerError(id, METHOD_NOT_FOUND, `Hermes Remote has no handler for ${method}`));
      } catch {
        /* socket closing */
      }
      return;
    }
    const request = toServerRequest(String(id), method as HandledServerRequestMethod, params);
    for (const window of this.resumeWindows.values()) window.add(request.id);
    this.emit("server-request", request);
  }

  /**
   * A `session.resume` answer. Only on a connection whose capabilities were accepted: re-deliver
   * each `open_requests` entry as if it had just arrived, then the snapshot of every id still open
   * (listed ones plus those received during the resume). Absent `open_requests` means none.
   */
  private onResumeAnswered(result: JsonValue, arrivedDuringResume: Set<string>): void {
    if (this._serverRequests !== "advertised") return;
    if (typeof result !== "object" || result === null || Array.isArray(result)) return;
    const entries = Array.isArray(result.open_requests) ? result.open_requests : [];
    const listed: string[] = [];
    for (const entry of entries) {
      if (typeof entry !== "object" || entry === null || Array.isArray(entry)) continue;
      const id = typeof entry.id === "string" || typeof entry.id === "number" ? String(entry.id) : null;
      if (id === null) continue;
      listed.push(id);
      const method = typeof entry.method === "string" ? entry.method : null;
      if (method === null || !HANDLED.has(method)) continue;
      const params = typeof entry.params === "object" && entry.params !== null && !Array.isArray(entry.params)
        ? entry.params
        : {};
      this.emit("server-request", { ...toServerRequest(id, method as HandledServerRequestMethod, params), replayed: true });
    }
    const sessionId = typeof result.session_id === "string" ? result.session_id : null;
    if (sessionId === null) return;
    this.emit("open-requests", { sessionId, ids: [...new Set([...listed, ...arrivedDuringResume])] });
  }

  private setServerRequests(state: ServerRequestsState): void {
    this._serverRequests = state;
    this.emit("capabilities", state);
  }

  private onClosed(code: number, reason: string, cause: ClosedInfo["cause"]): void {
    if (this.state === "closed") return;
    this.state = "closed";
    if (this.handshakeTimer) clearTimeout(this.handshakeTimer);
    this.handshakeTimer = null;
    if (this.ws) {
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
    }
    const failure = new HermesSocketError("closed", reason || "closed");
    const waiters = this.readyWaiters;
    this.readyWaiters = [];
    for (const w of waiters) w.reject(failure);
    for (const id of [...this.pending.keys()]) {
      const call = this.settle(id);
      call?.reject(new HermesSocketError("closed", reason || "closed", call.method));
    }
    this.closedInfo = { code, reason, cause };
    this.emit("closed", this.closedInfo);
  }

  /** How the socket ended, once it has. */
  get closeInfo(): ClosedInfo | null {
    return this.closedInfo;
  }

  private emit<K extends keyof HermesSocketEvents>(type: K, ...args: Parameters<HermesSocketEvents[K]>): void {
    for (const listener of [...this.listeners[type]]) {
      try {
        (listener as (...a: Parameters<HermesSocketEvents[K]>) => void)(...args);
      } catch (error) {
        // A listener bug must not kill the reader loop (Android's HG crash class).
        queueMicrotask(() => {
          throw error;
        });
      }
    }
  }
}

/**
 * For clarify the request id also becomes `request_id`: it is what `clarify.lock` expects. For
 * approval the params' own `request_id` is the approval queue's id, a different thing, left alone
 * (`ServerRequests.toEvent`).
 */
function toServerRequest(id: string, method: HandledServerRequestMethod, params: JsonObject): ServerRequest {
  const merged: JsonObject = method === "clarify" ? { ...params, request_id: id } : { ...params };
  const sessionId = serverEventFrom({ type: method, payload: merged }).sessionId;
  return { id, method, params: merged, sessionId };
}
