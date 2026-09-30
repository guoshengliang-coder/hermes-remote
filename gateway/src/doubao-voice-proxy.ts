import type { AccountWebSocketAccess } from "./app-websocket-authorizer.js";
import type { AccountAccessRevocation } from "./account/postgres-access-revocation-bus.js";
import { RevalidationFailurePolicy } from "./websocket-tunnel-broker.js";
import { randomUUID } from "node:crypto";
import { WebSocket, type RawData } from "ws";

const UPSTREAM_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
const RESOURCE_ID = "volc.seedasr.sauc.duration";
const MAX_SESSIONS = 16;
const MAX_PENDING_BYTES = 512 * 1024;
const MAX_SESSION_MS = 90_000;

/** Relays only binary ASR frames. The provider credential never travels to the phone. */
export class DoubaoVoiceProxy {
  private readonly sessions = new Map<WebSocket, AccountWebSocketAccess | undefined>();

  constructor(
    private readonly apiKey: string | undefined,
    private readonly upstreamUrl = UPSTREAM_URL,
  ) {}

  get available(): boolean { return Boolean(this.apiKey); }
  get atCapacity(): boolean { return this.sessions.size >= MAX_SESSIONS; }

  revoke(event: AccountAccessRevocation): void {
    for (const [socket, access] of this.sessions) {
      if (!access || access.accountId !== event.accountId) continue;
      if (event.kind === "session" && access.sessionId !== event.sessionId) continue;
      if (event.kind === "installation" && access.installationId !== event.installationId) continue;
      if (event.kind === "binding" && access.bindingId !== event.bindingId) continue;
      socket.close(4403, "account authorization changed");
    }
  }

  open(phone: WebSocket, access?: AccountWebSocketAccess, revalidate?: () => Promise<unknown>): void {
    if (!this.apiKey || this.atCapacity) {
      phone.close(1013, "voice unavailable");
      return;
    }
    this.sessions.set(phone, access);
    const pending: Buffer[] = [];
    let pendingBytes = 0;
    let closed = false;
    const provider = new WebSocket(this.upstreamUrl, {
      perMessageDeflate: false,
      handshakeTimeout: 10_000,
      maxPayload: 256 * 1024,
      headers: {
        "X-Api-Key": this.apiKey,
        "X-Api-Resource-Id": RESOURCE_ID,
        "X-Api-Request-Id": randomUUID(),
      },
    });
    const deadline = setTimeout(() => {
      phone.close(1000, "voice limit reached");
      provider.close();
    }, MAX_SESSION_MS);
    deadline.unref();
    const policy = new RevalidationFailurePolicy(3);
    let verifying = false;
    let verifyTimeout: NodeJS.Timeout | undefined;
    const verify = () => {
      if (!revalidate || verifying || closed || phone.readyState !== WebSocket.OPEN) return;
      verifying = true;
      verifyTimeout = setTimeout(() => phone.close(1013, "account service unavailable"), 10_000);
      verifyTimeout.unref();
      void revalidate().then(() => policy.recordSuccess(), (error: unknown) => {
        if (closed) return;
        const result = policy.recordFailure(error);
        if (result.action === "close") phone.close(result.code, result.reason);
      }).finally(() => { if (verifyTimeout) clearTimeout(verifyTimeout); verifyTimeout = undefined; verifying = false; });
    };
    const verification = revalidate ? setInterval(verify, 10_000) : undefined;
    verification?.unref();
    const cleanup = () => {
      if (closed) return;
      closed = true;
      clearTimeout(deadline);
      if (verification) clearInterval(verification);
      if (verifyTimeout) clearTimeout(verifyTimeout);
      pending.length = 0;
      this.sessions.delete(phone);
      if (provider.readyState === WebSocket.OPEN) provider.close();
      else if (provider.readyState === WebSocket.CONNECTING) provider.terminate();
      if (phone.readyState === WebSocket.OPEN) phone.close();
    };
    phone.on("message", (data: RawData, binary: boolean) => {
      if (!binary || closed) { phone.close(1003, "binary frames required"); return; }
      const frame = Buffer.from(data as Buffer);
      if (provider.readyState === WebSocket.OPEN) {
        if (provider.bufferedAmount > MAX_PENDING_BYTES) phone.close(1013, "voice busy");
        else provider.send(frame, { binary: true });
      } else if (provider.readyState === WebSocket.CONNECTING) {
        pendingBytes += frame.length;
        if (pendingBytes > MAX_PENDING_BYTES) phone.close(1009, "voice buffer full");
        else pending.push(frame);
      }
    });
    provider.on("open", () => {
      for (const frame of pending) provider.send(frame, { binary: true });
      pending.length = 0;
    });
    provider.on("message", (data: RawData, binary: boolean) => {
      if (!binary || phone.readyState !== WebSocket.OPEN) return;
      if (phone.bufferedAmount > MAX_PENDING_BYTES) phone.close(1013, "voice busy");
      else phone.send(data, { binary: true });
    });
    provider.on("error", () => { if (phone.readyState === WebSocket.OPEN) phone.close(1011, "voice service failed"); });
    provider.on("close", () => cleanup());
    phone.on("close", cleanup);
    phone.on("error", cleanup);
    verify();
  }
}
