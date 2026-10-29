import { WebSocket } from "ws";
import { LinkHeartbeat, TRANSPORT_HEADER, encodeWireMessage, parseWireMessage, transportOfferHeader,
  type LinkState, type TransportOffer } from "@hermes-remote/protocol";
import type { AccountConnectorAuthenticator } from "./account-connector.js";
import type { LocalHealthMonitor } from "./local-health-monitor.js";
import { ControlReconnectBackoff } from "./control-reconnect.js";

/** An independently authenticated, small-payload socket. It owns no business/file resources. */
export class ControlLane {
  private socket?: WebSocket;
  private timer?: NodeJS.Timeout;
  private generation = 0;
  private enabled = false;
  private readonly backoff = new ControlReconnectBackoff(5_000);
  constructor(private readonly url: string, private readonly instanceId: string,
    private readonly auth: AccountConnectorAuthenticator, private readonly monitor: LocalHealthMonitor,
    private readonly changed: (state: LinkState, diagnostics?: ReturnType<LinkHeartbeat["health"]["diagnostics"]>) => void,
    private readonly revoked: () => void,
    private readonly cancel: (requestId: string, reason: string) => void) {}
  start(): void { this.enabled = true; if (!this.socket && !this.timer) this.connect(); }
  stop(): void {
    this.enabled = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.socket?.close(1000, "control disabled");
  }
  private connect(): void {
    if (!this.enabled) return;
    this.backoff.beginAttempt();
    const offer: TransportOffer = { version: 1, role: "control", instanceId: this.instanceId, generation: ++this.generation };
    const socket = new WebSocket(this.url, { maxPayload: 16 * 1024, handshakeTimeout: 15_000,
      perMessageDeflate: false, headers: { [TRANSPORT_HEADER]: transportOfferHeader(offer) } });
    this.socket = socket;
    let negotiated = false;
    let ready = false;
    const deadline = setTimeout(() => socket.terminate(), 25_000);
    const heartbeat = new LinkHeartbeat((nonce, done) => socket.ping(nonce, undefined, done), this.changed, () => socket.terminate());
    const send = (value: Parameters<typeof encodeWireMessage>[0]) => {
      if (this.socket === socket && socket.readyState === WebSocket.OPEN && socket.bufferedAmount < 16 * 1024) socket.send(encodeWireMessage(value));
    };
    socket.on("open", () => send(this.auth.identify()));
    socket.on("pong", (data) => { heartbeat.pong(data.toString()); if (heartbeat.health.state === "normal") this.backoff.markPong(); });
    socket.on("message", (data) => {
      try {
        const message = parseWireMessage(data.toString());
        if (message.type === "connector.challenge" && !ready) {
          if (!message.transport || transportOfferHeader(message.transport) !== transportOfferHeader(offer)) {
            // A Gateway rollback: stop this lane. The business handshake will select legacy mode.
            this.enabled = false; socket.close(1000, "capability unavailable"); return;
          }
          negotiated = true; send(this.auth.authenticate(message));
        } else if (message.type === "connector.preflight.request" && negotiated) {
          const health = this.monitor.snapshot();
          send({ type: "connector.preflight.result", version: 2, requestId: message.requestId,
            hermesReachable: health.state === "healthy", health,
            ...(this.monitor.version ? { hermesVersion: this.monitor.version } : {}) });
        } else if (message.type === "connector.ready" && negotiated && !ready) {
          this.auth.requireReady(message);
          if (!message.transport || transportOfferHeader(message.transport) !== transportOfferHeader(offer)) throw new Error("lane mismatch");
          ready = true; clearTimeout(deadline); this.backoff.markReady(); heartbeat.start(); this.changed("normal");
        } else if (ready && message.type === "tunnel.http.cancel") this.cancel(message.requestId, message.reason);
        else socket.close(1008, "invalid control message");
      } catch { socket.close(1008, "invalid control handshake"); }
    });
    socket.on("error", () => {}); // Status uses classified lane state, never raw socket errors.
    socket.on("close", (code) => {
      heartbeat.stop(); clearTimeout(deadline);
      if (this.socket !== socket) return;
      this.socket = undefined; if (this.enabled) this.changed("lost");
      if (code === 4401 || code === 4409) { this.enabled = false; this.revoked(); }
      if (this.enabled) this.timer = setTimeout(() => { this.timer = undefined; this.connect(); }, this.backoff.nextDelay().delayMs);
    });
  }
}
