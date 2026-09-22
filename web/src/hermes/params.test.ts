// @vitest-environment node
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import * as P from "./params";

interface Contract {
  methods: Record<string, { keys: string[] }>;
  absent_upstream: Record<string, { keys: string[] }>;
}

const contract = JSON.parse(
  readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), "../../../docs/hermes-rpc-params.json"), "utf8"),
) as Contract;

function whitelist(method: string): string[] {
  const entry = contract.methods[method] ?? contract.absent_upstream[method];
  if (!entry) throw new Error(`${method} is not in docs/hermes-rpc-params.json`);
  return entry.keys;
}

// Every builder with every optional key set: the widest params each can emit.
const widest: P.RpcCall[] = [
  P.sessionCreate({ profile: "work", cwd: "/Users/x/p" }),
  P.sessionResume("s", { profile: "work" }),
  P.promptSubmit("s", "hi"),
  P.sessionInterrupt("s"),
  P.imageAttach("s", "/tmp/a.png"),
  P.fileAttach("s", "/tmp/a.pdf", "a.pdf"),
  P.clientCapabilities(),
  P.requestAnswer("srq-1", { choice: "once" }),
  P.clarifyLock("srq-1", "q1", "yes"),
  P.approvalRespond("s", "deny"),
  P.clarifyRespond("s", "r", "a", "q1"),
  P.sessionWorkspaceMove("s", "/Users/x/p", "work"),
  P.slashExec("live-1", "/model m --provider p --session"),
  P.configGetReasoning("live-1"),
  P.configSetReasoning("live-1", "high"),
  P.processList("live-1"),
  P.sessionAccess("s", "work", "live-1"),
];

describe("params whitelist (docs/hermes-rpc-params.json)", () => {
  it.each(widest.map((c) => [c.method, c] as const))("%s emits only declared keys", (_m, call) => {
    const allowed = whitelist(call.method);
    for (const key of Object.keys(call.params)) expect(allowed).toContain(key);
  });

  it("covers every builder exported", () => {
    const builders = Object.entries(P).filter(([, v]) => typeof v === "function");
    expect(builders.length).toBe(widest.length);
  });

  it("every method is one the Gateway forwards on a browser WebSocket", () => {
    for (const call of widest) expect(P.WEB_TUNNEL_METHODS.has(call.method)).toBe(true);
  });
});

describe("builders", () => {
  it("omit blank optional keys and always send the hermes_remote source", () => {
    expect(P.sessionCreate().params).toEqual({ source: "hermes_remote" });
    expect(P.sessionCreate({ profile: " ", cwd: null }).params).toEqual({ source: "hermes_remote" });
    expect(P.sessionResume("s").params).toEqual({ session_id: "s", source: "hermes_remote", omit_messages: true });
  });

  it("keeps legacy approval to session_id + choice (no approved flag)", () => {
    expect(P.approvalRespond("s", "always")).toEqual({ method: "approval.respond", params: { session_id: "s", choice: "always" } });
  });

  it("adds question_id to legacy clarify.respond only for a batch lock", () => {
    expect(P.clarifyRespond("s", "r", "").params).toEqual({ session_id: "s", request_id: "r", answer: "" });
    expect(P.clarifyRespond("s", "r", "x", "q2").params.question_id).toBe("q2");
  });

  it("builds new-protocol answers", () => {
    expect(P.requestAnswer("srq-9", { answer: "" })).toEqual({ method: "request.answer", params: { id: "srq-9", result: { answer: "" } } });
    expect(P.clarifyLock("srq-9", "q", "a")).toEqual({ method: "clarify.lock", params: { request_id: "srq-9", question_id: "q", answer: "a" } });
    expect(P.clientCapabilities().params).toEqual({ server_requests: true });
  });
});
