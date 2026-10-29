import { Buffer } from "node:buffer";
import { randomUUID } from "node:crypto";

const BLOCK = 32 * 1024;
const LIMIT = 24 * 1024 * 1024;
interface Pending { id: string; value: Buffer; offset: number; sequence: number }

/** Negotiated application fragments let probes and other request flows pass between bulk blocks. */
export class BusinessWire {
  private readonly outgoing = new Map<string, Pending[]>();
  private readonly incoming = new Map<string, { flow: string; parts: Buffer[]; bytes: number; sequence: number; updated: number }>();
  private readonly cancelled = new Map<string, number>();
  private queuedBytes = 0;
  private receivedBytes = 0;
  private timer?: ReturnType<typeof setInterval>;
  private lastFlow?: string;
  private stopped = false;
  private pacingFlow?: string;
  private pacingAbort?: AbortController;
  private readonly abort = new AbortController();
  constructor(private readonly write: (raw: string) => void, private readonly buffered: () => number,
    private readonly failure: () => void, private readonly now = () => performance.now(),
    private readonly pace?: (bytes: number, signal: AbortSignal) => Promise<void>,
    private readonly maxMessageBytes = 20 * 1024 * 1024) {}
  send(raw: string, flow: string): boolean {
    if (this.stopped) return false;
    const value = Buffer.from(raw);
    if (this.queuedBytes + value.length > LIMIT || value.length > this.maxMessageBytes) return false;
    if (value.length <= BLOCK && !this.outgoing.has(flow) && this.buffered() < 128 * 1024) {
      this.write(raw); return true;
    }
    const queue = this.outgoing.get(flow) ?? [];
    if (this.outgoing.size >= 128 && !this.outgoing.has(flow)) return false;
    queue.push({ id: randomUUID(), value, offset: 0, sequence: 0 });
    this.queuedBytes += value.length; this.outgoing.set(flow, queue); this.ensureTimer();
    return true;
  }
  receive(raw: string): string | undefined {
    if (this.stopped) throw new Error("wire_closed");
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || !("type" in value) || value.type !== "transport.fragment") return raw;
    const fragment = value as Record<string, unknown>;
    if (Object.keys(fragment).sort().join(",") !== "data,final,flow,id,sequence,type,version"
      || fragment.version !== 1 || typeof fragment.id !== "string" || !/^[a-f0-9-]{36}$/.test(fragment.id)
      || typeof fragment.flow !== "string" || fragment.flow.length < 1 || fragment.flow.length > 256
      || !Number.isSafeInteger(fragment.sequence) || (fragment.sequence as number) < 0
      || typeof fragment.final !== "boolean" || typeof fragment.data !== "string"
      || fragment.data.length > Math.ceil(BLOCK / 3) * 4 || !/^[A-Za-z0-9+/]+={0,2}$/.test(fragment.data)) throw new Error("invalid_fragment");
    for (const [flow, expires] of this.cancelled) if (expires <= this.now()) this.cancelled.delete(flow);
    if (this.cancelled.has(fragment.flow)) return undefined;
    const bytes = Buffer.from(fragment.data, "base64");
    if (bytes.length > BLOCK || (!fragment.final && bytes.length !== BLOCK)
      || bytes.toString("base64") !== fragment.data) throw new Error("invalid_fragment");
    let entry = this.incoming.get(fragment.id);
    if (!entry) {
      if (fragment.sequence !== 0 || this.incoming.size >= 128) throw new Error("fragment_capacity");
      entry = { flow: fragment.flow, parts: [], bytes: 0, sequence: 0, updated: this.now() }; this.incoming.set(fragment.id, entry);
    }
    if (entry.flow !== fragment.flow || entry.sequence !== fragment.sequence || this.receivedBytes + bytes.length > LIMIT
      || entry.bytes + bytes.length > this.maxMessageBytes) throw new Error("invalid_fragment_sequence");
    entry.parts.push(bytes); entry.bytes += bytes.length; entry.sequence++; entry.updated = this.now(); this.receivedBytes += bytes.length;
    this.ensureTimer();
    if (!fragment.final) return undefined;
    this.incoming.delete(fragment.id); this.receivedBytes -= entry.bytes;
    const result = Buffer.concat(entry.parts).toString("utf8");
    const message = JSON.parse(result) as { type: string; id?: string; requestId?: string };
    if (message?.type === "transport.fragment" || typeof message?.type !== "string" || businessFlow(message) !== entry.flow) throw new Error("invalid_fragment_flow");
    return result;
  }
  cancel(flow: string): void {
    this.cancelled.set(flow, this.now() + 120_000);
    if (this.cancelled.size > 4096) this.cancelled.delete(this.cancelled.keys().next().value!);
    const outgoing = this.outgoing.get(flow);
    if (outgoing) { for (const entry of outgoing) this.queuedBytes -= entry.value.length; this.outgoing.delete(flow); }
    if (this.pacingFlow === flow) this.pacingAbort?.abort();
    for (const [id, entry] of this.incoming) if (entry.flow === flow) { this.receivedBytes -= entry.bytes; this.incoming.delete(id); }
  }
  stop(): void {
    this.stopped = true; if (this.timer) clearInterval(this.timer); this.timer = undefined;
    this.abort.abort();
    this.outgoing.clear(); this.incoming.clear(); this.queuedBytes = 0; this.receivedBytes = 0;
    this.cancelled.clear();
  }
  private ensureTimer(): void { this.timer ??= setInterval(() => this.pump(), 10); }
  private pump(): void {
    try {
      for (const entry of this.incoming.values()) if (this.now() - entry.updated > 75_000) throw new Error("fragment_expired");
      if (this.buffered() >= 128 * 1024) return;
      const flows = [...this.outgoing.keys()].filter((flow) => flow !== this.pacingFlow
        && (!this.pacingFlow || this.outgoing.get(flow)![0]!.value.length <= BLOCK));
      if (!flows.length) return;
      const flow = flows[(flows.indexOf(this.lastFlow ?? "") + 1) % flows.length]!; this.lastFlow = flow;
      const queue = this.outgoing.get(flow)!; const current = queue[0]!;
      const part = current.value.subarray(current.offset, current.offset + BLOCK);
      const raw = current.value.length <= BLOCK ? current.value.toString("utf8") : JSON.stringify({
        type: "transport.fragment", version: 1, id: current.id, flow, sequence: current.sequence,
        final: current.offset + part.length === current.value.length, data: part.toString("base64") });
      const write = () => {
        if (this.stopped || this.outgoing.get(flow) !== queue) return;
        this.write(raw); current.offset += part.length; current.sequence++;
        if (current.value.length <= BLOCK || current.offset === current.value.length) {
          queue.shift(); this.queuedBytes -= current.value.length;
          if (!queue.length) this.outgoing.delete(flow);
        }
      };
      if (this.pace && current.value.length > BLOCK) {
        this.pacingFlow = flow;
        const controller = new AbortController(); this.pacingAbort = controller;
        void this.pace(Buffer.byteLength(raw), AbortSignal.any([this.abort.signal, controller.signal])).then(write).catch(() => {
          if (!this.stopped && !controller.signal.aborted) { this.stop(); this.failure(); }
        }).finally(() => { this.pacingFlow = undefined; this.pacingAbort = undefined; });
      } else write();
    } catch { this.stop(); this.failure(); }
  }
}

export function businessFlow(message: { type: string; id?: string; requestId?: string }): string {
  if (message.type.startsWith("tunnel.ws.")) return `ws:${message.id}`;
  return message.requestId ?? message.id ?? message.type;
}
