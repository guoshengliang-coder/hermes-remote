/** Evidence from ordinary client traffic only. Never sends a probe or replays a write. */
export type HermesFeature = "chat" | "history" | "images" | "approval" | "clarify" | "cron";
export type CapabilityState = "supported" | "unsupported" | "unknown" | "fault";
export interface HermesCapability {
  feature: HermesFeature;
  state: CapabilityState;
  evidence: "observed_success" | "method_missing" | "observed_fault" | "not_observed";
  checkedAt?: string;
}
export interface HermesCapabilities {
  schema: 1;
  profile: string;
  generation: number;
  features: HermesCapability[];
}
const METHODS: Record<string, HermesFeature> = {
  "prompt.submit": "chat", "session.messages": "history",
  "image.attach": "images", "image.attach_bytes": "images",
  "approval.respond": "approval", "clarify.respond": "clarify", "clarify.lock": "clarify",
};
const FEATURES: HermesFeature[] = ["chat", "history", "images", "approval", "clarify", "cron"];
const PROFILE = /^[\p{L}\p{N}_. -]{1,64}$/u;
const MAX_FRAME_BYTES = 2 * 1024 * 1024;
type Pending = { feature: HermesFeature; profile: string; at: number; generation: number; variant: string };
type Observation = HermesCapability & { at: number };
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function decode(bytes: Buffer): Record<string, unknown> | undefined {
  if (bytes.length > MAX_FRAME_BYTES) return undefined;
  try { const value: unknown = JSON.parse(bytes.toString("utf8")); return object(value) ? value : undefined; }
  catch { return undefined; }
}
function profileName(value: unknown, fallback = "default"): string {
  return typeof value === "string" && PROFILE.test(value) ? value : fallback;
}

export class HermesCapabilityMonitor {
  private generation = 0;
  private observations = new Map<string, Observation>();
  private pending = new Map<string, Pending>();
  private questions = new Map<string, { feature: HermesFeature; profile: string; at: number }>();
  constructor(private readonly now: () => number = Date.now, private readonly lifetimeMs = 5 * 60_000) {}

  reset(): void { this.generation++; this.observations.clear(); this.pending.clear(); this.questions.clear(); }
  close(tunnel: string): void {
    for (const key of this.pending.keys()) if (key.startsWith(tunnel + "\n")) this.pending.delete(key);
    for (const key of this.questions.keys()) if (key.startsWith(tunnel + "\n")) this.questions.delete(key);
  }
  request(tunnel: string, bytes: Buffer, binary: boolean): void {
    if (binary) return;
    const frame = decode(bytes);
    if (!frame || (typeof frame.id !== "string" && typeof frame.id !== "number")
        || typeof frame.method !== "string") return;
    const params = object(frame.params) ? frame.params : {};
    let feature = METHODS[frame.method];
    const question = this.questions.get(tunnel + "\n" + String(params.id ?? params.request_id));
    if (frame.method === "request.answer" && question && this.now() - question.at < 10 * 60_000) {
      feature = question.feature;
    }
    if (!feature) return;
    if (this.pending.size >= 256) this.pending.delete(this.pending.keys().next().value!);
    this.pending.set(tunnel + "\n" + String(frame.id), {
      feature, variant: frame.method, profile: question?.profile ?? profileName(params.profile, "_unscoped"), at: this.now(), generation: this.generation,
    });
  }
  response(tunnel: string, bytes: Buffer, binary: boolean): void {
    if (binary) return;
    const frame = decode(bytes);
    if (frame && (frame.method === "approval" || frame.method === "clarify") && typeof frame.id === "string") {
      const params = object(frame.params) ? frame.params : {};
      if (this.questions.size >= 128) this.questions.delete(this.questions.keys().next().value!);
      this.questions.set(tunnel + "\n" + frame.id, {
        feature: frame.method, profile: profileName(params.profile, "_unscoped"), at: this.now(),
      });
      return;
    }
    if (!frame || (typeof frame.id !== "string" && typeof frame.id !== "number")
        || !("result" in frame || "error" in frame)) return;
    const key = tunnel + "\n" + String(frame.id);
    const pending = this.pending.get(key);
    this.pending.delete(key);
    if (!pending || pending.generation !== this.generation || this.now() - pending.at >= 60_000) return;
    const error = object(frame.error) ? frame.error : undefined;
    const missing = error?.code === -32601;
    const state: CapabilityState = error ? missing ? "unsupported" : "fault" : "supported";
    this.record(pending.profile, pending.feature, pending.variant, state,
      error ? missing ? "method_missing" : "observed_fault" : "observed_success");
  }
  http(method: string, path: string, status: number): void {
    let url: URL;
    try { url = new URL(path, "http://connector.local"); } catch { return; }
    const feature = /^\/api\/sessions\/[^/]+\/messages$/.test(url.pathname) ? "history"
      : url.pathname === "/api/cron/jobs" ? "cron" : undefined;
    // Only reads establish REST availability; write failures remain their operation's error.
    if (!feature || method !== "GET") return;
    const state: CapabilityState = status >= 200 && status < 300 ? "supported"
      : status === 405 || status === 404 && url.pathname === "/api/cron/jobs" ? "unsupported" : "fault";
    this.record(profileName(url.searchParams.get("profile"), "_unscoped"), feature, feature === "history" ? "rest.messages" : "rest.cron", state,
      state === "supported" ? "observed_success" : state === "unsupported" ? "method_missing" : "observed_fault");
  }
  report(profile = "default"): HermesCapabilities {
    const name = profileName(profile);
    return {
      schema: 1, profile: name, generation: this.generation,
      features: FEATURES.map((feature) => {
        const variants = feature === "images" ? ["image.attach", "image.attach_bytes"]
          : feature === "history" ? ["session.messages", "rest.messages"]
          : feature === "approval" ? ["approval.respond", "request.answer"]
          : feature === "clarify" ? ["clarify.respond", "request.answer"]
          : feature === "cron" ? ["rest.cron"] : ["prompt.submit"];
        const observations = variants.map(variant => this.observations.get(name + "\n" + feature + "\n" + variant))
          .filter((row): row is Observation => !!row && this.now() - row.at < this.lifetimeMs);
        const success = observations.find(row => row.state === "supported");
        const fault = observations.find(row => row.state === "fault");
        // A missing method cannot disprove an unobserved alternate transport.
        const found = success ?? fault ?? (observations.length === variants.length ? observations[0] : undefined);
        if (!found || this.now() - found.at >= this.lifetimeMs) {
          return { feature, state: "unknown", evidence: "not_observed" };
        }
        const { at: _, ...safe } = found;
        return safe;
      }),
    };
  }
  private record(profile: string, feature: HermesFeature, variant: string, state: CapabilityState, evidence: HermesCapability["evidence"]): void {
    if (this.observations.size >= 128) this.observations.delete(this.observations.keys().next().value!);
    const at = this.now();
    this.observations.set(profile + "\n" + feature + "\n" + variant, { feature, state, evidence, checkedAt: new Date(at).toISOString(), at });
  }
}
