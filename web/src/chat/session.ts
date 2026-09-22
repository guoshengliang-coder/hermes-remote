import { GatewayHttpError, paths, type GatewayClient } from "../api/gateway";
import { toAppError } from "../app/failures";
import { appError, RPC, type AppError } from "../errors";
import { HermesSocket, HermesSocketError, neverSent, type ClosedInfo } from "../hermes/client";
import {
  fileAttach,
  imageAttach,
  promptSubmit,
  sessionCreate,
  sessionInterrupt,
  sessionResume,
} from "../hermes/params";
import { interpretAnswer, type AnswerPlan } from "../hermes/requests";
import type {
  FileAttachResult,
  JsonObject,
  JsonValue,
  ServerEvent,
  SessionCreateResult,
  SessionResumeResult,
} from "../hermes/types";
import type { PendingAttachment } from "./attachments";
import type { ChatAction } from "./model";

// One conversation's live connection: the Hermes WebSocket through the Gateway device route,
// resume/create, submit with the 4001 resume-and-retry, interrupt, question answers, and
// reconnection with backoff while the page is visible (Android HermesGatewayClient +
// ChatViewModel, reduced to what the Web app needs).

export interface ChatSessionOptions {
  client: GatewayClient;
  deviceId: string;
  storedSessionId: string | null;
  /** Folder a NEW chat is created in (the list's project filter); unset = Hermes' launch folder. */
  cwd?: string | null;
  /**
   * The session's Hermes profile when it is not the default one. The list spans every profile, and
   * the Web app never switches the Mac's active profile (that would move the phone too), so resume,
   * history and create name it explicitly.
   */
  profile?: string | null;
  dispatch: (action: ChatAction) => void;
  /** A new chat got its durable id (navigate there, replacing /app/new). */
  onStored?: (storedSessionId: string) => void;
  /** The socket told us the account/installation lost access and a refresh did not fix it. */
  onAuthLost?: () => void;
  socketFactory?: (url: string) => HermesSocket;
}

const BACKOFF_MS = [1000, 2000, 4000, 8000, 15000, 30000];
/** How long a request that never left waits for the next connection before failing after all. */
const RECONNECT_WAIT_MS = 15_000;

export class ChatSession {
  private socket: HermesSocket | null = null;
  private liveId: string | null = null;
  private storedId: string | null;
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;
  private resuming: Promise<string> | null = null;
  private creating: Promise<string> | null = null;
  private droppedBeforeReady = 0;
  private socketWasReady = false;
  private everReady = false;
  private readonly onVisible = () => {
    if (document.visibilityState === "visible") this.reconnectNow();
  };
  private readonly onOnline = () => this.reconnectNow();

  constructor(private readonly o: ChatSessionOptions) {
    this.storedId = o.storedSessionId;
  }

  get storedSessionId(): string | null {
    return this.storedId;
  }

  get liveSessionId(): string | null {
    return this.liveId;
  }

  start(): void {
    document.addEventListener("visibilitychange", this.onVisible);
    window.addEventListener("online", this.onOnline);
    if (this.storedId) void this.loadHistory();
    else this.o.dispatch({ type: "history-missing" });
    this.connect();
  }

  dispose(): void {
    this.disposed = true;
    document.removeEventListener("visibilitychange", this.onVisible);
    window.removeEventListener("online", this.onOnline);
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.socket?.close(1000, "page closed");
    this.socket = null;
  }

  // ---- connection ----

  private connect(): void {
    if (this.disposed) return;
    // Never upgrade while the access cookie is being rotated: the upgrade would carry the old one.
    void this.o.client.settled().then(() => this.open());
  }

  private open(): void {
    if (this.disposed) return;
    const url = paths.deviceWs(this.o.deviceId);
    const socket = this.o.socketFactory ? this.o.socketFactory(url) : new HermesSocket({ url });
    this.socket = socket;
    this.liveId = null;
    // A resume still in flight is not dropped: if it never left the old socket it is sent again on
    // this one (call() retries requests that never left), and the ready handler below joins it.
    this.socketWasReady = false;
    socket.on("event", (event) => this.onEvent(event));
    socket.on("server-request", (request) => {
      if (this.mine(request.sessionId)) this.o.dispatch({ type: "server-request", request });
    });
    // The replayed open requests of a resume carry its new live handle before our resume() promise
    // has recorded it; without this they failed mine() and a pending approval never came back after
    // leaving and reopening the conversation.
    socket.on("resumed", (live) => {
      if (this.resuming) this.liveId = live;
    });
    socket.on("open-requests", (snapshot) => {
      if (this.mine(snapshot.sessionId)) this.o.dispatch({ type: "open-requests", snapshot });
    });
    socket.on("ready", () => {
      const waiting = this.readyWaiters;
      this.readyWaiters = [];
      // After our own resume below has been queued: a retried request then runs on the new handle
      // (or resumes on the 4001 it gets with the old one).
      queueMicrotask(() => waiting.forEach((w) => w()));
      this.attempt = 0;
      this.droppedBeforeReady = 0;
      this.socketWasReady = true;
      this.o.dispatch({ type: "connection", state: "ready" });
      const reconnected = this.everReady;
      this.everReady = true;
      if (this.storedId) {
        this.resume().then(
          // After a reconnect the tail may have moved on while we were away.
          () => (reconnected ? void this.loadHistory() : undefined),
          () => {
            /* notice already dispatched */
          },
        );
      }
    });
    socket.on("closed", (info) => this.onClosed(socket, info));
    try {
      socket.connect();
    } catch (error) {
      this.o.dispatch({ type: "notice", error: toAppError(error) });
    }
  }

  private onClosed(socket: HermesSocket, info: ClosedInfo): void {
    if (socket !== this.socket || this.disposed || info.cause === "client") return;
    this.socket = null;
    this.liveId = null;
    this.o.dispatch({ type: "connection", state: navigator.onLine === false ? "offline" : "reconnecting" });
    if (!this.socketWasReady) this.droppedBeforeReady += 1;
    // 4403: the Gateway ended the socket because access changed (sign-out, revocation, binding).
    // Repeated drops before the handshake can be an upgrade refused for an expired cookie.
    if (info.code === 4403 || this.droppedBeforeReady >= 2) {
      this.o.client.refresh().then(
        () => this.scheduleReconnect(),
        (error: unknown) => {
          if (error instanceof GatewayHttpError && error.status >= 400 && error.status < 500 && error.status !== 429) {
            this.o.onAuthLost?.();
          } else {
            this.scheduleReconnect();
          }
        },
      );
      return;
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.timer) return;
    if (document.visibilityState !== "visible") return; // visibilitychange reconnects
    const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)]!;
    this.attempt += 1;
    this.timer = setTimeout(() => {
      this.timer = null;
      if (!this.socket) this.connect();
    }, delay);
  }

  /** Foreground / network back: reconnect at once instead of waiting out the backoff. */
  reconnectNow(): void {
    if (this.disposed || (this.socket && !this.socket.isClosed)) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.connect();
  }

  private mine(sessionId: string | null): boolean {
    if (sessionId === null) return this.liveId !== null || this.storedId !== null;
    return sessionId === this.liveId || sessionId === this.storedId;
  }

  private onEvent(event: ServerEvent): void {
    if (event.type === "gateway.ready") return;
    if (event.sessionId === null || !this.mine(event.sessionId)) return;
    this.o.dispatch({ type: "event", event });
    if (event.type === "error") {
      const message = typeof event.payload.message === "string" ? event.payload.message : "error event";
      this.o.dispatch({ type: "notice", error: appError("HR-RPC-001", `hermes error event: ${message}`) });
    }
  }

  /**
   * One RPC. A request that never left (the socket closed while it waited for readiness, or none
   * was open) is sent once more on the next connection instead of failing: to the user it is still
   * the same message, and nothing reached Hermes. Anything that did leave fails as it did.
   */
  private async call<T>(method: string, params: JsonObject, timeoutMs?: number): Promise<T> {
    try {
      return await this.callOnce<T>(method, params, timeoutMs);
    } catch (error) {
      if (!neverSent(error) || this.disposed) throw error;
      await this.nextReady(error as HermesSocketError);
      return this.callOnce<T>(method, params, timeoutMs);
    }
  }

  private callOnce<T>(method: string, params: JsonObject, timeoutMs?: number): Promise<T> {
    const socket = this.socket;
    if (!socket || socket.isClosed) {
      return Promise.reject(new HermesSocketError("not-connected", "not connected", method));
    }
    return socket.call<T>(method, params, timeoutMs !== undefined ? { timeoutMs } : {});
  }

  private readyWaiters: Array<() => void> = [];

  /** The next connection's readiness, or the original failure after RECONNECT_WAIT_MS. */
  private nextReady(failure: HermesSocketError): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const done = () => {
        clearTimeout(timer);
        resolve();
      };
      const timer = setTimeout(() => {
        this.readyWaiters = this.readyWaiters.filter((w) => w !== done);
        reject(failure);
      }, RECONNECT_WAIT_MS);
      this.readyWaiters.push(done);
    });
  }

  // ---- history ----

  async loadHistory(): Promise<void> {
    const id = this.storedId;
    if (!id) return;
    try {
      const body = await this.o.client.messages(this.o.deviceId, id, this.o.profile);
      if (this.disposed || id !== this.storedId) return;
      this.o.dispatch({ type: "history", rows: Array.isArray(body?.messages) ? body.messages : [] });
    } catch (error) {
      if (this.disposed) return;
      // A brand-new session has no stored rows yet: 404 is normal, not an error.
      if (error instanceof GatewayHttpError && error.status === 404) {
        this.o.dispatch({ type: "history-missing" });
        return;
      }
      this.o.dispatch({ type: "history-missing" });
      this.o.dispatch({ type: "notice", error: toAppError(error, "history") });
    }
  }

  // ---- resume / create ----

  /** Live handle for the stored session (single flight per socket). */
  resume(force = false): Promise<string> {
    if (!this.storedId) return Promise.reject(new Error("no stored session"));
    if (!force && this.liveId) return Promise.resolve(this.liveId);
    if (!force && this.resuming) return this.resuming;
    const { method, params } = sessionResume(this.storedId, { profile: this.o.profile });
    const run = this.call<SessionResumeResult>(method, params, 60_000).then(
      (result) => {
        const live = typeof result?.session_id === "string" && result.session_id ? result.session_id : this.storedId!;
        this.liveId = live;
        this.resuming = null;
        return live;
      },
      (error: unknown) => {
        this.resuming = null;
        this.o.dispatch(this.noticeFor(error, "resume"));
        throw error;
      },
    );
    this.resuming = run;
    return run;
  }

  private create(): Promise<string> {
    this.creating ??= (async () => {
      const { method, params } = sessionCreate({ cwd: this.o.cwd, profile: this.o.profile });
      try {
        const result = await this.call<SessionCreateResult>(method, params);
        this.liveId = result.session_id;
        this.storedId = result.stored_session_id || result.session_id;
        this.o.onStored?.(this.storedId);
        return this.liveId;
      } finally {
        this.creating = null;
      }
    })();
    return this.creating;
  }

  private async liveHandle(): Promise<string> {
    if (this.liveId) return this.liveId;
    if (this.storedId) return this.resume();
    return this.create();
  }

  /** Run an RPC on the live handle; a 4001 (stale handle) resumes once and retries. */
  private async onLive<T>(build: (liveId: string) => { method: string; params: JsonObject }): Promise<T> {
    const live = await this.liveHandle();
    try {
      const { method, params } = build(live);
      return await this.call<T>(method, params);
    } catch (error) {
      if (error instanceof HermesSocketError && error.kind === "rpc" && error.code === RPC.STALE_SESSION && this.storedId) {
        const fresh = await this.resume(true);
        const { method, params } = build(fresh);
        return await this.call<T>(method, params);
      }
      throw error;
    }
  }

  private noticeFor(error: unknown, context: "submit" | "resume"): ChatAction {
    if (error instanceof HermesSocketError && error.kind === "rpc") {
      if (error.code === RPC.SESSION_NOT_FOUND) return { type: "notice", error: toAppError(error, "generic", context), terminal: true };
    }
    return { type: "notice", error: toAppError(error, "generic", context) };
  }

  // ---- send ----

  /**
   * Upload and attach every pending attachment (images: image.attach; files: file.attach, whose
   * ref_text lines are appended to the prompt — Android's flow), then prompt.submit.
   */
  async send(key: string, text: string, attachments: readonly PendingAttachment[]): Promise<void> {
    try {
      let prompt = text;
      if (attachments.length) {
        await this.liveHandle();
        for (const attachment of attachments) {
          const uploaded = await this.o.client.uploadFile(this.o.deviceId, attachment.name, attachment.file, attachment.mimeType);
          if (!uploaded?.path) throw new Error("upload response contained no path");
          if (attachment.kind === "image") {
            await this.onLive((live) => imageAttach(live, uploaded.path));
          } else {
            const result = await this.onLive<FileAttachResult>((live) => fileAttach(live, uploaded.path, attachment.name));
            if (!result || typeof result.ref_text !== "string") throw new Error("file.attach answered without ref_text");
            prompt += result.ref_text.startsWith("\n") ? result.ref_text : `\n${result.ref_text}`;
          }
        }
      }
      await this.onLive((live) => promptSubmit(live, prompt));
      this.o.dispatch({ type: "user-delivered", key });
    } catch (error) {
      // One place per failure. A session that no longer exists (4007) is terminal for the whole page,
      // so its page notice explains the disabled composer and the bubble is only marked failed. Every
      // other failure, including "owned elsewhere" (4090), stays on the bubble, whose Retry resends it.
      if (error instanceof HermesSocketError && error.kind === "rpc" && error.code === RPC.SESSION_NOT_FOUND) {
        this.o.dispatch({ type: "user-failed", key });
        this.o.dispatch(this.noticeFor(error, "submit"));
      } else {
        this.o.dispatch({ type: "user-failed", key, error: this.submitError(error) });
      }
    }
  }

  private submitError(error: unknown): AppError {
    if (error instanceof HermesSocketError) return toAppError(error, "generic", "submit");
    if (error instanceof GatewayHttpError && error.status === 403) return toAppError(error, "generic", "submit");
    // An upload that failed for any other reason leaves the message unsent: HR-SESS-007.
    const details = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    return appError("HR-SESS-007", details);
  }

  async interrupt(): Promise<void> {
    if (!this.liveId) {
      this.o.dispatch({ type: "interrupted" });
      return;
    }
    try {
      await this.onLive((live) => sessionInterrupt(live));
      this.o.dispatch({ type: "interrupted" });
    } catch (error) {
      this.o.dispatch({ type: "notice", error: toAppError(error) });
    }
  }

  // ---- answers ----

  async answer(plan: AnswerPlan): Promise<void> {
    if (plan.kind === "approval") this.o.dispatch({ type: "questions", action: { type: "approval-settled" } });
    else this.o.dispatch({ type: "questions", action: { type: "clarify-set", card: plan.next.clarify } });
    try {
      const result = await this.call<JsonValue>(plan.rpc.method, plan.rpc.params);
      const outcome = interpretAnswer(plan, result);
      if (outcome.expired && outcome.code) {
        if (plan.kind === "clarify") this.o.dispatch({ type: "questions", action: { type: "clarify-set", card: null } });
        this.o.dispatch({ type: "notice", error: appError(outcome.code) });
      }
    } catch (error) {
      if (plan.kind === "clarify") this.o.dispatch({ type: "questions", action: { type: "clarify-set", card: plan.onFailure.clarify } });
      this.o.dispatch({ type: "notice", error: toAppError(error, "generic", "answer") });
    }
  }

  /** The live handle legacy answers need (approval.respond / clarify.respond). */
  answerSessionId(): string {
    return this.liveId ?? this.storedId ?? "";
  }
}
